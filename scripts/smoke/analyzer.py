"""Unified business gates for runtime smoke results."""
from __future__ import annotations

import json
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


def _player_chunk(probe: dict[str, Any]) -> tuple[int, int] | None:
    pos = probe.get("playerPos")
    if not isinstance(pos, list) or len(pos) < 3:
        return None
    try:
        return int(pos[0]) >> 4, int(pos[2]) >> 4
    except (TypeError, ValueError):
        return None


def _all_outside_final_vd_window(probe: dict[str, Any], gap: dict[str, Any], server_vd: int) -> bool:
    """缺口坐标是否全部落在玩家区块 Chebyshev 半径 server_vd 之外（明确窗外）。"""
    positions = gap.get("positions") or []
    if not positions:
        return False
    chunk = _player_chunk(probe)
    if chunk is None:
        return False
    px, pz = chunk
    for item in positions:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            return False
        if max(abs(int(item[0]) - px), abs(int(item[1]) - pz)) <= server_vd:
            return False
    return True


def _in_authority_shape(cx: int, cz: int, vd: int, x: int, z: int) -> bool:
    """原版视距形状（= 权威/交付域）判定。

    公式与 Java 侧 `compat/ChunkShapeCompat.contains` 逐字相同（1.20.1 `ChunkMap.isChunkInRange`
    == 1.21.1 `ChunkTrackingView.isWithinDistance(..., includeBorder=true)`）。Java 单一真相源是
    `ChunkShapeCompat`，不变量由 `ChunkShapeDilationTest` 钉死；此处是**测试门禁**需要的第二份实现
    （analyzer 是 Python，无法调用 Java）。改动形状公式时两处必须同步。
    """
    i = max(0, abs(x - cx) - 1)
    j = max(0, abs(z - cz) - 1)
    k = max(0, max(i, j) - 1)
    l = min(i, j)
    return l * l + k * k < vd * vd


def _all_outside_authority_shape(probe: dict[str, Any], gap: dict[str, Any], server_vd: int) -> bool:
    """缺口坐标是否**全部**落在权威形状（交付域）之外 ⇒ 只可能是光照光环柱。

    S3b（2026-09-19）起：计算域 = 权威形状 + 光环（`ChunkShapeCompat.containsDilated`），
    光环柱被拉取/注入/算光但**不交付**。故 `networkReceived`/`shadowInjected` 会比
    `shadowReady`/`clientApplied` 多出恰好一环——这是设计，不是丢柱。
    """
    positions = gap.get("positions") or []
    if not positions or server_vd <= 0:
        return False
    chunk = _player_chunk(probe)
    if chunk is None:
        return False
    px, pz = chunk
    for item in positions:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            return False
        if _in_authority_shape(px, pz, server_vd, int(item[0]), int(item[1])):
            return False
    return True


def _halo_only_gap(probe: dict[str, Any], gaps: dict[str, Any], server_vd: int) -> bool:
    """两个「收到/注入但未交付」缺口是否**都**（凡非空者）全在权威形状之外 ⇒ 判为光照光环。"""
    checked = False
    for key in ("injectedNotReady", "expectedNotPresent"):
        gap = _obj(gaps.get(key))
        if not gap.get("count"):
            continue
        if not _all_outside_authority_shape(probe, gap, server_vd):
            return False
        checked = True
    return checked


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


def _trace_analysis(probe: dict[str, Any]) -> dict[str, Any]:
    trace = _obj(probe.get("chunkTrace"))
    stages = {name: _positions(trace.get(name)) for name in _STAGE_NAMES}
    cache = _obj(probe.get("clientCache"))
    actual = _positions(cache.get("actualPresent"))
    expected = stages["networkReceived"] or stages["shadowReady"]
    gaps = {
        # 纯原版 world-side 路径会直接 networkReceived → clientApplied，不经过已裁剪的影子注入/ready 阶段。
        "receivedNotInjected": stages["networkReceived"] - (stages["shadowInjected"] | stages["clientApplied"]),
        "injectedNotReady": stages["shadowInjected"] - stages["shadowReady"],
        # 名实一致：ready 却未进 clientApplied。不可用 actualPresent——那只抽
        # networkReceived 候选（SmokeProbeWriter），seedgen 本地柱会整批伪造成缺口。
        "readyNotApplied": stages["shadowReady"] - stages["clientApplied"],
        "appliedNotMeshed": stages["clientApplied"] - stages["meshCompiled"],
        "expectedNotPresent": expected - actual,
    }
    return {"available": bool(expected or actual),
            "actualSource": "clientCache.actualPresent" if "actualPresent" in cache else "missing",
            "counts": {name: len(value) for name, value in stages.items()},
            "gaps": {name: _position_report(value) for name, value in gaps.items()}}


