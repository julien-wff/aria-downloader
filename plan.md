I recommend a **native Kotlin + Jetpack Compose application with aria2 embedded through the Android
NDK**, using **user-initiated data transfer jobs** to run downloads in the background.

Use AriaNg as a feature reference, with a new **Material 3 Expressive** interface. Everything needed
to download files ships inside the APK: no server, companion application, or first-launch binary
download.

The plan below assumes an initial phone-focused release, with HTTP/HTTPS first and
BitTorrent/Metalink added once background execution and storage are reliable.

**1. Technical foundation**

| Area                       | Choice                                                                   |
|----------------------------|--------------------------------------------------------------------------|
| Minimum OS                 | Android 15, `minSdk = 35`                                                |
| Compile/target SDK         | Latest stable SDK at implementation time                                 |
| UI                         | Jetpack Compose, Material 3 Expressive                                   |
| State management           | ViewModels, coroutines, `StateFlow`, unidirectional state                |
| Download engine            | NDK-built libaria2 with a small JNI wrapper                              |
| Background execution       | `JobScheduler` user-initiated data transfer jobs                         |
| Persistent data            | Room                                                                     |
| Preferences                | DataStore                                                                |
| Public file storage        | `MediaStore.Downloads`; Storage Access Framework for custom destinations |
| Supporting background work | WorkManager for short maintenance tasks                                  |
| Architectures              | `arm64-v8a` release; `x86_64` for emulator development                   |

