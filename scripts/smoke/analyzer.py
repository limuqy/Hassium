"""Unified business gates for runtime smoke results."""
from __future__ import annotations

import json
import math
from pathlib import Path
import re
from typing import Any

_STAGE_NAMES = ("networkReceived", "shadowInjected", "shadowReady", "clientApplied", "meshCompiled")

# classic 场景里，这么大（4-连通单元格）的实心封闭空洞 = 玩家眼前的永久虚空（≥2x2）。
# 见 _hole_check：空洞必须「被已持有柱完全包围」才算，视图边界/采样边缘不算。
_ENCLOSED_HOLE_P0_CELLS = 4
_ENCLOSED_COMPONENT_LIMIT = 8
# 包围盒洪水填充的规模上限（已持有集合实测 ≤ ~2000 柱，包围盒 ≤ ~70²；超限则放弃判定而非卡死）。
_ENCLOSED_BOX_CELL_LIMIT = 1_000_000

# 封闭空洞门禁的场景口径。判据是「该场景的盘回填是否走同一交付后驻留契约」：
# - P0（成片虚空，largest >= _ENCLOSED_HOLE_P0_CELLS）：`classic` 与 `dimension`。
#   `dimension` 自身门禁只有 loadedChunks > 64，看不见 303 格中心空洞（F3 实证：该场曾 RESULT: PASS），
#   而其跨维切换后同样是「交付后驻留」，成片空洞就是真缺陷。
# - P1（零散小洞）：仅 `classic`。`dimension` 的维边界/采样边缘本就存在单格差异，告警噪声大于信号，
#   故 P1 留排除。`seedgen` / `modcompat` 的稀疏采样不走同一契约，两档均排除。
_ENCLOSED_HOLE_P0_SCENARIOS = frozenset({"classic", "dimension"})
_ENCLOSED_HOLE_P1_SCENARIOS = frozenset({"classic"})

# 移动会话（`-MoveSeconds > 0`）的 trace 缺口口径失效：`expected = networkReceived` 假设
# 「收到即常驻」，而走开后 vanilla 会正常 CHUNK_UNLOAD（实测单场 472 次），已收到的柱合法消失。
# 于是 `expectedNotPresent` / `readyNotApplied` 只反映「走开」，不反映缺陷——6/6 场历史移动会话
# 全部因此 FAIL，移动场景实际从未被 trace 门禁覆盖过。移动会话里把这两项降为运行内诊断；
# `TRACE_ENCLOSED_HOLE`（真正的虚空门禁，按「被已持有柱包围」判定）不受影响，仍然把守。
_MOBILE_TRACE_DIAGNOSTIC_CODES = frozenset(
    {"TRACE_EXPECTED_NOT_PRESENT", "TRACE_READY_NOT_APPLIED"})


def _obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _chunk_of(coord: Any) -> int:
    """世界坐标 → 区块坐标：必须**向下取整**（Java 区块归属 = floor(coord / 16)）。

    `int(coord) >> 4` 是截断（向零取整）——坐标在 (-16, 0) 区间的负小数（如 -0.5）会偏 1 格，
    把设计上不交付的光环环判成形状内缺口 → 假 P0（`1.21.1_fabric_I_diag2` 实证：同一份 trace
    只有玩家 z=-0.5 时 FAIL）。
    """
    return int(math.floor(float(coord) / 16.0))


def _player_chunk(probe: dict[str, Any]) -> tuple[int, int] | None:
    pos = probe.get("playerPos")
    if not isinstance(pos, list) or len(pos) < 3:
        return None
    try:
        return _chunk_of(pos[0]), _chunk_of(pos[2])
    except (TypeError, ValueError):
        return None


def _all_outside_final_delivery_window(probe: dict[str, Any], positions: set[tuple[int, int]],
                                       server_vd: int, ovd: int, mc_ver: Any = None) -> bool:
    """缺口是否全在最终 serverVD 权威形状与 OVD Chebyshev 窗的并集之外。"""
    if not positions:
        return False
    chunk = _player_chunk(probe)
    if chunk is None:
        return False
    px, pz = chunk
    for x, z in positions:
        if (_in_authority_shape(px, pz, server_vd, x, z, mc_ver)
                or max(abs(x - px), abs(z - pz)) <= ovd):
            return False
    return True


def _parse_mc_ver(ver: Any) -> tuple[int, ...] | None:
    """把 "1.21.4" 解析成 (1, 21, 4)；解析失败返回 None（调用方按旧公式兜底）。"""
    if not isinstance(ver, str):
        return None
    parts: list[int] = []
    for token in ver.strip().split("."):
        digits = "".join(ch for ch in token if ch.isdigit())
        if not digits:
            return None
        parts.append(int(digits))
    return tuple(parts) if len(parts) >= 2 else None


def _uses_modern_tracking_shape(ver: Any) -> bool:
    """1.21.4 起原版 `ChunkTrackingView.isWithinDistance` 改为纯欧氏圆（反编译源码已核对
    1.21.3 / 1.21.4 / 1.21.5 / 1.21.11），VD=20 形状 1529 → 1573 柱。"""
    parsed = _parse_mc_ver(ver)
    return parsed is not None and parsed >= (1, 21, 4)


