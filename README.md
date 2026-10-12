# Aria Downloader

Native Android download manager following [plan.md](plan.md). The current
bootstrap launches a Compose Downloads screen with an empty state. Downloading,
native aria2, persistence and background jobs are not implemented yet.

## Modules

- `:app`: Compose screens, themes and application wiring.
- `:downloads`: future persistence, queue, scheduling, notifications and export.
- `:aria2`: future native builds, JNI and engine interface.

Dependencies flow `:app → :downloads → :aria2`.

## Development

Use Android Studio compatible with AGP 9.4.1, Android SDK platform 37 and build
tools 37.0.0. The app requires Android 15 (API 35) or newer. Set your SDK location
in untracked `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

The Gradle wrapper pins Gradle 9.6.0; the existing daemon configuration requests
JDK 25. Use a modern JDK to launch Gradle; Java 11 cannot launch this wrapper.
Java/Kotlin bytecode targets JVM 17. Kotlin is provided by AGP rather than a
separate Kotlin Android plugin. Initial dependency/toolchain downloads require
network access.

The Compose compiler plugin stays at 2.2.10 to match AGP 9.4.1's built-in
Kotlin compiler; lint may suggest a newer version, but upgrade them together.

On Windows:

```powershell
.\gradlew.bat assembleDebug lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest
```

On macOS/Linux, use `./gradlew` with the same tasks. Device tests require a
connected API 35+ emulator/device. The launch smoke test checks the Downloads
heading and empty state. Check light/dark appearance, large text, and system-bar
insets on a device as well.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
Contributor verification rules are in [AGENTS.md](AGENTS.md).
