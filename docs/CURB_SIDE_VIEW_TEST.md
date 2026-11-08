# Curb / side camera view (#92)

The left module reuses the side-camera crops from the local
`codex/main-activity-surround-test` branch. It uses only `/dev/video16` (left)
and `/dev/video14` (right). The main camera preview remains alongside it.

The close and settings buttons use smaller white icons within the left module.
The close hit area stays entirely above the camera images; Settings shares the
mode buttons' top/bottom edges and has a wide touch area. The mode buttons follow
the Settings accent immediately: the selected
button is filled; the other has a transparent fill and accent outline. There is
no gray header/control-bar background and no title at the top.

## Rendering after the first vehicle test

The first vehicle test reported heavy stuttering on the left, recurring SAIC side
navigation in Settings and while reversing/moving, and black left images when the
navigation bar appeared. The right camera remained smooth.

The original left path had an 80 ms snapshot/render timer (at most 12.5 FPS), its
own stacked-field UYVY-to-NV12 conversion, texture uploads and a GLSurfaceView.
That path has been removed. Side consumers now receive the same RGBA preview field
as the main camera, from the existing capture-loop conversion. Each side uses the
existing independent native-window preview worker and latest-frame mailbox.
There is no extra YUV conversion, GL context, texture upload or fixed display FPS
limit. Rotation/cropping runs on the preview worker, outside the capture thread.
The last selected mode uses the same persisted preference as the first test.

The module scales uniformly from 540 × 640 physical pixels. The camera area is
516 × 456, with an 8-pixel gap between the 254-pixel-wide columns. Wheel mode has
two 224-pixel-high rows per side; side mode has one 456-pixel-high portrait image.
Each side owns a fixed 254 × 456 SurfaceView buffer. Mode switches and size changes
reuse it. Crop coordinates are passed directly from `SideCameraGeometry` to native
code, including when RGBA input contains only the 720 × 240 main-preview field.

Consumer IDs 2100/2101 remain independent of the main preview and recording.
Each side reports freshness separately. Missing/stalled outputs retry every two
seconds without detaching the healthy side. Pause/close releases these side preview consumers independently.
OEM handoff now blocks all app camera outputs as described below.

## Navigation and resize handling

`AppSystemBars` now maintains each app/dialog window's immersive state after focus,
visibility and inset changes. Android 9 uses the legacy visibility callback and
stable layout flags. Newer Android versions use window-inset visibility. Reapply
requests are throttled and cannot be postponed indefinitely by repeated callbacks.
Dialogs inherit hidden navigation before taking input focus, then regain normal
keyboard/input focus. The top status bar still follows its existing setting.

MainActivity consumes status-bar/cutout insets, but not navigation-bar insets.
Transient SAIC navigation therefore overlays the camera region rather than
shrinking it. Surface size callbacks do not reattach or pre-warm side previews.

## Local validation (2026-11-07)

- Debug and minified Release builds passed; Android Lint reported no errors.
- All 67 JVM tests passed, including crop geometry and JNI crop ordering.
- Both native C++ executables passed on the Android 13 Automotive arm64 emulator:
  preview mailbox behavior, and the actual OpenCV side compositor with 720 × 240
  and 720 × 480 inputs, padded stride, front/rear and inner/outer orientation,
  wheel gaps, unchanged source frames and buffer reuse across mode switches.
- All three Android instrumentation tests passed: persisted modes, live accent,
  icon touch targets/header separation/control-row alignment, five pause/resume cycles, stable side surfaces
  under left/right navigation-inset injection and mode changes, dialog input focus,
  and automatic hiding after navigation is revealed without another focus change.
- The local emulator runs the new Debug APK. No vehicle installation was performed.

Build checks:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebugAndroidTest --offline
```

The standalone C++ test project is `app/src/test/cpp/CMakeLists.txt`. Configure it
with the Android NDK toolchain, `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-28`,
`ANDROID_STL=c++_static`, and `OpenCV_DIR` pointing to the Android SDK's native JNI
folder. Build and run `side_camera_preview_test` and `latest_preview_frame_test`
on the emulator/device with ADB.

## OEM ownership follow-up (2026-11-08)

OEM coexistence now controls a common native camera gate, including while
MainActivity or Settings is open. Reverse gear or an OEM start request closes it.
All app preview sessions are detached in parallel, preview-only combined output
and warm camera holds are released, and an active recorder is interrupted through
its existing urgent OEM-pause path. This also works with the dashcam disabled.

Main, side, overlay/IPK and combined preview attachments, and recorder starts,
are rejected while the native gate is closed. CameraSession also checks the gate
before attaching another consumer to an existing capture session. Activity/surface
recreation and automatic side retries cannot reclaim the devices. Main and side
views display an OEM pause message rather than presenting a frozen image as live.

An OEM stop during R does not reopen cameras. Return requires leaving R and a
confirmed OEM absence lasting at least the existing 250 ms settling interval.
The existing OEM lifecycle monitor now reads gear independently of lamp callbacks.
Without a running recorder it also recovers an abandoned/stale OEM request after
an 8-second startup grace and confirmed absence; unknown foreground stays blocked
until another reliable exit signal. A fresh request resets that grace interval.

Disabling OEM coexistence clears the gate immediately and restores normal app
preview behavior. Recording resume actions remain blocked during OEM ownership,
and repeated pause requests do not produce duplicate pause notifications.

Local validation: Debug/Release builds and Lint passed, all 69 JVM tests and all
six affected Android instrumentation tests passed. Coverage includes recovery
unit tests for stop signals/re-entry during R, and
Android integration checks for an open app with the recorder disabled, all public
preview/recorder entry points, a recreated Activity, bouncing start/stop requests,
coexistence toggles, and recovery without a STOP or new vehicle event. Physical
V4L2 device release timing and moving-reverse behavior still need a vehicle test.

## Vehicle verification

1. Compare Wheels/Sides motion against the right preview, both with and without
   loop recording. Check the unchanged front/rear crop placement.
2. Open/close Settings and its child dialogs repeatedly; verify no SAIC side-bar
   flash and no black left camera images.
3. Reverse while stationary, then moving, and change back to forward gear. Confirm
   OEM 360 opens without repeated camera takeovers, and app views return after
   leaving R and closing OEM 360. Repeat with the dashcam disabled and enabled.
4. Repeat app open/close and vehicle restart, including indicator/IPK camera use.

The emulator cannot reproduce the SAIC Android 9 vehicle policy or camera hardware.
Smooth live capture/recording and the moving-reverse scenario require another
vehicle test; the local checks do not establish those results.
