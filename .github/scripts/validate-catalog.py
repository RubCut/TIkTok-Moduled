#!/usr/bin/env python3
"""Проверка site/catalog.json (ТЗ §13.1): схема, уникальность ID, HTTPS."""
import json
import sys

MANIFEST_FIELDS = ("id", "name", "description", "manifestUrl")


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "site/catalog.json"
    with open(path, encoding="utf-8") as f:
        catalog = json.load(f)
    assert catalog.get("schemaVersion") == 1, "schemaVersion != 1"
    assert isinstance(catalog.get("modules"), list), "modules не список"
    ids = [m["id"] for m in catalog["modules"]]
    assert len(ids) == len(set(ids)), "дубли ID в каталоге"
    for module in catalog["modules"]:
        for key in MANIFEST_FIELDS:
            assert key in module, f"нет поля {key} у {module.get('id')}"
        for url_key in ("manifestUrl", "downloadUrl", "sourceUrl"):
            url = module.get(url_key)
            if url is not None:
                assert str(url).startswith("https://"), f"{url_key} обязан быть HTTPS"
    print("catalog.json валиден:", len(ids), "модулей")
    return 0


if __name__ == "__main__":
    sys.exit(main())
