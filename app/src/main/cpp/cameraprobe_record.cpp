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
