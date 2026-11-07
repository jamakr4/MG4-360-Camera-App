#pragma once

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <array>
#include <cmath>

namespace side_camera_preview {
// Side/front-wheel/rear-wheel crops supplied by the existing Java crop definitions.
using Crops = std::array<float, 12>;
constexpr int WIDTH = 254, HEIGHT = 456, WHEEL_HEIGHT = 224, GAP = 8;

inline bool valid(const Crops& crops) {
    for (int i = 0; i < 12; i += 4) {
        for (int j = 0; j < 4; ++j)
            if (!std::isfinite(crops[i+j]) || crops[i+j] < 0 || crops[i+j] > 1) return false;
        if (crops[i+2] <= crops[i] || crops[i+3] <= crops[i+1]) return false;
    }
    return true;
}

inline cv::Rect region(const cv::Mat& image, const Crops& crops, int crop) {
    const int i = crop * 4;
    const int x0 = std::min(image.cols - 1, cvRound(crops[i] * image.cols));
    const int y0 = std::min(image.rows - 1, cvRound(crops[i+1] * image.rows));
    const int x1 = std::max(x0 + 1, std::min(image.cols, cvRound(crops[i+2] * image.cols)));
    const int y1 = std::max(y0 + 1, std::min(image.rows, cvRound(crops[i+3] * image.rows)));
    return {x0, y0, x1-x0, y1-y0};
}

// Use the same RGBA field as the main preview. No second YUV conversion or GL upload.
// Work runs on the existing independent preview worker, never on the capture thread.
inline void compose(const cv::Mat& rgba, bool left, bool wheels, const Crops& crops,
                    cv::Mat& upright, cv::Mat& output) {
    cv::rotate(rgba, upright, left ? cv::ROTATE_90_COUNTERCLOCKWISE : cv::ROTATE_90_CLOCKWISE);
    output.create(HEIGHT, WIDTH, CV_8UC4);
    if (wheels) {
        output.setTo(cv::Scalar(0, 0, 0, 255));
        cv::Mat front = output(cv::Rect(0, 0, WIDTH, WHEEL_HEIGHT));
        cv::Mat rear = output(cv::Rect(0, WHEEL_HEIGHT + GAP, WIDTH, WHEEL_HEIGHT));
        cv::resize(upright(region(upright, crops, 1)), front, front.size(), 0, 0, cv::INTER_LINEAR);
        cv::resize(upright(region(upright, crops, 2)), rear, rear.size(), 0, 0, cv::INTER_LINEAR);
    } else {
        cv::resize(upright(region(upright, crops, 0)), output, output.size(), 0, 0, cv::INTER_LINEAR);
    }
}
}
