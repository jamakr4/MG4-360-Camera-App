#include <jni.h>

#include <android/log.h>

#include <linux/videodev2.h>

#include <cerrno>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "camera_stream_manager.h"

namespace {

static constexpr const char* TAG = "CameraProbeOnly";

void logi(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
    va_end(args);
}

void logw(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, TAG, fmt, args);
    va_end(args);
}

std::string errnoStr() {
    std::ostringstream oss;
    oss << errno << " (" << std::strerror(errno) << ")";
    return oss.str();
}

std::string fourccToStr(__u32 value) {
    char text[5];
    text[0] = static_cast<char>(value & 0xFF);
    text[1] = static_cast<char>((value >> 8) & 0xFF);
    text[2] = static_cast<char>((value >> 16) & 0xFF);
    text[3] = static_cast<char>((value >> 24) & 0xFF);
    text[4] = '\0';
    return std::string(text);
}

std::string probeOne(int fd) {
    v4l2_capability capability{};
    if (ioctl(fd, VIDIOC_QUERYCAP, &capability) != 0) {
        return "VIDIOC_QUERYCAP failed: " + errnoStr();
    }

    std::ostringstream oss;
    oss << "OK driver=" << capability.driver
        << " card=" << capability.card
        << " bus=" << capability.bus_info
        << " ver=" << capability.version;

    __u32 caps = capability.capabilities;
    oss << " caps=0x" << std::hex << caps << std::dec;

    const bool hasVideoCapture = (caps & V4L2_CAP_VIDEO_CAPTURE) != 0;
    const bool hasVideoOutput = (caps & V4L2_CAP_VIDEO_OUTPUT) != 0;
    const bool hasMplaneCapture = (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE) != 0;
    const bool hasMplaneOutput = (caps & V4L2_CAP_VIDEO_OUTPUT_MPLANE) != 0;

    oss << " [";
    if (hasVideoCapture) oss << "CAPTURE ";
    if (hasVideoOutput) oss << "OUTPUT ";
    if (hasMplaneCapture) oss << "CAPTURE_MPLANE ";
    if (hasMplaneOutput) oss << "OUTPUT_MPLANE ";
    oss << "]";

    std::vector<std::string> formats;
    v4l2_fmtdesc formatDesc{};
    formatDesc.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    for (formatDesc.index = 0;; formatDesc.index++) {
        if (ioctl(fd, VIDIOC_ENUM_FMT, &formatDesc) != 0) {
            break;
        }
        formats.push_back(fourccToStr(formatDesc.pixelformat));
    }

    if (!formats.empty()) {
        oss << " fmts=[";
        for (size_t i = 0; i < formats.size(); i++) {
            if (i > 0) oss << ",";
            oss << formats[i];
        }
        oss << "]";
    }
    return oss.str();
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_drivehub_kamera_CameraProbe_probeAll(JNIEnv* env, jclass /*clazz*/, jint maxIndex) {
    if (maxIndex <= 0) maxIndex = 4;
    if (maxIndex > 32) maxIndex = 32;

    std::ostringstream summary;
    summary << "Probe /dev/video0.." << (maxIndex - 1) << "\n";

    for (int i = 0; i < maxIndex; i++) {
        std::string path = "/dev/video" + std::to_string(i);
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            std::string message = path + " open FAILED: " + errnoStr();
            logw("%s", message.c_str());
            summary << message << "\n";
            continue;
        }

        std::string result = probeOne(fd);
        close(fd);

        std::string message = path + " " + result;
        logi("%s", message.c_str());
        summary << message << "\n";
    }

    return env->NewStringUTF(summary.str().c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_attachPreview(JNIEnv* env, jclass /*clazz*/,
                                                   jint videoIndex, jobject surface) {
    return camera_stream_manager::attachPreview(env, static_cast<int>(videoIndex), surface)
           ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_attachPreviewSized(JNIEnv* env, jclass /*clazz*/,
                                                        jint videoIndex, jobject surface,
                                                        jint targetWidth, jint targetHeight) {
    return camera_stream_manager::attachPreview(env, static_cast<int>(videoIndex), surface,
                                                static_cast<int>(targetWidth),
                                                static_cast<int>(targetHeight))
           ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_detachPreview(JNIEnv* /*env*/, jclass /*clazz*/,
                                                   jint videoIndex) {
    camera_stream_manager::detachPreview(static_cast<int>(videoIndex));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_detachAllPreviews(JNIEnv* /*env*/, jclass /*clazz*/) {
    camera_stream_manager::detachAllPreviews();
}