def _in_authority_shape(cx: int, cz: int, vd: int, x: int, z: int,
                        mc_ver: Any = None) -> bool:
    """原版视距形状（= 权威/交付域）判定，按 mc 版本分段。

    1.20.1–1.21.3（`ChunkMap.isChunkInRange` == 1.21.1–1.21.3
    `ChunkTrackingView.isWithinDistance(..., includeBorder=true)`，chebyshev 角区折算）：
        i=max(0,|dx|-1), j=max(0,|dz|-1), k=max(0,max(i,j)-1), l=min(i,j), l²+k²<vd²。
    1.21.4+（`ChunkTrackingView.isWithinDistance` 新公式，includeBorder 折算为 offset 2）：
        纯欧氏 max(0,|dx|-2)² + max(0,|dz|-2)² < vd²。

    Java 单一真相源是 `ChunkShapeCompat`（`contains` 委托原版；`containsDilated` 收缩坐标后
    委托 `contains`），不变量由 `ChunkShapeDilationTest` 钉死；此处是**测试门禁**需要的第二份
    实现（analyzer 是 Python，无法调用 Java）。原版公式再变时两处必须同步。
    """
    if _uses_modern_tracking_shape(mc_ver):
        i = max(0, abs(x - cx) - 2)
        j = max(0, abs(z - cz) - 2)
        return i * i + j * j < vd * vd
    i = max(0, abs(x - cx) - 1)
    j = max(0, abs(z - cz) - 1)
    k = max(0, max(i, j) - 1)
    l = min(i, j)
    return l * l + k * k < vd * vd


def _all_outside_authority_shape(probe: dict[str, Any], positions: set[tuple[int, int]], server_vd: int,
                                 mc_ver: Any = None) -> bool:
    """缺口坐标是否**全部**落在权威形状（交付域）之外 ⇒ 只可能是光照光环柱。

    S3b（2026-09-19）起：计算域 = 权威形状 + 光环（`ChunkShapeCompat.containsDilated`），
    光环柱被拉取/注入/算光但**不交付**。故 `networkReceived`/`shadowInjected` 会比
    `shadowReady`/`clientApplied` 多出恰好一环——这是设计，不是丢柱。
    """
    if not positions or server_vd <= 0:
        return False
    chunk = _player_chunk(probe)
    if chunk is None:
        return False
    px, pz = chunk
    for x, z in positions:
        if _in_authority_shape(px, pz, server_vd, x, z, mc_ver):
            return False
    return True


def _halo_only_gap(probe: dict[str, Any], gaps: dict[str, Any], server_vd: int,
                   mc_ver: Any = None, ovd: int = 0) -> bool:
    """两个交付缺口是否都在最终交付域外；R2 须包含仍有效的 OVD 方窗。"""
    full_gaps = _trace_gap_sets(probe)
    checked = False
    for key in ("injectedNotReady", "expectedNotPresent"):
        gap = _obj(gaps.get(key))
        if not gap.get("count"):
            continue
        positions = full_gaps[key]
        # 报告只展示前 64 个坐标；若原始集合缺失或数量不符，必须保守判失败。
        outside = (_all_outside_final_delivery_window(
            probe, positions, server_vd, ovd, mc_ver) if ovd > 0 else
            _all_outside_authority_shape(probe, positions, server_vd, mc_ver))
        if len(positions) != gap["count"] or not outside:
            return False
        checked = True
    return checked


def _mobile_retired_gap(probe: dict[str, Any], positions: set[tuple[int, int]],
                        server_vd: int, mc_ver: Any = None) -> bool:
    """移动后退役的累计 trace：差集全在最终交付窗外，且最终窗已完整驻留。

    注入时和 ready 时的视窗可不同。只有实测 actualPresent 恰覆盖最终原版视窗，
    才能把旧视窗候选视为已退役；任何窗内缺口、缺失或截断的驻留探针仍走 P0。
    """
    center = _player_chunk(probe)
    cache = _obj(probe.get("clientCache"))
    present_report = _obj(cache.get("actualPresent"))
    if center is None or server_vd <= 0 or not positions or present_report.get("truncated"):
        return False
    present = _positions(present_report)
    if present_report.get("count") != len(present):
        return False
    cx, cz = center
    radius = server_vd + 2
    required = {(x, z) for x in range(cx - radius, cx + radius + 1)
                for z in range(cz - radius, cz + radius + 1)
                if _in_authority_shape(cx, cz, server_vd, x, z, mc_ver)}
    return present == required and positions.isdisjoint(required)


def _num(value: Any) -> int | float | None:
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _positions(value: Any) -> set[tuple[int, int]]:
    if isinstance(value, dict):
        value = value.get("positions", [])
    if not isinstance(value, list):
        return set()
    result: set[tuple[int, int]] = set()
    for item in value:
        try:
            if isinstance(item, str):
                fields = item.replace(",", " ").split()
                if len(fields) >= 2:
                    result.add((int(fields[0]), int(fields[1])))
            elif isinstance(item, (list, tuple)) and len(item) >= 2:
                result.add((int(item[0]), int(item[1])))
        except (TypeError, ValueError):
            continue
    return result


def _check_ovd_generation_probe(probe: dict[str, Any], number: int, mc_ver: Any) -> list[dict[str, Any]]:
    """Require fresh OVD targets beyond the authority light halo to reach the real client."""
    trace = _obj(probe.get("chunkTrace"))
    center = _player_chunk(probe)
    server_vd = _num(trace.get("serverViewDistance")) or 0
    client_vd = _num(trace.get("effectiveClientVD")) or 0
    if center is None or server_vd <= 0 or client_vd <= server_vd:
        return [_failure("OVD_GENERATION_WINDOW", round=number)]
    px, pz = center
    generated = _positions(trace.get("ovdGenerated"))
    # Authority compute domain = the vanilla shape's Chebyshev dilation by one.
    outer = {(x, z) for x, z in generated
             if max(abs(x - px), abs(z - pz)) <= client_vd
             and not any(_in_authority_shape(px, pz, int(server_vd), x + dx, z + dz, mc_ver)
                         for dx in (-1, 0, 1) for dz in (-1, 0, 1))}
    failures = []
    if not outer:
        failures.append(_failure("OVD_GENERATION_MISSING", round=number))
    resident = _positions(_obj(probe.get("clientCache")).get("actualPresent"))
    applied = _positions(trace.get("clientApplied"))
    if not (outer & applied & resident):
        failures.append(_failure("OVD_GENERATION_NOT_PRESENT", round=number))
    expected = {(x, z) for x in range(px - int(client_vd), px + int(client_vd) + 1)
                for z in range(pz - int(client_vd), pz + int(client_vd) + 1)
                if not any(_in_authority_shape(px, pz, int(server_vd), x + dx, z + dz, mc_ver)
                           for dx in (-1, 0, 1) for dz in (-1, 0, 1))}
    missing = expected - (applied & resident)
    if missing:
        failures.append(_failure("OVD_GENERATION_INCOMPLETE", round=number,
                                 missing=_position_report(missing)))
    received = outer & _positions(trace.get("networkReceived"))
    if received:
        failures.append(_failure("OVD_GENERATION_REMOTE_CONTENT", round=number,
                                 positions=[list(pos) for pos in sorted(received)]))
    return failures


