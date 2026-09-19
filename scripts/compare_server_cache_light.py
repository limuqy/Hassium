#!/usr/bin/env python3
"""Compare vanilla dedicated-server region light vs hassium client cache."""
from __future__ import annotations

import gzip
import struct
import zlib
from collections import Counter
from pathlib import Path

SERVER = Path(r"D:\project\MC\Hassium\fabric\run\server\parity_fabric_1_20_1")
CLIENT_CACHE = Path(
    r"D:\project\MC\Hassium\fabric\run\client\hassium_cache\server_127.0.0.1_25565\world"
)


def load_chunk_nbt(region_path: Path, cx: int, cz: int):
    if not region_path.exists():
        return None, f"missing {region_path}"
    raw = region_path.read_bytes()
    # Anvil region = 32x32 chunks (1024 header slots), NOT 16x16.
    rx, rz = cx >> 5, cz >> 5
    lx, lz = cx - rx * 32, cz - rz * 32
    idx = lx + lz * 32
    e = struct.unpack_from(">I", raw, idx * 4)[0]
    sector, count = e >> 8, e & 0xFF
    if count == 0:
        return None, f"empty slot local=({lx},{lz}) in {region_path.name}"
    pos = sector * 4096
    length = struct.unpack_from(">I", raw, pos)[0]
    ctype = raw[pos + 4]
    payload = raw[pos + 5 : pos + 4 + length]
    if ctype == 2:
        nbt = zlib.decompress(payload)
    elif ctype == 1:
        nbt = gzip.decompress(payload)
    elif ctype == 3:
        nbt = payload
    elif ctype == 126:
        return None, f"type126 payload (need dict) head={payload[:12].hex()}"
    else:
        return None, f"unknown ctype={ctype}"
    return nbt, f"ctype={ctype} length={length} sector={sector}"


def analyze_nbt(nbt: bytes, label: str) -> None:
    print(f"\n===== {label} nbt={len(nbt)} =====")
    io = nbt.find(b"isLightOn")
    if io >= 0:
        print("  isLightOn bytes after name:", nbt[io + 9 : io + 12].hex())
    else:
        print("  isLightOn ABSENT")
    stats = []
    idx = 0
    while True:
        i = nbt.find(b"SkyLight", idx)
        if i < 0:
            break
        j = i + 8
        alen = int.from_bytes(nbt[j : j + 4], "big")
        data = nbt[j + 4 : j + 4 + alen]
        allff = bool(data) and all(b == 0xFF for b in data)
        all00 = bool(data) and all(b == 0 for b in data)
        hist = Counter(data).most_common(4)
        stats.append((alen, allff, all00, hist, data[:8].hex() if data else ""))
        idx = i + 1
    print(f"  SkyLight arrays: {len(stats)}")
    ff = sum(1 for s in stats if s[1])
    print(f"  allFF={ff} / {len(stats)}")
    for k, s in enumerate(stats[:8]):
        print(f"    [{k}] alen={s[0]} allFF={s[1]} all00={s[2]} hist={s[3]} head={s[4]}")
    bi = nbt.find(b"BlockLight")
    if bi >= 0:
        j = bi + 10
        alen = int.from_bytes(nbt[j : j + 4], "big")
        data = nbt[j + 4 : j + 4 + min(alen, 32)]
        print(f"  BlockLight first alen={alen} head={data.hex()}")


def main() -> None:
    print("SERVER ROOT", SERVER)
    for sub in ["region", "world/region"]:
        d = SERVER / sub
        if d.exists():
            print(" ", sub, "->", list(d.glob("*.mca"))[:6])

    cases = [
        (SERVER / "region" / "r.-1.0.mca", -10, 3, "server (-10,3)"),
        (SERVER / "world" / "region" / "r.-1.0.mca", -10, 3, "server/world (-10,3)"),
        (SERVER / "region" / "r.0.0.mca", 0, 0, "server (0,0)"),
        (SERVER / "world" / "region" / "r.0.0.mca", 0, 0, "server/world (0,0)"),
        (SERVER / "region" / "r.-1.0.mca", -13, 3, "server (-13,3)"),
        (CLIENT_CACHE / "region" / "r.-1.0.mca", -10, 3, "client-cache (-10,3)"),
        (CLIENT_CACHE / "region" / "r.0.0.mca", 0, 0, "client-cache (0,0)"),
    ]
    for path, cx, cz, label in cases:
        nbt, meta = load_chunk_nbt(path, cx, cz)
        print(f"\n--- {label} {meta}")
        if nbt is None:
            continue
        if label.startswith("client-cache") and b"SkyLight" in nbt:
            analyze_nbt(nbt, label)
        elif not label.startswith("client-cache"):
            analyze_nbt(nbt, label)


if __name__ == "__main__":
    main()
