# Product behavior

Aria runs on Android 15+ and bundles its download engine. Downloads, History and Settings are the
main destinations. The Material 3 Expressive interface adapts to phone and wide windows, supports
dynamic colors and system/light/dark appearance, and keeps forms usable with the keyboard open.

## Add and manage downloads

Add one HTTP/HTTPS link, magnet, torrent URL or Metalink URL. URLs without a recognized metadata
extension allow explicit protocol selection. Android sharing accepts text links and torrent/Metalink
documents. There is no file picker. Clipboard suggestions prefill new forms without replacing a
draft or shared input. Receiving input always leaves the user in control of starting work.

HTTP downloads allow a filename, referrer and User-Agent. The queue is FIFO, with pause, resume,
confirmed cancel and retry actions. Search and status filters help find tasks. Details show
progress, speed, ETA, state explanations, errors and
[session insights](transfer-insights.md).

Torrents, magnets and Metalinks resolve metadata first. Review the files, select at least one
supported entry, then start the payload. Selection stays fixed during that transfer. Torrents may
upload while downloading; seeding stops afterward.
See [protocol behavior and limits](advanced-protocols.md).

History contains completed, failed and canceled tasks. Successfully saved files can be opened or
shared with temporary read access. Task deletion asks whether to also remove public files. A failed
deletion or declined Android consent retains the record.

## Saving and recovery

Transfers download into private staging, then publish through MediaStore Downloads. Single-file
results go directly into Downloads. Multi-file results preserve safe relative paths under
`Downloads/Aria-<task UUID>/`. Completed means all selected files are publicly saved. Verification,
saving and save failure are separate states.

Saving temporarily needs space for both staging and the public copy. Retry saving uses the existing
staged data and destination identity. Staging remains recoverable until publication succeeds.
Uninstalling removes unfinished and unexported data; publicly saved files remain.

Temporary connection, DNS, timeout and server-busy errors retry after 5, 15 and 45 seconds. Waiting
retries release their queue slot. Retries require an active Android execution authorization.
Constraint waiting does not consume retry attempts. Access, TLS, storage, verification and
rejected-resume failures need user action. Manual Retry creates a fresh task; Resume preserves the
existing transfer.

Opening Aria never schedules a new transfer. After reboot or user stop, choose Resume explicitly.
Other interruptions can recover when Android retries the existing job. Notifications provide
progress and controls; denying notification permission still permits transfers and in-app controls.

## Settings and diagnostics

Settings autosave. Defaults are three simultaneous transfers, four connections per server, unlimited
speed, any network and system appearance. Concurrency accepts one to six transfers; connections
accept one to sixteen. Unmetered Wi-Fi preference applies to the authorized queue. Connection and
speed defaults affect new requests; network and concurrency changes reconcile the queue under
visible authorization.

Diagnostics require preview before sharing. Reports contain app/API versions and allowlisted
structured transfer status. They omit source URLs, filenames, headers, credentials, tokens, paths
and raw exceptions.

## Current limits

Custom destinations, bulk actions and queue reordering are not implemented. Supported protocol
formats and metadata bounds are listed in the
[protocol guide](advanced-protocols.md). The engine trusts system certificate authorities;
user-installed CAs are not supported.

Release delivery uses signed GitHub APKs. There is no Play Store integration or push/PR build
pipeline. Licensing and corresponding-source distribution remain unresolved distribution work;
release automation does not complete them.
