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
        auto& gate = camera_stream_manager::cameraAccessGate();
        CameraOpening opening(gate);
        if (!opening) {
            summary << "Camera access blocked\n";
            break;
        }
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            std::string message = path + " open FAILED: " + errnoStr();
            logw("%s", message.c_str());
            summary << message << "\n";
            continue;
        }

        gate.opened();
        std::string result = gate.blocked() ? "blocked" : probeOne(fd);
        close(fd);
        gate.closed();

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
Java_com_drivehub_kamera_CameraProbe_attachEnhancedPreview(JNIEnv* env, jclass /*clazz*/,
                                                           jint videoIndex, jobject surface) {
    return camera_stream_manager::attachPreview(env, static_cast<int>(videoIndex), surface, true)
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_attachPreviewSink(JNIEnv* env, jclass, jint videoIndex,
                                                     jint sinkId, jobject surface) {
    return camera_stream_manager::attachPreviewSink(env, videoIndex, sinkId, surface)
           ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_attachIpkRearviewSink(JNIEnv* env,jclass,jint videoIndex,jint sinkId,jobject surface) {
    return camera_stream_manager::attachPreviewSink(env,videoIndex,sinkId,surface,true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_detachPreviewSink(JNIEnv*, jclass, jint videoIndex, jint sinkId) {
    camera_stream_manager::detachPreviewSink(videoIndex, sinkId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_setIpkRearviewSession(JNIEnv*,jclass,jint token) {
    camera_stream_manager::setIpkRearviewSession(token);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_setIpkRearviewMap(JNIEnv* env,jclass,jint token,jfloatArray input) {
    if(!input)return camera_stream_manager::setIpkRearviewMap(token,nullptr);
    if(env->GetArrayLength(input)!=ipk_rearview::FLOATS)return false;
    try {
        std::vector<float> uv(ipk_rearview::FLOATS);
        env->GetFloatArrayRegion(input,0,uv.size(),uv.data());
        if(env->ExceptionCheck())return false;
        return camera_stream_manager::setIpkRearviewMap(token,&uv);
    }catch(const std::exception&){return false;}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_CameraProbe_mainPreviewFrameAgeMs(JNIEnv*, jclass, jint videoIndex) {
    return camera_stream_manager::mainPreviewFrameAgeMs(videoIndex);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_CameraProbe_previewSinkCaptureAgeMs(JNIEnv*, jclass, jint videoIndex, jint sinkId) {
    return camera_stream_manager::previewSinkCaptureAgeMs(videoIndex, sinkId);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_CameraProbe_previewSinkFrameAgeMs(JNIEnv*, jclass, jint videoIndex, jint sinkId) {
    return camera_stream_manager::previewSinkFrameAgeMs(videoIndex, sinkId);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_drivehub_kamera_CameraProbe_previewSinkStats(JNIEnv* env, jclass, jint videoIndex, jint sinkId) {
    const auto values = camera_stream_manager::previewSinkStats(videoIndex, sinkId);
    const jlong data[] = {values[0], values[1], values[2], values[3]};
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, data);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_setOemCameraAccessBlocked(JNIEnv*, jclass, jboolean blocked) {
    camera_stream_manager::setOemCameraAccessBlocked(blocked);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_isOemCameraAccessBlocked(JNIEnv*, jclass) {
    return camera_stream_manager::isOemCameraAccessBlocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_setExternalCameraAccessBlocked(JNIEnv*, jclass, jboolean blocked) {
    camera_stream_manager::cameraAccessGate().setExternalBlocked(blocked);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_releaseCameraPreviewsForHandoff(JNIEnv*, jclass) {
    camera_stream_manager::releaseCameraPreviewsForHandoff();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_drivehub_kamera_CameraProbe_cameraOwnershipSnapshot(JNIEnv* env, jclass) {
    const auto state = camera_stream_manager::cameraAccessGate().snapshot();
    const jint values[] = {state.devices, state.opening, state.blocked ? 1 : 0};
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_attach(JNIEnv*, jclass, jint owner, jint camera) {
    return camera_stream_manager::attachBirdseye(owner, camera);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_hold(JNIEnv*,jclass,jint owner,jint camera){
    try{return camera_stream_manager::holdCamera(owner,camera);}
    catch(...){logw("Camera ownership request failed");return false;}
}
extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_releaseHold(JNIEnv*,jclass,jint owner){
    try{camera_stream_manager::releaseCameraHold(owner);}
    catch(...){logw("Camera ownership release failed");}
}
extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_detach(JNIEnv*, jclass, jint owner) {
    camera_stream_manager::detachBirdseye(owner);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_copy(JNIEnv* env, jclass, jint owner, jint camera,
                                                   jobject output, jlong afterUs) {
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    const jlong size = env->GetDirectBufferCapacity(output);
    if (!data || size < 0) return 0;
    return camera_stream_manager::copyBirdseye(owner, camera, data, static_cast<size_t>(size), afterUs);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_latest(JNIEnv* env, jclass, jint owner, jlongArray output) {
    if (!output || env->GetArrayLength(output) < 4) return false;
    int64_t latest[4];
    if (!camera_stream_manager::latestBirdseye(owner, latest)) return false;
    jlong values[4] = {latest[0], latest[1], latest[2], latest[3]};
    env->SetLongArrayRegion(output, 0, 4, values);
    return true;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_clockUs(JNIEnv*, jclass) {
    return camera_stream_manager::birdseyeClockUs();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_attachSnapshot(JNIEnv*,jclass,jint owner,jint camera) {
    return camera_stream_manager::attachBirdseye(owner,camera,true);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_drivehub_kamera_birdseye_BirdseyeNative_copySnapshot(JNIEnv* env,jclass,jint owner,jint camera,jobject buffer,jlong afterUs) {
    auto* bytes=static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong capacity=env->GetDirectBufferCapacity(buffer);if(!bytes||capacity<0)return nullptr;
    auto info=camera_stream_manager::copyBirdseyeSnapshot(owner,camera,bytes,static_cast<size_t>(capacity),afterUs);
    if(info[0]<=0)return nullptr;
    const jlong values[]={info[0],info[1],info[2],info[3],info[4],info[5]};
    jlongArray result=env->NewLongArray(6);if(result)env->SetLongArrayRegion(result,0,6,values);return result;
}

// Order L/R/F/rear, six values each: descriptor open, consumers, leases, frame age ms, errno, frames.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_drivehub_kamera_CameraProbe_cameraHealth(JNIEnv* env, jclass) {
    auto health = camera_stream_manager::cameraHealth();
    jlong values[24];
    for (size_t i=0;i<24;++i) values[i]=health[i];
    jlongArray result=env->NewLongArray(24);
    if(result) env->SetLongArrayRegion(result,0,24,values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_drivehub_kamera_CameraProbe_drainCameraEvents(JNIEnv* env, jclass) {
    return env->NewStringUTF(camera_stream_manager::drainCameraEvents().c_str());
}
