#!/usr/bin/env python3
"""
ARAWN — offline verification harness for the OUI lookup engine.

Mirrors the Kotlin `OuiLookupManager` (classify + tiered longest-prefix probe)
against the generated `assets/oui.txt`, so the data + algorithm can be validated
on the dev box BEFORE building the APK. It does NOT replace on-device testing of
live BLE scans — it proves the table and the resolution logic are correct.

Run:
    python verify_oui.py ../app/src/main/assets/oui.txt

Stdlib only.
"""

import sys
import time

UNKNOWN = "Unknown Vendor"
RANDOMIZED = "Randomized (private)"


# ---- mirror of OuiLookupManager -------------------------------------------

def load(path):
    table = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            i = -1
            for n, c in enumerate(line):
                if c in "\t ":
                    i = n
                    break
            if i <= 0:
                continue
            key = "".join(ch for ch in line[:i] if ch in "0123456789abcdefABCDEF").upper()
            if len(key) not in (6, 7, 9):
                continue
            rest = line[i:].strip()
            tab = rest.find("\t")
            vendor = (rest[:tab] if tab >= 0 else rest).strip()
            if vendor:
                table[key] = vendor
    return table


def _norm_hex(mac, n):
    out = []
    for c in mac:
        if c in "0123456789abcdefABCDEF":
            out.append(c.upper())
            if len(out) == n:
                break
    return "".join(out) if out else None


def classify(mac):
    h = _norm_hex(mac, 2)
    if not h or len(h) < 2:
        return "MALFORMED"
    octet = int(h, 16)
    if octet & 0x01:
        return "MULTICAST"
    if octet & 0x02:
        return "RANDOMIZED"
    return "GLOBAL"


def lookup(table, mac):
    kind = classify(mac)
    if kind == "RANDOMIZED":
        return RANDOMIZED
    if kind in ("MULTICAST", "MALFORMED"):
        return UNKNOWN
    h = _norm_hex(mac, 9)
    if not h:
        return UNKNOWN
    if len(h) >= 9 and h[:9] in table:
        return table[h[:9]]
    if len(h) >= 7 and h[:7] in table:
        return table[h[:7]]
    if len(h) >= 6 and h[:6] in table:
        return table[h[:6]]
    return UNKNOWN


# ---- checks ----------------------------------------------------------------

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "../app/src/main/assets/oui.txt"
    t0 = time.perf_counter()
    table = load(path)
    load_ms = (time.perf_counter() - t0) * 1000

    lens = {6: 0, 7: 0, 9: 0}
    key_chars = 0
    val_chars = 0
    for k, v in table.items():
        lens[len(k)] += 1
        key_chars += len(k)
        val_chars += len(v)

    print(f"== LOAD ==")
    print(f"entries        : {len(table):,}")
    print(f"  MA-L /24 (6) : {lens[6]:,}")
    print(f"  MA-M /28 (7) : {lens[7]:,}")
    print(f"  MA-S /36 (9) : {lens[9]:,}")
    print(f"parse time     : {load_ms:.0f} ms (CPython; ART will differ)")

    # Heap estimate for a Java HashMap<String,String> on ART (compact strings,
    # 1 byte/char): per entry ~ Node(32) + 2*String(~24 header + char[] data).
    est = 0
    for k, v in table.items():
        est += 32  # HashMap.Node
        est += 24 + ((len(k) + 7) & ~7)  # key String + char[]
        est += 24 + ((len(v) + 7) & ~7)  # value String + char[]
    est += 1 << (len(table)).bit_length()  # backing array (next pow2 of refs) *8
    print(f"\n== MEMORY (estimate) ==")
    print(f"raw string bytes : {(key_chars + val_chars):,}")
    print(f"est. heap (ART)  : ~{est / 1_048_576:.1f} MB (one-time, lives for process)")

    # Lookup benchmark.
    sample = list(table.keys())[:: max(1, len(table) // 5000)]
    macs = [k + "112233"[: 12 - len(k)] for k in sample]  # pad keys to full MACs
    t0 = time.perf_counter()
    reps = 20
    for _ in range(reps):
        for m in macs:
            lookup(table, m)
    total = reps * len(macs)
    ns = (time.perf_counter() - t0) * 1e9 / total
    print(f"\n== LOOKUP PERF ==")
    print(f"{total:,} lookups, ~{ns:.0f} ns/lookup (CPython upper bound; ART/JIT faster)")

    # Randomized / private MAC handling.
    print(f"\n== RANDOMIZED / PRIVATE MAC ==")
    cases = [
        ("DA:A1:19:12:34:56", RANDOMIZED, "bit1 set (locally administered)"),
        ("06:00:00:11:22:33", RANDOMIZED, "x6 -> LAA"),
        ("02:1A:11:00:00:00", RANDOMIZED, "x2 -> LAA"),
        ("01:00:5E:00:00:FB", UNKNOWN, "multicast (bit0)"),
        ("E8:9F:6D:AA:BB:CC", "Espressif", "global unicast resolves"),
    ]
    ok = True
    for mac, want, why in cases:
        got = lookup(table, mac)
        good = got == want
        ok = ok and good
        print(f"  [{'PASS' if good else 'FAIL'}] {mac} -> {got!r}  ({why})")

    # MA-M / MA-S correctness: a sub-block MAC must resolve to the real vendor,
    # never the registrar that owns the parent /24.
    print(f"\n== MA-M / MA-S vs PARENT /24 ==")
    subblocks = [k for k in table if len(k) in (7, 9)][:6]
    for key in subblocks:
        parent = key[:6]
        full = key + "0" * (12 - len(key))
        got = lookup(table, full)
        parent_owner = table.get(parent, "(parent /24 not listed)")
        tier = "MA-S /36" if len(key) == 9 else "MA-M /28"
        good = got == table[key]
        ok = ok and good
        print(f"  [{'PASS' if good else 'FAIL'}] {tier} {full} -> {got!r}")
        print(f"           parent {parent} owner = {parent_owner!r}")

    print(f"\n== RESULT == {'ALL CHECKS PASSED' if ok else 'FAILURES PRESENT'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
