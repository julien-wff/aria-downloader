# Development progress

## 2026-10-17 - Milestone 5 reliability hardening

Status: implementation complete; device acceptance is recorded below. The user
explicitly authorized the full milestone 5 plan.

### Implemented

- Added three automatic retries after 5, 15 and 45 seconds for temporary timeout,
  connection, DNS and server-busy errors. Room v4 stores the budget, deadline,
  failure category, engine code and last known Android stop reason. Backoff releases
  queue slots; pause/cancel, stale leases and failed persistence cannot launch retries.
- Added native failed-task restart using the original request, staging identity,
  file and checkpoint. Session recreation preserves sibling running/paused states;
  native retry loops are disabled so the coordinator owns the bounded policy.
- Bound native traffic to the UIDT job's assigned network. Network callbacks refresh
  the binding, session and DNS, including changes during retry backoff. Constraint
  stops preserve running intent and show actionable network/system explanations.
- Added reboot/user-stop detection using boot count and process exit history. Those
  recoveries pause tasks and fence previous tokens. Startup never schedules work;
  unknown interruptions are described without guessing their cause.
- Retained transfers and in-app controls when notification permission is denied,
  with persistent in-app status and a notification-settings shortcut. Details show
  retry/error information and a previewable, explicitly shared diagnostics report.
  Reports include only allowlisted structured fields, excluding request data,
  filenames, credentials, tokens, paths and raw exceptions.
- Extended the two-transfer host recovery harness with force-stop and Task Manager
  Stop scenarios. Updated current milestone instructions and documented behavior,
  commands and manual acceptance in [docs/reliability.md](docs/reliability.md).

### Verification

- Build, lint and all 74 JVM tests passed on the final sources (10 app, 55 downloads,
  9 native). The full command below also completed successfully earlier in this
  milestone on Samsung SM-S948B, Android 17/API 37 (73 JVM tests before the final
  early-network-callback regression was added):

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest \
    :downloads:testDebugUnitTest :aria2:testDebugUnitTest connectedDebugAndroidTest
  ```

- Device coverage passed all 14 downloads tests, eight default native tests and
  ten app tests. The opt-in 4 GiB test and locked-device clipboard check were skipped.
  The native and end-to-end tests verify HTTP 503 recovery, original staging/control
  identity, non-zero range resume and file hashes. The app test uses actual denied
  notification permission and verifies successful MediaStore publication.
- The final rerun below passed build/lint/JVM tasks, retry/publication and diagnostics
  preview coverage, but the command failed on the existing keyboard test's 10-second
  IME visibility wait on the locked phone. That keyboard case passed in the earlier
  full run; clipboard remained skipped. The rerun is not claimed as fully passing:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest \
    :downloads:testDebugUnitTest :aria2:testDebugUnitTest :app:connectedDebugAndroidTest
  ```

- The abrupt process-kill harness passed against the final APKs:

  ```sh
  python3 tools/test-process-recovery.py --adb /home/julien/Android/Sdk/platform-tools/adb
  ```

  Both live transfers had native checkpoints before SIGKILL. A new process reused
  their identities, issued non-zero range requests, published distinct files and
  verified both SHA-256 hashes. Recovery took 86 seconds. Seed instrumentation's
  process-crash result is intentional; server and ADB reverse mapping were cleaned up.

- The force-stop harness also passed:

  ```sh
  python3 tools/test-process-recovery.py --adb /home/julien/Android/Sdk/platform-tools/adb --stop-mode force-stop
  ```

  Reopening left both tasks paused with unchanged byte counts. Explicit Resume then
  recovered both files using non-zero ranges and verified their published hashes in
  83 seconds. The old authorization was fenced; cleanup completed.