def _check_ovd_light_probe(probe: dict[str, Any], number: int,
                           require_generation: bool = True) -> list[dict[str, Any]]:
    """The artificial Y297 floor must survive OVD-to-authority promotion in both rounds."""
    foot = _obj(probe.get("contentCheck")).get("footBlock")
    failures = []
    if foot != "minecraft:quartz_block":
        failures.append(_failure("OVD_LIGHT_FIXTURE_MISSING", round=number, footBlock=foot))
    fixture = _obj(probe.get("lightFixture"))
    if (_num(fixture.get("sampled")) or 0) <= 0 or _num(fixture.get("mismatches")) != 0:
        failures.append(_failure("OVD_LIGHT_FIXTURE_CONTENT", round=number, fixture=fixture))
    if ((_num(fixture.get("movementSamples")) or 0) <= 0
            or any(_num(fixture.get(key)) != 0 for key in
                   ("movementFloorMissing", "movementSkyLeaks", "movementDarkSources"))):
        failures.append(_failure("OVD_LIGHT_MOVEMENT_FIXTURE", round=number, fixture=fixture))
    if require_generation and number == 1 and not _positions(_obj(probe.get("chunkTrace")).get("ovdGenerated")):
        failures.append(_failure("OVD_GENERATION_MISSING", round=number))
    return failures


def _position_report(points: set[tuple[int, int]], limit: int = 64) -> dict[str, Any]:
    ordered = sorted(points)
    return {"count": len(ordered), "positions": [list(pos) for pos in ordered[:limit]],
            "truncated": len(ordered) > limit}


def _failure(code: str, severity: str = "P0", **details: Any) -> dict[str, Any]:
    return {"code": code, "severity": severity, **details}


def _round_probe(result: dict[str, Any], number: int, root: Path | None = None) -> dict[str, Any]:
    session = str(result.get("SessionId") or "")
    if root is not None and session:
        path = root / "probe" / session / f"round{number}.json"
        try:
            value = json.loads(path.read_text(encoding="utf-8-sig"))
            if isinstance(value, dict):
                return value
        except (OSError, json.JSONDecodeError):
            pass
    return _obj(_obj(result.get("Probe")).get(f"Round{number}"))


def _server_full_push_timeouts(text: str) -> list[dict[str, Any]]:
    """读取服务端明确记录的 pending-confirm 超时全量直推事件。"""
    pattern = re.compile(
        r"\[PENDING_CONFIRM\]\s+(\d+)\s+confirms timed out\s+\(>(\d+)ms\),\s+"
        r"direct-pushing stripped full to\s+(.+?)\s*$",
        re.MULTILINE,
    )
    return [{"count": int(match.group(1)), "timeoutMs": int(match.group(2)),
             "player": match.group(3).strip()}
            for match in pattern.finditer(text)]


def _check_probe_metrics(probe: dict[str, Any], round_number: int) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    for section_name in ("stats", "counters", "clientCache"):
        for name, value in _obj(probe.get(section_name)).items():
            if isinstance(value, (int, float)) and not isinstance(value, bool) and value < -1:
                failures.append(_failure("METRIC_INVALID_NEGATIVE", round=round_number,
                                         section=section_name, metric=name, value=value))
    stats = _obj(probe.get("stats"))
    applied = _num(stats.get("clientAppliedChunkCount"))
    landed = _num(stats.get("clientLandedChunkCount"))
    if applied is not None and landed is not None and applied < landed:
        failures.append(_failure("METRIC_APPLIED_BELOW_LANDED", round=round_number,
                                 applied=applied, landed=landed))
    cache = _obj(probe.get("clientCache"))
    actual = _positions(cache.get("actualPresent"))
    loaded = _num(cache.get("loadedChunks"))
    tracked = _num(cache.get("trackedCandidateCount"))
    if loaded is not None and loaded >= 0 and len(actual) > loaded:
        failures.append(_failure("METRIC_ACTUAL_ABOVE_LOADED", round=round_number,
                                 actual=len(actual), loaded=loaded))
    if applied is not None and applied > 0 and loaded is not None and loaded <= 0:
        failures.append(_failure("CLIENT_CACHE_EMPTY", round=round_number, applied=applied,
                                 loaded=loaded))
    if tracked is not None and tracked >= 0 and len(actual) > tracked:
        failures.append(_failure("METRIC_PRESENT_ABOVE_TRACKED", round=round_number,
                                 actual=len(actual), tracked=tracked))
    return failures


def _trace_gap_sets(probe: dict[str, Any]) -> dict[str, set[tuple[int, int]]]:
    trace = _obj(probe.get("chunkTrace"))
    stages = {name: _positions(trace.get(name)) for name in _STAGE_NAMES}
    cache = _obj(probe.get("clientCache"))
    actual = _positions(cache.get("actualPresent"))
    expected = stages["networkReceived"] or stages["shadowReady"]
    return {
        # 纯原版 world-side 路径会直接 networkReceived → clientApplied，不经过已裁剪的影子注入/ready 阶段。
        "receivedNotInjected": stages["networkReceived"] - (stages["shadowInjected"] | stages["clientApplied"]),
        "injectedNotReady": stages["shadowInjected"] - stages["shadowReady"],
        # 名实一致：ready 却未进 clientApplied。不可用 actualPresent——那只抽
        # networkReceived 候选（SmokeProbeWriter），seedgen 本地柱会整批伪造成缺口。
        "readyNotApplied": stages["shadowReady"] - stages["clientApplied"],
        "appliedNotMeshed": stages["clientApplied"] - stages["meshCompiled"],
        "expectedNotPresent": expected - actual,
    }


