#include "camera_stream_manager.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <linux/videodev2.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdint>
#include <ctime>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <unistd.h>

namespace camera_stream_manager
{
    namespace
    {

        static constexpr const char *TAG = "CameraStreamManager";
        static constexpr int PREVIEW_SELECT_TIMEOUT_US = 100000;
        static constexpr int STOP_WAIT_MS = 2500;
        static constexpr int COLOR_FORMAT_YUV420_PLANAR = 19;
        static constexpr int COLOR_FORMAT_YUV420_SEMIPLANAR = 21;

        // Camera /dev/videoX indices – keep in sync with CameraIndex.java.
        static constexpr int CAMERA_VIDEO_INDEX_RIGHT = 14;
        static constexpr int CAMERA_VIDEO_INDEX_FRONT = 15;
        static constexpr int CAMERA_VIDEO_INDEX_LEFT  = 16;
        static constexpr int CAMERA_VIDEO_INDEX_REAR  = 17;

        // The rear camera feed is flipped horizontally to match driver expectations.
        static constexpr int CAMERA_INDEX_REAR = 17;

        void logPrint(int level, const char *fmt, va_list args)
        {
            __android_log_vprint(level, TAG, fmt, args);
        }

        void logi(const char *fmt, ...)
        {
            va_list args;
            va_start(args, fmt);
            logPrint(ANDROID_LOG_INFO, fmt, args);
            va_end(args);
        }

        void logw(const char *fmt, ...)
        {
            va_list args;
            va_start(args, fmt);
            logPrint(ANDROID_LOG_WARN, fmt, args);
            va_end(args);
        }

        void loge(const char *fmt, ...)
        {
            va_list args;
            va_start(args, fmt);
            logPrint(ANDROID_LOG_ERROR, fmt, args);
            va_end(args);
        }

        std::string errnoStr()
        {
            const int err = errno;
            const char *message = std::strerror(err);
            std::ostringstream oss;
            oss << err << " (" << (message ? message : "?") << ")";
            return oss.str();
        }

        std::string fourccToString(__u32 value)
        {
            char text[5];
            text[0] = static_cast<char>(value & 0xFF);
            text[1] = static_cast<char>((value >> 8) & 0xFF);
            text[2] = static_cast<char>((value >> 16) & 0xFF);
            text[3] = static_cast<char>((value >> 24) & 0xFF);
            text[4] = '\0';
            return std::string(text);
        }

        int64_t nowUs()
        {
            using namespace std::chrono;
            return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
        }

        enum class PackedFormat
        {
            UNKNOWN,
            UYVY,
            YUYV
        };

        PackedFormat packedFormatFromFourcc(__u32 fourcc)
        {
            switch (fourcc)
            {
            case V4L2_PIX_FMT_UYVY:
                return PackedFormat::UYVY;
            case V4L2_PIX_FMT_YUYV:
                return PackedFormat::YUYV;
            default:
                return PackedFormat::UNKNOWN;
            }
        }

        int rgbaConversionCode(PackedFormat format)
        {
            switch (format)
            {
            case PackedFormat::UYVY:
                return cv::COLOR_YUV2RGBA_UYVY;
            case PackedFormat::YUYV:
                return cv::COLOR_YUV2RGBA_YUYV;
            default:
                return -1;
            }
        }

        struct MappedBuffer
        {
            void *start = nullptr;
            size_t length = 0;
        };

        void packI420ToNv12(const uint8_t *src, uint8_t *dst, int width, int height)
        {
            const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
            const size_t uvPlaneSize = ySize / 4U;
            const uint8_t *srcY = src;
            const uint8_t *srcU = src + ySize;
            const uint8_t *srcV = srcU + uvPlaneSize;
            std::memcpy(dst, srcY, ySize);
            uint8_t *dstUv = dst + ySize;
            for (size_t i = 0; i < uvPlaneSize; i++)
            {
                dstUv[i * 2U] = srcU[i];
                dstUv[i * 2U + 1U] = srcV[i];
            }
        }

        class RecordingSink
        {
        public:
            RecordingSink(int slot, int videoIndex, std::string outputPath, int requestedWidth,
                          int requestedHeight, int fps, int bitrate, bool flipHorizontal)
                : slot_(slot),
                  videoIndex_(videoIndex),
                  outputPath_(std::move(outputPath)),
                  requestedWidth_(requestedWidth),
                  requestedHeight_(requestedHeight),
                  fps_(fps),
                  bitrate_(bitrate),
                  flipHorizontal_(flipHorizontal)
            {
            }

            ~RecordingSink()
            {
                finalize();
            }

            bool initialize(int srcWidth, int srcHeight)
            {
                recWidth_ = std::min(requestedWidth_, srcWidth);
                // srcHeight_ is double the actual frame height because the V4L2 device
                // stacks two camera inputs vertically in a single UYVY buffer.
                // We only encode the top half (one camera).        recHeight_ = std::min(requestedHeight_, srcHeight / 2);
                if (recWidth_ <= 0 || recHeight_ <= 0 || (recWidth_ % 2) != 0 || (recHeight_ % 2) != 0)
                {
                    loge("slot=%d invalid recording size %dx%d for /dev/video%d", slot_, recWidth_, recHeight_, videoIndex_);
                    return false;
                }

                if (!initializeCodec())
                {
                    return false;
                }

                outFd_ = open(outputPath_.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
                if (outFd_ < 0)
                {
                    loge("slot=%d output open failed: %s", slot_, errnoStr().c_str());
                    finalize();
                    return false;
                }

                muxer_ = AMediaMuxer_new(outFd_, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
                if (!muxer_)
                {
                    loge("slot=%d AMediaMuxer_new failed", slot_);
                    finalize();
                    return false;
                }

                i420Frame_.create(recHeight_ + (recHeight_ / 2), recWidth_, CV_8UC1);
                encoderFrame_.resize(static_cast<size_t>(recWidth_) * static_cast<size_t>(recHeight_) * 3U / 2U);
                frameDurationUs_ = 1000000LL / std::max(1, fps_);
                startUs_ = nowUs();
                nextPtsUs_ = 0;
                frameCount_ = 0;
                logi("slot=%d /dev/video%d encoder color format=%d", slot_, videoIndex_, encoderColorFormat_);
                return true;
            }

            void requestStop()
            {
                stopRequested_.store(true);
            }

            bool isStopRequested() const
            {
                return stopRequested_.load();
            }

            bool isFinalized() const
            {
                return finalized_.load();
            }

            bool waitUntilStopped(int timeoutMs)
            {
                std::unique_lock<std::mutex> lock(waitMutex_);
                return waitCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this]()
                                        { return stopped_; });
            }

