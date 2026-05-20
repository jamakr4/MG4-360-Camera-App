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
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdint>
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

namespace camera_stream_manager {
namespace {

static constexpr const char* TAG = "CameraStreamManager";
static constexpr int PREVIEW_SELECT_TIMEOUT_US = 100000;
static constexpr int STOP_WAIT_MS = 2500;

void logPrint(int level, const char* fmt, va_list args) {
    __android_log_vprint(level, TAG, fmt, args);
}

void logi(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logPrint(ANDROID_LOG_INFO, fmt, args);
    va_end(args);
}

void logw(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logPrint(ANDROID_LOG_WARN, fmt, args);
    va_end(args);
}

void loge(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logPrint(ANDROID_LOG_ERROR, fmt, args);
    va_end(args);
}

std::string errnoStr() {
    const int err = errno;
    const char* message = std::strerror(err);
    std::ostringstream oss;
    oss << err << " (" << (message ? message : "?") << ")";
    return oss.str();
}

std::string fourccToString(__u32 value) {
    char text[5];
    text[0] = static_cast<char>(value & 0xFF);
    text[1] = static_cast<char>((value >> 8) & 0xFF);
    text[2] = static_cast<char>((value >> 16) & 0xFF);
    text[3] = static_cast<char>((value >> 24) & 0xFF);
    text[4] = '\0';
    return std::string(text);
}

int64_t nowUs() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

enum class PackedFormat {
    UNKNOWN,
    UYVY,
    YUYV
};

PackedFormat packedFormatFromFourcc(__u32 fourcc) {
    switch (fourcc) {
        case V4L2_PIX_FMT_UYVY:
            return PackedFormat::UYVY;
        case V4L2_PIX_FMT_YUYV:
            return PackedFormat::YUYV;
        default:
            return PackedFormat::UNKNOWN;
    }
}

int rgbaConversionCode(PackedFormat format) {
    switch (format) {
        case PackedFormat::UYVY:
            return cv::COLOR_YUV2RGBA_UYVY;
        case PackedFormat::YUYV:
            return cv::COLOR_YUV2RGBA_YUYV;
        default:
            return -1;
    }
}

struct MappedBuffer {
    void* start = nullptr;
    size_t length = 0;
};

class RecordingSink {
public:
    RecordingSink(int slot, int videoIndex, std::string outputPath, int requestedWidth,
                  int requestedHeight, int fps, int bitrate)
            : slot_(slot),
              videoIndex_(videoIndex),
              outputPath_(std::move(outputPath)),
              requestedWidth_(requestedWidth),
              requestedHeight_(requestedHeight),
              fps_(fps),
              bitrate_(bitrate) {
    }

    ~RecordingSink() {
        finalize();
    }

    bool initialize(int srcWidth, int srcHeight) {
        recWidth_ = std::min(requestedWidth_, srcWidth);
        recHeight_ = std::min(requestedHeight_, srcHeight / 2);
        if (recWidth_ <= 0 || recHeight_ <= 0 || (recWidth_ % 2) != 0 || (recHeight_ % 2) != 0) {
            loge("slot=%d invalid recording size %dx%d for /dev/video%d", slot_, recWidth_, recHeight_, videoIndex_);
            return false;
        }

        codec_ = AMediaCodec_createEncoderByType("video/avc");
        if (!codec_) {
            loge("slot=%d AMediaCodec_createEncoderByType failed", slot_);
            return false;
        }

        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, recWidth_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, recHeight_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrate_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, fps_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 19);

