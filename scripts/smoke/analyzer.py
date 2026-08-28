"""Unified business gates for runtime smoke results."""
from __future__ import annotations

import json
from pathlib import Path
import re
from typing import Any


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


def _failure(code: str, severity: str = "P0", **details: Any) -> dict[str, Any]:
    return {"code": code, "severity": severity, **details}


def _round_probe(result: dict[str, Any], number: int, root: Path | None = None) -> dict[str, Any]:
    probe = _obj(_obj(result.get("Probe")).get(f"Round{number}"))
    if probe or root is None:
        return probe
    session = str(result.get("SessionId") or "")
    path = root / "probe" / session / f"round{number}.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        return value if isinstance(value, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _gateway_markers(text: str) -> dict[str, dict[str, Any]]:
    pattern = re.compile(
        r"HassiumSmokeTest:GATEWAY_CLIENT\s+ROUND(\d)\s+state=(\w+)\s+"
        r"s2c=(\d+)\s+c2s=(\d+)\s+resume=(true|false)"
    )
    return {
        f"ROUND{match.group(1)}": {
            "gatewayState": match.group(2), "gatewayS2c": int(match.group(3)),
            "gatewayC2s": int(match.group(4)), "gatewayResume": match.group(5) == "true",
        }
        for match in pattern.finditer(text)
    }
def _check_probe_metrics(probe: dict[str, Any], round_number: int) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    for section_name in ("stats", "counters", "clientCache"):
        section = _obj(probe.get(section_name))
        for name, value in section.items():
            if isinstance(value, (int, float)) and not isinstance(value, bool) and value < -1:
                failures.append(_failure("METRIC_INVALID_NEGATIVE", round=round_number,
                                         section=section_name, metric=name, value=value))
    stats = _obj(probe.get("stats"))
    applied = _num(stats.get("clientAppliedChunkCount"))
    landed = _num(stats.get("clientLandedChunkCount"))
    if applied is not None and landed is not None and applied < landed:
        failures.append(_failure("METRIC_APPLIED_BELOW_LANDED", round=round_number,
                                 applied=applied, landed=landed))
    actual = _positions(_obj(probe.get("clientCache")).get("actualPresent"))
    loaded = _num(_obj(probe.get("clientCache")).get("loadedChunks"))
    if loaded is not None and loaded >= 0 and len(actual) > loaded:
        failures.append(_failure("METRIC_ACTUAL_ABOVE_LOADED", round=round_number,
                                 actual=len(actual), loaded=loaded))
    return failures


