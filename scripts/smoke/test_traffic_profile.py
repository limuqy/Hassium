import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("traffic_profile", Path(__file__).with_name("analyze-traffic-profile.py"))
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class TrafficProfileTest(unittest.TestCase):
    def fixture(self, directory):
        rows = []
        for second in range(61):
            row = {
                "phase": "traffic_real_factory", "fixture": "user_factory_1.21.1",
                "edge": "start" if second == 0 else "end" if second == 60 else "sample",
                "timestampMs": 100000 + second * 1000, "nanoTime": (second + 100) * 10**9,
                "ticks": second * 20, "wireBytes": 1000 + second * 40,
                "originalBytes": 2000 + second * 20, "aggregateBytes": 500 + second * 10,
                "bypassBytes": 0, "packets": second, "frames": second, "compressionNanos": second * 100,
                "compressionCpuNanos": 0, "errors": 0, "outputItems": 0,
                "x": 1, "y": 2, "z": 3, "yaw": 0, "pitch": 10, "frozen": False,
            }
            rows.append(row)
        captures = []
        for second in range(60):
            captures.append({"src": "native_v3", "ts": 100500 + second * 1000,
                             "type": "minecraft:block_update", "size": 20})
            captures.append({"src": "agg_v3", "ts": 100600 + second * 1000, "size": 10, "trainedEpoch": 1})
        (directory / "agg-test.jsonl").write_text("\n".join(map(json.dumps, captures)), encoding="utf-8")
        (directory / "result_test.json").write_text('{"Result":"PASS"}', encoding="utf-8")
        return rows

    def run_profile(self, directory, rows):
        (directory / "server_test.log").write_text(
            "\n".join("[TRAFFIC_PROFILE] " + json.dumps(r) for r in rows), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            failed = profile.analyze(directory)
        return failed, json.loads((directory / "traffic-summary.json").read_text(encoding="utf-8"))

    def test_successful_wire_counter_is_not_sum_of_native_and_aggregate(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            failed, report = self.run_profile(directory, self.fixture(directory))
            self.assertFalse(failed)
            window = report["windows"][0]
            self.assertEqual(window["wireBytes"], 2400)
            self.assertEqual(window["bytesPerSecond"], 40)
            self.assertEqual(window["capturedNativeBytes"], 1200)
            self.assertEqual(window["capturePayloadBytes"], 600)
            self.assertEqual(window["lastFiveSecondsBlockPackets"], [1] * 5)

    def test_stationary_sample_rejects_movement_and_frozen_world(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            rows = self.fixture(directory)
            rows[30]["x"] += 1
            rows[31]["frozen"] = True
            failed, report = self.run_profile(directory, rows)
            self.assertTrue(failed)
            self.assertTrue(any("not stable" in p for p in report["qualityProblems"]))
            self.assertTrue(any("frozen world" in p for p in report["qualityProblems"]))

    def test_missing_end_does_not_become_complete_measurement(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            rows = self.fixture(directory)
            failed, report = self.run_profile(directory, rows[:-1])
            self.assertTrue(failed)
            self.assertEqual(report["windows"], [])


if __name__ == "__main__":
    unittest.main()
