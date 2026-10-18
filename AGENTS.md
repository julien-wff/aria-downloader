# Working on Aria Downloader

- Follow `plan.md` and keep work within the current milestone.
- Preserve module responsibilities: `:app` owns Compose UI and dependency wiring;
  `:downloads` owns persistence, scheduling, notifications and storage; `:aria2`
  owns the engine interface, JNI and native builds. Dependencies flow from
  `:app` to `:downloads` to `:aria2`.
- Always verify work before reporting completion. For application or build changes,
  run `./gradlew assembleDebug lintDebug` (`.\gradlew.bat assembleDebug lintDebug`
  on Windows). Run relevant tests when behavior changes; use
  `connectedDebugAndroidTest` for device tests.
- Fix verification failures caused by your changes. If verification is blocked,
  report the exact command, blocker, and what remains unverified. Never claim a
  check passed unless it ran successfully.
- Centralize and pin dependency versions in `gradle/libs.versions.toml`. Keep the
  Compose compiler plugin aligned with AGP's built-in Kotlin compiler.
- Keep local SDK paths, credentials and secrets out of shared configuration.
- Update documentation when setup or architecture changes.

Milestone 8 is current, scoped to transfer insights: a two-minute live-session speed
graph, a bounded actual-piece completion matrix, clearer state explanations, and
shared readable duration formatting in the app and notifications. Follow
docs/transfer-insights.md. Keep insight telemetry memory-only; piece completion
never implies successful public saving.

Milestone 7 is complete, scoped to GitHub Release automation: build and verify the
tagged commit, sign a universal APK with a persistent release key, and attach the
APK/checksums to the published release. No Play Store, PR or push pipeline.
Follow docs/releases.md; never commit signing material. Broader licensing/source
distribution remains separately tracked. Retain milestone 6's torrents, magnets
and HTTP/HTTPS-backed Metalink, with
metadata review, explicit file selection and restartable per-file public export.
The user explicitly authorized implementing the milestone 6 plan. Support links and
shared metadata files without a file picker. Group multi-file exports into task
folders, preserve safe relative paths, and stop post-download seeding. Retain
milestone 5's bounded retries, network constraints, interruption recovery and
previewable sanitized diagnostics. Retain milestone 4.5's
Material 3 Expressive UI, original vector launcher icon, accessible controls,
autosaving settings and keyboard-aware navigation/forms, along with the queue,
task details, history, share-sheet intake and per-download HTTP options.
Retain Room recovery, native checkpoints and restartable public Downloads export.
Remaining earlier acceptance gates do not block advancement; build, lint and relevant
tests still apply. Completion means successful MediaStore publication. Staged or
export-failed files must never be presented as publicly saved.
