#!/usr/bin/env python3
"""Probe hassium type-126 region slot for chunk (-13, 3).

Anvil: header 4096 + timestamps 4096; slot = ((x&15) + (z&15)*32)*4
payload: [offset 3B][sectorCount 1B] → sector*4096
chunk data: [length 4B][compressionType 1B][payload...]
type 126: [0x48][hash 8B][zstd frame...]
"""
from __future__ import annotations

import json
import struct
import sys
from collections import Counter
from pathlib import Path

REGION = Path(
    r"D:\project\MC\Hassium\fabric\run\client\hassium_cache\server_127.0.0.1_25565\world\region\r.-1.0.mca"
)
CX, CZ = -13, 3
MIN_Y_SECTION = -4  # 1.18+ typical; printed from NBT if present


def nibble_values(data: bytes) -> list[int]:
    out = []
    for b in data:
        out.append(b & 15)
        out.append((b >> 4) & 15)
    return out


def summarize_light(arr: bytes | None, label: str) -> dict:
    if arr is None:
        return {"present": False}
    if len(arr) != 2048:
        return {"present": True, "len": len(arr), "error": "bad length"}
    vals = nibble_values(arr)
    c = Counter(vals)
    all_ff = all(b == 0xFF for b in arr)
    all_00 = all(b == 0 for b in arr)
    return {
        "present": True,
        "all_0xFF": all_ff,
        "all_0x00": all_00,
        "nibble_hist_top": c.most_common(8),
        "mean": sum(vals) / len(vals),
        "max": max(vals),
        "min": min(vals),
    }


def parse_nbt_compound_list_sections(nbt: bytes) -> dict:
    """Minimal NBT walk for top-level isLightOn + sections[].Y/sky_light/block_light."""
    # Use a tiny big-endian NBT reader
    import io

    class R:
        def __init__(self, b: bytes):
            self.b = b
            self.i = 0

        def u1(self):
            v = self.b[self.i]
            self.i += 1
            return v

        def u2(self):
            v = struct.unpack_from(">H", self.b, self.i)[0]
            self.i += 2
            return v

        def i4(self):
            v = struct.unpack_from(">i", self.b, self.i)[0]
            self.i += 4
            return v

        def i8(self):
            v = struct.unpack_from(">q", self.b, self.i)[0]
            self.i += 8
            return v

        def f4(self):
            v = struct.unpack_from(">f", self.b, self.i)[0]
            self.i += 4
            return v

        def f8(self):
            v = struct.unpack_from(">d", self.b, self.i)[0]
            self.i += 8
            return v

        def name(self):
            n = self.u2()
            s = self.b[self.i : self.i + n]
            self.i += n
            return s.decode("utf-8", "replace")

        def skip_payload(self, tag: int):
            if tag == 0:
                return None
            if tag == 1:
                return self.u1() if False else self.b[self.i]  # signed-ish
            # handle properly below in read_tag

    def read_payload(r: R, tag: int):
        if tag == 1:  # byte
            v = r.b[r.i]
            r.i += 1
            return v
        if tag == 2:  # short
            return r.u2()
        if tag == 3:  # int
            return r.i4()
        if tag == 4:  # long
            return r.i8()
        if tag == 5:  # float
            return r.f4()
        if tag == 6:  # double
            return r.f8()
        if tag == 7:  # byte array
            n = r.i4()
            b = r.b[r.i : r.i + n]
            r.i += n
            return b
        if tag == 8:  # string
            return r.name()
        if tag == 9:  # list
            et = r.u1()
            n = r.i4()
            return [read_payload(r, et) for _ in range(n)]
        if tag == 10:  # compound
            d = {}
            while True:
                t = r.u1()
                if t == 0:
                    break
                nm = r.name()
                d[nm] = read_payload(r, t)
            return d
        if tag == 11:  # int array
            n = r.i4()
            arr = [struct.unpack_from(">i", r.b, r.i + 4 * i)[0] for i in range(n)]
            r.i += 4 * n
            return arr
        if tag == 12:  # long array
            n = r.i4()
            arr = [struct.unpack_from(">q", r.b, r.i + 8 * i)[0] for i in range(n)]
            r.i += 8 * n
            return arr
        raise ValueError(f"unknown tag {tag}")

    r = R(nbt)
    root_tag = r.u1()
    if root_tag != 10:
        raise ValueError(f"root tag {root_tag}")
    r.name()  # root name
    root = read_payload(r, 10)
    return root


