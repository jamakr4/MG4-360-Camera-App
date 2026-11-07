# Curb / side camera view (#92)

The left module reuses the side-camera crops from the local
`codex/main-activity-surround-test` branch. It uses only `/dev/video16` (left)
and `/dev/video14` (right), without OEM surround libraries, calibration or model
assets. The existing main camera preview remains alongside it.

The original close and settings buttons retain their layout, padding, icons and
colors. The new mode buttons follow the saved Settings accent immediately. The
selected button is filled; the other has a transparent fill and accent outline.
There is no gray header or control-bar background.
Mode names appear only on the bottom buttons; there is no title at the top.

## Geometry and ownership

The module scales uniformly from 540 × 640 physical pixels. The image region is
516 × 456, with an 8-pixel gap between the 254-pixel-wide columns. Wheel mode has
two 224-pixel-high rows; side mode has two 456-pixel-high portrait views. The front
of the vehicle is at the top, with no direction labels over camera images.

`SideCameraPanel` owns the mode preference and UI. `SideCameraView` owns the GL
lifecycle. Mode switching changes UV crops without changing camera consumers.
The preference key also reads the test branch's previously saved selection.

The stream manager gives each side an independent preview consumer (2100/2101).
Packed-frame snapshots are limited to 12.5 FPS; display snapshots use a try-lock
so they do not wait on capture/recording threads. Each side expires independently
after 500 ms. Failed feeds are retried every two seconds, while healthy feeds
remain attached. Closing/pausing the view and OEM handoff detach only these
consumers, leaving other stream consumers under their existing owners.

## Local validation (2026-11-07)

- Debug and minified Release builds passed.
- All 67 JVM tests passed, including the four imported crop/rotation tests.
- Android Lint passed without errors.
- The host C++ test passed for stacked-field UYVY to NV12 conversion, padded
  strides and invalid input sizes.
- Two instrumentation tests passed on the Android 13 Automotive arm64 emulator:
  GLES rotation/front-rear ordering and independent left/right/both feed loss;
  mode persistence across activity recreation, live accent updates, original
  icon dimensions/padding, uniform module scaling, and five pause/resume cycles.
- A separate cold process restart restored Wheels mode. The emulator's original
  Sides selection was restored afterward.
- Visual inspection confirmed the large original X and settings icon, accent
  controls on black, and separate missing-camera messages.
- Original `btnClose` and `btnSettings` XML elements were compared with the
  branch's starting revision and are identical.

Build and JVM/lint checks:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebugAndroidTest --offline
c++ -std=c++17 -Wall -Wextra -Werror app/src/test/cpp/side_camera_nv12_test.cpp \
  -o /tmp/mg4-side-camera-nv12-test
/tmp/mg4-side-camera-nv12-test
```

## Vehicle acceptance still required

1. Check all four wheel crops and both portrait views against live vehicle images.
2. Switch modes during loop recording and an event/test clip; verify uninterrupted
   recording and no camera device reopen caused by the switch.
3. Check OEM 360/reverse handoff and restoration, plus existing indicator/IPK
   camera priorities.
4. Reopen the app repeatedly and restart the vehicle; confirm the selected mode
   returns, healthy feeds remain independent, and camera resources are released.

The emulator has no MG4 camera hardware, so it cannot validate recording
throughput or actual vehicle camera ownership and alignment.
