#!/usr/bin/env python3
"""Count literals that still look like user-facing UI text in Kotlin.

Advisory, not a gate: Thai hardcoding is a correctness bug (an English-locale user sees Thai with
no way out) and `check-i18n.sh` fails on it. Hardcoded *English* only means that locale is not
translated yet, so this prints a number and a per-file ranking to work down, and never fails.

The heuristic is a literal reaching a known UI sink (text/title/hint/toast/setTitle/...) that
reads like a sentence or a capitalised label. It will miss text passed positionally and will
occasionally flag a log line; treat the ranking as a worklist, not a spec.

Usage: i18n_hardcoded_scan.py <source-root> [--top N]
"""
import os
import re
import sys

NOISE = re.compile(r'"""(?:.|\n)*?"""|//[^\n]*|/\*(?:.|\n)*?\*/')
SINK = re.compile(
    r'(?:\btext\s*=|\btitle\s*=|\bhint\s*=|\blabel\s*=|\bcaption\s*=|\bsubtitle\s*=|'
    r'contentDescription\s*=|toast\(|message\(|setTitle\(|setMessage\(|addText\(|'
    r'setHint\(|setSearchHint\(|Text\(|makeText\([^,]+,\s*|screenHeader\(|sectionLabel\(|'
    r'settingsEntry\(|settingRow\(|SettingsGroup\(|showMessage\(|showFeedback\()\s*'
    r'"((?:[^"\\\n]|\\.)+)"')
# A capitalised word, or two lowercase words: a label or a sentence rather than a key or an id.
PROSE = re.compile(r"^[A-Z][a-z]|^[a-z]+ [a-z]")


def counts(root):
    for dirpath, _, filenames in os.walk(root):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                source = handle.read()
            body = NOISE.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), source)
            hits = sum(1 for m in SINK.finditer(body) if PROSE.search(m.group(1)))
            if hits:
                yield hits, os.path.relpath(path, root)


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    top = 10
    if "--top" in argv:
        top = int(argv[argv.index("--top") + 1])
    rows = sorted(counts(argv[1]), reverse=True)
    total = sum(n for n, _ in rows)
    print(f"{total} literal(s) in {len(rows)} file(s); largest first:")
    for n, rel in rows[:top]:
        print(f"  {n:4d}  {rel}")
    if len(rows) > top:
        print(f"  ... and {len(rows) - top} more file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