def _spatial_check(probe: dict[str, Any]) -> dict[str, Any]:
    cache = _obj(probe.get("clientCache"))
    trace = _obj(probe.get("chunkTrace"))
    observed = _positions(cache.get("actualPresent"))
    expected = _positions(trace.get("networkReceived")) or _positions(trace.get("shadowReady"))
    if "actualPresent" not in cache or not observed:
        return {"available": False, "cardinalHoles": [], "diagonalHoles": [],
                "reason": "clientCache.actualPresent unavailable or empty"}
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
    remaining = set(holes)
    sizes: list[int] = []
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
    """封闭空洞诊断：口径见 _enclosed_holes，门禁在 analyze_result 里按场景分级。"""
    cache = _obj(probe.get("clientCache"))
    if "actualPresent" not in cache:
        return {"available": False, "reason": "clientCache.actualPresent unavailable"}
    observed = _positions(cache.get("actualPresent"))
    if not observed:
        return {"available": False, "reason": "clientCache.actualPresent empty"}
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


def analyze_result(result: dict[str, Any], root: Path) -> dict[str, Any]:
    scenario = str(result.get("Scenario") or "classic")
    # 移动会话标记（由 runtime-smoke-test.ps1 透传 -MoveSeconds）。>0 ⇒ 走开后柱会合法卸载，
    # 故 trace 的「驻留」口径不适用（见 _MOBILE_TRACE_DIAGNOSTIC_CODES）。
    mobile_session = (_num(result.get("MoveSeconds")) or 0) > 0
    failures: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    checks: dict[str, str] = {}
    session = str(result.get("SessionId") or "")
    log_text = ""
    # Exact session files only. A prefix glob (`*1.21.1_fabric_I*.log`) also
    # swallows historical `*_p1b` / `*_task` logs and poisons smoke_markers.
    logs_dir = root / "logs"
    for name in (f"client_{session}.log", f"server_{session}.log", f"{session}.log"):
        path = logs_dir / name
        try:
            log_text += path.read_text(encoding="utf-8", errors="replace") + "\n"
        except OSError:
            pass

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
    if not has_handshake:
        failures.append(_failure("HANDSHAKE_NOT_NEGOTIATED"))
    if not has_agg:
        failures.append(_failure("AGGREGATION_NOT_ACTIVE"))
    checks["login_handshake"] = "PASS" if has_handshake else "FAIL"
    checks["zstd_pipeline"] = "PASS" if has_agg else "FAIL"

    # 单轮场景（probe 只有 round1.json）：新场景必须在此登记，否则会被按两轮判定 → PROBE_MISSING。
    single_round_scenarios = {"seedgen", "modcompat", "modcompat_strict", "flyroundtrip"}
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
        # 封闭空洞门禁：P0 判 classic + dimension（口径见 _ENCLOSED_HOLE_P0_SCENARIOS），
        # P1 仅 classic。分块大小 ≥ _ENCLOSED_HOLE_P0_CELLS 才算虚空。
        holes = spatial["enclosed"]
        if scenario in _ENCLOSED_HOLE_P0_SCENARIOS and holes["available"]:
            largest = holes["largestComponent"]
            if largest >= _ENCLOSED_HOLE_P0_CELLS:
                failures.append(_failure("TRACE_ENCLOSED_HOLE", round=number, largestComponent=largest,
                                         components=holes["components"], holes=holes["enclosedHoles"]))
            elif largest and scenario in _ENCLOSED_HOLE_P1_SCENARIOS:
                warnings.append(_failure("TRACE_ENCLOSED_HOLE_SMALL", "P1", round=number,
                                         components=holes["components"], holes=holes["enclosedHoles"]))
        gaps = trace_report["gaps"]
        # TRACE 缺口门禁仅 classic：其它场景的盘回填不走同一 trace 契约
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
            # 光照光环（S3b，2026-09-19）：交付域 = 权威形状(serverVD)，计算域 = 权威形状 + 光环。
            # 光环柱「收到/注入但不交付」是设计（`chunk.lightHaloRadius`）——凡缺口**全部**在权威
            # 形状之外即判为光环；只要有一个在形状内，一律不放行（窗内缺口仍是 P0）。
            server_vd = _num(result.get(f"Vd{number}")) or 0
            halo_only = (not mobile_session and server_vd > 0
                         and _halo_only_gap(probe, gaps, int(server_vd)))
            for key, code in (("expectedNotPresent", "TRACE_EXPECTED_NOT_PRESENT"),
                              ("readyNotApplied", "TRACE_READY_NOT_APPLIED")):
                if not gaps[key]["count"]:
                    continue
                if halo_only and code == "TRACE_EXPECTED_NOT_PRESENT":
                    skipped.append(_failure(
                        code, "INFO", round=number, gap=gaps[key],
                        detail=("light halo: compute domain = authority shape + halo "
                                "(chunk.lightHaloRadius); halo columns are pulled/injected/lit but "
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
            # R4 降级策略：classic R2 cache-only + VD 缩距后，injectedNotReady 若**全部**
            # 落在最终玩家 ServerVD 窗外（Chebyshev > VD，经典 R2=10），视为票/红发残留
            # 的窗外 inject 未 publish，降 P1；窗内缺口仍 P0。窗内虚空由 enclosed-hole 把守。
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
                if (code == "TRACE_INJECTED_NOT_READY" and cache_only_reconnect
                        and _all_outside_final_vd_window(probe, gaps[key], 10)):
                    warnings.append(_failure(
                        code, "P1", round=number, gap=gaps[key],
                        detail=("classic R2 cache-only after VD shrink: inject-without-ready "
                                "entirely outside final ServerVD window; "
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
