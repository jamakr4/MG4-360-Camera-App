#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <cerrno>
#include <cstring>

#include <opencv2/core.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>

#include <android/log.h>
#include <android/native_window_jni.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <linux/videodev2.h>

static constexpr const char *TAG = "CameraProbeOnly";

static void logi(const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
    va_end(args);
}
static void logw(const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, TAG, fmt, args);
    va_end(args);
}

static std::string errnoStr()
{
    std::ostringstream oss;
    oss << errno << " (" << std::strerror(errno) << ")";
    return oss.str();
}

static std::string fourccToStr(__u32 f)
{
    char s[5];
    s[0] = static_cast<char>(f & 0xFF);
    s[1] = static_cast<char>((f >> 8) & 0xFF);
    s[2] = static_cast<char>((f >> 16) & 0xFF);
    s[3] = static_cast<char>((f >> 24) & 0xFF);
    s[4] = '\0';
    return std::string(s);
}

static std::string probeOne(int fd)
{
    v4l2_capability cap{};
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) != 0)
    {
        return "VIDIOC_QUERYCAP failed: " + errnoStr();
    }

    std::ostringstream oss;
    oss << "OK driver=" << cap.driver
        << " card=" << cap.card
        << " bus=" << cap.bus_info
        << " ver=" << cap.version;

    __u32 caps = cap.capabilities;
    oss << " caps=0x" << std::hex << caps << std::dec;

    bool hasVideoCapture = (caps & V4L2_CAP_VIDEO_CAPTURE) != 0;
    bool hasVideoOutput = (caps & V4L2_CAP_VIDEO_OUTPUT) != 0;
    bool hasMplaneCapture = (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE) != 0;
    bool hasMplaneOutput = (caps & V4L2_CAP_VIDEO_OUTPUT_MPLANE) != 0;

    oss << " [";
    if (hasVideoCapture)
        oss << "CAPTURE ";
    if (hasVideoOutput)
        oss << "OUTPUT ";
    if (hasMplaneCapture)
        oss << "CAPTURE_MPLANE ";
    if (hasMplaneOutput)
        oss << "OUTPUT_MPLANE ";
    oss << "]";

    std::vector<std::string> fmts;
    v4l2_fmtdesc fdesc{};
    fdesc.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    for (fdesc.index = 0;; fdesc.index++)
    {
        if (ioctl(fd, VIDIOC_ENUM_FMT, &fdesc) != 0)
            break;
        fmts.push_back(fourccToStr(fdesc.pixelformat));
    }
    if (!fmts.empty())
    {
        oss << " fmts=[";
        for (size_t i = 0; i < fmts.size(); i++)
        {
            if (i)
                oss << ",";
            oss << fmts[i];
        }
        oss << "]";
    }
    return oss.str();
}

// ---- Preview state ----
static int g_fd = -1;
static ANativeWindow *g_window = nullptr;
static pthread_t g_thread = 0;
static volatile bool g_running = false;

static int g_width = 0;
static int g_height = 0;
static int g_srcStrideBytes = 0;
static int g_videoIndex = -1;
static cv::Mat g_undistortMap1;
static cv::Mat g_undistortMap2;
static cv::Rect g_displayCrop;

struct FisheyeCalibration
{
    cv::Matx33d K;
    cv::Vec4d D;
};

// OEM calibration was performed on a per-camera content image of ~700x490.
// The principal point (cx ~= 350, cy ~= 246) is centered in that frame, so
// the calibration coordinate system is the *content image* itself, not the
// padded V4L2 stream. We therefore scale K by (cropW/700, cropH/490) where
// the crop is the region of the stream that actually carries fisheye pixels.
static constexpr double kCalibWidth = 700.0;
static constexpr double kCalibHeight = 490.0;

static bool getCalibrationForVideoIndex(int videoIndex, int cropWidth, int cropHeight,
                                        FisheyeCalibration *out)
{
    if (!out || cropWidth <= 0 || cropHeight <= 0)
        return false;

    double fx, fy, cx, cy, k1, k2;

    // Plausible OEM block mapping derived from MG's calibration blob:
    // block1=right, block2=left, block3=front, block4=rear.
    switch (videoIndex)
    {
    case 14: // right
        fx = 197.450968; fy = 179.769482;
        cx = 350.8809805131158; cy = 246.4031885354052;
        k1 = 0.121851; k2 = -0.029633;
        break;
    case 16: // left
        fx = 196.799498; fy = 174.939944;
        cx = 352.1096473941443; cy = 251.7309546993812;
        k1 = 0.121447; k2 = -0.029392;
        break;
    case 15: // front
        fx = 197.542865; fy = 179.678174;
        cx = 349.4731477242575; cy = 242.2744490654553;
        k1 = 0.119027; k2 = -0.029049;
        break;
    case 17: // rear
        fx = 197.213019; fy = 179.503685;
        cx = 353.3940664819203; cy = 246.0065958061789;
        k1 = 0.118051; k2 = -0.028805;
        break;
    default:
        return false;
    }

    const double sx = static_cast<double>(cropWidth) / kCalibWidth;
    const double sy = static_cast<double>(cropHeight) / kCalibHeight;

    out->K = cv::Matx33d(
            fx * sx, 0.0,     cx * sx,
            0.0,     fy * sy, cy * sy,
            0.0,     0.0,     1.0);
    out->D = cv::Vec4d(k1, k2, 0.0, 0.0);
    return true;
}

