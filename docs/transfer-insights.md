# Transfer insights

Download details show a two-minute download-speed graph and a compact piece map.
Speed samples are collected by the download coordinator at most once a second,
including while another screen is open. Current-session samples and the latest map
remain available after pausing, failure, or successful saving. They are memory-only:
restarting the app process clears insights, but does not clear persisted downloads
or native recovery checkpoints. A fresh retry creates a new task and fresh insights.

The graph labels the latest sample, average, and peak for its displayed samples.
Missing samples and execution interruptions break the line. Paused time does not
count as zero download speed when calculating the sample average. Time is measured
with a monotonic clock; the last sample anchors the graph, so a stopped transfer's
final graph stays frozen.

The piece map uses libaria2's actual completion bitfield, not byte percentage.
The engine thread groups consecutive pieces into at most 256 cells and sends only
the bounded summary through JNI, at most once per second plus a final completion
read. Empty cells have no completed pieces; partially filled cells contain some
completed pieces; filled cells have all their pieces. The legend and accessible
summary explain this without relying on color alone. A cell does not indicate which
piece is currently being requested. Missing piece metadata displays an unavailable
message and does not affect the transfer.

- HTTP/HTTPS: the map covers the current payload.
- Torrent/magnet: the map covers the whole torrent, including unselected files.
  Gaps can remain after all selected files finish.
- Metalink: the map covers the current selected file, named above the grid. It
  resets when the engine advances to the next file.

A completed map describes private staging data. Files are publicly saved only after
successful publication to Downloads. Verification, saving, and save failures remain
separate states.

ETAs use the same formatter in the app and notifications: seconds, minutes/seconds,
hours/minutes, or days/hours. Unknown sizes, zero speed, and non-downloading states
have no transfer ETA. Positive subsecond estimates read “Less than 1 sec”. Spoken
in-app ETAs use full unit names. Retry countdowns in details refresh once a second.

## Verification

Run build/lint and all three module JVM suites with:

```sh
./gradlew assembleDebug lintDebug :aria2:testDebugUnitTest :downloads:testDebugUnitTest :app:testDebugUnitTest
```

Run the pure native aggregation test on a host with a C++17 compiler:

```sh
c++ -std=c++17 -Wall -Wextra -Werror aria2/src/test/cpp/piece_overview_test.cpp -o /tmp/aria-piece-overview-test
/tmp/aria-piece-overview-test
```

Run `./gradlew connectedDebugAndroidTest` for native HTTP pause/resume/completion,
torrent selection, Metalink transitions, readable notification ETAs, and the
accessible insight panels at large text size.

The clipboard acceptance test requires an unlocked device; the multi-gigabyte
native hash test is opt-in. See [verification progress](../PROGRESS.md) for the
exact device command and any skipped checks.
