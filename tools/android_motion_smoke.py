"""Check motion-enabled and system-motion-disabled UI without grading any card.

Requires an installed app, a running emulator, and a captured read-only baseline:
    python tools/android_redesign_smoke.py --capture-baseline
    python tools/android_motion_smoke.py
All three Android animation-scale settings are restored, even on failure.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("Refusing to change animation settings on a physical device.")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
    adb = sdk / "platform-tools/adb.exe"
    keys = ("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
    source = Path.home() / "AppData/Local/Temp/opencode/slovarik-redesign"
    output = Path.home() / "AppData/Local/Temp/opencode/slovarik-motion"
    output.mkdir(parents=True, exist_ok=True)
    suite = Path(__file__).with_name("android_redesign_smoke.py")

    def shell(*parts: str) -> str:
        result = subprocess.run([str(adb), "-s", args.serial, "shell", *parts],
                                capture_output=True, timeout=40)
        if result.returncode:
            raise RuntimeError((result.stdout + result.stderr).decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8", errors="replace").strip()

    previous = {key: shell("settings", "get", "global", key) for key in keys}
    try:
        for label, scale in (("enabled", "1"), ("disabled", "0")):
            for key in keys:
                shell("settings", "put", "global", key, scale)
            subprocess.run([sys.executable, str(suite), "--serial", args.serial], check=True)
            destination = output / label
            destination.mkdir(exist_ok=True)
            for screenshot in source.glob("0[1-8]-*.png"):
                shutil.copy2(screenshot, destination / screenshot.name)
            print(f"PASS: all read-only UI flows with system motion {label}", flush=True)
    finally:
        failures = []
        for key, value in previous.items():
            try:
                if value == "null":
                    shell("settings", "delete", "global", key)
                else:
                    shell("settings", "put", "global", key, value)
            except RuntimeError as error:
                failures.append(f"{key}: {error}")
        if failures:
            raise RuntimeError("Could not restore Android animation settings: " + "; ".join(failures))
        shell("am", "force-stop", "com.slovarik.app")
        shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
    print(f"Screenshots: {output}")


if __name__ == "__main__":
    main()
