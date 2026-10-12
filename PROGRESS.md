# Development progress

## 2026-10-12 - Project bootstrap

Status: foundation implemented and build verified. Device verification remains
pending. Milestone 1 (native feasibility) has not started.

### Completed

- Preserved the application ID, minimum SDK 35, SDK 37 configuration, Gradle
  wrapper and AGP version.
- Enabled Jetpack Compose and Material 3 with pinned dependencies and a Compose
  compiler matching AGP's built-in Kotlin. Configured JVM 17 bytecode.
- Added the launcher Activity and an edge-to-edge Downloads empty state with
  dynamic colors and system light/dark themes.
- Added Android library modules with dependencies `:app -> :downloads -> :aria2`.
  The libraries currently establish boundaries; they contain no download logic.
- Removed unused template themes, colors, dependencies and example tests.
- Added a Compose launch smoke test, README setup instructions, module build
  ignore rules, and root AGENTS.md requiring verification before completion.

### Verification

- `.\gradlew.bat assembleDebug lintDebug :app:assembleDebugAndroidTest` passed.
  The application APK, both library modules and instrumentation test APK compiled.
- `.\gradlew.bat lintDebug --rerun-tasks` passed with zero errors and one version
  warning. Compose compiler 2.2.10 remains pinned to match AGP 9.4.1's built-in
  Kotlin compiler.
- `.\gradlew.bat :app:connectedDebugAndroidTest` could not run: Gradle expects
  `D:\android-sdk\platform-tools\adb.exe`, which is missing. Platform tools are
  present under `platform-tools.backup`; invoking that ADB reported no devices.
- Device smoke-test execution and visual checks for light/dark appearance,
  large text and system-bar insets remain unverified.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Verification used a JDK 17 launcher; the existing Gradle daemon used JDK 25.

### Next step - Milestone 1: native feasibility

Build and validate the embedded aria2 engine in `:aria2` before implementing
background execution or expanding the product UI:

1. Pin aria2, native dependencies and the NDK; script reproducible shared-library
   builds for `arm64-v8a` and `x86_64`, with 4 KB and 16 KB page compatibility.
2. Establish TLS dependencies and an explicit CA trust strategy, keeping
   certificate verification enabled and recording source/license information.
3. Add the small Kotlin engine interface described in plan.md and a JNI bridge.
   Keep native session ownership and command execution on one engine thread.
4. Validate an HTTPS download, pause/resume, large-file handling and TLS rejection
   using controlled fixtures and content hashes. Check the packaged APK on a
   16 KB emulator/device.

Restore SDK platform tools and connect an API 35+ device/emulator before device
acceptance checks. This environment issue also blocks the bootstrap smoke test.

The subsequent milestone is a single download running through a user-initiated
data transfer job with a progress notification. Persistence and Downloads export
follow that slice, as specified in plan.md.