Material 3 Expressive is the current evolution of Material 3 and is available through Compose.
Prefer stable components, isolating any experimental APIs behind your own UI
components. [developer.android.com](https://developer.android.com/develop/ui/compose/designsystems/material3?authuser=131\&utm_source=chatgpt.com)

Keep the existing Android Studio project. Introduce three modules initially:

| Module       | Responsibility                                                    |
|--------------|-------------------------------------------------------------------|
| `:app`       | Compose screens, navigation, ViewModels, dependency wiring        |
| `:downloads` | Room, queue policy, job scheduling, notifications, storage/export |
| `:aria2`     | Native builds, JNI bridge, engine interface                       |

Avoid splitting every screen into a module at this stage.

**2. Embed aria2 as a library**

Build libaria2 from pinned source and package it as a native shared library. aria2 documents Android
cross-compilation, but its existing instructions reference an older NDK; modernizing and validating
that build should be the first
milestone. [GitHub](https://github.com/aria2/aria2?utm_source=chatgpt.com)

The native build should:

- Pin aria2 and all native dependency versions.
- Enable HTTP/HTTPS, compression, asynchronous DNS, BitTorrent and Metalink.
- Add SFTP later if wanted.
- Bundle a maintained TLS implementation and establish an explicit CA trust strategy.
- Produce reproducible libraries through a scripted build.
- Support both 4 KB and 16 KB memory-page devices.

Every native dependency needs compatible alignment, not just the JNI wrapper. Test the packaged APK
on a 16 KB
emulator/device. [developer.android.com](https://developer.android.com/guide/practices/page-sizes?utm_source=chatgpt.com)

Expose a deliberately small Kotlin interface:

```kotlin
interface DownloadEngine {
    val snapshots: Flow<EngineSnapshot>

    suspend fun start()
    suspend fun add(request: EngineRequest): EngineTaskId
    suspend fun pause(id: EngineTaskId)
    suspend fun resume(id: EngineTaskId)
    suspend fun remove(id: EngineTaskId)
    suspend fun updateOptions(id: EngineTaskId, options: EngineOptions)
    suspend fun checkpoint()
    suspend fun stop()
}
```

Implementation principles:

- Own the native session on one dedicated engine thread.
- Serialize commands through a queue.
- Run the engine loop off the main thread.
- Copy native data into Kotlin snapshots; never expose native pointers.
- Catch native exceptions at the JNI boundary.
- Stop the session when there is no eligible work.

Validate libaria2’s API coverage during the initial spike. If a needed feature requires JSON-RPC,
put that behind the same interface and secure it with a random secret and loopback-only binding. The
preferred initial design requires no listening RPC server.

For the first version, keep the engine in the application process. A separate `:engine` process can
later isolate native crashes, but introduces IPC and additional lifecycle complexity.

**3. Background execution: design this before the UI**

**Do not base the application on an indefinitely running `dataSync` foreground service.** Apps
targeting Android 15+ face a six-hour background allowance per 24-hour period for that service type,
shared across the app’s
services. [developer.android.com](https://developer.android.com/develop/background-work/services/fgs/timeout?utm_source=chatgpt.com)

Use user-initiated data transfer jobs for downloads explicitly started by the user:

1. Persist the download request.
2. Schedule a job while the app is visible.
3. Set `setUserInitiated(true)` and appropriate network constraints.
4. Provide estimated transfer sizes when known.
5. Attach the required notification using `JobService.setNotification()`.
6. Start the engine asynchronously.
7. Finish the job when its authorized transfers are finished or paused.

Declare `RUN_USER_INITIATED_JOBS`, and protect the service with `BIND_JOB_SERVICE`. UIDT jobs
support long transfers, but the OS can still stop them for constraints, system health, or process
pressure. [developer.android.com](https://developer.android.com/develop/background-work/background-tasks/uidt?utm_source=chatgpt.com)

Use a **single coordinator** to own the engine. Jobs authorize particular tasks or batches; the
engine must not continue unrelated downloads after their execution authorization ends.

Important behavior:

| Event                                            | Expected behavior                                                    |
|--------------------------------------------------|----------------------------------------------------------------------|
| User leaves the app or locks the screen          | Downloads continue under the active job                              |
| Network constraint fails                         | Affected transfers stop; resume when the scheduler permits           |
| Process dies                                     | Reconstruct tasks and resume from saved engine state                 |
| User pauses                                      | Persist pause intent before stopping transfer work                   |
| User force-stops the app                         | Respect the stop; require user re-entry                              |
| Device reboots                                   | Recover persisted jobs/state where supported; otherwise offer Resume |
| Notification Resume cannot legally schedule work | Open the relevant screen for user-initiated resume                   |

Checkpoint continuously: Android’s Task Manager Stop action can terminate the process without
calling
`onStopJob()`. [developer.android.com](https://developer.android.com/develop/background-work/background-tasks/uidt?utm_source=chatgpt.com)

Reserve WorkManager for short cleanup and reconciliation. Treat scheduled overnight downloads and
unattended recurring transfers as later features with their own execution design.

**4. Storage: download first, publish second**

aria2 expects filesystem paths and random-access files. A Storage Access Framework `content://` URI
is not a filesystem path.

For the initial implementation:

1. Create a staging directory per task in app-specific storage.
2. Download there, keeping aria2 control files alongside partial data.
3. Verify completion and any supplied checksum.
4. Publish the result into `MediaStore.Downloads`.
5. Remove staging data only after successful publication.

Use `IS_PENDING` while writing the public file. Public Downloads entries remain after uninstall,
unlike app-specific staging
files. [developer.android.com](https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com)

For custom folders, obtain a persisted SAF grant and export completed files through
`ContentResolver`.

Explicitly account for:

- **Temporary double storage:** staging plus exported file.
- **Export failures:** preserve the completed staged file and offer “Retry saving.”
- **Process death during export:** track destination URI and export phase to prevent duplicates.
- **Filename collisions:** offer rename or explicit replacement.
- **Large files:** use 64-bit sizes and progress counters everywhere.
- **Uninstall:** unfinished and unexported files in app-specific storage are removed.

For multi-file torrents, maintain an export record per file. Retain the staging copy while seeding.

Do not add all-files access for this design. Direct download into arbitrary document providers would
require a more substantial native storage adapter.

**5. Persistence and recovery**

Room should own **user intent and application history**. aria2 owns live transfer mechanics and its
resume metadata.

Suggested entities:

| Entity            | Key data                                                                     |
|-------------------|------------------------------------------------------------------------------|
| `DownloadTask`    | UUID, type, title, desired state, observed state, queue position, timestamps |
| `DownloadSource`  | URLs/mirrors and references to protected credentials                         |
| `DownloadOptions` | Connection limits, speed limits, checksum, selected torrent files            |
| `DownloadFile`    | Relative path, size, staging location, published URI                         |
| `EngineMapping`   | App UUID, current aria2 identifier, related metadata task identifiers        |
| `ExportRecord`    | Destination, phase, progress, failure                                        |
| `TaskError`       | Category, engine code, sanitized message, retry information                  |

Keep desired state separate from observed state. For example, “user wants running” plus “waiting for
unmetered network” should not become “paused by user.”

Use states such as:

`Queued`, `WaitingForNetwork`, `Downloading`, `Paused`, `Verifying`, `Exporting`, `Completed`,
`Failed`, `Canceled`.

Recovery must reconcile Room, staged files and engine checkpoints. Do not blindly re-add every task
at startup.

Persist important transitions immediately; throttle progress persistence to a few seconds. UI
updates can be more frequent without writing every update to SQLite.

**6. Native Android interface**

Build around three destinations:

| Screen    | Main content                                                        |
|-----------|---------------------------------------------------------------------|
| Downloads | Active and queued tasks, filters, search, aggregate speed           |
| History   | Completed/failed downloads, open/share/retry actions                |
| Settings  | Network policy, concurrency, speed limits, destinations, appearance |

Use an extended **Add download** FAB. The add screen accepts:

- One URL or multiple pasted URLs.
- Android share-sheet text.
- Later: magnet links, `.torrent` files and Metalink files.
- Optional name, destination, checksum and advanced request settings.

Each download row should show filename, status, transferred/total size, progress, speed and ETA.
Make pause/resume directly accessible; place destructive actions in an explicit menu.

A detail screen can expose:

- Sources and redirects.
- Files and file selection.
- Transfer statistics and a small speed graph.
- Per-download options.
- Human-readable errors with retry guidance.
- Later: trackers and peers.

AriaNg provides a useful reference for search, sorting, queue reordering, retries, file trees and
speed charts. Prioritize those interactions over reproducing every desktop
setting. [GitHub](https://github.com/mayswind/ariang?utm_source=chatgpt.com)

Visual requirements:

- Dynamic color, light/dark themes and expressive typography.
- Edge-to-edge layout with correct insets.
- Adaptive list/detail layout on larger screens.
- Accessible contrast, TalkBack descriptions and large-text support.
- Text labels alongside status colors.
- Restrained motion that does not make frequently updating progress distracting.

**7. Notifications**

Use Android’s standard notification templates for consistent system integration.

| Situation                 | Notification                                          |
|---------------------------|-------------------------------------------------------|
| One active download       | Filename, progress, speed, ETA; Pause and Cancel      |
| Multiple active downloads | Summary plus grouped task notifications               |
| Unknown file size         | Indeterminate progress and bytes transferred          |
| Exporting                 | “Saving to Downloads…” with export progress           |
| Completed                 | Open and Share                                        |
| Failed                    | Short explanation; Retry                              |
| Waiting                   | Clear reason, such as “Waiting for unmetered network” |

Implementation details:

- Separate channels for active transfers and completion/errors.
- Silent progress updates with stable notification IDs.
- Throttle updates to roughly once per second or meaningful changes.
- Use explicit, immutable pending intents and task-specific actions.
- Keep notification controls working when the Activity is absent.
- Request notification permission when the user starts their first download.
- Hide sensitive filenames on the lock screen if requested.

Android 16+ provides progress-centric notification styling; add it behind an API check, keeping
ordinary progress notifications on Android 15. Do not make promoted Live Update treatment a release
requirement. [developer.android.com](https://developer.android.com/develop/ui/views/notifications/progress-centric?utm_source=chatgpt.com)

**8. Feature scope**

| First usable release         | Next release                       | Later                            |
|------------------------------|------------------------------------|----------------------------------|
| HTTP/HTTPS downloads         | Magnets and torrent files          | SFTP                             |
| Pause/resume where supported | Torrent file selection             | Remote aria2 connections         |
| Persistent queue/history     | Metalink                           | Scheduled download windows       |
| Background transfers         | Mirrors and batch import           | Advanced automation              |
| Progress notifications       | Speed graphs                       | Direct document-provider storage |
| Share-sheet URL intake       | Advanced headers/authentication UI | Extensive engine settings        |
| Downloads export             | Custom SAF destinations            |                                  |
| Retry/cancel/delete          | Checksum UI                        |                                  |
| Concurrency and speed limits | Queue reordering and bulk actions  |                                  |
| Unmetered-only preference    | Charging constraints               |                                  |

For BitTorrent, distinguish normal peer uploads during downloading from **post-download seeding**.
Stop post-download seeding by default in the first torrent release. Indefinite unattended seeding
needs a separately validated background-execution policy.

Suggested initial tuning: three concurrent downloads and four connections per server, both
configurable. Benchmark rather than assuming more connections are always faster.

**9. Security and distribution**

Build these into the implementation:

- Validate incoming URLs and supported schemes.
- Treat filenames, torrent paths and metadata as untrusted input.
- Reject path traversal outside each staging directory.
- Keep TLS certificate verification enabled and test the chosen trust store.
- Protect stored credentials using Android Keystore-backed encryption.
- Redact authorization headers and sensitive URL parameters from logs.
- Allowlist engine settings exposed to users; keep filesystem and execution-related options
  app-controlled.
- Grant temporary URI permissions when opening/sharing files.
- Never automatically install downloaded APKs.

aria2 is GPL-licensed. Plan the distributed application around compatible licensing and include the
required notices and corresponding source/build materials; verify the exact pinned versions and
dependencies before release. AriaNg uses MIT licensing if you reuse any of its code or
assets. [GitHub](https://github.com/aria2/aria2?utm_source=chatgpt.com)

**10. Implementation milestones and acceptance gates**

Milestone 2 (background vertical slice) is implemented, with automated acceptance
verified on an API 37 phone (screen-off transfer, notification Pause/Cancel,
explicit Resume, and final content hash). The application
has single-download controls, a UIDT coordinator and system progress notifications
(ProgressStyle on API 36+, standard progress on API 35). Completion currently means
private staging. The single durable intent record deliberately does not implement
milestone 3's Room recovery or public Downloads export. See README.md for behavior,
test commands and remaining device acceptance checks.

| Milestone                    | Deliverable                                         | Acceptance gate                                                                                      |
|------------------------------|-----------------------------------------------------|------------------------------------------------------------------------------------------------------|
| 1. Native feasibility        | Reproducible libaria2 build and JNI bridge          | HTTPS download, pause/resume, large file, valid TLS, 16 KB compatibility                             |
| 2. Background vertical slice | One task running through UIDT with notification     | Transfer continues with Activity closed and screen locked; pause works from notification             |
| 3. Persistence and storage   | Room, checkpoints, Downloads export                 | Process kill recovers; interrupted export creates no duplicate; disk-full preserves recoverable data |
| 4. Core product UI           | Add flow, queue, details, history, settings         | Complete HTTP/HTTPS workflow through native UI                                                       |
| 5. Reliability hardening     | Retry policies, network constraints, diagnostics    | Wi-Fi/mobile transitions, notification denial, reboot and user stop behave predictably               |
| 6. Advanced protocols        | Torrents, magnets, Metalink                         | Metadata transitions, file selection, cancellation and export recover correctly                      |
| 7. Release                   | Signed APK, CI build, licenses and source materials | Clean install runs without downloading extra executable components                                   |

Test against controlled servers that exercise range support, no range support, redirects, unknown
lengths, authentication, changed resources, throttling and mid-transfer failures. Verify downloaded
content hashes, not just reported completion.

The first development target should be **one HTTPS download that survives leaving the app, can be
paused from its notification, recovers after process death, and appears correctly in Downloads**.
That validates the architecture before investing in the full interface.
