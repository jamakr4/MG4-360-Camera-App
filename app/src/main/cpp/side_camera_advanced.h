#pragma once
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <array>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace side_camera_advanced {
constexpr int WIDTH=254, HEIGHT=224, FLOATS=WIDTH*HEIGHT*2;
constexpr int SIDE_HEIGHT=456, SIDE_FLOATS=WIDTH*SIDE_HEIGHT*2;
using Inputs=std::array<std::vector<float>,6>;
inline cv::Mat makeHatch(int height){
        cv::Mat image(height,WIDTH,CV_8UC4,cv::Scalar(0x30,0x23,0x2E,255));
        for(int x=-height;x<WIDTH;x+=12)cv::line(image,{x,0},{x+height,height},cv::Scalar(0x79,0x50,0x6B,255),1,cv::LINE_AA);
        return image;
}
inline const cv::Mat& hatch(int height=HEIGHT){
    static const cv::Mat wheel=makeHatch(HEIGHT),side=makeHatch(SIDE_HEIGHT);
    return height==SIDE_HEIGHT?side:wheel;
}

// Immutable maps in original, unmirrored 720x480 calibration coordinates.
// Both source layouts are prepared once; posting a frame only performs remap/add.
class LensMap {
public:
    explicit LensMap(const std::vector<float>& uv,int height=HEIGHT){
        if((height!=HEIGHT&&height!=SIDE_HEIGHT)||uv.size()!=size_t(WIDTH*height*2))throw std::invalid_argument("Invalid lens map size");
        x_.create(height,WIDTH,CV_32FC1);fullY_.create(height,WIDTH,CV_32FC1);
        fieldY_.create(height,WIDTH,CV_32FC1);missing_.create(height,WIDTH,CV_8UC4);
        int covered=0;
        for(int y=0;y<height;y++)for(int x=0;x<WIDTH;x++){
            int i=(y*WIDTH+x)*2;float u=uv[i],v=uv[i+1];bool absent=std::isnan(u)&&std::isnan(v);
            if(!absent&&(!std::isfinite(u)||!std::isfinite(v)||u<0||u>719||v<0||v>479))
                throw std::invalid_argument("Invalid lens coordinates");
            x_.at<float>(y,x)=absent?-10000:u;fullY_.at<float>(y,x)=absent?-10000:v;
            // Top field row j is reference row 2*j; bob/clamp the final unobserved odd row.
            fieldY_.at<float>(y,x)=absent?-10000:std::min(239.f,v*.5f);
            auto tint=absent?hatch(height).at<cv::Vec4b>(y,x):cv::Vec4b(0,0,0,0);tint[3]=0;
            missing_.at<cv::Vec4b>(y,x)=tint;
            if(!absent)covered++;
        }
        if(!covered)throw std::invalid_argument("Empty lens coverage");
    }
    bool draw(const cv::Mat& rgba,cv::Mat& target,bool highlight)const{
        if(rgba.cols!=720||(rgba.rows!=240&&rgba.rows!=480)||rgba.type()!=CV_8UC4)return false;
        cv::remap(rgba,target,x_,rgba.rows==240?fieldY_:fullY_,cv::INTER_LINEAR,
                  cv::BORDER_CONSTANT,cv::Scalar(0,0,0,255));
        if(highlight)cv::add(target,missing_,target);
        return true;
    }
private:
    cv::Mat x_,fullY_,fieldY_,missing_;
};

struct Maps {
    const std::string key;
    // left front/rear, right front/rear
    const std::array<LensMap,4> wheel;
    const std::array<LensMap,2> side;
    Maps(std::string identity,const Inputs& uv)
        :key(std::move(identity)),wheel{LensMap(uv[0]),LensMap(uv[1]),LensMap(uv[2]),LensMap(uv[3])},
         side{LensMap(uv[4],SIDE_HEIGHT),LensMap(uv[5],SIDE_HEIGHT)}{
        if(key.empty()||key.size()>128)throw std::invalid_argument("Invalid calibration key");
    }
};

// Generation-checked publication prevents an old loader from reactivating maps after pause,
// OEM handoff, calibration failure or a newer Activity. No camera/session mutex is involved.
class Registry {
public:
    void session(int token){
        std::lock_guard<std::mutex> lock(mutex_);
        if(token>0){owner_=token;std::atomic_store(&active_,std::shared_ptr<const Maps>{});}
        else if(token<0&&owner_==-token){owner_=0;std::atomic_store(&active_,std::shared_ptr<const Maps>{});}
    }
    bool activate(int token,const std::string& key){
        std::lock_guard<std::mutex> lock(mutex_);
        if(token<=0||token!=owner_)return false;
        auto selected=cached_&&!key.empty()&&cached_->key==key?cached_:std::shared_ptr<const Maps>{};
        std::atomic_store(&active_,selected);return selected!=nullptr;
    }
    bool install(int token,std::shared_ptr<const Maps> maps){
        std::lock_guard<std::mutex> lock(mutex_);
        if(token<=0||token!=owner_||!maps)return false;
        cached_=std::move(maps);std::atomic_store(&active_,cached_);return true;
    }
    std::shared_ptr<const Maps> current()const{return std::atomic_load(&active_);}
private:
    std::mutex mutex_;int owner_=0;
    std::shared_ptr<const Maps> cached_,active_;
};
}
