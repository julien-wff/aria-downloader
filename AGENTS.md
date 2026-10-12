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

The current bootstrap has no download engine. Do not present placeholder UI as a
working download feature.
