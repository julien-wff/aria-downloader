# Embedded engine development

This is milestone 1 in `plan.md`. `:aria2` packages an in-process engine; the
application still has no download UI or background execution authorization.

## Build

Native versions live in `gradle/libs.versions.toml`. `sources.json` records release
archive URLs, SHA-256 digests and license identifiers. Python 3.12+ verifies every
archive before extraction. The scripts currently support Linux x86_64 hosts.

Install the SDK platform/build tools documented in the root README, NDK
`30.0.16248370`, and SDK CMake `4.1.2`. The NDK supplies Android compilers,
binutils and libc++; do not install host development libraries as substitutes.
Host prerequisites are JDK 25, Python 3.12+, make, Perl, pkg-config, GCC, tar,
Autoconf, Automake, libtool, gettext, gperf and patch. Release archives include
generated configure scripts, so Autotools regeneration is normally unnecessary.

On NixOS, expose these packages through your existing configuration:

```nix
environment.systemPackages = with pkgs; [
  jdk25 python3 gnumake perl pkg-config gcc autoconf automake libtool
  gettext gperf patch gnutar unzip curl android-tools
];
programs.nix-ld.enable = true;
```

Generic Linux binaries in the Android SDK/NDK need nix-ld and compatible runtime
libraries. If the emulator reports `libX11.so.6` missing, add `libx11` to
`programs.nix-ld.libraries`. Keep SDK paths in `local.properties` or environment
variables, never shared Gradle configuration. No system configuration is changed
by this repository's scripts.

```sh
./gradlew assembleDebug lintDebug :aria2:testDebugUnitTest :aria2:assembleDebugAndroidTest
python3 aria2/native/verify_apk.py --sdk "$ANDROID_HOME" \
  --apk app/build/outputs/apk/debug/app-debug.apk
```

Gradle invokes `nativeDependencies` before CMake. For a standalone native build:

```sh
python3 aria2/native/build.py --sdk "$ANDROID_HOME"
# Optional development-only restriction:
python3 aria2/native/build.py --sdk "$ANDROID_HOME" --abi x86_64 --jobs 4
```

Archives, extracted sources, workspaces and install prefixes live under
`aria2/build/native`. Successful builds are cached by script, source manifest and
version-catalog fingerprints. `./gradlew clean` removes them. Source downloads
require network access; cached verified archives permit offline native builds.
The build fixes versions and build inputs; byte-for-byte reproducibility across
different host environments is not yet an acceptance claim.

OpenSSL, c-ares, Expat and zlib are static PIC dependencies of `libaria2.so`.
`libaria_jni.so` and the NDK's `libc++_shared.so` are packaged beside it. Both ABIs
target Android 35. HTTP/HTTPS, compression, async DNS, BitTorrent and Metalink
are compiled in. Torrent/Metalink intake and automatic metadata following are
disabled in the initial Kotlin interface. No RPC listener, DHT listener, external
binary download, SFTP dependency or host shared library is required at runtime.

## Engine contract

Create `NativeDownloadEngine(context)`, call `start()`, then `add(EngineRequest)`.
Collect immutable `snapshots`. The caller must later provide Android execution
authorization; instantiating this class alone does not grant background runtime.

- A single dedicated thread owns all native operations. Only one engine may be
  started per process. Native pointers never leave JNI.
  Library initialization lasts until JNI unload; session finalization is separate.
  libaria2 1.37's global preference registry cannot be deinitialized/reinitialized
  between sessions.
- `start()` and `stop()` are idempotent. `stop()` pauses remaining runnable tasks
  and releases process ownership. `close()` additionally releases the owner
  thread permanently. Other operations require a started engine.
- Task IDs remain stable within the instance. Each task gets a private directory
  under `noBackupFilesDir/aria2` (partial data and session URLs stay out of backup)
  and a fixed caller-validated filename; metadata cannot choose an output path.
  Options expose connection count and download speed limits only. Zero means no
  speed limit. URL credentials, non-HTTP(S) intake and unsafe filenames are rejected.
- `remove()` cancels work without deleting staged files. `:downloads` will own
  deletion and public export. Completion here means a staged file is complete.
- Native execution ends when all tasks are paused or terminal. Explicit resume
  reconstructs the session and uses the existing control files. Requests and
  task intent remain in memory; process-death reconciliation is a later milestone.
- `checkpoint()` finalizes and reconstructs the session to flush control files
  and native session metadata. This briefly reconnects active transfers.
  Pause/removal checkpoints and changes to an active task's connection count can
  likewise reconnect other active transfers in this initial engine implementation.
  Native checkpoints also run every five seconds. The native session file is
  diagnostic/resume metadata, not a replacement for future Room intent records;
  it is never blindly loaded at startup.
- System CA anchors come from AndroidCAStore's `system:` aliases and are exported
  into a private PEM bundle at each session creation. User-installed CAs are not
  trusted. Certificate and hostname checks stay enabled. Test-only CA injection
  is internal and is not a production option.
- DNS servers are taken from the current Android network when creating a session.
  Network changes during a session require checkpoint/restart for refresh; full
  network-policy coordination belongs to later milestones. Private DNS/VPN DNS
  routing is not yet validated. Native errors expose codes and generic messages,
  not URLs, headers or native exception text.

## Device acceptance

```sh
./gradlew :aria2:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
# Requires at least 6 GiB of free staging space:
./gradlew :aria2:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.largeFile=true
```

Tests host controlled loopback HTTP/HTTPS servers in the instrumentation process.
They verify SHA-256 content, Unicode filenames, redirects, unknown lengths,
range/no-range pause/resume, option updates, explicit checkpoints, TLS rejection,
and a length greater than 4 GiB crossing JNI. The opt-in large-file test transfers
and hashes an entire 4 GiB + 64 KiB file. It is skipped by default.

Range-capable servers must resume and produce the correct final hash. A server
that rejects resume produces aria2 code 8 and a readable failure while retaining
the partial file/control data; the engine does not silently overwrite it to
restart from zero.

The TLS assets are **public test fixtures**, with password `fixture-password`;
they contain no production credentials. The valid and wrong-host certificates
are valid from January 2026 to December 2035; the expired fixture expired in 2020.
They are packaged only in the instrumentation APK. Production trust is tested
separately by rejecting the fixture under actual system trust anchors.

Run the suite on both 4 KiB and 16 KiB page environments. Record
`adb shell getconf PAGE_SIZE` for each run. `verify_apk.py` checks every packaged
ELF's LOAD alignment and dynamic dependencies and invokes `zipalign -P 16`;
static inspection complements device execution and does not replace it.
For a 16 KiB emulator, install
`system-images;android-35;google_apis_ps16k;x86_64` and create an AVD with that
image. Increase its data partition if also running the full large-file test.

With AGP 9.4.1, prefer a USB or wireless mDNS device serial for `ANDROID_SERIAL`.
An `IP:port` serial can produce a failed Gradle task even when every device test
passes: the host runner compares its plain serial with a JUnit identifier that
encodes the colon. Use the mDNS serial shown by `adb devices` for that connection.

## Source and notices

Upstream source/license records are in `sources.json`. The build copies each
archive's license/notice files to `aria2/build/native/install/<abi>/licenses`.
aria2 is GPL-2.0-or-later with an OpenSSL linking exception; OpenSSL is Apache-2.0,
c-ares and Expat use MIT licenses, and zlib uses the Zlib license. Preserve these
notices and corresponding pinned source/build scripts when preparing a release.
This milestone does not complete the distribution/source-offer work in milestone 7.
