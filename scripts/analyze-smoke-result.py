#!/usr/bin/env python3
"""Run all offline business gates for one runtime smoke result."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from smoke.analyzer import analyze_result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result_path = args.result.resolve()
        data = json.loads(result_path.read_text(encoding="utf-8-sig"))
        if not isinstance(data, dict):
            raise ValueError("result JSON root must be an object")
        analysis = analyze_result(data, result_path.parent.parent)
        output = args.output or result_path.with_name(result_path.stem + "_analysis.json")
        output.write_text(json.dumps(analysis, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(analysis, ensure_ascii=False, separators=(",", ":")))
        return 0 if analysis["pass"] else 1
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"smoke analyzer input error: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:  # pragma: no cover - defensive CLI boundary
        print(f"smoke analyzer error: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    raise SystemExit(main())
