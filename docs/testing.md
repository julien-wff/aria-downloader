# Testing and verification

## Standard checks

For application or build changes, run build and lint. Add relevant module JVM tests when behavior
changes:

```sh
./gradlew assembleDebug lintDebug \
  :app:testDebugUnitTest :downloads:testDebugUnitTest :aria2:testDebugUnitTest
```

Use `gradlew.bat` on Windows; native builds require a supported Linux host. Device tests need a
connected Android 15+ device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

Keep the device unlocked for clipboard/UI coverage. Background tests can lock the screen, so run
clipboard checks separately if necessary. Inspect instrumentation results for skips, installation
errors and assumptions as well as Gradle's exit status. A skipped test is not a passed test. Prefer
USB or wireless mDNS serials for `ANDROID_SERIAL`; the current AGP runner can misreport IP:port
identifiers.

## Coverage by subsystem

| Subsystem              | Relevant coverage                                                                                                    |
|------------------------|----------------------------------------------------------------------------------------------------------------------|
| Queue and recovery     | Controller/queue JVM tests, virtual-time retry tests, stale leases, persistence faults and host interruption harness |
| Persistence and saving | Room migrations through v6, pending-entry crash windows, per-file journals, publication identity and public bytes    |
| Native engine          | Controlled HTTP/HTTPS servers, TLS rejection, range resume, checkpoints, torrent/magnet peers and Metalink mirrors   |
| UI                     | Intake, file selection, recreation, autosave, keyboard navigation, responsive layouts and accessible insights        |
| Insights               | JVM history/duration tests, native bitfield aggregation, protocol map transitions and notification ETAs              |
| Release tooling        | Python version/signing/packaging tests and APK verification                                                          |

Use controlled fixtures and verify public-file content hashes. Engine completion or a finished
progress indicator alone does not verify successful saving.

The native piece aggregator has a standalone C++17 test:

```sh
c++ -std=c++17 -Wall -Wextra -Werror aria2/src/test/cpp/piece_overview_test.cpp -o /tmp/aria-piece-overview-test
/tmp/aria-piece-overview-test
```

Release-tool tests run without signing credentials:

```sh
python3 -m unittest discover -s tools/tests -v
```

## Process interruption harness

Build the debug app and test APK first:

```sh
./gradlew assembleDebug :app:assembleDebugAndroidTest
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb"
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --skip-install --stop-mode force-stop
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --skip-install --stop-mode task-manager
```

Run these scenarios sequentially on a test device. Use `--skip-install` only when matching APKs are
already installed; otherwise omit it. The harness installs the APKs, seeds two
transfers, interrupts Aria, checks non-zero range resumes and verifies both public-file hashes.
User-stop scenarios also verify that opening Aria leaves tasks paused until explicit Resume. The
killed seed runner reports process death by design. The local server and ADB reverse mapping are
cleaned up afterward. Pass `--serial` when multiple devices are connected. Host recovery tests are
excluded from ordinary instrumentation by the `HostRecovery` annotation.

## Device coverage and remaining gaps

Use the [reliability matrix](reliability.md#device-matrix) for physical reboot, Wi-Fi/mobile/offline
transitions, user stop, permission denial and interrupted saving. Android 15 runtime coverage,
physical reboot/network transitions, physical tablet and full TalkBack review remain manual coverage
gaps. Real MediaStore consent dialogs need manual review; disk exhaustion is simulated through fault
injection. Private DNS/VPN routing remains unvalidated.

16 KiB runtime acceptance remains outstanding. APK ELF/ZIP alignment checks do not replace execution
on a 16 KiB device. See the [native guide](../aria2/native/README.md#device-acceptance)
for emulator setup and alignment verification.

The native 4 GiB + 64 KiB hash test is opt-in and needs at least 6 GiB free staging space. It is
excluded from default runs and must be requested explicitly:

```sh
./gradlew :aria2:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.largeFile=true
```

Responsive UI tests use local size/font/RTL overrides, which do not replace checks on real devices.
See [visual review](design/icons.md#visual-review) for captures. Release installation and runtime
smoke tests are separate from hosted build checks;
see [release verification](releases.md#verify-assets-and-local-builds).

When reporting results, give the commands actually run and identify exclusions, skips and blockers.
Documentation-only edits need link checks, source review and
`git diff --check`; they do not require rebuilding the app.

## HTTP storage and throughput

`NativeEngineTest` checks descriptor-backed payload hashes, redirects, unknown lengths, servers
without ranges, resume after engine recreation, and preservation when checkpoints are missing.
Queue JVM tests reject admission from restored snapshots before authorized engine startup.
`PersistenceAndExportTest` covers direct pending identities, revoked publication authorization,
missing entries and publication commit recovery without copying. It also verifies root-level pending
files, final filenames/MIME types, legacy folder recovery and cleanup that preserves pending bytes. `QueuePersistenceTest` verifies
that v5 migration preserves staged storage and that direct destination identity survives reopening.

The descriptor registry also has a host check:

```sh
c++ -std=c++17 -Wall -Wextra -Werror aria2/src/test/cpp/http_output_test.cpp -o /tmp/aria-http-output-test
/tmp/aria-http-output-test
```

The opt-in HTTP benchmark is excluded from normal device tests by the `HttpBenchmark` annotation.
Build its APKs, then run it on an unlocked device:

```sh
./gradlew :aria2:assembleDebug :aria2:assembleDebugAndroidTest
python3 tools/benchmark-http.py --adb "$ANDROID_HOME/platform-tools/adb"
```

It verifies hashes and reports startup, download, publication and total times for 24 MiB files,
one/four connections, 20/4 MiB split thresholds, and staged/direct MediaStore destinations. Fixtures
cover unrestricted ranges, per-connection throttling and no ranges. Three rounds alternate option
ordering. Raw JSON defaults to `/tmp/aria-http-benchmark.json`; use `--output` to keep another copy.
These loopback measurements isolate engine/storage behavior; they do not establish parity with a
browser or measure a real CDN, Wi-Fi, TLS or mobile network. Use the same phone, source URL and network
for a separate real-world comparison. The host interruption harness also exercises direct HTTP
storage through production UIDT authorization and verifies both public hashes.

On a connected SM-S948B running Android 17, the three-round 24 MiB fixture benchmark measured
median download completion of 6.80 s at four connections/20 MiB versus 1.71 s at four connections/4 MiB
for direct transfers with per-connection throttling. Direct publication took about 4–13 ms versus
21–37 ms for staged copy/publication across the measured cases. The non-range direct fixture's
median download time increased from 74 to 76 ms with the lower threshold, an approximately 2 ms
loopback overhead. These results support the HTTP 4 MiB default for range-capable throttled sources;
they do not establish a universal speedup or browser parity.

On devices that kill instrumentation when a granted notification permission is revoked, prepare the
app suite with notifications already denied. Install without `-g`, revoke before instrumentation,
and select the active user explicitly (replace `0` below with `adb shell am get-current-user` when
using another profile):

```sh
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm revoke com.julien_wff.aria_android android.permission.POST_NOTIFICATIONS
adb shell am instrument -w --user 0 -e notAnnotation com.julien_wff.aria_android.HostRecovery \
  com.julien_wff.aria_android.test/androidx.test.runner.AndroidJUnitRunner
```

The clipboard test is skipped when the device remains locked. A successful instrumentation summary
can include this assumption; inspect the detailed output before reporting passed coverage.
