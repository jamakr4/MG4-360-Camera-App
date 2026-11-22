#include "ipk_rearview.h"
#include <jni.h>

#ifndef NDEBUG
extern "C" JNIEXPORT jintArray JNICALL
Java_com_drivehub_kamera_camera_IpkRearviewDebugBridge_render(JNIEnv* env,jclass,jintArray input,jint height,jfloatArray coordinates) {
    if(!input||(height!=240&&height!=480)||env->GetArrayLength(input)!=720*height)return nullptr;
    try {
        std::vector<jint> pixels(720*height);env->GetIntArrayRegion(input,0,pixels.size(),pixels.data());
        if(env->ExceptionCheck())return nullptr;
        cv::Mat rgba(height,720,CV_8UC4),scratch;
        for(int y=0;y<height;y++)for(int x=0;x<720;x++) {
            uint32_t p=pixels[y*720+x];rgba.at<cv::Vec4b>(y,x)={(uchar)(p>>16),(uchar)(p>>8),(uchar)p,(uchar)(p>>24)};
        }
        std::shared_ptr<const ipk_rearview::LensMap> map;
        if(coordinates) {
            if(env->GetArrayLength(coordinates)!=ipk_rearview::FLOATS)return nullptr;
            std::vector<float> uv(ipk_rearview::FLOATS);env->GetFloatArrayRegion(coordinates,0,uv.size(),uv.data());
            if(env->ExceptionCheck())return nullptr;
            map=std::make_shared<const ipk_rearview::LensMap>(uv);
        }
        cv::Mat result=ipk_rearview::render(rgba,map.get(),scratch);pixels.resize(result.total());
        for(int y=0;y<result.rows;y++)for(int x=0;x<result.cols;x++) {
            auto p=result.at<cv::Vec4b>(y,x);pixels[y*result.cols+x]=(uint32_t(p[3])<<24)|(uint32_t(p[0])<<16)|(uint32_t(p[1])<<8)|p[2];
        }
        jintArray output=env->NewIntArray(pixels.size());
        if(output)env->SetIntArrayRegion(output,0,pixels.size(),pixels.data());return output;
    }catch(const std::exception&){return nullptr;}
}
#endif
