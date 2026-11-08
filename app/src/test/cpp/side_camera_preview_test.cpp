#include "../../main/cpp/side_camera_preview.h"
#include <cassert>
#include <iostream>
#include <limits>

using namespace side_camera_preview;

static Crops crops(bool left) {
    const float band = 254.f / 456 * 720 / 480;
    const float frontSpan = .66f * 480 / 720 * 224 / 254;
    const float rearSpan = .48f * 480 / 720 * 224 / 254;
    return {left ? 1-band : 0, 0, left ? 1 : band, 1,
            left ? .34f : 0, .01f, left ? 1 : .66f, .01f + frontSpan,
            left ? .40f : .12f, 1-rearSpan, left ? .88f : .60f, 1};
}

// Independent inverse screen-space rotation oracle over the source's known colour ramp.
// Also checks that rotation samples neighbouring source pixels beyond the original crop.
static void checkAlignedWheel(const cv::Mat& wheel, const Crops& spec, int crop,
                              bool left, int rawHeight, double degrees) {
    const double angle = degrees * CV_PI / 180;
    const double c = std::cos(angle), s = std::sin(angle);
    const int i = crop * 4;
    int black = 0, neighbouring = 0, checked = 0;
    for (int y = 3; y < WHEEL_HEIGHT; y += 11) for (int x = 3; x < WIDTH; x += 11) {
        const double dx = x + .5 - WIDTH * .5, dy = y + .5 - WHEEL_HEIGHT * .5;
        const double u = (spec[i] + spec[i+2]) * .5
                + (c*dx + s*dy) / WIDTH * (spec[i+2] - spec[i]);
        const double v = (spec[i+1] + spec[i+3]) * .5
                + (-s*dx + c*dy) / WHEEL_HEIGHT * (spec[i+3] - spec[i+1]);
        const double uprightX = u * rawHeight - .5, uprightY = v * 720 - .5;
        const auto pixel = wheel.at<cv::Vec4b>(y, x);
        assert(pixel[3] == 255);
        if (uprightX < -1 || uprightX > rawHeight || uprightY < -1 || uprightY > 720) {
            assert(pixel == cv::Vec4b(0, 0, 0, 255));
            ++black;
        } else if (uprightX >= 1 && uprightX < rawHeight-2 && uprightY >= 1 && uprightY < 718) {
            const double rawX = left ? 719-uprightY : uprightY;
            const double rawY = left ? uprightX : rawHeight-1-uprightX;
            assert(std::abs(pixel[0] - 255*rawX/719) < 2);
            assert(std::abs(pixel[1] - 255*rawY/(rawHeight-1)) < 2);
            assert(pixel[2] == 42);
            ++checked;
            if (u < spec[i] || u > spec[i+2] || v < spec[i+1] || v > spec[i+3]) ++neighbouring;
        }
    }
    assert(black > 0 && checked > 100 && neighbouring > 0);
}

