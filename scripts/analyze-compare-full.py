#!/usr/bin/env python3
"""compare FULL 取证解析：逐柱对比服务端 FULL 包与影子端本地基线。

数据来源：`CompareFullDump`（`common/.../protocol/CompareFullDump.java`），由
`runtime-smoke-test.ps1 -CompareFullDumpDir <dir>` 打开。每个 FULL 落盘三件套：
  server-full.bin   服务端解压后的原版区块包（`ShadowServerCompat.encodeLevelChunkPacket`）
  local-baseline.bin 影子端本地生成柱，同一 vanilla codec 编码
  metadata.txt      维度/坐标/两侧字节数/sha256

线格式（1.21.1 mojmap，逐字取自反编译源码，可用 `minecraft_dev_get_minecraft_source` 复核
`ClientboundLevelChunkWithLightPacket` / `ClientboundLevelChunkPacketData` / `ClientboundLightUpdatePacketData`）：
  ClientboundLevelChunkWithLightPacket
    int32 x, int32 z
    ClientboundLevelChunkPacketData
      heightmaps : 无名 CompoundTag（type byte + payload，无 name；0x00 结束）
      varint bufferLen; byte[] buffer      # 逐 section: short nonEmptyBlockCount
                                           #   + PalettedContainer(states) + PalettedContainer(biomes)
      varint beCount; {byte packedXZ, short y, varint typeId, nbt}
    ClientboundLightUpdatePacketData
      BitSet skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask  # varint n + n×int64
      varint skyCount;   {varint len, byte[len]}
      varint blockCount; {varint len, byte[len]}

PalettedContainer（`PalettedContainer.Data.write`）：
  byte bits; palette; varint storageLen; storageLen×int64
  palette：bits==0 → varint registryId（单值）
           1..4(states)→ linear，storageBits=4；1..3(biomes)→ linear，storageBits=bits
           5..8(states) → hashmap，storageBits=bits
           其余 → global，无 palette 体，storageBits=bits（= ceillog2(registrySize)）
  storage 位序同 `SimpleBitStorage`：valuesPerLong=64//bits，i → long[i//vpl] 的 (i%vpl)*bits 位。

本脚本只做**同版本内 id 级比较**（两侧 global id 同源，无需方块名映射）。
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import statistics
import sys

# ---------------------------------------------------------------- 读取原语


class Reader:
    __slots__ = ("buf", "pos")

    def __init__(self, buf: bytes) -> None:
        self.buf = buf
        self.pos = 0

    def remaining(self) -> int:
        return len(self.buf) - self.pos

    def u8(self) -> int:
        v = self.buf[self.pos]
        self.pos += 1
        return v

    def i16(self) -> int:
        v = int.from_bytes(self.buf[self.pos:self.pos + 2], "big", signed=True)
        self.pos += 2
        return v

    def i32(self) -> int:
        v = int.from_bytes(self.buf[self.pos:self.pos + 4], "big", signed=True)
        self.pos += 4
        return v

    def i64(self) -> int:
        v = int.from_bytes(self.buf[self.pos:self.pos + 8], "big", signed=True)
        self.pos += 8
        return v

    def varint(self) -> int:
        result = 0
        shift = 0
        while True:
            b = self.u8()
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7
            if shift > 35:
                raise ValueError("varint too long")

    def bytes(self, n: int) -> bytes:
        v = self.buf[self.pos:self.pos + n]
        if len(v) != n:
            raise EOFError(f"want {n} bytes, got {len(v)}")
        self.pos += n
        return v

    def utf(self) -> str:
        n = int.from_bytes(self.bytes(2), "big")
        return self.bytes(n).decode("utf-8", "replace")

    def long_array(self) -> list[int]:
        n = self.varint()
        return [self.i64() for _ in range(n)]

    def bitset(self) -> set[int]:
        out: set[int] = set()
        for idx, word in enumerate(self.long_array()):
            if word:
                for bit in range(64):
                    if word >> bit & 1:
                        out.add(idx * 64 + bit)
        return out


# ---------------------------------------------------------------- NBT（仅需 heightmap / BE 用到的类型）

NBT_END, NBT_BYTE, NBT_SHORT, NBT_INT, NBT_LONG, NBT_FLOAT, NBT_DOUBLE = 0, 1, 2, 3, 4, 5, 6
NBT_BYTE_ARRAY, NBT_STRING, NBT_LIST, NBT_COMPOUND = 7, 8, 9, 10
NBT_INT_ARRAY, NBT_LONG_ARRAY = 11, 12


def read_payload(r: Reader, tag_id: int):
    if tag_id == NBT_BYTE:
        v = r.u8()
        return v - 256 if v > 127 else v
    if tag_id == NBT_SHORT:
        return r.i16()
    if tag_id == NBT_INT:
        return r.i32()
    if tag_id == NBT_LONG:
        return r.i64()
    if tag_id == NBT_FLOAT:
        return int.from_bytes(r.bytes(4), "big")
    if tag_id == NBT_DOUBLE:
        return int.from_bytes(r.bytes(8), "big")
    if tag_id == NBT_BYTE_ARRAY:
        n = r.i32()
        return r.bytes(n)
    if tag_id == NBT_STRING:
        return r.utf()
    if tag_id == NBT_LIST:
        elem = r.u8()
        n = r.i32()
        return [read_payload(r, elem) for _ in range(n)]
    if tag_id == NBT_COMPOUND:
        return read_compound_body(r)
    if tag_id == NBT_INT_ARRAY:
        n = r.i32()
        return [r.i32() for _ in range(n)]
    if tag_id == NBT_LONG_ARRAY:
        n = r.i32()
        return [r.i64() for _ in range(n)]
    raise ValueError(f"unsupported nbt tag {tag_id}")


def read_compound_body(r: Reader) -> dict:
    out: dict = {}
    while True:
        tag_id = r.u8()
        if tag_id == NBT_END:
            return out
        name = r.utf()
        out[name] = read_payload(r, tag_id)


def read_nbt(r: Reader):
    """`FriendlyByteBuf.readNbt()` = `NbtIo.readAnyTag`：type byte + 无名 payload。"""
    tag_id = r.u8()
    if tag_id == NBT_END:
        return None
    return read_payload(r, tag_id)


# ---------------------------------------------------------------- 区块包


def read_container(r: Reader, is_biomes: bool) -> tuple[int, list[int], int]:
    """返回 (bits, 展开后的全局 id 列表, 元素数)。"""
    bits = r.u8()
    size = 64 if is_biomes else 4096
    if bits == 0:
        # 单值调色板：ZeroBitStorage，storage 为空数组，全格同值
        value = r.varint()
        r.long_array()
        return bits, [value] * size, size

    palette: list[int] = []
    storage_bits = bits
    if is_biomes:
        if 1 <= bits <= 3:
            palette = [r.varint() for _ in range(r.varint())]
        else:
            storage_bits = bits  # global，无 palette 体
    else:
        if 1 <= bits <= 4:
            storage_bits = 4
            palette = [r.varint() for _ in range(r.varint())]
        elif 5 <= bits <= 8:
            palette = [r.varint() for _ in range(r.varint())]
        else:
            storage_bits = bits  # global

    longs = r.long_array()
    values_per_long = 64 // storage_bits
    mask = (1 << storage_bits) - 1
    out = [0] * size
    for i in range(size):
        word = longs[i // values_per_long]
        out[i] = (word >> ((i % values_per_long) * storage_bits)) & mask
    if palette:
        out = [palette[v] for v in out]
    return bits, out, size


def read_section(r: Reader) -> dict:
    non_empty = r.i16()
    state_bits, states, _ = read_container(r, False)
    biome_bits, biomes, _ = read_container(r, True)
    return {"nonEmpty": non_empty, "stateBits": state_bits, "states": states,
            "biomeBits": biome_bits, "biomes": biomes}


def parse_packet(data: bytes) -> dict:
    r = Reader(data)
    out: dict = {}
    out["x"] = r.i32()
    out["z"] = r.i32()
    out["heightmaps"] = read_nbt(r) or {}
    buf_len = r.varint()
    buf = Reader(r.bytes(buf_len))
    sections = []
    while buf.remaining() > 0:
        sections.append(read_section(buf))
    out["sections"] = sections
    be_count = r.varint()
    bes = []
    for _ in range(be_count):
        packed = r.u8()
        y = r.i16()
        type_id = r.varint()
        tag = read_nbt(r)
        bes.append({"packedXZ": packed, "y": y, "type": type_id, "tag": tag})
    out["blockEntities"] = bes
    out["light"] = {
        "skyMask": r.bitset(),
        "blockMask": r.bitset(),
        "emptySkyMask": r.bitset(),
        "emptyBlockMask": r.bitset(),
        "skyUpdates": [r.bytes(r.varint()) for _ in range(r.varint())],
        "blockUpdates": [r.bytes(r.varint()) for _ in range(r.varint())],
    }
    out["trailing"] = r.remaining()
    return out


# ---------------------------------------------------------------- 差异


def decode_heightmap(longs: list[int]) -> list[int] | None:
    """1.21.1 `Heightmap` = `SimpleBitStorage(ceillog2(height+1), 256)`；位宽由 long 数反推
    （valuesPerLong = 64 // bits，longs = ceil(256 / valuesPerLong)）。"""
    for bits in range(1, 16):
        values_per_long = 64 // bits
        if -(-256 // values_per_long) != len(longs):
            continue
        mask = (1 << bits) - 1
        out = []
        for i in range(256):
            word = longs[i // values_per_long]
            out.append((word >> ((i % values_per_long) * bits)) & mask)
        return out
    return None


def diff_pair(server: dict, local: dict) -> dict:
    d: dict = {}

    # heightmap：逐 key 逐列比较
    hm_s, hm_l = server["heightmaps"], local["heightmaps"]
    hm_diff = {}
    for key in sorted(set(hm_s) | set(hm_l)):
        a, b = hm_s.get(key), hm_l.get(key)
        if a is None or b is None:
            hm_diff[key] = -1  # 单侧缺失
            continue
        if not isinstance(a, list) or not isinstance(b, list) or len(a) != len(b):
            hm_diff[key] = -2
            continue
        cols_a, cols_b = decode_heightmap(a), decode_heightmap(b)
        if cols_a is None or cols_b is None:
            hm_diff[key] = sum(1 for i in range(len(a)) if a[i] != b[i])  # 回落到 long 级
        else:
            n = sum(1 for i in range(256) if cols_a[i] != cols_b[i])
            if n:
                hm_diff[key] = n
    d["heightmapDiff"] = hm_diff

    # section：逐格比较 global state id / biome id
    sec_s, sec_l = server["sections"], local["sections"]
    d["sectionCount"] = (len(sec_s), len(sec_l))
    per_section = []
    total_block_diff = 0
    total_biome_diff = 0
    for idx in range(min(len(sec_s), len(sec_l))):
        a, b = sec_s[idx], sec_l[idx]
        bd = sum(1 for i in range(4096) if a["states"][i] != b["states"][i])
        bid = sum(1 for i in range(64) if a["biomes"][i] != b["biomes"][i])
        if bd or bid or a["nonEmpty"] != b["nonEmpty"]:
            per_section.append({"section": idx, "blockDiff": bd, "biomeDiff": bid,
                                "nonEmpty": (a["nonEmpty"], b["nonEmpty"]),
                                "stateBits": (a["stateBits"], b["stateBits"])})
        total_block_diff += bd
        total_biome_diff += bid
    d["perSection"] = per_section
    d["blockDiffTotal"] = total_block_diff
    d["biomeDiffTotal"] = total_biome_diff
    d["sectionsDiffering"] = len(per_section)

    # 差异方块的 id 分布（服务端侧 id → 本地侧 id 计数）
    pairs: collections.Counter = collections.Counter()
    for idx in range(min(len(sec_s), len(sec_l))):
        a, b = sec_s[idx]["states"], sec_l[idx]["states"]
        for i in range(4096):
            if a[i] != b[i]:
                pairs[(a[i], b[i])] += 1
    d["idPairs"] = pairs

    # BE
    def be_key(be):
        return (be["packedXZ"], be["y"], be["type"])

    be_s = {be_key(be): be["tag"] for be in server["blockEntities"]}
    be_l = {be_key(be): be["tag"] for be in local["blockEntities"]}
    d["beOnlyServer"] = sorted(set(be_s) - set(be_l))
    d["beOnlyLocal"] = sorted(set(be_l) - set(be_s))
    d["beTagDiff"] = sorted(k for k in set(be_s) & set(be_l) if be_s[k] != be_l[k])

    # light（服务端 stripLight 下应为空）
    ls, ll = server["light"], local["light"]
    d["light"] = {
        "serverSkyMask": len(ls["skyMask"]), "serverBlockMask": len(ls["blockMask"]),
        "serverSkyUpdates": len(ls["skyUpdates"]), "serverBlockUpdates": len(ls["blockUpdates"]),
        "localSkyMask": len(ll["skyMask"]), "localBlockMask": len(ll["blockMask"]),
        "localSkyUpdates": len(ll["skyUpdates"]), "localBlockUpdates": len(ll["blockUpdates"]),
    }
    return d


# ---------------------------------------------------------------- 主流程


def load_block_names(path: str | None) -> dict[int, str]:
    """blocks.json（`java -DbundlerMainClass=net.minecraft.data.Main -jar minecraft_server.jar
    --reports`）→ global block-state id → `minecraft:name[k=v,...]`。"""
    if not path:
        return {}
    with open(path, encoding="utf-8") as fh:
        raw = json.load(fh)
    out: dict[int, str] = {}
    for name, info in raw.items():
        for state in info.get("states", []):
            props = state.get("properties") or {}
            suffix = "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else ""
            out[state["id"]] = name + suffix
    return out


def name_of(names: dict[int, str], block_id: int) -> str:
    return names.get(block_id, f"#{block_id}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dump_dir", help="CompareFullDump 输出根目录（含 <dimension>/<x>_<z>_<hash>/）")
    ap.add_argument("--json", help="把逐柱结果写成 JSON")
    ap.add_argument("--top", type=int, default=15, help="打印前 N 个差异最大的柱")
    ap.add_argument("--blocks", help="blocks.json（数据生成器 --reports 产物）→ 差异方块 id 出名字")
    args = ap.parse_args()
    block_names = load_block_names(args.blocks)

    cases = []
    for dim in sorted(os.listdir(args.dump_dir)):
        dim_dir = os.path.join(args.dump_dir, dim)
        if not os.path.isdir(dim_dir):
            continue
        for name in sorted(os.listdir(dim_dir)):
            case_dir = os.path.join(dim_dir, name)
            sp = os.path.join(case_dir, "server-full.bin")
            lp = os.path.join(case_dir, "local-baseline.bin")
            if not (os.path.exists(sp) and os.path.exists(lp)):
                continue
            with open(sp, "rb") as fh:
                server_bytes = fh.read()
            with open(lp, "rb") as fh:
                local_bytes = fh.read()
            cases.append((dim, name, server_bytes, local_bytes))

    print(f"样本数: {len(cases)}")
    if not cases:
        return 1

    results = []
    errors = 0
    for dim, name, sb, lb in cases:
        try:
            server = parse_packet(sb)
            local = parse_packet(lb)
        except Exception as exc:  # noqa: BLE001 - 取证脚本，逐柱隔离失败
            errors += 1
            print(f"  [parse-fail] {dim}/{name}: {type(exc).__name__}: {exc}")
            continue
        d = diff_pair(server, local)
        d["dim"] = dim
        d["name"] = name
        d["serverBytes"] = len(sb)
        d["localBytes"] = len(lb)
        d["serverSections"] = len(server["sections"])
        d["localSections"] = len(local["sections"])
        d["serverTrailing"] = server["trailing"]
        d["localTrailing"] = local["trailing"]
        results.append(d)

    print(f"解析成功: {len(results)}  解析失败: {errors}")

    # ---- 结构层
    print("\n== 结构 ==")
    print("  server sections 分布:", collections.Counter(r["serverSections"] for r in results))
    print("  local  sections 分布:", collections.Counter(r["localSections"] for r in results))
    print("  server trailing 非 0:", sum(1 for r in results if r["serverTrailing"]))
    print("  local  trailing 非 0:", sum(1 for r in results if r["localTrailing"]))
    print("  server 字节 min/med/max: %d/%d/%d" % (
        min(r["serverBytes"] for r in results),
        statistics.median([r["serverBytes"] for r in results]),
        max(r["serverBytes"] for r in results)))
    print("  local  字节 min/med/max: %d/%d/%d" % (
        min(r["localBytes"] for r in results),
        statistics.median([r["localBytes"] for r in results]),
        max(r["localBytes"] for r in results)))

    # ---- 差异层
    print("\n== 差异 ==")
    no_diff = [r for r in results if r["blockDiffTotal"] == 0 and r["biomeDiffTotal"] == 0]
    print(f"  方块级完全一致（但被判 FULL）的柱: {len(no_diff)}/{len(results)}")
    print("  blockDiffTotal min/med/max: %d/%d/%d" % (
        min(r["blockDiffTotal"] for r in results),
        statistics.median([r["blockDiffTotal"] for r in results]),
        max(r["blockDiffTotal"] for r in results)))
    print("  sectionsDiffering 分布:", collections.Counter(r["sectionsDiffering"] for r in results))
    print("  biomeDiffTotal 非零柱数:", sum(1 for r in results if r["biomeDiffTotal"]))
    hm_any = sum(1 for r in results if r["heightmapDiff"])
    print(f"  heightmap 有差异的柱: {hm_any}/{len(results)}")
    hm_keys: collections.Counter = collections.Counter()
    for r in results:
        for k in r["heightmapDiff"]:
            hm_keys[k] += 1
    print("  heightmap 差异 key 分布:", dict(hm_keys))
    print("  BE only-server 柱数:", sum(1 for r in results if r["beOnlyServer"]),
          " only-local 柱数:", sum(1 for r in results if r["beOnlyLocal"]),
          " tag 差异柱数:", sum(1 for r in results if r["beTagDiff"]))

    # ---- 差异方块 id 对
    agg: collections.Counter = collections.Counter()
    for r in results:
        agg.update(r["idPairs"])
    print("\n== 差异方块 id 对（server -> local，前 %d）==" % args.top)
    for (a, b), n in agg.most_common(args.top):
        if block_names:
            print(f"  {name_of(block_names, a)}  ->  {name_of(block_names, b)}   ×{n}")
        else:
            print(f"  {a} -> {b}: {n}")

    # 差异方向的「服务端侧方块」分布（本地侧一律为何物）
    server_side: collections.Counter = collections.Counter()
    local_side: collections.Counter = collections.Counter()
    for (a, b), n in agg.items():
        server_side[a] += n
        local_side[b] += n
    print("\n== 差异涉及的服务端方块（前 %d）==" % args.top)
    for bid, n in server_side.most_common(args.top):
        print(f"  {name_of(block_names, bid)}  ×{n}")
    print("== 差异涉及的本地方块（前 %d）==" % args.top)
    for bid, n in local_side.most_common(args.top):
        print(f"  {name_of(block_names, bid)}  ×{n}")

    # ---- light
    print("\n== light ==")
    print("  server 侧 skyMask 非空柱数:", sum(1 for r in results if r["light"]["serverSkyMask"]),
          " skyUpdates 非空柱数:", sum(1 for r in results if r["light"]["serverSkyUpdates"]))
    print("  local  侧 skyMask 非空柱数:", sum(1 for r in results if r["light"]["localSkyMask"]),
          " skyUpdates 非空柱数:", sum(1 for r in results if r["light"]["localSkyUpdates"]))

    # ---- 最差样本
    print("\n== 差异最大的柱（前 %d）==" % args.top)
    for r in sorted(results, key=lambda x: -x["blockDiffTotal"])[:args.top]:
        print(f"  {r['name']}: blockDiff={r['blockDiffTotal']} sections={r['sectionsDiffering']} "
              f"be(+{len(r['beOnlyServer'])}/-{len(r['beOnlyLocal'])}) hm={r['heightmapDiff']}")

    if args.json:
        serializable = []
        for r in results:
            row = {k: v for k, v in r.items() if k != "idPairs"}
            row["idPairs"] = [[list(k), v] for k, v in r["idPairs"].most_common(30)]
            serializable.append(row)
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(serializable, fh, ensure_ascii=False, indent=1)
        print(f"\n逐柱结果已写入 {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())