"""Compare legacy user data with the local pre-upgrade archive, without restoring it.

Run immediately after installing phase one and before doing real reviews.
Only private temporary copies of the archived SQLite files are opened writable.
"""
import argparse
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--backup", type=Path)
args = parser.parse_args()
temp_root = Path.home() / "AppData/Local/Temp/opencode"
backup = args.backup or sorted(temp_root.glob("slovarik-before-phase1-*.tar"))[-1]
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
raw = subprocess.check_output([str(sdk / "platform-tools/adb.exe"), "-s", "emulator-5580",
                               "exec-out", "run-as", "com.slovarik.app", "cat", "databases/slovarik.db"])
after = sqlite3.connect(":memory:")
after.deserialize(raw)
assert after.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
assert after.execute("PRAGMA user_version").fetchone()[0] == 2
with tempfile.TemporaryDirectory(prefix="slovarik-migration-", dir=temp_root) as directory:
    database = Path(directory) / "slovarik.db"
    with tarfile.open(backup) as archive:
        for suffix in ("", "-journal", "-wal", "-shm"):
            name = f"databases/slovarik.db{suffix}"
            if name in archive.getnames():
                Path(str(database) + suffix).write_bytes(archive.extractfile(name).read())
    with closing(sqlite3.connect(database)) as before:
        assert before.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        for table in ("folders", "decks", "cards", "events"):
            columns = [row[1] for row in before.execute(f"PRAGMA table_info({table})")]
            selection = ",".join(f'"{column}"' for column in columns)
            old_rows = before.execute(f"SELECT {selection} FROM {table} ORDER BY id").fetchall()
            new_rows = after.execute(f"SELECT {selection} FROM {table} ORDER BY id").fetchall()
            assert old_rows == new_rows, f"Legacy {table} data changed; do not overwrite user changes"
            print(f"PASS: {table}: all {len(old_rows)} existing rows and fields preserved")
for table in ("folders", "decks", "cards"):
    assert after.execute(f"SELECT COUNT(*) FROM {table} WHERE deleted_at<>0").fetchone()[0] == 0
assert after.execute("SELECT COUNT(*) FROM cards WHERE learning_step<>0").fetchone()[0] == 0
print("PASS: real v1-to-v2 migration, new learning/trash fields initialized safely")