- Final review added a guard for network callbacks arriving before engine startup,
  plus a regression that delays Room loading while authorization is pending. Final
  build/lint, all 74 JVM tests and instrumentation compilation passed with:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest \
    :downloads:testDebugUnitTest :aria2:testDebugUnitTest :app:assembleDebugAndroidTest
  ```

- Task Manager Stop verification passed after the phone reconnected:

  ```sh
  python3 tools/test-process-recovery.py --adb /home/julien/Android/Sdk/platform-tools/adb --stop-mode task-manager
  ```

  Both transfers remained paused with unchanged byte counts after reopening. Explicit
  Resume reused their staging identities, issued non-zero range requests, published
  distinct files and verified both SHA-256 hashes. Recovery took 50.104 seconds;
  server and ADB reverse mapping cleanup completed. The killed seed runner's crash
  output is intentional. Two earlier attempts had been blocked during APK installation
  by wireless ADB connectivity; the harness now includes ADB stderr in failures.
- After the phone reconnected, a direct instrumentation rerun of
  `ProductUiTest#keyboardFormsAndAutosaveSurviveNavigationAndRecreation` and
  `ProductUiTest#clipboardPrefillsNewDownloadButNeverOverwritesDraftOrShareIntake`
  passed with no skips: `OK (2 tests)`, 6.741 seconds. All 33 default device tests
  have now passed across the milestone's runs; the opt-in 4 GiB case remains excluded.
  No reboot or device network settings were changed.
- Physical reboot, actual Wi-Fi/mobile/offline transitions and Android 15 remain
  manual acceptance checks. The connected phone uses Wi-Fi ADB, so network toggles
  and reboot were not induced. Earlier 16 KiB runtime and 4 GiB acceptance gates were
  not rerun. These checks remain unverified; they do not block advancement.

## 2026-10-17 - Per-download HTTP options

- Follow-up verification with the reconnected Samsung SM-S948B:
  `./gradlew connectedDebugAndroidTest` completed successfully. Background
  pause/resume, completed-file hash verification, Advanced UI, HTTP headers and all
  13 storage/device checks passed (29 device tests passed overall). Clipboard was
  skipped because the background test left the phone locked; the opt-in large-file
  test was also skipped. After the user unlocked the phone, the isolated clipboard
  regression passed with no skips:

  ```sh
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.julien_wff.aria_android.ProductUiTest#clipboardPrefillsNewDownloadButNeverOverwritesDraftOrShareIntake
  ```

  All 30 default device tests have now passed across these runs; only the opt-in
  large-file test remains unexecuted.

- Removed the save-location notice from Add download. Added an accessible Advanced
  section, collapsed for each new form, with optional HTTP referrer and User-Agent.
- Draft values survive collapsing and Activity recreation, and reset for fresh
  clipboard/share intake. Validation runs before notification permission requests.
- Persisted overrides in Room schema v3 with a non-destructive v2 migration; resume,
  recovery and retry retain them. Native checkpoints retain headers, and clearing
  engine overrides restores defaults.
- Build, lint, all 59 JVM tests and the focused Advanced device regression passed:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest :downloads:testDebugUnitTest :aria2:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.julien_wff.aria_android.ProductUiTest#advancedRequestFieldsCollapseRetainAndReset
  ```

- The broader verification command was:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest :downloads:testDebugUnitTest :aria2:testDebugUnitTest connectedDebugAndroidTest
  ```

  All 13 downloads device tests passed, including v1/v2 migrations and persisted
  headers. Seven native tests passed, including real HTTP header delivery; the
  opt-in large-file test was skipped. Eight app tests passed, including Advanced;
  clipboard coverage was skipped because the Samsung SM-S948B (Android 17/API 37)
  was locked. The command failed because the existing background pause/resume test
  timed out after 60 seconds. Background resume and unlocked clipboard behavior
  remain unverified by this run; the full device suite is not claimed as passing.

## 2026-10-17 - Stable settings autosave layout

- Removed the transient saving row that inserted and removed space above the form
  on every preference update. Routine saves are silent; loading and actionable save
  errors still appear.
- Removed the conditional appearance-chip icon so selection no longer changes chip
  widths or causes adjacent choices to move or wrap.
- Added a device regression with deliberately suspended saves. It verifies identical
  control bounds before, during and after saving, including appearance selection.
- Build, lint, all 10 app JVM tests and the new device regression passed on Samsung
  SM-S948B, Android 17/API 37:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.julien_wff.aria_android.SettingsLayoutTest
  ```

## 2026-10-17 - Milestone 4.5 clipboard and Downloads refinements

- Removed the persistent settings save-success message; automatic saving and
  loading/saving/error feedback remain.
- Completed MediaStore files now publish directly into Downloads. A pending entry
  retains its recoverable task namespace until its URI is durable; publication moves
  it to Downloads while clearing IS_PENDING. Existing published files keep their
  location. Name collisions receive distinct filenames without replacing content.
- Tapping Add checks the clipboard once for an HTTP/HTTPS link and fills the URL
  and decoded, safe URL-path filename. Rotation and shared links preserve their own
  drafts, and no download starts until Start is pressed.
- Build, lint, instrumentation compilation and all 56 JVM tests passed with:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest \
    :downloads:testDebugUnitTest :aria2:testDebugUnitTest :app:assembleDebugAndroidTest
  ```

