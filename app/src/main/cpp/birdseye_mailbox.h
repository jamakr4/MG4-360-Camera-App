#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

// One full woven frame, UYVY, unmirrored, tightly packed, 720x480 = the calibration's
// reference image. The camera delivers its two fields stacked (rows 0..239 the first,
// 240..479 the second); both are interleaved here, as the OEM app shows the
// full height. Earlier builds kept the top field only and let the shader interpolate.
//
// Triple buffered: the producer fills its own back slot without any lock, the reader
// copies out of its own slot without any lock, and only the slot exchange is locked.
// Neither side can make the other drop a frame: the previous single buffer was guarded
// by try_lock on both ends, so a frame arriving while the GL thread was copying was
// thrown away and the previous image stayed up for a whole extra camera period.
class BirdseyeMailbox
{
public:
    static constexpr int WIDTH = 720, HEIGHT = 480, FIELD = HEIGHT / 2, STRIDE = WIDTH * 2;
    static constexpr int BYTES = STRIDE * HEIGHT;
    enum class Layout
    {
        STACKED_TOP_FIRST,
        STACKED_BOTTOM_FIRST,
        WOVEN
    };
    struct Info
    {
        int64_t capturedUs = 0;
        uint32_t sequence = 0;
    };

    BirdseyeMailbox()
    {
        for (auto &slot : slots_)
            slot.pixels.resize(BYTES);
    }

    bool submit(const uint8_t *data, int width, int height, size_t stride,
                Layout layout, int64_t capturedUs, uint32_t sequence)
    {
        // Only the capture thread submits: back_ and lastSubmittedUs_ belong to it.
        std::unique_lock<std::mutex> producer(producer_, std::try_to_lock);
        if (!producer || stopped_.load() || !data || width != WIDTH || height != HEIGHT || stride < STRIDE || capturedUs <= 0 || capturedUs <= lastSubmittedUs_)
        {
            dropped_.fetch_add(1);
            return false;
        }
        Slot &slot = slots_[back_];
        for (int y = 0; y < HEIGHT; ++y)
        {
            // Even output rows are the top field, odd rows the bottom field; line y / 2 of it.
            // TB stacks the top field first, BT stacks it second.
            const bool topField = (y & 1) == 0;
            const bool topStackedFirst = layout != Layout::STACKED_BOTTOM_FIRST;
            const int row = layout == Layout::WOVEN ? y
                                                    : (topField == topStackedFirst ? 0 : FIELD) + y / 2;
            std::memcpy(slot.pixels.data() + y * STRIDE, data + row * stride, STRIDE);
        }
        slot.info = {capturedUs, sequence};
        {
            std::lock_guard<std::mutex> lock(exchange_);
            if (stopped_.load())
                return false;
            std::swap(back_, ready_);
            fresh_ = true;
            latestUs_.store(capturedUs);
        }
        lastSubmittedUs_ = capturedUs;
        return true;
    }

    bool copy(uint8_t *destination, size_t capacity, int64_t afterUs, Info &info)
    {
        // A single GL thread reads; a second concurrent reader is refused, not queued.
        std::unique_lock<std::mutex> reader(reader_, std::try_to_lock);
        if (!reader || !destination || capacity < BYTES)
            return false;
        Info current;
        {
            std::lock_guard<std::mutex> lock(exchange_);
            if (stopped_.load())
                return false;
            if (fresh_)
            {
                std::swap(ready_, reading_);
                fresh_ = false;
            }
            current = slots_[reading_].info;
        }
        if (current.capturedUs <= afterUs)
            return false;
        // reading_ is only ever exchanged by this (reader-locked) thread, and the producer
        // never writes the slot being read, so its pixels stay put during the copy.
        std::memcpy(destination, slots_[reading_].pixels.data(), BYTES);
        info = current;
        return !stopped_.load();
    }

    void stop()
    {
        std::lock_guard<std::mutex> lock(exchange_);
        stopped_.store(true);
        fresh_ = false;
        latestUs_.store(0);
    }
    int64_t capturedUs() const { return stopped_.load() ? 0 : latestUs_.load(); }
    int64_t dropped() const { return dropped_.load(); }

private:
    struct Slot
    {
        std::vector<uint8_t> pixels;
        Info info;
    };
    std::array<Slot, 3> slots_;
    std::mutex producer_, reader_, exchange_;
    int back_ = 0, ready_ = 1, reading_ = 2;
    bool fresh_ = false;
    int64_t lastSubmittedUs_ = 0;
    std::atomic<int64_t> latestUs_{0};
    std::atomic<bool> stopped_{false};
    std::atomic<int64_t> dropped_{0};
};
