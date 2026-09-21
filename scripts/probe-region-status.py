#!/usr/bin/env python3
"""Throwaway probe: 影子 region 槽的 (Status, 是否带 0x48 内容 hash) 直方图。

Anvil 布局: header 4096 + timestamps 4096；槽 = ((x&31) + (z&31)*32)*4
槽载荷: [offset 3B][sectorCount 1B] → sector*4096
柱: [length 4B][type 1B][payload...]
type 126: [0x48][hash 8B][ZSTD(字典)] 或 [ZSTD(字典)]（旧形态无 hash）
"""
from __future__ import annotations

import collections
import struct
import sys
import zstandard
from pathlib import Path

DICT = Path("common/src/main/resources/assets/hassium/hassium-dictionary.bin")
TYPE_HASSIUM = 126
HASH_MAGIC = 0x48


def read_region(path: Path) -> list[tuple[int, int, bytes, int]]:
    """-> [(x, z, payload_after_type, compression_type)]"""
    raw = path.read_bytes()
    if len(raw) < 8192:
        return []
    out = []
    for i in range(1024):
        entry = struct.unpack(">I", raw[i * 4 : i * 4 + 4])[0]
        sector = entry >> 8
        count = entry & 0xFF
        if sector == 0 or count == 0:
            continue
        off = sector * 4096
        if off + 5 > len(raw):
            continue
        length = struct.unpack(">I", raw[off : off + 4])[0]
        ctype = raw[off + 4]
        if length > 1:
            payload = raw[off + 5 : off + 5 + (length - 1)]
        else:
            payload = raw[off + 5 : off + count * 4096]
        out.append((i % 32, i // 32, payload, ctype))
    return out


class Nbt:
    """只走够用的 NBT：顶层 compound 里取 Status 字符串。"""

    def __init__(self, buf: bytes) -> None:
        self.b = buf
        self.i = 0

    def u1(self) -> int:
        v = self.b[self.i]
        self.i += 1
        return v

    def u2(self) -> int:
        v = struct.unpack_from(">H", self.b, self.i)[0]
        self.i += 2
        return v

    def u4(self) -> int:
        v = struct.unpack_from(">I", self.b, self.i)[0]
        self.i += 4
        return v

    def u8(self) -> int:
        v = struct.unpack_from(">Q", self.b, self.i)[0]
        self.i += 8
        return v

    def name(self) -> str:
        n = self.u2()
        s = self.b[self.i : self.i + n].decode("utf-8", "replace")
        self.i += n
        return s

    def string(self) -> str:
        n = self.u2()
        s = self.b[self.i : self.i + n].decode("utf-8", "replace")
        self.i += n
        return s

    def skip(self, tag: int) -> None:
        if tag == 1:
            self.i += 1
        elif tag == 2:
            self.i += 2
        elif tag == 3:
            self.i += 4
        elif tag == 4:
            self.i += 8
        elif tag == 5:
            self.i += 4
        elif tag == 6:
            self.i += 8
        elif tag == 7:
            n = self.u4()
            self.i += max(0, min(n, len(self.b) - self.i))
        elif tag == 8:
            self.string()
        elif tag == 9:
            inner = self.u1()
            n = self.u4()
            for _ in range(min(n, 1 << 22)):
                self.skip(inner)
        elif tag == 10:
            while True:
                t = self.u1()
                if t == 0:
                    break
                self.name()
                self.skip(t)
        elif tag == 11:
            n = self.u4()
            self.i += min(4 * n, max(0, len(self.b) - self.i))
        elif tag == 12:
            n = self.u4()
            self.i += min(8 * n, max(0, len(self.b) - self.i))
        else:
            raise ValueError(f"bad tag {tag} @{self.i}")


def top_level_status(nbt: bytes) -> str | None:
    r = Nbt(nbt)
    root = r.u1()
    if root != 10:
        return None
    r.name()
    while True:
        t = r.u1()
        if t == 0:
            return None
        nm = r.name()
        if t == 8 and nm == "Status":
            return r.string()
        r.skip(t)


def main() -> int:
    roots = sys.argv[1:] or ["fabric/run/client/hassium_cache"]
    dctx = zstandard.ZstdDecompressor(
        dict_data=zstandard.ZstdCompressionDict(DICT.read_bytes())
    )
    status_hash = collections.Counter()
    status_nohash = collections.Counter()
    errors = collections.Counter()
    per_type = collections.Counter()
    for root in roots:
        for mca in sorted(Path(root).rglob("region/*.mca")):
            for x, z, payload, ctype in read_region(mca):
                per_type[ctype] += 1
                if ctype != TYPE_HASSIUM:
                    errors[f"type={ctype}"] += 1
                    continue
                has_hash = len(payload) > 8 and payload[0] == HASH_MAGIC
                body = payload[9:] if has_hash else payload
                try:
                    nbt = dctx.decompress(body, max_output_size=1 << 24)
                except Exception as exc:  # noqa: BLE001
                    errors[f"decomp:{type(exc).__name__}"] += 1
                    continue
                try:
                    st = top_level_status(nbt) or "<none>"
                except Exception as exc:  # noqa: BLE001
                    errors[f"nbt:{type(exc).__name__}"] += 1
                    continue
                (status_hash if has_hash else status_nohash)[st] += 1
    print("槽 compressionType 分布:", dict(per_type))
    print("\n带 0x48 hash 的列 —— 按 Status:")
    for st, n in status_hash.most_common():
        print(f"  {n:6d}  {st}")
    print("\n无 hash 的列 —— 按 Status:")
    for st, n in status_nohash.most_common():
        print(f"  {n:6d}  {st}")
    if errors:
        print("\n错误:", dict(errors))
    return 0


if __name__ == "__main__":
    sys.exit(main())
