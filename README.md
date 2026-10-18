# Aria Downloader

<p align="center">
  <img src="docs/design/aria-icon.svg" alt="Aria Downloader icon" width="112" height="112">
</p>

Aria is an Android download manager powered by aria2. Paste a link or share one from another app.
Follow the transfer, then open the saved files in Downloads.

The download engine ships inside the app. Transfers can continue while you use other apps or lock
the screen.

## Features

- Download HTTP links, torrents, magnets and Metalinks.
- Pause, resume and retry queued downloads.
- Choose torrent files before downloading their contents.
- Save files to your device's Downloads folder.
- Track transfer speeds and completed download pieces.
- Set speed limits and Wi-Fi preferences.
- Use light, dark or system appearance.

## Install

Aria requires Android 15 or newer. Download the APK from
[GitHub Releases](https://github.com/julien-wff/aria-downloader/releases/latest). Open it on your
device and allow installation from that source when Android asks.

Future updates install over the existing release. A debug build uses a different signing key, so
switching from debug to release requires uninstalling it first. Uninstalling removes unfinished
downloads and app settings.

## Developer setup

Native builds currently support Linux x86_64. Install JDK 25, Python 3.12 or newer, and the host
tools listed in the [native build guide](aria2/native/README.md#build).

Install Android SDK platform `platforms;android-37.0`, Build Tools `37.0.0`, NDK `30.0.16248370` and
CMake `4.1.2`. The app compiles and targets API 37, with Android 15 (API 35) as its minimum.

Clone the repository and open it in Android Studio, or use the Gradle wrapper. Set `ANDROID_HOME` to
your SDK directory, or set `sdk.dir` in an untracked
`local.properties` file.

```sh
git clone https://github.com/julien-wff/aria-downloader.git
cd aria-downloader
./gradlew assembleDebug lintDebug
```

The first build downloads and compiles the bundled native dependencies. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. On Windows, the Gradle wrapper is `gradlew.bat`; the
native build scripts require a supported Linux host.

## Documentation

Read the [product guide](docs/product.md) for app behavior and limitations.
The [architecture guide](docs/architecture.md) explains module ownership and the transfer lifecycle.
See [testing](docs/testing.md) for verification commands,
[release setup](docs/releases.md) for signing and publishing, and
[AGENTS.md](AGENTS.md) for contributor rules.
