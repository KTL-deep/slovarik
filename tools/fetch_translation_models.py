"""Fetch pinned OPUS-MT ONNX models for packaging, not for runtime downloads.

Weights are checked against Hugging Face's published LFS SHA-256 values.
No downloaded scripts or custom model code are executed.
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/assets/translation"
MODELS = {
    "en-ru": {
        "revision": "e050376e960175e8ddc5cff85025dc8436cddd68",
        "encoder": "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53",
        "decoder": "7e4e602c31130176b086cd4bf31d6d08d44a6a367617e910870e0ea3e5408574",
        "license": "Apache-2.0",
    },
    "ru-en": {
        "revision": "afe8c6c738ec81b6d033fd8f44f9678a639a7c67",
        "encoder": "c10df0466540534bb70c4402d539c349b8bfa9968184cad89f51408cf13e7eb7",
        "decoder": "d880abf58612013815554ef55c98d0f28b68190b2cb6f65c39d6e834f51dda8c",
        "license": "CC-BY-4.0",
    },
}


def download(task):
    direction, filename, expected = task
    config = MODELS[direction]
    remote = f"onnx/{filename}" if filename.endswith(".onnx") else filename
    url = f"https://huggingface.co/Xenova/opus-mt-{direction}/resolve/{config['revision']}/{remote}"
    destination = ROOT / direction / filename
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        if expected is None or digest == expected:
            return direction, filename, digest, destination.stat().st_size
        raise RuntimeError(f"Existing model failed integrity check: {destination}")
    temporary = destination.with_suffix(destination.suffix + ".part")
    offset = temporary.stat().st_size if temporary.exists() else 0
    request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
    with urllib.request.urlopen(request, timeout=600) as response:
        resume = offset > 0 and response.status == 206 and response.headers.get("Content-Range", "").startswith(f"bytes {offset}-")
        digest = hashlib.sha256(temporary.read_bytes()) if resume else hashlib.sha256()
        with temporary.open("ab" if resume else "wb") as target:
            while chunk := response.read(1024 * 1024):
                target.write(chunk)
                digest.update(chunk)
    actual = digest.hexdigest()
    if expected and actual != expected:
        raise RuntimeError(f"Model SHA-256 mismatch: {direction}/{filename}")
    temporary.rename(destination)
    print(f"Verified {direction}/{filename}: {destination.stat().st_size} bytes", flush=True)
    return direction, filename, actual, destination.stat().st_size


if __name__ == "__main__":
    tasks = []
    for direction, config in MODELS.items():
        tasks.extend((direction, f"{kind}_model_quantized.onnx", config[kind]) for kind in ("encoder", "decoder"))
        tasks.extend((direction, filename, None) for filename in ("tokenizer.json", "config.json"))
    with ThreadPoolExecutor(max_workers=2) as pool:
        downloaded = list(pool.map(download, tasks))
    manifest = {"publisher": "Helsinki-NLP / University of Helsinki; ONNX conversion by Xenova",
                "models": MODELS,
                "files": [{"path": f"{d}/{f}", "sha256": h, "bytes": n} for d, f, h, n in downloaded]}
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Bundled translation assets ready.")
