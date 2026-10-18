# GitHub Release builds

Publishing a GitHub Release runs `.github/workflows/release.yml`. It builds the tagged commit, runs
debug build/lint and all module JVM tests, assembles and verifies a signed release APK, then
attaches these assets to the existing release:

- `aria-downloader-vX.Y.Z.apk`: one APK containing arm64-v8a and x86_64.
- `SHA256SUMS`: SHA-256 checksum for that APK.

The repository is public, uses `main`, and has GitHub Actions enabled. The workflow requests
`contents: write` for its release job; the repository's read-only token default does not need
changing. No Play Store account, personal access token, pull-request pipeline or push pipeline is
involved.

## One-time signing setup

Create a release key on a trusted machine. These commands assume Linux/Bash and keep the key outside
the checkout. `keytool` prompts for the password and identity; use the same password for both
password secrets below because PKCS12 uses one password. Do not use the development debug key.

```sh
release_key_dir="$HOME/.local/share/aria-signing"
install -d -m 700 "$release_key_dir"
keytool -genkeypair -keystore "$release_key_dir/aria-release.jks" \
  -storetype PKCS12 -alias aria-release -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$release_key_dir/aria-release.jks"
```

Back up the keystore, alias and password offline before publishing. Every future APK must use this
same key for Android to accept updates. GitHub secrets are not a recoverable backup. Existing
debug-signed installations must be uninstalled before installing the first release APK, which
removes their app-private data.

Authenticate `gh` to the repository, then configure the four repository secrets:

```sh
gh secret set ANDROID_KEYSTORE_BASE64 --repo julien-wff/aria-downloader \
  < <(base64 -w 0 "$release_key_dir/aria-release.jks")
gh secret set ANDROID_KEYSTORE_PASSWORD --repo julien-wff/aria-downloader
gh secret set ANDROID_KEY_ALIAS --repo julien-wff/aria-downloader
gh secret set ANDROID_KEY_PASSWORD --repo julien-wff/aria-downloader
gh secret list --repo julien-wff/aria-downloader
```

Enter `aria-release` for the alias and the keystore password for both password prompts. Commands
omit secret values from command-line arguments and shell history. CI decodes the key into
`$RUNNER_TEMP/aria-signing`, limits file permissions and removes it in an always-run cleanup step.
Signing builds disable Gradle's configuration cache; signing material is excluded from caches and
report uploads. Missing credentials fail the release build without uploading an unsigned APK.

## Publish a release

The workflow must exist in the tagged commit. Use a canonical tag such as `v1.0.0`:
major is 0–2099, minor/patch are 0–999, with no leading zeroes. `v0.0.0` and suffixes such as `-rc1`
are rejected. A GitHub Release marked as a prerelease can still use this tag format.

Version name is `X.Y.Z`; version code is `major * 1000000 + minor * 1000 + patch`. For example,
`v1.2.3` produces `1.2.3` / `1002003`. Publish increasing versions for installable upgrades.
Ordinary local debug builds retain their existing version.

Create and publish a release through GitHub's Releases page, or explicitly run:

```sh
gh release create v1.0.0 --repo julien-wff/aria-downloader \
  --target main --title v1.0.0 --generate-notes
gh run list --repo julien-wff/aria-downloader --workflow release.yml
gh run view RUN_ID --repo julien-wff/aria-downloader --log-failed
gh release view v1.0.0 --repo julien-wff/aria-downloader
```

Drafts do not trigger builds; publishing them does. Keep release immutability disabled: this
workflow attaches assets after the release is already published. Creating only a tag does not
trigger the workflow. Releases created by another workflow using its default `GITHUB_TOKEN` do not
trigger additional workflows; create the release manually or through your authenticated local `gh`
session.

The release initially exists without APK assets while CI runs. Failure leaves the release in place,
with no newly uploaded assets from a failed verification. Inspect the Actions log and the retained
verification reports, correct the issue, then rerun the failed job with
`gh run rerun RUN_ID --failed`. If code must change, use a new release tag pointing to the fix.
Reruns serialize per release and overwrite only the managed APK and checksum assets; other release
assets and notes remain. An upload transport failure can leave one asset present; rerun to finish
both.

## Verify assets and local builds

After the first real release, inspect its successful workflow run and download its assets into a
fresh directory:

```sh
gh release download v1.0.0 --repo julien-wff/aria-downloader \
  --pattern '*.apk' --pattern SHA256SUMS --dir /tmp/aria-release-check
(cd /tmp/aria-release-check && sha256sum --check SHA256SUMS)
java -jar "$ANDROID_HOME/build-tools/37.0.0/lib/apksigner.jar" verify --verbose --print-certs \
  /tmp/aria-release-check/aria-downloader-v1.0.0.apk
```

The workflow verifies the expected package, version and non-debuggable manifest, APK signing, both
native ABIs, dependencies and 16 KiB ELF/ZIP alignment before upload. Compare the printed
signing-certificate SHA-256 fingerprint with the release key's `keytool -list -v` output. Install
the APK on Android 15+ and perform a download/publication smoke test; device acceptance is separate
from hosted CI.

For a local signed build, provide `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` through your
environment. Use the key file's absolute path, never commit credentials, and disable the
configuration cache:

```sh
./gradlew --no-configuration-cache :app:assembleRelease \
  -PreleaseVersionName=1.0.0 -PreleaseVersionCode=1000000
python3 tools/release.py package --sdk "$ANDROID_HOME" --tag v1.0.0 \
  --apk app/build/outputs/apk/release/app-release.apk
python3 -m unittest discover -s tools/tests -v
```

Build tool and SDK versions live in `gradle/libs.versions.toml`. CI's cache keys cover the native
sources manifest, native scripts, Gradle inputs and runner platform. The API level
(`androidSdk = 37`) and SDK package suffix (`androidPlatform = 37.0`) are distinct: SDK Manager
requires
`platforms;android-37.0` for this platform.

Project licensing and corresponding-source distribution remain unresolved. The APK workflow handles
signing and asset publication; it does not select a project license or provide a complete
source-distribution process. Native dependency notices and pinned source records are described in
the
[native guide](../aria2/native/README.md#source-and-notices).
