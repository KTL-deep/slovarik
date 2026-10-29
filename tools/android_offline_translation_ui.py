"""Check the built-in EN->RU suggestion in the real UI without saving a card.
Requires the English/Russian language pair in Settings. Restores connectivity.
"""
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
adb = [str(sdk / "platform-tools/adb.exe"), "-s", "emulator-5580"]


def run(*parts, binary=False):
    result = subprocess.run(adb + list(parts), capture_output=True, timeout=40, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()


def shell(*parts):
    return run("shell", " ".join(shlex.quote(part) for part in parts))


def tree():
    shell("uiautomator", "dump", "/sdcard/slovarik-offline.xml")
    return ET.fromstring(shell("cat", "/sdcard/slovarik-offline.xml"))


def wait(text):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        node = next((n for n in tree().iter("node") if n.get("text") == text), None)
        if node is not None:
            return node
        time.sleep(0.3)
    raise AssertionError(f"Missing UI label: {text!r}")


def touch(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


raw = run("exec-out", "run-as", "com.slovarik.app", "cat", "databases/slovarik.db", binary=True)
with sqlite3.connect(":memory:") as db:
    db.deserialize(raw)
    candidate = db.execute("SELECT f.name,d.name FROM folders f JOIN decks d ON d.folder_id=f.id ORDER BY d.id LIMIT 1").fetchone()
assert candidate, "A deck is needed for this read-only UI check"
previous = {"wifi": shell("settings", "get", "global", "wifi_on"),
            "data": shell("settings", "get", "global", "mobile_data")}
try:
    for key in previous:
        shell("svc", key, "disable")
    shell("am", "force-stop", "com.slovarik.app")
    shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
    touch(wait(candidate[0]))
    touch(wait(candidate[1]))
    touch(wait("Добавить карточку"))
    field = next(n for n in tree().iter("node") if n.get("class") == "android.widget.EditText")
    touch(field)
    shell("input", "text", "book")
    touch(wait("Перевести офлайн"))
    result = ""
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        fields = [n for n in tree().iter("node") if n.get("class") == "android.widget.EditText"]
        if len(fields) > 1:
            result = fields[1].get("text", "").lower()
        if "книг" in result:
            break
        time.sleep(0.5)
    assert "книг" in result, "Built-in offline suggestion was not rendered"
    image = Path.home() / "AppData/Local/Temp/opencode/slovarik-bundled-translation.png"
    image.write_bytes(run("exec-out", "screencap", "-p", binary=True))
    touch(wait("Отмена"))
    print("PASS: built-in OPUS-MT translation rendered in the UI without network or saving a card")
    print(f"Screenshot: {image}")
finally:
    for key, value in previous.items():
        shell("svc", key, "enable" if value == "1" else "disable")
    shell("am", "force-stop", "com.slovarik.app")
    shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
