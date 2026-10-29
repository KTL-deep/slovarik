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
            candidate = db.execute("SELECT f.name,d.name,(SELECT c.word FROM cards c WHERE c.deck_id=d.id "
                                   "AND c.deleted_at=0 ORDER BY c.id LIMIT 1) "
                                   "FROM folders f JOIN decks d ON d.folder_id=f.id "
                                   "WHERE f.deleted_at=0 AND d.deleted_at=0 AND EXISTS"
                                   "(SELECT 1 FROM cards c WHERE c.deck_id=d.id AND c.deleted_at=0) "
                                   "ORDER BY f.id,d.id LIMIT 1").fetchone()
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
    for old in out.glob("*.png"):
        if re.fullmatch(r"\d+-.*\.png", old.name):
            old.unlink()

    def tree():
        shell("uiautomator", "dump", "/sdcard/slovarik-redesign.xml")
        return ET.fromstring(shell("cat", "/sdcard/slovarik-redesign.xml"))

    def find(text: str, root=None):
        return next((node for node in (root if root is not None else tree()).iter("node")
                     if node.get("text") == text or node.get("content-desc") == text), None)

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

    def front_card():
        end = time.monotonic() + 25
        while time.monotonic() < end:
            try:
                root = tree()
                face = next((node for node in root.iter("node") if node.get("clickable") == "true"
                             and any("ПЕРЕВЕДИТЕ" in child.get("text", "") for child in node.iter("node"))), None)
                if face is not None:
                    return face
            except (RuntimeError, ET.ParseError):
                pass
            time.sleep(0.3)
        raise AssertionError("Front review card missing")

    def tap(text: str):
        node = wait(text)
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def screenshot(name: str):
        (out / f"{name}.png").write_bytes(run("exec-out", "screencap", "-p", binary=True))

    def no_legacy_navigation():
        root = tree()
        for label in ("Тренироваться", "Прогресс", "Статистика", "Импорт CSV"):
            assert find(label, root) is None, f"Legacy navigation still visible: {label}"

    def back():
        shell("input", "keyevent", "KEYCODE_BACK")

    def menu():
        tap("Menu" if find("Menu") is not None else "Меню")

    def settings():
        menu()
        tap("Настройки")
        wait("Язык интерфейса")

    def scroll_to(text: str, upward: bool = False):
        for _ in range(6):
            if find(text) is not None:
                return
            shell("input", "swipe", "500", "800" if upward else "1750",
                  "500", "1750" if upward else "800", "350")
        wait(text)

    shell("am", "force-stop", "com.slovarik.app")
    shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
    original_language = None
    try:
        wait("Меню" if find("Мои папки") is not None else "Menu")
        original_language = "ru" if find("Мои папки") is not None else "en"
        if original_language == "en":
            tap("Menu")
            tap("Settings")
            wait("Interface language")
            tap("Русский")
            wait("Язык интерфейса")
            back()
        wait("Мои папки")
        wait("Мои колоды")
        no_legacy_navigation()
        screenshot("01-home")
        assert candidate is not None, "An active deck with an active card is required"
        folder, deck, word = candidate
        tap(folder)
        wait(deck)
        assert any(node.get("text", "").startswith("Повторено:") for node in tree().iter("node")), "Folder progress missing"
        screenshot("02-folder")
        tap(f"Новая колода в «{folder}»")
        wait("Новая колода")
        wait(f"Папка: {folder}")
        screenshot("02a-new-deck-dialog")
        tap("Отмена")
        tap(deck)
        for label in ("Карточки", "Тест", "Ввод", "Пары"):
            wait(label)
        wait("Добавить карточку")
        no_legacy_navigation()
        assert any(node.get("text", "").startswith("Повторено:") for node in tree().iter("node")), "Deck progress missing"
        wait(word)  # First active word is rendered in the deck mosaic.
        screenshot("03-deck-mosaic")
        tap("Добавить карточку")
        wait("Новая карточка")
        screenshot("03a-new-card-dialog")
        tap("Отмена")

        menu()
        for label in ("Настройки", "Вставить слова", "Импортировать файл"):
            wait(label)
        screenshot("04-deck-menu")
        # uiautomator may expose only the popup window; ask the system for real screen size.
        width, height = map(int, re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1])
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", wait("Настройки").get("bounds")))
        assert (x1 + x2) / 2 > width / 2 and y2 < height / 3, \
            f"Menu must open at the top right: {(x1, y1, x2, y2)} on {width}x{height}"
        assert find("Импорт CSV") is None, "Unexpected CSV button"
        tap("Вставить слова")
        wait("Импорт слов")
        screenshot("05-paste-import")
        back()  # Dismiss without importing anything.
        wait("Карточки")

        for index, mode in enumerate(("Карточки", "Тест", "Ввод", "Пары"), 6):
            tap(mode)
            wait(f"← {deck}")
            if mode == "Карточки":
                # Tap the clickable card containing the front-face label; never grade a card.
                face = front_card()
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", face.get("bounds")))
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                wait("Произнести")
                # A second tap must return to the first face, a third reveals again.
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                front_card()
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                wait("Произнести")
            screenshot(f"{index:02d}-{('cards', 'quiz', 'type', 'pairs')[index - 6]}")
            back()
            wait("Добавить карточку")

        back()
        wait(folder)
        back()
        wait("Мои папки")
        settings()
        screenshot("10-settings-ru")
        wait("Русский")
        wait("English")
        wait("Язык исходных слов")
        wait("Язык перевода")
        scroll_to("Напоминания")
        if find("Включить") is None and find("Выключить") is None:
            shell("input", "swipe", "500", "1750", "500", "800", "350")
        assert find("Включить") is not None or find("Выключить") is not None, "Reminder status missing"
        scroll_to("Корзина")
        tap("Корзина")
        wait("Корзина")
        screenshot("11-trash")
        back()
        scroll_to("English", upward=True)
        tap("English")
        wait("Interface language")
        wait("Word language")
        wait("Translation language")
        screenshot("12-settings-en")
        assert data()[0] == expected, "Language change modified vocabulary or review history"
        shell("am", "force-stop", "com.slovarik.app")
        shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
        menu()
        tap("Settings")
        wait("Interface language")
        wait("Word language")
        wait("Translation language")
        screenshot("13-settings-en-restart")
        assert data()[0] == expected, "Restart modified vocabulary or review history"
    finally:
        if original_language is not None:
            try:
                shell("am", "force-stop", "com.slovarik.app")
                shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
                current_language = "ru" if find("Мои папки") is not None or find("Язык интерфейса") is not None else "en"
                if current_language != original_language:
                    tap("Меню" if current_language == "ru" else "Menu")
                    tap("Настройки" if current_language == "ru" else "Settings")
                    wait("Язык интерфейса" if current_language == "ru" else "Interface language")
                    tap("Русский" if original_language == "ru" else "English")
                    wait("Язык интерфейса" if original_language == "ru" else "Interface language")
                assert data()[0] == expected, "Language restoration modified vocabulary or review history"
            finally:
                shell("am", "force-stop", "com.slovarik.app")
                shell("am", "start", "-W", "-n", "com.slovarik.app/.MainActivity")
    print("PASS: redesigned UI, language persistence, and exact preservation of vocabulary/review data")
    print(f"Screenshots: {out}")


if __name__ == "__main__":
    main()
