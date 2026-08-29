#!/usr/bin/env python3
"""Compare R1 shadow-cache chunks with their post-R2 versions.

Input is the directory emitted by SmokeProbeWriter:
  r2-full-fallback/r1-world/
  r2-full-fallback/fallback-events.jsonl
The post-R2 world is supplied with --r2-world.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import struct
import zlib
from pathlib import Path
from collections import Counter

from typing import Any
try:
    zstandard = __import__("zstandard")
except ImportError:
    zstandard = None


class NbtReader:
    def __init__(self, data: bytes):
        self.fp = memoryview(data)
        self.pos = 0

    def take(self, n: int) -> bytes:
        out = self.fp[self.pos:self.pos + n].tobytes()
        if len(out) != n:
            raise ValueError("truncated NBT")
        self.pos += n
        return out

    def u8(self) -> int:
        return self.take(1)[0]

    def i8(self) -> int:
        return struct.unpack(">b", self.take(1))[0]

    def i16(self) -> int:
        return struct.unpack(">h", self.take(2))[0]

    def i32(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def i64(self) -> int:
        return struct.unpack(">q", self.take(8))[0]

    def string(self) -> str:
        return self.take(self.i16()).decode("utf-8", "replace")

    def tag_payload(self, tag: int) -> Any:
        if tag == 1:
            return self.i8()
        if tag == 2:
            return self.i16()
        if tag == 3:
            return self.i32()
        if tag == 4:
            return self.i64()
        if tag == 5:
            return struct.unpack(">f", self.take(4))[0]
        if tag == 6:
            return struct.unpack(">d", self.take(8))[0]
        if tag == 7:
            n = self.i32()
            return list(self.take(n))
        if tag == 8:
            return self.string()
        if tag == 9:
            child = self.u8()
            n = self.i32()
            return [self.tag_payload(child) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                child = self.u8()
                if child == 0:
                    return out
                name = self.string()
                out[name] = self.tag_payload(child)
        if tag == 11:
            n = self.i32()
            return list(struct.unpack(f">{n}i", self.take(4 * n)))
        if tag == 12:
            n = self.i32()
            return list(struct.unpack(f">{n}q", self.take(8 * n)))
        raise ValueError(f"unsupported NBT tag {tag}")

    def root(self) -> dict[str, Any]:
        tag = self.u8()
        if tag != 10:
            raise ValueError("chunk root is not a compound")
        self.string()
        return self.tag_payload(tag)


def read_chunk(world: Path, x: int, z: int, dictionary: bytes | None) -> dict[str, Any] | None:
    region = world / "region" / f"r.{x >> 5}.{z >> 5}.mca"
    if not region.is_file():
        return None
    raw = region.read_bytes()
    slot = ((x & 31) + (z & 31) * 32) * 4
    if len(raw) < slot + 8:
        return None
    offset, sectors = struct.unpack(">II", raw[slot:slot + 8])
    offset >>= 8
    sectors &= 0xFF
    if offset == 0 or sectors == 0:
        return None
    start = offset * 4096
    if start + 5 > len(raw):
        return None
    length = struct.unpack(">I", raw[start:start + 4])[0]
    compression = raw[start + 4]
    payload = raw[start + 5:start + 4 + length]
    if compression == 1:
        payload = gzip.decompress(payload)
    elif compression == 2:
        payload = zlib.decompress(payload)
    elif compression == 126:
        if zstandard is None:
            raise ValueError("type126 requires: python -m pip install zstandard")
        if dictionary is None:
            raise ValueError("type126 requires --dictionary hassium-dictionary.bin")
        if payload[:1] == b"H" and len(payload) >= 9:
            payload = payload[9:]
        payload = zstandard.ZstdDecompressor(
            dict_data=zstandard.ZstdCompressionDict(dictionary)).decompress(payload)
    elif compression != 3:
        raise ValueError(f"unsupported MCA compression {compression}")


def level(root: dict[str, Any]) -> dict[str, Any]:
    value = root.get("Level")
    return value if isinstance(value, dict) else root


def section_map(root: dict[str, Any]) -> dict[int, dict[str, Any]]:
    sections = level(root).get("sections", level(root).get("Sections", []))
    out = {}
    for section in sections if isinstance(sections, list) else []:
        if isinstance(section, dict) and isinstance(section.get("Y"), int):
            out[section["Y"]] = section
    return out


def palette_names(block_states: Any) -> list[str]:
    if not isinstance(block_states, dict):
        return []
    palette = block_states.get("palette", block_states.get("Palette", []))
    return [str(p.get("Name", "")) for p in palette if isinstance(p, dict)]


def decode_states(block_states: Any) -> list[int] | None:
    if not isinstance(block_states, dict):
        return None
    palette = block_states.get("palette", block_states.get("Palette", []))
    data = block_states.get("data", block_states.get("BlockStates"))
    if not isinstance(palette, list):
        return None
    if not isinstance(data, list):
        return [0] * 4096
    bits = max(4, math.ceil(math.log2(max(1, len(palette)))))
    per_long = 64 // bits
    mask = (1 << bits) - 1
    values = []
    for i in range(4096):
        word = i // per_long
        shift = (i % per_long) * bits
        values.append((data[word] >> shift) & mask if word < len(data) else 0)
    return values


def state_summary(section: dict[str, Any]) -> dict[str, Any]:
    bs = section.get("block_states", section.get("BlockStates"))
    names = palette_names(bs)
    values = decode_states(bs)
    stones = 0
    if values is not None:
        stones = sum(1 for value in values if 0 <= value < len(names) and names[value] == "minecraft:stone")
    return {"paletteSize": len(names), "stoneCells": stones, "palette": names, "states": values}


def changed_paths(a: Any, b: Any, prefix: str = "") -> list[str]:
    if type(a) is not type(b):
        return [prefix or "<root>"]
    if isinstance(a, dict):
        keys = sorted(set(a) | set(b))
        out = []
        for key in keys:
            path = f"{prefix}.{key}" if prefix else key
            if key not in a or key not in b:
                out.append(path)
            else:
                out.extend(changed_paths(a[key], b[key], path))
        return out
    if isinstance(a, list):
        if len(a) != len(b):
            return [prefix + f"[len {len(a)}->{len(b)}]"]
        out = []
        for i, (left, right) in enumerate(zip(a, b)):
            out.extend(changed_paths(left, right, f"{prefix}[{i}]"))
            if len(out) > 32:
                return out[:32] + ["..."]
        return out
    return [] if a == b else [prefix]


def compare(r1: dict[str, Any], r2: dict[str, Any], x: int, z: int) -> dict[str, Any]:
    s1, s2 = section_map(r1), section_map(r2)
    sections = []
    for y in sorted(set(s1) | set(s2)):
        if y not in s1 or y not in s2:
            sections.append({"y": y, "status": "added" if y in s2 else "removed"})
            continue
        before, after = state_summary(s1[y]), state_summary(s2[y])
        states1, states2 = before.pop("states"), after.pop("states")
        cell_changes = sum(a != b for a, b in zip(states1 or [], states2 or [])) if states1 and states2 else None
        to_stone = from_stone = 0
        if states1 is not None and states2 is not None:
            names1, names2 = before["palette"], after["palette"]
            for a, b in zip(states1, states2):
                old = names1[a] if a < len(names1) else "<invalid>"
                new = names2[b] if b < len(names2) else "<invalid>"
                to_stone += old != "minecraft:stone" and new == "minecraft:stone"
                from_stone += old == "minecraft:stone" and new != "minecraft:stone"
        if before != after or cell_changes:
            sections.append({"y": y, "cellChanges": cell_changes, "toStone": to_stone,
                             "fromStone": from_stone, "r1": before, "r2": after})
    top1, top2 = level(r1).copy(), level(r2).copy()
    top1.pop("sections", None); top1.pop("Sections", None)
    top2.pop("sections", None); top2.pop("Sections", None)
    return {"x": x, "z": z, "changedSections": sections,
            "otherChangedPaths": changed_paths(top1, top2)}


def read_cell_dump(path: Path) -> dict[tuple[int, int, int, int], str]:
    cells = {}
    if not path.is_file():
        return cells
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t", 4)
        if len(fields) == 5:
            cells[tuple(map(int, fields[:4]))] = fields[4]
    return cells


def compare_cell_dumps(r1: Path, r2: Path) -> dict[str, Any] | None:
    before, after = read_cell_dump(r1), read_cell_dump(r2)
    if not before and not after:
        return None
    transitions = Counter()
    changed = 0
    for key in set(before) | set(after):
        old, new = before.get(key, "<missing>"), after.get(key, "<missing>")
        if old != new:
            changed += 1
            transitions[(old, new)] += 1
    return {"r1Cells": len(before), "r2Cells": len(after), "changedCells": changed,
            "topTransitions": [{"from": old, "to": new, "count": count}
                               for (old, new), count in transitions.most_common(20)]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fallback-dir", type=Path, required=True)
    parser.add_argument("--dictionary", type=Path,
                        default=Path("common/src/main/resources/assets/hassium/hassium-dictionary.bin"))
    parser.add_argument("--r2-world", type=Path, required=True)
    args = parser.parse_args()
    events = args.fallback_dir / "fallback-events.jsonl"
    positions: set[tuple[int, int]] = set()
    reasons: dict[tuple[int, int], list[str]] = {}
    if events.is_file():
        for line in events.read_text(encoding="utf-8").splitlines():
            try:
                record = json.loads(line)
                for point in record.get("positions", []):
                    key = (int(point[0]), int(point[1]))
                    positions.add(key)
                    reasons.setdefault(key, []).append(str(record.get("reason", "unknown")))
            except (ValueError, TypeError, json.JSONDecodeError):
                continue
    if not positions:
        raise SystemExit(f"no fallback positions in {events}")
    r1_world = args.fallback_dir / "r1-world"
    print(json.dumps({"r1World": str(r1_world), "r2World": str(args.r2_world),
                      "positions": sorted(positions),
                      "reasons": {f"{x},{z}": values for (x, z), values in reasons.items()}},
                     ensure_ascii=False, indent=2))
    for x, z in sorted(positions):
        dictionary = args.dictionary.read_bytes() if args.dictionary.is_file() else None
        cell_diff = compare_cell_dumps(
            args.fallback_dir / "r1-memory" / f"{x}.{z}.tsv",
            args.fallback_dir / "r2-memory" / f"{x}.{z}.tsv")
        if cell_diff is not None:
            print(json.dumps({"x": x, "z": z, "status": "memory-compared",
                              "reasons": reasons.get((x, z), []), **cell_diff},
                             ensure_ascii=False))
        r1, r2 = read_chunk(r1_world, x, z, dictionary), read_chunk(args.r2_world, x, z, dictionary)
        if r1 is None or r2 is None:
            print(json.dumps({"x": x, "z": z, "status": "missing",
                              "reasons": reasons.get((x, z), []),
                              "r1": r1 is not None, "r2": r2 is not None,
                              "rawPacket": str(args.fallback_dir / "r2-packets" / f"{x}.{z}.packet")
                                  if (args.fallback_dir / "r2-packets" / f"{x}.{z}.packet").is_file()
                                  else None}))
            continue
        print(json.dumps(compare(r1, r2, x, z), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