def try_zstd(data: bytes) -> bytes:
    try:
        import zstandard as zstd

        return zstd.ZstdDecompressor().decompress(data, max_output_size=80_000_000)
    except Exception:
        pass
    # try raw / stream
    try:
        import zstandard as zstd

        dctx = zstd.ZstdDecompressor()
        with dctx.stream_reader(__import__("io").BytesIO(data)) as reader:
            return reader.read()
    except Exception as e:
        raise RuntimeError(f"zstd decompress failed: {e}")


def main() -> int:
    raw = REGION.read_bytes()
    # Anvil region = 32x32 chunks (1024 header slots), NOT 16x16.
    slot_x = CX & 31
    slot_z = CZ & 31
    idx = slot_x + slot_z * 32
    # Location table is the FIRST 4096 bytes (1024 entries x 4B); timestamps follow.
    off = idx * 4
    entry = struct.unpack_from(">I", raw, off)[0]
    sector = entry >> 8
    count = entry & 0xFF
    print(f"region={REGION}")
    print(f"chunk=({CX},{CZ}) slot=({slot_x},{slot_z}) header_entry=0x{entry:08x} sector={sector} count={count}")
    if count == 0:
        print("EMPTY_SLOT: no data for this chunk")
        return 2
    pos = sector * 4096
    length = struct.unpack_from(">I", raw, pos)[0]
    ctype = raw[pos + 4]
    payload = raw[pos + 5 : pos + 4 + length]
    print(f"length={length} compression_type={ctype} payload_len={len(payload)}")
    print(f"payload_head_hex={payload[:16].hex()}")
    hash_val = None
    zstd_off = 0
    if ctype == 126:
        if payload and payload[0] == 0x48:
            hash_val = int.from_bytes(payload[1:9], "big")
            zstd_off = 9
        else:
            zstd_off = 0
        print(f"type126 hash={hash_val} zstd_off={zstd_off}")
        compressed = payload[zstd_off:]
        try:
            nbt = try_zstd(compressed)
        except RuntimeError as e:
            print(f"DECOMPRESS_FAIL: {e}")
            # dump as hex head for manual zstd
            Path("build/smoke-test/probe_chunk_-13_3.bin").write_bytes(compressed)
            print("wrote compressed to build/smoke-test/probe_chunk_-13_3.bin")
            return 3
    else:
        import gzip
        import zlib

        if ctype == 2:
            nbt = zlib.decompress(payload)
        elif ctype == 1:
            nbt = gzip.decompress(payload)
        elif ctype == 3:
            nbt = payload
        else:
            print(f"unknown compression {ctype}")
            return 4

    out_nbt = Path("build/smoke-test/probe_chunk_-13_3.nbt")
    out_nbt.write_bytes(nbt)
    print(f"nbt_bytes={len(nbt)} saved={out_nbt}")
    root = parse_nbt_compound_list_sections(nbt)
    print("root_keys", sorted(root.keys())[:40])
    print("isLightOn", root.get("isLightOn"), "Status", root.get("Status"), "xPos", root.get("xPos"), "zPos", root.get("zPos"))
    sections = root.get("sections") or root.get("Sections") or []
    print(f"sections={len(sections)}")
    report = []
    for sec in sections:
        if not isinstance(sec, dict):
            continue
        y = sec.get("Y", sec.get("y"))
        sky = sec.get("SkyLight") or sec.get("sky_light")
        blk = sec.get("BlockLight") or sec.get("block_light")
        bs = sec.get("block_states") or sec.get("BlockStates")
        rec = {
            "Y": y,
            "sky": summarize_light(sky, "sky"),
            "block": summarize_light(blk, "block"),
            "has_block_states": bs is not None,
        }
        report.append(rec)
    # print compact
    for rec in report:
        sky, blk = rec["sky"], rec["block"]
        if not sky.get("present") and not blk.get("present"):
            continue
        print(
            f"  Y={rec['Y']}: sky all15={sky.get('all_0xFF')} all0={sky.get('all_0x00')} "
            f"hist={sky.get('nibble_hist_top')} | block all15={blk.get('all_0xFF')} all0={blk.get('all_0x00')} "
            f"hist={blk.get('nibble_hist_top')}"
        )
    Path("build/smoke-test/probe_chunk_-13_3_report.json").write_text(
        json.dumps({"chunk": [CX, CZ], "isLightOn": root.get("isLightOn"), "sections": report}, indent=2),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
