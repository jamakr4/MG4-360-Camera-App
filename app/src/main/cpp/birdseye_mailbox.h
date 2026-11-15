#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

// One spatial TOP field, UYVY, unmirrored, tightly packed. Row j is reference
// image row 2*j in the 720x480 calibration. The GLES sampler bobs/interpolates
// these rows; it never interprets stacked fields as a progressive image.
class BirdseyeMailbox {
public:
    static constexpr int WIDTH = 720, HEIGHT = 240, STRIDE = WIDTH * 2;
    static constexpr int BYTES = STRIDE * HEIGHT;
    enum class Layout { STACKED_TOP_FIRST, STACKED_BOTTOM_FIRST, WOVEN };
    struct Info { int64_t capturedUs = 0; uint32_t sequence = 0; };

    BirdseyeMailbox() : pixels_(BYTES) {}

    bool submit(const uint8_t* data, int width, int height, size_t stride,
                Layout layout, int64_t capturedUs, uint32_t sequence) {
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (!lock || stopped_ || !data || width != WIDTH || height != HEIGHT * 2
                || stride < STRIDE || capturedUs <= 0
                || capturedUs <= info_.capturedUs) {
            dropped_.fetch_add(1);
            return false;
        }
        for (int y = 0; y < HEIGHT; ++y) {
            const int row = layout == Layout::WOVEN ? 2 * y
                    : y + (layout == Layout::STACKED_BOTTOM_FIRST ? HEIGHT : 0);
            std::memcpy(pixels_.data() + y * STRIDE, data + row * stride, STRIDE);
        }
        info_ = {capturedUs, sequence};
        return true;
    }

    bool copy(uint8_t* destination, size_t capacity, int64_t afterUs, Info& info) {
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (!lock || stopped_ || !destination || capacity < BYTES
                || info_.capturedUs <= afterUs) return false;
        std::memcpy(destination, pixels_.data(), BYTES);
        info = info_;
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopped_ = true;
        info_ = {};
    }
    int64_t capturedUs() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stopped_ ? 0 : info_.capturedUs;
    }
    int64_t dropped() const { return dropped_.load(); }

private:
    std::mutex mutex_;
    std::vector<uint8_t> pixels_;
    Info info_;
    bool stopped_ = false;
    std::atomic<int64_t> dropped_{0};
};
