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

int main() {
    for (int height : {240, 480}) {
        // Real preview fields may have padding and may share their storage with another output.
        cv::Mat storage(height, 736, CV_8UC4);
        cv::Mat source = storage(cv::Rect(0, 0, 720, height));
        for (int y = 0; y < height; y++) for (int x = 0; x < 720; x++)
            source.at<cv::Vec4b>(y, x) = {static_cast<uint8_t>(255*x/719),
                    static_cast<uint8_t>(255*y/(height-1)), 42, 255};
        const auto originalSum = cv::sum(source);
        for (bool left : {true, false}) {
            cv::Mat upright, output;
            auto spec = crops(left);
            assert(valid(spec));
            compose(source, left, false, spec, upright, output);
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

            compose(source, left, true, spec, upright, output);
            assert(output.data == allocation); // Mode switches keep the same buffer dimensions.
            const int front = output.at<cv::Vec4b>(3, 100)[0];
            const int rear = output.at<cv::Vec4b>(452, 100)[0];
            assert(left ? front > 245 && rear < 10 : front < 10 && rear > 245);
            for (int y = 224; y < 232; y++) for (int x = 0; x < 254; x++)
                assert(output.at<cv::Vec4b>(y, x) == cv::Vec4b(0, 0, 0, 255));
            compose(source, left, false, spec, upright, output);
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
    std::cout << "Side preview: field/full-frame rotation, crops, padded stride, mode reuse and input isolation passed" << std::endl;
}
