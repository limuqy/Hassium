#!/usr/bin/env python3
"""Dump sky/block light nibble histograms from all hassium type-126 region chunks.

Usage: python scripts/dump-shadow-light.py <region_dir> [limit]
"""
from __future__ import annotations

import struct
import sys
from collections import Counter
from pathlib import Path

import zstandard as zstd


def read_nbt(buf: bytes):
    def payload(tag):
        if tag == 1:
            v = buf[r[0]]; r[0] += 1; return v
        if tag == 2:
            v = struct.unpack_from(">H", buf, r[0])[0]; r[0] += 2; return v
        if tag == 3:
            v = struct.unpack_from(">i", buf, r[0])[0]; r[0] += 4; return v
        if tag == 4:
            v = struct.unpack_from(">q", buf, r[0])[0]; r[0] += 8; return v
        if tag == 5:
            v = struct.unpack_from(">f", buf, r[0])[0]; r[0] += 4; return v
        if tag == 6:
            v = struct.unpack_from(">d", buf, r[0])[0]; r[0] += 8; return v
        if tag == 7:
            n = struct.unpack_from(">i", buf, r[0])[0]; r[0] += 4
            b = buf[r[0]:r[0] + n]; r[0] += n; return b
        if tag == 8:
            n = struct.unpack_from(">H", buf, r[0])[0]; r[0] += 2
            s = buf[r[0]:r[0] + n].decode("utf-8", "replace"); r[0] += n; return s
        if tag == 9:
            et = buf[r[0]]; r[0] += 1
            n = struct.unpack_from(">i", buf, r[0])[0]; r[0] += 4
            return [payload(et) for _ in range(n)]
        if tag == 10:
            d = {}
            while True:
                t = buf[r[0]]; r[0] += 1
                if t == 0:
                    break
                n = struct.unpack_from(">H", buf, r[0])[0]; r[0] += 2
                nm = buf[r[0]:r[0] + n].decode("utf-8", "replace"); r[0] += n
                d[nm] = payload(t)
            return d
        if tag == 11:
            n = struct.unpack_from(">i", buf, r[0])[0]; r[0] += 4
            a = [struct.unpack_from(">i", buf, r[0] + 4 * i)[0] for i in range(n)]
            r[0] += 4 * n; return a
        if tag == 12:
            n = struct.unpack_from(">i", buf, r[0])[0]; r[0] += 4
            a = [struct.unpack_from(">q", buf, r[0] + 8 * i)[0] for i in range(n)]
            r[0] += 8 * n; return a
        raise ValueError(f"tag {tag}")

    r = [0]
    if buf[0] != 10:
        raise ValueError("root not compound")
    r[0] = 1
    n = struct.unpack_from(">H", buf, r[0])[0]; r[0] += 2 + n
    return payload(10)


def nibbles(data: bytes):
    out = []
    for b in data:
        out.append(b & 15)
        out.append((b >> 4) & 15)
    return out


def main() -> int:
    region_dir = Path(sys.argv[1])
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 10**9
    dict_path = Path(__file__).resolve().parents[1] / \
        "common/src/main/resources/assets/hassium/hassium-dictionary.bin"
    dctx = zstd.ZstdDecompressor(
        dict_data=zstd.ZstdCompressionDict(dict_path.read_bytes()))
    chunks = 0
    sec_total = 0
    sec_all15 = 0
    sec_all0 = 0
    sky_hist = Counter()
    below_all15 = 0
    below_total = 0

    for mca in sorted(region_dir.glob("r.*.mca")):
        raw = mca.read_bytes()
        for idx in range(1024):
            if chunks >= limit:
                break
            entry = struct.unpack_from(">I", raw, idx * 4)[0]
            sector = entry >> 8
            count = entry & 0xFF
            if count == 0 or sector == 0:
                continue
            pos = sector * 4096
            if pos + 5 > len(raw):
                continue
            length = struct.unpack_from(">I", raw, pos)[0]
            ctype = raw[pos + 4]
            if ctype != 126:
                continue
            payload = raw[pos + 5: pos + 4 + length]
            off = 9 if payload and payload[0] == 0x48 else 0
            try:
                nbt = dctx.decompress(payload[off:], max_output_size=80_000_000)
            except Exception:
                continue
            try:
                root = read_nbt(nbt)
            except Exception:
                continue
            chunks += 1
            cx, cz = root.get("xPos"), root.get("zPos")
            for sec in (root.get("sections") or []):
                y = sec.get("Y")
                sky = sec.get("SkyLight")
                if sky is None or len(sky) != 2048:
                    continue
                sec_total += 1
                if all(b == 0xFF for b in sky):
                    sec_all15 += 1
                if all(b == 0 for b in sky):
                    sec_all0 += 1
                for v in nibbles(sky):
                    sky_hist[v] += 1
                # below-surface sample: section top <= 62 (typical ground)
                if y is not None and y * 16 + 15 <= 62:
                    below_total += 1
                    if all(b == 0xFF for b in sky):
                        below_all15 += 1
            if chunks % 200 == 0:
                print(f"  ..{chunks} chunks", flush=True)

    total_nib = sum(sky_hist.values())
    print(f"chunks={chunks} sections={sec_total}")
    print(f"sky all-15 sections = {sec_all15} ({100.0*sec_all15/max(1,sec_total):.1f}%)")
    print(f"sky all-0  sections = {sec_all0} ({100.0*sec_all0/max(1,sec_total):.1f}%)")
    print(f"below-y62 sections  = {below_total}, of which all-15 = {below_all15} "
          f"({100.0*below_all15/max(1,below_total):.1f}%)")
    print("sky nibble histogram (value:pct):")
    for v in range(16):
        c = sky_hist.get(v, 0)
        print(f"  {v:2d}: {100.0*c/max(1,total_nib):5.1f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
