# Working on Aria Downloader

Aria is an Android 15+ download manager with an embedded aria2 engine. It supports HTTP/HTTPS,
torrents, magnets and HTTP/HTTPS-backed Metalink. Read
[product behavior](docs/product.md), [architecture](docs/architecture.md) and
[testing](docs/testing.md) before changing the relevant subsystem.

## Ownership and invariants

- Dependencies flow `:app` → `:downloads` → `:aria2`. The app owns Compose UI, ViewModels and UI
  wiring; downloads owns queue policy, persistence, Android jobs, notifications and storage; aria2
  owns the engine contract, JNI and native builds.
- Network work requires a matching user-initiated transfer job authorization. Startup, share receipt
  and metadata import never schedule new transfers. Reboot or user stop requires explicit Resume.
  Persist intent before acting.
- Protocol payloads require metadata review and explicit file selection. Validate untrusted URLs,
  metadata and relative paths. Stop post-download seeding.
  See [protocols](docs/advanced-protocols.md).
- Completion means successful MediaStore publication of every selected file. Staged or save-failed
  files are never publicly saved. Preserve stable recovery identities, partial files and restartable
  per-file publication journals.
- Keep native operations on the engine owner thread. Preserve TLS certificate and hostname
  verification; expose sanitized errors and allowlisted diagnostics only.
- Keep [insight telemetry](docs/transfer-insights.md) bounded and memory-only. Piece completion does
  not imply public saving.
- Preserve accessible controls, adaptive layouts, keyboard access and autosaving settings when
  changing the UI.

## Working rules

- Centralize and pin dependency versions in `gradle/libs.versions.toml`. Align the Compose compiler
  plugin with AGP's built-in Kotlin compiler.
- Preserve existing data with explicit Room migrations and recovery tests.
- Keep SDK paths, credentials and signing material out of shared configuration. Follow
  the [native guide](aria2/native/README.md) and
  [release guide](docs/releases.md) for those subsystems.
- Update the relevant documentation when behavior, setup or architecture changes. Describe the
  current state; do not maintain development timelines or roadmaps.

## Verification

Always verify before reporting completion. For application or build changes, run
`./gradlew assembleDebug lintDebug` (`.\gradlew.bat assembleDebug lintDebug` on Windows). Run
relevant tests when behavior changes; use
`connectedDebugAndroidTest` for device tests. See [testing](docs/testing.md) for JVM, native, device
and recovery checks.

Fix failures caused by your changes. If blocked, report the exact command, blocker and what remains
unverified. A skipped test is not a passed test. For docs-only changes, check links, source accuracy
and `git diff --check`.
