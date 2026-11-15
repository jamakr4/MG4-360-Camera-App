#pragma once

#include <mutex>

/** Tracks real device ownership, including opens that were already in flight when blocked. */
class CameraAccessGate {
public:
    struct Snapshot { int devices; int opening; bool blocked; };

    bool setOemBlocked(bool value) {
        std::lock_guard<std::mutex> lock(mutex_);
        const bool before = oem_ || external_;
        oem_ = value;
        return !before && (oem_ || external_);
    }
    void setExternalBlocked(bool value) {
        std::lock_guard<std::mutex> lock(mutex_);
        external_ = value;
    }
    bool blocked() const { return snapshot().blocked; }
    bool beginOpen() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (oem_ || external_) return false;
        ++opening_;
        return true;
    }
    void finishOpen() { std::lock_guard<std::mutex> lock(mutex_); --opening_; }
    void opened() { std::lock_guard<std::mutex> lock(mutex_); ++devices_; }
    void closed() { std::lock_guard<std::mutex> lock(mutex_); --devices_; }
    Snapshot snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return {devices_, opening_, oem_ || external_};
    }
private:
    mutable std::mutex mutex_;
    bool oem_ = false, external_ = false;
    int devices_ = 0, opening_ = 0;
};

class CameraOpening {
public:
    explicit CameraOpening(CameraAccessGate& gate) : gate_(gate), allowed_(gate.beginOpen()) {}
    ~CameraOpening() { if (allowed_) gate_.finishOpen(); }
    explicit operator bool() const { return allowed_; }
    CameraOpening(const CameraOpening&) = delete;
    CameraOpening& operator=(const CameraOpening&) = delete;
private:
    CameraAccessGate& gate_;
    bool allowed_;
};
