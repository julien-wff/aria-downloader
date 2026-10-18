# Architecture

Aria is an in-process Kotlin/Compose Android app with embedded libaria2. There is no companion
server, RPC listener or first-launch engine download.

## Module ownership and entry points

| Module       | Responsibility                                                  | Main entry points                                                                 |
|--------------|-----------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `:app`       | Compose screens, navigation, ViewModels and UI wiring           | `MainActivity`, `DownloadsViewModel`, `DownloadScreens`                           |
| `:downloads` | Queue, persistence, execution, notifications and public storage | `DownloadController`, `DownloadRuntime`, `TransferJobService`, `DownloadExporter` |
| `:aria2`     | Engine contract, serialized native owner, JNI and native builds | `DownloadEngine`, `NativeDownloadEngine`, `bridge.cpp`                            |

Dependencies flow `:app` → `:downloads` → `:aria2`. UI observes immutable task and settings
projections through StateFlow. `DownloadRuntime` supplies one process-wide controller to the
Activity, job service and notification receiver.

## Execution lifecycle

1. A visible user action validates input and persists task intent and batch membership.
2. JobScheduler receives a user-initiated data transfer job with a batch token and network
   constraints. Startup only reconstructs state; it never schedules work.
3. `TransferJobService` supplies an execution lease for that invocation, attaches its notification
   and binds the process to the job's assigned network.
4. `DownloadController` serializes changes and admits FIFO work only for a matching authorization.
   Stale leases and notification tokens cannot control newer batches.
5. `NativeDownloadEngine` serializes native calls on one dedicated owner thread and emits immutable
   snapshots. Only one native engine can run per process.
6. Verified staged payloads leave the network queue and enter serialized public export outside the
   coordinator lock. Completion follows durable publication.

Network changes checkpoint/restart the native session to refresh network and DNS. Job revocation
stops its network work. Constraint waiting preserves desired running intent. User pause/cancel
intent is persisted before stopping work. Automatic retry budgets and deadlines survive process
death; they cannot authorize execution alone.

Protocol tasks add a metadata stage followed by durable awaiting-selection state. That state
releases the queue slot. Starting selected files persists selection and requires a fresh user
authorization. Metalink children share one parent slot and run sequentially.
See [protocol details](advanced-protocols.md).

## State and recovery

| Owner                | Stored state                                                                                        | Lifetime                           |
|----------------------|-----------------------------------------------------------------------------------------------------|------------------------------------|
| Room                 | User intent, observed status, batch membership, engine mapping, retries and per-file export journal | Durable, private no-backup storage |
| aria2 staging        | Partial files, retained metadata, control files and native checkpoints                              | Durable, private no-backup storage |
| DataStore            | Queue defaults, connection/speed limits, network preference and appearance                          | Durable preferences                |
| Coordinator insights | Speed samples and bounded piece summaries                                                           | Current process only               |

Room schema version is 5, with explicit migrations from versions 1 through 4. The legacy JSON intent
record imports once without guessing a missing staging map. Desired state and observed status are
separate: waiting for a network is not a user pause. The coordinator reconciles Room and native
identities instead of blindly loading the native session file. Native checkpoints run every five
seconds; explicit checkpoints reconstruct the session and can reconnect active transfers.

After reboot or a detected user stop, saved runnable tasks require explicit Resume. An existing
Android job may recover other interruptions. Unknown stop causes remain generic. The newest-task
`state` projection is retained for compatibility with the host recovery harness; the UI uses the
task list.

## Publication and security boundaries

The engine owns private staged bytes. `:downloads` owns MediaStore entries and public completion.
Pending entries and persisted per-file URIs cover insert and publication crash windows. Retry saving
preserves destination identity and never truncates an already public file. Staging cleanup follows
successful durable publication of every selected file.

Validate URLs, filenames and protocol paths before native execution. Byte counters are 64-bit. JNI
copies bounded data and translates exceptions; native pointers stay inside the bridge. Errors
exposed to the app are sanitized, and diagnostics use an explicit allowlist. Open/share actions
grant temporary URI permissions.

TLS verifies hostnames and certificates using exported Android system CA anchors. Native DNS comes
from the assigned Android network. See the
[native contract](../aria2/native/README.md#engine-contract) for session constraints
and [transfer insights](transfer-insights.md) for telemetry bounds and semantics.