- All 12 storage device tests passed, including direct-Downloads paths, same-name
  collision protection, pending-insertion recovery and post-publication retry.
- The combined device command (`:app:connectedDebugAndroidTest
  :downloads:connectedDebugAndroidTest` alongside build/lint/JVM tasks) failed two
  app cases on the locked phone: clipboard intake returned no data and the IME did
  not open. Six other app cases passed. The final clipboard test now checks for an
  unlocked device before changing the clipboard and restores its original contents.
  Clipboard and keyboard checks await an unlocked-device rerun; they are not claimed
  as passed for this refinement.

## 2026-10-17 - Milestone 4.5 interface overhaul

Status: milestone 4.5 implemented and verified on API 37.
The user authorized milestone 4.5 with Material 3 Expressive styling, dynamic
colors and bounded settings controls.

### Implemented

- Reworked all product screens with consistent typography, rounded grouped
  surfaces, iconography, search/filter treatment, empty states and transfer actions.
  Phone secondary screens use back-arrow app bars. Navigation rail layouts put Add
  in the rail, or in the app bar in short landscape windows; tablet forms are capped
  at 640 dp. System-bar contrast follows the selected light/dark appearance.
- Designed the original A/download SVG mark and matching adaptive/monochrome
  launcher resources. Bundled selected Material Symbols Rounded vectors from a
  pinned upstream revision with Apache 2.0 attribution in `docs/design`.
- Replaced explicit settings saves with bounded count controls and a serialized,
  ViewModel-owned writer. Discrete changes save immediately; valid speed edits
  debounce for 400 ms and flush on Done, focus loss or navigation. Invalid drafts
  preserve the last valid value. Failed saves/applications retain drafts with Retry;
  rapid changes cannot be dropped by the exclusive-action busy guard.
- Corrected content/IME inset consumption, hid phone navigation/Add during keyboard
  input, and added Next/Done/Search actions. Forms scroll above the keyboard and
  retain drafts across Activity recreation. Invalid Add input is validated through
  the existing engine request rules before asking for notification permission;
  validation itself never persists or schedules work.
- Preserved queue/recovery/export behavior and publication-dependent completed-file
  actions. Failed saving is explicitly labeled as unsuccessful public publication.

### Verification

- This final command passed build, lint, all 53 JVM tests (7 app settings,
  40 downloads, 6 native) and all 7 app device tests:

  ```sh
  ./gradlew assembleDebug lintDebug :app:testDebugUnitTest \
    :downloads:testDebugUnitTest :aria2:testDebugUnitTest :app:connectedDebugAndroidTest
  ```

- The 12 storage/notification device tests and 6 default native device tests also
  passed during this milestone's module runs on Samsung SM-S948B, Android 17/API 37:
  25 device tests passed across the verified suites. The opt-in 4 GiB test was excluded.
  Lint reports dependency-update warnings; pinned compiler/toolchain versions remain
  aligned. No lint errors remain.
- App tests cover native toolbar/system Back, share intake without automatic start,
  pause/resume/cancellation, automatic settings persistence, invalid drafts, Activity
  recreation, actual IME visibility, hidden phone navigation, focus traversal and
  reachable form actions. The background-download test still verifies publication
  and the public file hash.
- Local Compose overrides exercise landscape (700 × 360 dp), tablet (1000 × 800 dp)
  and 150% text with RTL (360 × 740 dp). Review captures covered light/dark settings,
  forms and adaptive navigation; the landscape review prompted the compact Add action.
  These overrides do not change the physical device's display configuration.
  A final direct instrumentation run with `captureUi=true` passed both the responsive
  and keyboard/recreation scenarios. Final screenshots confirmed a reachable Start
  button above the IME, hidden phone navigation, no landscape FAB overlap and
  centered tablet forms.
- Android 15/API 35, a physical tablet, launcher themed-icon appearance, full TalkBack
  review and real notification-denial/MediaStore-consent dialogs remain manual checks.
  The earlier process-kill harness, 16 KiB runtime and 4 GiB transfer were not rerun
  for this UI milestone.

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
