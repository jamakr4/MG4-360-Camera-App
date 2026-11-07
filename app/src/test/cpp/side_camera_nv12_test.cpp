#include "../../main/cpp/side_camera_nv12.h"
#include <array>
#include <cassert>
int main() {
    // Padded rows: U Y V Y with two fields stacked (10,30 then 20,40).
    const uint8_t packed[]{100,10,150,11,255,255, 101,30,151,31,255,255,
                           102,20,152,21,255,255, 103,40,153,41,255,255};
    std::array<uint8_t,12> out{};
    assert(side_camera_frames::stackedUyvyToNv12(packed,6,2,4,out.data(),out.size()));
    const std::array<uint8_t,12> expected{10,11,20,21,30,31,40,41,100,150,101,151};
    assert(out==expected); // Conversion leaves rotation/mirroring to the side-camera shader.
    assert(!side_camera_frames::stackedUyvyToNv12(packed,3,2,4,out.data(),out.size()));
    assert(!side_camera_frames::stackedUyvyToNv12(packed,6,2,4,out.data(),out.size()-1));
    assert(!side_camera_frames::stackedUyvyToNv12(packed,6,1,4,out.data(),out.size()));
}
