# Aria Downloader

Native Android download manager following [plan.md](plan.md). Milestone 4.5 refreshes
the Material 3 Expressive interface, launcher icon, navigation and autosaving settings.
Milestone 4 added
Downloads, History, details, Settings and reviewed share intake around a concurrent
HTTP/HTTPS queue running under a user-initiated data transfer (UIDT) job. Files first download into
private staging; **Completed means successfully published to Downloads**.

## Modules

- `:app`: Compose controls, ViewModel, themes and application wiring.
- `:downloads`: queue controller, Room persistence, UIDT scheduling, notifications
  and restartable Downloads export.
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

## Product UI and queue (milestones 4 and 4.5)

Downloads shows unfinished tasks with search, status filters, progress, speed and ETA.
History retains completed, failed and canceled tasks. Select a row for details;
wide windows show a navigation rail and the detail beside the list. The interface
uses system dynamic colors and supports system, light and dark appearance, including
matching system-bar contrast. Secondary screens use native back-arrow app bars.
The Add action uses a floating button on phones, a rail button on larger windows,
and an app-bar action in short landscape windows. Forms have a comfortable maximum
width on tablets. Bottom navigation and the Add action hide with the keyboard visible;
forms scroll above the IME and provide Next, Done and Search actions.

The original [Aria SVG mark](docs/design/aria-icon.svg) supplies the adaptive launcher
and themed monochrome icon. Rounded Material Symbols are bundled locally; source
revision and licensing are recorded in [icon notes](docs/design/icons.md).

Choose **Add download**, enter one absolute HTTP/HTTPS URL and a safe single
filename, then Start. Tapping Add checks the clipboard once and prefills the first HTTP/HTTPS
link and its decoded filename when available. Shared links and open drafts take
precedence; clipboard intake never starts a download automatically. Invalid input
is shown inline before any notification-permission prompt.
Sharing plain text to Aria opens the same review screen;
receiving an intent never starts a transfer automatically. The first Start requests
notification permission. Denial leaves app controls available and does not prevent UIDT.

The FIFO queue runs three downloads by default, configurable from one to six.
Paused tasks release their slots. Completed network work releases its slot while
publication runs through a separate, serialized export worker. Export does not
hold the coordinator lock, so other transfers remain controllable while saving.
Task actions include pause, visible resume, cancellation with confirmation and
Retry saving. Retry of failed/canceled transfers creates a new identity and staging
folder; Retry saving retains its original destination and identity.

Settings autosave quietly through DataStore, with bounded count controls and grouped
Transfers, Network and Appearance sections. Discrete changes save immediately;
valid speed edits save after 400 ms or on Done, focus loss or leaving Settings.
Invalid speed drafts keep the last valid value and show inline guidance. A single
ViewModel-owned writer preserves rapid changes across navigation/rotation and offers
Retry after save/application failure. No Save button is required.

Connections per server default to four (1–16);
the per-download speed default is unlimited (zero bytes/s). Connection/speed
changes apply to new tasks. Concurrency and Wi-Fi-only changes reconcile the current
queue when the setting changes. Wi-Fi-only requires both Wi-Fi transport
and an unmetered network; metered Wi-Fi does not satisfy it.

History offers Open and Share only after successful publication. Both grant temporary
read access. Missing files and unavailable apps produce an error. Delete removes
the record and private staging after confirmation; an unchecked option also deletes
the public file. Android consent is requested when necessary. Declining consent or
failing file deletion keeps the record. Deleting an unfinished task first persists
cancellation and stops its native work.

`:downloads` owns the process-wide queue and native engine. A single UIDT job
authorizes a durable batch of tasks. Visible Add/Resume and queue-setting changes
atomically persist replacement membership and a fresh token, checkpoint/stop old
work, then schedule the replacement job. Queue advancement stays inside that batch;
it never schedules a new job from the background. Stale job callbacks and notification
tokens cannot control a replacement. A task pause/cancel leaves unrelated tasks running.
System interruption revokes the whole execution, checkpoints its native work and lets
JobScheduler retry the existing batch. Network changes checkpoint to refresh Android DNS.
No dataSync foreground service or unattended scheduling is introduced.

## Persistence and public Downloads export

