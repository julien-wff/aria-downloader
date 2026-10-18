#!/usr/bin/env python3
"""Release tag validation, ephemeral signing setup and verified APK packaging."""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[1]


def version(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})", tag)
    if not match:
        raise ValueError("Expected a canonical vX.Y.Z release tag")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if major > 2099 or code == 0:
        raise ValueError("Release version must be positive; major must be <= 2099")
    return tag[1:], code


def versions():
    return tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]


def sdk_packages(config):
    return [
        "platform-tools", f"platforms;android-{config['androidPlatform']}",
        f"build-tools;{config['buildTools']}", f"ndk;{config['ndk']}",
        f"cmake;{config['cmake']}",
    ]


def prepare_signing(directory, environment):
    required = (
        "ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
    )
    missing = [name for name in required if not environment.get(name, "").strip()]
    if missing:
        raise ValueError("Missing release secrets: " + ", ".join(missing))
    # Decode fully before writing; diagnostics never include supplied values.
    try:
        data = base64.b64decode(environment["ANDROID_KEYSTORE_BASE64"], validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("ANDROID_KEYSTORE_BASE64 is not valid base64") from None
    if not data:
        raise ValueError("Release keystore is empty")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = directory / "release.jks"
    with path.open("xb") as target:
        path.chmod(0o600)
        target.write(data)
    return path.resolve()


def package(apk, sdk, tag, destination):
    name, code = version(tag)
    tools = sdk / "build-tools" / versions()["buildTools"]
    subprocess.run([
        "java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk,
    ], check=True)
    badging = subprocess.check_output([tools / "aapt", "dump", "badging", apk], text=True)
    expected = (f"name='com.julien_wff.aria_android' versionCode='{code}' "
                f"versionName='{name}'")
    if expected not in badging or "application-debuggable" in badging:
        raise ValueError("APK must be a non-debuggable release with the expected package and version")
    subprocess.run([
        "python3", ROOT / "aria2/native/verify_apk.py", "--sdk", sdk, "--apk", apk,
    ], check=True)
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / f"aria-downloader-{tag}.apk"
    target.write_bytes(apk.read_bytes())
    with target.open("rb") as source:
        checksum = hashlib.file_digest(source, "sha256").hexdigest()
    (destination / "SHA256SUMS").write_text(f"{checksum}  {target.name}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    tag_parser = commands.add_parser("version")
    tag_parser.add_argument("tag")
    commands.add_parser("config")
    sdk_parser = commands.add_parser("install-sdk")
    sdk_parser.add_argument("--sdk", required=True, type=Path)
    signing_parser = commands.add_parser("prepare-signing")
    signing_parser.add_argument("--directory", required=True, type=Path)
    package_parser = commands.add_parser("package")
    package_parser.add_argument("--apk", required=True, type=Path)
    package_parser.add_argument("--sdk", required=True, type=Path)
    package_parser.add_argument("--tag", required=True)
    package_parser.add_argument("--destination", default=ROOT / "dist", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "version":
            name, code = version(args.tag)
            print(f"version_name={name}\nversion_code={code}")
        elif args.command == "config":
            print(f"jdk={versions()['jdk']}")
        elif args.command == "install-sdk":
            config = versions()
            manager = args.sdk / "cmdline-tools/latest/bin/sdkmanager"
            command = [str(manager), f"--sdk_root={args.sdk}"]
            subprocess.run(command + ["--licenses"], input="y\n" * 100, text=True, check=True)
            subprocess.run(command + sdk_packages(config), check=True)
        elif args.command == "prepare-signing":
            print(prepare_signing(args.directory, os.environ))
        else:
            package(args.apk.resolve(), args.sdk.resolve(), args.tag, args.destination)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release preparation failed: {error}\n")


if __name__ == "__main__":
    main()