def _spatial_check(probe: dict[str, Any]) -> dict[str, Any]:
    trace = _obj(probe.get("chunkTrace"))
    cache = _obj(probe.get("clientCache"))
    observed = _positions(cache.get("actualPresent")) or _positions(trace.get("clientApplied"))
    expected = _positions(trace.get("networkReceived")) or _positions(trace.get("shadowReady"))
    if not observed:
        return {"available": False, "cardinalHoles": [], "diagonalHoles": [],
                "reason": "clientCache.actualPresent or clientApplied positions unavailable"}

    cardinal: list[list[int]] = []
    diagonal: list[list[int]] = []
    candidates = {
        (x + dx, z + dz)
        for x, z in observed
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))
    }
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
    """Analyze business behavior; process startup remains PowerShell-owned."""
    scenario = str(result.get("Scenario") or "classic")
    failures: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    checks: dict[str, str] = {}
    log_text = ""
    session = str(result.get("SessionId") or "")
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

    stats_ok = {
        number: bool(re.search(rf"CLIENT_STATS ROUND{number} begin", log_text)
                    and re.search(rf"CLIENT_STATS ROUND{number} end", log_text))
        for number in (1, 2)
    }
    if scenario == "classic" and not all(stats_ok.values()):
        failures.append(_failure("ROUND_STATS_MISSING",
                                 rounds=[n for n, ok in stats_ok.items() if not ok]))

    markers = _gateway_markers(log_text)
    for number in (1, 2):
        probe = _round_probe(result, number, root)
        gateway = markers.get(f"ROUND{number}", _obj(result.get(f"GatewayRound{number}")))
        if not probe:
            skipped.append({"code": "PROBE_MISSING", "round": number})
        else:
            failures.extend(_check_probe_metrics(probe, number))
        if scenario == "classic" or gateway:
            c2s = _num(gateway.get("gatewayC2s"))
            if gateway.get("gatewayState") != "ACTIVE" or (
                    scenario == "classic" and (c2s is None or c2s <= 0)):
                failures.append(_failure("GATEWAY_NOT_ACTIVE", round=number,
                                         state=gateway.get("gatewayState", "MISSING"), c2s=c2s or 0))

        if scenario == "classic" and probe and number == 2:
            counters = _obj(probe.get("counters"))
            disk = _obj(probe.get("disk"))
            ovd = _num(counters.get("ovdLoaded"))
            if ovd is not None and ovd <= 0:
                failures.append(_failure("OVD_NOT_LOADED", round=number, value=ovd))
            delta = _num(counters.get("sectionDeltaApplied")) or 0
            light = _num(counters.get("lightSegRecalc")) or 0
            if delta <= 0 and light <= 0:
                failures.append(_failure("SECTION_DELTA_OR_LIGHT_RECALC_ABSENT", round=number))
            generated = _num(counters.get("locallyGenerated"))
            if generated is not None and generated != 0:
                failures.append(_failure("LOCALLY_GENERATED_NONZERO", round=number, value=generated))
            if disk.get("shadowRegionExists") is not None and (
                    not disk.get("shadowRegionExists") or (_num(disk.get("regionFileCount")) or 0) <= 0):
                failures.append(_failure("SHADOW_REGION_MISSING", round=number))

            full_metrics = {
                name: _num(_obj(probe.get("stats")).get(name)) or 0
                for name in ("fullChunkRequestCount", "newFullChunkRequestCount",
                             "staleFullChunkRequestCount", "chunksDecompressed")
            }
            if any(value > 0 for value in full_metrics.values()):
                failures.append(_failure("R2_FULL_CHUNK_TRANSFER", round=number, metrics=full_metrics))
        spatial = _spatial_check(probe)
        if spatial["available"] and spatial["cardinalHoles"]:
            failures.append(_failure("SPATIAL_CARDINAL_HOLE", round=number,
                                     positions=spatial["cardinalHoles"]))
        if spatial["available"] and spatial["diagonalHoles"]:
            warnings.append(_failure("SPATIAL_DIAGONAL_HOLE", "P1", round=number,
                                     positions=spatial["diagonalHoles"]))


    fatal = result.get("LogAuditFailures")
    fatal = fatal if isinstance(fatal, list) else []
    failures.extend(_failure("PROCESS_FATAL", detail=item) for item in fatal)
    checks["process_fatal"] = "PASS" if not fatal else "FAIL"
    if scenario == "dimension":
        probe = _round_probe(result, 2, root) or _round_probe(result, 1, root)
        cache_dir = _obj(probe.get("disk")).get("cacheDir")
        world = Path(str(cache_dir)).parent if cache_dir else None
        for name, relative in (("overworld", Path("region")), ("nether", Path("DIM-1") / "region"),
                               ("end", Path("DIM1") / "region")):
            if world is None or not any((world / relative).glob("*.mca")):
                failures.append(_failure("DIMENSION_REGION_MISSING", dimension=name))
    return {
        "pass": not failures,
        "failures": failures,
        "warnings": warnings,
        "skipped": skipped,
        "checks": checks,
        "spatial": {"round1": _spatial_check(_round_probe(result, 1, root)),
                    "round2": _spatial_check(_round_probe(result, 2, root))},
    }

def load_and_analyze(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict):
        raise ValueError("result JSON root must be an object")
    return analyze_result(data, path.parent.parent)