// Build maps sized to the *crop region* (the bottom half of the stream that
// actually carries the fisheye image), not the full padded stream.
static void buildUndistortMapsIfNeeded(int videoIndex, int cropWidth, int cropHeight)
{
    if (!g_undistortMap1.empty() && !g_undistortMap2.empty() &&
        g_undistortMap1.cols == cropWidth && g_undistortMap1.rows == cropHeight)
    {
        return;
    }

    FisheyeCalibration calib{};
    if (!getCalibrationForVideoIndex(videoIndex, cropWidth, cropHeight, &calib))
    {
        g_undistortMap1.release();
        g_undistortMap2.release();
        return;
    }

    cv::Size imageSize(cropWidth, cropHeight);
    cv::Matx33d newK;
    // balance=0.0: keep only the inner valid region. balance=1.0 retains the
    // full FOV including pixels that map outside the source, which on a strong
    // fisheye produces a mostly-black image.
    cv::fisheye::estimateNewCameraMatrixForUndistortRectify(
            calib.K,
            calib.D,
            imageSize,
            cv::Matx33d::eye(),
            newK,
            0.0);
    cv::fisheye::initUndistortRectifyMap(
            calib.K,
            calib.D,
            cv::Matx33d::eye(),
            newK,
            imageSize,
            CV_16SC2,
            g_undistortMap1,
            g_undistortMap2);
}