def _trace_analysis(probe: dict[str, Any]) -> dict[str, Any]:
    trace = _obj(probe.get("chunkTrace"))
    stages = {name: _positions(trace.get(name)) for name in _STAGE_NAMES}
    cache = _obj(probe.get("clientCache"))
    actual = _positions(cache.get("actualPresent"))
    expected = stages["networkReceived"] or stages["shadowReady"]
    gaps = _trace_gap_sets(probe)
    return {"available": bool(expected or actual),
            "actualSource": "clientCache.actualPresent" if "actualPresent" in cache else "missing",
            "counts": {name: len(value) for name, value in stages.items()},
            "gaps": {name: _position_report(value) for name, value in gaps.items()}}


def _held_positions(probe: dict[str, Any]) -> set[tuple[int, int]]:
    """空间门禁只接受探针实测仍驻留的柱。

    ``chunkTrace.clientApplied`` 是本会话累计事件，不代表柱在 probe 时仍在
    ``ClientChunkCache``。玩家移动/重定位后，累计集合能把已卸载的 3×3 中心围成假洞；
    ``actualPresent`` 已逐个调用 ``cache.getChunk(..., false)`` 验证，因而是唯一适合
    当前驻留几何检查的集合。旧 probe 没有该字段时，退回累计落地集合。
    """
    actual = _positions(_obj(probe.get("clientCache")).get("actualPresent"))
    if actual:
        return actual
    return _positions(_obj(probe.get("chunkTrace")).get("clientApplied"))


def _spatial_check(probe: dict[str, Any]) -> dict[str, Any]:
    trace = _obj(probe.get("chunkTrace"))
    observed = _held_positions(probe)
    expected = _positions(trace.get("networkReceived")) or _positions(trace.get("shadowReady"))
    if not observed:
        return {"available": False, "cardinalHoles": [], "diagonalHoles": [],
                "reason": "no held-chunk set (chunkTrace.clientApplied / clientCache.actualPresent)"}
    cardinal: list[list[int]] = []
    diagonal: list[list[int]] = []
    candidates = {(x + dx, z + dz) for x, z in observed
                  for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))}
    for x, z in candidates - observed:
        sides = {(x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)}
        if sides <= observed:
            cardinal.append([x, z])
            continue
        diagonals = {(x - 1, z - 1), (x - 1, z + 1),
                     (x + 1, z - 1), (x + 1, z + 1)}
        if len(diagonals & observed) >= 3:
            diagonal.append([x, z])
    return {"available": True, "observed": len(observed), "expected": len(expected),
            "cardinalHoles": sorted(cardinal), "diagonalHoles": sorted(diagonal)}



def _enclosed_holes(points: set[tuple[int, int]]) -> set[tuple[int, int]]:
    """返回「被已持有柱完全包围」的缺席柱——真正看不见的虚空，而非视图/采样边缘。

    判据：取已持有集合的包围盒并外扩一圈，从盒外角 4-连通洪水填充；填不到的缺席柱即被围住的洞。

    这个口径专门补上 `gaps.expectedNotPresent` 的盲区：后者的候选集就是 `networkReceived`，
    一个**从未被投递**的柱不在候选集里，因此结构上永远看不见。实测 `1.21.1_fabric_I_band2`
    落位点 3x3 九柱从未投递，而当时的门禁全绿。
    """
    if not points:
        return set()
    xs = [x for x, _ in points]
    zs = [z for _, z in points]
    lo_x, hi_x = min(xs) - 1, max(xs) + 1
    lo_z, hi_z = min(zs) - 1, max(zs) + 1
    if (hi_x - lo_x + 1) * (hi_z - lo_z + 1) > _ENCLOSED_BOX_CELL_LIMIT:
        return set()
    outside = {(lo_x, lo_z)}
    stack = [(lo_x, lo_z)]
    while stack:
        x, z = stack.pop()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            neighbour = (x + dx, z + dz)
            if not (lo_x <= neighbour[0] <= hi_x and lo_z <= neighbour[1] <= hi_z):
                continue
            if neighbour in points or neighbour in outside:
                continue
            outside.add(neighbour)
            stack.append(neighbour)
    return {(x, z) for x in range(lo_x, hi_x + 1) for z in range(lo_z, hi_z + 1)
            if (x, z) not in points and (x, z) not in outside}


def _enclosed_components(holes: set[tuple[int, int]]) -> list[int]:
    """空洞的 4-连通分块大小（降序）——连续成片的洞才是虚空，零散单格多为采样边缘。"""
    sizes: list[int] = []
    remaining = set(holes)
    while remaining:
        seed = remaining.pop()
        stack = [seed]
        size = 1
        while stack:
            x, z = stack.pop()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                neighbour = (x + dx, z + dz)
                if neighbour in remaining:
                    remaining.discard(neighbour)
                    stack.append(neighbour)
                    size += 1
        sizes.append(size)
    return sorted(sizes, reverse=True)


def _hole_check(probe: dict[str, Any]) -> dict[str, Any]:
    """封闭空洞诊断：口径见 _enclosed_holes（人口见 _held_positions）。"""
    observed = _held_positions(probe)
    if not observed:
        return {"available": False,
                "reason": "no held-chunk set (chunkTrace.clientApplied / clientCache.actualPresent)"}
    holes = _enclosed_holes(observed)
    components = _enclosed_components(holes)
    return {"available": True, "observed": len(observed), "enclosedCount": len(holes),
            "largestComponent": components[0] if components else 0,
            "components": components[:_ENCLOSED_COMPONENT_LIMIT],
            "enclosedHoles": _position_report(holes)}
