#pragma once

#include <jni.h>

#include <string>

namespace camera_stream_manager {

bool attachPreview(JNIEnv* env, int videoIndex, jobject surface);
void detachPreview(int videoIndex);
void detachAllPreviews();

bool startRecording(JNIEnv* env, int slot, int videoIndex, const std::string& outputPath,
                    int width, int height, int fps, int bitrate);
void stopRecording(int slot);

} // namespace camera_stream_manager
