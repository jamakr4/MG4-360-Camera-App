# MG4-Camera-Mod

Community mod for the MG4 EV (AAOS 9, pre-2026 facelift) that improves the 360° turn signal camera behavior — removing the launcher overlay in favor of a Tesla-style tile view and raising the auto-close speed threshold.

# Features for v0.6 (release on May 13th)
- **Turn signal overlay** — automatic camera popup when indicator is activated
- **Full Access to camera system**
- **Language support for Englisch and German with auto select based on vehicles language**

> **Work in progress.** Check the [Wiki](https://github.com/jamakr4/MG4-360-Camera-App/wiki) for technical details and the [Project Board](https://github.com/users/jamakr4/projects/4) for current progress.

## Setup OEM SAIC 360Cam App

To avoid conflicts with the modified turn signal camera behavior in this app, it is recommended to disable the original turn signal camera popups inside the OEM SAIC 360 camera app.

If the OEM turn signal camera feature remains enabled, both systems may try to react to the same indicator event at the same time. This can lead to unexpected behavior, inconsistent switching, or the OEM app taking over when you want the custom camera flow from `MG4-360-Camera-App`.

![OEM SAIC 360 camera settings step 1](images/setting%231.jpg)

![OEM SAIC 360 camera settings step 2](images/setting%232.jpg)


## Build Setup

OpenCV is referenced from a local path, so Android builds can fail if `OpenCV_DIR` is not set correctly.

Before building:
- Download the OpenCV Android SDK from [opencv/opencv releases](https://github.com/opencv/opencv/releases)
- Use `opencv-android-sdk.zip` and not the Windows or macOS packages
- Update `OpenCV_DIR` in [app/src/main/cpp/CMakeLists.txt](/Users/jan/Projekts/MG4-360-Camera-App/app/src/main/cpp/CMakeLists.txt:1) so it matches your local OpenCV Android SDK path

## ⚠️ Disclaimer

**Use at your own risk.**

This project involves modifying system APKs on a production vehicle. The author(s) take **no responsibility** for any damage, malfunction, data loss, voided warranty, or any other consequences resulting from the use of these modifications. Modifying vehicle software may affect safety systems — always test in a safe environment.

This is an independent community project and is **not affiliated with SAIC, MG Motor, or any of their subsidiaries**.

## Credits

- Analysis based on community research from [XDA Forums — MG4 Electric AAOS 9](https://xdaforums.com/t/mg4-electric-aaos-9-playing-and-possibly-other-mg-models.4697712/)
- Tile View based on: [merth4n](https://xdaforums.com/m/merth4n.13350648/)
- OpenCV 4.9.0 — Apache License 2.0
- AndroidX AppCompat 1.7.1 — Apache License 2.0
- AndroidX Activity 1.12.4 — Apache License 2.0
- AndroidX ConstraintLayout 2.2.1 — Apache License 2.0
- Material Components for Android 1.13.0 — Apache License 2.0
- Icons based on [Google Material Symbols](https://developers.google.com/fonts/docs/material_symbols) — Apache License 2.0

## Support the Project

If you'd like to support this project financially — thank you for the thought! Unfortunately, due to German tax regulations, setting up donation platforms like Buy Me a Coffee creates more bureaucratic overhead than it's worth for a hobby project like this.

If you want to show your appreciation, the best ways to help are:
- **Star the repository** on GitHub
- **Contribute ideas**, feedback, or feature requests via Issues or in discussions
- **Share the project** with others who might find it useful

That means a lot and keeps the project going!

## License

GPL-3.0 — see [LICENSE](LICENSE) for details.