Room stores task identity, desired/observed state, 64-bit progress and export URI
in `noBackupFilesDir/transfers.db`. It retains all task records for queue and history.
Important transitions persist immediately; progress writes are throttled to three
seconds. Schema v2 migrates v1 without deleting rows, retaining native mappings, intent,
64-bit progress and export destinations while adding transfer defaults and batch settings.
Schemas are checked into `downloads/schemas`; Room, KSP and DataStore versions are pinned
in the version catalog.

The engine reuses each persisted task ID and its `aria2/<id>` staging directory.
After process death, an existing authorized UIDT job can restore its eligible batch;
otherwise the screen offers Resume. Loading the app never schedules a new job.
Paused/canceled intent, force-stop and Task Manager Stop remain respected.
Legacy `transfer.json` records import once, but have no reliable staging mapping:
the UI preserves them as interrupted and never guesses which old directory to use.

Native completion transitions through Ready to save and Saving to Downloads.
Export creates an app-owned pending MediaStore entry, records its URI before
copying, then publishes only after the full copy closes successfully. Completed files
appear directly in `Downloads/`. MediaStore gives colliding names unique filenames
without replacing existing files. Pending entries temporarily use an app/task
namespace so they can be found if the process dies between insertion and recording
their URI. Publication moves the entry to Downloads and clears its pending flag;
its URI is already durable before this step. Previously published files retain their
existing location. A published entry is never recopied on retry. Automatic staging cleanup follows successful publication and its durable Room
record; interrupted cleanup is retried on a later launch.

Export temporarily requires space for both staging and the public copy. Disk-full
or interrupted exports preserve staging and offer **Retry saving**. A visible
Retry saving action performs local export without starting the network engine or
scheduling a UIDT job; if its process is interrupted, retry on the next launch.
Canceled partials remain preserved until explicit deletion. Unlocatable legacy
files are never guessed or removed. Staging is
removed on uninstall; successfully published Downloads files remain.

Notifications use the system `Notification.ProgressStyle` on API 36+ and standard
progress on API 35, without custom layouts or a promoted Live Update requirement.
Known sizes have continuous progress; unknown sizes are indeterminate. Active
updates are silent, coalesced to approximately once per second, and show size,
speed and ETA when available. Private lock-screen visibility hides filenames.
Saving uses progress notifications; failures open the app for Retry saving.
Notifications are grouped by task with stable identities and an active summary.
Pause/Cancel controls target only their task and authorization token. Resume/Retry
open the task for a visible action. Completion reports Saved to Downloads with
Open/Share actions.

```sh
./gradlew assembleDebug lintDebug :downloads:testDebugUnitTest :aria2:testDebugUnitTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.julien_wff.aria_android.aria2.NativeEngineTest#fullLargeFileHash
```

The app background test uses a controlled throttled loopback server, leaves the
Activity, locks the screen, invokes notification Pause, resumes through visible
app authorization, verifies the public file’s SHA-256 and staging cleanup, and
cancels a second transfer from its notification. A test-only lifecycle rule lets the test Activity return above a
secure keyguard without changing device security or production window flags.
Notification tests cover unknown/large sizes, unavailable ETA, terminal states,
immutable controls, and optional updates with a simulated denied-permission query.
Room/MediaStore device tests cover reopening the database, legacy import, pending
entry discovery, publication interruption and name collisions. JVM tests also
simulate disk-full and revoked execution while preserving recoverable data.
The runner grants manifest permissions automatically; the real permission-dialog
denial flow still needs a manual check. The explicit method exclusion above keeps
the opt-in 4 GiB test out of the default suite; run it separately as documented in
the native guide.

For an abrupt process-kill recovery check, first build the app/test APKs and run:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb"
```

Pass `--serial <device>` when necessary. The script installs the debug APKs,
serves a controlled range-enabled file on the host through `adb reverse`, starts
two simultaneous transfers, kills the live app with `run-as ... kill -9`, and launches
a separate instrumentation phase to resume and hash both published files. The two
phase tests are excluded from ordinary suites by the HostRecovery annotation.
This intentionally interrupts the app's current queue; use a development device. No dataSync foreground service or
unattended scheduling is introduced.

The following verification results describe **milestone 2 before these changes**.
Current milestone results are recorded in PROGRESS.md.

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
