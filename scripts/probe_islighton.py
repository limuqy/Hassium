#!/usr/bin/env python3
"""Probe hassium type-126 region slots for isLightOn + light-array presence.

S6 归因用（2026-09-19）：R2 里被 `isPushableToClient` 挡下的光环柱，其落盘
`isLightOn` 到底是真还是假；顺带看光层数据在不在（在 = 只是 flag 被扣）。

用法: python scripts/probe_islighton.py x1,z1 x2,z2 ...
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from probe_cache_chunk import (  # noqa: E402
    parse_nbt_compound_list_sections,
    summarize_light,
    try_zstd,
)

CACHE = Path(
    r"D:\project\MC\Hassium\fabric\run\client\hassium_cache"
    r"\server_127.0.0.1_25565\world\region"
)


def region_path(cx: int, cz: int) -> Path:
    return CACHE / f"r.{cx // 32}.{cz // 32}.mca"


def read_slot(raw: bytes, cx: int, cz: int):
    idx = (cx & 31) + (cz & 31) * 32
    entry = struct.unpack_from(">I", raw, idx * 4)[0]
    sector = entry >> 8
    count = entry & 0xFF
    if count == 0:
        return None
    pos = sector * 4096
    length = struct.unpack_from(">I", raw, pos)[0]
    ctype = raw[pos + 4]
    payload = raw[pos + 5 : pos + 4 + length]
    if ctype != 126:
        return ("non-126", ctype, None)
    zstd_off = 9 if payload and payload[0] == 0x48 else 0
    return ("ok", ctype, try_zstd(payload[zstd_off:]))


def probe(cx: int, cz: int) -> None:
    rp = region_path(cx, cz)
    tag = f"({cx},{cz})"
    if not rp.exists():
        print(f"{tag}: region missing {rp.name}")
        return
    got = read_slot(rp.read_bytes(), cx, cz)
    if got is None:
        print(f"{tag}: EMPTY_SLOT ({rp.name})")
        return
    kind, ctype, nbt = got
    if kind != "ok":
        print(f"{tag}: ctype={ctype} (not type126)")
        return
    root = parse_nbt_compound_list_sections(nbt)
    secs = root.get("sections") or root.get("Sections") or []
    with_sky = 0
    sky_all15 = 0
    sky_all0 = 0
    for sec in secs:
        if not isinstance(sec, dict):
            continue
        sky = sec.get("SkyLight") or sec.get("sky_light")
        s = summarize_light(sky, "sky")
        if s.get("present"):
            with_sky += 1
            if s.get("all_0xFF"):
                sky_all15 += 1
            if s.get("all_0x00"):
                sky_all0 += 1
    print(
        f"{tag}: isLightOn={root.get('isLightOn')} status={root.get('Status')} "
        f"sections={len(secs)} sky_sections={with_sky} sky_all15={sky_all15} sky_all0={sky_all0} "
        f"({rp.name})"
    )


def main() -> int:
    for arg in sys.argv[1:]:
        cx, cz = (int(v) for v in arg.split(","))
        probe(cx, cz)
    return 0


if __name__ == "__main__":
    sys.exit(main())
