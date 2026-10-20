"""Export the OVD moving-light fixture and measured client/shadow intensities."""
from __future__ import annotations
import argparse
import csv
import json
import re
from pathlib import Path

SAMPLE = re.compile(
    r"\[(?P<time>\d\d:\d\d:\d\d)\].*\[OVD_LIGHT_SAMPLE\] z=(?P<z>-?\d+) "
    r"clientSky=(?P<client_sky>\d+) shadowSky=(?P<shadow_sky>\d+) "
    r"clientBlock=(?P<client_block>\d+) shadowBlock=(?P<shadow_block>\d+) "
    r"floor=Block\{(?P<floor>[^}]+)\} .*correct=(?P<light_correct>true|false)"
)


def limits(z: int) -> dict:
    region = "eaves" if 16 <= z <= 176 else "cave" if 280 <= z <= 488 else "transition"
    return {"region": region, "sky_max": 6 if region == "eaves" else 0 if region == "cave" else None,
            "block_min": 10 - min(abs(z - 320), abs(z - 448)) if min(abs(z - 320), abs(z - 448)) <= 3 else None}


def positions() -> list[dict]:
    return [{"dimension": "minecraft:overworld", "x": 0, "y": 299, "z": z,
             "floor_x": 0, "floor_y": 297, "floor_z": z,
             "floor_block": "minecraft:quartz_block", **limits(z)} for z in range(-56, 697)]


def samples(log: str) -> list[dict]:
    number = 1
    rows = []
    for line in log.splitlines():
        if "WAIT_JOIN_2 player entered world" in line:
            number = 2
        match = SAMPLE.search(line)
        if not match:
            continue
        row = match.groupdict()
        for key in ("z", "client_sky", "shadow_sky", "client_block", "shadow_block"):
            row[key] = int(row[key])
        row.update(round=number, direction="OUT" if number == 1 else "BACK", x=0, y=299,
                   **limits(row["z"]))
        sky_max, block_min = row["sky_max"], row["block_min"]
        row["client_sky_leak"] = sky_max is not None and row["client_sky"] > sky_max
        row["shadow_sky_leak"] = sky_max is not None and row["shadow_sky"] > sky_max
        row["client_dark_source"] = block_min is not None and row["client_block"] < block_min
        row["shadow_dark_source"] = block_min is not None and row["shadow_block"] < block_min
        row["floor_missing"] = row["floor"] != "minecraft:quartz_block"
        rows.append(row)
    return rows


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def export(root: Path, session: str) -> dict:
    output = root / "build/smoke-test/light-data" / session
    output.mkdir(parents=True, exist_ok=True)
    fixture = positions()
    write_csv(output / "test-positions.csv", fixture)
    rows = samples((root / f"build/smoke-test/logs/client_{session}.log").read_text(encoding="utf-8-sig"))
    summary = {"session": session, "dimension": "minecraft:overworld", "sample_y": 299,
               "planned_positions": len(fixture), "rounds": [], "pass": True}
    for number in (1, 2):
        measured = [row for row in rows if row["round"] == number]
        if measured:
            write_csv(output / f"round{number}-light.csv", measured)
        counts = {key: sum(bool(row[key]) for row in measured) for key in (
            "client_sky_leak", "shadow_sky_leak", "client_dark_source", "shadow_dark_source", "floor_missing")}
        probe_path = root / f"build/smoke-test/probe/{session}/round{number}.json"
        probe = json.loads(probe_path.read_text(encoding="utf-8-sig")) if probe_path.exists() else {}
        movement = probe.get("lightFixture", {})
        coverage = {region: sum(row["region"] == region for row in measured) for region in ("eaves", "cave")}
        passed = (coverage["eaves"] >= 120 and coverage["cave"] >= 160 and not any(counts.values())
                  and movement.get("movementSamples", 0) >= 600
                  and all(movement.get(key) == 0 for key in (
                      "movementFloorMissing", "movementSkyLeaks", "movementDarkSources", "mismatches")))
        ranges = {}
        for region in ("eaves", "cave"):
            selected = [row for row in measured if row["region"] == region]
            ranges[region] = {key: [min(row[key] for row in selected), max(row[key] for row in selected)]
                              for key in ("client_sky", "shadow_sky")} if selected else {}
        summary["rounds"].append({"round": number, "samples": len(measured), "coverage": coverage,
                                  "sky_ranges": ranges,
                                  "light_sources": [{key: row[key] for key in
                                      ("x", "y", "z", "client_block", "shadow_block", "block_min")}
                                      for row in measured if row["z"] in (320, 448)],
                                  "failures": counts, "continuous_probe": movement, "pass": passed})
        summary["pass"] &= passed
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    result = export(args.root, args.session)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
