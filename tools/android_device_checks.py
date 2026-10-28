"""Run instrumented checks without Gradle uninstalling the user's application.

Build assembleDebug/assembleDebugAndroidTest first, install the main APK, then run.
Only the test APK is installed here. The target APK and its data are not removed.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--class-name", default="com.slovarik.app.BundledTranslatorDeviceTest")
parser.add_argument("--offline", action="store_true")
args = parser.parse_args()
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
adb = [str(sdk / "platform-tools/adb.exe"), "-s", "emulator-5580"]
root = Path(__file__).resolve().parents[1]


def run(*parts, timeout=180):
    result = subprocess.run(adb + list(parts), capture_output=True, timeout=timeout)
    text = (result.stdout + result.stderr).decode("utf-8", errors="replace").strip()
    if result.returncode:
        raise RuntimeError(text)
    return text


print(run("install", "-r", str(root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")))
previous = {"wifi": run("shell", "settings", "get", "global", "wifi_on"),
            "data": run("shell", "settings", "get", "global", "mobile_data")}
try:
    if args.offline:
        for key in previous:
            run("shell", "svc", key, "disable")
    result = run("shell", "am", "instrument", "-w", "-e", "class", args.class_name,
                 "com.slovarik.app.test/androidx.test.runner.AndroidJUnitRunner")
    print(result)
    assert re.search(r"OK \(\d+ tests?\)", result), "Instrumented checks failed"
finally:
    if args.offline:
        for key, value in previous.items():
            run("shell", "svc", key, "enable" if value == "1" else "disable")
    run("shell", "am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