        media_status_t status = AMediaCodec_configure(
                codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        if (status != AMEDIA_OK) {
            loge("slot=%d AMediaCodec_configure failed: %d", slot_, static_cast<int>(status));
            finalize();
            return false;
        }

        status = AMediaCodec_start(codec_);
        if (status != AMEDIA_OK) {
            loge("slot=%d AMediaCodec_start failed: %d", slot_, static_cast<int>(status));
            finalize();
            return false;
        }

        outFd_ = open(outputPath_.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
        if (outFd_ < 0) {
            loge("slot=%d output open failed: %s", slot_, errnoStr().c_str());
            finalize();
            return false;
        }

        muxer_ = AMediaMuxer_new(outFd_, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
        if (!muxer_) {
            loge("slot=%d AMediaMuxer_new failed", slot_);
            finalize();
            return false;
        }

        i420Frame_.create(recHeight_ + (recHeight_ / 2), recWidth_, CV_8UC1);
        frameDurationUs_ = 1000000LL / std::max(1, fps_);
        startUs_ = nowUs();
        nextPtsUs_ = 0;
        frameCount_ = 0;
        return true;
    }

    void requestStop() {
        stopRequested_.store(true);
    }

    bool isStopRequested() const {
        return stopRequested_.load();
    }

    bool isFinalized() const {
        return finalized_.load();
    }

    bool waitUntilStopped(int timeoutMs) {
        std::unique_lock<std::mutex> lock(waitMutex_);
        return waitCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this]() {
            return stopped_;
        });
    }

    void processFrame(const cv::Mat& rgbaFrame) {
        if (isFinalized() || stopRequested_.load()) {
            return;
        }

        if (rgbaFrame.empty() || rgbaFrame.cols < recWidth_ || rgbaFrame.rows < recHeight_) {
            return;
        }

        const int64_t elapsedUs = nowUs() - startUs_;
        if (elapsedUs < nextPtsUs_) {
            drainEncoder(0);
            return;
        }

        cv::Mat rgbaCrop = rgbaFrame(cv::Rect(0, 0, recWidth_, recHeight_));
        cv::cvtColor(rgbaCrop, i420Frame_, cv::COLOR_RGBA2YUV_I420);

        ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
        if (inputIndex >= 0) {
            size_t inputSize = 0;
            uint8_t* inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &inputSize);
            const size_t frameSize = static_cast<size_t>(recWidth_) * static_cast<size_t>(recHeight_) * 3U / 2U;
            if (inputBuffer != nullptr && inputSize >= frameSize) {
                std::memcpy(inputBuffer, i420Frame_.data, frameSize);
                const int64_t pts = frameCount_ * frameDurationUs_;
                if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, frameSize,
                                                 static_cast<uint64_t>(pts), 0) == AMEDIA_OK) {
                    frameCount_++;
                    nextPtsUs_ += frameDurationUs_;
                } else {
                    logw("slot=%d queueInputBuffer failed", slot_);
                    requestStop();
                }
            } else {
                logw("slot=%d encoder input buffer too small", slot_);
                requestStop();
            }
        }

        drainEncoder(0);
    }

    void finalize() {
        if (finalized_.exchange(true)) {
            return;
        }

        if (codec_ != nullptr) {
            queueEndOfStream();
            drainEncoder(10000);
        }

        if (muxer_ != nullptr) {
            if (muxerStarted_) {
                AMediaMuxer_stop(muxer_);
            }
            AMediaMuxer_delete(muxer_);
            muxer_ = nullptr;
        }

        if (outFd_ >= 0) {
            close(outFd_);
            outFd_ = -1;
        }

        if (codec_ != nullptr) {
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
    void queueEndOfStream() {
        if (eosQueued_ || codec_ == nullptr) {
            return;
        }
        ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
        if (inputIndex >= 0) {
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, 0,
                                         static_cast<uint64_t>(frameCount_ * frameDurationUs_),
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            eosQueued_ = true;
        }
    }

    void drainEncoder(int timeoutUs) {
        if (codec_ == nullptr) {
            return;
        }

        int idleLoops = 0;
        while (idleLoops < 8) {
            AMediaCodecBufferInfo info{};
            ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
            if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                idleLoops++;
                if (!eosQueued_) {
                    break;
                }
                continue;
            }
            if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted_ && muxer_ != nullptr) {
                    AMediaFormat* outputFormat = AMediaCodec_getOutputFormat(codec_);
                    trackIndex_ = AMediaMuxer_addTrack(muxer_, outputFormat);
                    AMediaFormat_delete(outputFormat);
                    if (trackIndex_ >= 0) {
                        AMediaMuxer_start(muxer_);
                        muxerStarted_ = true;
                    }
                }
                continue;
            }
            if (outputIndex < 0) {
                break;
            }

            if (info.size > 0 && muxerStarted_ && muxer_ != nullptr) {
                size_t outputSize = 0;
                uint8_t* outputBuffer =
                        AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(outputIndex), &outputSize);
                if (outputBuffer != nullptr) {
                    AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(trackIndex_), outputBuffer, &info);
                }
            }

            const bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
            if (isEos) {
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

    int recWidth_ = 0;
    int recHeight_ = 0;
    int64_t frameDurationUs_ = 0;
    int64_t startUs_ = 0;
    int64_t nextPtsUs_ = 0;
    int64_t frameCount_ = 0;

    AMediaCodec* codec_ = nullptr;
    AMediaMuxer* muxer_ = nullptr;
    int outFd_ = -1;
    ssize_t trackIndex_ = -1;
    bool muxerStarted_ = false;
    bool eosQueued_ = false;

    cv::Mat i420Frame_;

    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> finalized_{false};
    std::mutex waitMutex_;
    std::condition_variable waitCv_;
    bool stopped_ = false;
};

