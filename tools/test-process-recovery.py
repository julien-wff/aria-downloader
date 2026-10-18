#!/usr/bin/env python3
"""Opt-in device acceptance: kill two live UIDT transfers, resume, verify both public hashes."""
import argparse
import hashlib
import http.server
from pathlib import Path
import subprocess
import threading
import time
import uuid
from urllib.parse import urlsplit, parse_qs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="ADB device serial (required when several devices are attached)")
    parser.add_argument("--skip-install", action="store_true", help="Reuse already-installed matching app and test APKs")
    parser.add_argument("--stop-mode", choices=("sigkill", "force-stop", "task-manager"), default="sigkill",
                        help="How to interrupt Aria only; user stops must require explicit Resume")
    options = parser.parse_args()
    adb = [options.adb] + (["-s", options.serial] if options.serial else [])
    package = "com.julien_wff.aria_android"
    test_class = package + ".ProcessDeathRecoveryTest"
    payload = (bytes(range(251)) * ((32 * 1024 * 1024 // 251) + 1))[:32 * 1024 * 1024]
    ranges = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            request = urlsplit(self.path)
            task = parse_qs(request.query).get("recoveryTask", [""])[0]
            if request.path != "/file":
                self.send_error(404)
                return
            start, end = 0, len(payload) - 1
            requested = self.headers.get("Range")
            if requested:
                first, last = requested.removeprefix("bytes=").split("-", 1)
                start = int(first)
                if last:
                    end = min(int(last), end)
                ranges.append((task, start))
            self.send_response(206 if requested else 200)
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            if requested:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(payload)}")
            self.end_headers()
            try:
                for offset in range(start, end + 1, 64 * 1024):
                    self.wfile.write(payload[offset:min(offset + 64 * 1024, end + 1)])
                    self.wfile.flush()
                    time.sleep(0.1)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def run(*arguments):
        result = subprocess.run(adb + list(arguments), text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError(f"ADB {arguments[0]} failed ({result.returncode}): {result.stdout}{result.stderr}")
        return result.stdout

    user = run("shell", "am", "get-current-user").strip()
    if not user.isdigit():
        raise RuntimeError("Unable to identify the active Android user")
    root = Path(__file__).resolve().parents[1]
    if not options.skip_install:
        for apk in ("app/build/outputs/apk/debug/app-debug.apk",
                    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
            print(run("install", "-r", "-t", str(root / apk)), end="", flush=True)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_port
    name = f"recovery-{uuid.uuid4()}.bin"
    try:
        run("reverse", f"tcp:{port}", f"tcp:{port}")
        run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        run("shell", "wm", "dismiss-keyguard")
        def instrument(phase, method):
            return ["shell", "am", "instrument", "-w", "--user", user, "-e", "class", f"{test_class}#{method}",
                    "-e", "recoveryPhase", phase, "-e", "recoveryUrl", f"http://127.0.0.1:{port}/file",
                    "-e", "recoveryName", name, "-e", "expectUserStop", str(options.stop_mode != "sigkill").lower(),
                    "-e", "recoveryHash", hashlib.sha256(payload).hexdigest(),
                    f"{package}.test/androidx.test.runner.AndroidJUnitRunner"]

        seed = subprocess.Popen(adb + instrument("seed", "seedRunningTransfer"),
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 90
            while True:
                marker = subprocess.run(adb + ["shell", "run-as", package, "cat", "no_backup/process-recovery-ready"],
                                        text=True, capture_output=True)
                if marker.returncode == 0 and marker.stdout.strip() == name:
                    break
                if seed.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Seed phase exited or timed out before a durable checkpoint")
                time.sleep(0.25)
            run("shell", "input", "keyevent", "KEYCODE_HOME")
            pids = run("shell", "pidof", package).strip().split()
            if len(pids) != 1:
                raise RuntimeError(f"Expected one live target process, got {pids}")
            if options.stop_mode == "force-stop":
                run("shell", "am", "force-stop", package)
            elif options.stop_mode == "task-manager":
                run("shell", "cmd", "activity", "stop-app", package)
            else:
                run("shell", "run-as", package, "kill", "-9", pids[0])
            print(f"Interrupted live transfers after checkpoints: {options.stop_mode}.", flush=True)
            output, _ = seed.communicate(timeout=20)
            print(output, flush=True)  # The killed seed runner is expected to report process death.
        finally:
            if seed.poll() is None:
                seed.terminate()
                seed.wait(timeout=10)
        ranges.clear()
        result = run(*instrument("recover", "recoverAndVerifyPublishedContent"))
        print(result, flush=True)
        if "OK (1 test)" not in result:
            raise RuntimeError("Recovery instrumentation did not pass")
        if not all(any(task == expected and offset > 0 for task, offset in ranges) for expected in ("0", "1")):
            raise RuntimeError("Recovery did not issue non-zero range requests for both tasks")
        print(f"PASS: two transfers recovered after {options.stop_mode}; both public SHA-256 hashes matched; range resume verified.")
    finally:
        run("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
