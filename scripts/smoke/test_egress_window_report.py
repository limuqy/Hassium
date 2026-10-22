import json
import tempfile
import unittest
from pathlib import Path

from scripts.smoke.egress_window_report import difference, expected_packets, percentile_upper, read_run


def stress_fixture():
    requested = dict(rate=1024, warmupTicks=900, measureTicks=1200, drainTicks=100)
    workload = requested | dict(expected=2688000, stateBytes=3072, dictionaryFrozen=True,
                                steadyPlacement="before-flush", burstPlacement="after-flush")
    phases = {phase: {"workload": workload.copy()} for phase in ("warmup", "steady", "burst", "drain", "done")}
    return {"Scenario": "egressstress", "EgressStressWorkload": requested}, phases


class StressWorkloadGateTest(unittest.TestCase):
    def test_expected_comes_from_requested_workload(self):
        result, phases = stress_fixture()
        self.assertEqual(2688000, expected_packets(result, phases))
        phases["burst"]["workload"]["rate"] = 512
        with self.assertRaises(ValueError):
            expected_packets(result, phases)

    def test_rejects_missing_or_self_reported_lower_count(self):
        result, phases = stress_fixture()
        phases["done"]["workload"]["expected"] -= 1
        with self.assertRaises(ValueError):
            expected_packets(result, phases)
        with self.assertRaises(ValueError):
            expected_packets({"Scenario": "egressstress"}, phases)

class EgressWindowReportTest(unittest.TestCase):
    def test_profile_window_and_explicit_override_are_validated_from_logs(self):
        result, phases = stress_fixture()
        result.update(Result="PASS", Ver="1.20.1", Loader="forge")
        for index, (name, phase) in enumerate(phases.items()):
            phase.update(
                phase=name, settings={"waitMs": 50},
                sent=[{"packets": 2688000, "callbacks": 10459}] * 2,
                metrics={"errors": 0},
                cpu={"nanoTime": index * 1000, "processCpuNs": index * 200,
                     "workerCpuNs": index * 100, "threadCpuSupported": True, "workerThreads": 2},
                profileBuckets={"OUTBOUND_TOTAL": [index]},
                profile={"OUTBOUND_TOTAL": {"totalNs": index * 100, "allocatedBytes": index * 10,
                                            "allocationSamples": index}})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            smoke = root / "build/smoke-test"
            (smoke / "results").mkdir(parents=True)
            (smoke / "logs").mkdir()
            (smoke / "logs/server_s.log").write_text(
                "\n".join("[EGRESS_LOAD] " + json.dumps(phase) for phase in phases.values()), encoding="utf-8")
            for role in ("client", "observer"):
                (smoke / f"logs/{role}_s.log").write_text(
                    "[EGRESS_LOAD_CLIENT] received=2688000 profile={}\n", encoding="utf-8")
            result_path = smoke / "results/result_s.json"
            for requested in (None, 0, 50):
                with self.subTest(requested=requested):
                    if requested is not None:
                        result["AggregationMaxWaitTimeMs"] = requested
                    result_path.write_text(json.dumps(result), encoding="utf-8")
                    run = read_run(root, "s")
                    self.assertEqual(50, run["settings"]["waitMs"])
                    self.assertEqual(300, run["intervals"]["measured"]["cpu"]["workerCpuNs"])
            result["AggregationMaxWaitTimeMs"] = 10
            result_path.write_text(json.dumps(result), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "effective window differs"):
                read_run(root, "s")

    def test_interval_uses_histogram_difference_instead_of_subtracting_percentiles(self):
        warmup = [0] * 488
        warmup[0] = 1000
        after = warmup.copy()
        after[40] = 80
        self.assertEqual(0, percentile_upper(after, 90))
        interval = difference(warmup, after)
        self.assertEqual(80, sum(interval))
        self.assertEqual(143, percentile_upper(interval, 95))
        self.assertEqual(143, percentile_upper(interval, 99))

    def test_empty_and_extreme_histogram_bounds(self):
        counts = [0] * 488
        self.assertEqual(0, percentile_upper(counts, 99))
        counts[-1] = 1
        self.assertEqual((1 << 63) - 1, percentile_upper(counts, 99))

    def test_reset_and_layout_change_are_rejected(self):
        with self.assertRaises(ValueError):
            difference([2], [1])
        with self.assertRaises(ValueError):
            difference([0], [0, 1])


if __name__ == "__main__":
    unittest.main()
