"""Unified business gates for runtime smoke results."""
from __future__ import annotations

import json
from pathlib import Path
import re
from typing import Any

_STAGE_NAMES = ("networkReceived", "shadowInjected", "shadowReady", "clientApplied", "meshCompiled")


def _obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


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


def _gateway_markers(text: str) -> dict[str, dict[str, Any]]:
    pattern = re.compile(
        r"HassiumSmokeTest:GATEWAY_CLIENT\s+ROUND(\d)\s+state=(\w+)\s+"
        r"s2c=(\d+)\s+c2s=(\d+)\s+resume=(true|false)"
    )
    return {f"ROUND{m.group(1)}": {"gatewayState": m.group(2), "gatewayS2c": int(m.group(3)),
                                   "gatewayC2s": int(m.group(4)), "gatewayResume": m.group(5) == "true"}
            for m in pattern.finditer(text)}

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
        "readyNotApplied": stages["shadowReady"] - actual,
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
    failures: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    checks: dict[str, str] = {}
    session = str(result.get("SessionId") or "")
    log_text = ""
    for path in (root / "logs").glob(f"*{session}*.log"):
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

    round_numbers = (1,) if scenario == "seedgen" else (1, 2)
    stats_ok = {n: bool(re.search(rf"CLIENT_STATS ROUND{n} begin", log_text)
                    and re.search(rf"CLIENT_STATS ROUND{n} end", log_text)) for n in round_numbers}
    if scenario == "classic" and not all(stats_ok.values()):
        failures.append(_failure("ROUND_STATS_MISSING", rounds=[n for n, ok in stats_ok.items() if not ok]))

    markers = _gateway_markers(log_text)
    trace_reports: dict[str, Any] = {}
    spatial_reports: dict[str, Any] = {}
    gateway_required = bool(result.get("GatewayRequired", True))
    # 保留空间快照供结果诊断；影子预生成/全视距覆盖已裁剪，任何场景均不以它作通过门禁。
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
        spatial_reports[f"round{number}"] = spatial
        if scenario == "classic":
            late_near_player = _late_near_player(probe)
            if late_near_player:
                warnings.append(_failure("LATE_NEAR_PLAYER_CHUNK", severity="P1", round=number,
                                         thresholdMs=10_000, chunks=late_near_player[:64],
                                         truncated=len(late_near_player) > 64))
        gateway = markers.get(f"ROUND{number}", _obj(result.get(f"GatewayRound{number}")))
        if gateway_required and (scenario == "classic" or gateway):
            c2s = _num(gateway.get("gatewayC2s"))
            if gateway.get("gatewayState") != "ACTIVE" or (scenario == "classic" and (c2s is None or c2s <= 0)):
                failures.append(_failure("GATEWAY_NOT_ACTIVE", round=number,
                                         state=gateway.get("gatewayState", "MISSING"), c2s=c2s or 0))
        gaps = trace_report["gaps"]
        for key, code in (("expectedNotPresent", "TRACE_EXPECTED_NOT_PRESENT"),
                          ("receivedNotInjected", "TRACE_RECEIVED_NOT_INJECTED"),
                          ("injectedNotReady", "TRACE_INJECTED_NOT_READY")):
            if gaps[key]["count"]:
                failures.append(_failure(code, round=number, gap=gaps[key]))
        for key, code in (("readyNotApplied", "TRACE_READY_NOT_APPLIED"),):
            if gaps[key]["count"]:
                warnings.append(_failure(code, "P1", round=number, gap=gaps[key]))
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
    if scenario == "migrate":
        resumed = _obj(_round_probe(result, 2, root).get("gateway")).get("resumeAccepted")
        if resumed is not True:
            failures.append(_failure("MIGRATION_RESUME_NOT_ACCEPTED", value=resumed))

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
