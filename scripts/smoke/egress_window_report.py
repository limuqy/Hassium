"""Compare real egressload sessions, subtracting cumulative phase histograms.

P95/P99 remain bucket upper bounds. CPU uses cumulative worker/process clocks,
not the sum of Windows' coarse per-job thread CPU deltas.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def percentile_upper(counts: list[int], percentile: int) -> int:
    rank = (sum(counts) * percentile + 99) // 100
    if rank == 0:
        return 0
    seen = 0
    for index, count in enumerate(counts):
        seen += count
        if seen >= rank:
            if index < 8:
                return index
            exponent, sub = 3 + (index - 8) // 8, (index - 8) % 8
            return min((1 << 63) - 1, ((9 + sub) << (exponent - 3)) - 1)
    raise ValueError("Invalid histogram")


def difference(before: list[int], after: list[int]) -> list[int]:
    if len(before) != len(after):
        raise ValueError("Histogram layout changed")
    counts = [end - start for start, end in zip(before, after)]
    if any(count < 0 for count in counts):
        raise ValueError("Histogram reset inside a measurement interval")
    return counts


def expected_packets(result: dict, phases: dict) -> int:
    if result.get("Scenario") != "egressstress":
        return 36480
    requested = result.get("EgressStressWorkload", {})
    keys = ("rate", "warmupTicks", "measureTicks", "drainTicks")
    if any(type(requested.get(key)) is not int for key in keys):
        raise ValueError("Missing stress workload parameters")
    rate, warmup, measure, drain = (requested[key] for key in keys)
    if not (4 <= rate <= 1536 and rate % 4 == 0 and 20 <= warmup <= 2400
            and 20 <= measure <= 3600 and measure % 2 == 0 and 20 <= drain <= 400):
        raise ValueError("Invalid stress workload parameters")
    expected = warmup * (rate // 4) + 2 * measure * rate
    for phase in phases.values():
        workload = phase.get("workload", {})
        if (any(workload.get(key) != requested[key] for key in keys) or workload.get("expected") != expected
                or workload.get("stateBytes") != 3072 or workload.get("dictionaryFrozen") is not True
                or workload.get("steadyPlacement") != "before-flush" or workload.get("burstPlacement") != "after-flush"):
            raise ValueError("Stress workload differs from requested parameters")
    return expected


def read_run(root: Path, session: str) -> dict:
    smoke = root / "build/smoke-test"
    result = json.loads((smoke / "results" / f"result_{session}.json").read_text(encoding="utf-8-sig"))
    if result["Result"] != "PASS":
        raise ValueError(f"{session}: harness did not pass")
    phases = {}
    for line in (smoke / "logs" / f"server_{session}.log").read_text(encoding="utf-8-sig", errors="replace").splitlines():
        if "[EGRESS_LOAD] " in line:
            data = json.loads(line.split("[EGRESS_LOAD] ", 1)[1])
            phases[data["phase"]] = data
    if set(phases) != {"warmup", "steady", "burst", "drain", "done"}:
        raise ValueError(f"{session}: missing phases")
    done = phases["done"]
    expected = expected_packets(result, phases)
    settings = done["settings"]
    if any(phase["settings"] != settings for phase in phases.values()):
        raise ValueError(f"{session}: settings changed")
    requested_wait = result.get("AggregationMaxWaitTimeMs", 0)
    if requested_wait and settings["waitMs"] != requested_wait:
        raise ValueError(f"{session}: effective window differs from requested window")
    if any(sent != {"packets": expected, "callbacks": expected // 257} for sent in done["sent"]) or len(done["sent"]) != 2:
        raise ValueError(f"{session}: fixture/callback count mismatch")
    if done["metrics"]["errors"]:
        raise ValueError(f"{session}: protocol error")
    intervals = {}
    for name, start, end in (("measured", "steady", "done"), ("steady", "steady", "burst"),
                             ("burst", "burst", "drain"), ("drain", "drain", "done")):
        before, after = phases[start], phases[end]
        metrics = {key: value - before["metrics"][key] for key, value in after["metrics"].items()}
        cpu = {key: after["cpu"][key] - before["cpu"][key]
               for key in ("nanoTime", "processCpuNs", "workerCpuNs")}
        if not before["cpu"]["threadCpuSupported"] or not after["cpu"]["threadCpuSupported"]:
            raise ValueError(f"{session}: CPU measurement unavailable")
        if before["cpu"]["processCpuNs"] < 0 or after["cpu"]["processCpuNs"] < 0 or before["cpu"]["workerThreads"] == 0:
            raise ValueError(f"{session}: process/worker CPU measurement unavailable")
        if cpu["nanoTime"] <= 0 or cpu["processCpuNs"] < 0 or cpu["workerCpuNs"] < 0:
            raise ValueError(f"{session}: CPU clock regressed")
        if before["cpu"]["workerThreads"] != after["cpu"]["workerThreads"]:
            raise ValueError(f"{session}: worker population changed after warmup")
        stages = {}
        for stage, buckets in after["profileBuckets"].items():
            counts = difference(before["profileBuckets"][stage], buckets)
            summary_before, summary_after = before["profile"][stage], after["profile"][stage]
            stages[stage] = {
                "count": sum(counts), "totalNs": summary_after["totalNs"] - summary_before["totalNs"],
                "p50UpperNs": percentile_upper(counts, 50),
                "p95UpperNs": percentile_upper(counts, 95), "p99UpperNs": percentile_upper(counts, 99),
                "javaAllocatedBytes": summary_after["allocatedBytes"] - summary_before["allocatedBytes"],
                "allocationSamples": summary_after["allocationSamples"] - summary_before["allocationSamples"],
            }
        intervals[name] = {"metrics": metrics, "cpu": cpu, "stages": stages}
    clients = []
    for role in ("client", "observer"):
        lines = (smoke / "logs" / f"{role}_{session}.log").read_text(encoding="utf-8-sig", errors="replace").splitlines()
        matching = [line for line in lines if f"[EGRESS_LOAD_CLIENT] received={expected} profile=" in line]
        if not matching:
            raise ValueError(f"{session}: missing {role} delivery verification")
        clients.append({"role": role, "received": expected,
                        "profile": json.loads(matching[-1].split(" profile=", 1)[1])})
    return {"session": session, "version": result["Ver"], "loader": result["Loader"], "result": "PASS",
            "settings": settings, "workload": done.get("workload"), "sent": done["sent"], "intervals": intervals, "clients": clients}


def summary_row(run: dict) -> dict:
    measured = run["intervals"]["measured"]
    metrics, cpu, stages = measured["metrics"], measured["cpu"], measured["stages"]
    return {
        "version": run["version"], "loader": run["loader"], "waitMs": run["settings"]["waitMs"],
        "session": run["session"], "wireBytes": metrics["wireBytes"], "originalBytes": metrics["originalBytes"],
        "aggregateBytes": metrics["aggregateBytes"], "bypassBytes": metrics["bypassBytes"],
        "packets": metrics["packets"], "frames": metrics["frames"],
        "wallMs": cpu["nanoTime"] / 1e6, "workerCpuMs": cpu["workerCpuNs"] / 1e6,
        "processCpuMs": cpu["processCpuNs"] / 1e6,
        "workerOneCorePercent": cpu["workerCpuNs"] / cpu["nanoTime"] * 100,
        "processOneCorePercent": cpu["processCpuNs"] / cpu["nanoTime"] * 100,
        "outboundP95Ms": stages["OUTBOUND_TOTAL"]["p95UpperNs"] / 1e6,
        "outboundP99Ms": stages["OUTBOUND_TOTAL"]["p99UpperNs"] / 1e6,
        "batchP95Ms": stages["BATCH_WAIT"]["p95UpperNs"] / 1e6,
        "batchP99Ms": stages["BATCH_WAIT"]["p99UpperNs"] / 1e6,
        "codecP95Ms": stages["CODEC"]["p95UpperNs"] / 1e6,
        "codecP99Ms": stages["CODEC"]["p99UpperNs"] / 1e6,
        "workerQueueP95Ms": stages["WORKER_QUEUE"]["p95UpperNs"] / 1e6,
        "workerQueueP99Ms": stages["WORKER_QUEUE"]["p99UpperNs"] / 1e6,
        "prepareAllocatedBytes": stages["PREPARE"]["javaAllocatedBytes"],
        "codecAllocatedBytes": stages["CODEC"]["javaAllocatedBytes"],
    }


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sessions", nargs="+")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    runs = [read_run(args.root, session) for session in args.sessions]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    summaries = [summary_row(run) for run in runs]
    write_csv(args.output_dir / "summary.csv", summaries)
    stages = []
    for run in runs:
        identity = {key: run[key] for key in ("session", "version", "loader")}
        identity["waitMs"] = run["settings"]["waitMs"]
        for interval, data in run["intervals"].items():
            for stage, values in data["stages"].items():
                stages.append(identity | {"role": "server", "interval": interval, "stage": stage} | values)
        for client in run["clients"]:
            for stage, values in client["profile"].items():
                if values["count"]:
                    stages.append(identity | {"role": client["role"], "interval": "full-client-window", "stage": stage}
                                  | {key: values[key] for key in ("count", "totalNs", "p50UpperNs", "p95UpperNs", "p99UpperNs", "allocationSamples")}
                                  | {"javaAllocatedBytes": values["allocatedBytes"]})
    write_csv(args.output_dir / "stages.csv", stages)
    (args.output_dir / "runtime.json").write_text(json.dumps(runs, indent=2) + "\n", encoding="utf-8")
    for row in summaries:
        print(json.dumps(row))


if __name__ == "__main__":
    main()
