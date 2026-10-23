"""Summarize local benchmark counters and spark call trees without uploading data."""
import argparse
from collections import defaultdict
import csv
import json
import math
import re
from pathlib import Path
import statistics
import subprocess

REPO = Path(__file__).resolve().parent.parent
ROOT = REPO / "build/analysis/spark-20261023"
JAVA = Path(r"D:\app\graalvm-jdk-21.0.5\bin\java.exe")
CHUNK_PROFILE = re.compile(
    r"\[CHUNK-PROFILE\] side=(?P<side>\w+) epoch=(?P<epoch>-?\d+) tag=(?P<tag>\w+) "
    r"source=(?P<source>\w+) stage=(?P<stage>\w+) n=(?P<count>\d+) "
    r"meanNs=(?P<mean_ns>\d+) p50Ns=(?P<p50_ns>\d+) p95Ns=(?P<p95_ns>\d+) "
    r"p99Ns=(?P<p99_ns>\d+) maxNs=(?P<max_ns>\d+)"
)


def light_confirmation_summary(run, side, phase):
    path = run / f"{side}-control/light-confirmation.csv"
    if not path.exists():
        return {"side": side, "phase": phase, "available": False, "samples": 0,
                "unavailableReasons": ["file-missing"]}
    with path.open(encoding="utf-8", newline="") as stream:
        all_rows = list(csv.DictReader(stream))
    rows = [row for row in all_rows if row.get("phase") == phase]
    valid = [row for row in rows if row.get("available") == "true"]
    errors = [row for row in rows if row.get("available") == "error"]
    unavailable = sorted({row.get("reason", "unspecified") for row in all_rows
                          if row.get("available") == "false"})
    error_reasons = sorted({row.get("reason", "api-call-failed") for row in all_rows
                            if row.get("available") == "error"})
    numeric = ("pending", "high_water", "marked", "coalesced", "examined", "max_wait_ns", "oldest_ns")
    peaks = {key: max((int(row[key]) for row in valid), default=None) for key in numeric}
    latest = valid[-1] if valid else None
    return {"side": side, "phase": phase, "available": bool(valid), "samples": len(rows),
            "availableSamples": len(valid), "errorSamples": len(errors),
            "capabilityProbePhase": next((row.get("phase") for row in all_rows
                                           if row.get("available") in ("false", "error")), None),
            "unavailableReasons": unavailable, "errorReasons": error_reasons, "peak": peaks,
            "latest": ({key: latest[key] for key in numeric + ("overflow", "closed")} if latest else None)}


def quantile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def profile_summary(path, main_name):
    profile = json.loads(path.read_text(encoding="utf-8"))
    thread = next(t for t in profile["threads"] if t["name"] == main_name)
    nodes = thread["children"]
    total = sum(thread["times"])
    inclusive, exclusive = defaultdict(float), defaultdict(float)
    for node in nodes:
        key = node["className"] + "." + node["methodName"]
        time_ms = sum(node["times"])
        child_ms = sum(sum(nodes[i]["times"]) for i in node["childrenRefs"])
        inclusive[key] += time_ms
        exclusive[key] += max(0, time_ms - child_ms)
    def attributed(ref, inside=False):
        node = nodes[ref]
        own = "hassium" in (node["className"] + "." + node["methodName"]).lower()
        if own and not inside:
            return sum(node["times"])
        return sum(attributed(i, inside or own) for i in node["childrenRefs"])
    def top(values, limit=20):
        return [{"method": key, "sampleMs": value, "percentOfThreadSamples": value / total * 100}
                for key, value in sorted(values.items(), key=lambda item: -item[1])[:limit]]
    return {"thread": main_name, "sampleMs": total,
            "hassiumInclusiveMs": sum(attributed(i) for i in thread["childrenRefs"]),
            "selfHotspots": top(exclusive), "inclusiveHotspots": top(inclusive),
            "hassiumHotspots": top({k: v for k, v in inclusive.items() if "hassium" in k.lower()}),
            "platform": profile["metadata"].get("platformStatistics"),
            "interval": profile["metadata"].get("interval"),
            "threads": [t["name"] for t in profile["threads"]]}


