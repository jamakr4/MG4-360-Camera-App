#include "../../main/cpp/side_camera_preview.h"
#include <cassert>
#include <iostream>
#include <limits>

using namespace side_camera_advanced;
static Inputs coordinates(){
    Inputs uv;
    for(int n=0;n<6;n++){auto& map=uv[n];int height=n<4?HEIGHT:SIDE_HEIGHT;map.resize(WIDTH*height*2);for(int y=0;y<height;y++)for(int x=0;x<WIDTH;x++){int i=(y*WIDTH+x)*2;map[i]=300+x*.5f;map[i+1]=180+y*.5f;}}
    return uv;
}
static side_camera_preview::Crops crops(){return {0,0,1,1,0,0,.8f,.5f,.2f,.5f,1,1};}
int main(){
    auto uv=coordinates();uv[0][0]=uv[0][1]=std::numeric_limits<float>::quiet_NaN();
    auto maps=std::make_shared<const Maps>("synthetic-a",uv);
    cv::Mat full(480,720,CV_8UC4),field(240,720,CV_8UC4);
    for(int y=0;y<480;y++)for(int x=0;x<720;x++)full.at<cv::Vec4b>(y,x)=cv::Vec4b(x%251,y/2,37,255);
    for(int y=0;y<240;y++)full.row(y*2).copyTo(field.row(y));
    cv::Mat a,b;
    assert(maps->wheel[0].draw(full,a,false));assert(maps->wheel[0].draw(field,b,false));
    assert(a.at<cv::Vec4b>(0,0)==cv::Vec4b(0,0,0,255));
    assert(a.at<cv::Vec4b>(0,2)==full.at<cv::Vec4b>(180,301));
    assert(b.at<cv::Vec4b>(0,2)==full.at<cv::Vec4b>(180,301));
    assert(maps->wheel[0].draw(full,a,true));assert(a.at<cv::Vec4b>(0,0)!=cv::Vec4b(0,0,0,255));
    cv::Mat black(480,720,CV_8UC4,cv::Scalar(0,0,0,255));maps->wheel[0].draw(black,a,true);
    assert(a.at<cv::Vec4b>(0,2)==cv::Vec4b(0,0,0,255)); // true black is not a missing pixel
    auto c=crops();cv::Mat upright,legacy,mixed;
    side_camera_preview::WheelTransforms cache;
    side_camera_preview::compose(full,true,true,c,upright,legacy,cache,3,16.5f,-45.f,true);
    side_camera_preview::compose(full,true,true,c,upright,mixed,cache,3,16.5f,-45.f,true,maps.get(),1);
    assert(cv::norm(legacy(cv::Rect(0,232,254,224)),mixed(cv::Rect(0,232,254,224)),cv::NORM_INF)==0);
    assert(mixed.at<cv::Vec4b>(0,2)==full.at<cv::Vec4b>(180,301));
    side_camera_preview::compose(full,false,true,c,upright,mixed,cache,3,16.5f,-45.f,false,maps.get(),2);
    assert(mixed.at<cv::Vec4b>(232,2)==full.at<cv::Vec4b>(180,301));
    side_camera_preview::compose(full,true,false,c,upright,legacy,cache,3,16.5f,-45.f,true);
    side_camera_preview::compose(full,true,false,c,upright,mixed,cache,3,16.5f,-45.f,true,maps.get(),3);
    assert(cv::norm(legacy,mixed,cv::NORM_INF)==0);
    for(bool left:{true,false}){
        side_camera_preview::compose(full,left,false,c,upright,mixed,cache,0,0,0,false,maps.get(),0,true);
        assert(mixed.rows==SIDE_HEIGHT&&mixed.cols==WIDTH);
        assert(mixed.at<cv::Vec4b>(0,2)==full.at<cv::Vec4b>(180,301));
        side_camera_preview::compose(field,left,false,c,upright,a,cache,0,0,0,false,maps.get(),0,true);
        assert(cv::norm(mixed,a,cv::NORM_INF)<=1);
        side_camera_preview::compose(full,left,false,c,upright,legacy,cache);
        side_camera_preview::compose(full,left,false,c,upright,mixed,cache,0,0,0,true,nullptr,0,true);
        assert(cv::norm(legacy,mixed,cv::NORM_INF)==0); // unavailable correction keeps original
    }
    uv[4][0]=uv[4][1]=std::numeric_limits<float>::quiet_NaN();
    Maps missingSide("side-missing",uv);
    side_camera_preview::compose(black,true,false,c,upright,a,cache,0,0,0,true,&missingSide,0,true);
    side_camera_preview::compose(black,true,false,c,upright,b,cache,0,0,0,false,&missingSide,0,true);
    assert(a.at<cv::Vec4b>(0,0)!=b.at<cv::Vec4b>(0,0));
    assert(a.at<cv::Vec4b>(0,2)==cv::Vec4b(0,0,0,255));
    for(int invalid=0;invalid<3;invalid++){
        auto bad=coordinates();if(invalid==0)bad[0].pop_back();else bad[0][0]=invalid==1?720:std::numeric_limits<float>::infinity();
        bool rejected=false;try{Maps ignored("bad",bad);}catch(const std::invalid_argument&){rejected=true;}assert(rejected);
    }
    Registry registry;registry.session(1);assert(registry.install(1,maps));assert(registry.current()==maps);
    registry.session(2);assert(!registry.current());assert(!registry.install(1,maps));
    assert(registry.activate(2,"synthetic-a"));assert(registry.current()==maps);
    registry.session(-1);assert(registry.current()==maps); // old Activity cannot clear new maps
    assert(!registry.activate(2,"changed-calibration"));assert(!registry.current());
    assert(registry.activate(2,"synthetic-a"));registry.session(-2);assert(!registry.current());
    assert(!registry.activate(2,"synthetic-a"));assert(!registry.install(2,maps));
    std::cout<<"Advanced curbside: field/reference sampling, independent axles, legacy side view, geometric hatching, bounds and stale-session rejection passed\n";
}