class CameraSession : public std::enable_shared_from_this<CameraSession> {
public:
    explicit CameraSession(int videoIndex) : videoIndex_(videoIndex) {
    }

    ~CameraSession() {
        detachPreview();
        std::vector<int> slots;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            slots.reserve(recordings_.size());
            for (const auto& entry : recordings_) {
                slots.push_back(entry.first);
            }
        }
        for (int slot : slots) {
            stopRecording(slot);
        }
        requestStopAndJoin();
    }

    bool attachPreview(JNIEnv* env, jobject surface) {
        if (surface == nullptr) {
            return false;
        }

        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        if (window == nullptr) {
            logw("ANativeWindow_fromSurface failed for /dev/video%d", videoIndex_);
            return false;
        }

        bool started = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (previewWindow_ != nullptr) {
                ANativeWindow_release(previewWindow_);
            }
            previewWindow_ = window;
            stopRequested_.store(false);
            started = ensureStartedLocked();
            if (started) {
                configurePreviewWindowLocked();
            }
        }

        if (!started) {
            std::lock_guard<std::mutex> lock(mutex_);
            if (previewWindow_ == window) {
                ANativeWindow_release(previewWindow_);
                previewWindow_ = nullptr;
            } else {
                ANativeWindow_release(window);
            }
        }
        return started;
    }

    void detachPreview() {
        bool shouldStop = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (previewWindow_ != nullptr) {
                ANativeWindow_release(previewWindow_);
                previewWindow_ = nullptr;
            }
            shouldStop = recordings_.empty();
            if (shouldStop) {
                stopRequested_.store(true);
            }
        }
        if (shouldStop) {
            requestStopAndJoin();
        }
    }

    bool startRecording(int slot, const std::string& outputPath, int width, int height, int fps, int bitrate) {
        auto sink = std::make_shared<RecordingSink>(slot, videoIndex_, outputPath, width, height, fps, bitrate);
        bool started = false;
        bool shouldStopAfterInitFailure = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!ensureStartedLocked()) {
                return false;
            }
            if (recordings_.find(slot) != recordings_.end()) {
                logw("slot=%d already recording on /dev/video%d", slot, videoIndex_);
                return false;
            }
            if (!sink->initialize(srcWidth_, srcHeight_)) {
                shouldStopAfterInitFailure = (previewWindow_ == nullptr && recordings_.empty());
                if (shouldStopAfterInitFailure) {
                    stopRequested_.store(true);
                }
            } else {
                recordings_[slot] = sink;
                stopRequested_.store(false);
                started = true;
            }
        }

        if (shouldStopAfterInitFailure) {
            requestStopAndJoin();
        }

        if (started) {
            logi("slot=%d recording attached to /dev/video%d", slot, videoIndex_);
        }
        return started;
    }

    void stopRecording(int slot) {
        std::shared_ptr<RecordingSink> sink;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = recordings_.find(slot);
            if (it == recordings_.end()) {
                return;
            }
            sink = it->second;
        }

        sink->requestStop();
        sink->waitUntilStopped(STOP_WAIT_MS);

        bool shouldStop = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            shouldStop = (previewWindow_ == nullptr && recordings_.empty());
            if (shouldStop) {
                stopRequested_.store(true);
            }
        }
        if (shouldStop) {
            requestStopAndJoin();
        }
    }

    bool isIdle() {
        std::lock_guard<std::mutex> lock(mutex_);
        return previewWindow_ == nullptr && recordings_.empty() && !running_.load();
    }

