# Reliability acceptance

Milestone 5 established HTTP/HTTPS reliability; milestone 6 extends those safeguards
with protocol metadata review and per-file journals (see [advanced protocols](advanced-protocols.md)).
Completion means all selected files are published through MediaStore,
not just finished network transfer or staged bytes. See PROGRESS.md for executed
checks and remaining device coverage.

## Automated verification

```sh
./gradlew assembleDebug lintDebug :app:testDebugUnitTest \
  :downloads:testDebugUnitTest :aria2:testDebugUnitTest connectedDebugAndroidTest
```

The native 4 GiB test is opt-in using
`-Pandroid.testInstrumentationRunnerArguments.largeFile=true`. Clipboard coverage
requires an unlocked device. A locked-device assumption skip is not a passed test.

Device tests cover HTTP 503 recovery with original staging/checkpoint identity,
non-zero range resume, notification permission denial and the published SHA-256.
The diagnostics preview test checks that the report excludes the task filename and
source. Storage tests exercise v1/v2/v3 migrations into Room v4. JVM virtual-time
tests cover exact backoffs, budget exhaustion, queue admission, stale executions,
network changes during backoff, pause/cancel, recovery and persistence faults.

After building app/test APKs, run these opt-in host scenarios sequentially on a
connected test device. They stop only Aria, use a local controlled server and remove
their server/ADB reverse mapping afterward:

```sh
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb"
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --stop-mode force-stop
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --stop-mode task-manager
```

Each seeds two running transfers, interrupts the process, and verifies range resume
and both published-file hashes. User-stop scenarios additionally verify that opening
the app leaves both tasks paused until explicit Resume. The killed seed runner is
expected to report process death. Add `--serial` when several devices are connected.

## Device matrix

Use a controlled range-capable server and verify its expected hash after publication.
Run on Android 15 and a newer Android release. Use a test device for reboot and
network toggles, especially when ADB itself uses Wi-Fi.

| Scenario | Expected behavior |
| --- | --- |
| Wi-Fi → mobile, Wi-Fi restriction off | The assigned job network changes; session/DNS refresh, partial data resumes and the public hash matches. |
| Wi-Fi → mobile, unmetered Wi-Fi required | Transfer stops and displays a waiting reason. No mobile transfer occurs. Returning to qualifying Wi-Fi permits Android to resume the existing job. |
| Offline → online | Waiting retains running intent and partial data. Constraint waiting does not spend retry attempts. |
| Reboot during transfer | Reopening shows paused recoverable tasks. Only explicit Resume schedules new work; published hashes match. |
| Task Manager Stop / force-stop | No callbacks are assumed. No new transfer starts merely because the app is reopened. |
| Notification permission denied | Transfer and in-app controls work; the app shows notification status and a Settings shortcut. |
| Temporary failure followed by permanent failure | Backoff is bounded; permanent errors stop promptly with actionable advice. |
| Interrupted publication | Retry saving preserves the destination identity and avoids duplicate public files. |

Native TLS verification remains enabled. Rejected resume, changed-resource/control
metadata and storage errors preserve partial data and require user action. Manual
Retry intentionally creates a fresh task; Retry saving performs local export only.
