#!/usr/bin/env python3
"""Run the opt-in on-device HTTP benchmark and summarize repeated timings."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--repetitions", type=int, default=3, choices=range(1, 11))
    parser.add_argument("--output", type=Path, default=Path("/tmp/aria-http-benchmark.json"))
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    root = Path(__file__).resolve().parents[1]
    # Android library instrumentation is self-targeting; there is no library app APK.
    apk = root / "aria2/build/outputs/apk/androidTest/debug/aria2-debug-androidTest.apk"
    subprocess.run(adb + ["install", "-r", "-t", str(apk)], check=True)
    user = subprocess.run(adb + ["shell", "am", "get-current-user"], text=True, capture_output=True, check=True).stdout.strip()
    if not user.isdigit():
        raise RuntimeError("Unable to identify the active Android user")
    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "--user", user, "-e", "class",
        "com.julien_wff.aria_android.aria2.NativeEngineTest#httpThroughputBenchmark",
        "-e", "benchmarkRepetitions", str(args.repetitions),
        "com.julien_wff.aria_android.aria2.test/androidx.test.runner.AndroidJUnitRunner"],
        text=True, capture_output=True, check=True)
    rows = [json.loads(line.split("ariaHttpBenchmark=", 1)[1]) for line in result.stdout.splitlines()
            if "INSTRUMENTATION_STATUS: ariaHttpBenchmark=" in line]
    args.output.write_text(json.dumps(rows, indent=2) + "\n")
    if "OK (1 test)" not in result.stdout or len(rows) != args.repetitions * 24:
        raise RuntimeError(f"Incomplete benchmark ({len(rows)} samples): {result.stdout}\n{result.stderr}")
    print("fixture,connections,split,direct,median_download_ms,median_publication_ms,median_total_ms")
    for key in sorted({(r["fixture"], r["connections"], r["split"], r["direct"]) for r in rows}):
        group = [r for r in rows if (r["fixture"], r["connections"], r["split"], r["direct"]) == key]
        medians = [statistics.median(r[field] for r in group) for field in ("downloadMs", "publicationMs", "totalMs")]
        print(",".join(map(str, (*key, *(round(v, 1) for v in medians)))))
    print(f"Raw samples: {args.output}")


if __name__ == "__main__":
    main()
