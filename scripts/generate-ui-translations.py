#!/usr/bin/env python3
"""Generate Android raw-resource maps from the owner-maintained UI translation CSV.

Add another column such as ``translation_fr`` or ``french_translation`` to
translations/ui-strings.csv; the app selects the matching resource from the
Android device/app locale automatically.
"""
import collections
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "translations" / "ui-strings.csv"
RAW_DIR = ROOT / "res" / "raw"
PLACEHOLDER = re.compile(r"\{[^{}]+\}")
REQUIRED = {"key", "english_source", "placeholders_do_not_change"}
LANGUAGE_CODES = {
    "arabic": "ar", "english": "en", "french": "fr", "spanish": "es",
    "german": "de", "italian": "it", "portuguese": "pt", "japanese": "ja",
    "korean": "ko", "chinese": "zh", "russian": "ru", "hindi": "hi",
    "turkish": "tr", "dutch": "nl", "polish": "pl", "indonesian": "id",
}


def locale_for(column):
    if column.endswith("_translation"):
        name = column[:-len("_translation")].lower()
    elif column.startswith("translation_"):
        name = column[len("translation_"):].lower()
    else:
        return None
    if name in LANGUAGE_CODES:
        return LANGUAGE_CODES[name]
    if re.fullmatch(r"[a-z]{2,3}", name):
        return name
    raise SystemExit(f"Cannot infer locale from translation column {column!r}; use e.g. translation_fr")


with SOURCE.open("r", encoding="utf-8-sig", newline="") as stream:
    reader = csv.DictReader(stream)
    fields = reader.fieldnames or []
    missing = REQUIRED.difference(fields)
    if missing:
        raise SystemExit(f"Missing CSV columns: {', '.join(sorted(missing))}")
    rows = list(reader)

if not rows:
    raise SystemExit("Translation CSV has no entries")

seen = set()
for line, row in enumerate(rows, start=2):
    key = (row.get("key") or "").strip()
    english = row.get("english_source") or ""
    if not key or not english:
        raise SystemExit(f"Missing key or English source at CSV line {line}")
    if key in seen:
        raise SystemExit(f"Duplicate key {key!r} at CSV line {line}")
    seen.add(key)
    expected = collections.Counter(PLACEHOLDER.findall(english))
    declared = collections.Counter(x.strip() for x in (row.get("placeholders_do_not_change") or "").split(",") if x.strip())
    if expected != declared:
        raise SystemExit(f"Placeholder column does not match English at CSV line {line}: {key}")

translation_columns = {}
for column in fields:
    locale = locale_for(column)
    if locale:
        if locale in translation_columns:
            raise SystemExit(f"More than one translation column maps to locale {locale!r}")
        translation_columns[locale] = column
if not translation_columns:
    raise SystemExit("Add a translation column, such as arabic_translation or translation_ar")

RAW_DIR.mkdir(parents=True, exist_ok=True)
for stale in RAW_DIR.glob("ui_strings_*.json"):
    stale.unlink()

for locale, column in sorted(translation_columns.items()):
    strings = []
    for line, row in enumerate(rows, start=2):
        translation = row.get(column) or ""
        if not translation:
            continue
        expected = collections.Counter(PLACEHOLDER.findall(row["english_source"]))
        actual = collections.Counter(PLACEHOLDER.findall(translation))
        if expected != actual:
            raise SystemExit(f"{column} placeholders differ from English at CSV line {line}: {row['key']}")
        strings.append({"key": row["key"], "english": row["english_source"], "translation": translation})
    output = RAW_DIR / f"ui_strings_{locale}.json"
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump({"locale": locale, "strings": strings}, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"Generated {len(strings)} {locale} strings from {SOURCE.relative_to(ROOT)}")
