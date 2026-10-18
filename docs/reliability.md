# Reliability behavior

Temporary transfer failures receive at most three automatic retries, after 5, 15 and 45 seconds. A
matching active job lease is required. Backoff releases the queue slot, while network constraints
preserve intent without spending attempts.

Native TLS verification remains enabled. Rejected resume, changed-resource/control metadata and
storage errors preserve partial data and require user action. Manual Retry creates a fresh task;
Retry saving performs local export only.

Completion requires public MediaStore publication of every selected file. Protocol metadata review
and per-file journals follow the same recovery rules. See
[protocols](advanced-protocols.md) and [testing](testing.md) for automated coverage, host recovery
commands and outstanding device checks.

## Device matrix

Use a controlled range-capable server and verify its expected hash after publication. Run on Android
15 and a newer Android release. Use a test device for reboot and network toggles, especially when
ADB itself uses Wi-Fi.

| Scenario                                        | Expected behavior                                                                                                                                  |
|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Wi-Fi → mobile, Wi-Fi restriction off           | The assigned job network changes; session/DNS refresh, partial data resumes and the public hash matches.                                           |
| Wi-Fi → mobile, unmetered Wi-Fi required        | Transfer stops and displays a waiting reason. No mobile transfer occurs. Returning to qualifying Wi-Fi permits Android to resume the existing job. |
| Offline → online                                | Waiting retains running intent and partial data. Constraint waiting does not spend retry attempts.                                                 |
| Reboot during transfer                          | Reopening shows paused recoverable tasks. Only explicit Resume schedules new work; published hashes match.                                         |
| Task Manager Stop / force-stop                  | No callbacks are assumed. No new transfer starts merely because the app is reopened.                                                               |
| Notification permission denied                  | Transfer and in-app controls work; the app shows notification status and a Settings shortcut.                                                      |
| Temporary failure followed by permanent failure | Backoff is bounded; permanent errors stop promptly with actionable advice.                                                                         |
| Interrupted publication                         | Retry saving preserves the destination identity and avoids duplicate public files.                                                                 |
