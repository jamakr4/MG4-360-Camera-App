#include <jni.h>

#include <string>

#include "camera_stream_manager.h"

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
    if (outputPath == nullptr) {
        return JNI_FALSE;
    }

    const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
    if (outputChars == nullptr) {
        return JNI_FALSE;
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
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_stopMp4Record(JNIEnv* /*env*/, jclass /*clazz*/, jint slot) {
    return camera_stream_manager::stopRecording(static_cast<int>(slot)) ? JNI_TRUE : JNI_FALSE;
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
                                                            jboolean showSpeed) {
    if (outputPath == nullptr) {
        return JNI_FALSE;
    }

    const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
    if (outputChars == nullptr) {
        return JNI_FALSE;
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
            showSpeed == JNI_TRUE
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_stopCombinedMp4Record(JNIEnv* /*env*/, jclass /*clazz*/) {
    return camera_stream_manager::stopCombinedRecording() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_updateCombinedRecordingSpeed(JNIEnv* /*env*/, jclass /*clazz*/,
                                                                  jint speedKmh) {
    camera_stream_manager::updateCombinedRecordingSpeed(static_cast<int>(speedKmh));
}
