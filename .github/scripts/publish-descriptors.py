#!/usr/bin/env python3
"""Генерация descriptor'ов (ТЗ §10.2) из готовых ZIP-модулей.

Использование:
    publish-descriptors.py <каталог с ZIP> <каталог вывода> <base-url архивов>

Для каждого ZIP читается module.json (внутренний манифест), считается SHA-256
ТОЧНЫХ байтов архива (значение не хранится внутри того же ZIP, §10.2) и
записывается descriptor <id>.json.
"""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

MANIFEST_FIELDS = ("id", "name", "description", "version", "versionCode",
                   "apiVersion", "entrypoint", "targetPackages")


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    zips_dir, out_dir, base_url = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3].rstrip("/")
    out_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    for archive in sorted(zips_dir.glob("*.zip")):
        with zipfile.ZipFile(archive) as zf:
            names = sorted(zf.namelist())
            if names != ["classes.dex", "module.json"]:
                print(f"SKIP {archive.name}: состав {names} != [module.json, classes.dex]")
                continue
            manifest = json.loads(zf.read("module.json").decode("utf-8"))
        missing = [key for key in MANIFEST_FIELDS if key not in manifest]
        if missing:
            print(f"SKIP {archive.name}: нет полей {missing}")
            continue
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        descriptor = dict(manifest)
        descriptor["downloadUrl"] = f"{base_url}/{archive.name}"
        descriptor["archiveSha256"] = digest
        descriptor["sourceUrl"] = "https://github.com/RubCut/TIkTok-Moduled"
        target = out_dir / f"{manifest['id']}.json"
        target.write_text(json.dumps(descriptor, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"OK {target}: sha256={digest}")
        count += 1
    print(f"descriptors generated: {count}")
    return 0 if count else 1


if __name__ == "__main__":
    sys.exit(main())
