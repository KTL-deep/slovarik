"""Emulator-only smoke check for the seeded demo deck.

Exercises offline startup, free practice, pairs, and one scheduled review.
It intentionally records ONE review in the demo deck; it never clears app data.
Run after start-android.ps1, not concurrently with instrumented Gradle tests.
"""
from __future__ import annotations

import argparse
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
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--services", action="store_true", help="Also download a translation model and check reminder settings")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("Refusing to alter a physical device: use an emulator serial.")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
    adb = sdk / "platform-tools/adb.exe"
    artifacts = Path.home() / "AppData/Local/Temp/opencode/slovarik-smoke"
    artifacts.mkdir(parents=True, exist_ok=True)

    def run(*parts: str, binary: bool = False):
        result = subprocess.run([str(adb), "-s", args.serial, *parts],
                                capture_output=True, timeout=40)
        if result.returncode:
            raise RuntimeError(f"adb {parts}: " + (result.stdout + result.stderr).decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()

    def shell(*parts: str) -> str:
        return run("shell", " ".join(shlex.quote(p) for p in parts))

    def tree() -> ET.Element:
        shell("uiautomator", "dump", "/sdcard/slovarik-smoke.xml")
        return ET.fromstring(shell("cat", "/sdcard/slovarik-smoke.xml"))

    def find(text: str, root: ET.Element | None = None):
        return next((n for n in (root if root is not None else tree()).iter("node")
                     if n.get("text") == text), None)

    def wait(text: str, seconds: int = 25):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                node = find(text)
                if node is not None:
                    return node
            except (ET.ParseError, RuntimeError):
                pass  # UI hierarchy can be unavailable while the activity is starting.
            time.sleep(0.5)
        raise AssertionError(f"UI text not found: {text!r}")

    def tap_node(node: ET.Element) -> None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(text: str) -> None:
        tap_node(wait(text))

    def back() -> None:
        shell("input", "keyevent", "KEYCODE_BACK")

    def snapshot(name: str) -> None:
        (artifacts / f"{name}.png").write_bytes(run("exec-out", "screencap", "-p", binary=True))

    def state():
        raw = run("exec-out", "run-as", "com.slovarik.app", "cat", "databases/slovarik.db", binary=True)
        with sqlite3.connect(":memory:") as db:
            db.deserialize(raw)
            cards = db.execute("SELECT id,word,translation,reps,interval_days,ease,due FROM cards ORDER BY id").fetchall()
            events = db.execute("SELECT card_id,day,correct FROM events ORDER BY id").fetchall()
        return cards, events

    def wait_reminder(enabled: bool) -> None:
        # SharedPreferences.apply updates memory synchronously, but disk asynchronously.
        expected = f'name="reminder" value="{str(enabled).lower()}"'
        end = time.monotonic() + 8
        while time.monotonic() < end:
            prefs = shell("run-as", "com.slovarik.app", "cat", "shared_prefs/settings.xml")
            if expected in prefs:
                return
            time.sleep(0.2)
        raise AssertionError(f"Reminder preference did not become {enabled}")

    def open_demo() -> None:
        shell("am", "force-stop", "com.slovarik.app")
        shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
        tap("Мои слова")
        tap("Первые слова · English")
        wait("Тренироваться")

    if not shell("pm", "path", "com.slovarik.app"):
        raise SystemExit("Application not installed. Run start-android.ps1 -NoBuild after Gradle device tests.")
    wifi = shell("settings", "get", "global", "wifi_on")
    data = shell("settings", "get", "global", "mobile_data")
    try:
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
        open_demo()
        before = state()
        assert len(before[0]) >= 2, "Demo cards missing"
        snapshot("offline-deck")
        tap("Тренироваться")
        wait("Без расписания и статистики")
        tap("Показать перевод")
        tap("Следующее слово")
        # The last chip can be offscreen at high font scales: reveal it with a horizontal swipe.
        root = tree()
        pair_chip = find("Пары", root)
        if pair_chip is None:
            shell("input", "swipe", "950", "650", "180", "650", "300")
        tap("Пары")
        wait("Нажмите слово, затем его перевод")
        root = tree()
        for card in before[0]:
            word, translation = card[1:3]
            left, right = find(word, root), find(translation, root)
            if left is not None and right is not None:
                tap_node(left)
                tap(translation)
                wait("Верно!")
                break
        else:
            raise AssertionError("No matching pair visible")
        snapshot("pairs")
        assert state() == before, "Free practice changed the spaced repetition schedule/history"
        back()
        wait("Тренироваться")
        # Review one due demo card; other cards remain untouched.
        root = tree()
        button = next((n for n in root.iter("node") if n.get("text", "").startswith("Повторить ·")), None)
        assert button is not None, "Scheduled review button missing"
        tap_node(button)
        tap("Показать перевод")
        root = tree()
        grade = find("Хорошо", root)
        if grade is None:
            shell("input", "swipe", "500", "1800", "500", "700", "400")
        tap("Хорошо")
        after = state()
        assert len(after[1]) == len(before[1]) + 1, "Review not recorded exactly once"
        assert sum(c[3] for c in after[0]) == sum(c[3] for c in before[0]) + 1
        open_demo()
        assert state() == after, "Saved progress changed after offline process restart"
        snapshot("offline-restarted")
        print("PASS: offline startup/restart, flashcard practice, matching, unchanged practice schedule, persisted scheduled review")
        if args.services:
            shell("svc", "wifi", "enable")
            tap("+ Карточка")
            root = tree()
            word_field = next(n for n in root.iter("node") if n.get("class") == "android.widget.EditText")
            tap_node(word_field)
            shell("input", "text", "book")
            tap("Скачать модель и перевести с Google")
            end = time.monotonic() + 180
            translated = ""
            while time.monotonic() < end:
                root = tree()
                fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
                if len(fields) > 1 and fields[1].get("text", "").strip():
                    translated = fields[1].get("text", "").strip()
                    break
                if find("Скачать модель и перевести с Google", root) is not None:
                    raise AssertionError("Translation returned without text; inspect the app's model-download error")
                time.sleep(3)
            assert translated, "Translation model download did not finish in 180 seconds"
            snapshot("translation")
            tap("Отмена")
            # Verify that the now-cached model can translate a different word without a network.
            shell("svc", "wifi", "disable")
            tap("+ Карточка")
            tap_node(next(n for n in tree().iter("node") if n.get("class") == "android.widget.EditText"))
            shell("input", "text", "house")
            tap("Скачать модель и перевести с Google")
            end = time.monotonic() + 35
            translated = ""
            while time.monotonic() < end:
                fields = [n for n in tree().iter("node") if n.get("class") == "android.widget.EditText"]
                if len(fields) > 1 and fields[1].get("text", "").strip():
                    translated = fields[1].get("text", "").strip()
                    break
                time.sleep(1)
            assert translated, "Cached translation model did not work offline"
            tap("Отмена")
            tap("Настройки")
            if find("Выбрать время") is None:
                shell("input", "swipe", "500", "1750", "500", "850", "350")
            tap("Выбрать время")
            positive = next(n for n in tree().iter("node") if n.get("resource-id") == "android:id/button1")
            tap_node(positive)
            root = tree()
            allow = next((n for n in root.iter("node") if n.get("resource-id", "").endswith("permission_allow_button")), None)
            if allow is not None:
                tap_node(allow)
            wait("Выключить")
            wait_reminder(True)
            snapshot("reminders")
            tap("Выключить")
            wait_reminder(False)
            assert state() == after, "Service checks changed saved words/progress"
            print("PASS: ML Kit translation online and with cached model offline; reminder enable/disable")
        print(f"Screenshots: {artifacts}")
    finally:
        shell("svc", "wifi", "enable" if wifi == "1" else "disable")
        shell("svc", "data", "enable" if data == "1" else "disable")


if __name__ == "__main__":
    main()
