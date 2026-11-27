#!/usr/bin/env python3
"""Печатает хвосты логов CI и дублирует их в аннотации GitHub Actions.

Логи джобов хранятся во внешнем blob-хранилище, которое доступно не из любой
среды отладки. Аннотации (::error::) читаются обычным REST API, поэтому
ключевые строки падения дублируются туда.
"""
import os
import re
import sys

MAX_CHUNK = 900
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def clean(text: str) -> str:
    text = ANSI.sub("", text)
    return text.replace("%", "%25").replace("\r", "")


def emit(level: str, message: str) -> None:
    message = clean(message)
    for start in range(0, len(message), MAX_CHUNK):
        chunk = message[start:start + MAX_CHUNK]
        chunk = chunk.replace("\n", "%0A")
        print(f"::{level}::{chunk}")


def main(paths) -> int:
    found = False
    for path in paths:
        if not os.path.isfile(path):
            continue
        found = True
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            lines = handle.readlines()
        print(f"\n===== {path} (last 120 of {len(lines)} lines) =====")
        for line in lines[-120:]:
            sys.stdout.write(line)
        pattern = re.compile(
            r"(?i)(FAILURE|error:|Exception|What went wrong|Caused by:|Execution failed|"
            r"symbol:|location:|FAILED|expected:|AssertionError|ComparisonFailure|"
            r"\bat dev\.moduled\.|incompatible types|cannot find symbol)")
        interesting = []
        for index, line in enumerate(lines):
            if pattern.search(line):
                # Берём саму строку и две следующие: javac печатает "symbol:" и
                # "location:" отдельными строками, а Gradle — причину ниже.
                for extra in range(0, 3):
                    if index + extra < len(lines):
                        candidate = lines[index + extra].rstrip("\n")
                        if candidate not in interesting:
                            interesting.append(candidate)
        summary = interesting[-60:] if interesting else [line.rstrip("\n") for line in lines[-25:]]
        emit("error", f"[{os.path.basename(path)}]\n" + "\n".join(summary))
    if not found:
        emit("error", "CI failed and no log files were produced.")
    return 0


if __name__ == "__main__":
    main(sys.argv[1:])
