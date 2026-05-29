#!/usr/bin/env python3
"""
ARAWN — offline OUI registry builder (Phase 3, Module B).

Converts a public MAC registry dump into the compact, flat format that
OuiLookupManager expects:

    001A11<TAB>Cisco Systems          (MA-L /24, 6 hex)
    8C1F64C<TAB>Acme                  (MA-M /28, 7 hex)
    70B3D5E1F<TAB>Tiny IoT Co         (MA-S /36, 9 hex)

100% local / free / open: the only input is a public registry file you download
once by hand (no API key, no account, no paid service). Stdlib only — no pip.

------------------------------------------------------------------------------
SOURCES (pick ONE):
    PREFERRED — Wireshark `manuf` (MA-L + MA-M + MA-S in one file, short names):
        https://www.wireshark.org/download/automated/data/manuf
    IEEE MA-L only (24-bit prefixes, long names):
        CSV : https://standards-oui.ieee.org/oui/oui.csv
        TXT : https://standards-oui.ieee.org/oui/oui.txt

CONVERT (format auto-detected from the filename):
    python build_oui.py manuf      ../app/src/main/assets/oui.txt   # preferred
    python build_oui.py oui.csv    ../app/src/main/assets/oui.txt
    python build_oui.py oui.txt    ../app/src/main/assets/oui.txt

The assets/ dir is created if missing. Rebuild the app; the service warms the
table on startup. Re-run whenever the upstream registry updates (the Wireshark
manuf file refreshes ~weekly) — this is the entire DB-refresh workflow.
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


def parse_manuf(path):
    """
    Wireshark `manuf`: tab-separated `prefix[/NN]<TAB>short[<TAB>long]`.

    The prefix may carry a CIDR mask (/28 MA-M, /36 MA-S); the emitted key is
    sized to the mask's nibble count (24->6, 28->7, 36->9) so longest-prefix
    lookups resolve sub-blocks of a shared /24. The short name (field 2) is
    preferred; falls back to the long name when short is absent.
    """
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            token = fields[0].strip()
            short = fields[1].strip() if len(fields) > 1 else ""
            long = fields[2].strip() if len(fields) > 2 else ""
            org = short or long
            if not org:
                continue

            mask = None
            if "/" in token:
                token, _, m = token.partition("/")
                mask = int(m) if m.strip().isdigit() else None
            hexs = re.sub(r"[^0-9A-Fa-f]", "", token).upper()
            length = (mask // 4) if mask else len(hexs)
            if length not in (6, 7, 9) or len(hexs) < length:
                continue
            yield hexs[:length], org


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: python build_oui.py <manuf|oui.csv|oui.txt> <out/oui.txt>")

    src, dst = sys.argv[1], sys.argv[2]
    if not os.path.isfile(src):
        sys.exit(f"source not found: {src}")

    low = src.lower()
    if low.endswith(".csv"):
        parser = parse_csv
    elif "manuf" in os.path.basename(low):
        parser = parse_manuf
    else:
        parser = parse_txt

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
