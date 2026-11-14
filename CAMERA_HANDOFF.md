# External camera handoff verification

Implemented on `107-fix-cam-conflict`. The OEM broadcast pause fix remains separate
from the new explicit external pause.

## Verified on 2026-11-13

- Android debug APK and Android instrumentation APK build successfully.
- 82 JVM unit tests pass, including eight handoff-state regression tests.
- Nine Automotive emulator handoff/OEM tests pass, plus one fresh-process startup
  test after an actual force-stop. The cold Resume callback reports `idle`.
- The standalone native gate/in-flight-open race test passes.
- Gradle's connected-test launcher lacks offline UTP dependencies on this host;
  the built test APK was executed directly with AndroidJUnitRunner instead.

## Automated checks

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
- Native gate/race test (host):
  `clang++ -std=c++17 -pthread app/src/test/cpp/camera_access_gate_test.cpp -o /tmp/camera-access-gate-test && /tmp/camera-access-gate-test`
- Android Automotive emulator: run `CameraHandoffInstrumentedTest` through
  AndroidJUnitRunner, including real manifest delivery and ordered-broadcast results.

To repeat the fresh-process check after installing the debug and test APKs:

```sh
adb shell am broadcast -n com.drivehub.kamera/.camera.CameraReleaseTriggerReceiver -a com.drivehub.kamera.action.RELEASE_CAMERAS
adb shell am force-stop com.drivehub.kamera
adb shell am instrument -w -r -e class com.drivehub.kamera.camera.CameraHandoffStartupInstrumentedTest com.drivehub.kamera.test/androidx.test.runner.AndroidJUnitRunner
adb shell am broadcast -n com.drivehub.kamera/.camera.CameraResumeTriggerReceiver -a com.drivehub.kamera.action.RESUME_CAMERAS
```

The startup test deliberately skips when no persisted external pause was prepared.

## Vehicle test with Rivela — pending

The emulator has no MG4 V4L2 camera hardware. Actual successful camera reclamation,
release during recording, and Rivela coexistence require this parked-vehicle test.

1. Enable dashcam recording and Digital Rearview. Open the main/side views and,
   where available, enable the IPK output. Request RELEASE_CAMERAS using the README
   helper. Expect success, status `released`, claimed=false and blocked=true.
2. Open Rivela only after this response. Leave it open for more than 60 seconds.
   Check that our app stays paused, produces no camera-open toast and does not
   reopen a camera when an OEM STOP broadcast is received.
3. Close Rivela's cameras, then request RESUME_CAMERAS. Expect success and `claimed`
   after the requested recording/preview outputs have started again. Check that
   all applicable views and recording work, and that settings were preserved.
4. Repeat with OEM coexistence disabled. The explicit handoff must still work.
5. While externally paused, activate OEM 360/reverse and send Resume. Expect
   `blocked`, success=false. OEM must retain the cameras until its own release.
6. Repeat Release and Resume, and rapidly alternate them. The final requested
   state must win. Old callbacks must not reopen cameras or report false release.
7. Force-stop/restart our app while externally paused. It must restore the pause
   before opening any cameras. Reboot the infotainment: the external pause must
   be cleared. Do not reboot or force-stop while recording important clips.
8. With recording and all views inactive, Resume must report `idle` and must not
   enable continuous recording. With an intentionally unavailable required output,
   expect `timeout` within five seconds and the actual claimed/blocked snapshot.

Record firmware, app versions, callback results, transitions and any relevant
CameraHandoff / CameraStreamManager runtime log entries when performing this test.
