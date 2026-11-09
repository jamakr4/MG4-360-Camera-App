#include "camera_stream_manager.h"

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_enablePreviews(JNIEnv*, jclass) {
    camera_stream_manager::enableSideCameraPreviews();
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setWheelsMode(JNIEnv*, jclass, jboolean wheels) {
    camera_stream_manager::setSideCameraWheelsMode(wheels);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setWheelAlignment(JNIEnv*, jclass,
        jboolean frontEnabled, jboolean rearEnabled, jfloat frontDegrees, jfloat rearDegrees) {
    camera_stream_manager::setSideCameraWheelAlignment(frontEnabled, rearEnabled, frontDegrees, rearDegrees);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setHighlightMissingAreas(JNIEnv*, jclass, jboolean enabled) {
    camera_stream_manager::setSideCameraHighlightMissingAreas(enabled);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_attachPreview(JNIEnv* env, jclass, jint side,
        jobject surface, jfloatArray cropArray) {
    if (!cropArray || env->GetArrayLength(cropArray) != 12) return false;
    side_camera_preview::Crops crops;
    env->GetFloatArrayRegion(cropArray, 0, 12, crops.data());
    if (env->ExceptionCheck()) return false;
    return camera_stream_manager::attachSideCameraPreview(env, side, surface, crops);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_detachPreview(JNIEnv*, jclass, jint side) {
    camera_stream_manager::detachSideCameraPreview(side);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_detachPreviews(JNIEnv*, jclass) {
    camera_stream_manager::detachSideCameraPreviews();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_previewAgeMs(JNIEnv*, jclass, jint side) {
    return camera_stream_manager::sideCameraPreviewAgeMs(side);
}