def summarize(run, instrumentation_root=None):
    result = json.loads((run / "result.json").read_text(encoding="utf-8"))
    if result["status"] != "PASS":
        raise ValueError(f"Incomplete run: {run}")
    inputs = json.loads((run / "inputs.json").read_text(encoding="utf-8"))
    rows, profiles, light_confirmation = [], {}, []
    key = "atm9" if inputs["loader"] == "forge" else "atm302"
    jar = next((run / key / "server/mods").glob("spark-*.jar"))
    for side in ("server", "client"):
        files = sorted((run / key / side / "config/spark").glob("*.sparkprofile"))
        if len(files) != 2:
            raise ValueError(f"Expected two spark profiles, got {files}")
        for phase, raw in zip(("movement", "movement_load"), files):
            light_confirmation.append(light_confirmation_summary(run, side, phase))
            path = run / f"{side}-{phase}.spark.json"
            if not path.exists():
                tools = instrumentation_root or Path(inputs.get("instrumentation", {}).get("root", run.parent / "tools"))
                subprocess.run([str(JAVA), "-cp", str(tools / "classes") + ";" + str(jar),
                                "SparkProfileJson", str(raw), str(path)], check=True, creationflags=subprocess.CREATE_NO_WINDOW)
            profiles[f"{side}_{phase}"] = profile_summary(path, "Server thread" if side == "server" else "Render thread")
            data = json.loads((run / f"{side}-control/{phase}.json").read_text())
            with (run / f"{side}-control/{phase}.csv").open() as stream:
                values = [int(row["duration_ns"]) / 1e6 for row in csv.DictReader(stream)]
            seconds = data["elapsedNs"] / 1e9
            rows.append({"run": run.name, "loader": inputs["loader"], "version": inputs["version"],
                         "arm": inputs["arm"], "phase": phase, "side": side,
                         "seconds": seconds, "iterations": len(values), "rate": len(values) / seconds,
                         "meanMs": statistics.mean(values), "p50Ms": quantile(values, .5),
                         "p95Ms": quantile(values, .95), "p99Ms": quantile(values, .99),
                         "over50Ms": sum(v > 50 for v in values), "maxMs": max(values),
                         "mainCpuCorePercent": data["threadCpuNs"] / data["elapsedNs"] * 100,
                         "processCpuCorePercent": data["processCpuNs"] / data["elapsedNs"] * 100,
                         "wireMiB": data["encodedBytes"] / 1024**2,
                         "entityDataPerSecond": data["entityDataPackets"] / seconds,
                         "entityMotionPerSecond": (data["entityMovePackets"] + data["entityTeleportPackets"]) / seconds,
                         "turns": data["turns"], "minX": data["minX"], "maxX": data["maxX"],
                         "hassiumSamplePercent": profiles[f"{side}_{phase}"]["hassiumInclusiveMs"] / profiles[f"{side}_{phase}"]["sampleMs"] * 100})
            rows[-1].update(primaryMetric="server_mspt" if side == "server" else "client_frame_ms",
                            cpuIsolationEnabled=inputs.get("cpuIsolation", {}).get("enabled", False),
                            metricScope="tickServer wall time" if side == "server" else "runTick wall time")
            if side == "server":
                rows[-1].update(msptMean=rows[-1]["meanMs"], msptP50=rows[-1]["p50Ms"],
                                msptP95=rows[-1]["p95Ms"], msptP99=rows[-1]["p99Ms"],
                                ticksOver50Ms=rows[-1]["over50Ms"])
            scene_file = run / f"{side}-control/{phase}-scene.csv"
            if scene_file.exists():
                with scene_file.open() as stream:
                    scenes = list(csv.DictReader(stream))
                loaded = [int(row["loaded_chunks"]) for row in scenes]
                rendered = [int(re.search(r"C: (\d+)", row["renderer"])[1]) for row in scenes]
                rows[-1].update(loadedMin=min(loaded), loadedMax=max(loaded), loadedMedian=statistics.median(loaded),
                                renderedMin=min(rendered), renderedMax=max(rendered), renderedMedian=statistics.median(rendered))
    warnings = {}
    chunk_profile = {}
    for side in ("server", "client"):
        log = (run / f"{side}.log").read_text(encoding="utf-8", errors="replace")
        warnings[side] = {pattern: log.count(pattern) for pattern in
                          ("NBT snapshot failed", "Light timeout (", "[ERROR]", "/ERROR]", "ClassNotFoundException", "Outbound queue exceeds")}
        chunk_profile[side] = [
            ({key: int(value) if key not in ("side", "tag", "source", "stage") else value
              for key, value in match.groupdict().items()})
            if (match := CHUNK_PROFILE.search(line)) else {"raw": line.strip()}
            for line in log.splitlines() if "[CHUNK-PROFILE]" in line
        ]
    (run / "summary.json").write_text(json.dumps({"metrics": rows, "profiles": profiles,
        "lightConfirmation": light_confirmation,
        "chunkPipelineProfile": chunk_profile, "warnings": warnings}, ensure_ascii=False, indent=2), encoding="utf-8")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT, help="Benchmark output root containing the named run folders")
    parser.add_argument("--instrumentation-root", type=Path,
                        help="Frozen instrumentation directory (defaults to the path recorded in inputs.json)")
    parser.add_argument("runs", nargs="+")
    args = parser.parse_args()
    root = args.root.resolve()
    rows = [row for name in args.runs for row in summarize(root / name, args.instrumentation_root)]
    output = root / "summary.csv"
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(dict.fromkeys(key for row in rows for key in row)))
        writer.writeheader(); writer.writerows(rows)
    for row in rows:
        label = "MSPT" if row["side"] == "server" else "frame"
        print(row["run"], row["phase"], row["side"],
              f'{label} mean={row["meanMs"]:.3f}ms P95={row["p95Ms"]:.3f}ms P99={row["p99Ms"]:.3f}ms '
              f'over50={row["over50Ms"]} rate={row["rate"]:.1f}/s '
              f'CPU(aux)={row["mainCpuCorePercent"]:.1f}% wire={row["wireMiB"]:.2f}MiB')


if __name__ == "__main__":
    main()
