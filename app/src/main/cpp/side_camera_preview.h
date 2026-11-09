#pragma once

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <array>
#include <cmath>

namespace side_camera_preview {
// Side/front-wheel/rear-wheel crops supplied by the existing Java crop definitions.
using Crops = std::array<float, 12>;
constexpr int WIDTH = 254, HEIGHT = 456, WHEEL_HEIGHT = 224, GAP = 8;
constexpr unsigned ALIGN_FRONT = 1, ALIGN_REAR = 2;

inline float clampAngle(float degrees, float fallback) {
    return std::isfinite(degrees) ? std::max(-180.f, std::min(180.f, degrees)) : fallback;
}

// Android Canvas uses clockwise-positive screen angles. Compose scale/crop and rotation
// directly, avoiding OpenCV getRotationMatrix2D's opposite angle convention.
inline cv::Matx23d wheelTransform(cv::Size source, const Crops& crops, int crop, double degrees) {
    const int i = crop * 4;
    const double sx = WIDTH / ((crops[i+2] - crops[i]) * source.width);
    const double sy = WHEEL_HEIGHT / ((crops[i+3] - crops[i+1]) * source.height);
    // Canvas maps pixel edges; OpenCV addresses pixel centres at integer coordinates.
    const double tx = sx * (.5 - crops[i] * source.width) - .5;
    const double ty = sy * (.5 - crops[i+1] * source.height) - .5;
    const double cx = (WIDTH - 1) * .5, cy = (WHEEL_HEIGHT - 1) * .5;
    const double radians = degrees * CV_PI / 180;
    const double c = std::cos(radians), s = std::sin(radians);
    return {c*sx, -s*sy, c*(tx-cx) - s*(ty-cy) + cx,
            s*sx, c*sy, s*(tx-cx) + c*(ty-cy) + cy};
}

// One cache per output worker; reuse matrices, coverage and tint buffers between frames.
struct WheelTransforms {
    cv::Matx23d front, rear;
    cv::Mat frontMissingTint, rearMissingTint;

    void update(cv::Size source, bool left, const Crops& crops, float frontDegrees, float rearDegrees) {
        if (ready_ && source == source_ && left == left_ && crops == crops_
                && frontDegrees == frontDegrees_ && rearDegrees == rearDegrees_) return;
        front = wheelTransform(source, crops, 1, left ? frontDegrees : -frontDegrees);
        rear = wheelTransform(source, crops, 2, left ? rearDegrees : -rearDegrees);
        source_ = source;
        left_ = left;
        crops_ = crops;
        frontDegrees_ = frontDegrees;
        rearDegrees_ = rearDegrees;
        ready_ = true;
        highlightReady_ = false;
    }

    void prepareHighlight() {
        if (highlightReady_) return;
        sourceCoverage_.create(source_, CV_8UC1);
        sourceCoverage_.setTo(255);
        makeMissingTint(front, frontMissingTint);
        makeMissingTint(rear, rearMissingTint);
        highlightReady_ = true;
    }

private:
    // Same background, diagonal colour and 12px spacing as the local Canvas debug view.
    static const cv::Mat& hatch() {
        static const cv::Mat pattern = [] {
            cv::Mat image(WHEEL_HEIGHT, WIDTH, CV_8UC4, cv::Scalar(0x30, 0x23, 0x2E, 255));
            for (int x = -WHEEL_HEIGHT; x < WIDTH; x += 12)
                cv::line(image, {x, 0}, {x+WHEEL_HEIGHT, WHEEL_HEIGHT},
                        cv::Scalar(0x79, 0x50, 0x6B, 255), 1, cv::LINE_AA);
            return image;
        }();
        return pattern;
    }

    void makeMissingTint(const cv::Matx23d& transform, cv::Mat& tint) {
        // Warp an opaque source mask once per geometry change. Camera RGB is never used
        // to detect missing pixels, so actual black/shadowed camera areas stay untouched.
        cv::warpAffine(sourceCoverage_, coverage_, transform, {WIDTH, WHEEL_HEIGHT},
                cv::INTER_LINEAR, cv::BORDER_CONSTANT, 0);
        tint.create(WHEEL_HEIGHT, WIDTH, CV_8UC4);
        for (int y = 0; y < WHEEL_HEIGHT; ++y) {
            const auto* coverage = coverage_.ptr<uint8_t>(y);
            const auto* pattern = hatch().ptr<cv::Vec4b>(y);
            auto* row = tint.ptr<cv::Vec4b>(y);
            for (int x = 0; x < WIDTH; ++x) {
                const unsigned missing = 255 - coverage[x];
                // The RGBA warp already contains camera RGB multiplied by coverage. Add
                // only the missing background contribution, including antialiased edges.
                row[x] = cv::Vec4b((pattern[x][0]*missing+127)/255,
                        (pattern[x][1]*missing+127)/255, (pattern[x][2]*missing+127)/255, 0);
            }
        }
    }

    bool ready_ = false, left_ = false, highlightReady_ = false;
    cv::Size source_;
    Crops crops_{};
    float frontDegrees_ = 0, rearDegrees_ = 0;
    cv::Mat sourceCoverage_, coverage_;
};

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
                    cv::Mat& upright, cv::Mat& output, WheelTransforms& transforms,
                    unsigned alignment = 0, float frontDegrees = 16.5f, float rearDegrees = -45.f,
                    bool highlightMissingAreas = false) {
    cv::rotate(rgba, upright, left ? cv::ROTATE_90_COUNTERCLOCKWISE : cv::ROTATE_90_CLOCKWISE);
    output.create(HEIGHT, WIDTH, CV_8UC4);
    if (wheels) {
        output.setTo(cv::Scalar(0, 0, 0, 255));
        cv::Mat front = output(cv::Rect(0, 0, WIDTH, WHEEL_HEIGHT));
        cv::Mat rear = output(cv::Rect(0, WHEEL_HEIGHT + GAP, WIDTH, WHEEL_HEIGHT));
        const bool rotateFront = (alignment & ALIGN_FRONT) && frontDegrees != 0;
        const bool rotateRear = (alignment & ALIGN_REAR) && rearDegrees != 0;
        if (rotateFront || rotateRear) transforms.update(upright.size(), left, crops, frontDegrees, rearDegrees);
        if (highlightMissingAreas && (rotateFront || rotateRear)) transforms.prepareHighlight();
        if (rotateFront) {
            cv::warpAffine(upright, front, transforms.front, front.size(), cv::INTER_LINEAR,
                    cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0, 255));
            if (highlightMissingAreas) cv::add(front, transforms.frontMissingTint, front);
        } else {
            cv::resize(upright(region(upright, crops, 1)), front, front.size(), 0, 0, cv::INTER_LINEAR);
        }
        if (rotateRear) {
            cv::warpAffine(upright, rear, transforms.rear, rear.size(), cv::INTER_LINEAR,
                    cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0, 255));
            if (highlightMissingAreas) cv::add(rear, transforms.rearMissingTint, rear);
        } else {
            cv::resize(upright(region(upright, crops, 2)), rear, rear.size(), 0, 0, cv::INTER_LINEAR);
        }
    } else {
        cv::resize(upright(region(upright, crops, 0)), output, output.size(), 0, 0, cv::INTER_LINEAR);
    }
}
}
