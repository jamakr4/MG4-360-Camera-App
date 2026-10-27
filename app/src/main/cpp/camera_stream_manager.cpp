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
#include <exception>
#include <cstdarg>
#include <cstdint>
#include <ctime>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <map>
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

        /**
         * How long a camera may be held open with nothing reading from it, while the recorder
         * swaps one clip for the next.
         *
         * <p>A rotation that goes to plan reclaims the camera in well under a tenth of a second.
         * This is not that budget - it is the safety net for the rotation that never comes back:
         * a loop that died, a stick that vanished, a pause that arrived in the gap. A camera we
         * still hold is a camera the factory 360 view cannot open, so the hold has to expire on
         * its own rather than wait to be told.
         */
        static constexpr int KEEP_WARM_HOLD_MS = 2000;
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

        /**
         * Names the V4L2 field order, which is the one thing that says how the buffer is laid out.
         *
         * <p>The capture path takes the top half of every frame and has always treated the rest as
         * a second copy: full field of view at half the vertical resolution. If that is right the
         * driver is reporting SEQ_TB or SEQ_BT - two fields stacked - and the other half is the
         * missing scan lines rather than a duplicate, which would mean the full 720x480 is there
         * for the weaving. The factory app saves progressive 720x480 stills from these same
         * cameras, so something can get at it.
         *
         * <p>Nobody was reading this field, and nothing logged the format at all. Whatever the
         * answer is, it should not take another guess to find out.
         */
        const char *fieldName(__u32 field)
        {
            switch (field)
            {
            case V4L2_FIELD_ANY: return "ANY";
            case V4L2_FIELD_NONE: return "NONE (progressive)";
            case V4L2_FIELD_TOP: return "TOP";
            case V4L2_FIELD_BOTTOM: return "BOTTOM";
            case V4L2_FIELD_INTERLACED: return "INTERLACED (woven)";
            case V4L2_FIELD_SEQ_TB: return "SEQ_TB (two fields stacked, top first)";
            case V4L2_FIELD_SEQ_BT: return "SEQ_BT (two fields stacked, bottom first)";
            case V4L2_FIELD_ALTERNATE: return "ALTERNATE (one field per buffer)";
            case V4L2_FIELD_INTERLACED_TB: return "INTERLACED_TB";
            case V4L2_FIELD_INTERLACED_BT: return "INTERLACED_BT";
            default: return "unknown";
            }
        }

        /** One line per device, replaced when a device is reopened. See describeFormats(). */
        std::mutex gFormatMutex;
        std::map<int, std::string> gFormats;

        /**
         * How many frames each camera has actually delivered, and how many the grid composed.
         *
         * <p>"The preview is not smooth" is a feeling; this turns it into a number. If a camera
         * reports well under the configured rate, the capture thread is losing time somewhere -
         * to the deinterlacing, to the preview copy, or to the encoder - and which of those it is
         * can then be tested instead of guessed.
         */
        std::mutex gRateMutex;
        std::map<int, long long> gFrameCounts;
        std::map<int, int64_t> gFrameFirstUs;
        long long gComposedFrames = 0;
        int64_t gComposedFirstUs = 0;

        /**
         * What the driver captured while we were not looking.
         *
         * <p>Counting our own dequeues says how many frames we got, never how many there were.
         * The driver stamps every captured frame with a sequence number, so the gap between one
         * dequeue's number and the next is exactly the frames it filled and we never collected.
         * That single figure settles whether the cameras are slow or we are: a device handing
         * over nine frames a second with no gaps is a slow device, and one with sixteen missing
         * is a fast device and a slow reader.
         */
        std::map<int, unsigned> gLastSequence;
        std::map<int, long long> gMissedFrames;
        /** Time between collecting a buffer and giving it back, in microseconds. */
        std::map<int, long long> gWorkUs;
        /** Time spent waiting for a buffer to arrive. */
        std::map<int, long long> gWaitUs;

        /**
         * When the last frame arrived, not when the report was asked for.
         *
         * <p>Dividing by "now minus the first frame" quietly counted everything that happened
         * after the recording stopped - walking back to the laptop, opening the app, pressing
         * the button. On a twenty-second run that was a third of the denominator, and every
         * rate printed here was wrong by that much, always low.
         */
        std::map<int, int64_t> gFrameLastUs;
        int64_t gComposedLastUs = 0;

        void countFrame(int videoIndex, int64_t nowUs, unsigned sequence)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gFrameLastUs[videoIndex] = nowUs;
            if (gFrameCounts[videoIndex] == 0)
            {
                gFrameFirstUs[videoIndex] = nowUs;
            }
            else
            {
                const unsigned previous = gLastSequence[videoIndex];
                if (sequence > previous + 1)
                {
                    gMissedFrames[videoIndex] += sequence - previous - 1;
                }
            }
            gLastSequence[videoIndex] = sequence;
            gFrameCounts[videoIndex]++;
        }

        /** The whole turn of the loop, so that what the two other figures miss is visible. */
        std::map<int, long long> gLoopUs;
        /** Turns of the loop where select() gave nothing: time that no other figure counts. */
        std::map<int, long long> gEmptyCount;
        std::map<int, long long> gEmptyUs;

        void countEmptySelect(int videoIndex, long long us)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gEmptyCount[videoIndex]++;
            gEmptyUs[videoIndex] += us;
        }

        /** Where the encoder thread's time goes, one figure per stage. */
        long long gSnapUs = 0, gFooterUs = 0, gFeedUs = 0, gDrainUs = 0, gEncodeCount = 0;

        /**
         * Opening a clip, split in two.
         *
         * <p>The gap between clips is now dominated by the start call, and the start call does
         * two unrelated things: it builds an encoder and a muxer - which means creating the
         * output file on the stick - and it attaches four cameras. Until these are timed apart
         * the number says only "slow", and the last two builds were spent guessing which half.
         */
        long long gClipEncoderUs = 0, gClipCamerasUs = 0, gClipStartCount = 0;

        void countClipStart(long long encoderUs, long long camerasUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gClipEncoderUs += encoderUs;
            gClipCamerasUs += camerasUs;
            gClipStartCount++;
        }

        /**
         * When a frame composed now should say it happened, snapped to the nominal grid.
         *
         * <p>The cameras deliver on a regular period, so each sample of the canvas advances the
         * picture by one camera frame: the content moves in even steps. Stamping the moment the
         * sample was taken instead put the scheduler's jitter into the file - measured at one
         * interval in nine outside +-8 ms, and correlated -0.44 with the next, which is the
         * signature of a late wake-up followed by a catch-up. Even motion shown at uneven
         * intervals is exactly what a viewer calls stuttering, and it was visible on the road.
         *
         * <p>Snapping fixes it without going back to counting frames. The jitter is under half a
         * period, so rounding erases it; a real stall is longer than half a period, so it still
         * lands in a later slot and the file keeps the duration it actually has - which is why
         * the real clock was chosen in the first place, and that reason still holds.
         *
         * <p>Never twice in the same slot: the catch-up frame carries a camera frame of its own,
         * so it takes the next slot rather than the one just used. A repeated timestamp would
         * also be a timestamp the muxer is entitled to refuse.
         */
        int64_t alignedPts(int64_t elapsedUs, int64_t frameDurationUs, int64_t lastPtsUs)
        {
            if (frameDurationUs <= 0)
            {
                return std::max<int64_t>(0, elapsedUs);
            }
            const int64_t clamped = std::max<int64_t>(0, elapsedUs);
            const int64_t slot = (clamped + frameDurationUs / 2) / frameDurationUs;
            int64_t pts = slot * frameDurationUs;
            if (lastPtsUs >= 0 && pts <= lastPtsUs)
            {
                pts = lastPtsUs + frameDurationUs;
            }
            return pts;
        }

        /**
         * Whether the camera handed us the very same picture twice.
         *
         * <p>The question this answers is not ours to fix if the answer is yes. In poor light a
         * sensor lengthens its integration until it can no longer produce a new picture every
         * period, and repeats one - and the driver still delivers a buffer, so nothing in the
         * capture loop notices. The factory 360 view shows the same hesitation in the same
         * light, which is the observation that made this worth counting: two apps sharing only
         * the cameras.
         *
         * <p>Bit-identical is the right test and it is immune to the trap that has caught every
         * measurement made from the footage: a still scene produces frames that look alike but
         * are never identical, because sensor noise is never the same twice.
         */
        long long gRepeatedBuffers = 0, gDeliveredBuffers = 0;

        void countDelivery(bool repeated)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gDeliveredBuffers++;
            if (repeated)
            {
                gRepeatedBuffers++;
            }
        }

        /**
         * Whether a snapshot of the canvas found a cell nobody had rewritten since the last one.
         *
         * <p>The other half of the same question. If the cameras are delivering new pictures and
         * the canvas still holds an old cell when the encoder takes its sample, the lateness is
         * ours - the sampler ran behind and missed a camera frame. Counted separately for the
         * samples that land on a key frame, where the encoder does the most work and the write
         * to the medium is the largest.
         */
        long long gStaleCells = 0, gStaleSamples = 0, gCanvasSamples = 0, gStaleOnKeyframe = 0;

        /** How long samples spent waiting for a camera that was about to deliver. */
        long long gWaitFreshUs = 0, gWaitFreshCount = 0;

        void countWaitFresh(long long us)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gWaitFreshUs += us;
            if (us > 0)
            {
                gWaitFreshCount++;
            }
        }

        void countSnapshot(int staleCells, bool keyframe)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gCanvasSamples++;
            if (staleCells > 0)
            {
                gStaleCells += staleCells;
                gStaleSamples++;
                if (keyframe)
                {
                    gStaleOnKeyframe++;
                }
            }
        }

        /**
         * A cheap stand-in for comparing two buffers byte by byte.
         *
         * <p>Taken from the cached copy, never from the mapped buffer, so it costs nothing worth
         * measuring. About four thousand samples spread across the frame: enough that two
         * genuinely different pictures agreeing on all of them is not a thing that happens.
         */
        uint64_t cheapFingerprint(const cv::Mat &m)
        {
            const size_t bytes = m.total() * m.elemSize();
            if (m.data == nullptr || bytes == 0)
            {
                return 0;
            }
            const size_t step = std::max<size_t>(1, bytes / 4096);
            uint64_t h = 1469598103934665603ULL;
            for (size_t i = 0; i < bytes; i += step)
            {
                h ^= m.data[i];
                h *= 1099511628211ULL;
            }
            return h;
        }

        /**
         * How much of the picture the comb test decided against weaving.
         *
         * <p>The one number that says whether the threshold is sane. A few per cent on a moving
         * scene is the shape of a working gate; near zero means it is asleep and the comb is
         * still there, and a large fraction means it is smoothing away the detail this whole
         * feature exists to recover.
         */
        long long gCombPixels = 0, gCombTotal = 0;

        void countComb(long long combed, long long total)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gCombPixels += combed;
            gCombTotal += total;
        }

        /** How long the last urgent release took, in microseconds. Reported, not guessed. */
        int64_t gLastUrgentReleaseUs = 0;

        /** Asked for, and actually got: a sleep on a loaded system is a lower bound. */
        long long gSleepAskedUs = 0, gSleepGotUs = 0, gSleepCount = 0;

        void countSleep(long long askedUs, long long gotUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gSleepAskedUs += askedUs;
            gSleepGotUs += gotUs;
            gSleepCount++;
        }

        /** What showing the grid costs, now that it is off the encoder's thread. */
        long long gPreviewUs = 0, gPreviewCount = 0;

        void countPreview(long long us)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gPreviewUs += us;
            gPreviewCount++;
        }

        void countEncodeStages(long long snapUs, long long footerUs, long long feedUs,
                               long long drainUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gSnapUs += snapUs;
            gFooterUs += footerUs;
            gFeedUs += feedUs;
            gDrainUs += drainUs;
            gEncodeCount++;
        }

        void countTimings(int videoIndex, long long workUs, long long waitUs, long long loopUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gWorkUs[videoIndex] += workUs;
            gWaitUs[videoIndex] += waitUs;
            gLoopUs[videoIndex] += loopUs;
        }

        /**
         * How long the one sequential read of the mapped camera buffer takes.
         *
         * <p>If this is most of the per-frame budget, the cost is the memory the buffer lives
         * in and no rearranging of our own code will touch it - the answer would be to read it
         * once and never again, which is what we now do, or not to read it at all, which is
         * what the factory hardware path does.
         */
        long long gCopyUs = 0;
        long long gCopyCount = 0;

        void countCopyUs(long long us)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            gCopyUs += us;
            gCopyCount++;
        }

        void countComposedFrame(int64_t nowUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            if (gComposedFrames == 0)
            {
                gComposedFirstUs = nowUs;
            }
            gComposedLastUs = nowUs;
            gComposedFrames++;
        }

        void rememberFormat(int videoIndex, const std::string &line)
        {
            std::lock_guard<std::mutex> lock(gFormatMutex);
            gFormats[videoIndex] = line;
        }

        /** A further line under a device's format line, for what the driver says about itself. */
        void appendFormatDetail(int videoIndex, const std::string &line)
        {
            std::lock_guard<std::mutex> lock(gFormatMutex);
            gFormats[videoIndex] += "\n";
            gFormats[videoIndex] += line;
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

        /**
         * Persists the directory entry, not just the bytes.
         *
         * <p>fsync on the file writes its contents and its metadata; the name that points at it
         * lives in the parent directory, and that needs a sync of its own, or a clip can
         * survive a power cut with nothing referring to it.
         */
        void syncDirectoryOf(const std::string &path);

        /**
         * Puts the finished clip on the device, without making the next one wait for it.
         *
         * <p>Closing a descriptor does not write anything out: the clip sits in the page cache,
         * and on this head unit the power can go before the kernel gets round to it. Thirty
         * seconds at 9 Mbit/s is 34 MB plus the FAT entries describing it, and losing half of
         * that is how a stick comes back mounted read-only on the next drive - which has
         * happened on both of the sticks in use.
         *
         * <p>fsync blocks until the stick has actually taken the data, and a slow stick can
         * take seconds over it. Doing that between two segments would put a hole in the
         * recording every thirty seconds to avoid an occasional corruption that a reinsertion
         * repairs, which for a dashcam is the wrong way round: the hole is road nobody filmed.
         * So the descriptor is handed to a thread of its own and the next segment starts at
         * once. The only cost is one more file open for as long as the flush takes.
         */
        /**
         * How many clips are still being pushed to the medium.
         *
         * <p>The flush below runs detached, which is right on the rotation path - up to 34 MB
         * of fsync has no business blocking the start of the next clip. It is wrong at
         * shutdown: the app reported "clip closed before standby" while these threads were
         * still writing, so the one moment it was meant to make safe was the one it lied about.
         */
        std::mutex gFlushMutex;
        std::condition_variable gFlushCv;
        int gFlushesInFlight = 0;
        std::atomic<bool> gFlushFailed{false};
        std::atomic<int64_t> gWrittenSamples{0};
        std::atomic<bool> gWriteFailed{false};

        void flushAndCloseInBackground(int fd, const std::string &path)
        {
            if (fd < 0)
            {
                return;
            }
            {
                std::lock_guard<std::mutex> lock(gFlushMutex);
                gFlushesInFlight++;
            }
            std::thread([fd, path]()
                        {
                if (fsync(fd) != 0)
                {
                    gFlushFailed = true;
                    logw("fsync failed on %s: %s", path.c_str(), strerror(errno));
                }
                close(fd);
                syncDirectoryOf(path);
                {
                    std::lock_guard<std::mutex> lock(gFlushMutex);
                    gFlushesInFlight--;
                }
                gFlushCv.notify_all(); })
                .detach();
        }

        void syncDirectoryOf(const std::string &path)
        {
            const size_t cut = path.find_last_of('/');
            if (cut == std::string::npos || cut == 0)
            {
                return;
            }
            int dirFd = open(path.substr(0, cut).c_str(), O_RDONLY | O_DIRECTORY);
            if (dirFd < 0)
            {
                return;
            }
            if (fsync(dirFd) != 0) gFlushFailed = true;
            close(dirFd);
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
                // The assignment below used to be inside the comment above it, so recHeight_ kept
                // its initial 0, the validity check just below rejected it, and this recorder
                // could never start - it is still that way upstream. Worth reporting there.
                //
                // The comment was also wrong about what the other half of the buffer holds: not a
                // second camera, but the second field of an interlaced frame. The capture path
                // now weaves them, so a frame arriving here is the full height and nothing needs
                // halving. See docs/camera-format.md.
                recHeight_ = std::min(requestedHeight_, srcHeight);
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
                lastPtsUs_ = -1;
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
                        // The slot this frame belongs in, which is not the same as the moment
                        // it happened to be composed. See alignedPts.
                        const int64_t pts = alignedPts(nowUs() - startUs_, frameDurationUs_,
                                                       lastPtsUs_);
                        lastPtsUs_ = pts;
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
                    // Closing a descriptor does not put anything on the device: the clip sits
                    // in the page cache, and on this head unit the power can go before the
                    // kernel gets round to it. Thirty seconds at 9 Mbit/s is 34 MB of data plus
                    // the FAT entries describing it, and losing half of that is how a stick
                    // comes back mounted read-only on the next drive - which has happened on
                    // both sticks in use, once after a standby and once with an early build,
                    // each time needing a filesystem check to become writable again.
                    //
                    // Once per clip: the cost lands between segments, thirty seconds apart.
                    flushAndCloseInBackground(outFd_, outputPath_);
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
                                                 static_cast<uint64_t>(
                                                     std::max<int64_t>(0, nowUs() - startUs_)),
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
            int64_t lastPtsUs_ = -1;
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

            /**
             * Whether this consumer would rather have the camera buffer as it came off the
             * device, UYVY and untouched.
             *
             * <p>The capture loop used to convert every frame to RGBA before handing it on,
             * because that is what every consumer wanted. The combined recorder does not: it
             * writes NV12, and going UYVY -> RGBA -> I420 -> NV12 moved more than a gigabyte a
             * second on four cameras and cost half the frame rate. A consumer that says yes here
             * gets the packed buffer instead, and the RGBA conversion is skipped entirely when
             * nobody else asks for it.
             */
            virtual bool wantsPackedFrame() const { return false; }

            /** The camera buffer, cropped, still in its capture format. Only called when
             *  {@link #wantsPackedFrame} returns true. */
            virtual void processPackedFrame(const cv::Mat & /*packedFrame*/) {}

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

        /** The strip under the grid carrying the date, the time and the speed. */
        static constexpr int COMBINED_FOOTER_HEIGHT = 80;

        /**
         * The size of the composed canvas, in one place.
         *
         * <p>The sink builds its buffers from this and the preview sizes its window from it, so a
         * change to the layout cannot leave one of them believing the old shape.
         */
        int combinedCanvasWidth(int cellWidth, int /*cellHeight*/)
        {
            return cellWidth * 2;
        }

        int combinedCanvasHeight(int /*cellWidth*/, int cellHeight)
        {
            return (cellHeight * 2) + COMBINED_FOOTER_HEIGHT;
        }

        class CombinedRecordingSink : public std::enable_shared_from_this<CombinedRecordingSink>
        {
        public:
            /**
             * Per-camera scratch, so the slow read of the mapped buffer and the splitting into
             * planes both happen outside every lock and without fighting the other cameras.
             */
            struct CellStaging
            {
                cv::Mat packed;    // a cached copy of the camera's buffer
                cv::Mat luma;      // Y as captured
                cv::Mat lumaFull;  // Y at cell height
                cv::Mat chromaU;
                cv::Mat chromaV;
                cv::Mat chroma;    // U and V interleaved, as NV12 wants them
                uint64_t lastFingerprint = 0;
                bool hasFingerprint = false;
                // For the comb test below. Allocated once and reused: four cameras at 30 fps is
                // no place to be asking the allocator for anything.
                cv::Mat deltaAbove;
                cv::Mat deltaBelow;
                cv::Mat combAbove;
                cv::Mat combBelow;
                cv::Mat combMask;
                cv::Mat interpolated;
            };
            std::array<CellStaging, 4> staging_{};

            CombinedRecordingSink(std::string outputPath, int cellWidth, int cellHeight, int fps, int bitrate,
                                  std::string signature, bool showSpeed, int cameraMask)
                : outputPath_(std::move(outputPath)),
                  cameraMask_(cameraMask),
                  cellWidth_(cellWidth),
                  cellHeight_(cellHeight),
                  gridWidth_(combinedCanvasWidth(cellWidth, cellHeight)),
                  gridHeight_(cellHeight * 2),
                  footerHeight_(COMBINED_FOOTER_HEIGHT),
                  totalHeight_(combinedCanvasHeight(cellWidth, cellHeight)),
                  fps_(fps),
                  bitrate_(bitrate),
                  signature_(std::move(signature)),
                  showSpeed_(showSpeed)
            {
            }

            ~CombinedRecordingSink()
            {
                finalize();
                if (previewWindow_ != nullptr)
                {
                    ANativeWindow_release(previewWindow_);
                    previewWindow_ = nullptr;
                }
            }

            /**
             * Whether this sink writes a file, as opposed to only composing for the preview.
             *
             * <p>An empty output path means somebody wants to look at the grid without recording
             * it. Everything up to the encoder is the same work, which is the point: what the
             * preview shows is what the file would contain, composed by the same code rather
             * than by an approximation of it.
             */
            bool isRecording() const
            {
                return !outputPath_.empty();
            }

            /**
             * Shows the composed canvas on a Surface, live.
             *
             * <p>Has a lock of its own: the surface is touched by the encoder thread, and a
             * window that changed mid-post would be read after being released.
             */
            void setPreviewWindow(ANativeWindow *window)
            {
                std::lock_guard<std::mutex> lock(previewMutex_);
                if (previewWindow_ != nullptr)
                {
                    ANativeWindow_release(previewWindow_);
                }
                previewWindow_ = window;
                if (previewWindow_ != nullptr)
                {
                    ANativeWindow_setBuffersGeometry(previewWindow_, gridWidth_, totalHeight_,
                                                     AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
                    // Same pre-warm as the single-camera preview: the first locks would
                    // otherwise block while the buffer pool is allocated, and they would block
                    // on the capture thread.
                    for (int i = 0; i < 3; i++)
                    {
                        ANativeWindow_Buffer warm{};
                        if (ANativeWindow_lock(previewWindow_, &warm, nullptr) == 0)
                        {
                            if (warm.bits != nullptr && warm.height > 0 && warm.stride > 0)
                            {
                                std::memset(warm.bits, 0,
                                            static_cast<size_t>(warm.stride) *
                                                static_cast<size_t>(warm.height) * 4U);
                            }
                            ANativeWindow_unlockAndPost(previewWindow_);
                        }
                    }
                }
            }

            /** Hands the window over without releasing it: the next composer will own it. */
            ANativeWindow *takePreviewWindow()
            {
                std::lock_guard<std::mutex> lock(previewMutex_);
                ANativeWindow *window = previewWindow_;
                previewWindow_ = nullptr;
                return window;
            }

            bool hasPreviewWindow()
            {
                std::lock_guard<std::mutex> lock(previewMutex_);
                return previewWindow_ != nullptr;
            }

            /**
             * How often the preview is refreshed, at most.
             *
             * <p>This was twelve a second, set when posting the preview ran on a capture thread
             * and every post held a V4L2 buffer back from the driver. It was the right trade
             * then and it was also the answer to "why is the preview always stuttery": it was
             * stuttery because it was told to be, and no amount of work elsewhere was going to
             * change that.
             *
             * <p>The preview has its own thread now and takes its own copy of the canvas, so it
             * costs the cameras and the encoder nothing. There is no reason left to show less
             * than what is being recorded.
             */
            static constexpr int64_t PREVIEW_INTERVAL_US = 1000000 / 25;

            /**
             * Shows the snapshot the encoder is about to use, so the preview needs neither the
             * canvas lock nor a second conversion.
             */
            /**
             * The preview runs on its own thread, and this is not a tidiness question.
             *
             * <p>It used to be posted from the encoder thread, between two stopwatches and so
             * invisible to both. Measured afterwards: 23 ms a frame, which held the recording
             * to 15.9 fps while the cameras were delivering 23.5. Watching the grid was slowing
             * down the recording of it - and making the preview itself look worse, which is
             * what it was being judged on.
             */
            void previewLoop()
            {
                while (!stopRequested_.load() && !isFinalized())
                {
                    if (!hasPreviewWindow())
                    {
                        std::this_thread::sleep_for(std::chrono::milliseconds(100));
                        continue;
                    }
                    const int64_t startedUs = nowUs();
                    postPreviewFrame();
                    const int64_t spentUs = nowUs() - startedUs;
                    countPreview(spentUs);
                    if (spentUs < PREVIEW_INTERVAL_US)
                    {
                        std::this_thread::sleep_for(
                            std::chrono::microseconds(PREVIEW_INTERVAL_US - spentUs));
                    }
                }
            }

            void postPreviewFrame()
            {
                {
                    std::lock_guard<std::mutex> lock(canvasMutex_);
                    if (nv12Canvas_.empty())
                    {
                        return;
                    }
                    nv12Canvas_.copyTo(previewSnapshot_);
                }

                std::lock_guard<std::mutex> lock(previewMutex_);
                if (previewWindow_ == nullptr)
                {
                    return;
                }
                lastPreviewUs_ = nowUs();
                // The one place RGBA is still needed, and the only one that pays for it: a
                // surface wants it, and only while somebody is looking, twelve times a second.
                cv::cvtColor(previewSnapshot_, rgbaPreview_, cv::COLOR_YUV2RGBA_NV12);
                ANativeWindow_Buffer out{};
                if (ANativeWindow_lock(previewWindow_, &out, nullptr) != 0)
                {
                    return;
                }
                const int rows = std::min(rgbaPreview_.rows, out.height);
                const int cols = std::min(rgbaPreview_.cols, out.width);
                const int srcStride = static_cast<int>(rgbaPreview_.step[0]);
                const int dstStride = out.stride * 4;
                uint8_t *dst = static_cast<uint8_t *>(out.bits);
                for (int row = 0; row < rows; row++)
                {
                    std::memcpy(dst + row * dstStride, rgbaPreview_.data + row * srcStride,
                                static_cast<size_t>(cols) * 4U);
                }
                ANativeWindow_unlockAndPost(previewWindow_);
            }

            /**
             * The canvas, as NV12: a full-size luma plane with a half-height interleaved chroma
             * plane below it, in one allocation so the whole thing is one memcpy to the codec.
             *
             * <p>Started at black - luma 16, which is black in the studio range this encoder
             * writes - with neutral chroma at 128. Zeroing the whole buffer would have been
             * simpler and would have shown lurid green, because zero chroma is not grey.
             *
             * <p>What stays black is what nobody writes: a camera not selected in the mask
             * never attaches, so its quarter is never touched for the whole recording. That is
             * the intended look for a two- or one-camera recording, and it is also what a
             * camera that has not yet delivered its first frame shows.
             */
            void allocateCanvas()
            {
                nv12Canvas_.create(totalHeight_ + (totalHeight_ / 2), gridWidth_, CV_8UC1);
                nv12Canvas_(cv::Rect(0, 0, gridWidth_, totalHeight_)).setTo(cv::Scalar(16));
                nv12Canvas_(cv::Rect(0, totalHeight_, gridWidth_, totalHeight_ / 2))
                    .setTo(cv::Scalar(128));
            }

            bool initialize()
            {
                if (cellWidth_ <= 0 || cellHeight_ <= 0 || (cellWidth_ % 2) != 0 || (cellHeight_ % 2) != 0)
                {
                    loge("combined invalid cell size %dx%d", cellWidth_, cellHeight_);
                    return false;
                }

                if (!isRecording())
                {
                    // Preview only: no codec, no muxer, no file. Just the canvas to draw on.
                    allocateCanvas();
                    encoderFrame_.resize(
                        static_cast<size_t>(gridWidth_) * static_cast<size_t>(totalHeight_) * 3U / 2U);
                    frameDurationUs_ = 1000000LL / std::max(1, fps_);
                    startUs_ = nowUs();
                    nextPtsUs_ = 0;
                    lastPtsUs_ = -1;
                    startEncoderThread();
                    logi("combined preview %dx%d", gridWidth_, totalHeight_);
                    return true;
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

                allocateCanvas();
                encoderFrame_.resize(static_cast<size_t>(gridWidth_) * static_cast<size_t>(totalHeight_) * 3U / 2U);
                frameDurationUs_ = 1000000LL / std::max(1, fps_);
                startUs_ = nowUs();
                nextPtsUs_ = 0;
                lastPtsUs_ = -1;
                frameCount_ = 0;
                startEncoderThread();
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

            /**
             * A camera has a frame. Copy it into its quarter and get out.
             *
             * <p>This used to compose, convert and encode inline, holding one mutex for all four
             * cameras - and it did it while still holding the V4L2 buffer, because the capture
             * loop only gives the buffer back when the consumer returns. Measured on the car:
             * 1.1 ms waiting for a frame and 72.3 ms holding one. The device was never slow. It
             * was idle, out of buffers, waiting for us.
             *
             * <p>So the encoder lives on its own thread now and this does the one thing that has
             * to happen in the camera's own time: write the pixels down.
             */
            void processSourcePackedFrame(int sourceIndex, const cv::Mat &packedFrame)
            {
                if (sourceIndex < 0 || sourceIndex >= 4 || packedFrame.empty() || isFinalized() || stopRequested_.load())
                {
                    return;
                }
                if (packedFrame.cols < cellWidth_ || packedFrame.rows <= 0)
                {
                    return;
                }

                // The V4L2 buffer is device memory, and on this SoC it is not cached: every
                // read of it costs many times what the same read from ordinary memory would.
                // One sequential copy, taken before any lock, and everything after this works
                // on cached bytes.
                //
                // This is what the last two builds got wrong. Writing NV12 straight from the
                // mapped buffer replaced one pass over it (a single cvtColor) with three - luma,
                // then U, then V - and did them holding the canvas lock. Total memory traffic
                // went down sixfold and the frame rate did not move, because the traffic that
                // mattered was the slow kind.
                const int64_t copyStartUs = nowUs();
                CellStaging &staging = staging_[sourceIndex];
                const int srcRows = std::min(packedFrame.rows, cellHeight_);
                packedFrame(cv::Rect(0, 0, cellWidth_, srcRows)).copyTo(staging.packed);
                countCopyUs(nowUs() - copyStartUs);

                const uint64_t fingerprint = cheapFingerprint(staging.packed);
                countDelivery(staging.hasFingerprint && fingerprint == staging.lastFingerprint);
                staging.lastFingerprint = fingerprint;
                staging.hasFingerprint = true;

                prepareCell(staging, srcRows);

                std::lock_guard<std::mutex> lock(canvasMutex_);
                if (isFinalized() || stopRequested_.load())
                {
                    return;
                }
                writeCellLocked(sourceIndex, staging);
            }

            /**
             * How far out of line a captured row has to be before it is called combing.
             *
             * <p>In luma levels. Sensor noise on these cameras is two or three even in poor
             * light, so twelve is clear of it while still catching the edge of a passing car.
             * Too low and genuine fine detail gets smoothed away, which is the picture this app
             * recorded before and the one thing worth not going back to; too high and the comb
             * stays.
             */
            static constexpr int COMB_THRESHOLD = 12;

            /** Splits the packed pairs into the two planes NV12 wants, weaving the fields.
             *
             * <p>What arrives is one interlaced frame with its two fields stacked: rows 0..239
             * are the field captured first, rows 240..479 the one captured a fiftieth of a
             * second later. Interleaving them is what recovers the full height - measured at
             * 127% more vertical detail than stretching one of them, see docs/camera-format.md.
             *
             * <p>Luma only. Chroma is taken from the first field alone, exactly as before:
             * NV12 wants one chroma row per two picture rows, so a 240-row field already fits
             * a 480-row cell with nothing to resample, and vertical colour resolution is the
             * least visible thing in a frame. Changing one plane at a time also means that if
             * this looks wrong, there is one place it can be wrong in.
             */
            void prepareCell(CellStaging &staging, int srcRows)
            {
                cv::extractChannel(staging.packed, staging.luma, 1);
                if (staging.luma.rows == cellHeight_ && (cellHeight_ % 2) == 0)
                {
                    const int fieldRows = cellHeight_ / 2;
                    softenCombing(staging, fieldRows);
                    staging.lumaFull.create(cellHeight_, cellWidth_, CV_8UC1);
                    // Two views of the same buffer, each stepping over every other row: the
                    // first field lands on the even lines and the second on the odd ones,
                    // without a pass to interleave them afterwards.
                    cv::Mat evenLines(fieldRows, cellWidth_, CV_8UC1,
                                      staging.lumaFull.data, staging.lumaFull.step[0] * 2);
                    cv::Mat oddLines(fieldRows, cellWidth_, CV_8UC1,
                                     staging.lumaFull.data + staging.lumaFull.step[0],
                                     staging.lumaFull.step[0] * 2);
                    staging.luma.rowRange(0, fieldRows).copyTo(evenLines);
                    staging.luma.rowRange(fieldRows, cellHeight_).copyTo(oddLines);
                }
                else if (staging.luma.rows != cellHeight_)
                {
                    // A field arriving at half the cell height is stretched to fill it.
                    // Bilinear on purpose: there is no detail to recover here, and a sharper
                    // filter would only invent edges for the encoder to pay for.
                    cv::resize(staging.luma, staging.lumaFull,
                               cv::Size(cellWidth_, cellHeight_), 0, 0, cv::INTER_LINEAR);
                }
                else
                {
                    staging.lumaFull = staging.luma;
                }

                // Read four bytes at a time the packed pairs are [U, Y, V, Y], so chroma comes
                // out as two half-width planes. NV12 wants them interleaved, one row per two
                // picture rows - and a 240-line field stretched over 480 lines leaves exactly
                // one source row per chroma row, so there is nothing to resample.
                // One field's worth of chroma rows, whether one field arrived or two: NV12
                // wants cellHeight_/2 of them, which is exactly a field.
                const int chromaRows = std::min(srcRows, cellHeight_ / 2);
                cv::Mat quads(chromaRows, cellWidth_ / 2, CV_8UC4,
                              staging.packed.data, staging.packed.step[0]);
                cv::extractChannel(quads, staging.chromaU, 0);
                cv::extractChannel(quads, staging.chromaV, 2);
                cv::merge(std::vector<cv::Mat>{staging.chromaU, staging.chromaV}, staging.chroma);
            }

            /**
             * Replaces the rows where weaving would show, and leaves the rest alone.
             *
             * <p>The two fields are a fiftieth of a second apart, so anything that moved between
             * them lands in a different place on each - and interleaving that produces the comb
             * everybody recognises. Measured on a real drive: weaving without this gave 131% more
             * vertical detail and, in the metre of asphalt ahead of the bumper, four times the
             * combing of the car's own bodywork. Detail that cares how fast the scene is moving
             * is not detail.
             *
             * <p>The test is spatial and needs no history. A row from the second field belongs
             * between its two neighbours from the first: genuine vertical detail sits inside that
             * pair, a combed row falls outside <b>both</b> of them, in the same direction. Where
             * that happens the row is replaced by the average of its neighbours - which is
             * exactly the picture this app recorded before, so the worst case here is the old
             * behaviour, applied only to the pixels that would have combed.
             *
             * <p>Cheap, and deliberately so: half a dozen passes over a cached 720x240 plane,
             * against the 8 ms the uncached read already costs. The expensive part of this
             * feature was paid for before we got here.
             */
            void softenCombing(CellStaging &staging, int fieldRows)
            {
                if (fieldRows < 2)
                {
                    return;
                }
                const cv::Mat fieldA = staging.luma.rowRange(0, fieldRows);
                cv::Mat fieldB = staging.luma.rowRange(fieldRows, fieldRows * 2);

                // A row of the second field sits between these two rows of the first.
                const cv::Mat above = fieldA.rowRange(0, fieldRows - 1);
                const cv::Mat below = fieldA.rowRange(1, fieldRows);
                cv::Mat middle = fieldB.rowRange(0, fieldRows - 1);

                cv::subtract(middle, above, staging.deltaAbove, cv::noArray(), CV_16S);
                cv::subtract(middle, below, staging.deltaBelow, cv::noArray(), CV_16S);

                // Outside both neighbours, in the same direction, by more than the threshold.
                cv::compare(staging.deltaAbove, COMB_THRESHOLD, staging.combAbove, cv::CMP_GT);
                cv::compare(staging.deltaBelow, COMB_THRESHOLD, staging.combBelow, cv::CMP_GT);
                cv::bitwise_and(staging.combAbove, staging.combBelow, staging.combMask);

                cv::compare(staging.deltaAbove, -COMB_THRESHOLD, staging.combAbove, cv::CMP_LT);
                cv::compare(staging.deltaBelow, -COMB_THRESHOLD, staging.combBelow, cv::CMP_LT);
                cv::bitwise_and(staging.combAbove, staging.combBelow, staging.combAbove);
                cv::bitwise_or(staging.combMask, staging.combAbove, staging.combMask);

                // One pixel of margin along the row: an edge caught at its centre and missed at
                // its shoulders leaves a speckled seam, which reads worse than either choice.
                cv::dilate(staging.combMask, staging.combMask,
                           cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 1)));

                cv::addWeighted(above, 0.5, below, 0.5, 0.0, staging.interpolated);
                staging.interpolated.copyTo(middle, staging.combMask);
                countComb(cv::countNonZero(staging.combMask), staging.combMask.total());
            }

            /**
             * Takes a copy of the canvas for the encoder, then writes the footer onto the copy.
             *
             * <p>The footer used to be drawn into the canvas itself, which meant holding the
             * canvas lock through a setTo, a line and two putText calls while four cameras
             * waited to write their quarters. It belongs to the frame being sent, not to the
             * shared canvas, and the strip it occupies is below every camera's quarter - so
             * drawing it here costs the same and blocks nobody.
             */
            void snapshotCanvas(std::vector<uint8_t> &into, long long &snapUs, long long &footerUs)
            {
                const int64_t t0 = nowUs();
                int stale = 0;
                {
                    std::lock_guard<std::mutex> lock(canvasMutex_);
                    std::memcpy(into.data(), nv12Canvas_.data, into.size());
                    // Cells that nobody rewrote since the previous sample. Only the ones we are
                    // expecting: a camera that was never selected is not late, it is absent.
                    const int missing = cameraMask_ & ~cellsFreshMask_;
                    for (int i = 0; i < 4; ++i)
                    {
                        if (missing & (1 << i))
                        {
                            stale++;
                        }
                    }
                    cellsFreshMask_ = 0;
                }
                countSnapshot(stale, frameDurationUs_ > 0
                                     && (frameCount_ % std::max<int64_t>(1, fps_)) == 0);
                const int64_t t1 = nowUs();
                cv::Mat frame(totalHeight_ + (totalHeight_ / 2), gridWidth_, CV_8UC1, into.data());
                drawFooter(frame);
                snapUs = t1 - t0;
                footerUs = nowUs() - t1;
            }

            /**
             * One thread paces the output, so the cameras never wait for the codec.
             *
             * <p>It also means the recording holds its frame rate when a camera stalls: the
             * canvas is still there, still current for the other three, and the clock in the
             * footer keeps moving.
             */
            void startEncoderThread()
            {
                // Weak, not strong. A thread holding a shared_ptr to its own owner keeps that
                // owner alive for as long as it runs, and the owner's destructor is what stops
                // the thread: the two would wait for each other forever.
                std::weak_ptr<CombinedRecordingSink> weak = shared_from_this();
                // Nothing a worker thread does may take the process down with it.
                //
                // The capture loop has had this guard from the start; these two never got one,
                // and the difference showed the first time something threw in here: the whole
                // app died with the bare 'terminating' abort and a backtrace that stopped at
                // the C++ runtime, naming nothing. An uncaught exception on a thread is
                // std::terminate() by the standard, and std::terminate() is not a crash report,
                // it is the destruction of one.
                //
                // Caught, the message survives - and OpenCV's carries the failing expression
                // with its file and line, which is the whole answer. The sink then stops the
                // way it stops for any other reason, and the clip is finalised rather than lost.
                encoderThread_ = std::thread([weak]()
                                             {
                    if (auto self = weak.lock())
                    {
                        self->runGuarded("encoder", [&self]() { self->encodeLoop(); });
                    } });
                previewThread_ = std::thread([weak]()
                                             {
                    if (auto self = weak.lock())
                    {
                        self->runGuarded("preview", [&self]() { self->previewLoop(); });
                    } });
            }

            /** How long to wait for a first picture from every camera before starting anyway. */
            static constexpr int CANVAS_WAIT_MS = 300;

            /**
             * Waits for a canvas worth encoding, then starts the clock.
             *
             * <p>Without this, every clip opened with a black frame: allocateCanvas() zeroes the
             * buffer and the encoder thread began sampling before any camera had written a cell.
             * Measured on two clips: the first frame had a mean luminance of 1.1 against 82 ten
             * frames later, and compressed to 8 KB against 37 - the signature of encoding
             * nothing at all. Then the four cells appeared over one or two frames, so the clip
             * also opened with a picture that was part black.
             *
             * <p>The clock is started after the wait rather than before it, so the file's first
             * timestamp belongs to its first real picture and the duration stays honest.
             *
             * <p>The deadline is not a nicety. A camera that never arrives must not hold a clip
             * open for ever; after it, this starts with whatever there is, which is the old
             * behaviour and no worse.
             */
            void awaitFirstPicture()
            {
                const int64_t deadlineUs = nowUs() + CANVAS_WAIT_MS * 1000LL;
                while (!stopRequested_.load() && !isFinalized())
                {
                    {
                        std::lock_guard<std::mutex> lock(canvasMutex_);
                        if ((cellsWrittenMask_ & cameraMask_) == cameraMask_)
                        {
                            break;
                        }
                    }
                    if (nowUs() >= deadlineUs)
                    {
                        logw("combined starting with an incomplete canvas after %d ms",
                             CANVAS_WAIT_MS);
                        break;
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
                startUs_ = nowUs();
                nextPtsUs_ = 0;
                lastPtsUs_ = -1;
            }

            /** Runs a worker's body and turns anything it throws into a line and a stop. */
            template <typename Body>
            void runGuarded(const char *what, Body body)
            {
                try
                {
                    body();
                }
                catch (const std::exception &e)
                {
                    loge("combined %s thread ended on an exception: %s", what, e.what());
                    requestStop();
                }
                catch (...)
                {
                    loge("combined %s thread ended on an unknown exception", what);
                    requestStop();
                }
            }

            void encodeLoop()
            {
                awaitFirstPicture();
                while (!stopRequested_.load() && !isFinalized())
                {
                    // Paced by the cameras, not by a clock of our own.
                    //
                    // This loop used to sleep to a deadline it set itself, wake, and only then
                    // look at whether the cameras had delivered. The two rates were never the
                    // same - the cameras give 29.9 pictures a second and the loop asked for 30 -
                    // so it gained four tenths of a frame every second and, at the moment the
                    // phases crossed, photographed the canvas before the new picture had landed
                    // and used the old one again. Measured, that was one sample in five, and a
                    // stale cell is worse to look at than a lost frame: the quadrant holds still
                    // for a frame and then moves twice as far, which is what a driver calls
                    // stuttering.
                    //
                    // Waiting a little for the late ones cut it to 5%, and deinterlacing - which
                    // pushes the work per camera from 8.8 ms to 19.5 - put it straight back to
                    // 19%, because the wait was capped and the cells now arrive after the cap.
                    // Patching a race by being more patient only works until something slows
                    // down.
                    //
                    // So the sleep is gone. The last camera to complete the picture wakes this
                    // thread, and a stale cell stops being unlikely and becomes impossible: the
                    // frame cannot be composed before the frame exists. The output rate is the
                    // cameras' own, which is the rate the footage was always going to have.
                    //
                    // The cap is two frames, and it is the way out rather than the mechanism: a
                    // camera the factory 360 view has taken is not late, it is gone, and the
                    // other three must not be held for it.
                    countWaitFresh(awaitFreshCells(frameDurationUs_ * 2));
                    {
                        std::lock_guard<std::mutex> lock(encoderMutex_);
                        drainEncoderLocked(0);
                    }
                    // And then, if the requested rate is slower than the cameras, hold.
                    //
                    // Waiting for a complete picture fixes *which* picture gets encoded; it
                    // says nothing about how often. Without this the loop ran at whatever the
                    // cameras delivered, and the timestamps - still spaced by the rate the
                    // driver asked for - stretched to cover it: at the default 25 against
                    // cameras giving 29.9, a thirty-second clip claimed thirty-six and played
                    // a fifth slow. The author's car never showed it because it had been set
                    // to 30, where the two rates agree.
                    //
                    // Sleeping here rather than dropping the picture: by the time the slot
                    // comes round the cameras have usually delivered another complete one, so
                    // what gets encoded is the newest picture rather than the one that happened
                    // to arrive first - and it is still complete, which is the property the
                    // wait above exists to guarantee.
                    const int64_t owedUs = nextPtsUs_ - (nowUs() - startUs_);
                    if (owedUs > 0)
                    {
                        const int64_t beforeUs = nowUs();
                        std::this_thread::sleep_for(std::chrono::microseconds(owedUs));
                        countSleep(owedUs, nowUs() - beforeUs);
                    }
                    {
                        std::lock_guard<std::mutex> lock(canvasMutex_);
                        if ((cellsFreshMask_ & cameraMask_) != cameraMask_) continue;
                    }
                    encodeOneFrame();
                }
            }

            /**
             * Waits, briefly, for the cameras to finish the picture this sample is about to take.
             *
             * <p>Measured before this existed: **21.5% of samples found a cell nobody had
             * rewritten**, 2405 cells over 8180 samples. The cause is two free-running clocks -
             * the cameras deliver at 29.9 fps and this loop sampled at 30.3, so it gained about
             * four tenths of a frame a second and now and then arrived before the picture did.
             * With four cameras each on its own phase, one cell in fourteen was old, which is
             * one sample in five.
             *
             * <p>A stale cell is not a lost frame - it is worse to look at than that. The
             * quadrant holds still for one frame and then moves twice as far, which is what a
             * driver sees as stuttering, and no amount of correct timestamps hides it because
             * the timestamps were never wrong: the picture was.
             *
             * <p>So the sampler now follows the cameras instead of a wall clock. Waiting is
             * bounded at half a period: past that, a camera is not late, it is gone - the
             * factory 360 view has taken it, or it never arrived - and holding the whole grid
             * for it would cost road. The pacing loop needs no changes, because a sample that
             * waited leaves less owed to the next one, and the grid the timestamps snap to
             * absorbs the rest.
             */
            int64_t awaitFreshCells(int64_t maxWaitUs)
            {
                if (maxWaitUs <= 0)
                {
                    return 0;
                }
                const int64_t began = nowUs();
                const int64_t deadlineUs = began + maxWaitUs;
                while (!stopRequested_.load() && !isFinalized())
                {
                    std::unique_lock<std::mutex> lock(canvasMutex_);
                    // Only cameras that have ever written: one that never arrived is absent,
                    // not late, and waiting for it every frame would slow everything down.
                    const int expected = cameraMask_;
                    if (expected != 0 && (cellsFreshMask_ & expected) == expected)
                    {
                        break;
                    }
                    const int64_t leftUs = deadlineUs - nowUs();
                    if (leftUs <= 0)
                    {
                        break;
                    }
                    // Capped rather than run to the deadline: the two conditions above this
                    // wait are not ones a signal can carry, and a thread asleep on a camera is
                    // a thread not noticing that it has been told to stop.
                    canvasCv_.wait_for(lock,
                                       std::chrono::microseconds(std::min<int64_t>(leftUs, 20000)));
                }
                return nowUs() - began;
            }

            void encodeOneFrame()
            {
                long long snapUs = 0;
                long long footerUs = 0;
                snapshotCanvas(encoderFrame_, snapUs, footerUs);
                countComposedFrame(nowUs());

                const int64_t feedStartUs = nowUs();
                std::lock_guard<std::mutex> lock(encoderMutex_);
                if (isFinalized() || stopRequested_.load())
                {
                    return;
                }
                if (!isRecording())
                {
                    // Preview only. The pts gate still paces us, so a preview costs the same
                    // snapshot as a recording and nothing more.
                    nextPtsUs_ += frameDurationUs_;
                    return;
                }
                if (encoderColorFormat_ != COLOR_FORMAT_YUV420_SEMIPLANAR)
                {
                    // This encoder wants the chroma split apart. Done in place on the snapshot,
                    // which nobody else is looking at.
                    packNv12ToI420InPlace(encoderFrame_, gridWidth_, totalHeight_);
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
                        // The slot this frame belongs in, which is not the same as the moment
                        // it happened to be composed. See alignedPts.
                        const int64_t pts = alignedPts(nowUs() - startUs_, frameDurationUs_,
                                                       lastPtsUs_);
                        lastPtsUs_ = pts;
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
                else
                {
                    // The codec has nothing free. Nothing was consumed, so the pts gate has not
                    // moved and this loop would spin on it; give the encoder a frame's grace.
                    std::this_thread::sleep_for(std::chrono::microseconds(2000));
                }

                const int64_t drainStartUs = nowUs();
                drainEncoderLocked(0);
                countEncodeStages(snapUs, footerUs, drainStartUs - feedStartUs,
                                  nowUs() - drainStartUs);
            }

            void finalize()
            {
                if (finalized_.exchange(true))
                {
                    return;
                }

                // The encoder thread reads the codec on every pass: it has to be gone before
                // the codec is. It cannot be this thread - finalize() is called from outside -
                // but check anyway rather than deadlock if that ever changes.
                for (std::thread *worker : {&encoderThread_, &previewThread_})
                {
                    if (!worker->joinable())
                    {
                        continue;
                    }
                    if (worker->get_id() == std::this_thread::get_id())
                    {
                        worker->detach();
                    }
                    else
                    {
                        worker->join();
                    }
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
                        if (AMediaMuxer_stop(muxer_) != AMEDIA_OK) gWriteFailed = true;
                    }
                    AMediaMuxer_delete(muxer_);
                    muxer_ = nullptr;
                }

                if (outFd_ >= 0)
                {
                    // Closing a descriptor does not put anything on the device: the clip sits
                    // in the page cache, and on this head unit the power can go before the
                    // kernel gets round to it. Thirty seconds at 9 Mbit/s is 34 MB of data plus
                    // the FAT entries describing it, and losing half of that is how a stick
                    // comes back mounted read-only on the next drive - which has happened on
                    // both sticks in use, once after a standby and once with an early build,
                    // each time needing a filesystem check to become writable again.
                    //
                    // Once per clip: the cost lands between segments, thirty seconds apart.
                    flushAndCloseInBackground(outFd_, outputPath_);
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

            /**
             * Where each camera's quarter sits. Front and rear share the top row because that is
             * the pair worth reading together when working out who came from where; left and
             * right go below, each on the side it belongs to.
             */
            void cellOriginLocked(int sourceIndex, int &x, int &y) const
            {
                switch (sourceIndex)
                {
                case 0:  x = 0;          y = 0;           break;  // front
                case 3:  x = cellWidth_; y = 0;           break;  // rear
                case 2:  x = 0;          y = cellHeight_; break;  // left
                default: x = cellWidth_; y = cellHeight_; break;  // right
                }
            }

            /**
             * Writes one camera's buffer straight into its quarter of the NV12 canvas.
             *
             * <p>This replaces a chain that went UYVY -> RGBA in the capture thread, RGBA ->
             * a per-camera holding frame, four holding frames -> an RGBA canvas, canvas -> I420,
             * I420 -> NV12, and finally NV12 -> the codec. Six passes over full frames, more
             * than a gigabyte a second across four cameras, and the measured cost was half the
             * frame rate. UYVY and NV12 are both YCbCr; nothing in between needed to exist.
             *
             * <p>No mirroring, deliberately. Upstream flips the rear view horizontally to match
             * what a driver expects from a mirror, which is right when reversing and wrong in an
             * archive: it reverses every number plate behind you.
             */
            /** All that happens under the lock: two copies into the canvas planes. */
            void writeCellLocked(int sourceIndex, const CellStaging &staging)
            {
                if (sourceIndex >= 0 && sourceIndex < 4)
                {
                    cellsWrittenMask_ |= (1 << sourceIndex);
                    cellsFreshMask_ |= (1 << sourceIndex);
                    // The compositor sleeps until the picture is complete, so the camera that
                    // completes it is the one that wakes it. Signalled under canvasMutex_,
                    // which is what the sleeper holds: outside it this would race the check.
                    const int expected = cameraMask_;
                    if (expected != 0 && (cellsFreshMask_ & expected) == expected)
                    {
                        canvasCv_.notify_one();
                    }
                }
                int x = 0;
                int y = 0;
                cellOriginLocked(sourceIndex, x, y);

                staging.lumaFull.copyTo(nv12Canvas_(cv::Rect(x, y, cellWidth_, cellHeight_)));

                cv::Mat destBytes = nv12Canvas_(
                    cv::Rect(x, totalHeight_ + (y / 2), cellWidth_, cellHeight_ / 2));
                cv::Mat dest(destBytes.rows, destBytes.cols / 2, CV_8UC2,
                             destBytes.data, destBytes.step[0]);
                if (staging.chroma.rows == dest.rows)
                {
                    staging.chroma.copyTo(dest);
                }
                else
                {
                    cv::resize(staging.chroma, dest, dest.size(), 0, 0, cv::INTER_NEAREST);
                }
            }

            /** For the encoders that ask for planar I420 instead: split the interleaved chroma. */
            static void packNv12ToI420InPlace(std::vector<uint8_t> &frame, int width, int height)
            {
                const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
                const size_t pairs = ySize / 4;
                std::vector<uint8_t> chroma(frame.begin() + static_cast<long>(ySize), frame.end());
                uint8_t *u = frame.data() + ySize;
                uint8_t *v = u + pairs;
                for (size_t i = 0; i < pairs; i++)
                {
                    u[i] = chroma[2 * i];
                    v[i] = chroma[2 * i + 1];
                }
            }

            // A 2x2 grid, every cell at the shape its camera actually has. The old layout stood
            // the side cameras on end and stacked front and rear between them, which left 18% of
            // every frame black and squashed all four views - the centre ones vertically, the
            // sides horizontally once rotated. Nothing about that was chosen for a recording:
            // the cell size came from upstream's full-screen single-camera view, and the grid
            // was assembled out of whatever shape that left.
            //
            // There is no compose step any more. Each camera writes into its own quarter as its
            // frame arrives, so the canvas is always current and nothing is copied twice. It is
            // never cleared either: the four quarters cover every pixel above the footer, and
            // clearing 1040 rows to black only to overwrite them was 150 MB a second.

            /**
             * The footer, cached.
             *
             * <p>It costs 4.3 ms to draw - half of everything the encoder thread does - and its
             * only moving part is a clock reading whole seconds. So it is drawn when the second
             * changes and copied the other twenty-four times.
             */
            void drawFooter(cv::Mat &frame)
            {
                const std::time_t second = std::time(nullptr);
                cv::Rect luma(0, gridHeight_, gridWidth_, footerHeight_);
                cv::Rect chroma(0, totalHeight_ + (gridHeight_ / 2), gridWidth_, footerHeight_ / 2);
                if (footerSecond_ != second || footerCache_.empty())
                {
                    footerSecond_ = second;
                    footerCache_.create(footerHeight_ + (footerHeight_ / 2), gridWidth_, CV_8UC1);
                    renderFooter(frame);
                    frame(luma).copyTo(footerCache_(cv::Rect(0, 0, gridWidth_, footerHeight_)));
                    frame(chroma).copyTo(
                        footerCache_(cv::Rect(0, footerHeight_, gridWidth_, footerHeight_ / 2)));
                    return;
                }
                footerCache_(cv::Rect(0, 0, gridWidth_, footerHeight_)).copyTo(frame(luma));
                footerCache_(cv::Rect(0, footerHeight_, gridWidth_, footerHeight_ / 2))
                    .copyTo(frame(chroma));
            }

            void renderFooter(cv::Mat &frame)
            {
                frame(cv::Rect(0, gridHeight_, gridWidth_, footerHeight_))
                    .setTo(cv::Scalar(FOOTER_LUMA));
                // Neutral chroma for the strip, so the grey stays grey.
                frame(cv::Rect(0, totalHeight_ + (gridHeight_ / 2),
                                     gridWidth_, footerHeight_ / 2))
                    .setTo(cv::Scalar(128));
                cv::line(frame,
                         cv::Point(0, gridHeight_),
                         cv::Point(gridWidth_, gridHeight_),
                         cv::Scalar(RULE_LUMA),
                         2,
                         cv::LINE_AA);

                const int baselineY = gridHeight_ + 50;
                const double fontScale = 0.78;
                const int thickness = 2;
                const int marginX = 24;
                const cv::Scalar textColor(TEXT_LUMA);

                if (!signature_.empty())
                {
                    cv::putText(frame,
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
                cv::putText(frame,
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
                                                 static_cast<uint64_t>(
                                                     std::max<int64_t>(0, nowUs() - startUs_)),
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
                                muxerStarted_ = AMediaMuxer_start(muxer_) == AMEDIA_OK;
                                if (!muxerStarted_) gWriteFailed = true;
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
                            if (AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(trackIndex_), outputBuffer, &info) == AMEDIA_OK) {
                                gWrittenSamples++;
                            } else {
                                gWriteFailed = true;
                            }
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
            const int cameraMask_;
            /** Cells written at least once since this sink started, and since the last sample. */
            int cellsWrittenMask_ = 0;
            int cellsFreshMask_ = 0;
            const int cellWidth_;
            const int cellHeight_;
            const int gridWidth_;
            const int gridHeight_;
            const int footerHeight_;
            const int totalHeight_;
            const int fps_;
            const int bitrate_;
            const std::string signature_;
            const bool showSpeed_;

            int64_t frameDurationUs_ = 0;
            int64_t startUs_ = 0;
            int64_t nextPtsUs_ = 0;
            int64_t lastPtsUs_ = -1;
            int64_t frameCount_ = 0;
            int encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;

            AMediaCodec *codec_ = nullptr;
            AMediaMuxer *muxer_ = nullptr;
            int outFd_ = -1;
            ssize_t trackIndex_ = -1;
            bool muxerStarted_ = false;
            bool eosQueued_ = false;

            // Footer shades, as luma. OpenCV writes studio-range Y, so these are the values the
            // RGBA footer used to end up with once converted: 14, 70 and 235 grey.
            static constexpr uint8_t FOOTER_LUMA = 28;
            static constexpr uint8_t RULE_LUMA = 76;
            static constexpr uint8_t TEXT_LUMA = 218;

            cv::Mat previewSnapshot_;
            std::thread previewThread_;
            cv::Mat footerCache_;
            std::time_t footerSecond_ = 0;

            /** Guards the codec and the muxer. Only the encoder thread takes it in anger. */
            std::mutex encoderMutex_;
            /** Guards the canvas: four camera threads write it, the encoder thread copies it. */
            std::mutex canvasMutex_;
            /** Raised when the last cell a frame was waiting for has been written. */
            std::condition_variable canvasCv_;
            /** Guards the preview surface, which outlives neither of the other two. */
            std::mutex previewMutex_;
            std::thread encoderThread_;
            /** The frame itself, NV12, written in place by the cameras and copied to the codec. */
            cv::Mat nv12Canvas_;
            /** Where the canvas is also shown, when somebody is looking. */
            ANativeWindow *previewWindow_ = nullptr;
            int64_t lastPreviewUs_ = 0;
            /** Only allocated while a preview surface is attached. */
            cv::Mat rgbaPreview_;
            cv::Mat lumaCell_;
            cv::Mat chromaU_;
            cv::Mat chromaV_;
            cv::Mat chromaPair_;
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

            /** Never called: this tap asks for the packed buffer instead. */
            void processFrame(const cv::Mat & /*rgbaFrame*/) override {}

            bool wantsPackedFrame() const override { return true; }

            void processPackedFrame(const cv::Mat &packedFrame) override
            {
                if (sink_ != nullptr)
                {
                    sink_->processSourcePackedFrame(sourceIndex_, packedFrame);
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
                    sink_->requestStop();
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
                    }
                    shouldStop = consumers_.empty() && !keepWarm_;
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

            /**
             * Lets a consumer go without waiting for it to finish.
             *
             * <p>stopConsumer() below waits for the consumer to report itself stopped, and a
             * combined tap answers that question for the whole shared sink - so releasing the
             * first camera waits for the entire encoder to drain. That is right when a clip is
             * being closed properly and wrong when the factory 360 view is asking for the
             * cameras: measured on the car, it gives up after about 210 ms, and the drain alone
             * costs up to 230.
             *
             * <p>What the factory app needs is the video device, not our file. So the consumer
             * is dropped, the capture comes down, and the encoder is left to finish into a file
             * nobody is waiting for.
             */
            bool detachConsumer(int consumerId)
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
                    consumers_.erase(it);
                }
                consumer->requestStop();

                bool shouldStop;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    shouldStop = (previewWindow_ == nullptr && consumers_.empty() && !keepWarm_);
                    if (shouldStop)
                    {
                        stopRequested_.store(true);
                    }
                }
                if (shouldStop)
                {
                    requestStopAndJoin();
                }
                return true;
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
                    consumers_.erase(consumerId);
                    shouldStop = (previewWindow_ == nullptr && consumers_.empty() && !keepWarm_);
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
                return previewWindow_ == nullptr && consumers_.empty() && !running_.load()
                       && !keepWarm_;
            }

            /**
             * Holds the camera open across a clip rotation, or lets it go.
             *
             * <p>Rotating a clip used to close all four video devices and reopen them a fifth of
             * a second later, identically: the session counts its consumers, the recorder was the
             * only one, and a session nobody reads from tears its capture down. Measured on the
             * car, that teardown and rebuild was 254 ms of the 640 ms hole between one clip and
             * the next - road that simply was not filmed, seventy-eight times an hour.
             *
             * <p>With the hold set the capture thread keeps running with nothing attached to it,
             * which costs a dequeue and a requeue per frame and no copying at all: the expensive
             * part of the loop is skipped when there is nobody to hand a frame to.
             *
             * <p>Releasing tears the capture down at once when nothing else is using the camera.
             * That is what makes it safe to set: every path that really means to stop - the
             * service stopping, the screen going off, the factory 360 view asking for the
             * devices - releases, and the camera is gone by the time the call returns.
             */
            void setKeepWarm(bool warm)
            {
                bool release = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    keepWarm_ = warm;
                    keepWarmDeadlineUs_ = warm ? nowUs() + KEEP_WARM_HOLD_MS * 1000LL : 0;
                    release = !warm && !hasConsumersLocked();
                }
                if (release)
                {
                    requestStopAndJoin();
                }
            }

        private:
            bool hasConsumersLocked() const
            {
                return previewWindow_ != nullptr || !consumers_.empty();
            }

            /**
             * Drops a hold the recorder asked for and never came back to claim.
             *
             * <p>Runs from the capture loop's housekeeping, so it is checked about four times a
             * second - late enough to cost nothing, soon enough that a camera is never held for
             * long by a recorder that has gone away.
             */
            void expireKeepWarmLocked()
            {
                if (!keepWarm_ || nowUs() < keepWarmDeadlineUs_)
                {
                    return;
                }
                logw("/dev/video%d: keep-warm hold lapsed, releasing the camera", videoIndex_);
                keepWarm_ = false;
                keepWarmDeadlineUs_ = 0;
                if (!hasConsumersLocked())
                {
                    stopRequested_.store(true);
                }
            }

            void configurePreviewWindowLocked()
            {
                if (previewWindow_ == nullptr || cropWidth_ <= 0 || cropHeight_ <= 0)
                {
                    return;
                }
                ANativeWindow_setBuffersGeometry(previewWindow_, cropWidth_, cropHeight_,
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
                // One field is half the buffer; the frame is the whole of it. The device reports
                // 720x480 and calls it progressive, and it is not: the two halves are the two
                // fields of one interlaced frame. See docs/camera-format.md - it is measured, not
                // assumed. Keeping one field, which is what this app did until now, threw away
                // 127% more vertical detail than it kept.
                fieldHeight_ = srcHeight_ / 2;
                // Both fields now, because the compositor weaves them. Handing over one of
                // them was the state of things from beta.6 until here, and it threw away the
                // half of the vertical detail that is sitting in the same buffer.
                //
                // The price is paid here and nowhere else: the copy out of the mapped buffer
                // goes from 345,600 bytes to 691,200. That memory is not cached on this SoC -
                // about 85 MB/s, measured - so the read is the whole cost of this feature and
                // the weaving itself is almost free, happening on the cached copy afterwards.
                cropHeight_ = srcHeight_;

                {
                    char line[256];
                    snprintf(line, sizeof(line),
                             "/dev/video%d: %s %ux%u stride=%u size=%u field=%u %s -> using %dx%d",
                             videoIndex_, fourccToString(pixelFormat_).c_str(),
                             format.fmt.pix.width, format.fmt.pix.height,
                             format.fmt.pix.bytesperline, format.fmt.pix.sizeimage,
                             format.fmt.pix.field, fieldName(format.fmt.pix.field),
                             cropWidth_, cropHeight_);
                    logi("%s", line);
                    rememberFormat(videoIndex_, line);
                }

                {
                    // What rate the device thinks it runs at, and whether that is ours to set.
                    // We have never asked: the frame interval has been whatever the driver came
                    // up with, and the factory app is getting 25 fps out of the same hardware.
                    v4l2_streamparm parm{};
                    parm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                    char line[160];
                    if (ioctl(fd_, VIDIOC_G_PARM, &parm) == 0)
                    {
                        const auto &tpf = parm.parm.capture.timeperframe;
                        snprintf(line, sizeof(line),
                                 "    G_PARM: %u/%u s per frame (%.1f fps), capability=0x%x,"
                                 " settable=%s, driver buffers=%u",
                                 tpf.numerator, tpf.denominator,
                                 tpf.numerator > 0
                                     ? static_cast<double>(tpf.denominator) / tpf.numerator
                                     : 0.0,
                                 parm.parm.capture.capability,
                                 (parm.parm.capture.capability & V4L2_CAP_TIMEPERFRAME) ? "yes" : "no",
                                 parm.parm.capture.readbuffers);
                    }
                    else
                    {
                        snprintf(line, sizeof(line), "    G_PARM: not supported (errno %d)", errno);
                    }
                    logi("%s", line);
                    appendFormatDetail(videoIndex_, line);
                }

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
                if (worker_.joinable() && worker_.get_id() == std::this_thread::get_id())
                {
                    // Cannot happen today - this runs on whichever thread asked to record, never
                    // on the capture thread - but skipping the join below and falling through to
                    // the assignment would be the very bug this guard exists to prevent.
                    worker_.detach();
                }
                if (worker_.joinable())
                {
                    // The previous loop can end on its own, and usually does: once the last
                    // consumer is gone, cleanupStoppedConsumers() sets stopRequested_ from
                    // inside the loop, threadLoop() breaks out and clears running_ - and
                    // nothing joins worker_. That is exactly what happens every time the
                    // cameras are handed to the factory 360 view.
                    //
                    // Move-assigning onto a joinable std::thread is std::terminate() by the
                    // standard, so without this the process aborted the next time recording
                    // started: SIGABRT, "terminating", on whichever thread asked to record.
                    // The thread has already finished, so the join returns at once.
                    worker_.join();
                }
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
                    // The second place that decides a camera nobody reads from should go
                    // down, and the one that made the first attempt at this useless: a rotation
                    // set the hold, stopConsumer() honoured it, and a quarter of a second later
                    // this ran from the capture loop's own housekeeping and stopped the camera
                    // anyway. Measured on a 21-minute drive: the cameras were down for 169 s of
                    // it, against 168 s of rotations. The hold held nothing.
                    if (previewWindow_ == nullptr && consumers_.empty() && !keepWarm_)
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

            /**
             * How much a pixel must change between one frame and the next to count as moving.
             *
             * <p>In luma levels, over 1/25 s. Sensor noise is two or three even in poor light, so
             * twelve is clear of it while still catching a slow pan.
             */
            static constexpr int MOTION_THRESHOLD = 12;

            /**
             * Rebuilds the full frame from the two fields in the buffer, without combing.
             *
             * <p>The even lines are the top field and are always taken as they are: they were
             * captured, they are real. Every odd line has two candidates - the bottom field line
             * that was captured at that position, and the average of the top-field lines above
             * and below it.
             *
             * <p>Which one is right depends on whether anything moved in the 1/50 s between the
             * two fields. Weaving a still scene doubles the vertical resolution honestly; weaving
             * a moving one serrates every edge, because the two halves of the picture are showing
             * different moments. So the choice is made per pixel, by asking whether the captured
             * line disagrees with *both* of its neighbours in the same direction - which is what a
             * combed edge looks like and what a vertical detail does not.
             *
             * <p>Where it disagrees, the interpolation is used: that is exactly the picture this
             * app recorded before, so the worst case of this whole change is the old behaviour,
             * on the pixels that would have combed.
             */
            void deinterlaceLocked(const cv::Mat &packedFrame)
            {
                const int code = rgbaConversionCode(packedFormat_);
                const cv::Mat packedTop = packedFrame(cv::Rect(0, 0, cropWidth_, fieldHeight_));
                const cv::Mat packedBot = packedFrame(cv::Rect(0, fieldHeight_, cropWidth_, fieldHeight_));

                cv::cvtColor(packedTop, rgbaTopField_, code);
                cv::cvtColor(packedBot, rgbaBotField_, code);

                // Luma straight out of the packed pairs: UYVY carries it in channel 1, so this is
                // a strided read rather than another colour conversion.
                cv::extractChannel(packedTop, lumaTopField_, 1);
                cv::extractChannel(packedBot, lumaBotField_, 1);

                const int last = fieldHeight_ - 1;
                interpolated_.create(fieldHeight_, cropWidth_, CV_8UC4);
                cv::addWeighted(rgbaTopField_.rowRange(0, last), 0.5,
                                rgbaTopField_.rowRange(1, fieldHeight_), 0.5, 0.0,
                                interpolated_.rowRange(0, last));
                // The last odd line has no top-field line below it to average with.
                rgbaTopField_.row(last).copyTo(interpolated_.row(last));

                // Motion is found by comparing this frame's top field with the last one: same
                // lines, same parity, so a difference can only be movement or noise - never
                // vertical detail.
                //
                // The first attempt asked instead whether a bottom-field pixel sat outside both
                // of its top-field neighbours, which needs no history but cannot tell a moving
                // edge from a sharp one. On the car it did exactly the wrong thing both ways:
                // interpolated half of a still lawn, throwing away the detail this whole change
                // exists to recover, and left the combing on a walking leg untouched - because at
                // the edge of a moving object the two neighbours straddle the edge, the two
                // differences take opposite signs, and the test cancels itself out.
                if (lumaTopPrev_.size() == lumaTopField_.size() && lumaTopPrev_.type() == lumaTopField_.type())
                {
                    cv::absdiff(lumaTopField_, lumaTopPrev_, motionDiff_);
                    cv::compare(motionDiff_, MOTION_THRESHOLD, combMask_, cv::CMP_GT);
                    // Combing shows at the edges of a moving thing, and those edges move too.
                    // Growing the mask by a pixel keeps the fringe from being woven.
                    cv::dilate(combMask_, combMask_, cv::Mat());
                }
                else
                {
                    // No previous frame to compare against: interpolate everything, which is what
                    // the app produced before any of this.
                    combMask_.create(fieldHeight_, cropWidth_, CV_8UC1);
                    combMask_.setTo(cv::Scalar(255));
                }
                lumaTopField_.copyTo(lumaTopPrev_);

                rgbaScratch_.create(cropHeight_, cropWidth_, CV_8UC4);
                // Views onto alternate lines of the output: same data, twice the row step. Lets
                // both fields be written with one copy each instead of a loop over 480 rows.
                cv::Mat evenLines(fieldHeight_, cropWidth_, CV_8UC4,
                                  rgbaScratch_.data, rgbaScratch_.step[0] * 2);
                cv::Mat oddLines(fieldHeight_, cropWidth_, CV_8UC4,
                                 rgbaScratch_.data + rgbaScratch_.step[0], rgbaScratch_.step[0] * 2);
                rgbaTopField_.copyTo(evenLines);
                rgbaBotField_.copyTo(oddLines);
                interpolated_.copyTo(oddLines, combMask_);
            }

            bool hasPreviewLocked() const { return previewWindow_ != nullptr; }

            void renderPreviewLocked(const cv::Mat &rgbaFrame)
            {
                if (previewWindow_ == nullptr)
                {
                    return;
                }

                const cv::Mat *previewSource = &rgbaFrame;
                if (videoIndex_ == CAMERA_INDEX_REAR)
                {
                    previewScratch_.create(rgbaFrame.rows, rgbaFrame.cols, rgbaFrame.type());
                    cv::flip(rgbaFrame, previewScratch_, 1);
                    previewSource = &previewScratch_;
                }

                ANativeWindow_Buffer outBuffer{};
                if (ANativeWindow_lock(previewWindow_, &outBuffer, nullptr) != 0)
                {
                    return;
                }

                const int copyWidth = std::min(previewSource->cols, outBuffer.width);
                const int copyHeight = std::min(previewSource->rows, outBuffer.height);
                const uint8_t *src = previewSource->data;
                uint8_t *dst = static_cast<uint8_t *>(outBuffer.bits);
                const int srcStrideBytes = static_cast<int>(previewSource->step[0]);
                const int dstStrideBytes = outBuffer.stride * 4;
                for (int row = 0; row < copyHeight; row++)
                {
                    std::memcpy(dst + row * dstStrideBytes, src + row * srcStrideBytes, static_cast<size_t>(copyWidth) * 4U);
                }

                ANativeWindow_unlockAndPost(previewWindow_);
            }

            void threadLoop()
            {
                // OpenCV is linked statically here and reports every failure by throwing:
                // an unexpected frame size, a conversion code it does not know, a failed
                // allocation. Nothing in this file used to catch any of it, so a bad frame from
                // a camera the factory app was taking away did not end a capture - it called
                // std::terminate and took the whole process with it.
                //
                // Ending this one camera's capture is the right size of failure. The cleanup
                // below still runs, and ensureStartedLocked() can start it again afterwards.
                try
                {
                    // Housekeeping that used to run on every frame: two turns of the session
                    // mutex, with four capture threads contending for it. Measured, the loop
                    // spent 18 ms a frame outside the select and outside the work - more than
                    // the work itself. None of it is urgent: a consumer that stopped can be
                    // reaped a fifth of a second later without consequence.
                    static constexpr int HOUSEKEEPING_EVERY = 8;
                    int sinceHousekeeping = HOUSEKEEPING_EVERY;

                    while (running_.load())
                    {
                        const int64_t iterationStartUs = nowUs();
                        if (++sinceHousekeeping >= HOUSEKEEPING_EVERY)
                        {
                            sinceHousekeeping = 0;
                            bool shouldExit = false;
                            {
                                std::lock_guard<std::mutex> lock(mutex_);
                                expireKeepWarmLocked();
                                shouldExit = stopRequested_.load() && !hasConsumersLocked();
                            }
                            if (shouldExit)
                            {
                                break;
                            }
                            cleanupStoppedConsumers();
                        }
                        else if (stopRequested_.load())
                        {
                            // Stopping is the one thing worth checking every time round, and
                            // the flag is atomic: no lock needed to notice it.
                            sinceHousekeeping = HOUSEKEEPING_EVERY;
                        }

                        // One read of the descriptor, and only while it is still open.
                        //
                        // The thread tearing the session down closes it under mutex_ and leaves
                        // -1 behind; this loop reads it outside that lock, so it can go away
                        // between the exit check above and this line. select() and the ioctl
                        // below merely fail on a closed descriptor, and the loop already treats
                        // a failure as nothing to do. FD_SET does not fail: FORTIFY turns a
                        // negative descriptor into abort(), which takes down the whole process
                        // rather than the thread. On the car that is a black screen and the
                        // service coming back - blamed on the head unit until a tombstone
                        // named us.
                        const int fd = fd_;
                        if (fd < 0)
                        {
                            break;
                        }
                        fd_set readSet;
                        FD_ZERO(&readSet);
                        FD_SET(fd, &readSet);
                        timeval timeout{0, PREVIEW_SELECT_TIMEOUT_US};
                        const int64_t waitStartUs = nowUs();
                        const int ready = select(fd + 1, &readSet, nullptr, nullptr, &timeout);
                        if (ready <= 0)
                        {
                            countEmptySelect(videoIndex_, nowUs() - iterationStartUs);
                            sinceHousekeeping = HOUSEKEEPING_EVERY;
                            continue;
                        }
                        const int64_t waitedUs = nowUs() - waitStartUs;

                        v4l2_buffer buffer{};
                        buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                        buffer.memory = V4L2_MEMORY_MMAP;
                        if (ioctl(fd, VIDIOC_DQBUF, &buffer) < 0)
                        {
                            continue;
                        }
                        const int64_t workStartUs = nowUs();

                        if (buffer.index >= buffers_.size()
                            || srcStrideBytes_ < srcWidth_ * 2
                            || buffers_[buffer.index].length < static_cast<size_t>(srcStrideBytes_) * srcHeight_) {
                            loge("invalid camera buffer bounds on /dev/video%d", videoIndex_);
                            break;
                        }
                        cv::Mat packedFrame(srcHeight_, srcWidth_, CV_8UC2, buffers_[buffer.index].start, srcStrideBytes_);
                        cv::Mat packedCrop = packedFrame(cv::Rect(0, 0, cropWidth_, cropHeight_));
                        countFrame(videoIndex_, nowUs(), buffer.sequence);

                        std::vector<std::shared_ptr<FrameConsumer>> consumers;
                        bool needRgba;
                        {
                            std::lock_guard<std::mutex> lock(mutex_);
                            // The per-camera preview is RGBA and so is any consumer that has not
                            // asked for the packed buffer. If neither is here, the conversion
                            // below is work nobody would look at.
                            needRgba = hasPreviewLocked();
                            consumers.reserve(consumers_.size());
                            for (const auto &entry : consumers_)
                            {
                                consumers.push_back(entry.second);
                                if (entry.second != nullptr && !entry.second->wantsPackedFrame())
                                {
                                    needRgba = true;
                                }
                            }
                        }

                        if (needRgba)
                        {
                            // One field for the preview, not the whole buffer. What arrives now
                            // is two fields stacked, and converting that as if it were a picture
                            // shows the top half of the scene above the top half of the scene
                            // again - which looks far more broken than the combing it would be
                            // mistaken for. The preview is a picture to glance at, not the
                            // recording: a field stretched by the display is enough for it, and
                            // it costs one pass instead of a weave per frame per camera.
                            const cv::Mat previewSrc =
                                    packedCrop.rows > fieldHeight_
                                            ? packedCrop(cv::Rect(0, 0, cropWidth_, fieldHeight_))
                                            : packedCrop;
                            rgbaScratch_.create(previewSrc.rows, cropWidth_, CV_8UC4);
                            cv::cvtColor(previewSrc, rgbaScratch_, rgbaConversionCode(packedFormat_));
                            std::lock_guard<std::mutex> lock(mutex_);
                            renderPreviewLocked(rgbaScratch_);
                        }

                        for (const auto &consumer : consumers)
                        {
                            if (consumer == nullptr)
                            {
                                continue;
                            }
                            if (consumer->wantsPackedFrame())
                            {
                                if (packedFormat_ != PackedFormat::UYVY) {
                                    loge("combined capture requires UYVY on /dev/video%d", videoIndex_);
                                    consumer->requestStop();
                                } else {
                                    consumer->processPackedFrame(packedCrop);
                                }
                            }
                            else
                            {
                                consumer->processFrame(rgbaScratch_);
                            }
                        }

                        ioctl(fd_, VIDIOC_QBUF, &buffer);
                        countTimings(videoIndex_, nowUs() - workStartUs, waitedUs,
                                     nowUs() - iterationStartUs);
                    }

                }
                catch (const std::exception &e)
                {
                    loge("capture loop for /dev/video%d ended on an exception: %s",
                         videoIndex_, e.what());
                }
                catch (...)
                {
                    loge("capture loop for /dev/video%d ended on an unknown exception",
                         videoIndex_);
                }

                cleanupStoppedConsumers();
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    // Whatever brought the loop down, the hold goes with it. It exists to keep
                    // a capture alive; over a capture that has already ended it would only
                    // keep the session from ever reporting itself idle, and the expiry that
                    // should have cleared it runs from this very loop.
                    keepWarm_ = false;
                    keepWarmDeadlineUs_ = 0;
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
                rgbaTopField_.release();
                rgbaBotField_.release();
                lumaTopField_.release();
                lumaBotField_.release();
                interpolated_.release();
                lumaTopPrev_.release();
                motionDiff_.release();
                combMask_.release();
                srcWidth_ = 0;
                srcHeight_ = 0;
                srcStrideBytes_ = 0;
                cropWidth_ = 0;
                cropHeight_ = 0;
                fieldHeight_ = 0;
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
                    if (worker.get_id() == std::this_thread::get_id())
                    {
                        // We are the capture thread, tearing down the session we belong to.
                        //
                        // That happens because the thread holds a shared_ptr to its own session:
                        // eraseSessionIfIdle() can drop the map's reference in the moment between
                        // threadLoop() clearing running_ and the lambda being destroyed, and then
                        // the last reference is ours. The destructor runs here, on this thread.
                        //
                        // Joining yourself is EDEADLK, join() throws, and the local std::thread is
                        // then destroyed still joinable - which is std::terminate(), called with
                        // no exception caught yet. That is the bare "terminating" abort on the car.
                        //
                        // Detaching is correct rather than merely safe: this thread is one
                        // statement from returning, and there is nothing left to wait for.
                        worker.detach();
                    }
                    else
                    {
                        worker.join();
                    }
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
            /** The full frame height: what consumers now receive. */
            int cropHeight_ = 0;
            /** Half of it: the height of one field, and of every scratch buffer below. */
            int fieldHeight_ = 0;
            std::vector<MappedBuffer> buffers_;
            ANativeWindow *previewWindow_ = nullptr;
            std::unordered_map<int, std::shared_ptr<FrameConsumer>> consumers_;
            std::atomic<bool> running_{false};
            std::atomic<bool> stopRequested_{false};
            /** Set while the recorder is between clips and means to come straight back. */
            bool keepWarm_ = false;
            int64_t keepWarmDeadlineUs_ = 0;
            std::thread worker_;
            cv::Mat rgbaScratch_;
            // Deinterlacing scratch. Allocated once and reused: four cameras at 25 fps is no
            // place to be asking the allocator for anything.
            cv::Mat rgbaTopField_;
            cv::Mat rgbaBotField_;
            cv::Mat lumaTopField_;
            cv::Mat lumaBotField_;
            cv::Mat interpolated_;
            cv::Mat lumaTopPrev_;
            cv::Mat motionDiff_;
            cv::Mat combMask_;
            cv::Mat previewScratch_;
        };

        std::mutex gManagerMutex;
        std::mutex gCombinedMutex;
        std::unordered_map<int, std::shared_ptr<CameraSession>> gSessions;
        std::unordered_map<int, int> gSlotToVideoIndex;
        std::shared_ptr<CombinedRecordingSink> gCombinedSink;
        bool gCombinedRecordingActive = false;

        static constexpr int COMBINED_CONSUMER_ID_BASE = 1000;
        static constexpr int COMBINED_CAMERA_MASK_ALL = 0x0F;
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

    namespace
    {

    /**
     * Wires a composed sink to the cameras it needs, or to none of them if any refuses.
     *
     * <p>Shared by recording and preview: the two differ only in whether the sink has an encoder
     * behind it, and nothing about the attaching changes. Caller holds gCombinedMutex.
     */
    bool attachCombinedSinkLocked(const std::shared_ptr<CombinedRecordingSink> &sink, int normalizedMask)
    {
        std::vector<std::pair<int, std::shared_ptr<CameraSession>>> attached;
        std::vector<int> attachedConsumerIds;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            if ((normalizedMask & (1 << static_cast<int>(i))) == 0)
            {
                continue;
            }
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
        return true;
    }

    /**
     * Ends any hold left over from the previous clip.
     *
     * <p>Called on every way out of starting one: once the new sink is attached the cameras have
     * a real reader again and the hold has done its job, and if the start failed they must not
     * stay held for a recording that is not happening.
     */
    void releaseKeepWarm()
    {
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            auto session = getSession(COMBINED_VIDEO_INDICES[i]);
            if (session != nullptr)
            {
                session->setKeepWarm(false);
            }
        }
    }

    /** Detaches whatever the combined sink was using. Caller must not hold gCombinedMutex. */
    bool detachCombinedSessions()
    {
        bool allStopped = true;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            auto session = getSession(videoIndex);
            if (session == nullptr)
            {
                continue;
            }
            if (!session->detachConsumer(COMBINED_CONSUMER_ID_BASE + static_cast<int>(i)))
            {
                allStopped = false;
            }
            eraseSessionIfIdle(videoIndex, session);
        }
        return allStopped;
    }

    } // namespace

    bool startCombinedRecording(JNIEnv * /*env*/, const std::string &outputPath,
                                int cellWidth, int cellHeight, int fps, int bitrate,
                                const std::string &signature, bool showSpeed, int cameraMask)
    {
        std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
        std::shared_ptr<CombinedRecordingSink> sink;
        if (gCombinedRecordingActive)
        {
            logw("combined recording already active");
            return false;
        }

        // A preview-only composer may be holding the cameras. It has to go before a recording
        // one can have them, but its window does not: it is handed over below so the picture on
        // screen carries on, now coming from the frames that are being written to the file.
        ANativeWindow *previewWindow = nullptr;
        if (gCombinedSink != nullptr)
        {
            auto previewSink = gCombinedSink;
            gCombinedSink.reset();
            previewWindow = previewSink->takePreviewWindow();
            previewSink->requestStop();
            detachCombinedSessions();
            previewSink->finalize();
            previewSink->waitUntilStopped(STOP_WAIT_MS);
        }

        int normalizedMask = cameraMask & COMBINED_CAMERA_MASK_ALL;
        if (normalizedMask == 0)
        {
            normalizedMask = COMBINED_CAMERA_MASK_ALL;
        }

        sink = std::make_shared<CombinedRecordingSink>(
            outputPath,
            cellWidth,
            cellHeight,
            fps,
            bitrate,
            signature,
            showSpeed,
            normalizedMask);
        gWrittenSamples = 0;
        gWriteFailed = false;
        const int64_t encoderStartUs = nowUs();
        if (!sink->initialize())
        {
            // Nothing is going to read from a camera being held for a clip that will not exist.
            releaseKeepWarm();
            return false;
        }
        const int64_t camerasStartUs = nowUs();

        if (!attachCombinedSinkLocked(sink, normalizedMask))
        {
            releaseKeepWarm();
            return false;
        }
        countClipStart(camerasStartUs - encoderStartUs, nowUs() - camerasStartUs);

        // A preview that was running on its own composer hands its window to the recording one,
        // so starting to record does not blank the screen somebody is watching.
        if (previewWindow != nullptr)
        {
            sink->setPreviewWindow(previewWindow);
        }
        gCombinedSink = sink;
        gCombinedRecordingActive = true;
        releaseKeepWarm();
        logi("combined recording attached with camera mask 0x%x", normalizedMask);
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

    /**
     * Shows the composed grid on a Surface, exactly as it would be recorded.
     *
     * <p>If a recording is running the preview rides on its composer, so what appears is the very
     * frame being written to the file - not a second rendering that might differ from it. If not,
     * a composer is started with no encoder behind it: the same work up to the canvas, and
     * nothing after.
     */
    bool attachCombinedPreview(JNIEnv *env, jobject surface, int cellWidth, int cellHeight,
                               int fps, const std::string &signature, bool showSpeed,
                               int cameraMask)
    {
        if (surface == nullptr)
        {
            return false;
        }
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (window == nullptr)
        {
            logw("combined preview: ANativeWindow_fromSurface failed");
            return false;
        }

        std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
        if (gCombinedSink != nullptr)
        {
            gCombinedSink->setPreviewWindow(window);
            return true;
        }

        int normalizedMask = cameraMask & COMBINED_CAMERA_MASK_ALL;
        if (normalizedMask == 0)
        {
            normalizedMask = COMBINED_CAMERA_MASK_ALL;
        }
        // The empty output path is what makes this a preview: see CombinedRecordingSink.
        auto sink = std::make_shared<CombinedRecordingSink>(
            std::string(), cellWidth, cellHeight, fps, 0, signature, showSpeed, normalizedMask);
        if (!sink->initialize() || !attachCombinedSinkLocked(sink, normalizedMask))
        {
            ANativeWindow_release(window);
            return false;
        }
        sink->setPreviewWindow(window);
        gCombinedSink = sink;
        logi("combined preview attached with camera mask 0x%x", normalizedMask);
        return true;
    }

    /**
     * Stops showing it. A recording composer keeps going without its window; a preview-only one
     * has nothing left to do and lets the cameras go.
     */
    int previewCanvasWidth(int cellWidth, int cellHeight)
    {
        return combinedCanvasWidth(cellWidth, cellHeight);
    }

    int previewCanvasHeight(int cellWidth, int cellHeight)
    {
        return combinedCanvasHeight(cellWidth, cellHeight);
    }

    bool detachCombinedPreview()
    {
        std::shared_ptr<CombinedRecordingSink> sink;
        {
            std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
            if (gCombinedSink == nullptr)
            {
                return true;
            }
            if (gCombinedRecordingActive)
            {
                gCombinedSink->setPreviewWindow(nullptr);
                return true;
            }
            sink = gCombinedSink;
            gCombinedSink.reset();
        }
        sink->setPreviewWindow(nullptr);
        sink->requestStop();
        const bool detached = detachCombinedSessions();
        sink->finalize();
        const bool stopped = sink->waitUntilStopped(STOP_WAIT_MS);
        return detached && stopped;
    }

    bool awaitPendingFlushes(int timeoutMs)
    {
        std::unique_lock<std::mutex> lock(gFlushMutex);
        return gFlushCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                                 [] { return gFlushesInFlight == 0; }) && !gFlushFailed;
    }

    void releaseCombinedCameras()
    {
        std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
        releaseKeepWarm();
    }

    bool stopCombinedRecording(bool keepCamerasWarm)
    {
        return stopCombinedRecording(keepCamerasWarm, false);
    }

    /**
     * @param urgent the factory 360 view is waiting for these cameras and will not wait long.
     *               Measured twice on the car, it appears and gives up again after 209 and
     *               218 ms - a timeout in its code, not luck - and the ordinary path spends
     *               most of that draining an encoder the factory app does not care about. In
     *               urgent mode the four devices are freed together and in parallel, and the
     *               clip is finalised afterwards on a thread nobody is waiting for.
     */
    bool stopCombinedRecording(bool keepCamerasWarm, bool urgent)
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

        // Every hold goes on before the sink is told anything. A tap answers
        // isStopRequested() with the shared sink's state, so requestStop() makes all four taps
        // say "stopped" in the same instant - and a camera whose housekeeping runs before the
        // loop below reaches it would drop its tap, find neither a consumer nor a hold, and
        // take the capture down. The window is not narrow either: stopConsumer() waits for the
        // sink itself to finish, which is the encoder draining.
        //
        // Measured with the holds set inside the loop instead: over 37 rotations one camera of
        // the four stayed up and three did not, each by its own housekeeping phase.
        if (keepCamerasWarm)
        {
            for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
            {
                auto session = getSession(COMBINED_VIDEO_INDICES[i]);
                if (session != nullptr)
                {
                    session->setKeepWarm(true);
                }
            }
        }

        sink->requestStop();

        if (urgent)
        {
            // All four at once. Each detach ends with joining that camera's capture thread,
            // which takes as long as one turn of its loop; done one after another that is four
            // turns, and four turns is most of the budget the factory app allows.
            const int64_t began = nowUs();
            std::vector<std::thread> releases;
            for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
            {
                const int videoIndex = COMBINED_VIDEO_INDICES[i];
                const int consumerId = COMBINED_CONSUMER_ID_BASE + static_cast<int>(i);
                releases.emplace_back([videoIndex, consumerId]() {
                    auto session = getSession(videoIndex);
                    if (session == nullptr)
                    {
                        return;
                    }
                    session->detachConsumer(consumerId);
                    eraseSessionIfIdle(videoIndex, session);
                });
            }
            for (auto &t : releases)
            {
                if (t.joinable())
                {
                    t.join();
                }
            }
            gLastUrgentReleaseUs = nowUs() - began;
            logi("cameras released in %lld us", (long long) gLastUrgentReleaseUs);

            // The file still has to be closed properly, but nothing is waiting for it now.
            // Cameras are already free. Keep finalization owned by the Java worker so
            // event copies, validation and shutdown cannot race an unfinished MP4.
            sink->finalize();
            return sink->waitUntilStopped(STOP_WAIT_MS) && !gWriteFailed;
        }

        bool allConsumersStopped = true;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            auto session = getSession(videoIndex);
            if (session == nullptr)
            {
                continue;
            }
            if (!session->detachConsumer(COMBINED_CONSUMER_ID_BASE + static_cast<int>(i)))
            {
                allConsumersStopped = false;
            }
            // A session on hold does not report itself idle, so this leaves it in the map.
            eraseSessionIfIdle(videoIndex, session);
        }

        sink->finalize();
        const bool sinkStopped = sink->waitUntilStopped(STOP_WAIT_MS);
        return allConsumersStopped && sinkStopped && !gWriteFailed;
    }

    int64_t writtenSamples() { return gWriteFailed ? -1 : gWrittenSamples.load(); }

    std::string describeFormats()
    {
        std::lock_guard<std::mutex> lock(gFormatMutex);
        if (gFormats.empty())
        {
            return "no camera has been opened yet";
        }
        std::string out;
        for (const auto &entry : gFormats)
        {
            out += entry.second;
            out += "\n";
        }
        {
            std::lock_guard<std::mutex> rateLock(gRateMutex);
            const int64_t now = nowUs();
            char line[160];
            for (const auto &entry : gFrameCounts)
            {
                const double seconds =
                    (gFrameLastUs[entry.first] - gFrameFirstUs[entry.first]) / 1000000.0;
                const long long missed = gMissedFrames[entry.first];
                // Two rates, because they answer different questions. The first covers the
                // whole span and so includes every pause - handing the cameras to the factory
                // 360 view, or simply nobody asking for a frame between a preview and a
                // recording. The second is the cadence while capture is actually running,
                // which is the one that says whether the pipeline keeps up.
                const double activeSeconds = gLoopUs[entry.first] / 1000000.0;
                snprintf(line, sizeof(line),
                         "/dev/video%d: %lld frames, %.1f fps while capturing"
                         " (%.1f fps over %.1fs including pauses)",
                         entry.first, entry.second,
                         activeSeconds > 0.1 ? entry.second / activeSeconds : 0.0,
                         seconds > 0.5 ? entry.second / seconds : 0.0, seconds);
                out += line;
                out += "\n";
                // What the driver captured in the same window: ours plus the ones whose
                // sequence numbers went by without us. If these two agree, the device is slow;
                // if the second is far larger, we are.
                snprintf(line, sizeof(line),
                         "    driver captured %lld (%.1f fps), we missed %lld"
                         " | busy %.1fms, waiting %.1fms, whole loop %.1fms per frame\n",
                         entry.second + missed,
                         seconds > 0.5 ? (entry.second + missed) / seconds : 0.0,
                         missed,
                         entry.second > 0 ? gWorkUs[entry.first] / 1000.0 / entry.second : 0.0,
                         entry.second > 0 ? gWaitUs[entry.first] / 1000.0 / entry.second : 0.0,
                         entry.second > 0 ? gLoopUs[entry.first] / 1000.0 / entry.second : 0.0);
                out += line;
            }
            for (const auto &entry : gEmptyCount)
            {
                snprintf(line, sizeof(line),
                         "/dev/video%d: %lld empty waits costing %.1fs in total"
                         " (%.1f ms each)\n",
                         entry.first, entry.second, gEmptyUs[entry.first] / 1000000.0,
                         entry.second > 0 ? gEmptyUs[entry.first] / 1000.0 / entry.second : 0.0);
                out += line;
            }
            if (gEncodeCount > 0)
            {
                snprintf(line, sizeof(line),
                         "encoder thread per frame: snapshot %.1fms, footer %.1fms,"
                         " feeding the codec %.1fms, draining it %.1fms\n",
                         gSnapUs / 1000.0 / gEncodeCount, gFooterUs / 1000.0 / gEncodeCount,
                         gFeedUs / 1000.0 / gEncodeCount, gDrainUs / 1000.0 / gEncodeCount);
                out += line;
            }
            if (gLastUrgentReleaseUs > 0)
            {
                snprintf(line, sizeof(line),
                         "handing the cameras to the 360 view: %.0f ms last time"
                         " (it gives up after about 210)\n",
                         gLastUrgentReleaseUs / 1000.0);
                out += line;
            }
            if (gClipStartCount > 0)
            {
                snprintf(line, sizeof(line),
                         "opening a clip: encoder and muxer %.0fms, attaching the cameras %.0fms"
                         " (%lld times)\n",
                         gClipEncoderUs / 1000.0 / gClipStartCount,
                         gClipCamerasUs / 1000.0 / gClipStartCount, gClipStartCount);
                out += line;
            }
            if (gSleepCount > 0)
            {
                snprintf(line, sizeof(line),
                         "encoder thread slept %lld times: asked %.1fms, got %.1fms"
                         " (overshoot %.1fms each)\n",
                         gSleepCount, gSleepAskedUs / 1000.0 / gSleepCount,
                         gSleepGotUs / 1000.0 / gSleepCount,
                         (gSleepGotUs - gSleepAskedUs) / 1000.0 / gSleepCount);
                out += line;
            }
            if (gPreviewCount > 0)
            {
                snprintf(line, sizeof(line),
                         "preview thread: %.1fms per post, %lld posts\n",
                         gPreviewUs / 1000.0 / gPreviewCount, gPreviewCount);
                out += line;
            }
            if (gCombTotal > 0)
            {
                snprintf(line, sizeof(line),
                         "deinterlacing: %.1f%% of rows interpolated rather than woven\n",
                         100.0 * gCombPixels / gCombTotal);
                out += line;
            }
            if (gDeliveredBuffers > 0)
            {
                snprintf(line, sizeof(line),
                         "cameras handing back the same picture twice: %lld of %lld (%.2f%%)\n",
                         gRepeatedBuffers, gDeliveredBuffers,
                         100.0 * gRepeatedBuffers / gDeliveredBuffers);
                out += line;
            }
            if (gWaitFreshCount > 0)
            {
                snprintf(line, sizeof(line),
                         "samples that waited for a camera: %lld, %.1f ms each on average\n",
                         gWaitFreshCount, gWaitFreshUs / 1000.0 / gWaitFreshCount);
                out += line;
            }
            if (gCanvasSamples > 0)
            {
                snprintf(line, sizeof(line),
                         "snapshots that found a cell nobody had rewritten: %lld of %lld"
                         " (%.2f%%), %lld cells in all, %lld of them on a key frame\n",
                         gStaleSamples, gCanvasSamples,
                         100.0 * gStaleSamples / gCanvasSamples, gStaleCells, gStaleOnKeyframe);
                out += line;
            }
            if (gCopyCount > 0)
            {
                snprintf(line, sizeof(line),
                         "copying one camera buffer out of mapped memory: %.1f ms average"
                         " (%lld reads)\n",
                         gCopyUs / 1000.0 / gCopyCount, gCopyCount);
                out += line;
            }
            if (gComposedFrames > 0)
            {
                const double seconds = (gComposedLastUs - gComposedFirstUs) / 1000000.0;
                // The wait for the cameras counts as time the loop was running: leaving it
                // out made the rate read 35.5 fps on a loop that was composing about 30.
                const double gridActive = (gSleepGotUs + gWaitFreshUs + gSnapUs + gFooterUs
                                           + gFeedUs + gDrainUs) / 1000000.0;
                snprintf(line, sizeof(line),
                         "grid: %lld composed, %.1f fps while running"
                         " (%.1f fps over %.1fs including pauses)",
                         gComposedFrames,
                         gridActive > 0.1 ? gComposedFrames / gridActive : 0.0,
                         seconds > 0.5 ? gComposedFrames / seconds : 0.0,
                         seconds);
                out += line;
                out += "\n";
            }
        }
        return out;
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
