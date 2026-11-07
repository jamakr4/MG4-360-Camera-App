#include "side_camera_frames.h"
#include "side_camera_nv12.h"
#include "camera_stream_manager.h"

#include <GLES2/gl2.h>
#include <chrono>
#include <mutex>
#include <vector>

namespace side_camera_frames {
namespace {
std::mutex frameMutex;
std::array<cv::Mat, SIDE_COUNT> frames;
std::array<int64_t, SIDE_COUNT> times{};

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

void submit(int side, const cv::Mat& packed) {
    std::unique_lock<std::mutex> lock(frameMutex, std::try_to_lock);
    if (!lock) return; // Display work must never wait on a capture/recorder thread.
    const int64_t now = nowMs();
    if (now - times[side] < 80) return;
    frames[side] = packed.clone(); // Do not retain a V4L2 mmap buffer after QBUF.
    times[side] = now;
}

void clear(int side) {
    std::lock_guard<std::mutex> lock(frameMutex);
    frames[side].release();
    times[side] = 0;
}

void snapshot(std::array<cv::Mat, SIDE_COUNT>& packed,
              std::array<int64_t, SIDE_COUNT>& ages) {
    std::lock_guard<std::mutex> lock(frameMutex);
    const int64_t now = nowMs();
    for (int side = 0; side < SIDE_COUNT; ++side) {
        ages[side] = times[side] ? now - times[side] : -1;
        packed[side] = frames[side]; // Immutable, refcounted snapshots.
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_enableFrames(JNIEnv*, jclass) {
    camera_stream_manager::enableSideCameraFrames();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_attachFrames(JNIEnv*, jclass, jboolean retryStale) {
    return camera_stream_manager::attachSideCameraFrames(retryStale);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_detachFrames(JNIEnv*, jclass) {
    camera_stream_manager::detachSideCameraFrames();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_drivehub_kamera_side_SideCameraNative_updateTextures(JNIEnv*, jclass,
        jint leftY, jint leftUv, jint rightY, jint rightUv) {
    try {
        std::array<cv::Mat, side_camera_frames::SIDE_COUNT> frames;
        std::array<int64_t, side_camera_frames::SIDE_COUNT> ages;
        side_camera_frames::snapshot(frames, ages);
        thread_local std::array<std::vector<uint8_t>, 2> buffers;
        const GLuint textures[]{static_cast<GLuint>(leftY), static_cast<GLuint>(leftUv),
                static_cast<GLuint>(rightY), static_cast<GLuint>(rightUv)};
        int mask = 0;
        for (int side = 0; side < 2; ++side) {
            const auto& frame = frames[side];
            if (ages[side] < 0 || ages[side] > side_camera_frames::MAX_AGE_MS
                    || frame.cols != 720 || frame.rows != 480 || frame.type() != CV_8UC2) continue;
            auto& buffer = buffers[side];
            buffer.resize(720 * 480 * 3 / 2);
            if (!side_camera_frames::stackedUyvyToNv12(frame.data, frame.step[0], 720, 480,
                    buffer.data(), buffer.size())) continue;
            for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; ++i) {}
            glActiveTexture(GL_TEXTURE0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glBindTexture(GL_TEXTURE_2D, textures[side * 2]);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 720, 480, GL_LUMINANCE,
                    GL_UNSIGNED_BYTE, buffer.data());
            glBindTexture(GL_TEXTURE_2D, textures[side * 2 + 1]);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 360, 240, GL_LUMINANCE_ALPHA,
                    GL_UNSIGNED_BYTE, buffer.data() + 720 * 480);
            if (glGetError() == GL_NO_ERROR) mask |= 1 << side;
        }
        return mask;
    } catch (...) {
        return 0;
    }
}
