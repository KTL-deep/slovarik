"""Check RU/EN resource coverage and matching format arguments without Android tools."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / "app/src/main/res"


def strings(directory):
    result = {}
    for file in directory.glob("*.xml"):
        for item in ET.parse(file).getroot().findall("string"):
            name = item.attrib["name"]
            assert name not in result, f"Duplicate string {name} in {file}"
            result[name] = "".join(item.itertext())
    return result


ru = strings(root / "values")
en = strings(root / "values-en")
assert ru.keys() == en.keys(), f"Missing translations: EN {ru.keys()-en.keys()}, RU {en.keys()-ru.keys()}"
for key in ru:
    arguments = lambda value: sorted(re.findall(r"%(?:\d+\$)?[-+ 0#,(]*\d*(?:\.\d+)?[dsf]", value))
    assert arguments(ru[key]) == arguments(en[key]), f"Format arguments differ: {key}"
    assert en[key].strip(), f"Empty English translation: {key}"
print(f"PASS: {len(ru)} RU/EN strings, complete coverage and matching format arguments")
