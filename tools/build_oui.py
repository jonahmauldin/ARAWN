#!/usr/bin/env python3
"""
ARAWN — offline OUI registry builder (Phase 3, Module B).

Converts the official IEEE MA-L registry dump into the compact, flat format
that OuiLookupManager expects:

    001A11<TAB>Cisco Systems

100% local / free / open: the only input is the public IEEE registry file, which
you download once by hand (no API key, no account, no paid service). This script
uses ONLY the Python standard library — no pip installs.

------------------------------------------------------------------------------
STEP 1 — get the source (pick ONE; the CSV is cleanest):
    CSV : https://standards-oui.ieee.org/oui/oui.csv
    TXT : https://standards-oui.ieee.org/oui/oui.txt
Download in a browser and save next to this script.

STEP 2 — convert:
    python build_oui.py oui.csv ../app/src/main/assets/oui.txt
    python build_oui.py oui.txt ../app/src/main/assets/oui.txt   # txt also works

STEP 3 — done. The assets/ dir is created if missing. Rebuild the app; the
service warms the table on startup. (Re-run anytime IEEE updates the registry.)
------------------------------------------------------------------------------
"""

import csv
import os
import re
import sys

HEX6 = re.compile(r"^[0-9A-Fa-f]{6}$")


def parse_csv(path):
    """IEEE oui.csv columns: Registry, Assignment, Organization Name, Address."""
    with open(path, newline="", encoding="utf-8", errors="replace") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            assignment = (row.get("Assignment") or "").strip()
            org = (row.get("Organization Name") or "").strip()
            if HEX6.match(assignment) and org:
                yield assignment.upper(), org


def parse_txt(path):
    """IEEE oui.txt '(hex)' lines: '00-1A-11   (hex)\\t\\tCisco Systems'."""
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if "(hex)" not in line:
                continue
            left, _, right = line.partition("(hex)")
            prefix = re.sub(r"[^0-9A-Fa-f]", "", left).upper()
            org = right.strip()
            if len(prefix) == 6 and org:
                yield prefix, org


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: python build_oui.py <oui.csv|oui.txt> <out/oui.txt>")

    src, dst = sys.argv[1], sys.argv[2]
    if not os.path.isfile(src):
        sys.exit(f"source not found: {src}")

    parser = parse_csv if src.lower().endswith(".csv") else parse_txt

    # dict() dedupes on prefix; last write wins (matches the manager's behavior).
    table = dict(parser(src))

    out_dir = os.path.dirname(dst)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    with open(dst, "w", encoding="utf-8", newline="\n") as out:
        for prefix in sorted(table):
            out.write(f"{prefix}\t{table[prefix]}\n")

    size_kb = os.path.getsize(dst) / 1024
    print(f"wrote {len(table):,} OUI entries -> {dst} ({size_kb:,.0f} KB)")


if __name__ == "__main__":
    main()
