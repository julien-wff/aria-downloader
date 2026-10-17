#!/usr/bin/env python3
"""Cross-build pinned release archives. Host packages never supply Android libraries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tomllib
import urllib.request

MODULE = Path(__file__).resolve().parents[1]
CATALOG = MODULE.parent / "gradle/libs.versions.toml"
SOURCES = Path(__file__).with_name("sources.json")
ABIS = {"arm64-v8a": ("aarch64-linux-android", "android-arm64"),
        "x86_64": ("x86_64-linux-android", "android-x86_64")}


def run(args, cwd, env):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def digest(path):
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()


def fetch_sources(root, versions):
    result = {}
    for name, spec in json.loads(SOURCES.read_text()).items():
        version = versions[name]
        url = spec["url"].format(version=version, underscore=version.replace(".", "_"))
        archive = root / "archives" / url.rsplit("/", 1)[1]
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.exists():
            temporary = archive.with_suffix(archive.suffix + ".part")
            print("Downloading", url, flush=True)
            with urllib.request.urlopen(url, timeout=120) as source, temporary.open("wb") as target:
                shutil.copyfileobj(source, target)
            if digest(temporary) != spec["sha256"]:
                temporary.unlink()
                raise RuntimeError(f"Checksum mismatch: {name}")
            temporary.replace(archive)
        if digest(archive) != spec["sha256"]:
            raise RuntimeError(f"Checksum mismatch: {archive}; remove the corrupt archive")
        source_dir = root / "sources" / f"{name}-{version}"
        if not source_dir.exists():
            unpack = source_dir.with_name(source_dir.name + ".unpack")
            shutil.rmtree(unpack, ignore_errors=True)
            unpack.mkdir(parents=True)
            with tarfile.open(archive) as tar:
                tar.extractall(unpack, filter="data")
            entries = list(unpack.iterdir())
            if len(entries) != 1 or not entries[0].is_dir():
                raise RuntimeError(f"Unexpected archive layout: {name}")
            entries[0].rename(source_dir)
            unpack.rmdir()
        result[name] = source_dir
    return result


def build(sdk, abi, root, sources, versions, jobs):
    triple, openssl_target = ABIS[abi]
    ndk = sdk / "ndk" / versions["ndk"]
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    cmake = sdk / "cmake" / versions["cmake"] / "bin/cmake"
    prefix = root / "install" / abi
    stamp = prefix / ".fingerprint"
    native_versions = {name: versions[name] for name in (*sources, "ndk", "cmake")}
    fingerprint = hashlib.sha256(Path(__file__).read_bytes() + SOURCES.read_bytes() +
                                 json.dumps(native_versions, sort_keys=True).encode()).hexdigest()
    if stamp.exists() and stamp.read_text() == fingerprint:
        return
    work = root / "work" / abi
    shutil.rmtree(work, ignore_errors=True)
    shutil.rmtree(prefix, ignore_errors=True)
    work.mkdir(parents=True)
    prefix.mkdir(parents=True)
    env = os.environ.copy()
    for key in ("CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "LIBRARY_PATH",
                "CMAKE_PREFIX_PATH", "PKG_CONFIG_PATH", "CFLAGS", "CXXFLAGS", "LDFLAGS"):
        env.pop(key, None)
    env.update(PATH=f"{tools}:{env['PATH']}", ANDROID_NDK_ROOT=str(ndk),
               CC=str(tools / f"{triple}35-clang"), CXX=str(tools / f"{triple}35-clang++"),
               AR=str(tools / "llvm-ar"), RANLIB=str(tools / "llvm-ranlib"),
               STRIP=str(tools / "llvm-strip"), CFLAGS="-fPIC -O2",
               CXXFLAGS="-fPIC -O2", CPPFLAGS=f"-I{prefix}/include",
               LDFLAGS=f"-L{prefix}/lib -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096",
               PKG_CONFIG_LIBDIR=f"{prefix}/lib/pkgconfig", PKG_CONFIG_PATH="",
               SOURCE_DATE_EPOCH="1700000000")

    def cmake_build(name, flags):
        directory = work / name
        run([cmake, "-S", sources[name], "-B", directory, "-G", "Unix Makefiles",
             f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
             f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-35",
             "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release",
             "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", f"-DCMAKE_INSTALL_PREFIX={prefix}",
             "-DCMAKE_INSTALL_LIBDIR=lib", *flags], work, env)
        run([cmake, "--build", directory, "--parallel", jobs], work, env)
        run([cmake, "--install", directory], work, env)

    openssl = work / "openssl"
    shutil.copytree(sources["openssl"], openssl)
    run(["perl", "Configure", openssl_target, "-D__ANDROID_API__=35", "no-shared", "no-tests", "no-apps",
         "no-module", "no-dso", f"--prefix={prefix}", "--libdir=lib"], openssl, env)
    run(["make", f"-j{jobs}"], openssl, env)
    run(["make", "install_sw"], openssl, env)
    cmake_build("zlib", ["-DZLIB_BUILD_TESTING=OFF", "-DZLIB_BUILD_SHARED=OFF"])
    cmake_build("cares", ["-DCARES_STATIC=ON", "-DCARES_SHARED=OFF",
                          "-DCARES_BUILD_TESTS=OFF", "-DCARES_BUILD_TOOLS=OFF"])
    cmake_build("expat", ["-DEXPAT_SHARED_LIBS=OFF", "-DEXPAT_BUILD_TESTS=OFF",
                          "-DEXPAT_BUILD_TOOLS=OFF", "-DEXPAT_BUILD_EXAMPLES=OFF",
                          "-DEXPAT_BUILD_DOCS=OFF"])
    # zlib's upstream CMake may also install a shared target. Only static native
    # dependencies belong in libaria2; prevent configure from selecting a .so.
    for path in (prefix / "lib").glob("*.so*"):
        path.unlink()
    directory = work / "aria2"
    directory.mkdir()
    # aria2's old libtool C++ driver uses -nostdlib. Explicitly supply Clang's
    # builtins so arm64 outlined atomics are resolved inside the shared library.
    builtins = subprocess.check_output([env["CC"], "--print-libgcc-file-name"], text=True).strip()
    env["LIBS"] = f"-ldl {builtins}"
    env["LDFLAGS"] += " -Wl,--no-undefined"
    run([sources["aria2"] / "configure", f"--host={triple}", f"--prefix={prefix}",
         "--enable-libaria2", "--enable-shared", "--disable-static", "--disable-nls",
         "--disable-dependency-tracking", "--enable-bittorrent", "--enable-metalink",
         "--with-openssl", "--without-gnutls", "--without-libgcrypt", "--without-libgmp",
         "--without-libssh2", "--without-libxml2", "--without-sqlite3",
         "--with-libexpat", "--with-libcares", "--with-libz"], directory, env)
    # Android packages .so filenames without Unix version suffixes. The SONAME
    # must match the APK entry, including libaria2's DT_NEEDED from JNI.
    run(["make", f"-j{jobs}", "AM_LDFLAGS=-avoid-version"], directory, env)
    run(["make", "install", "AM_LDFLAGS=-avoid-version"], directory, env)
    licenses = prefix / "licenses"
    licenses.mkdir()
    for name, source in sources.items():
        for pattern in ("COPYING*", "LICENSE*", "LICENCE*", "NOTICE*"):
            for path in source.glob(pattern):
                if path.is_file():
                    shutil.copyfile(path, licenses / f"{name}-{path.name}")
    stamp.write_text(fingerprint)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--abi", choices=ABIS, action="append")
    parser.add_argument("--jobs", type=int, default=min(os.cpu_count() or 2, 8))
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    for tool in ("make", "perl", "pkg-config", "gcc"):
        if not shutil.which(tool):
            parser.error(f"Missing host dependency: {tool}; see native/README.md")
    versions = tomllib.loads(CATALOG.read_text())["versions"]
    root = MODULE / "build/native"
    sources = fetch_sources(root, versions)
    for abi in args.abi or ABIS:
        build(args.sdk.resolve(), abi, root, sources, versions, args.jobs)


if __name__ == "__main__":
    main()
