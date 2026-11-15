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

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setWheelRendering(JNIEnv*,jclass,jint front,jint rear,jfloat frontAngle,jfloat rearAngle){
    camera_stream_manager::setSideCameraWheelRendering(front,rear,frontAngle,rearAngle);
}
extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setAdvancedSession(JNIEnv*,jclass,jint token){
    camera_stream_manager::setSideAdvancedSession(token);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setAdvancedMaps(JNIEnv* env,jclass,jint token,jstring identity,
        jfloatArray lf,jfloatArray lr,jfloatArray rf,jfloatArray rr,jfloatArray ls,jfloatArray rs){
    if(!identity)return camera_stream_manager::setSideAdvancedMaps(token,"",nullptr);
    if(env->GetStringLength(identity)>128)return false;
    const char* chars=env->GetStringUTFChars(identity,nullptr);if(!chars)return false;
    std::string key;
    try{key.assign(chars);}catch(const std::exception&){env->ReleaseStringUTFChars(identity,chars);return false;}
    env->ReleaseStringUTFChars(identity,chars);
    if(!lf&&!lr&&!rf&&!rr&&!ls&&!rs)return camera_stream_manager::setSideAdvancedMaps(token,key,nullptr);
    jfloatArray input[]={lf,lr,rf,rr,ls,rs};
    try{
        side_camera_advanced::Inputs uv;
        for(int i=0;i<6;i++){
            int count=i<4?side_camera_advanced::FLOATS:side_camera_advanced::SIDE_FLOATS;
            if(!input[i]||env->GetArrayLength(input[i])!=count)return false;
            uv[i].resize(count);
            env->GetFloatArrayRegion(input[i],0,count,uv[i].data());if(env->ExceptionCheck())return false;
        }
        return camera_stream_manager::setSideAdvancedMaps(token,key,&uv);
    }catch(const std::exception&){return false;}
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_setSideCorrection(JNIEnv*,jclass,jboolean enabled){
    camera_stream_manager::setSideCameraCorrection(enabled);
}

#ifndef NDEBUG
extern "C" JNIEXPORT jintArray JNICALL
Java_com_drivehub_kamera_side_SidePreviewDebugBridge_composeNative(JNIEnv* env,jclass,jintArray input,jint height,
        jboolean left,jboolean wheels,jint frontMode,jint rearMode,jfloat frontDegrees,jfloat rearDegrees,
        jboolean highlight,jfloatArray cropArray,jfloatArray frontUv,jfloatArray rearUv,jboolean correctSides,jfloatArray sideUv){
    if(!input||(height!=240&&height!=480)||env->GetArrayLength(input)!=720*height||!cropArray||env->GetArrayLength(cropArray)!=12)return nullptr;
    try{
        std::vector<jint> argb(720*height);env->GetIntArrayRegion(input,0,argb.size(),argb.data());if(env->ExceptionCheck())return nullptr;
        cv::Mat rgba(height,720,CV_8UC4);
        for(int y=0;y<height;y++)for(int x=0;x<720;x++){uint32_t p=argb[y*720+x];rgba.at<cv::Vec4b>(y,x)=cv::Vec4b((p>>16)&255,(p>>8)&255,p&255,(p>>24)&255);}
        side_camera_preview::Crops crops;env->GetFloatArrayRegion(cropArray,0,12,crops.data());if(!side_camera_preview::valid(crops))return nullptr;
        std::shared_ptr<const side_camera_advanced::Maps> advanced;
        if((frontUv&&rearUv)||sideUv){
            if(frontUv&&env->GetArrayLength(frontUv)!=side_camera_advanced::FLOATS)return nullptr;
            if(rearUv&&env->GetArrayLength(rearUv)!=side_camera_advanced::FLOATS)return nullptr;
            if(sideUv&&env->GetArrayLength(sideUv)!=side_camera_advanced::SIDE_FLOATS)return nullptr;
            side_camera_advanced::Inputs uv;
            for(int i=0;i<6;i++){
                jfloatArray source=i<4?(i%2==0?frontUv:rearUv):sideUv;
                if(!source)source=sideUv?sideUv:frontUv;
                int count=env->GetArrayLength(source);
                std::vector<float> values(count);env->GetFloatArrayRegion(source,0,count,values.data());
                uv[i].resize(i<4?side_camera_advanced::FLOATS:side_camera_advanced::SIDE_FLOATS);
                for(size_t n=0;n<uv[i].size();n++)uv[i][n]=values[n%values.size()];
            }
            if(env->ExceptionCheck())return nullptr;
            advanced=std::make_shared<const side_camera_advanced::Maps>("debug-memory-only",uv);
        }
        cv::Mat upright,output;side_camera_preview::WheelTransforms cache;
        unsigned axes=(frontMode?1:0)|(rearMode?2:0),nonlinear=(frontMode==2?1:0)|(rearMode==2?2:0);
        side_camera_preview::compose(rgba,left,wheels,crops,upright,output,cache,axes,frontMode?frontDegrees:0,rearMode?rearDegrees:0,highlight,advanced.get(),nonlinear,correctSides);
        std::vector<jint> pixels(output.cols*output.rows);
        for(int y=0;y<output.rows;y++)for(int x=0;x<output.cols;x++){auto p=output.at<cv::Vec4b>(y,x);pixels[y*output.cols+x]=static_cast<jint>((uint32_t(p[3])<<24)|(uint32_t(p[0])<<16)|(uint32_t(p[1])<<8)|p[2]);}
        jintArray result=env->NewIntArray(pixels.size());if(result)env->SetIntArrayRegion(result,0,pixels.size(),pixels.data());return result;
    }catch(const std::exception&){return nullptr;}
}
#endif
