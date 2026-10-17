# Development progress

## 2026-10-17 - Core product UI and concurrent queue

Status: milestone 4 implemented and verified on API 37, including two-transfer
process-death recovery and public-file hashes.
The user explicitly authorized advancing to milestone 4 after milestone 3 verification.

### Implemented

- Added Downloads, History, Settings, reviewed single-URL add/share intake and task
  details, with phone bottom navigation and a wide-window rail/list/detail layout.
  Lists support filename search and status filters. Tasks expose progress, speed,
  ETA, pause/resume, confirmed cancel/delete, Retry and Retry saving.
- Replaced newest-only coordination with a FIFO queue and task-ID commands, retaining
  the single-task compatibility API for existing tests. Concurrency is configurable
  from one to six (default three); native session configuration matches admission.
- Added non-destructive Room v1→v2 migration, all-row loading, atomic batch membership,
  durable connection/speed defaults and sanitized errors. Visible actions replace
  UIDT batches with fresh tokens; startup never schedules work. Pausing one task
  releases its slot without stopping unrelated tasks. Completion racing replacement
  is reconciled before native resume.
- Moved restartable MediaStore export outside the coordinator lock into a serialized
  worker. Network completion releases its queue slot while export continues. Saving
  failures preserve staging and destination identity; only publication is Completed.
- Added pinned DataStore preferences for concurrency, connections, per-download speed,
  unmetered Wi-Fi policy and system/light/dark appearance. Default option and appearance
  changes do not interrupt current transfers. Network/concurrency changes reconcile
  the queue under visible authorization.
- Added grouped task notifications with immutable task/token controls, a UIDT summary,
  and completed-file Open/Share actions. Detached summaries preserve paused/result
  child notifications. Historical results are not reposted wholesale on startup.
- Added MIME-aware Open/Share with temporary grants, and confirmed deletion with an
  optional public-file checkbox. Declined consent or file deletion failure preserves
  the record; explicit deletion cleans private staging after stopping native work.
- Extended JVM/device coverage and the opt-in host recovery harness to kill two live
  transfers, verify both non-zero range resumes, and hash both distinct public files.

### Verification status

- Build, lint, all 45 JVM tests (39 downloads, 6 native) and all 17 app/storage
  device tests (5 app, 12 downloads) passed on Samsung SM-S948B, Android 17/API 37:

  ```sh
  ./gradlew assembleDebug lintDebug :downloads:testDebugUnitTest :aria2:testDebugUnitTest \
    :app:connectedDebugAndroidTest :downloads:connectedDebugAndroidTest
  ```

- The six native device tests also passed during the earlier combined run with
  `NativeEngineTest#fullLargeFileHash` excluded. Across these runs, all 23 default
  device tests passed. The AGP result listener printed a nonfatal reporting warning
  during the successful combined run; the device XML records zero failures/errors.
- The two-transfer SIGKILL harness passed against the verified engine/coordinator:

  ```sh
  python3 tools/test-process-recovery.py --adb /home/julien/Android/Sdk/platform-tools/adb
  ```

  Both transfers had live native checkpoints when the host killed the process.
  Recovery reused both identities, issued non-zero range requests for each task,
  published two distinct files and verified both SHA-256 hashes. Recovery completed
  in 48 seconds. The seed runner's process-crash output is intentional. Server and
  ADB reverse mapping cleanup completed.
- A phone screenshot exposed a fixed-header problem in short landscape windows.
  Navigation now uses a rail from 600 dp, list/detail from 840 dp, and the list header
  scrolls with the task rows. The final UI adjustment passed build, lint and all five
  app device tests:

  ```sh
  ./gradlew assembleDebug lintDebug :app:connectedDebugAndroidTest
  ```
- API 35 fallback rendering, full tablet/large-font/TalkBack review, actual permission
  denial and public-file deletion consent dialogs remain manual checks. The 16 KiB
  runtime and opt-in 4 GiB checks were not rerun. Real disk exhaustion remains
  simulated by fault-injection tests rather than induced on the user's phone.

## 2026-10-17 - Persistence and public Downloads export

Status: milestone 3 implemented and verified on API 37, including abrupt
process-death recovery and public-file content hashes.
The user authorized advancing past outstanding milestone 2 acceptance gates.
That supersedes the historical advancement restrictions below, without marking
those outstanding checks as passed.

### Implemented

- Added Room schema v1 in private no-backup storage, with pinned Room/KSP
  dependencies, persisted desired/observed state, stable native mapping, 64-bit
  progress and export URI. Earlier task rows remain stored; the single-task UI
  displays the newest. Legacy JSON imports once without inventing a staging map.
