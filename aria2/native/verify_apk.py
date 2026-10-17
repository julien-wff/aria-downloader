#!/usr/bin/env python3
"""Check every packaged ELF library and APK native-library alignment."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    args = parser.parse_args()
    versions = tomllib.loads((Path(__file__).resolve().parents[2] / "gradle/libs.versions.toml").read_text())["versions"]
    readelf = args.sdk / "ndk" / versions["ndk"] / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temp:
        libraries = [name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")]
        if not libraries:
            raise RuntimeError("APK contains no native libraries")
        for name in libraries:
            if Path(name).parts[1] not in {"arm64-v8a", "x86_64"}:
                raise RuntimeError(f"Unsupported ABI packaged: {name}")
            path = Path(temp) / Path(name).name
            path.write_bytes(apk.read(name))
            headers = subprocess.check_output([readelf, "-lW", path], text=True)
            loads = [line for line in headers.splitlines() if line.strip().startswith("LOAD")]
            if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
                raise RuntimeError(f"ELF alignment below 16 KiB: {name}")
            dynamic = subprocess.check_output([readelf, "-dW", path], text=True)
            needed = re.findall(r"\(NEEDED\).*\[(.*?)\]", dynamic)
            for dependency in needed:
                if dependency not in system and str(Path(name).parent / dependency) not in libraries:
                    raise RuntimeError(f"Unpackaged dependency: {name} -> {dependency}")
            print(f"PASS {name}: 16 KiB ELF alignment; dependencies packaged/system")
        for abi in ("arm64-v8a", "x86_64"):
            for library in ("libaria2.so", "libaria_jni.so", "libc++_shared.so"):
                if f"lib/{abi}/{library}" not in libraries:
                    raise RuntimeError(f"Missing {abi}/{library}")
    zipalign = args.sdk / "build-tools/37.0.0/zipalign"
    subprocess.run([zipalign, "-c", "-P", "16", "4", args.apk], check=True)
    print("PASS APK 16 KiB ZIP alignment")


if __name__ == "__main__":
    main()