def _late_near_player(probe: dict[str, Any], threshold_ms: int = 10_000) -> list[dict[str, Any]]:
    """发现近玩家柱相对本轮首批落地长期延迟，覆盖 full/cache/delta 三条路径。"""
    trace = _obj(probe.get("chunkTrace"))
    received = _obj(trace.get("networkReceivedAtMs"))
    applied = _obj(trace.get("clientAppliedAtMs"))
    player = probe.get("playerPos")
    if not applied or not isinstance(player, list) or len(player) < 3:
        return []
    try:
        px, pz = int(player[0] // 16), int(player[2] // 16)
        first_applied = min(int(value) for value in applied.values())
    except (TypeError, ValueError):
        return []
    late: list[dict[str, Any]] = []
    for packed, applied_at in applied.items():
        try:
            key = int(packed)
            x = key & 0xffffffff
            z = (key >> 32) & 0xffffffff
            if x >= 0x80000000:
                x -= 0x100000000
            if z >= 0x80000000:
                z -= 0x100000000
            if max(abs(x - px), abs(z - pz)) > 3:
                continue
            applied_at = int(applied_at)
            received_at = int(received.get(packed, applied_at))
            delay_from_first = applied_at - first_applied
            apply_delay = applied_at - received_at
            if delay_from_first >= threshold_ms or apply_delay >= threshold_ms:
                late.append({"position": [x, z], "networkDelayMs": delay_from_first,
                             "applyDelayMs": apply_delay})
        except (TypeError, ValueError):
            continue
    return sorted(late, key=lambda item: (item["networkDelayMs"], item["applyDelayMs"]), reverse=True)


def _movement_segments(client_log: str) -> list[dict[str, Any]]:
    """Java TP 请求、客户端到高空后的水平起点、水平终点三点配对。"""
    tp_re = re.compile(r"HassiumSmokeTest:MOVE_TP_REQUEST targetY=(-?\d+) "
                       r"pos=\((-?\d+), (-?\d+), (-?\d+)\)")
    start_re = re.compile(r"HassiumSmokeTest:MOVE_START cruise (\d+)s "
                          r"pos=\((-?\d+), (-?\d+), (-?\d+)\) targetY=(-?\d+)")
    end_re = re.compile(r"HassiumSmokeTest:MOVE_END pos=\((-?\d+), (-?\d+), (-?\d+)\)")
    pending_tp: tuple[int, int, int, int] | None = None
    pending_start: tuple[int, int, int, int, int] | None = None
    segments: list[dict[str, Any]] = []
    for line in client_log.splitlines():
        tp = tp_re.search(line)
        if tp:
            pending_tp = tuple(map(int, tp.groups()))
            pending_start = None
            continue
        start = start_re.search(line)
        if start and pending_tp is not None:
            pending_start = tuple(map(int, start.groups()))
            continue
        end = end_re.search(line)
        if end and pending_tp is not None and pending_start is not None:
            requested_y, tx, ty, tz = pending_tp
            seconds, sx, sy, sz, reached_y = pending_start
            ex, ey, ez = map(int, end.groups())
            segments.append({"seconds": seconds, "tpRequest": [tx, ty, tz],
                             "targetY": requested_y, "start": [sx, sy, sz], "end": [ex, ey, ez],
                             "reachedTarget": reached_y == requested_y and sy >= requested_y - 1,
                             "chunkDelta": [_chunk_of(ex) - _chunk_of(sx),
                                            _chunk_of(ez) - _chunk_of(sz)]})
            pending_tp = None
            pending_start = None
    return segments


def _check_egress_load(server_log: str, client_logs: list[str]) -> list[dict[str, Any]]:
    failures = []
    reports = []
    for line in server_log.splitlines():
        if "[EGRESS_LOAD] " in line:
            try:
                reports.append(json.loads(line.split("[EGRESS_LOAD] ", 1)[1]))
            except (ValueError, TypeError):
                failures.append(_failure("EGRESS_LOAD_REPORT_INVALID"))
    done = next((report for report in reversed(reports) if report.get("phase") == "done"), {})
    if done.get("connections") != 2 or len(done.get("sent", [])) != 2:
        failures.append(_failure("EGRESS_LOAD_CONCURRENCY_MISSING"))
    if not {"warmup", "steady", "burst", "drain", "done"}.issubset({item.get("phase") for item in reports}):
        failures.append(_failure("EGRESS_LOAD_PHASE_MISSING"))
    for sent in done.get("sent", []):
        if sent.get("packets") != 36480 or sent.get("callbacks") != 36480 // 257:
            failures.append(_failure("EGRESS_LOAD_SEND_COUNT", actual=sent))
    if _obj(done.get("metrics")).get("errors") != 0:
        failures.append(_failure("EGRESS_LOAD_PROTOCOL_ERRORS"))
    for stage in ("BATCH_WAIT", "FIFO_WAIT", "PREPARE", "WORKER_QUEUE", "CODEC", "SAMPLE", "EVENT_LOOP_RETURN", "WRITE", "OUTBOUND_TOTAL"):
        summary = _obj(_obj(done.get("profile")).get(stage))
        if not summary.get("count") or (stage in ("PREPARE", "CODEC", "SAMPLE") and not summary.get("allocationSamples")):
            failures.append(_failure("EGRESS_LOAD_PROFILE_MISSING", stage=stage))
    for index, log in enumerate(client_logs):
        if "HassiumSmokeTest:PASS" not in log or "HassiumSmokeTest:FAIL" in log:
            failures.append(_failure("EGRESS_LOAD_CLIENT_FAILED", client=index))
        match = re.search(r"\[EGRESS_LOAD_CLIENT\] received=36480 profile=(\{[^\n]+\})", log)
        try:
            profile = json.loads(match.group(1)) if match else {}
        except ValueError:
            profile = {}
        if not all(_obj(profile.get(stage)).get("count") for stage in
                   ("INBOUND_WORKER_QUEUE", "DECODE", "INBOUND_EVENT_LOOP_RETURN", "NATIVE_DELIVERY")):
            failures.append(_failure("EGRESS_LOAD_CLIENT_PROFILE_MISSING", client=index))
    return failures


def analyze_result(result: dict[str, Any], root: Path) -> dict[str, Any]:
    scenario = str(result.get("Scenario") or "classic")
    # 移动会话标记（由 runtime-smoke-test.ps1 透传 -MoveSeconds）。>0 ⇒ 走开后柱会合法卸载，
    # 故 trace 的「驻留」口径不适用（见 _MOBILE_TRACE_DIAGNOSTIC_CODES）。
    mobile_session = scenario in ("ovdlight", "cachemodes") or (_num(result.get("MoveSeconds")) or 0) > 0
    failures: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    checks: dict[str, str] = {}
    session = str(result.get("SessionId") or "")
    log_text = ""
    client_log = ""
    # Exact session files only. A prefix glob (`*1.21.1_fabric_I*.log`) also
    # swallows historical `*_p1b` / `*_task` logs and poisons smoke_markers.
    logs_dir = root / "logs"
    for name in (f"client_{session}.log", f"server_{session}.log", f"{session}.log"):
        path = logs_dir / name
        try:
            content = path.read_text(encoding="utf-8", errors="replace") + "\n"
            log_text += content
            if name.startswith("client_"):
                client_log = content
        except OSError:
            pass

    if mobile_session:
        segments = _movement_segments(client_log)
        required = 2 if scenario in ("classic", "lightdiag", "flyroundtrip", "ovdlight", "cachemodes") else 1
        requested = 32 if scenario in ("ovdlight", "cachemodes") else int(_num(result.get("MoveSeconds")) or 0)
        moved = (len(segments) >= required and all(
            item["seconds"] == requested and item["reachedTarget"]
            and item["start"][1] > item["tpRequest"][1]
            and any(item["chunkDelta"])
            for item in segments[:required]))
        if not moved:
            failures.append(_failure("MOVEMENT_NOT_EXECUTED", expectedSegments=required,
                                     requestedSeconds=requested, segments=segments))
        checks["movement"] = "PASS" if moved else "FAIL"

    has_pass = "HassiumSmokeTest:PASS" in log_text
    has_fail = "HassiumSmokeTest:FAIL" in log_text
    if not has_pass:
        failures.append(_failure("SMOKE_PASS_MARKER_MISSING"))
    if has_fail:
        failures.append(_failure("SMOKE_FAIL_MARKER_PRESENT"))
    checks["smoke_markers"] = "PASS" if has_pass and not has_fail else "FAIL"
    client_exit = result.get("ClientExitCode")
    for timeout in _server_full_push_timeouts(log_text):
        failures.append(_failure("SERVER_FULL_PUSH_TIMEOUT", **timeout))
    if client_exit not in (None, 0):
        failures.append(_failure("CLIENT_EXIT_NONZERO", exitCode=client_exit))
    checks["client_exit"] = "PASS" if client_exit in (None, 0) else "FAIL"

    # 直连拓扑门禁：登录期握手完成（play_init 激活）+ 聚合激活确认（PENDING→ENABLED）。
    # 两处 marker 均为 Constants.LOG 常驻输出（不依赖 debug.* 开关）；
    # 区块落地由 probe 指标（CLIENT_CACHE_EMPTY / METRIC_*）把守。
    # 管线级全局包压缩已退役（run9 退役波）：原 ZSTD_NOT_ACTIVE 门由聚合门替代。
    has_handshake = "Hassium: play init (caps=" in log_text
    has_agg = "Hassium: Aggregation enabled for" in log_text
    require_hassium = scenario != "fallback"
    if require_hassium and not has_handshake:
        failures.append(_failure("HANDSHAKE_NOT_NEGOTIATED"))
    if require_hassium and not has_agg:
        failures.append(_failure("AGGREGATION_NOT_ACTIVE"))
    checks["login_handshake"] = ("PASS" if has_handshake else "FAIL") if require_hassium else "NOT_REQUIRED"
    checks["zstd_pipeline"] = ("PASS" if has_agg else "FAIL") if require_hassium else "NOT_REQUIRED"
    if scenario == "fallback" and "[SHADOW] Creating shadow server" in log_text:
        failures.append(_failure("FALLBACK_SHADOW_CREATED"))

    # 字典热更 rollout 门禁（scenario=dictionary 专属）：冒烟服务端以 SMOKE 模式跑
    # （预删字典文件 + 语料攒批高频采集 + 语料达标主动触发一次重训练），必须出现
    # 「Aggregation dictionary activated (epoch=2,」——epoch 1 是删档后的首训，
    # epoch 2 才是「语料 → 重训练 → offer → 客户端 ACK → 全员确认 → 切换」更新链路的证据。
    if scenario == "dictionary":
        has_dict_flip = "Aggregation dictionary activated (epoch=2," in log_text
        if not has_dict_flip:
            failures.append(_failure("DICTIONARY_ROLLOUT_NOT_ACTIVATED"))
        checks["dictionary_rollout"] = "PASS" if has_dict_flip else "FAIL"

    # 单轮场景（probe 只有 round1.json）：新场景必须在此登记，否则会被按两轮判定 → PROBE_MISSING。
    single_round_scenarios = {"seedgen", "ovdgen", "modcompat", "modcompat_strict", "flyroundtrip", "dictionary", "egress", "egressbench", "egressload", "factoryegress20", "factoryegress100", "factoryegress200", "trafficprofile", "trafficfactory"}
    round_numbers = (1,) if scenario in single_round_scenarios else (1, 2)
    stats_ok = {n: bool(re.search(rf"CLIENT_STATS ROUND{n} begin", log_text)
                    and re.search(rf"CLIENT_STATS ROUND{n} end", log_text)) for n in round_numbers}
    if scenario == "classic" and not all(stats_ok.values()):
        failures.append(_failure("ROUND_STATS_MISSING", rounds=[n for n, ok in stats_ok.items() if not ok]))

    trace_reports: dict[str, Any] = {}
    spatial_reports: dict[str, Any] = {}
    # 空间快照仅作结果诊断；区块落地门禁由 probe 指标承担（shadow 预生成/全视距已裁剪）。
    for number in round_numbers:
        probe = _round_probe(result, number, root)
        if not probe:
            failures.append(_failure("PROBE_MISSING", round=number))
            skipped.append({"code": "PROBE_MISSING", "round": number})
            continue
        failures.extend(_check_probe_metrics(probe, number))
        if scenario == "fallback":
            if probe.get("joined") is not True or (_num(_obj(probe.get("clientCache")).get("loadedChunks")) or 0) <= 64:
                failures.append(_failure("FALLBACK_CHUNKS_MISSING", round=number))
            if _obj(probe.get("isolation")).get("shadowRunning") is not False:
                failures.append(_failure("FALLBACK_SHADOW_RUNNING", round=number))
            if _num(_obj(probe.get("stats")).get("fullChunkRequestCount")) != 0:
                failures.append(_failure("FALLBACK_PULL_REQUESTED", round=number))
            if (_num(_obj(probe.get("contentCheck")).get("chunksWithNonAir")) or 0) <= 0:
                failures.append(_failure("FALLBACK_CONTENT_MISSING", round=number))
        if scenario == "ovdgen":
            failures.extend(_check_ovd_generation_probe(probe, number, result.get("Ver")))
        elif scenario == "ovdlight":
            failures.extend(_check_ovd_light_probe(probe, number))
        elif scenario == "cachemodes":
            failures.extend(_check_ovd_light_probe(probe, number, require_generation=False))
        trace_report = _trace_analysis(probe)
        trace_reports[f"round{number}"] = trace_report
        spatial = _spatial_check(probe)
        spatial["enclosed"] = _hole_check(probe)
        spatial_reports[f"round{number}"] = spatial
        if scenario == "classic":
            late_near_player = _late_near_player(probe)
            if late_near_player:
                warnings.append(_failure("LATE_NEAR_PLAYER_CHUNK", severity="P1", round=number,
                                         thresholdMs=10_000, chunks=late_near_player[:64],
                                         truncated=len(late_near_player) > 64))
        # ``actualPresent`` 仅从本轮投递候选采样，无法恢复完整 ClientChunkCache 几何。
        # dimension 会连续重建世界/切维，合法的原版 stream 可在集合中留下 3×3 空洞；
        # 该场景以显式 probe 的 loadedChunks/clientApplied 断言为准。经典场景没有切维，
        # 故保留封闭洞 P0 回归哨兵。
        holes = spatial["enclosed"]
        if scenario == "classic" and holes["available"]:
            largest = holes["largestComponent"]
            if largest >= _ENCLOSED_HOLE_P0_CELLS:
                failures.append(_failure("TRACE_ENCLOSED_HOLE", round=number, largestComponent=largest,
                                         components=holes["components"], holes=holes["enclosedHoles"]))
            elif largest:
                warnings.append(_failure("TRACE_ENCLOSED_HOLE_SMALL", "P1", round=number,
                                         components=holes["components"], holes=holes["enclosedHoles"]))
        gaps = trace_report["gaps"]
        if scenario == "classic":
            # 驻留口径缺口：expected = networkReceived 假设「收到即常驻」。
            # 移动会话里已收到的柱会随玩家飞离合法 CHUNK_UNLOAD，故降为运行内诊断；
            # 非移动会话仍是 P0（收到却从未落地 = 真丢柱）。
            # 缓存重连（R2）且 VD 收窄：networkReceived=0、landed≈loaded、缓存全命中时，
            # shadowReady 可能仍含影子表内窗外/光环柱（park 复用），expected 差集不等于丢柱；
            # 以 enclosed-hole 门禁为准，本条降 P1。
            stats = _obj(probe.get("stats"))
            cache_obj = _obj(probe.get("clientCache"))
            loaded_n = _num(cache_obj.get("loadedChunks"))
            landed_n = _num(stats.get("clientLandedChunkCount"))
            nr_count = _num((trace_report.get("counts") or {}).get("networkReceived")) or 0
            cache_only_reconnect = (
                number == 2
                and nr_count == 0
                and (_num(stats.get("fullChunkRequestCount")) or 0) == 0
                and landed_n is not None
                and loaded_n is not None
                and landed_n >= loaded_n
            )
            # 光照光环（S3b，2026-09-19）：R1 交付域为权威形状(serverVD)；
            # R2 缩距后还包括 OVD16 方窗。只有全量缺口都在本轮交付域外才可判光环；
            # OVD 有效带内缺口不能绕过 P0 门禁。
            server_vd = _num(result.get(f"Vd{number}")) or 0
            # 透传 mc 版本：原版 1.21.4 起改了视距形状公式（见 _in_authority_shape）。
            full_gaps = _trace_gap_sets(probe)
            halo_only = (not mobile_session and server_vd > 0
                         and _halo_only_gap(probe, gaps, int(server_vd), result.get("Ver"),
                                            ovd=16 if number == 2 else 0))
            for key, code in (("expectedNotPresent", "TRACE_EXPECTED_NOT_PRESENT"),
                              ("readyNotApplied", "TRACE_READY_NOT_APPLIED")):
                if not gaps[key]["count"]:
                    continue
                if halo_only and code == "TRACE_EXPECTED_NOT_PRESENT":
                    skipped.append(_failure(
                        code, "INFO", round=number, gap=gaps[key],
                        detail=("light halo: compute domain = authority shape + halo "
                                "(fixed one-ring dilation); halo columns are pulled/injected/lit but "
                                "deliberately NOT delivered, so 'received' exceeds 'resident' by "
                                "exactly that ring. Inside-authority gaps remain P0")))
                elif mobile_session and code in _MOBILE_TRACE_DIAGNOSTIC_CODES:
                    skipped.append(_failure(code, "INFO", round=number, gap=gaps[key],
                                            detail="mobile session: received chunks legitimately "
                                                   "unload as the player flies away"))
                elif cache_only_reconnect and code in ("TRACE_EXPECTED_NOT_PRESENT",
                                                      "TRACE_READY_NOT_APPLIED"):
                    warnings.append(_failure(code, "P1", round=number, gap=gaps[key],
                                             detail="classic R2 cache-only reconnect after VD shrink: "
                                                    "shadowReady may retain out-of-window columns; "
                                                    "enclosed-hole gate is authoritative"))
                elif code == "TRACE_READY_NOT_APPLIED":
                    warnings.append(_failure(code, "P1", round=number, gap=gaps[key]))
                else:
                    failures.append(_failure(code, round=number, gap=gaps[key]))
            # 投递链缺口：与驻留无关（注入/ready 是交付路径本身），移动会话同样把守。
            # R4 降级策略：classic R2 cache-only + VD 缩距后，仅当 injectedNotReady
            # 全在最终交付域（serverVD 原版权威形状 ∪ OVD16 方窗）之外，才将
            # 退役 inject 降 P1；OVD 带内仍 P0。classic 脚本固定 OVD16。
            for key, code in (("receivedNotInjected", "TRACE_RECEIVED_NOT_INJECTED"),
                              ("injectedNotReady", "TRACE_INJECTED_NOT_READY")):
                if not gaps[key]["count"]:
                    continue
                if halo_only and code == "TRACE_INJECTED_NOT_READY":
                    skipped.append(_failure(
                        code, "INFO", round=number, gap=gaps[key],
                        detail=("light halo: injected for the 3x3 neighborhood of boundary "
                                "authority columns but deliberately NOT delivered; "
                                "inside-authority gaps remain P0")))
                    continue
                # 只在 classic R1 的宽 VD20 窗口应用：R2 缩为 VD10 后，OVD16
                # 仍有有效交付带，不能把 VD10 外的柱一概视为退役。
                if (mobile_session and number == 1 and server_vd >= 20
                        and code == "TRACE_INJECTED_NOT_READY"
                        and _mobile_retired_gap(probe, full_gaps[key], int(server_vd), result.get("Ver"))):
                    skipped.append(_failure(
                        code, "INFO", round=number, gap=gaps[key],
                        detail=("mobile session: injected under an earlier delivery window; "
                                "all unmatched columns are outside the final authority shape, "
                                "and actualPresent covers that entire shape. Inside gaps remain P0")))
                    continue
                if (code == "TRACE_INJECTED_NOT_READY" and cache_only_reconnect
                        and _all_outside_final_delivery_window(
                            probe, full_gaps[key], int(server_vd), 16, result.get("Ver"))):
                    warnings.append(_failure(
                        code, "P1", round=number, gap=gaps[key],
                        detail=("classic R2 cache-only after VD shrink: inject-without-ready "
                                "entirely outside final serverVD + OVD16 delivery window; "
                                "enclosed-hole + stats remain authoritative")))
                    continue
                failures.append(_failure(code, round=number, gap=gaps[key]))
        if gaps["appliedNotMeshed"]["count"]:
            skipped.append(_failure("TRACE_MESH_PENDING", "INFO", round=number,
                                    gap=gaps["appliedNotMeshed"],
                                    detail="mesh compilation is asynchronous; resident cache is the smoke gate"))
        if spatial["available"] and (spatial["cardinalHoles"] or spatial["diagonalHoles"]):
            warnings.append(_failure("SPATIAL_SNAPSHOT_INCOMPLETE", "P1", round=number,
                                     cardinalHoles=spatial["cardinalHoles"][:64],
                                     diagonalHoles=spatial["diagonalHoles"][:64],
                                     cardinalTruncated=len(spatial["cardinalHoles"]) > 64,
                                     diagonalTruncated=len(spatial["diagonalHoles"]) > 64))


    if scenario == "egressload":
        observer_path = logs_dir / f"observer_{session}.log"
        observer = observer_path.read_text(encoding="utf-8", errors="replace") if observer_path.exists() else ""
        load_failures = _check_egress_load(log_text, [client_log, observer])
        if result.get("ObserverExitCode") != 0:
            load_failures.append(_failure("EGRESS_LOAD_OBSERVER_EXIT", exitCode=result.get("ObserverExitCode")))
        failures.extend(load_failures)
        checks["egress_load"] = "FAIL" if load_failures else "PASS"

    if scenario == "classic" and not bool(result.get("ServerSwitched")):
        failures.append(_failure("SERVER_SWITCH_MISSING"))
    checks["server_switch"] = "PASS" if scenario != "classic" or bool(result.get("ServerSwitched")) else "FAIL"

    fatal = result.get("LogAuditFailures")
    fatal = fatal if isinstance(fatal, list) else []
    failures.extend(_failure("PROCESS_FATAL", detail=item) for item in fatal)
    checks["process_fatal"] = "PASS" if not fatal else "FAIL"
    checks["probe"] = "PASS" if not any(item["code"] == "PROBE_MISSING" for item in failures) else "FAIL"
    return {"pass": not failures, "failures": failures, "warnings": warnings,
            "skipped": skipped, "checks": checks, "trace": trace_reports,
            "spatial": spatial_reports}


def load_and_analyze(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict):
        raise ValueError("result JSON root must be an object")
    return analyze_result(data, path.parent.parent)