int main() {
    WheelTransforms transforms;
    for (int height : {240, 480}) {
        // Real preview fields may have padding and may share their storage with another output.
        cv::Mat storage(height, 736, CV_8UC4);
        cv::Mat source = storage(cv::Rect(0, 0, 720, height));
        for (int y = 0; y < height; y++) for (int x = 0; x < 720; x++)
            source.at<cv::Vec4b>(y, x) = {static_cast<uint8_t>(255*x/719),
                    static_cast<uint8_t>(255*y/(height-1)), 42, 255};
        const auto originalSum = cv::sum(source);
        const cv::Mat original = source.clone();
        for (bool left : {true, false}) {
            cv::Mat upright, output;
            auto spec = crops(left);
            assert(valid(spec));
            compose(source, left, false, spec, upright, output, transforms);
            assert(output.size() == cv::Size(254, 456));
            assert(output.type() == CV_8UC4);
            auto* allocation = output.data;
            const int top = output.at<cv::Vec4b>(3, 100)[0];
            const int bottom = output.at<cv::Vec4b>(452, 100)[0];
            assert(left ? top > 245 && bottom < 10 : top < 10 && bottom > 245);
            // Side-specific rotation must preserve inner/outer edges, without an extra mirror.
            int greenLeft = output.at<cv::Vec4b>(200, 3)[1];
            int greenRight = output.at<cv::Vec4b>(200, 250)[1];
            assert(left ? greenLeft < greenRight : greenLeft > greenRight);
            assert(output.at<cv::Vec4b>(228, 100)[2] == 42);
            const cv::Mat originalSide = output.clone();

            compose(source, left, true, spec, upright, output, transforms);
            assert(output.data == allocation); // Mode switches keep the same buffer dimensions.
            const int front = output.at<cv::Vec4b>(3, 100)[0];
            const int rear = output.at<cv::Vec4b>(452, 100)[0];
            assert(left ? front > 245 && rear < 10 : front < 10 && rear > 245);
            for (int y = 224; y < 232; y++) for (int x = 0; x < 254; x++)
                assert(output.at<cv::Vec4b>(y, x) == cv::Vec4b(0, 0, 0, 255));
            const cv::Mat originalWheels = output.clone();
            for (unsigned alignment = 0; alignment < 4; ++alignment) {
                compose(source, left, true, spec, upright, output, transforms, alignment);
                assert(output.data == allocation);
                assert(output.size() == cv::Size(WIDTH, HEIGHT));
                for (int axle = 0; axle < 2; ++axle) {
                    const cv::Rect tile(0, axle * (WHEEL_HEIGHT+GAP), WIDTH, WHEEL_HEIGHT);
                    if (alignment & (1U << axle)) {
                        checkAlignedWheel(output(tile), spec, axle+1, left, height,
                                axle == 0 ? (left ? 16.5 : -16.5) : (left ? -45 : 45));
                    } else {
                        assert(cv::norm(output(tile), originalWheels(tile), cv::NORM_INF) == 0);
                    }
                }
                for (int y = 224; y < 232; y++) for (int x = 0; x < WIDTH; x++)
                    assert(output.at<cv::Vec4b>(y, x) == cv::Vec4b(0, 0, 0, 255));
                // Repeated frames reuse both transforms, without changing their mapping.
                const auto frontTransform = transforms.front, rearTransform = transforms.rear;
                compose(source, left, true, spec, upright, output, transforms, alignment);
                if (alignment) {
                    assert(cv::norm(cv::Mat(transforms.front), cv::Mat(frontTransform), cv::NORM_INF) == 0);
                    assert(cv::norm(cv::Mat(transforms.rear), cv::Mat(rearTransform), cv::NORM_INF) == 0);
                }
                compose(source, left, false, spec, upright, output, transforms, alignment);
                assert(output.data == allocation);
                assert(cv::norm(output, originalSide, cv::NORM_INF) == 0);
                assert(cv::norm(source, original, cv::NORM_INF) == 0);
            }
            // Live edits must invalidate cached matrices even with identical frame/crop sizes.
            for (const auto angles : {cv::Vec2f(-22.5f, 30.f), cv::Vec2f(0.f, 0.f),
                                      cv::Vec2f(180.f, -180.f), cv::Vec2f(16.5f, -45.f)}) {
                compose(source, left, true, spec, upright, output, transforms, 3, angles[0], angles[1]);
                assert(output.data == allocation);
                if (angles[0] == 0 && angles[1] == 0) {
                    assert(cv::norm(output, originalWheels, cv::NORM_INF) == 0);
                } else if (std::abs(angles[0]) < 180) {
                    checkAlignedWheel(output(cv::Rect(0, 0, WIDTH, WHEEL_HEIGHT)), spec, 1, left,
                            height, left ? angles[0] : -angles[0]);
                    checkAlignedWheel(output(cv::Rect(0, WHEEL_HEIGHT+GAP, WIDTH, WHEEL_HEIGHT)), spec, 2, left,
                            height, left ? angles[1] : -angles[1]);
                }
                compose(source, left, false, spec, upright, output, transforms, 3, angles[0], angles[1]);
                assert(cv::norm(output, originalSide, cv::NORM_INF) == 0);
                assert(cv::norm(source, original, cv::NORM_INF) == 0);
            }
            compose(source, left, false, spec, upright, output, transforms);
            assert(output.data == allocation);
            assert(output.at<cv::Vec4b>(228, 100)[2] == 42); // No stale wheel-mode gap.
            assert(cv::sum(source) == originalSum); // Other camera/recording consumers remain intact.
        }
    }
    auto invalid = crops(true);
    invalid[0] = std::numeric_limits<float>::quiet_NaN();
    assert(!valid(invalid));
    invalid = crops(true); invalid[2] = invalid[0]; assert(!valid(invalid));
    invalid = crops(false); invalid[7] = 2; assert(!valid(invalid));
    std::cout << "Side preview: custom/reset/zero angles, all axle combinations, angle direction, neighbouring source pixels, black borders, "
                 "legacy output, field/full frames, padded stride, buffer reuse and input isolation passed" << std::endl;
}
