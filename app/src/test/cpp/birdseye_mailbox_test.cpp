#include "../../main/cpp/birdseye_mailbox.h"
#include <cassert>
#include <iostream>
#include <thread>

int main() {
    constexpr int STRIDE = 1488;
    std::vector<uint8_t> source(STRIDE * 480, 0xEE), output(BirdseyeMailbox::BYTES, 0xCC);
    for (int y=0; y<480; ++y) for (int x=0; x<1440; ++x)
        source[y*STRIDE+x] = static_cast<uint8_t>((y*7+x)%251);
    const auto original = source;
    const BirdseyeMailbox::Layout layouts[] = {BirdseyeMailbox::Layout::STACKED_TOP_FIRST,
        BirdseyeMailbox::Layout::STACKED_BOTTOM_FIRST, BirdseyeMailbox::Layout::WOVEN};
    for (auto layout : layouts) {
        BirdseyeMailbox mailbox;
        BirdseyeMailbox::Info info;
        assert(!mailbox.copy(output.data(), output.size(), 0, info));
        assert(!mailbox.submit(source.data(), 719, 480, STRIDE, layout, 1000000, 5));
        assert(!mailbox.submit(source.data(), 720, 240, STRIDE, layout, 1000000, 5));
        assert(!mailbox.submit(source.data(), 720, 480, 100, layout, 1000000, 5));
        assert(mailbox.submit(source.data(), 720, 480, STRIDE, layout, 1000000, 5));
        assert(!mailbox.copy(output.data(), output.size()-1, 0, info));
        assert(mailbox.copy(output.data(), output.size(), 0, info));
        assert(info.capturedUs==1000000 && info.sequence==5);
        for (int y=0;y<240;++y) for(int x=0;x<1440;++x) {
            int row = layout==BirdseyeMailbox::Layout::WOVEN ? 2*y
                : y+(layout==BirdseyeMailbox::Layout::STACKED_BOTTOM_FIRST ? 240 : 0);
            assert(output[y*1440+x]==source[row*STRIDE+x]);
        }
        assert(source==original);
        assert(!mailbox.copy(output.data(),output.size(),1000000,info));
        assert(mailbox.submit(source.data(),720,480,STRIDE,layout,1020000,6));
        assert(mailbox.copy(output.data(),output.size(),1000000,info));
        assert(info.capturedUs==1020000 && info.sequence==6);
        assert(!mailbox.submit(source.data(),720,480,STRIDE,layout,1020000,6));
        assert(!mailbox.submit(source.data(),720,480,STRIDE,layout,1010000,5));
        assert(mailbox.submit(source.data(),720,480,STRIDE,layout,1060000,7));
        assert(mailbox.copy(output.data(),output.size(),1000000,info));
        assert(info.capturedUs==1060000 && info.sequence==7);
        mailbox.stop();assert(mailbox.capturedUs()==0);
        assert(!mailbox.copy(output.data(),output.size(),0,info));
        assert(!mailbox.submit(source.data(),720,480,STRIDE,layout,1120000,8));
    }
    // The native mailbox must not halve a 25 fps input with a 50 ms minimum interval.
    BirdseyeMailbox camera25fps;
    for (int i=0;i<25;i++) {
        assert(camera25fps.submit(source.data(),720,480,STRIDE,layouts[0],1000000+i*40000,i));
        BirdseyeMailbox::Info info;
        assert(camera25fps.copy(output.data(),output.size(),0,info));assert(info.sequence==i);
    }
    BirdseyeMailbox mailbox;
    std::thread producer([&]{for(int i=0;i<1000;i++)
        mailbox.submit(source.data(),720,480,STRIDE,layouts[0],1000000+i*50000,static_cast<uint32_t>(i));});
    for(int i=0;i<1000;i++) {
        BirdseyeMailbox::Info info;
        if(mailbox.copy(output.data(),output.size(),0,info))
            assert(info.capturedUs==1000000+info.sequence*50000LL);
    }
    producer.join();mailbox.stop();
    std::cout << "Birdseye mailbox: full 25 fps, monotonic timestamps, padded stride, spatial fields, ownership and concurrent copies passed\n";
}
