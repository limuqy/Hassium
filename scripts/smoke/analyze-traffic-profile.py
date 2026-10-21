"""Summarize actual successful PLAY TCP writes separately from native input bytes.

Usage: python scripts/smoke/analyze-traffic-profile.py build/traffic-profile/<session>
Raw packet exports are pre-compression attribution, not additive wire savings.
"""
import argparse
import bisect
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics

EXPECTED = ([f"traffic_{kind}_r{i}" for kind in ("normal", "observe", "factory")
             for i in range(1, 4)] + ["traffic_factory_hold", "traffic_factory_periodic"])


def category(key):
    name = key.split(":", 1)[-1].removeprefix("clientbound_").removesuffix("_packet")
    if key.startswith("hassium:"):
        return "hassium_payload"
    if not key.startswith("minecraft:"):
        return "other_or_unknown"
    if any(s in name for s in ("chunk", "light", "biome")):
        return "chunk_light"
    if name in ("block_event", "block_update", "section_blocks_update", "block_entity_data"):
        return "block_state_event"
    if name in ("pos", "rot", "pos_rot") or any(s in name for s in (
            "move_entity", "entity_motion", "teleport_entity", "rotate_head")):
        return "entity_movement"
    if any(s in name for s in ("entity", "entities", "equipment", "attributes")):
        return "entity_other"
    if "sound" in name or "particle" in name or "level_event" in name:
        return "sound_particle"
    if any(s in name for s in ("container", "inventory", "health", "experience",
                              "block_changed_ack", "block_destruction", "player_position")):
        return "interaction_player"
    if any(s in name for s in ("keep_alive", "set_time", "bundle", "ping", "tab_list")):
        return "control_time"
    return "other_vanilla"


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def analyze(directory):
    logs = list(directory.glob("server_*.log"))
    if len(logs) != 1:
        raise ValueError("Expected exactly one copied server log")
    rows = []
    for line in logs[0].read_text(encoding="utf-8", errors="replace").splitlines():
        if "[TRAFFIC_PROFILE] " in line:
            rows.append(json.loads(line.split("[TRAFFIC_PROFILE] ", 1)[1]))
    by_phase = collections.defaultdict(list)
    for row in rows:
        by_phase[row["phase"]].append(row)
    windows, problems = [], []
    real_factory = any(row.get("fixture") == "user_factory_1.21.1" for row in rows)
    expected = ["traffic_real_factory"] if real_factory else EXPECTED
    for name in expected:
        samples = by_phase[name]
        starts = [r for r in samples if r["edge"] == "start"]
        ends = [r for r in samples if r["edge"] == "end"]
        if len(starts) != 1 or len(ends) != 1:
            problems.append(f"{name}: requires one start and end")
            continue
        first, last = starts[0], ends[0]
        duration = (last["nanoTime"] - first["nanoTime"]) / 1e9
        per_second = [
            (b["wireBytes"] - a["wireBytes"]) / ((b["nanoTime"] - a["nanoTime"]) / 1e9)
            for a, b in zip(samples, samples[1:])
            if b["nanoTime"] - a["nanoTime"] >= 500_000_000
        ]
        result = dict(phase=name, seconds=duration, startMs=first["timestampMs"],
                      endMs=last["timestampMs"], actualTPS=(last["ticks"]-first["ticks"])/duration,
                      outputItems=last["outputItems"]-first["outputItems"],
                      p95BytesPerSecond=percentile(per_second, .95),
                      maxBytesPerSecond=max(per_second, default=0))
        for key in ("wireBytes", "originalBytes", "aggregateBytes", "bypassBytes", "packets",
                    "frames", "compressionNanos", "compressionCpuNanos", "errors"):
            result[key] = last[key] - first[key]
        result["bytesPerSecond"] = result["wireBytes"] / duration
        result["KiBPerSecond"] = result["bytesPerSecond"] / 1024
        result["compressionCpuMsPerSecond"] = result["compressionCpuNanos"] / 1e6 / duration
        for key in ("x", "y", "z", "yaw", "pitch"):
            result[key+"Range"] = max(r[key] for r in samples) - min(r[key] for r in samples)
        result["nativeTypes"] = {}
        result["categories"] = {}
        result["epochs"] = []
        result["capturedNativeBytes"] = 0
        result["capturedNativePackets"] = 0
        result["capturedFrames"] = 0
        result["capturePayloadBytes"] = 0
        result["lastFiveSecondsBlockPackets"] = [0] * 5
        if not 58 <= duration <= 63:
            problems.append(f"{name}: duration={duration}")
        if result["actualTPS"] < 19:
            problems.append(f"{name}: TPS below 19 ({result['actualTPS']})")
        if result["errors"] or result["wireBytes"] <= 0:
            problems.append(f"{name}: transport errors or no successful bytes")
        if any(r.get("frozen", False) for r in samples if r["edge"] != "end"):
            problems.append(f"{name}: measured a frozen world")
        if "factory" in name and not real_factory and result["outputItems"] <= 0:
            problems.append(f"{name}: factory produced no collected items")
        if "factory" in name and not real_factory and all("stockedDroppers" in r for r in samples):
            result["minStockedDroppers"] = min(r["stockedDroppers"] for r in samples)
            result["remainingSupplyItems"] = last["supplyItems"]
            if result["minStockedDroppers"] != 16:
                problems.append(f"{name}: a dropper ran out of input")
        if "normal" not in name:
            if any(result[key+"Range"] > .05 for key in ("x", "y", "z", "yaw", "pitch")):
                problems.append(f"{name}: player position/camera was not stable")
        elif max(result["xRange"], result["zRange"]) < 8 or result["yawRange"] < 180:
            problems.append(f"{name}: scripted walking/camera movement did not execute")
        windows.append(result)
    windows.sort(key=lambda r: r["startMs"])
    starts = [w["startMs"] for w in windows]
    for capture in sorted(directory.glob("agg-*.jsonl")):
        with capture.open(encoding="utf-8") as stream:
            for line in stream:
                data = json.loads(line)
                index = bisect.bisect_right(starts, data["ts"]) - 1
                if index < 0:
                    continue
                w = windows[index]
                if data["ts"] >= w["endMs"]:
                    continue
                if data["src"] == "native_v3":
                    key, size = data["type"], data["size"]
                    count = w["nativeTypes"].setdefault(key, {"packets": 0, "bytes": 0})
                    count["packets"] += 1
                    count["bytes"] += size
                    group = w["categories"].setdefault(category(key), {"packets": 0, "bytes": 0})
                    group["packets"] += 1
                    group["bytes"] += size
                    w["capturedNativePackets"] += 1
                    w["capturedNativeBytes"] += size
                    last_second = int((data["ts"] - (w["endMs"] - 5000)) // 1000)
                    if 0 <= last_second < 5 and category(key) == "block_state_event":
                        w["lastFiveSecondsBlockPackets"][last_second] += 1
                elif data["src"] == "agg_v3":
                    w["capturedFrames"] += 1
                    w["capturePayloadBytes"] += data["size"]
                    if data["trainedEpoch"] not in w["epochs"]:
                        w["epochs"].append(data["trainedEpoch"])
    for w in windows:
        # Server samples and encoder callbacks are not atomic. Bound the mismatch;
        # never silently call a partial native capture a complete packet breakdown.
        w["nativeCaptureByteDifference"] = w["capturedNativeBytes"] - w["originalBytes"]
        if abs(w["nativeCaptureByteDifference"]) > max(1024, .02 * w["originalBytes"]):
            problems.append(f"{w['phase']}: native byte capture/counter mismatch")
        if not w["capturedNativePackets"] or not w["capturedFrames"]:
            problems.append(f"{w['phase']}: packet capture missing")
        if real_factory and not all(w["lastFiveSecondsBlockPackets"]):
            problems.append(f"{w['phase']}: factory block activity missing in final five seconds")
    groups = {}
    for kind in ("normal", "observe", "factory", "real_factory"):
        selected = [w for w in windows if (w["phase"] == "traffic_real_factory" if kind == "real_factory"
                                          else w["phase"].startswith(f"traffic_{kind}_r"))]
        if selected:
            seconds = sum(w["seconds"] for w in selected)
            values = [w["KiBPerSecond"] for w in selected]
            types = {}
            for w in selected:
                for key, counts in w["nativeTypes"].items():
                    dest = types.setdefault(key, {"packets": 0, "bytes": 0})
                    for metric in dest:
                        dest[metric] += counts[metric]
            groups[kind] = {
                "seconds": seconds, "wireBytes": sum(w["wireBytes"] for w in selected),
                "KiBPerSecond": sum(w["wireBytes"] for w in selected) / seconds / 1024,
                "windowMinKiBPerSecond": min(values), "windowMaxKiBPerSecond": max(values),
                "windowStdevKiBPerSecond": statistics.stdev(values) if len(values) > 1 else 0,
                "nativeTypes": dict(sorted(types.items(), key=lambda pair: -pair[1]["bytes"])),
            }
    evidence = {}
    for path in [logs[0], *sorted(directory.glob("agg-*.jsonl")), directory / "dictionary.bin"]:
        if path.exists():
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            evidence[path.name] = {"bytes": path.stat().st_size,
                                   "sha256": digest}
    harness_files = list(directory.glob("result_*.json"))
    harness = json.loads(harness_files[0].read_text(encoding="utf-8-sig")) if len(harness_files) == 1 else {}
    if harness.get("Result") != "PASS":
        problems.append("Smoke harness did not pass; inspect harness failures separately from the byte totals")
    report = {"schema": 1, "session": directory.name, "qualityProblems": problems,
              "harnessResult": harness.get("Result"), "harnessFailures": harness.get("ProbeGateFailures", []),
              "fixture": "user_factory_1.21.1" if real_factory else "generated_control",
              "notes": ["wireBytes counts successful PLAY TCP payload writes, including framing/compression prefixes.",
                        "Excludes TCP/IP headers, ACKs, retransmissions, login and encryption overhead.",
                        "Packet type bytes are native PRE-compression bytes; they are not compressed wire attribution.",
                        "Three consecutive windows in one session are not independent restarts.",
                        "Synchronous debug packet export is enabled; TPS does not measure its full CPU/I/O cost."],
              "groups": groups, "windows": windows, "evidence": evidence}
    (directory / "traffic-summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    scalars = list(dict.fromkeys(k for w in windows for k in w if k not in
                   ("nativeTypes", "categories", "epochs", "lastFiveSecondsBlockPackets"))) if windows else ["phase"]
    with (directory / "traffic-windows.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=scalars, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(windows)
    with (directory / "traffic-packets.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["phase", "type", "category", "nativePackets", "nativeBytes", "nativeBytesPerSecond"])
        for w in windows:
            for key, counts in sorted(w["nativeTypes"].items(), key=lambda pair: -pair[1]["bytes"]):
                writer.writerow([w["phase"], key, category(key), counts["packets"], counts["bytes"],
                                 counts["bytes"] / w["seconds"]])
    print(json.dumps({"session": directory.name, "qualityProblems": problems,
                      "groups": {k: {a: b for a, b in v.items() if a != "nativeTypes"}
                                 for k, v in groups.items()}}, ensure_ascii=False, indent=2))
    return bool(problems)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    raise SystemExit(analyze(args.directory))
