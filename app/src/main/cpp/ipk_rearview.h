#pragma once
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace ipk_rearview {
constexpr int WIDTH=260, HEIGHT=173, FLOATS=WIDTH*HEIGHT*2;

inline bool validPacked(const cv::Mat& frame) {
    return frame.cols==720&&(frame.rows==240||frame.rows==480)&&frame.type()==CV_8UC2;
}
inline cv::Mat copyFirstField(const cv::Mat& packed) {
    if(!validPacked(packed))throw std::invalid_argument("Invalid IPK UYVY frame");
    return (packed.rows==480?packed.rowRange(0,240):packed).clone();
}

class LensMap {
public:
    explicit LensMap(const std::vector<float>& uv) {
        if(uv.size()!=FLOATS)throw std::invalid_argument("Invalid IPK rearview map size");
        x_.create(HEIGHT,WIDTH,CV_32FC1);fullY_.create(HEIGHT,WIDTH,CV_32FC1);fieldY_.create(HEIGHT,WIDTH,CV_32FC1);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            int i=(y*WIDTH+x)*2;float u=uv[i],v=uv[i+1];
            if(!std::isfinite(u)||!std::isfinite(v)||u<0||u>719||v<0||v>479)
                throw std::invalid_argument("Invalid IPK rearview coordinates");
            x_.at<float>(y,x)=u;fullY_.at<float>(y,x)=v;fieldY_.at<float>(y,x)=std::min(239.f,v*.5f);
        }
        fullBounds_=bounds(fullY_,480);fieldBounds_=bounds(fieldY_,240);
        localX_=x_-fullBounds_.x;localFullY_=fullY_-fullBounds_.y;localFieldY_=fieldY_-fieldBounds_.y;
    }
    bool draw(const cv::Mat& rgba,cv::Mat& target)const {
        if(rgba.cols!=720||(rgba.rows!=240&&rgba.rows!=480)||rgba.type()!=CV_8UC4)return false;
        cv::remap(rgba,target,x_,rgba.rows==240?fieldY_:fullY_,cv::INTER_LINEAR,
                  cv::BORDER_CONSTANT,cv::Scalar(12,12,12,255));
        return true;
    }
    bool drawPacked(const cv::Mat& uyvy,cv::Mat& converted,cv::Mat& target)const {
        if(!validPacked(uyvy))return false;
        // Normal needs only this lens footprint. Convert complete UYVY pairs, then use
        // the original sampling coordinates relative to the crop (including the filter border).
        const cv::Rect& crop=uyvy.rows==240?fieldBounds_:fullBounds_;
        cv::cvtColor(uyvy(crop),converted,cv::COLOR_YUV2RGBA_UYVY);
        cv::remap(converted,target,localX_,uyvy.rows==240?localFieldY_:localFullY_,cv::INTER_LINEAR,
                  cv::BORDER_CONSTANT,cv::Scalar(12,12,12,255));
        return true;
    }
private:
    cv::Rect bounds(const cv::Mat& y,int height)const {
        double minX,maxX,minY,maxY;
        cv::minMaxLoc(x_,&minX,&maxX);cv::minMaxLoc(y,&minY,&maxY);
        int left=static_cast<int>(std::floor(minX))&~1;
        int right=std::min(720,(static_cast<int>(std::ceil(maxX))+2)&~1);
        int top=static_cast<int>(std::floor(minY)),bottom=std::min(height,static_cast<int>(std::ceil(maxY))+1);
        return {left,top,right-left,bottom-top};
    }
    cv::Mat x_,fullY_,fieldY_,localX_,localFullY_,localFieldY_;
    cv::Rect fullBounds_,fieldBounds_;
};

// Wide retains the original pixels; both paths mirror once, after any lens correction.
inline cv::Mat render(cv::Mat rgba,const LensMap* map,cv::Mat& scratch) {
    if(map&&map->draw(rgba,scratch))rgba=scratch;
    cv::flip(rgba,rgba,1);return rgba;
}

// Runs only on the IPK output worker. Capture publishes an owned first-field copy;
// another display's conversion, frame size or blocked BufferQueue cannot alter it.
inline cv::Mat renderPacked(const cv::Mat& uyvy,const LensMap* map,cv::Mat& converted,cv::Mat& scratch) {
    if(!validPacked(uyvy))
        throw std::invalid_argument("Invalid IPK UYVY frame");
    cv::Mat display;
    if(map&&map->drawPacked(uyvy,converted,scratch))display=scratch;
    else {cv::cvtColor(uyvy,converted,cv::COLOR_YUV2RGBA_UYVY);display=converted;}
    cv::flip(display,display,1);return display;
}

/** An old overlay/loader cannot publish maps after a newer overlay takes ownership. */
class Registry {
public:
    void session(int token) {
        std::lock_guard<std::mutex> lock(mutex_);
        if(token>0){owner_=token;std::atomic_store(&active_,std::shared_ptr<const LensMap>{});}
        else if(token<0&&owner_==-token){owner_=0;std::atomic_store(&active_,std::shared_ptr<const LensMap>{});}
    }
    bool set(int token,std::shared_ptr<const LensMap> map) {
        std::lock_guard<std::mutex> lock(mutex_);
        if(token<=0||token!=owner_)return false;
        std::atomic_store(&active_,std::move(map));return true;
    }
    std::shared_ptr<const LensMap> current()const{return std::atomic_load(&active_);}
private:
    std::mutex mutex_;int owner_=0;
    std::shared_ptr<const LensMap> active_;
};
}
