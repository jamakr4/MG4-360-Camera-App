#pragma once
#include <cstddef>
#include <cstdint>
namespace side_camera_frames {
// UYVY fields are stacked, NV12 scan lines are interleaved. Destination is tightly packed.
inline bool stackedUyvyToNv12(const uint8_t* src, size_t stride, int width, int height,
                              uint8_t* dst, size_t dstSize) {
    if (!src || !dst || width <= 0 || height <= 0 || width % 2 || height % 2
            || stride < static_cast<size_t>(width) * 2
            || dstSize < static_cast<size_t>(width) * height * 3 / 2) return false;
    for (int y = 0; y < height; ++y) {
        const auto* row = src + (y / 2 + (y % 2) * (height / 2)) * stride;
        for (int x = 0; x < width; ++x) dst[y * width + x] = row[x * 2 + 1];
    }
    auto* uv = dst + width * height;
    for (int y = 0; y < height / 2; ++y) {
        const auto* row = src + y * stride;
        for (int x = 0; x < width / 2; ++x) {
            uv[y * width + x * 2] = row[x * 4];
            uv[y * width + x * 2 + 1] = row[x * 4 + 2];
        }
    }
    return true;
}
}
