#pragma once
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

// Temporary full-frame tap. Capture only copies packed bytes; no conversion or filesystem work.
class CameraSnapshotMailbox {
public:
    static constexpr int WIDTH=720, HEIGHT=480, STRIDE=WIDTH*2, BYTES=STRIDE*HEIGHT;
    struct Info { int64_t capturedUs=0; uint32_t sequence=0,field=0; size_t sourceStride=0; };
    CameraSnapshotMailbox():pixels_(BYTES){}
    bool submit(const uint8_t* input,int width,int height,size_t stride,uint32_t field,int64_t time,uint32_t sequence) {
        std::unique_lock<std::mutex> lock(mutex_,std::try_to_lock);
        if(!lock||stopped_||!input||width!=WIDTH||height!=HEIGHT||stride<STRIDE||time<=0)return false;
        for(int y=0;y<HEIGHT;++y)std::memcpy(pixels_.data()+y*STRIDE,input+y*stride,STRIDE);
        info_={time,sequence,field,stride};return true;
    }
    bool copy(uint8_t* into,size_t capacity,int64_t after,Info& info) {
        std::unique_lock<std::mutex> lock(mutex_,std::try_to_lock);
        if(!lock||stopped_||!into||capacity<BYTES||info_.capturedUs<=after)return false;
        std::memcpy(into,pixels_.data(),BYTES);info=info_;return true;
    }
    int64_t capturedUs(){std::lock_guard<std::mutex> lock(mutex_);return stopped_?0:info_.capturedUs;}
    void stop(){std::lock_guard<std::mutex> lock(mutex_);stopped_=true;info_={};}
private:
    std::mutex mutex_;std::vector<uint8_t> pixels_;Info info_;bool stopped_=false;
};
