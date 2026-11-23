#include "../../main/cpp/ipk_rearview.h"
#include "../../main/cpp/latest_preview_frame.h"
#include <cassert>

static std::vector<float> coordinates(int footprint) {
    std::vector<float> uv(ipk_rearview::FLOATS);
    for(int y=0;y<ipk_rearview::HEIGHT;y++)for(int x=0;x<ipk_rearview::WIDTH;x++) {
        int i=(y*ipk_rearview::WIDTH+x)*2;
        if(footprint==0) { uv[i]=x*719.f/259;uv[i+1]=y*479.f/172; }
        else if(footprint==1) { uv[i]=123.25f+x*.75f;uv[i+1]=53.8f+y*1.2f; }
        else { uv[i]=719;uv[i+1]=479; }
    }
    return uv;
}

int main() {
    for(int height:{240,480}) {
        // Padded rows catch accidental assumptions about packed camera strides.
        cv::Mat backing(height,736,CV_8UC2);cv::randu(backing,0,256);
        cv::Mat packed=backing(cv::Rect(0,0,720,height)),original=packed.clone();
        cv::Mat field=ipk_rearview::copyFirstField(packed);
        assert(field.rows==240&&field.cols==720&&field.type()==CV_8UC2);
        assert(cv::norm(field,packed.rowRange(0,240),cv::NORM_INF)==0);
        cv::Mat converted,scratch,referenceRgba,referenceScratch;
        for(int footprint:{0,1,2}) {
            ipk_rearview::LensMap map(coordinates(footprint));
            cv::cvtColor(packed,referenceRgba,cv::COLOR_YUV2RGBA_UYVY);
            cv::Mat reference=ipk_rearview::render(referenceRgba,&map,referenceScratch);
            cv::Mat actual=ipk_rearview::renderPacked(packed,&map,converted,scratch);
            assert(actual.size()==reference.size());
            assert(cv::norm(actual,reference,cv::NORM_INF)==0);
            if(footprint==1)assert(converted.total()<packed.total()/3);
        }
        cv::cvtColor(packed,referenceRgba,cv::COLOR_YUV2RGBA_UYVY);
        cv::Mat reference=ipk_rearview::render(referenceRgba,nullptr,referenceScratch);
        cv::Mat wide=ipk_rearview::renderPacked(packed,nullptr,converted,scratch);
        assert(cv::norm(wide,reference,cv::NORM_INF)==0);
        assert(cv::norm(packed,original,cv::NORM_INF)==0);

        // Capture owns the bytes only until QBUF. The queued field survives reuse
        // and a blocked display without sharing storage with Main/Enhanced output.
        LatestPreviewFrame<cv::Mat> queue(0);
        assert(queue.submit(1,[&]{return ipk_rearview::copyFirstField(packed);}));
        packed.setTo(cv::Scalar(0,0));cv::Mat delivered;
        assert(queue.take(delivered));assert(cv::norm(delivered,field,cv::NORM_INF)==0);
        queue.stop();
    }
    for(cv::Mat invalid:{cv::Mat(),cv::Mat(240,718,CV_8UC2),cv::Mat(120,720,CV_8UC2),cv::Mat(240,720,CV_8UC4)}) {
        assert(!ipk_rearview::validPacked(invalid));
        cv::Mat converted,scratch;
        try {ipk_rearview::renderPacked(invalid,nullptr,converted,scratch);assert(false);}
        catch(const std::invalid_argument&){}
    }
}