private:
    bool hasConsumersLocked() const {
        return previewWindow_ != nullptr || !recordings_.empty();
    }

    void configurePreviewWindowLocked() {
        if (previewWindow_ == nullptr || cropWidth_ <= 0 || cropHeight_ <= 0) {
            return;
        }
        ANativeWindow_setBuffersGeometry(previewWindow_, cropWidth_, cropHeight_,
                                         AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    }

    bool openCaptureLocked() {
        if (fd_ >= 0) {
            return true;
        }

        const std::string devicePath = "/dev/video" + std::to_string(videoIndex_);
        fd_ = open(devicePath.c_str(), O_RDWR | O_CLOEXEC);
        if (fd_ < 0) {
            loge("open %s failed: %s", devicePath.c_str(), errnoStr().c_str());
            return false;
        }

        v4l2_format format{};
        format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (ioctl(fd_, VIDIOC_G_FMT, &format) < 0) {
            loge("VIDIOC_G_FMT failed on %s: %s", devicePath.c_str(), errnoStr().c_str());
            cleanupCaptureLocked();
            return false;
        }

        pixelFormat_ = format.fmt.pix.pixelformat;
        packedFormat_ = packedFormatFromFourcc(pixelFormat_);
        if (packedFormat_ == PackedFormat::UNKNOWN) {
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
        if (ioctl(fd_, VIDIOC_REQBUFS, &request) < 0 || request.count < 1) {
            loge("VIDIOC_REQBUFS failed on /dev/video%d", videoIndex_);
            cleanupCaptureLocked();
            return false;
        }

        buffers_.clear();
        buffers_.resize(request.count);
        for (unsigned i = 0; i < request.count; i++) {
            v4l2_buffer buffer{};
            buffer.type = request.type;
            buffer.memory = V4L2_MEMORY_MMAP;
            buffer.index = i;
            if (ioctl(fd_, VIDIOC_QUERYBUF, &buffer) < 0) {
                loge("VIDIOC_QUERYBUF failed on /dev/video%d", videoIndex_);
                cleanupCaptureLocked();
                return false;
            }

            buffers_[i].length = buffer.length;
            buffers_[i].start = mmap(nullptr, buffer.length, PROT_READ | PROT_WRITE,
                                     MAP_SHARED, fd_, buffer.m.offset);
            if (buffers_[i].start == MAP_FAILED) {
                loge("mmap failed on /dev/video%d", videoIndex_);
                cleanupCaptureLocked();
                return false;
            }

            if (ioctl(fd_, VIDIOC_QBUF, &buffer) < 0) {
                loge("VIDIOC_QBUF failed on /dev/video%d", videoIndex_);
                cleanupCaptureLocked();
                return false;
            }
        }

        v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (ioctl(fd_, VIDIOC_STREAMON, &type) < 0) {
            loge("VIDIOC_STREAMON failed on /dev/video%d", videoIndex_);
            cleanupCaptureLocked();
            return false;
        }

        logi("/dev/video%d ready: %dx%d stride=%d fourcc=%s", videoIndex_, srcWidth_, srcHeight_,
             srcStrideBytes_, fourccToString(pixelFormat_).c_str());
        return true;
    }

    bool ensureStartedLocked() {
        if (running_.load()) {
            return true;
        }
        if (!openCaptureLocked()) {
            return false;
        }
        stopRequested_.store(false);
        running_.store(true);
        auto self = shared_from_this();
        worker_ = std::thread([self]() {
            self->threadLoop();
        });
        return true;
    }

    void cleanupStoppedRecordings() {
        std::vector<std::pair<int, std::shared_ptr<RecordingSink>>> finalized;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            for (auto it = recordings_.begin(); it != recordings_.end();) {
                if (it->second->isStopRequested() || it->second->isFinalized()) {
                    finalized.emplace_back(it->first, it->second);
                    it = recordings_.erase(it);
                } else {
                    ++it;
                }
            }
            if (previewWindow_ == nullptr && recordings_.empty()) {
                stopRequested_.store(true);
            }
        }

        for (auto& entry : finalized) {
            entry.second->finalize();
        }
    }

    void renderPreviewLocked(const cv::Mat& rgbaFrame) {
        if (previewWindow_ == nullptr) {
            return;
        }

        ANativeWindow_Buffer outBuffer{};
        if (ANativeWindow_lock(previewWindow_, &outBuffer, nullptr) != 0) {
            return;
        }

        const int copyWidth = std::min(rgbaFrame.cols, outBuffer.width);
        const int copyHeight = std::min(rgbaFrame.rows, outBuffer.height);
        const uint8_t* src = rgbaFrame.data;
        uint8_t* dst = static_cast<uint8_t*>(outBuffer.bits);
        const int srcStrideBytes = rgbaFrame.step[0];
        const int dstStrideBytes = outBuffer.stride * 4;
        for (int row = 0; row < copyHeight; row++) {
            std::memcpy(dst + row * dstStrideBytes, src + row * srcStrideBytes, static_cast<size_t>(copyWidth) * 4U);
        }

        ANativeWindow_unlockAndPost(previewWindow_);
    }

    void threadLoop() {
        while (running_.load()) {
            bool shouldExit = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                shouldExit = stopRequested_.load() && !hasConsumersLocked();
            }
            if (shouldExit) {
                break;
            }

            fd_set readSet;
            FD_ZERO(&readSet);
            FD_SET(fd_, &readSet);
            timeval timeout{0, PREVIEW_SELECT_TIMEOUT_US};
            const int ready = select(fd_ + 1, &readSet, nullptr, nullptr, &timeout);
            if (ready <= 0) {
                cleanupStoppedRecordings();
                continue;
            }

            v4l2_buffer buffer{};
            buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
            buffer.memory = V4L2_MEMORY_MMAP;
            if (ioctl(fd_, VIDIOC_DQBUF, &buffer) < 0) {
                continue;
            }

            cv::Mat packedFrame(srcHeight_, srcWidth_, CV_8UC2, buffers_[buffer.index].start, srcStrideBytes_);
            cv::Mat packedCrop = packedFrame(cv::Rect(0, 0, cropWidth_, cropHeight_));

            const int conversionCode = rgbaConversionCode(packedFormat_);
            rgbaScratch_.create(cropHeight_, cropWidth_, CV_8UC4);
            cv::cvtColor(packedCrop, rgbaScratch_, conversionCode);
            if (videoIndex_ == 17) {
                cv::flip(rgbaScratch_, rgbaScratch_, 1);
            }

            std::vector<std::shared_ptr<RecordingSink>> recordings;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                renderPreviewLocked(rgbaScratch_);
                recordings.reserve(recordings_.size());
                for (const auto& entry : recordings_) {
                    recordings.push_back(entry.second);
                }
            }

            for (const auto& sink : recordings) {
                if (sink != nullptr) {
                    sink->processFrame(rgbaScratch_);
                }
            }

            ioctl(fd_, VIDIOC_QBUF, &buffer);
            cleanupStoppedRecordings();
        }

        cleanupStoppedRecordings();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cleanupCaptureLocked();
            running_.store(false);
        }
    }

    void cleanupCaptureLocked() {
        if (fd_ >= 0) {
            v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
            ioctl(fd_, VIDIOC_STREAMOFF, &type);
        }

        for (MappedBuffer& buffer : buffers_) {
            if (buffer.start != nullptr && buffer.start != MAP_FAILED) {
                munmap(buffer.start, buffer.length);
            }
            buffer.start = nullptr;
            buffer.length = 0;
        }
        buffers_.clear();

        if (fd_ >= 0) {
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

    void requestStopAndJoin() {
        std::thread worker;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopRequested_.store(true);
            worker = std::move(worker_);
        }

        if (worker.joinable()) {
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
    ANativeWindow* previewWindow_ = nullptr;
    std::unordered_map<int, std::shared_ptr<RecordingSink>> recordings_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::thread worker_;
    cv::Mat rgbaScratch_;
};

std::mutex gManagerMutex;
std::unordered_map<int, std::shared_ptr<CameraSession>> gSessions;
std::unordered_map<int, int> gSlotToVideoIndex;

std::shared_ptr<CameraSession> getOrCreateSession(int videoIndex) {
    std::lock_guard<std::mutex> lock(gManagerMutex);
    auto it = gSessions.find(videoIndex);
    if (it != gSessions.end()) {
        return it->second;
    }
    auto session = std::make_shared<CameraSession>(videoIndex);
    gSessions[videoIndex] = session;
    return session;
}

std::shared_ptr<CameraSession> getSession(int videoIndex) {
    std::lock_guard<std::mutex> lock(gManagerMutex);
    auto it = gSessions.find(videoIndex);
    return (it != gSessions.end()) ? it->second : nullptr;
}

std::shared_ptr<CameraSession> getSessionForSlot(int slot, int* outVideoIndex = nullptr) {
    std::lock_guard<std::mutex> lock(gManagerMutex);
    auto it = gSlotToVideoIndex.find(slot);
    if (it == gSlotToVideoIndex.end()) {
        return nullptr;
    }
    if (outVideoIndex != nullptr) {
        *outVideoIndex = it->second;
    }
    auto sessionIt = gSessions.find(it->second);
    return (sessionIt != gSessions.end()) ? sessionIt->second : nullptr;
}

void eraseSessionIfIdle(int videoIndex, const std::shared_ptr<CameraSession>& session) {
    if (session == nullptr || !session->isIdle()) {
        return;
    }
    std::lock_guard<std::mutex> lock(gManagerMutex);
    auto it = gSessions.find(videoIndex);
    if (it != gSessions.end() && it->second == session) {
        gSessions.erase(it);
    }
}

} // namespace

bool attachPreview(JNIEnv* env, int videoIndex, jobject surface) {
    auto session = getOrCreateSession(videoIndex);
    const bool ok = session->attachPreview(env, surface);
    if (!ok) {
        eraseSessionIfIdle(videoIndex, session);
    }
    return ok;
}

void detachPreview(int videoIndex) {
    auto session = getSession(videoIndex);
    if (session == nullptr) {
        return;
    }
    session->detachPreview();
    eraseSessionIfIdle(videoIndex, session);
}

void detachAllPreviews() {
    std::vector<std::pair<int, std::shared_ptr<CameraSession>>> sessions;
    {
        std::lock_guard<std::mutex> lock(gManagerMutex);
        sessions.reserve(gSessions.size());
        for (const auto& entry : gSessions) {
            sessions.emplace_back(entry.first, entry.second);
        }
    }
    for (const auto& entry : sessions) {
        entry.second->detachPreview();
        eraseSessionIfIdle(entry.first, entry.second);
    }
}

bool startRecording(JNIEnv* /*env*/, int slot, int videoIndex, const std::string& outputPath,
                    int width, int height, int fps, int bitrate) {
    auto session = getOrCreateSession(videoIndex);
    if (!session->startRecording(slot, outputPath, width, height, fps, bitrate)) {
        eraseSessionIfIdle(videoIndex, session);
        return false;
    }

    std::lock_guard<std::mutex> lock(gManagerMutex);
    gSlotToVideoIndex[slot] = videoIndex;
    return true;
}

void stopRecording(int slot) {
    int videoIndex = -1;
    auto session = getSessionForSlot(slot, &videoIndex);
    if (session == nullptr) {
        return;
    }

    session->stopRecording(slot);
    {
        std::lock_guard<std::mutex> lock(gManagerMutex);
        gSlotToVideoIndex.erase(slot);
    }
    eraseSessionIfIdle(videoIndex, session);
}

} // namespace camera_stream_manager
