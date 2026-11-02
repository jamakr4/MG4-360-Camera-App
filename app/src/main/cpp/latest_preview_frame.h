#pragma once

#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <utility>

// Each display owns one mailbox. A slow display may lose frames, but must never
// hold up capture, recording, or another display's BufferQueue.
template <typename Frame>
class LatestPreviewFrame {
public:
    explicit LatestPreviewFrame(int64_t intervalUs) : intervalUs_(intervalUs) {}

    template <typename Copy>
    bool submit(int64_t nowUs, Copy copy) {
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (!lock || stopped_ || (hasSubmitted_ && nowUs - lastUs_ < intervalUs_)) return false;
        pending_ = copy();
        lastUs_ = nowUs;
        hasSubmitted_ = true;
        ready_ = true;
        changed_.notify_one();
        return true;
    }

    bool take(Frame &frame) {
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait(lock, [this] { return stopped_ || ready_; });
        if (stopped_) return false;
        frame = std::move(pending_);
        ready_ = false;
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopped_ = true;
        ready_ = false;
        pending_ = Frame{};
        changed_.notify_all();
    }

private:
    const int64_t intervalUs_;
    std::mutex mutex_;
    std::condition_variable changed_;
    Frame pending_{};
    int64_t lastUs_ = 0;
    bool hasSubmitted_ = false;
    bool ready_ = false;
    bool stopped_ = false;
};
