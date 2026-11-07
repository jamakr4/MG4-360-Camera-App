#pragma once

#include <opencv2/core.hpp>
#include <array>
#include <cstdint>

namespace side_camera_frames {
constexpr int SIDE_COUNT = 2;
constexpr int MAX_AGE_MS = 500;
void submit(int side, const cv::Mat& packed);
void clear(int side);
void snapshot(std::array<cv::Mat, SIDE_COUNT>& frames,
              std::array<int64_t, SIDE_COUNT>& ages);
}
