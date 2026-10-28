"""Read-only UI regression check for the Android redesign.

Captures screenshots and verifies that vocabulary/review data is unchanged.
Does not grade cards, edit words, change reminders, or download models.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture-baseline", action="store_true")
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("Use a test emulator, not a physical device.")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
    adb = sdk / "platform-tools/adb.exe"
    out = Path.home() / "AppData/Local/Temp/opencode/slovarik-redesign"
    out.mkdir(parents=True, exist_ok=True)

    def run(*parts: str, binary: bool = False):
        result = subprocess.run([str(adb), "-s", args.serial, *parts], capture_output=True, timeout=40)
        if result.returncode:
            raise RuntimeError((result.stdout + result.stderr).decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()

    def shell(*parts: str) -> str:
        return run("shell", " ".join(shlex.quote(part) for part in parts))

    def data():
        raw = run("exec-out", "run-as", "com.slovarik.app", "cat", "databases/slovarik.db", binary=True)
        with sqlite3.connect(":memory:") as db:
            db.deserialize(raw)
            tables = {name: db.execute(f"SELECT * FROM {name} ORDER BY id").fetchall()
                      for name in ("folders", "decks", "cards", "events")}
            candidate = db.execute("SELECT f.name,d.name FROM folders f JOIN decks d ON d.folder_id=f.id "
                                   "WHERE EXISTS(SELECT 1 FROM cards c WHERE c.deck_id=d.id) ORDER BY f.id,d.id LIMIT 1").fetchone()
        digest = hashlib.sha256(json.dumps(tables, ensure_ascii=False).encode("utf-8")).hexdigest()
        return digest, candidate

    digest, candidate = data()
    baseline = out / "baseline.json"
    if args.capture_baseline:
        if baseline.exists():
            archived = out / f"baseline-{time.time_ns()}.json"
            baseline.rename(archived)
        baseline.write_text(json.dumps({"sha256": digest}), encoding="utf-8")
        print(f"Read-only data baseline captured: {baseline}")
        return
    expected = json.loads(baseline.read_text(encoding="utf-8"))["sha256"]
    assert digest == expected, "Data changed before the UI check"

    def tree():
        shell("uiautomator", "dump", "/sdcard/slovarik-redesign.xml")
        return ET.fromstring(shell("cat", "/sdcard/slovarik-redesign.xml"))

    def find(text: str, root=None):
        return next((node for node in (root if root is not None else tree()).iter("node")
                     if node.get("text") == text), None)

    def wait(text: str):
        end = time.monotonic() + 25
        while time.monotonic() < end:
            try:
                node = find(text)
                if node is not None:
                    return node
            except (RuntimeError, ET.ParseError):
                pass
            time.sleep(0.3)
        raise AssertionError(f"UI label missing: {text!r}")

    def tap(text: str):
        node = wait(text)
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def screenshot(name: str):
        (out / f"{name}.png").write_bytes(run("exec-out", "screencap", "-p", binary=True))

    shell("am", "force-stop", "com.slovarik.app")
    shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
    wait("Настройки")
    screenshot("01-home")
    if candidate:
        folder, deck = candidate
        tap(folder)
        wait(deck)
        screenshot("02-lists")
        tap(deck)
        wait("Тренироваться")
        screenshot("03-vocabulary")
        tap("Тренироваться")
        tap("Показать перевод")
        wait("Следующее слово")
        screenshot("04-flashcard")
        tap("Следующее слово")
        tap("Тест")
        screenshot("05-choice")
        tap("Пары")
        screenshot("06-pairs")
    tap("Прогресс")
    screenshot("07-progress")
    tap("Настройки")
    screenshot("08-settings")
    assert data()[0] == expected, "UI navigation/practice modified vocabulary or review data"
    shell("am", "force-stop", "com.slovarik.app")
    shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
    print("PASS: redesigned screens, practice navigation, and exact preservation of vocabulary/review data")
    print(f"Screenshots: {out}")


if __name__ == "__main__":
    main()
