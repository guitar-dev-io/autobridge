#!/usr/bin/env python3
"""Report Kotlin files that hold Thai text inside a string literal.

Hardcoded Thai is the one hardcoding that cannot be worked around at runtime: an English-locale
user sees it with no way to switch. Comments are left alone on purpose — the voice-parser notes
quote the Thai they match, and rewriting those in English would make them harder to verify.

Usage: i18n_thai_scan.py <source-root> [allowed/relative/path.kt ...]
Prints one line per offending file and exits 1 if there were any.
"""
import os
import re
import sys

THAI = re.compile(r"[฀-๿]")
# Blanked before the literal scan so Thai inside them is never reported. Raw strings go too: they
# carry HTML/JS payloads rather than labels.
NOISE = re.compile(r'"""(?:.|\n)*?"""|//[^\n]*|/\*(?:.|\n)*?\*/')
LITERAL = re.compile(r'"(?:\\.|[^"\\\n])*"')


def offenders(root, allowed):
    for dirpath, _, filenames in os.walk(root):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            if rel in allowed:
                continue
            with open(path, encoding="utf-8") as handle:
                source = handle.read()
            # Replace with same-length blanks so reported line numbers stay true to the file.
            body = NOISE.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), source)
            hits = [m for m in LITERAL.finditer(body) if THAI.search(m.group(0))]
            if hits:
                yield rel, len(hits), body.count("\n", 0, hits[0].start()) + 1


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    found = False
    for rel, count, line in offenders(argv[1], set(argv[2:])):
        found = True
        print(f"{rel} ({count} literal(s), first at line {line})")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