static void *previewThread(void * /*arg*/)
{
    v4l2_requestbuffers req{};
    req.count = 4;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(g_fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 1)
    {
        logw("VIDIOC_REQBUFS failed");
        g_running = false;
        return nullptr;
    }

    struct Buffer
    {
        void *start;
        size_t length;
    };
    Buffer buffers[4]{};

    for (unsigned i = 0; i < req.count; i++)
    {
        v4l2_buffer buf{};
        buf.type = req.type;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (ioctl(g_fd, VIDIOC_QUERYBUF, &buf) < 0)
        {
            g_running = false;
            return nullptr;
        }
        buffers[i].length = buf.length;
        buffers[i].start = mmap(nullptr, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, g_fd, buf.m.offset);
        if (buffers[i].start == MAP_FAILED)
        {
            g_running = false;
            return nullptr;
        }
        if (ioctl(g_fd, VIDIOC_QBUF, &buf) < 0)
        {
            g_running = false;
            return nullptr;
        }
    }

    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(g_fd, VIDIOC_STREAMON, &type) < 0)
    {
        logw("VIDIOC_STREAMON failed");
        g_running = false;
        return nullptr;
    }

    ANativeWindow_Buffer outBuf{};

    while (g_running)
    {
        // Poll before DQBUF to avoid busy-waiting when the driver stalls.
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(g_fd, &fds);
        timeval tv{0, 100000}; // 100ms timeout
        if (select(g_fd + 1, &fds, nullptr, nullptr, &tv) <= 0)
            continue;

        v4l2_buffer buf{};
        buf.type = type;
        buf.memory = V4L2_MEMORY_MMAP;
        if (ioctl(g_fd, VIDIOC_DQBUF, &buf) < 0)
        {
            continue;
        }

        if (g_window && ANativeWindow_lock(g_window, &outBuf, nullptr) == 0)
        {
            uint8_t *dst = static_cast<uint8_t *>(outBuf.bits);
            int dstStrideBytes = outBuf.stride * 4;
            cv::Mat uyvyFrame(g_height, g_width, CV_8UC2,
                              buffers[buf.index].start, g_srcStrideBytes);
            cv::Mat fullDecodedRgba;
            cv::cvtColor(uyvyFrame, fullDecodedRgba, cv::COLOR_YUV2RGBA_UYVY);

            // Crop to the content region first, then undistort the crop. The
            // OEM K is in content-image coordinates, not full-stream
            // coordinates, so undistorting the crop is correct.
            cv::Mat croppedRaw = fullDecodedRgba(g_displayCrop);

            buildUndistortMapsIfNeeded(g_videoIndex,
                                       g_displayCrop.width,
                                       g_displayCrop.height);

            cv::Mat displayFrame;
            if (!g_undistortMap1.empty() && !g_undistortMap2.empty())
            {
                cv::remap(croppedRaw, displayFrame,
                          g_undistortMap1, g_undistortMap2,
                          cv::INTER_LINEAR);
                // Safety net: if the remap produced an essentially-black
                // frame (degenerate K scaling, wrong crop, etc.) fall back to
                // the raw fisheye crop so the user still sees the camera.
                cv::Scalar m = cv::mean(displayFrame);
                if ((m[0] + m[1] + m[2]) < 6.0)
                {
                    displayFrame = croppedRaw;
                }
            }
            else
            {
                displayFrame = croppedRaw;
            }

            cv::Mat rgbaFrame(g_displayCrop.height, g_displayCrop.width,
                              CV_8UC4, dst, dstStrideBytes);
            displayFrame.copyTo(rgbaFrame);

            // Mirror the rear camera (videoIndex 17)
            if (g_videoIndex == 17)
            {
                cv::flip(rgbaFrame, rgbaFrame, 1);
            }

            ANativeWindow_unlockAndPost(g_window);
        }

        ioctl(g_fd, VIDIOC_QBUF, &buf);
    }

    ioctl(g_fd, VIDIOC_STREAMOFF, &type);
    for (unsigned i = 0; i < req.count; i++)
    {
        if (buffers[i].start && buffers[i].start != MAP_FAILED)
        {
            munmap(buffers[i].start, buffers[i].length);
        }
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_drivehub_kamera_CameraProbe_probeAll(JNIEnv *env, jclass, jint maxIndex)
{
    if (maxIndex <= 0)
        maxIndex = 4;
    if (maxIndex > 32)
        maxIndex = 32;

    std::ostringstream summary;
    summary << "Probe /dev/video0.." << (maxIndex - 1) << "\n";

    for (int i = 0; i < maxIndex; i++)
    {
        std::string path = "/dev/video" + std::to_string(i);
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0)
        {
            std::string msg = path + " open FAILED: " + errnoStr();
            logw("%s", msg.c_str());
            summary << msg << "\n";
            continue;
        }

        std::string result = probeOne(fd);
        close(fd);

        std::string msg = path + " " + result;
        logi("%s", msg.c_str());
        summary << msg << "\n";
    }

    return env->NewStringUTF(summary.str().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startPreview(JNIEnv *env, jclass, jint videoIndex, jobject surface)
{
    if (g_running)
    {
        logw("Already running");
        return JNI_FALSE;
    }

    std::string path = "/dev/video" + std::to_string(videoIndex);
    g_fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (g_fd < 0)
    {
        logw("open %s failed: %s", path.c_str(), errnoStr().c_str());
        return JNI_FALSE;
    }

    v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(g_fd, VIDIOC_G_FMT, &fmt) < 0)
    {
        logw("VIDIOC_G_FMT failed: %s", errnoStr().c_str());
        close(g_fd);
        g_fd = -1;
        return JNI_FALSE;
    }
    g_width = fmt.fmt.pix.width;
    g_height = fmt.fmt.pix.height;
    g_srcStrideBytes = fmt.fmt.pix.bytesperline;
    g_videoIndex = videoIndex;
    g_undistortMap1.release();
    g_undistortMap2.release();
    // The V4L2 stream packs the real fisheye image in the bottom half of the
    // frame (top half is black/padding on this MG hardware). Crop to it.
    g_displayCrop = cv::Rect(0, g_height / 2, g_width, g_height / 2);
    logi("Using size %dx%d stride=%d", g_width, g_height, g_srcStrideBytes);

    g_window = ANativeWindow_fromSurface(env, surface);
    if (!g_window)
    {
        logw("ANativeWindow_fromSurface failed");
        close(g_fd);
        g_fd = -1;
        return JNI_FALSE;
    }

    int displayWidth = g_displayCrop.width;
    int displayHeight = g_displayCrop.height;

    ANativeWindow_setBuffersGeometry(g_window,
                                     displayWidth,
                                     displayHeight,
                                     AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

    g_running = true;
    if (pthread_create(&g_thread, nullptr, previewThread, nullptr) != 0)
    {
        logw("pthread_create failed");
        g_running = false;
        ANativeWindow_release(g_window);
        g_window = nullptr;
        close(g_fd);
        g_fd = -1;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_stopPreview(JNIEnv * /*env*/, jclass /*clazz*/)
{
    if (!g_running)
        return;
    g_running = false;
    if (g_thread)
    {
        pthread_join(g_thread, nullptr);
        g_thread = 0;
    }
    if (g_window)
    {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    if (g_fd >= 0)
    {
        close(g_fd);
        g_fd = -1;
    }
    g_undistortMap1.release();
    g_undistortMap2.release();
}
