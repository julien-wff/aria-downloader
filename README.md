# Aria Downloader

Native Android download manager following [plan.md](plan.md). Milestone 2 connects
the embedded engine to a single HTTP/HTTPS download, started from a minimal
Compose screen and run by a user-initiated data transfer (UIDT) job. Completed files
remain in **private app staging**, not the public Downloads folder. Room,
process-death recovery and public export belong to milestone 3.

## Modules

- `:app`: Compose controls, ViewModel, themes and application wiring.
- `:downloads`: single-task controller, durable intent, UIDT scheduling and notifications;
  future Room, queue policy and export.
- `:aria2`: native builds, JNI and engine interface.

Dependencies flow `:app → :downloads → :aria2`.

## Development

Use Android Studio compatible with AGP 9.4.1, Android SDK platform 37 and build
tools 37.0.0. The app requires Android 15 (API 35) or newer. Set your SDK location
in untracked `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

The Gradle wrapper pins Gradle 9.8.0; the existing daemon configuration requests
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
connected API 35+ emulator/device. The launch smoke test checks the download
controls. Check light/dark appearance, large text, and system-bar
insets on a device as well.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
Contributor verification rules are in [AGENTS.md](AGENTS.md).

Native builds additionally require NDK `30.0.16248370`, SDK CMake `4.1.2`,
Python 3.12+ and host build tools. See the [native development guide](aria2/native/README.md)
for NixOS setup, pinned sources, engine behavior and device acceptance commands.

## Background vertical slice (milestone 2)

Enter an absolute HTTP/HTTPS URL and a safe single filename, then choose Start.
The first Start requests notification permission; denial still allows the UIDT
job, with controls available in the app. Only one unfinished task is supported.
Start and Resume must happen while the app is visible. Notification Resume opens
the app for an explicit Resume action instead of scheduling from the background.

`:downloads` owns the process-wide controller and native engine. Only an active
JobService execution authorizes native transfer work. Job callbacks and user
commands are serialized; each execution has a separate lease so a stale stop
callback cannot stop a newer run. System interruptions stop the engine and allow
JobScheduler to retry while the same process retains its task mapping. A pause or
cancel saves intent before stopping. A job network-change callback checkpoints
the native session to refresh Android DNS. Native periodic checkpoints continue
as before.

A single atomic JSON intent record lives in `noBackupFilesDir`, alongside native
staging. This is not a recovery database: after process loss, an unfinished task
shows Interrupted and cannot resume. Cancel it before starting a new task. Old
partial files are preserved; cleanup, Room reconciliation, export, and reboot
recovery remain later work. Force-stop and Android Task Manager Stop are respected.
Staged files are removed on uninstall.

Notifications use the system `Notification.ProgressStyle` on API 36+ and standard
progress on API 35, without custom layouts or a promoted Live Update requirement.
Known sizes have continuous progress; unknown sizes are indeterminate. Active
updates are silent, coalesced to approximately once per second, and show size,
speed and ETA when available. Private lock-screen visibility hides filenames.
Completion explicitly reports private staging and offers no public Open/Share.

```sh
./gradlew assembleDebug lintDebug :downloads:testDebugUnitTest :aria2:testDebugUnitTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.julien_wff.aria_android.aria2.NativeEngineTest#fullLargeFileHash
```

The app background test uses a controlled throttled loopback server, leaves the
Activity, locks the screen, invokes notification Pause, resumes through visible
app authorization, verifies SHA-256, and cancels a second transfer from its
notification. A test-only lifecycle rule lets the test Activity return above a
secure keyguard without changing device security or production window flags.
Notification tests cover unknown/large sizes, unavailable ETA, terminal states,
immutable controls, and optional updates with a simulated denied-permission query.
The runner grants manifest permissions automatically; the real permission-dialog
denial flow still needs a manual check. The explicit method exclusion above keeps
the opt-in 4 GiB test out of the default suite; run it separately as documented in
the native guide.

Validated on 2026-10-17 with a Samsung SM-S948B running Android 17 (API 37):
build and lint passed, all 18 JVM tests passed, and all 13 default device tests
passed (2 app, 5 storage/notifications, 6 native). After a wireless disconnect,
the app and storage/notification tests were rerun successfully with:

```sh
./gradlew assembleDebug lintDebug :downloads:testDebugUnitTest \
  :app:connectedDebugAndroidTest :downloads:connectedDebugAndroidTest
```

The opt-in full 4 GiB transfer was not run. There is no API 35 emulator installed
in the verification environment, so Android 15 fallback rendering remains
unverified, as do manual appearance, large-text, and real permission-denial checks.

Run on API 35 and API 36+ to exercise both notification branches. Visually inspect
collapsed, expanded and lock-screen notifications on each; automated template
assertions do not replace those appearance checks. Native HTTPS/TLS acceptance
remains in the `:aria2` suite.
