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
| Persistence and saving | Room migrations through v5, pending-entry crash windows, per-file journals, publication identity and public bytes    |
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
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --stop-mode force-stop
python3 tools/test-process-recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --stop-mode task-manager
```

Run these scenarios sequentially on a test device. The harness installs the APKs, seeds two
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
