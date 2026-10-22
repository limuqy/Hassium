"""Extract final cumulative samples per process/epoch. Quantiles must not be averaged across epochs.

Usage: python scripts/analyze-chunk-profile.py build/smoke-test/logs/*chunkperf*.log > report.json
"""
import argparse
import glob
import json
import re
from pathlib import Path


def read_profile(path):
    epochs = {}
    with path.open(encoding="utf-8-sig", errors="replace") as stream:
        for line in stream:
            if "[CHUNK-PROFILE]" not in line:
                continue
            fields = dict(re.findall(r"(\w+)=(\S+)", line))
            if "side" not in fields or "epoch" not in fields:
                continue
            key = f"{fields['side']}:{fields['epoch']}"
            epoch = epochs.setdefault(key, {"stages": {}, "counters": {}})
            if "stage" in fields:
                epoch["stages"][f"{fields['source']}:{fields['stage']}"] = {
                    name: int(fields[name])
                    for name in ("n", "meanNs", "p50Ns", "p95Ns", "p99Ns", "maxNs")
                }
            elif "counters=" in line:
                epoch["counters"] = {
                    name: int(value) for name, value in re.findall(r"([A-Z_]+)=(\d+)", line)
                }
    return epochs


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+")
    args = parser.parse_args()
    paths = sorted({Path(p) for pattern in args.logs for p in glob.glob(pattern)})
    if not paths:
        parser.error("no matching log files")
    print(json.dumps({str(p): read_profile(p) for p in paths}, ensure_ascii=False, indent=2))
