<p align="center"><img src="app/src/main/res/drawable-xxxhdpi/ic_splash_logo.png" width="140"></p>
<h1 align="center"><b>Sounder</b></h1>
<h4 align="center">A modern, beautiful, and rational music player for Android.</h4>

<p align="center">
    <a href="https://github.com/nov0caina/Sounder/releases">
        <img alt="Latest Release" src="https://img.shields.io/github/v/release/nov0caina/Sounder?style=flat&color=7C3AED">
    </a>
    <a href="https://www.gnu.org/licenses/gpl-3.0">
        <img alt="License: GPL v3" src="https://img.shields.io/badge/license-GPL%20v3-E11D48.svg?style=flat">
    </a>
    <img alt="Minimum SDK Version" src="https://img.shields.io/badge/API-24%2B-EA580C?style=flat">
</p>

<p align="center">
    <a href="https://ko-fi.com/nov0caina">
        <img alt="Ko-fi" src="https://img.shields.io/badge/Support_on-Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white">
    </a>
    <a href="https://www.paypal.com/paypalme/nov0caina">
        <img alt="PayPal" src="https://img.shields.io/badge/Donate-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white">
    </a>
    <a href="https://github.com/sponsors/nov0caina">
        <img alt="GitHub Sponsors" src="https://img.shields.io/badge/Sponsor-GitHub-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white">
    </a>
</p>

---

## About

**Sounder** is an open-source, non-commercial music player for Android designed to combine the precision and purity of local audio management with dynamic online exploration. 

Sounder originated as a fork of [Auxio](https://github.com/OxygenCobalt/Auxio) by **Alexander Capehart (OxygenCobalt)**, licensed under the GNU General Public License v3.0 (GPLv3). While preserving Auxio's uncompromising local audio playback and metadata fidelity, Sounder extends the ecosystem with modernized UI surfaces, online music exploration, streaming, synchronized lyrics, and background offline caching.

## Features

- **Hybrid Audio Architecture**: Flawlessly stream online tracks and manage your local music library side-by-side.
- **Dynamic Online Exploration**: Discover trending artists, releases, and curated genre collections with zero tracking.
- **Synchronized Lyrics**: Real-time karaoke-style lyric scrolling with manual sync offsets.
- **Offline Audio Downloads**: Integrated background downloading powered by Android WorkManager.
- **Playback Engine**: Built on a patched [Media3 ExoPlayer](https://developer.android.com/guide/topics/media/exoplayer) with gapless playback, ReplayGain, and custom audio pitch/speed control.
- **Modern Material Design**: Ambient chromatic glows derived from album art, signature dark-violet theme, and fluid animations.
- **Advanced Metadata System**: Unified album artist resolution, disc number handling, and fast fuzzy-searching.
- **Android Auto & Widgets**: Responsive home screen widgets and automotive playback support.
- **100% Free & Open Source**: Zero proprietary trackers, zero ads, no closed dependencies.

## Support & Funding

If you enjoy using Sounder and want to support ongoing development, maintenance, and server-side metadata indexing:

- **Ko-fi**: [ko-fi.com/nov0caina](https://ko-fi.com/nov0caina)
- **PayPal**: [paypal.me/nov0caina](https://www.paypal.com/paypalme/nov0caina)
- **GitHub Sponsors**: [github.com/sponsors/nov0caina](https://github.com/sponsors/nov0caina)

## Permissions

- `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`: To index and play audio files stored on device.
- `MANAGE_EXTERNAL_STORAGE` *(Optional)*: To allow direct deletion and management of audio files on device.
- `INTERNET` & `ACCESS_NETWORK_STATE`: For online discovery, audio streaming, lyrics, and downloads.
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: To ensure uninterrupted background playback.
- `POST_NOTIFICATIONS`: To display the system media notification and download progress.

## Building

Sounder requires NDK support and submodules for media playback:

```bash
# Clone repository with submodules
git clone --recurse-submodules https://github.com/nov0caina/Sounder.git
cd Sounder

# Build debug APK
./gradlew assembleDebug
```

Requirements:
- Android SDK with NDK version `28.2.13676358`
- JDK 21 (`jdk21-openjdk`)
- `cmake` and `ninja-build`

## License & Attribution

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)

Sounder is Free Software licensed under the **GNU General Public License v3.0**.

Sounder is a derivative work of [Auxio](https://github.com/OxygenCobalt/Auxio), Copyright &copy; 2021-2024 Alexander Capehart (OxygenCobalt) and contributors.  
Sounder enhancements and modifications are Copyright &copy; 2026 Sounder Project (nov0caina).
