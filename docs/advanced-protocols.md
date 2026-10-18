# Advanced protocol downloads

Aria supports BitTorrent v1 torrent files and magnets, plus Metalink manifests with HTTP/HTTPS
mirrors. Add web links, choose the protocol when a URL has no recognized metadata extension, or
share a torrent/Metalink document from another app. There is no file picker. Receipt and import
never start network transfers.

## Review and execution

The first action resolves metadata under a user-initiated Android job. Magnets use metadata-only
retrieval; HTTP metadata is staged with its native checkpoint. Shared files are copied privately
while their URI grant remains available. Metadata imports are committed by a synced temporary file
and rename. Files are limited to 8 MiB and 10,000 entries, with a conservative aggregate
file-journal budget of 1 MB before Base64 encoding to keep Room rows readable through Android cursor
windows. Long relative paths reduce the effective file-count limit. Unsafe or conflicting paths,
symlinks, XML entities and unsupported torrent formats are rejected before payload execution.
Path conflicts include parent directories and the engine's `.aria2` checkpoint paths.

Metadata completion enters durable **Ready to select files**, releases its queue slot and stops its
network work. Select at least one supported file and choose **Start selected files**. That action
persists the selection before authorizing a new batch. Selection is fixed for that payload transfer.
Reopening the app or receiving a share does not authorize downloads. Pause/cancel, network
constraints, interruption recovery and bounded retries follow the shared
[reliability rules](reliability.md).

The engine retains a stable task staging identity and the original metadata. Torrent requests use
native file indexes and piece verification. Peer discovery supports magnets; private-torrent
restrictions remain enforced by libaria2. Peer uploads can occur during download; `seed-time=0`
stops post-download seeding. Metalink children run sequentially inside their parent queue slot, with
deterministic native identities, safe paths, HTTP/HTTPS mirror failover and supported whole-file
SHA-1/SHA-256/SHA-512 checksums. Unsupported-only entries cannot be selected. Torrent-backed
Metalink entries and piece-checksum-only manifests are not supported.
Piece checksums are never used as whole-file checksums, including when both appear in a manifest.

## Publication and recovery

Room v5 stores protocol, confirmed selection and an atomic per-file journal for each task. Migration
from v4 preserves existing HTTP tasks, retry budgets, mappings and published URIs. Each file journal
stores index, relative path, size, selection, progress, destination URI and publication status.

Selected files publish through independent pending MediaStore entries. Multi-file tasks preserve
relative directories under `Downloads/Aria-<task UUID>/`; single-file tasks publish directly into
Downloads. The pending task/file namespace permits recovery after insertion but before its URI
reaches Room. Persisted URIs handle recovery after publication. Retry saving never truncates an
already public file. A task becomes Completed only when every selected file is published and
durable. Staging is retained until that point. Open individual files in details; Share grants read
access to all selected public files. Deletion covers every associated entry, retaining the task if
any deletion or consent fails.
Staging cleanup after durable completion is best effort; a cleanup failure preserves Completed and
can be retried at startup.

## Verification

Use the commands in [testing](testing.md).

`ProtocolEngineTest` supplies controlled HTTP web seeds, a BEP 9 metadata peer and tracker, Metalink
mirrors, file checksums and native checkpoint reconstruction.
`ProtocolUiTest` verifies shared metadata intake, explicit review and retained selection through
Activity recreation. Storage tests cover v4 migration, grouped paths, per-file publication commit
windows and content bytes. JVM tests cover metadata safety, protocol intake, queue authorization and
interrupted publication.