- Reconstructs a native task in its original staging directory under an existing
  UIDT lease, or exposes explicit Resume. Startup never schedules a new job.
  Paused/canceled tasks stay stopped; stale notification tokens cannot control
  newer authorizations.
- Added restartable pending MediaStore export into
  `Downloads/Aria/<task UUID>/<filename>`. Pending entries are discoverable across
  the insert/Room crash window; published entries are never recopied. Completion
  and staging cleanup follow successful publication and its durable Room record.
- Added Saving/Retry saving UI and notifications. Failed exports preserve staged
  data. Visible local retries do not start a network engine or schedule a UIDT job.
- Added JVM fault-injection tests, Room/MediaStore device coverage and an opt-in
  host-driven process-kill harness. Updated the background flow to hash the public
  file and check staging removal.

### Verification status

- The following command passed on a Samsung SM-S948B running Android 17 (API 37):

  ```sh
  ./gradlew assembleDebug lintDebug :downloads:testDebugUnitTest :aria2:testDebugUnitTest \
    connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.julien_wff.aria_android.aria2.NativeEngineTest#fullLargeFileHash
  ```

- All 35 JVM tests and all 16 default device tests passed (2 app,
  8 persistence/export/notifications, 6 native). Host-only recovery phases are
  excluded from the default suite using the HostRecovery annotation.
- The abrupt process-kill harness passed against the final APKs:

  ```sh
  python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb"
  ```

  The seed process was killed with SIGKILL while downloading; its instrumentation
  intentionally reported process death. A new process recovered the task using
  range requests and verified the public file's SHA-256. The recovery phase passed
  in 47 seconds. The host server and ADB reverse mapping were cleaned up.
- Real filesystem disk exhaustion is simulated in JVM tests, not induced on the
  user's device. API 35 rendering, 16 KiB runtime acceptance, the opt-in 4 GiB
  transfer and existing manual appearance/permission-denial checks remain outside
  this verification run.

## 2026-10-17 - Embedded native engine

Status: milestone 1 implementation and 4 KiB device verification complete;
16 KiB runtime acceptance remains pending.
The application UI remains the bootstrap empty state. Background execution,
Room recovery and public Downloads export are not implemented.

### Implemented

- Added checksum-verified native release builds for arm64-v8a and x86_64, using
  pinned NDK/CMake versions and bundled OpenSSL, c-ares, Expat and zlib.
- Enabled HTTP/HTTPS, compression, asynchronous DNS, BitTorrent and Metalink in
  libaria2. The Kotlin API currently accepts HTTP/HTTPS only, with automatic
  torrent/Metalink following and RPC listeners disabled.
- Added the Kotlin engine contract, single-thread command owner, immutable
  progress snapshots, JNI exception translation, private staging paths,
  pause/resume, cancellation, option updates and session checkpoints.
  Native library initialization is process-wide; sessions are finalized separately.
- Added platform system CA export with certificate/hostname verification enabled.
  Native DNS uses the active Android network's servers at session creation.
- Added JVM lifecycle/validation tests and controlled loopback instrumentation
  fixtures, including TLS rejection, content hashes, resume and large files.
- Added APK alignment/dependency verification and NixOS/native setup guidance in
  [aria2/native/README.md](aria2/native/README.md).

### Verification status

- Both native ABIs built successfully from the pinned archives.
- Final command passed with the phone's mDNS serial selected through
  `ANDROID_SERIAL`:

  ```sh
  ./gradlew assembleDebug lintDebug :aria2:testDebugUnitTest \
    :aria2:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.largeFile=true
  ```

- All four JVM tests, all seven native instrumentation tests and the app launch
  smoke test passed. Device: arm64-v8a, API 37, `getconf PAGE_SIZE = 4096`.
  The native suite verified actual SHA-256 content for a 4 GiB + 64 KiB transfer,
  HTTPS, range resume/checkpoints, redirects and unknown lengths. It also checked
  expired/wrong-host/untrusted TLS rejection, sanitized exceptions, and preserved
  partial data when a server rejects resume.
- APK ELF dependency checks, 16 KiB LOAD alignment and `zipalign -P 16` passed
  for all packaged libraries in both supported ABIs.
- Android test runner and Espresso versions are explicitly pinned. AGP 9.4.1
  reports a false failed task for IP:port device serials because of an encoded
  JUnit result-key mismatch; the final command passed using the mDNS serial.
- 16 KiB runtime acceptance remains pending: no emulator image was installed.
  Downloading the API 35 16 KiB image into `/tmp` was paused because of slow
  network transfer. Install `system-images;android-35;google_apis_ps16k;x86_64`
  and run the instrumented suite on that emulator before closing milestone 1.

Do not advance to background execution until the native acceptance gates in
`plan.md` have been verified.

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
