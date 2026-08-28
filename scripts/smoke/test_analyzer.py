import unittest

from scripts.smoke.analyzer import _spatial_check


class SpatialCheckTest(unittest.TestCase):
    def test_cardinal_hole_is_p0(self):
        positions = [[-1, 0], [1, 0], [0, -1], [0, 1]]
        result = _spatial_check({"clientCache": {"actualPresent": {"positions": positions}}})
        self.assertEqual(result["cardinalHoles"], [[0, 0]])

    def test_complete_cross_has_no_hole(self):
        positions = [[x, z] for x in range(-1, 2) for z in range(-1, 2)]
        result = _spatial_check({"clientCache": {"actualPresent": {"positions": positions}}})
        self.assertEqual(result["cardinalHoles"], [])

    def test_string_positions_are_supported(self):
        positions = ["-1 0", "1 0", "0 -1", "0 1"]
        result = _spatial_check({"clientCache": {"actualPresent": {"positions": positions}}})
        self.assertEqual(result["cardinalHoles"], [[0, 0]])

    def test_r2_full_chunk_transfer_is_rejected(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "s.log").write_text(
                "HassiumSmokeTest:PASS\n"
                "CLIENT_STATS ROUND1 begin\nCLIENT_STATS ROUND1 end\n"
                "CLIENT_STATS ROUND2 begin\nCLIENT_STATS ROUND2 end\n"
            )
            probe = {"stats": {"fullChunkRequestCount": 1}, "chunkTrace": {}}
            result = {"SessionId": "s", "Scenario": "classic", "Probe": {"Round1": {}, "Round2": probe},
                      "GatewayRound1": {"gatewayState": "ACTIVE", "gatewayC2s": 1},
                      "GatewayRound2": {"gatewayState": "ACTIVE", "gatewayC2s": 1}}
            analysis = analyze_result(result, root)
            self.assertIn("R2_FULL_CHUNK_TRANSFER", {item["code"] for item in analysis["failures"]})

    def test_missing_probe_fails_classic(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "s.log").write_text("HassiumSmokeTest:PASS\nCLIENT_STATS ROUND1 begin\nCLIENT_STATS ROUND1 end\nCLIENT_STATS ROUND2 begin\nCLIENT_STATS ROUND2 end\n")
            result = {"SessionId": "s", "Scenario": "classic", "ServerSwitched": True,
                      "GatewayRound1": {"gatewayState": "ACTIVE", "gatewayC2s": 1},
                      "GatewayRound2": {"gatewayState": "ACTIVE", "gatewayC2s": 1, "gatewayS2c": 1}}
            analysis = analyze_result(result, root)
            self.assertIn("PROBE_MISSING", {item["code"] for item in analysis["failures"]})

    def test_expected_trace_gap_fails(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "s.log").write_text("HassiumSmokeTest:PASS\nCLIENT_STATS ROUND1 begin\nCLIENT_STATS ROUND1 end\nCLIENT_STATS ROUND2 begin\nCLIENT_STATS ROUND2 end\n")
            probe = {"chunkTrace": {"networkReceived": {"positions": [[0, 0]]},
                                     "shadowInjected": {"positions": [[0, 0]]},
                                     "shadowReady": {"positions": [[0, 0]]},
                                     "clientApplied": {"positions": []},
                                     "meshCompiled": {"positions": []}},
                     "clientCache": {"actualPresent": {"positions": []}}}
            result = {"SessionId": "s", "Scenario": "classic", "ServerSwitched": True,
                      "Probe": {"Round1": probe, "Round2": probe},
                      "GatewayRound1": {"gatewayState": "ACTIVE", "gatewayC2s": 1},
                      "GatewayRound2": {"gatewayState": "ACTIVE", "gatewayC2s": 1, "gatewayS2c": 1}}
            analysis = analyze_result(result, root)
            self.assertIn("TRACE_EXPECTED_NOT_PRESENT", {item["code"] for item in analysis["failures"]})


if __name__ == "__main__":
    unittest.main()
