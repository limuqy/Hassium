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
    if loaded is not None and loaded >= 0 and len(actual) > loaded:
        failures.append(_failure("METRIC_ACTUAL_ABOVE_LOADED", round=round_number,
                                 actual=len(actual), loaded=loaded))
    return failures


def _trace_analysis(probe: dict[str, Any]) -> dict[str, Any]:
    trace = _obj(probe.get("chunkTrace"))
    stages = {name: _positions(trace.get(name)) for name in _STAGE_NAMES}
    cache = _obj(probe.get("clientCache"))
    actual = _positions(cache.get("actualPresent"))
    expected = stages["networkReceived"] or stages["shadowReady"]
    gaps = {
        "receivedNotInjected": stages["networkReceived"] - stages["shadowInjected"],
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
    if client_exit not in (None, 0):
        failures.append(_failure("CLIENT_EXIT_NONZERO", exitCode=client_exit))
    checks["client_exit"] = "PASS" if client_exit in (None, 0) else "FAIL"

    stats_ok = {n: bool(re.search(rf"CLIENT_STATS ROUND{n} begin", log_text)
                    and re.search(rf"CLIENT_STATS ROUND{n} end", log_text)) for n in (1, 2)}
    if scenario == "classic" and not all(stats_ok.values()):
        failures.append(_failure("ROUND_STATS_MISSING", rounds=[n for n, ok in stats_ok.items() if not ok]))

    markers = _gateway_markers(log_text)
    trace_reports: dict[str, Any] = {}
    spatial_reports: dict[str, Any] = {}
    for number in (1, 2):
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
        gateway = markers.get(f"ROUND{number}", _obj(result.get(f"GatewayRound{number}")))
        if scenario == "classic" or gateway:
            c2s = _num(gateway.get("gatewayC2s"))
            s2c = _num(gateway.get("gatewayS2c"))
            if gateway.get("gatewayState") != "ACTIVE" or (scenario == "classic" and (c2s is None or c2s <= 0)):
                failures.append(_failure("GATEWAY_NOT_ACTIVE", round=number,
                                         state=gateway.get("gatewayState", "MISSING"), c2s=c2s or 0))
            if scenario == "classic" and number == 2 and (s2c is None or s2c <= 0):
                failures.append(_failure("GATEWAY_S2C_ABSENT", round=number, s2c=s2c or 0))
        gaps = trace_report["gaps"]
        for key, code in (("expectedNotPresent", "TRACE_EXPECTED_NOT_PRESENT"),
                          ("receivedNotInjected", "TRACE_RECEIVED_NOT_INJECTED"),
                          ("injectedNotReady", "TRACE_INJECTED_NOT_READY")):
            if gaps[key]["count"]:
                failures.append(_failure(code, round=number, gap=gaps[key]))
        for key, code in (("readyNotApplied", "TRACE_READY_NOT_APPLIED"),
                          ("appliedNotMeshed", "TRACE_APPLIED_NOT_MESHED")):
            if gaps[key]["count"]:
                warnings.append(_failure(code, "P1", round=number, gap=gaps[key]))
        if spatial["available"] and spatial["cardinalHoles"]:
            failures.append(_failure("SPATIAL_CARDINAL_HOLE", round=number,
                                     positions=spatial["cardinalHoles"][:64],
                                     truncated=len(spatial["cardinalHoles"]) > 64))
        if spatial["available"] and spatial["diagonalHoles"]:
            warnings.append(_failure("SPATIAL_DIAGONAL_HOLE", "P1", round=number,
                                     positions=spatial["diagonalHoles"][:64],
                                     truncated=len(spatial["diagonalHoles"]) > 64))

        if scenario == "classic" and number == 2:
            counters, disk, stats = _obj(probe.get("counters")), _obj(probe.get("disk")), _obj(probe.get("stats"))
            required = ("ovdLoaded", "sectionDeltaApplied", "lightSegRecalc", "locallyGenerated")
            full_names = ("fullChunkRequestCount", "newFullChunkRequestCount", "staleFullChunkRequestCount", "chunksDecompressed")
            missing = [name for name in required if name not in counters]
            missing += [name for name in ("shadowRegionExists", "regionFileCount") if name not in disk]
            missing += [name for name in full_names if name not in stats]
            if missing:
                failures.append(_failure("R2_METRICS_MISSING", round=number, metrics=missing))
            ovd = _num(counters.get("ovdLoaded"))
            if ovd is not None and ovd <= 0:
                failures.append(_failure("OVD_NOT_LOADED", round=number, value=ovd))
            if (_num(counters.get("sectionDeltaApplied")) or 0) <= 0 and (_num(counters.get("lightSegRecalc")) or 0) <= 0:
                failures.append(_failure("SECTION_DELTA_OR_LIGHT_RECALC_ABSENT", round=number))
            generated = _num(counters.get("locallyGenerated"))
            if generated is not None and generated != 0:
                failures.append(_failure("LOCALLY_GENERATED_NONZERO", round=number, value=generated))
            if disk.get("shadowRegionExists") is not None and (not disk.get("shadowRegionExists") or (_num(disk.get("regionFileCount")) or 0) <= 0):
                failures.append(_failure("SHADOW_REGION_MISSING", round=number))
            full_metrics = {name: _num(stats.get(name)) or 0 for name in full_names}
            if any(value > 0 for value in full_metrics.values()):
                failures.append(_failure("R2_FULL_CHUNK_TRANSFER", round=number, metrics=full_metrics))

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
