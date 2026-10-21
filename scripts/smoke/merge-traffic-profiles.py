"""Export small, reviewable CSV evidence from completed, validated profiling runs."""
import argparse
import collections
import csv
import json
from pathlib import Path


def merge(directories, prefix):
    window_rows, packet_rows, grouped = [], [], collections.defaultdict(list)
    for directory in directories:
        report = json.loads((directory / "traffic-summary.json").read_text(encoding="utf-8"))
        if report["qualityProblems"] or report["harnessResult"] != "PASS":
            raise ValueError(f"Refusing incomplete/invalid run: {directory}")
        result_file = next(directory.glob("result_*.json"))
        result = json.loads(result_file.read_text(encoding="utf-8-sig"))
        for window in report["windows"]:
            identity = {"session": directory.name, "mcVersion": result["Ver"],
                        "loader": result["Loader"], "fixture": report["fixture"], "phase": window["phase"]}
            row = dict(identity)
            row.update({k: v for k, v in window.items()
                        if k not in ("phase", "nativeTypes", "categories", "epochs", "lastFiveSecondsBlockPackets")})
            window_rows.append(row)
            phase = window["phase"].removeprefix("traffic_")
            kind = phase.rsplit("_r", 1)[0] if phase[-1:].isdigit() else phase
            grouped[(result["Ver"], result["Loader"], report["fixture"], kind)].append(window)
            for category, counts in window["categories"].items():
                packet_rows.append({**identity, "category": category, "nativePackets": counts["packets"],
                                    "nativeBytes": counts["bytes"],
                                    "nativeBytePercent": counts["bytes"] / window["capturedNativeBytes"] * 100})
    summary_rows = []
    for (version, loader, fixture, kind), windows in grouped.items():
        seconds = sum(w["seconds"] for w in windows)
        summary_rows.append({
            "mcVersion": version, "loader": loader, "fixture": fixture, "scenario": kind,
            "samples": len(windows), "seconds": seconds,
            "wireBytes": sum(w["wireBytes"] for w in windows),
            "KiBPerSecond": sum(w["wireBytes"] for w in windows) / seconds / 1024,
            "minWindowKiBPerSecond": min(w["KiBPerSecond"] for w in windows),
            "maxWindowKiBPerSecond": max(w["KiBPerSecond"] for w in windows),
            "minActualTPS": min(w["actualTPS"] for w in windows),
            "maxActualTPS": max(w["actualTPS"] for w in windows),
            "protocolErrors": sum(w["errors"] for w in windows),
        })
    for suffix, rows in (("summary", summary_rows), ("windows", window_rows), ("categories", packet_rows)):
        path = Path(str(prefix) + f"-{suffix}.csv")
        path.parent.mkdir(parents=True, exist_ok=True)
        fields = list(dict.fromkeys(k for row in rows for k in row))
        with path.open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        print(path)
    print(json.dumps(summary_rows, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-prefix", type=Path, required=True)
    parser.add_argument("directories", type=Path, nargs="+")
    args = parser.parse_args()
    merge(args.directories, args.output_prefix)