            void processFrame(const cv::Mat &rgbaFrame)
            {
                if (isFinalized() || stopRequested_.load())
                {
                    return;
                }

                if (rgbaFrame.empty() || rgbaFrame.cols < recWidth_ || rgbaFrame.rows < recHeight_)
                {
                    return;
                }

                const int64_t elapsedUs = nowUs() - startUs_;
                if (elapsedUs < nextPtsUs_)
                {
                    drainEncoder(0);
                    return;
                }

                cv::Mat rgbaCrop = rgbaFrame(cv::Rect(0, 0, recWidth_, recHeight_));
                const cv::Mat *encodeSource = &rgbaCrop;
                if (flipHorizontal_)
                {
                    flippedRgba_.create(recHeight_, recWidth_, CV_8UC4);
                    cv::flip(rgbaCrop, flippedRgba_, 1);
                    encodeSource = &flippedRgba_;
                }
                cv::cvtColor(*encodeSource, i420Frame_, cv::COLOR_RGBA2YUV_I420);
                if (encoderColorFormat_ == COLOR_FORMAT_YUV420_SEMIPLANAR)
                {
                    packI420ToNv12(i420Frame_.data, encoderFrame_.data(), recWidth_, recHeight_);
                }
                else
                {
                    std::memcpy(encoderFrame_.data(), i420Frame_.data, encoderFrame_.size());
                }

                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    size_t inputSize = 0;
                    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &inputSize);
                    const size_t frameSize = encoderFrame_.size();
                    if (inputBuffer != nullptr && inputSize >= frameSize)
                    {
                        std::memcpy(inputBuffer, encoderFrame_.data(), frameSize);
                        const int64_t pts = frameCount_ * frameDurationUs_;
                        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, frameSize,
                                                         static_cast<uint64_t>(pts), 0) == AMEDIA_OK)
                        {
                            frameCount_++;
                            nextPtsUs_ += frameDurationUs_;
                        }
                        else
                        {
                            logw("slot=%d queueInputBuffer failed", slot_);
                            requestStop();
                        }
                    }
                    else
                    {
                        logw("slot=%d encoder input buffer too small", slot_);
                        requestStop();
                    }
                }

                drainEncoder(0);
            }

            void finalize()
            {
                if (finalized_.exchange(true))
                {
                    return;
                }

                if (codec_ != nullptr)
                {
                    queueEndOfStream();
                    drainEncoder(10000);
                }

                if (muxer_ != nullptr)
                {
                    if (muxerStarted_)
                    {
                        AMediaMuxer_stop(muxer_);
                    }
                    AMediaMuxer_delete(muxer_);
                    muxer_ = nullptr;
                }

                if (outFd_ >= 0)
                {
                    close(outFd_);
                    outFd_ = -1;
                }

                if (codec_ != nullptr)
                {
                    AMediaCodec_stop(codec_);
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }

                {
                    std::lock_guard<std::mutex> lock(waitMutex_);
                    stopped_ = true;
                }
                waitCv_.notify_all();
            }

        private:
            bool initializeCodec()
            {
                const int preferredFormats[] = {
                    COLOR_FORMAT_YUV420_SEMIPLANAR,
                    COLOR_FORMAT_YUV420_PLANAR};
                for (int colorFormat : preferredFormats)
                {
                    if (tryInitializeCodec(colorFormat))
                    {
                        encoderColorFormat_ = colorFormat;
                        return true;
                    }
                    releaseCodecOnly();
                }
                loge("slot=%d no usable encoder color format found", slot_);
                return false;
            }

            bool tryInitializeCodec(int colorFormat)
            {
                codec_ = AMediaCodec_createEncoderByType("video/avc");
                if (!codec_)
                {
                    loge("slot=%d AMediaCodec_createEncoderByType failed", slot_);
                    return false;
                }

                AMediaFormat *format = AMediaFormat_new();
                AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, recWidth_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, recHeight_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrate_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, fps_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);

                media_status_t status = AMediaCodec_configure(
                    codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
                AMediaFormat_delete(format);
                if (status != AMEDIA_OK)
                {
                    logw("slot=%d AMediaCodec_configure failed for color format %d: %d",
                         slot_, colorFormat, static_cast<int>(status));
                    return false;
                }

                status = AMediaCodec_start(codec_);
                if (status != AMEDIA_OK)
                {
                    logw("slot=%d AMediaCodec_start failed for color format %d: %d",
                         slot_, colorFormat, static_cast<int>(status));
                    return false;
                }
                return true;
            }

            void releaseCodecOnly()
            {
                if (codec_ != nullptr)
                {
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }
                encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;
            }

            void queueEndOfStream()
            {
                if (eosQueued_ || codec_ == nullptr)
                {
                    return;
                }
                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, 0,
                                                 static_cast<uint64_t>(frameCount_ * frameDurationUs_),
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eosQueued_ = true;
                }
            }

            void drainEncoder(int timeoutUs)
            {
                if (codec_ == nullptr)
                {
                    return;
                }

                int idleLoops = 0;
                while (idleLoops < 8)
                {
                    AMediaCodecBufferInfo info{};
                    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
                    if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
                    {
                        idleLoops++;
                        if (!eosQueued_)
                        {
                            break;
                        }
                        continue;
                    }
                    if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
                    {
                        if (!muxerStarted_ && muxer_ != nullptr)
                        {
                            AMediaFormat *outputFormat = AMediaCodec_getOutputFormat(codec_);
                            trackIndex_ = AMediaMuxer_addTrack(muxer_, outputFormat);
                            AMediaFormat_delete(outputFormat);
                            if (trackIndex_ >= 0)
                            {
                                AMediaMuxer_start(muxer_);
                                muxerStarted_ = true;
                            }
                        }
                        continue;
                    }
                    if (outputIndex < 0)
                    {
                        break;
                    }

                    if (info.size > 0 && muxerStarted_ && muxer_ != nullptr)
                    {
                        size_t outputSize = 0;
                        uint8_t *outputBuffer =
                            AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(outputIndex), &outputSize);
                        if (outputBuffer != nullptr)
                        {
                            AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(trackIndex_), outputBuffer, &info);
                        }
                    }

                    const bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                    if (isEos)
                    {
                        break;
                    }
                }
            }

            const int slot_;
            const int videoIndex_;
            const std::string outputPath_;
            const int requestedWidth_;
            const int requestedHeight_;
            const int fps_;
            const int bitrate_;
            const bool flipHorizontal_;

            int recWidth_ = 0;
            int recHeight_ = 0;
            int64_t frameDurationUs_ = 0;
            int64_t startUs_ = 0;
            int64_t nextPtsUs_ = 0;
            int64_t frameCount_ = 0;
            int encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;

            AMediaCodec *codec_ = nullptr;
            AMediaMuxer *muxer_ = nullptr;
            int outFd_ = -1;
            ssize_t trackIndex_ = -1;
            bool muxerStarted_ = false;
            bool eosQueued_ = false;

            cv::Mat i420Frame_;
            cv::Mat flippedRgba_;
            std::vector<uint8_t> encoderFrame_;

            std::atomic<bool> stopRequested_{false};
            std::atomic<bool> finalized_{false};
            std::mutex waitMutex_;
            std::condition_variable waitCv_;
            bool stopped_ = false;
        };

        class FrameConsumer
        {
        public:
            virtual ~FrameConsumer() = default;
            virtual void processFrame(const cv::Mat &rgbaFrame) = 0;
            virtual void requestStop() = 0;
            virtual bool isStopRequested() const = 0;
            virtual bool isFinalized() const = 0;
            virtual bool waitUntilStopped(int timeoutMs) = 0;
            virtual void finalize() = 0;
        };

        class SingleCameraRecordingConsumer final : public FrameConsumer
        {
        public:
            explicit SingleCameraRecordingConsumer(std::shared_ptr<RecordingSink> sink) : sink_(std::move(sink))
            {
            }

            void processFrame(const cv::Mat &rgbaFrame) override
            {
                if (sink_ != nullptr)
                {
                    sink_->processFrame(rgbaFrame);
                }
            }

            void requestStop() override
            {
                if (sink_ != nullptr)
                {
                    sink_->requestStop();
                }
            }

            bool isStopRequested() const override
            {
                return sink_ == nullptr || sink_->isStopRequested();
            }

            bool isFinalized() const override
            {
                return sink_ == nullptr || sink_->isFinalized();
            }

            bool waitUntilStopped(int timeoutMs) override
            {
                return sink_ == nullptr || sink_->waitUntilStopped(timeoutMs);
            }

            void finalize() override
            {
                if (sink_ != nullptr)
                {
                    sink_->finalize();
                }
            }

        private:
            std::shared_ptr<RecordingSink> sink_;
        };

        class CombinedRecordingSink : public std::enable_shared_from_this<CombinedRecordingSink>
        {
        public:
            CombinedRecordingSink(std::string outputPath, int cellWidth, int cellHeight, int fps, int bitrate,
                                  std::string signature, bool showSpeed)
                : outputPath_(std::move(outputPath)),
                  cellWidth_(cellWidth),
                  cellHeight_(cellHeight),
                  sideWidth_(cellHeight),
                  sideHeight_(cellWidth),
                  gridWidth_(cellWidth + (cellHeight * 2)),
                  gridHeight_(cellWidth),
                  footerHeight_(80),
                  totalHeight_(gridHeight_ + footerHeight_),
                  centerStackTop_((gridHeight_ - (cellHeight * 2)) / 2),
                  fps_(fps),
                  bitrate_(bitrate),
                  signature_(std::move(signature)),
                  showSpeed_(showSpeed)
            {
            }

            ~CombinedRecordingSink()
            {
                finalize();
            }

            bool initialize()
            {
                if (cellWidth_ <= 0 || cellHeight_ <= 0 || (cellWidth_ % 2) != 0 || (cellHeight_ % 2) != 0)
                {
                    loge("combined invalid cell size %dx%d", cellWidth_, cellHeight_);
                    return false;
                }

                if (!initializeCodec())
                {
                    return false;
                }

                outFd_ = open(outputPath_.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
                if (outFd_ < 0)
                {
                    loge("combined output open failed: %s", errnoStr().c_str());
                    finalize();
                    return false;
                }

                muxer_ = AMediaMuxer_new(outFd_, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
                if (!muxer_)
                {
                    loge("combined AMediaMuxer_new failed");
                    finalize();
                    return false;
                }

                rgbaCanvas_.create(totalHeight_, gridWidth_, CV_8UC4);
                i420Frame_.create(totalHeight_ + (totalHeight_ / 2), gridWidth_, CV_8UC1);
                encoderFrame_.resize(static_cast<size_t>(gridWidth_) * static_cast<size_t>(totalHeight_) * 3U / 2U);
                for (cv::Mat &frame : latestFrames_)
                {
                    frame.create(cellHeight_, cellWidth_, CV_8UC4);
                    frame.setTo(cv::Scalar(0, 0, 0, 255));
                }
                frameDurationUs_ = 1000000LL / std::max(1, fps_);
                startUs_ = nowUs();
                nextPtsUs_ = 0;
                frameCount_ = 0;
                logi("combined encoder color format=%d size=%dx%d", encoderColorFormat_, gridWidth_, totalHeight_);
                return true;
            }

            void requestStop()
            {
                stopRequested_.store(true);
            }

            bool isStopRequested() const
            {
                return stopRequested_.load();
            }

            bool isFinalized() const
            {
                return finalized_.load();
            }

            bool waitUntilStopped(int timeoutMs)
            {
                std::unique_lock<std::mutex> lock(waitMutex_);
                return waitCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this]()
                                        { return stopped_; });
            }

            void updateSpeedKmh(int speedKmh)
            {
                currentSpeedKmh_.store(std::max(0, speedKmh));
            }

            void processSourceFrame(int sourceIndex, const cv::Mat &rgbaFrame)
            {
                if (sourceIndex < 0 || sourceIndex >= 4 || rgbaFrame.empty() || isFinalized() || stopRequested_.load())
                {
                    return;
                }
                if (rgbaFrame.cols < cellWidth_ || rgbaFrame.rows < cellHeight_)
                {
                    return;
                }

                std::lock_guard<std::mutex> lock(encoderMutex_);
                if (isFinalized() || stopRequested_.load())
                {
                    return;
                }

                cv::Mat rgbaCrop = rgbaFrame(cv::Rect(0, 0, cellWidth_, cellHeight_));
                if (sourceIndex == 3)
                {
                    cv::flip(rgbaCrop, latestFrames_[sourceIndex], 1);
                }
                else
                {
                    rgbaCrop.copyTo(latestFrames_[sourceIndex]);
                }

                const int64_t elapsedUs = nowUs() - startUs_;
                if (elapsedUs < nextPtsUs_)
                {
                    drainEncoderLocked(0);
                    return;
                }

                composeCanvasLocked();
                cv::cvtColor(rgbaCanvas_, i420Frame_, cv::COLOR_RGBA2YUV_I420);
                if (encoderColorFormat_ == COLOR_FORMAT_YUV420_SEMIPLANAR)
                {
                    packI420ToNv12(i420Frame_.data, encoderFrame_.data(), gridWidth_, totalHeight_);
                }
                else
                {
                    std::memcpy(encoderFrame_.data(), i420Frame_.data, encoderFrame_.size());
                }

                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    size_t inputSize = 0;
                    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &inputSize);
                    const size_t frameSize = encoderFrame_.size();
                    if (inputBuffer != nullptr && inputSize >= frameSize)
                    {
                        std::memcpy(inputBuffer, encoderFrame_.data(), frameSize);
                        const int64_t pts = frameCount_ * frameDurationUs_;
                        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, frameSize,
                                                         static_cast<uint64_t>(pts), 0) == AMEDIA_OK)
                        {
                            frameCount_++;
                            nextPtsUs_ += frameDurationUs_;
                        }
                        else
                        {
                            logw("combined queueInputBuffer failed");
                            requestStop();
                        }
                    }
                    else
                    {
                        logw("combined encoder input buffer too small");
                        requestStop();
                    }
                }

                drainEncoderLocked(0);
            }

            void finalize()
            {
                if (finalized_.exchange(true))
                {
                    return;
                }

                std::lock_guard<std::mutex> lock(encoderMutex_);
                if (codec_ != nullptr)
                {
                    queueEndOfStreamLocked();
                    drainEncoderLocked(10000);
                }

                if (muxer_ != nullptr)
                {
                    if (muxerStarted_)
                    {
                        AMediaMuxer_stop(muxer_);
                    }
                    AMediaMuxer_delete(muxer_);
                    muxer_ = nullptr;
                }

                if (outFd_ >= 0)
                {
                    close(outFd_);
                    outFd_ = -1;
                }

                if (codec_ != nullptr)
                {
                    AMediaCodec_stop(codec_);
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }

                {
                    std::lock_guard<std::mutex> waitLock(waitMutex_);
                    stopped_ = true;
                }
                waitCv_.notify_all();
            }

        private:
            bool initializeCodec()
            {
                const int preferredFormats[] = {
                    COLOR_FORMAT_YUV420_SEMIPLANAR,
                    COLOR_FORMAT_YUV420_PLANAR};
                for (int colorFormat : preferredFormats)
                {
                    if (tryInitializeCodec(colorFormat))
                    {
                        encoderColorFormat_ = colorFormat;
                        return true;
                    }
                    releaseCodecOnly();
                }
                loge("combined no usable encoder color format found");
                return false;
            }

            bool tryInitializeCodec(int colorFormat)
            {
                codec_ = AMediaCodec_createEncoderByType("video/avc");
                if (!codec_)
                {
                    loge("combined AMediaCodec_createEncoderByType failed");
                    return false;
                }

                AMediaFormat *format = AMediaFormat_new();
                AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, gridWidth_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, totalHeight_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrate_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, fps_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);

                media_status_t status = AMediaCodec_configure(
                    codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
                AMediaFormat_delete(format);
                if (status != AMEDIA_OK)
                {
                    logw("combined AMediaCodec_configure failed for color format %d: %d",
                         colorFormat, static_cast<int>(status));
                    return false;
                }

                status = AMediaCodec_start(codec_);
                if (status != AMEDIA_OK)
                {
                    logw("combined AMediaCodec_start failed for color format %d: %d",
                         colorFormat, static_cast<int>(status));
                    return false;
                }
                return true;
            }

            void releaseCodecOnly()
            {
                if (codec_ != nullptr)
                {
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }
                encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;
            }

            void composeCanvasLocked()
            {
                rgbaCanvas_.setTo(cv::Scalar(0, 0, 0, 255));
                cv::rotate(latestFrames_[2], leftRotated_, cv::ROTATE_90_COUNTERCLOCKWISE);
                cv::rotate(latestFrames_[1], rightRotated_, cv::ROTATE_90_CLOCKWISE);

                leftRotated_.copyTo(rgbaCanvas_(cv::Rect(0, 0, sideWidth_, sideHeight_)));
                latestFrames_[0].copyTo(rgbaCanvas_(cv::Rect(sideWidth_, centerStackTop_, cellWidth_, cellHeight_)));
                latestFrames_[3].copyTo(rgbaCanvas_(cv::Rect(sideWidth_, centerStackTop_ + cellHeight_, cellWidth_, cellHeight_)));
                rightRotated_.copyTo(rgbaCanvas_(cv::Rect(sideWidth_ + cellWidth_, 0, sideWidth_, sideHeight_)));
                drawFooterLocked();
            }

            void drawFooterLocked()
            {
                cv::Rect footerRect(0, gridHeight_, gridWidth_, footerHeight_);
                cv::Mat footer = rgbaCanvas_(footerRect);
                footer.setTo(cv::Scalar(14, 14, 14, 255));
                cv::line(rgbaCanvas_,
                         cv::Point(0, gridHeight_),
                         cv::Point(gridWidth_, gridHeight_),
                         cv::Scalar(70, 70, 70, 255),
                         2,
                         cv::LINE_AA);

                const int baselineY = gridHeight_ + 50;
                const double fontScale = 0.78;
                const int thickness = 2;
                const int marginX = 24;
                const cv::Scalar textColor(235, 235, 235, 255);

                if (!signature_.empty())
                {
                    cv::putText(rgbaCanvas_,
                                signature_,
                                cv::Point(marginX, baselineY),
                                cv::FONT_HERSHEY_SIMPLEX,
                                fontScale,
                                textColor,
                                thickness,
                                cv::LINE_AA);
                }

                std::string rightText = buildRightFooterText();
                int baseline = 0;
                cv::Size textSize = cv::getTextSize(
                    rightText,
                    cv::FONT_HERSHEY_SIMPLEX,
                    fontScale,
                    thickness,
                    &baseline);
                cv::putText(rgbaCanvas_,
                            rightText,
                            cv::Point(std::max(marginX, gridWidth_ - marginX - textSize.width), baselineY),
                            cv::FONT_HERSHEY_SIMPLEX,
                            fontScale,
                            textColor,
                            thickness,
                            cv::LINE_AA);
            }

            std::string buildRightFooterText() const
            {
                std::time_t now = std::time(nullptr);
                std::tm localNow{};
#if defined(_WIN32)
                localtime_s(&localNow, &now);
#else
                localtime_r(&now, &localNow);
#endif
                char dateBuffer[64];
                if (std::strftime(dateBuffer, sizeof(dateBuffer), "%d.%m.%Y %H:%M:%S", &localNow) == 0)
                {
                    std::snprintf(dateBuffer, sizeof(dateBuffer), "--.--.---- --:--:--");
                }

                std::string text(dateBuffer);
                if (showSpeed_)
                {
                    text += "  |  ";
                    text += std::to_string(currentSpeedKmh_.load());
                    text += " km/h";
                }
                return text;
            }

            void queueEndOfStreamLocked()
            {
                if (eosQueued_ || codec_ == nullptr)
                {
                    return;
                }
                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, 0,
                                                 static_cast<uint64_t>(frameCount_ * frameDurationUs_),
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eosQueued_ = true;
                }
            }

            void drainEncoderLocked(int timeoutUs)
            {
                if (codec_ == nullptr)
                {
                    return;
                }

                int idleLoops = 0;
                while (idleLoops < 8)
                {
                    AMediaCodecBufferInfo info{};
                    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
                    if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
                    {
                        idleLoops++;
                        if (!eosQueued_)
                        {
                            break;
                        }
                        continue;
                    }
                    if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
                    {
                        if (!muxerStarted_ && muxer_ != nullptr)
                        {
                            AMediaFormat *outputFormat = AMediaCodec_getOutputFormat(codec_);
                            trackIndex_ = AMediaMuxer_addTrack(muxer_, outputFormat);
                            AMediaFormat_delete(outputFormat);
                            if (trackIndex_ >= 0)
                            {
                                AMediaMuxer_start(muxer_);
                                muxerStarted_ = true;
                            }
                        }
                        continue;
                    }
                    if (outputIndex < 0)
                    {
                        break;
                    }

                    if (info.size > 0 && muxerStarted_ && muxer_ != nullptr)
                    {
                        size_t outputSize = 0;
                        uint8_t *outputBuffer =
                            AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(outputIndex), &outputSize);
                        if (outputBuffer != nullptr)
                        {
                            AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(trackIndex_), outputBuffer, &info);
                        }
                    }

                    const bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                    if (isEos)
                    {
                        break;
                    }
                }
            }

            const std::string outputPath_;
            const int cellWidth_;
            const int cellHeight_;
            const int sideWidth_;
            const int sideHeight_;
            const int gridWidth_;
            const int gridHeight_;
            const int footerHeight_;
            const int totalHeight_;
            const int centerStackTop_;
            const int fps_;
            const int bitrate_;
            const std::string signature_;
            const bool showSpeed_;

            int64_t frameDurationUs_ = 0;
            int64_t startUs_ = 0;
            int64_t nextPtsUs_ = 0;
            int64_t frameCount_ = 0;
            int encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;

            AMediaCodec *codec_ = nullptr;
            AMediaMuxer *muxer_ = nullptr;
            int outFd_ = -1;
            ssize_t trackIndex_ = -1;
            bool muxerStarted_ = false;
            bool eosQueued_ = false;

            std::mutex encoderMutex_;
            cv::Mat rgbaCanvas_;
            cv::Mat i420Frame_;
            std::array<cv::Mat, 4> latestFrames_{};
            cv::Mat leftRotated_;
            cv::Mat rightRotated_;
            std::vector<uint8_t> encoderFrame_;
            std::atomic<int> currentSpeedKmh_{0};

            std::atomic<bool> stopRequested_{false};
            std::atomic<bool> finalized_{false};
            std::mutex waitMutex_;
            std::condition_variable waitCv_;
            bool stopped_ = false;
        };

        class CombinedInputTap final : public FrameConsumer
        {
        public:
            CombinedInputTap(int sourceIndex, std::shared_ptr<CombinedRecordingSink> sink)
                : sourceIndex_(sourceIndex), sink_(std::move(sink))
            {
            }

            void processFrame(const cv::Mat &rgbaFrame) override
            {
                if (sink_ != nullptr)
                {
                    sink_->processSourceFrame(sourceIndex_, rgbaFrame);
                }
            }

            void requestStop() override
            {
                if (sink_ != nullptr)
                {
                    sink_->requestStop();
                }
            }

            bool isStopRequested() const override
            {
                return sink_ == nullptr || sink_->isStopRequested();
            }

            bool isFinalized() const override
            {
                return sink_ == nullptr || sink_->isFinalized();
            }

            bool waitUntilStopped(int timeoutMs) override
            {
                return sink_ == nullptr || sink_->waitUntilStopped(timeoutMs);
            }

            void finalize() override
            {
                if (sink_ != nullptr)
                {
                    sink_->finalize();
                }
            }

        private:
            const int sourceIndex_;
            std::shared_ptr<CombinedRecordingSink> sink_;
        };

        class CameraSession : public std::enable_shared_from_this<CameraSession>
        {
        public:
            explicit CameraSession(int videoIndex) : videoIndex_(videoIndex)
            {
            }

            ~CameraSession()
            {
                detachPreview();
                std::vector<int> consumerIds;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    consumerIds.reserve(consumers_.size());
                    for (const auto &entry : consumers_)
                    {
                        consumerIds.push_back(entry.first);
                    }
                }
                for (int consumerId : consumerIds)
                {
                    stopConsumer(consumerId);
                }
                requestStopAndJoin();
            }

            bool attachPreview(JNIEnv *env, jobject surface)
            {
                return attachPreview(env, surface, 0, 0);
            }

            bool attachPreview(JNIEnv *env, jobject surface, int targetWidth, int targetHeight)
            {
                if (surface == nullptr)
                {
                    return false;
                }

                ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
                if (window == nullptr)
                {
                    logw("ANativeWindow_fromSurface failed for /dev/video%d", videoIndex_);
                    return false;
                }

                bool started = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (previewWindow_ != nullptr)
                    {
                        ANativeWindow_release(previewWindow_);
                    }
                    previewWindow_ = window;
                    previewTargetWidth_ = std::max(0, targetWidth);
                    previewTargetHeight_ = std::max(0, targetHeight);
                    stopRequested_.store(false);
                    started = ensureStartedLocked();
                    if (started)
                    {
                        configurePreviewWindowLocked();
                        // Pre-warm the BufferQueue: the first ANativeWindow_lock calls would
                        // otherwise block while the consumer-side buffer pool is allocated lazily,
                        // stealing budget from the capture thread (which is already saturated by
                        // cvtColor + recording encoder). Three empty posts are enough to settle
                        // the typical pool size on this device.
                        for (int i = 0; i < 3; i++)
                        {
                            ANativeWindow_Buffer warmBuffer{};
                            if (ANativeWindow_lock(previewWindow_, &warmBuffer, nullptr) == 0)
                            {
                                if (warmBuffer.bits != nullptr && warmBuffer.height > 0 && warmBuffer.stride > 0)
                                {
                                    std::memset(warmBuffer.bits, 0,
                                                static_cast<size_t>(warmBuffer.stride) *
                                                    static_cast<size_t>(warmBuffer.height) * 4U);
                                }
                                ANativeWindow_unlockAndPost(previewWindow_);
                            }
                        }
                    }
                }

                if (!started)
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (previewWindow_ == window)
                    {
                        ANativeWindow_release(previewWindow_);
                        previewWindow_ = nullptr;
                        previewTargetWidth_ = 0;
                        previewTargetHeight_ = 0;
                    }
                    else
                    {
                        ANativeWindow_release(window);
                    }
                }
                return started;
            }

            void detachPreview()
            {
                bool shouldStop = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (previewWindow_ != nullptr)
                    {
                        ANativeWindow_release(previewWindow_);
                        previewWindow_ = nullptr;
                        previewTargetWidth_ = 0;
                        previewTargetHeight_ = 0;
                    }
                    shouldStop = consumers_.empty();
                    if (shouldStop)
                    {
                        stopRequested_.store(true);
                    }
                }
                if (shouldStop)
                {
                    requestStopAndJoin();
                }
            }

            bool startRecording(int slot, const std::string &outputPath, int width, int height, int fps, int bitrate)
            {
                auto sink = std::make_shared<RecordingSink>(
                    slot, videoIndex_, outputPath, width, height, fps, bitrate, videoIndex_ == CAMERA_INDEX_REAR);
                auto consumer = std::make_shared<SingleCameraRecordingConsumer>(sink);
                bool started = false;
                bool shouldStopAfterInitFailure = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (!ensureStartedLocked())
                    {
                        return false;
                    }
                    if (consumers_.find(slot) != consumers_.end())
                    {
                        logw("slot=%d already recording on /dev/video%d", slot, videoIndex_);
                        return false;
                    }
                    if (!sink->initialize(srcWidth_, srcHeight_))
                    {
                        shouldStopAfterInitFailure = (previewWindow_ == nullptr && consumers_.empty());
                        if (shouldStopAfterInitFailure)
                        {
                            stopRequested_.store(true);
                        }
                    }
                    else
                    {
                        consumers_[slot] = consumer;
                        stopRequested_.store(false);
                        started = true;
                    }
                }

                if (shouldStopAfterInitFailure)
                {
                    requestStopAndJoin();
                }

                if (started)
                {
                    logi("slot=%d recording attached to /dev/video%d", slot, videoIndex_);
                }
                return started;
            }

            bool attachConsumer(int consumerId, const std::shared_ptr<FrameConsumer> &consumer)
            {
                if (consumer == nullptr)
                {
                    return false;
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (!ensureStartedLocked())
                    {
                        return false;
                    }
                    if (consumers_.find(consumerId) != consumers_.end())
                    {
                        logw("consumer=%d already attached on /dev/video%d", consumerId, videoIndex_);
                        return false;
                    }
                    consumers_[consumerId] = consumer;
                    stopRequested_.store(false);
                }
                return true;
            }

            bool stopRecording(int slot)
            {
                return stopConsumer(slot);
            }

            bool stopConsumer(int consumerId)
            {
                std::shared_ptr<FrameConsumer> consumer;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    auto it = consumers_.find(consumerId);
                    if (it == consumers_.end())
                    {
                        return true;
                    }
                    consumer = it->second;
                }

                consumer->requestStop();
                bool stopped = consumer->waitUntilStopped(STOP_WAIT_MS);

                bool shouldStop = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    consumers_.erase(consumerId); // <- das ist der Fix
                    shouldStop = (previewWindow_ == nullptr && consumers_.empty());
                    if (shouldStop)
                    {
                        stopRequested_.store(true);
                    }
                }
                if (shouldStop)
                {
                    requestStopAndJoin();
                }
                return stopped;
            }

            bool isIdle()
            {
                std::lock_guard<std::mutex> lock(mutex_);
                return previewWindow_ == nullptr && consumers_.empty() && !running_.load();
            }

        private:
            bool hasConsumersLocked() const
            {
                return previewWindow_ != nullptr || !consumers_.empty();
            }

            void configurePreviewWindowLocked()
            {
                if (previewWindow_ == nullptr || cropWidth_ <= 0 || cropHeight_ <= 0)
                {
                    return;
                }
                const int bufferWidth = previewTargetWidth_ > 0 ? previewTargetWidth_ : cropWidth_;
                const int bufferHeight = previewTargetHeight_ > 0 ? previewTargetHeight_ : cropHeight_;
                ANativeWindow_setBuffersGeometry(previewWindow_, bufferWidth, bufferHeight,
                                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
            }

            bool openCaptureLocked()
            {
                if (fd_ >= 0)
                {
                    return true;
                }

                const std::string devicePath = "/dev/video" + std::to_string(videoIndex_);
                fd_ = open(devicePath.c_str(), O_RDWR | O_CLOEXEC);
                if (fd_ < 0)
                {
                    loge("open %s failed: %s", devicePath.c_str(), errnoStr().c_str());
                    return false;
                }

                v4l2_format format{};
                format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                if (ioctl(fd_, VIDIOC_G_FMT, &format) < 0)
                {
                    loge("VIDIOC_G_FMT failed on %s: %s", devicePath.c_str(), errnoStr().c_str());
                    cleanupCaptureLocked();
                    return false;
                }

                pixelFormat_ = format.fmt.pix.pixelformat;
                packedFormat_ = packedFormatFromFourcc(pixelFormat_);
                if (packedFormat_ == PackedFormat::UNKNOWN)
                {
                    loge("/dev/video%d unsupported format %s", videoIndex_, fourccToString(pixelFormat_).c_str());
                    cleanupCaptureLocked();
                    return false;
                }

                srcWidth_ = format.fmt.pix.width;
                srcHeight_ = format.fmt.pix.height;
                srcStrideBytes_ = format.fmt.pix.bytesperline;
                cropWidth_ = srcWidth_;
                cropHeight_ = srcHeight_ / 2;

                v4l2_requestbuffers request{};
                request.count = 4;
                request.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                request.memory = V4L2_MEMORY_MMAP;
                if (ioctl(fd_, VIDIOC_REQBUFS, &request) < 0 || request.count < 1)
                {
                    loge("VIDIOC_REQBUFS failed on /dev/video%d", videoIndex_);
                    cleanupCaptureLocked();
                    return false;
                }

                buffers_.clear();
                buffers_.resize(request.count);
                for (unsigned i = 0; i < request.count; i++)
                {
                    v4l2_buffer buffer{};
                    buffer.type = request.type;
                    buffer.memory = V4L2_MEMORY_MMAP;
                    buffer.index = i;
                    if (ioctl(fd_, VIDIOC_QUERYBUF, &buffer) < 0)
                    {
                        loge("VIDIOC_QUERYBUF failed on /dev/video%d", videoIndex_);
                        cleanupCaptureLocked();
                        return false;
                    }

                    buffers_[i].length = buffer.length;
                    buffers_[i].start = mmap(nullptr, buffer.length, PROT_READ | PROT_WRITE,
                                             MAP_SHARED, fd_, buffer.m.offset);
                    if (buffers_[i].start == MAP_FAILED)
                    {
                        loge("mmap failed on /dev/video%d", videoIndex_);
                        cleanupCaptureLocked();
                        return false;
                    }

                    if (ioctl(fd_, VIDIOC_QBUF, &buffer) < 0)
                    {
                        loge("VIDIOC_QBUF failed on /dev/video%d", videoIndex_);
                        cleanupCaptureLocked();
                        return false;
                    }
                }

                v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                if (ioctl(fd_, VIDIOC_STREAMON, &type) < 0)
                {
                    loge("VIDIOC_STREAMON failed on /dev/video%d", videoIndex_);
                    cleanupCaptureLocked();
                    return false;
                }

                logi("/dev/video%d ready: %dx%d stride=%d fourcc=%s", videoIndex_, srcWidth_, srcHeight_,
                     srcStrideBytes_, fourccToString(pixelFormat_).c_str());
                return true;
            }

            bool ensureStartedLocked()
            {
                if (running_.load())
                {
                    return true;
                }
                if (!openCaptureLocked())
                {
                    return false;
                }
                stopRequested_.store(false);
                running_.store(true);
                auto self = shared_from_this();
                worker_ = std::thread([self]()
                                      { self->threadLoop(); });
                return true;
            }

            void cleanupStoppedConsumers()
            {
                std::vector<std::shared_ptr<FrameConsumer>> finalized;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    for (auto it = consumers_.begin(); it != consumers_.end();)
                    {
                        if (it->second->isStopRequested() || it->second->isFinalized())
                        {
                            finalized.push_back(it->second);
                            it = consumers_.erase(it);
                        }
                        else
                        {
                            ++it;
                        }
                    }
                    if (previewWindow_ == nullptr && consumers_.empty())
                    {
                        stopRequested_.store(true);
                    }
                }

                for (const auto &consumer : finalized)
                {
                    if (consumer != nullptr)
                    {
                        consumer->finalize();
                    }
                }
            }

            void renderPreviewLocked(const cv::Mat &rgbaFrame)
            {
                if (previewWindow_ == nullptr)
                {
                    return;
                }

                const cv::Mat *previewSource = &rgbaFrame;
                if (videoIndex_ == CAMERA_INDEX_REAR)
                {
                    previewFlipScratch_.create(rgbaFrame.rows, rgbaFrame.cols, rgbaFrame.type());
                    cv::flip(rgbaFrame, previewFlipScratch_, 1);
                    previewSource = &previewFlipScratch_;
                }

                ANativeWindow_Buffer outBuffer{};
                if (ANativeWindow_lock(previewWindow_, &outBuffer, nullptr) != 0)
                {
                    return;
                }

                if (outBuffer.bits == nullptr || outBuffer.width <= 0 || outBuffer.height <= 0 ||
                    outBuffer.stride <= 0)
                {
                    ANativeWindow_unlockAndPost(previewWindow_);
                    return;
                }

                const bool scalePreview = previewTargetWidth_ > 0 && previewTargetHeight_ > 0;
                if (scalePreview &&
                    (previewSource->cols != outBuffer.width || previewSource->rows != outBuffer.height))
                {
                    previewResizeScratch_.create(outBuffer.height, outBuffer.width, previewSource->type());
                    cv::resize(*previewSource, previewResizeScratch_, cv::Size(outBuffer.width, outBuffer.height),
                               0, 0, cv::INTER_LINEAR);
                    previewSource = &previewResizeScratch_;
                }

                const int copyWidth = std::min(previewSource->cols, outBuffer.width);
                const int copyHeight = std::min(previewSource->rows, outBuffer.height);
                const uint8_t *src = previewSource->data;
                uint8_t *dst = static_cast<uint8_t *>(outBuffer.bits);
                const int srcStrideBytes = static_cast<int>(previewSource->step[0]);
                const int dstStrideBytes = outBuffer.stride * 4;
                std::memset(dst, 0, static_cast<size_t>(dstStrideBytes) * static_cast<size_t>(outBuffer.height));
                for (int row = 0; row < copyHeight; row++)
                {
                    std::memcpy(dst + row * dstStrideBytes, src + row * srcStrideBytes, static_cast<size_t>(copyWidth) * 4U);
                }

                ANativeWindow_unlockAndPost(previewWindow_);
            }

            void threadLoop()
            {
                while (running_.load())
                {
                    bool shouldExit = false;
                    {
                        std::lock_guard<std::mutex> lock(mutex_);
                        shouldExit = stopRequested_.load() && !hasConsumersLocked();
                    }
                    if (shouldExit)
                    {
                        break;
                    }

                    fd_set readSet;
                    FD_ZERO(&readSet);
                    FD_SET(fd_, &readSet);
                    timeval timeout{0, PREVIEW_SELECT_TIMEOUT_US};
                    const int ready = select(fd_ + 1, &readSet, nullptr, nullptr, &timeout);
                    if (ready <= 0)
                    {
                        cleanupStoppedConsumers();
                        continue;
                    }

                    v4l2_buffer buffer{};
                    buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                    buffer.memory = V4L2_MEMORY_MMAP;
                    if (ioctl(fd_, VIDIOC_DQBUF, &buffer) < 0)
                    {
                        continue;
                    }

                    cv::Mat packedFrame(srcHeight_, srcWidth_, CV_8UC2, buffers_[buffer.index].start, srcStrideBytes_);
                    cv::Mat packedCrop = packedFrame(cv::Rect(0, 0, cropWidth_, cropHeight_));

                    const int conversionCode = rgbaConversionCode(packedFormat_);
                    rgbaScratch_.create(cropHeight_, cropWidth_, CV_8UC4);
                    cv::cvtColor(packedCrop, rgbaScratch_, conversionCode);

                    std::vector<std::shared_ptr<FrameConsumer>> consumers;
                    {
                        std::lock_guard<std::mutex> lock(mutex_);
                        renderPreviewLocked(rgbaScratch_);
                        consumers.reserve(consumers_.size());
                        for (const auto &entry : consumers_)
                        {
                            consumers.push_back(entry.second);
                        }
                    }

                    for (const auto &consumer : consumers)
                    {
                        if (consumer != nullptr)
                        {
                            consumer->processFrame(rgbaScratch_);
                        }
                    }

                    ioctl(fd_, VIDIOC_QBUF, &buffer);
                    cleanupStoppedConsumers();
                }

                cleanupStoppedConsumers();
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    cleanupCaptureLocked();
                    running_.store(false);
                }
            }

            void cleanupCaptureLocked()
            {
                if (fd_ >= 0)
                {
                    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                    ioctl(fd_, VIDIOC_STREAMOFF, &type);
                }

                for (MappedBuffer &buffer : buffers_)
                {
                    if (buffer.start != nullptr && buffer.start != MAP_FAILED)
                    {
                        munmap(buffer.start, buffer.length);
                    }
                    buffer.start = nullptr;
                    buffer.length = 0;
                }
                buffers_.clear();

                if (fd_ >= 0)
                {
                    close(fd_);
                    fd_ = -1;
                }

                rgbaScratch_.release();
                srcWidth_ = 0;
                srcHeight_ = 0;
                srcStrideBytes_ = 0;
                cropWidth_ = 0;
                cropHeight_ = 0;
                pixelFormat_ = 0;
                packedFormat_ = PackedFormat::UNKNOWN;
            }

            void requestStopAndJoin()
            {
                std::thread worker;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    stopRequested_.store(true);
                    worker = std::move(worker_);
                }

                if (worker.joinable())
                {
                    worker.join();
                }

                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    running_.store(false);
                    cleanupCaptureLocked();
                }
            }

            const int videoIndex_;

            std::mutex mutex_;
            int fd_ = -1;
            __u32 pixelFormat_ = 0;
            PackedFormat packedFormat_ = PackedFormat::UNKNOWN;
            int srcWidth_ = 0;
            int srcHeight_ = 0;
            int srcStrideBytes_ = 0;
            int cropWidth_ = 0;
            int cropHeight_ = 0;
            std::vector<MappedBuffer> buffers_;
            ANativeWindow *previewWindow_ = nullptr;
            int previewTargetWidth_ = 0;
            int previewTargetHeight_ = 0;
            std::unordered_map<int, std::shared_ptr<FrameConsumer>> consumers_;
            std::atomic<bool> running_{false};
            std::atomic<bool> stopRequested_{false};
            std::thread worker_;
            cv::Mat rgbaScratch_;
            cv::Mat previewFlipScratch_;
            cv::Mat previewResizeScratch_;
        };

        std::mutex gManagerMutex;
        std::mutex gCombinedMutex;
        std::unordered_map<int, std::shared_ptr<CameraSession>> gSessions;
        std::unordered_map<int, int> gSlotToVideoIndex;
        std::shared_ptr<CombinedRecordingSink> gCombinedSink;
        bool gCombinedRecordingActive = false;

        static constexpr int COMBINED_CONSUMER_ID_BASE = 1000;
        static constexpr std::array<int, 4> COMBINED_VIDEO_INDICES = {
            CAMERA_VIDEO_INDEX_FRONT, CAMERA_VIDEO_INDEX_RIGHT,
            CAMERA_VIDEO_INDEX_LEFT,  CAMERA_VIDEO_INDEX_REAR
        };

        std::shared_ptr<CameraSession> getOrCreateSession(int videoIndex)
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSessions.find(videoIndex);
            if (it != gSessions.end())
            {
                return it->second;
            }
            auto session = std::make_shared<CameraSession>(videoIndex);
            gSessions[videoIndex] = session;
            return session;
        }

        std::shared_ptr<CameraSession> getSession(int videoIndex)
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSessions.find(videoIndex);
            return (it != gSessions.end()) ? it->second : nullptr;
        }

        std::shared_ptr<CameraSession> getSessionForSlot(int slot, int *outVideoIndex = nullptr)
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSlotToVideoIndex.find(slot);
            if (it == gSlotToVideoIndex.end())
            {
                return nullptr;
            }
            if (outVideoIndex != nullptr)
            {
                *outVideoIndex = it->second;
            }
            auto sessionIt = gSessions.find(it->second);
            return (sessionIt != gSessions.end()) ? sessionIt->second : nullptr;
        }

        void eraseSessionIfIdle(int videoIndex, const std::shared_ptr<CameraSession> &session)
        {
            if (session == nullptr || !session->isIdle())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSessions.find(videoIndex);
            if (it != gSessions.end() && it->second == session)
            {
                gSessions.erase(it);
            }
        }

    } // namespace

    bool attachPreview(JNIEnv *env, int videoIndex, jobject surface)
    {
        auto session = getOrCreateSession(videoIndex);
        const bool ok = session->attachPreview(env, surface);
        if (!ok)
        {
            eraseSessionIfIdle(videoIndex, session);
        }
        return ok;
    }

    bool attachPreview(JNIEnv *env, int videoIndex, jobject surface, int targetWidth, int targetHeight)
    {
        auto session = getOrCreateSession(videoIndex);
        const bool ok = session->attachPreview(env, surface, targetWidth, targetHeight);
        if (!ok)
        {
            eraseSessionIfIdle(videoIndex, session);
        }
        return ok;
    }

    void detachPreview(int videoIndex)
    {
        auto session = getSession(videoIndex);
        if (session == nullptr)
        {
            return;
        }
        session->detachPreview();
        eraseSessionIfIdle(videoIndex, session);
    }

    void detachAllPreviews()
    {
        std::vector<std::pair<int, std::shared_ptr<CameraSession>>> sessions;
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            sessions.reserve(gSessions.size());
            for (const auto &entry : gSessions)
            {
                sessions.emplace_back(entry.first, entry.second);
            }
        }
        for (const auto &entry : sessions)
        {
            entry.second->detachPreview();
            eraseSessionIfIdle(entry.first, entry.second);
        }
    }

    bool startRecording(JNIEnv * /*env*/, int slot, int videoIndex, const std::string &outputPath,
                        int width, int height, int fps, int bitrate)
    {
        auto session = getOrCreateSession(videoIndex);
        if (!session->startRecording(slot, outputPath, width, height, fps, bitrate))
        {
            eraseSessionIfIdle(videoIndex, session);
            return false;
        }

        std::lock_guard<std::mutex> lock(gManagerMutex);
        gSlotToVideoIndex[slot] = videoIndex;
        return true;
    }

    bool startCombinedRecording(JNIEnv * /*env*/, const std::string &outputPath,
                                int cellWidth, int cellHeight, int fps, int bitrate,
                                const std::string &signature, bool showSpeed)
    {
        std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
        std::shared_ptr<CombinedRecordingSink> sink;
        if (gCombinedRecordingActive)
        {
            logw("combined recording already active");
            return false;
        }

        sink = std::make_shared<CombinedRecordingSink>(
            outputPath,
            cellWidth,
            cellHeight,
            fps,
            bitrate,
            signature,
            showSpeed);
        if (!sink->initialize())
        {
            return false;
        }

        std::vector<std::pair<int, std::shared_ptr<CameraSession>>> attached;
        std::vector<int> attachedConsumerIds;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            const int consumerId = COMBINED_CONSUMER_ID_BASE + static_cast<int>(i);
            auto session = getOrCreateSession(videoIndex);
            auto tap = std::make_shared<CombinedInputTap>(static_cast<int>(i), sink);
            if (!session->attachConsumer(consumerId, tap))
            {
                sink->requestStop();
                for (size_t j = 0; j < attached.size(); j++)
                {
                    attached[j].second->stopConsumer(attachedConsumerIds[j]);
                    eraseSessionIfIdle(attached[j].first, attached[j].second);
                }
                sink->finalize();
                return false;
            }
            attached.emplace_back(videoIndex, session);
            attachedConsumerIds.push_back(consumerId);
        }

        gCombinedSink = sink;
        gCombinedRecordingActive = true;
        logi("combined recording attached to all 4 cameras");
        return true;
    }

    bool stopRecording(int slot)
    {
        int videoIndex = -1;
        auto session = getSessionForSlot(slot, &videoIndex);
        if (session == nullptr)
        {
            return true;
        }

        bool stopped = session->stopRecording(slot);
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            gSlotToVideoIndex.erase(slot);
        }
        eraseSessionIfIdle(videoIndex, session);
        return stopped;
    }

    bool stopCombinedRecording()
    {
        std::shared_ptr<CombinedRecordingSink> sink;
        {
            std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
            if (!gCombinedRecordingActive || gCombinedSink == nullptr)
            {
                return true;
            }
            sink = gCombinedSink;
            gCombinedSink.reset();
            gCombinedRecordingActive = false;
        }

        sink->requestStop();
        bool allConsumersStopped = true;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            auto session = getSession(videoIndex);
            if (session == nullptr)
            {
                continue;
            }
            if (!session->stopConsumer(COMBINED_CONSUMER_ID_BASE + static_cast<int>(i)))
            {
                allConsumersStopped = false;
            }
            eraseSessionIfIdle(videoIndex, session);
        }

        const bool sinkStopped = sink->waitUntilStopped(STOP_WAIT_MS);
        return allConsumersStopped && sinkStopped;
    }

    void updateCombinedRecordingSpeed(int speedKmh)
    {
        std::shared_ptr<CombinedRecordingSink> sink;
        {
            std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
            sink = gCombinedSink;
        }
        if (sink != nullptr)
        {
            sink->updateSpeedKmh(speedKmh);
        }
    }

} // namespace camera_stream_manager
