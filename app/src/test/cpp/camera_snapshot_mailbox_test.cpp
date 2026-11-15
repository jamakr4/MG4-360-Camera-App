#include "../../main/cpp/camera_snapshot_mailbox.h"
#include <cassert>
#include <cstdio>
int main(){
    CameraSnapshotMailbox box;CameraSnapshotMailbox::Info info;
    const int stride=CameraSnapshotMailbox::STRIDE+16;
    std::vector<uint8_t> input(stride*480,99),output(CameraSnapshotMailbox::BYTES);
    for(int y=0;y<480;y++)std::memset(input.data()+y*stride,y%251,1440);
    assert(!box.copy(output.data(),output.size(),0,info));
    assert(box.submit(input.data(),720,480,stride,6,1000,42));
    assert(box.copy(output.data(),output.size(),0,info));assert(info.field==6&&info.sequence==42&&info.sourceStride==stride);
    for(int y=0;y<480;y++)for(int x=0;x<1440;x++)assert(output[y*1440+x]==y%251);
    assert(!box.copy(output.data(),output.size(),1000,info));
    assert(!box.submit(input.data(),719,480,stride,5,2000,43));
    assert(!box.submit(input.data(),720,480,100,5,2000,43));
    assert(!box.copy(output.data(),1,0,info));box.stop();assert(box.capturedUs()==0);
    assert(!box.copy(output.data(),output.size(),0,info));
    assert(!box.submit(input.data(),720,480,stride,5,3000,44));
    std::puts("Full snapshot mailbox: padded raw frame, metadata, freshness and stop passed");
}
