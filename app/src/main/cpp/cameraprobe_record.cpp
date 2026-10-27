#include <jni.h>
#include <fcntl.h>
#include <unistd.h>

#include <android/log.h>

#include <exception>
#include <string>

#include "camera_stream_manager.h"

namespace {

constexpr const char *TAG = "CameraProbeRecord";

/**
 * Runs a recording call and turns anything it throws into a plain false.
 *
 * OpenCV is linked statically into this library and reports every failure by throwing; so does
 * every allocation in here. An exception that reaches the JNI boundary is not caught by anything
 * above it - the C++ runtime calls std::terminate and aborts the process, which on the head unit
 * meant the whole app disappearing mid-drive instead of one clip failing to start.
 *
 * A failed start is a false. The Java side already knows what to do with that.
 */
template <typename Call>
bool guarded(const char *what, Call call) {
    try {
        return call();
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s failed: %s", what, e.what());
        return false;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s failed with an unknown exception", what);
        return false;
    }
}

}  // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startMp4Record(JNIEnv* env, jclass /*clazz*/,
                                                    jint slot,
                                                    jint videoIndex,
                                                    jstring outputPath,
                                                    jint width,
                                                    jint height,
                                                    jint fps,
                                                    jint bitrate) {
    return guarded("startMp4Record", [&]() -> bool {
        if (outputPath == nullptr) {
            return false;
        }

        const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
        if (outputChars == nullptr) {
            return false;
        }

        std::string output(outputChars);
        env->ReleaseStringUTFChars(outputPath, outputChars);

        return camera_stream_manager::startRecording(
                env,
                static_cast<int>(slot),
                static_cast<int>(videoIndex),
                output,
                static_cast<int>(width),
                static_cast<int>(height),
                static_cast<int>(fps),
                static_cast<int>(bitrate)
        );
    }) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Pushes everything this filesystem is still holding in memory onto the medium.
 *
 * <p>Deleting a file on FAT is two writes - the directory entry freed, and the chain of clusters
 * released in the allocation table - and nothing forces either of them anywhere. They wait in
 * the kernel's cache until it chooses to write them, which in a car is until the ignition is
 * switched off. What is left behind is a table that still says those clusters are in use and a
 * directory that no longer mentions them, so the next mount finds clusters with no owner and
 * files them under LOST.DIR: a little more on every drive, until the medium is full of the
 * recordings it was told to delete.
 *
 * <p>syncfs() rather than fsync() on the directory. The second would probably do - the kernel's
 * vfat code flushes the allocation table alongside a directory - but "probably" is a poor thing
 * to put between somebody's dashcam and a full stick, and this costs the same.
 *
 * <p>Scoped to the one filesystem the descriptor belongs to, so a slow USB stick cannot drag
 * the head unit's own storage into the wait.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_syncFilesystem(JNIEnv* env, jclass /*clazz*/,
                                                    jstring path) {
    if (path == nullptr) {
        return JNI_FALSE;
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    const int fd = open(chars, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) {
        return JNI_FALSE;
    }
    const int result = syncfs(fd);
    close(fd);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_stopMp4Record(JNIEnv* /*env*/, jclass /*clazz*/, jint slot) {
    return guarded("stopMp4Record", [&]() -> bool {
        return camera_stream_manager::stopRecording(static_cast<int>(slot));
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startCombinedMp4Record(JNIEnv* env, jclass /*clazz*/,
                                                            jstring outputPath,
                                                            jint cellWidth,
                                                            jint cellHeight,
                                                            jint fps,
                                                            jint bitrate,
                                                            jstring signature,
                                                            jboolean showSpeed,
                                                            jint cameraMask) {
    return guarded("startCombinedMp4Record", [&]() -> bool {
        if (outputPath == nullptr) {
            return false;
        }

        const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
        if (outputChars == nullptr) {
            return false;
        }

        std::string output(outputChars);
        env->ReleaseStringUTFChars(outputPath, outputChars);

        std::string signatureText;
        if (signature != nullptr) {
            const char* signatureChars = env->GetStringUTFChars(signature, nullptr);
            if (signatureChars != nullptr) {
                signatureText.assign(signatureChars);
                env->ReleaseStringUTFChars(signature, signatureChars);
            }
        }

        return camera_stream_manager::startCombinedRecording(
                env,
                output,
                static_cast<int>(cellWidth),
                static_cast<int>(cellHeight),
                static_cast<int>(fps),
                static_cast<int>(bitrate),
                signatureText,
                showSpeed == JNI_TRUE,
                static_cast<int>(cameraMask)
        );
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_stopCombinedMp4Record(JNIEnv* /*env*/, jclass /*clazz*/,
                                                           jboolean keepCamerasWarm,
                                                           jboolean urgent) {
    return guarded("stopCombinedMp4Record", [&]() -> bool {
        return camera_stream_manager::stopCombinedRecording(keepCamerasWarm == JNI_TRUE,
                                                            urgent == JNI_TRUE);
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_awaitPendingFlushes(JNIEnv* /*env*/, jclass /*clazz*/,
                                                         jint timeoutMs) {
    return guarded("awaitPendingFlushes", [&]() -> bool {
        return camera_stream_manager::awaitPendingFlushes(static_cast<int>(timeoutMs));
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_releaseCombinedCameras(JNIEnv* /*env*/, jclass /*clazz*/) {
    guarded("releaseCombinedCameras", []() -> bool {
        camera_stream_manager::releaseCombinedCameras();
        return true;
    });
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_updateCombinedRecordingSpeed(JNIEnv* /*env*/, jclass /*clazz*/,
                                                                  jint speedKmh) {
    guarded("updateCombinedRecordingSpeed", [&]() -> bool {
        camera_stream_manager::updateCombinedRecordingSpeed(static_cast<int>(speedKmh));
        return true;
    });
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_drivehub_kamera_CameraProbe_describeCameraFormats(JNIEnv* env, jclass /*clazz*/) {
    std::string text;
    try {
        text = camera_stream_manager::describeFormats();
    } catch (...) {
        text = "unavailable";
    }
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_attachCombinedPreview(JNIEnv* env, jclass /*clazz*/,
                                                           jobject surface,
                                                           jint cellWidth,
                                                           jint cellHeight,
                                                           jint fps,
                                                           jstring signature,
                                                           jboolean showSpeed,
                                                           jint cameraMask) {
    return guarded("attachCombinedPreview", [&]() -> bool {
        std::string signatureText;
        if (signature != nullptr) {
            const char* chars = env->GetStringUTFChars(signature, nullptr);
            if (chars != nullptr) {
                signatureText.assign(chars);
                env->ReleaseStringUTFChars(signature, chars);
            }
        }
        return camera_stream_manager::attachCombinedPreview(
                env, surface,
                static_cast<int>(cellWidth), static_cast<int>(cellHeight),
                static_cast<int>(fps), signatureText,
                showSpeed == JNI_TRUE, static_cast<int>(cameraMask));
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_detachCombinedPreview(JNIEnv* /*env*/, jclass /*clazz*/) {
    return guarded("detachCombinedPreview", [&]() -> bool {
        return camera_stream_manager::detachCombinedPreview();
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_drivehub_kamera_CameraProbe_previewCanvasWidth(JNIEnv* /*env*/, jclass /*clazz*/,
                                                        jint cellWidth, jint cellHeight) {
    return camera_stream_manager::previewCanvasWidth(
            static_cast<int>(cellWidth), static_cast<int>(cellHeight));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_drivehub_kamera_CameraProbe_previewCanvasHeight(JNIEnv* /*env*/, jclass /*clazz*/,
                                                         jint cellWidth, jint cellHeight) {
    return camera_stream_manager::previewCanvasHeight(
            static_cast<int>(cellWidth), static_cast<int>(cellHeight));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_CameraProbe_writtenSamples(JNIEnv*, jclass) {
    return camera_stream_manager::writtenSamples();
}
