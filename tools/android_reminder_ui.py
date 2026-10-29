"""Toggle reminders on a test emulator, restoring original state and permission.

Never changes words or grades. Run separately from all other device UI tests.
"""
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb.exe"), "-s", "emulator-5580"]
package = "com.slovarik.app"
permission = "android.permission.POST_NOTIFICATIONS"


def run(*args, binary=False):
    return subprocess.check_output(adb + list(args), timeout=40).decode("utf-8", errors="replace").strip() if not binary else subprocess.check_output(adb + list(args), timeout=40)


def shell(*args):
    return run("shell", " ".join(shlex.quote(arg) for arg in args))


def prefs():
    return {item.attrib["name"]: item.attrib.get("value", item.text) for item in ET.fromstring(shell("run-as", package, "cat", "shared_prefs/settings.xml"))}


def tree():
    shell("uiautomator", "dump", "/sdcard/slovarik-reminder-ui.xml")
    return ET.fromstring(shell("cat", "/sdcard/slovarik-reminder-ui.xml"))


def find(text, root):
    return next((n for n in root.iter("node") if n.get("text") == text or n.get("content-desc") == text), None)


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def tap(text, scroll=False):
    end = time.monotonic() + 25
    while time.monotonic() < end:
        node = find(text, tree())
        if node is not None:
            tap_node(node)
            return
        if scroll:
            shell("input", "swipe", "500", "1700", "500", "900", "300")
    raise AssertionError(f"Missing label: {text}")


def data():
    with sqlite3.connect(":memory:") as db:
        db.deserialize(run("exec-out", "run-as", package, "cat", "databases/slovarik.db", binary=True))
        return {table: db.execute(f"SELECT * FROM {table} ORDER BY id").fetchall() for table in ("folders", "decks", "cards", "events")}


original = prefs()
before = data()
granted = bool(re.search(r"android.permission.POST_NOTIFICATIONS: granted=true", shell("dumpsys", "package", package)))
english = original.get("interface_language") == "en"
enable, disable = ("Enable", "Disable") if english else ("Включить", "Выключить")
try:
    # Grant only for this check; restore a previously denied permission in finally.
    if not granted:
        shell("pm", "grant", package, permission)
    shell("am", "force-stop", package)
    shell("am", "start", "-W", "-n", f"{package}/.MainActivity")
    tap("Menu" if english else "Меню")
    tap("Settings" if english else "Настройки")
    if original.get("reminder") == "true":
        tap(disable, scroll=True)
    # Changing time without changing its value must not auto-enable reminders.
    tap("Choose time" if english else "Выбрать время", scroll=True)
    positive = next(n for n in tree().iter("node") if n.get("resource-id") == "android:id/button1")
    tap_node(positive)
    assert prefs().get("reminder") == "false", "Choosing time unexpectedly enabled reminders"
    tap(enable, scroll=True)
    assert find(disable, tree()) is not None, "Toggle did not change to Disable"
    assert prefs().get("reminder") == "true"
    tap(disable)
    assert find(enable, tree()) is not None, "Toggle did not change back to Enable"
    assert prefs().get("reminder") == "false"
    assert data() == before, "Reminder controls changed user learning data"
    print("PASS: time selection stays disabled; Enable/Disable labels and saved state toggle correctly")
finally:
    current = prefs().get("reminder") == "true"
    wanted = original.get("reminder") == "true"
    if current != wanted:
        tap(enable if wanted else disable, scroll=True)
    if not granted:
        shell("pm", "revoke", package, permission)
    after = prefs()
    assert all(after.get(key) == original.get(key) for key in ("reminder", "hour", "minute", "language", "target", "interface_language")), "Settings were not restored"
    assert data() == before, "User data changed"
    shell("am", "force-stop", package)
    shell("am", "start", "-W", "-n", f"{package}/.MainActivity")
