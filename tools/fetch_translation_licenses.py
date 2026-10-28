"""Include upstream model/runtime license texts with the packaged weights."""
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1] / "app/src/main/assets/translation/licenses"
root.mkdir(parents=True, exist_ok=True)
sources = {
    "Apache-2.0.txt": "https://www.apache.org/licenses/LICENSE-2.0.txt",
    "CC-BY-4.0.txt": "https://raw.githubusercontent.com/creativecommons/cc-legal-tools-data/main/docs/licenses/by/4.0/legalcode.txt",
    "ONNX-Runtime-MIT.txt": "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/LICENSE",
}
for name, url in sources.items():
    with urllib.request.urlopen(url, timeout=60) as response:
        text = response.read().decode("utf-8")
    if len(text) < 500 or "<html" in text.lower():
        raise RuntimeError(f"Invalid license response: {url}")
    (root / name).write_text(text, encoding="utf-8")
    print(f"Included {name}")
