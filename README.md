# MG4-Camera-Mod

Community mod for the MG4 EV (AAOS 9, pre-2026 facelift) that improves the 360° turn signal camera behavior — removing the launcher overlay in favor of a Tesla-style tile view and eliminating the auto-close speed threshold.

# Features for v0.8.4 
- **Turn Signal Overlay** — automatic camera popup when indicator is activated (no matter the speed of the vehicle)
- **Dashcam Recording** — uses all four native cameras, merged into a single 720×240 grid clip
  - Rolling 30 s segments with a configurable ring buffer (default 10 clips)
  - Event capture saves a window of pre/post segments to a separate `events/` folder, with a configurable cap (default 5 events)
  - Optional speed and signature burned into the video
  - Pause/resume/error banners with per-group size and volume controls
  - **OEM 360° AVM coexistence** — the dashcam briefly yields the cameras so the factory reverse/360° view can still open. Can be disabled in settings if you'd rather keep recording uninterrupted.
- **External Trigger** — broadcast `com.drivehub.kamera.action.TRIGGER_DASHCAM_EVENT` from any app or ADB to save an event clip. Companion app: [MG4-Dashcam-Trigger](https://github.com/jamakr4/MG4-Dashcam-Trigger).
- **Full Access to Camera System**
- **Language support for English and German with auto select based on the vehicle's language**

> **Work in progress.** Check the [Wiki](https://github.com/jamakr4/MG4-360-Camera-App/wiki) for technical details and the [Project Board](https://github.com/users/jamakr4/projects/4) for current progress.


![MG4 360 Camera App](images/PXL_20260603_192645923.jpg)

[![Dashcam Demo Video](https://img.youtube.com/vi/Rzb_Owc_RT0/maxresdefault.jpg)](https://youtu.be/Rzb_Owc_RT0)
> Click video for a YouTube redirect 

## ⚠️ Disclaimer

**Use at your own risk.**

This project involves modifying system APKs on a production vehicle. The author(s) take **no responsibility** for any damage, malfunction, data loss, voided warranty, or any other consequences resulting from the use of these modifications. Modifying vehicle software may affect safety systems — always test in a safe environment.

This is an independent community project and is **not affiliated with SAIC, MG Motor, or any of their subsidiaries**.

## Dev Mode

A hidden Dev tab exposes low-level knobs. To unlock it, open Settings and tap the version label at the bottom **5 times**

What's behind it:
- **Polling rates** — default poll, turn-signal poll, Android Auto / CarPlay foreground poll (ms).
- **Overlay safe area** — top inset (px) that the tile overlay cannot be dragged into.
- **Dashcam capacity** — number of rolling clips kept in the ring buffer, and number of saved events retained before the oldest is deleted.
- **Storage override** — pick a custom folder for dashcam recordings instead of `Downloads/dashcam/`.
- **Reset defaults** — restores every dev value to its built-in default.

> [!CAUTION]
> **Dev Mode can break things. Use at your own risk.**
>
> These values are **not** validated against the realities of your install — storage size, system load, camera bandwidth, signal latency. Raising the ring buffer or event cap can fill storage in minutes; cranking polling rates down can lock the UI or miss turn-signal events; combinations may have effects nobody has ever tested. The app **does not check free space** before writing.
>
> If something starts misbehaving after a Dev tweak, hit **Reset defaults** first. If that doesn't help, clear the app's data.

## Build Setup

OpenCV is referenced from a local path, so Android builds can fail if `OpenCV_DIR` is not set correctly.

Before building:
- Download the OpenCV Android SDK from [opencv/opencv releases](https://github.com/opencv/opencv/releases)
- Use `opencv-android-sdk.zip` and not the Windows or macOS packages
- Update `OpenCV_DIR` in [app/src/main/cpp/CMakeLists.txt](/Users/jan/Projekts/MG4-360-Camera-App/app/src/main/cpp/CMakeLists.txt:1) so it matches your local OpenCV Android SDK path

The notification sound file is licensed under CC-BY-NC-4.0 and therefore not tracked in this repository. To build, download it manually:
- Get the source from [Freesound — notification-sound-7062 by HenryCena82595](https://freesound.org/s/731783/)
- Convert to `.ogg` (Vorbis) if Freesound serves another format — the app loads the resource via `R.raw.notification_sound_7062_henrycena82595` and expects an `.ogg` extension
- Place it at `app/src/main/res/raw/notification_sound_7062_henrycena82595.ogg`

## Credits

- Analysis based on community research from [XDA Forums — MG4 Electric AAOS 9](https://xdaforums.com/t/mg4-electric-aaos-9-playing-and-possibly-other-mg-models.4697712/)
- Tile View based on: [merth4n](https://xdaforums.com/m/merth4n.13350648/)
- OpenCV 4.9.0 — Apache License 2.0
- AndroidX AppCompat 1.7.1 — Apache License 2.0
- AndroidX Activity 1.12.4 — Apache License 2.0
- AndroidX ConstraintLayout 2.2.1 — Apache License 2.0
- Material Components for Android 1.13.0 — Apache License 2.0
- Icons based on [Google Material Symbols](https://developers.google.com/fonts/docs/material_symbols) — Apache License 2.0
- `notification-sound-7062` by `HenryCena82595` — [Freesound](https://freesound.org/s/731783/) — License: `Attribution NonCommercial 4.0`

## Support the Project

If you'd like to support this project financially — thank you for the thought! Unfortunately, due to German tax regulations, setting up donation platforms like Buy Me a Coffee creates more bureaucratic overhead than it's worth for a hobby project like this.

If you want to show your appreciation, the best ways to help are:
- **Star the repository** on GitHub
- **Contribute ideas**, feedback, or feature requests via Issues or in discussions
- **Share the project** with others who might find it useful

That means a lot and keeps the project going!

## License

GPL-3.0 — see [LICENSE](LICENSE) for details.
