#pragma once

#include <jni.h>
#include "side_camera_preview.h"

#include <string>
#include <cstdint>
#include <array>

namespace camera_stream_manager {

void setOemCameraAccessBlocked(bool blocked);
bool isOemCameraAccessBlocked();

void enableSideCameraPreviews();
void setSideCameraWheelsMode(bool wheels);
void setSideCameraWheelAlignment(bool frontEnabled, bool rearEnabled,
                                 float frontDegrees, float rearDegrees);
void setSideCameraHighlightMissingAreas(bool enabled);
bool attachSideCameraPreview(JNIEnv* env, int side, jobject surface,
                             const side_camera_preview::Crops& crops);
void detachSideCameraPreview(int side);
void detachSideCameraPreviews();
int64_t sideCameraPreviewAgeMs(int side);

bool attachPreview(JNIEnv* env, int videoIndex, jobject surface, bool enhanced = false);
void detachPreview(int videoIndex);
void detachAllPreviews();
// Independent preview outputs. Sink IDs do not address the legacy main preview.
bool attachPreviewSink(JNIEnv* env, int videoIndex, int sinkId, jobject surface);
void detachPreviewSink(int videoIndex, int sinkId);
int64_t previewSinkFrameAgeMs(int videoIndex, int sinkId);
// age ms (-1 until first post), posted frames, last capture-to-post ms, last post cost ms.
std::array<int64_t, 4> previewSinkStats(int videoIndex, int sinkId);

bool startRecording(JNIEnv* env, int slot, int videoIndex, const std::string& outputPath,
                    int width, int height, int fps, int bitrate);
bool stopRecording(int slot);
bool startCombinedRecording(JNIEnv* env, const std::string& outputPath,
                            int cellWidth, int cellHeight, int fps, int bitrate,
                            const std::string& signature, bool showSpeed, int cameraMask);
bool stopCombinedRecording(bool keepCamerasWarm);
/** keepCamerasWarm, plus urgent when the factory 360 view is waiting for the devices. */
bool stopCombinedRecording(bool keepCamerasWarm, bool urgent);
/** Ends a hold left over from a rotation that is not going to produce another clip. */
void releaseCombinedCameras();
/** Blocks until every clip has finished being pushed to the medium, or the timeout. */
bool awaitPendingFlushes(int timeoutMs);

/** Shows the composed grid live on a Surface - the same canvas that recording writes. */
bool attachCombinedPreview(JNIEnv* env, jobject surface, int cellWidth, int cellHeight,
                           int fps, const std::string& signature, bool showSpeed,
                           int cameraMask);
bool detachCombinedPreview();

/** The composed canvas size, so a preview can be given the right shape rather than a stretch. */
int previewCanvasWidth(int cellWidth, int cellHeight);
int previewCanvasHeight(int cellWidth, int cellHeight);
void updateCombinedRecordingSpeed(int speedKmh);

/**
 * What each camera device reported the last time it was opened: pixel format, size, stride and
 * field order.
 *
 * <p>There is no adb on this head unit, so anything the native side learns and only writes to
 * logcat is learned in private. This hands it back for the diagnostics report.
 */
std::string describeFormats();
int64_t writtenSamples();

} // namespace camera_stream_manager
