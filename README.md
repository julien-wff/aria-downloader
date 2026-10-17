# Aria Downloader

<p align="center">
  <img src="docs/design/aria-icon.svg" alt="Aria Downloader icon" width="112" height="112">
</p>

<p align="center">
  A native Android download manager powered by the aria2 engine.
</p>

Aria brings reliable HTTP and HTTPS downloads to Android in a focused, modern interface. Add a link
or share one from another app, follow its progress, and find the published file in your device's
Downloads folder. Transfers can keep running when you leave the app, with Android notifications for
progress and quick controls.

## What it does

- Manage concurrent downloads in a persistent queue, with pause, resume, cancel and retry actions.
- Continue user-started transfers in the background and recover interrupted work.
- Review completed, failed and canceled transfers in History; open or share files after they have
  been saved.
- Share a link into the app for review before starting a transfer.
- Tune queue size, connection limits, speed limits, network preference and appearance.
- Use an adaptive Material 3 interface with light and dark themes.

Aria is an Android app with the download engine embedded in the application. It does not require a
companion server or a first-launch binary download. The current interface accepts individual HTTP
and HTTPS links.

## Project structure

| Module       | Responsibility                                                           |
|--------------|--------------------------------------------------------------------------|
| `:app`       | Compose interface, navigation and app wiring                             |
| `:downloads` | Queue, persistence, background scheduling, notifications and file export |
| `:aria2`     | aria2 engine interface, JNI bridge and native builds                     |

The dependency direction is `:app` → `:downloads` → `:aria2`.

## Build

Open the project in Android Studio or use the Gradle wrapper. The project targets Android 15 (API
35) and compiles against Android SDK 37. Building the app also builds its bundled native engine;
native builds need NDK `30.0.16248370`, CMake
`4.1.2`, and Python 3.12 or newer. Set the Android SDK path in an untracked
`local.properties` file or through `ANDROID_HOME`.

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See
the [native development guide](aria2/native/README.md) for host tools and native build details.

## Development

Run the standard build and lint checks with:

```sh
./gradlew assembleDebug lintDebug
```

Device tests require a connected Android 15 or newer device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

The project roadmap and milestone status are in [plan.md](plan.md) and
[PROGRESS.md](PROGRESS.md).
