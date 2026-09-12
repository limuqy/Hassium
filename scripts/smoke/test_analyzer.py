import unittest

from scripts.smoke.analyzer import _late_near_player, _server_full_push_timeouts, _spatial_check


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

    def test_seedgen_accepts_its_single_round_probe(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "s.log").write_text("HassiumSmokeTest:PASS\n")
            probe = {
                "stats": {"clientAppliedChunkCount": 1, "clientLandedChunkCount": 1},
                "chunkTrace": {},
                "clientCache": {"actualPresent": {"positions": [[0, 0]]}, "loadedChunks": 1},
            }
            result = {"SessionId": "s", "Scenario": "seedgen", "Probe": {"Round1": probe}}
            analysis = analyze_result(result, root)
            codes = {item["code"] for item in analysis["failures"]}
            self.assertNotIn("PROBE_MISSING", codes)
            self.assertNotIn("R2_FULL_CHUNK_TRANSFER", codes)

    def test_applied_without_resident_client_chunk_fails(self):
        from scripts.smoke.analyzer import _check_probe_metrics

        failures = _check_probe_metrics({
            "stats": {"clientAppliedChunkCount": 1, "clientLandedChunkCount": 1},
            "clientCache": {"loadedChunks": 0, "trackedCandidateCount": 1,
                            "actualPresent": {"positions": []}},
        }, 1)

        self.assertIn("CLIENT_CACHE_EMPTY", {item["code"] for item in failures})
    def test_native_trace_skips_removed_shadow_stages(self):
        from scripts.smoke.analyzer import _trace_analysis

        report = _trace_analysis({
            "chunkTrace": {
                "networkReceived": {"positions": [[0, 0]]},
                "clientApplied": {"positions": [[0, 0]]},
            },
            "clientCache": {"actualPresent": {"positions": [[0, 0]]}},
        })
        self.assertEqual(0, report["gaps"]["receivedNotInjected"]["count"])
        self.assertEqual(0, report["gaps"]["expectedNotPresent"]["count"])

    def test_classic_spatial_snapshot_is_diagnostic_not_gate(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "classic.log").write_text(
                "HassiumSmokeTest:PASS\n"
                "HassiumSmokeTest:CLIENT_STATS ROUND1 begin\nHassiumSmokeTest:CLIENT_STATS ROUND1 end\n"
                "HassiumSmokeTest:CLIENT_STATS ROUND2 begin\nHassiumSmokeTest:CLIENT_STATS ROUND2 end\n")
            probe = {
                "stats": {"clientAppliedChunkCount": 4, "clientLandedChunkCount": 4},
                "chunkTrace": {},
                "clientCache": {"actualPresent": {"positions": [[-1, 0], [1, 0], [0, -1], [0, 1]]}},
            }
            gateway = {"gatewayState": "ACTIVE", "gatewayC2s": 1}
            analysis = analyze_result({"SessionId": "classic", "Scenario": "classic",
                                       "ServerSwitched": True, "GatewayRound1": gateway,
                                       "GatewayRound2": gateway,
                                       "Probe": {"Round1": probe, "Round2": probe}}, root)
            self.assertNotIn("SPATIAL_CARDINAL_HOLE", {item["code"] for item in analysis["failures"]})

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

    def test_historical_session_suffix_logs_do_not_poison_markers(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            logs = root / "logs"
            logs.mkdir()
            (logs / "client_1.21.1_fabric_I.log").write_text(
                "HassiumSmokeTest:PASS\n"
                "Hassium: play init (caps=1)\n"
                "Hassium: Aggregation enabled for player\n"
                "CLIENT_STATS ROUND1 begin\nCLIENT_STATS ROUND1 end\n"
                "CLIENT_STATS ROUND2 begin\nCLIENT_STATS ROUND2 end\n")
            (logs / "client_1.21.1_fabric_I_p1b.log").write_text("HassiumSmokeTest:FAIL\n")
            result = {"SessionId": "1.21.1_fabric_I", "Scenario": "seedgen",
                      "Probe": {"Round1": {
                          "stats": {"clientAppliedChunkCount": 1, "clientLandedChunkCount": 1},
                          "chunkTrace": {},
                          "clientCache": {"actualPresent": {"positions": [[0, 0]]}, "loadedChunks": 1},
                      }}}
            analysis = analyze_result(result, root)
            self.assertNotIn("SMOKE_FAIL_MARKER_PRESENT", {item["code"] for item in analysis["failures"]})
            self.assertEqual(analysis["checks"]["smoke_markers"], "PASS")

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



    def test_late_near_player_chunk_is_diagnostic_input(self):
        def packed(x, z):
            return str((z & 0xffffffff) << 32 | (x & 0xffffffff))

        probe = {
            "playerPos": [-16, 64, -16],
            "chunkTrace": {"clientAppliedAtMs": {
                packed(20, 20): 1_000,
                packed(-1, -1): 12_000,
            }},
        }
        result = _late_near_player(probe)
        self.assertEqual(result[0]["position"], [-1, -1])
        self.assertEqual(result[0]["networkDelayMs"], 11_000)

    def test_server_full_push_timeout_is_parsed(self):
        text = "[PENDING_CONFIRM] 2 confirms timed out (>60000ms), direct-pushing stripped full to Player"
        self.assertEqual(_server_full_push_timeouts(text), [{
            "count": 2, "timeoutMs": 60000, "player": "Player"
        }])


class EnclosedHoleTest(unittest.TestCase):
    """落位点 3x3 永久空洞（P5-6）的回归门禁：空洞必须被已持有柱完全包围才算。"""

    @staticmethod
    def _ring_around_3x3():
        """5x5 方环（切比雪夫 2 的一圈 16 格）：正中间的 3x3 从未投递，且被完全包围。"""
        return [[x, z] for x in range(-2, 3) for z in range(-2, 3) if max(abs(x), abs(z)) == 2]

    @staticmethod
    def _root_with_logs(directory, name):
        from pathlib import Path
        root = Path(directory)
        (root / "logs").mkdir()
        (root / "logs" / f"{name}.log").write_text(
            "HassiumSmokeTest:PASS\n"
            "CLIENT_STATS ROUND1 begin\nCLIENT_STATS ROUND1 end\n"
            "CLIENT_STATS ROUND2 begin\nCLIENT_STATS ROUND2 end\n")
        return root

    def _analyze_ring(self, scenario):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = self._root_with_logs(directory, scenario)
            probe = {
                "stats": {"clientAppliedChunkCount": 16, "clientLandedChunkCount": 16},
                "chunkTrace": {},
                "clientCache": {"actualPresent": {"positions": self._ring_around_3x3()}},
            }
            return analyze_result({"SessionId": scenario, "Scenario": scenario,
                                   "ServerSwitched": True,
                                   "Probe": {"Round1": probe, "Round2": probe}}, root)

    def test_enclosed_3x3_is_detected_as_one_block(self):
        from scripts.smoke.analyzer import _hole_check

        report = _hole_check({"clientCache": {"actualPresent": {"positions": self._ring_around_3x3()}}})
        self.assertTrue(report["available"])
        self.assertEqual(report["enclosedCount"], 9)
        self.assertEqual(report["largestComponent"], 9)
        self.assertEqual(report["components"], [9])

    def test_open_edge_gap_is_not_a_hole(self):
        from scripts.smoke.analyzer import _hole_check

        report = _hole_check({"clientCache": {"actualPresent": {"positions": [[0, 0], [1, 0], [2, 0]]}}})
        self.assertTrue(report["available"])
        self.assertEqual(report["enclosedCount"], 0)

    def test_classic_enclosed_hole_fails(self):
        failures = [item for item in self._analyze_ring("classic")["failures"]
                    if item["code"] == "TRACE_ENCLOSED_HOLE"]
        self.assertTrue(failures)
        self.assertEqual(failures[0]["largestComponent"], 9)

    def test_non_classic_scenario_does_not_gate_on_enclosed_hole(self):
        analysis = self._analyze_ring("seedgen")
        self.assertNotIn("TRACE_ENCLOSED_HOLE", {item["code"] for item in analysis["failures"]})

    def test_small_enclosed_hole_is_warning_not_failure(self):
        from scripts.smoke.analyzer import analyze_result
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = self._root_with_logs(directory, "classic")
            probe = {
                "stats": {"clientAppliedChunkCount": 8, "clientLandedChunkCount": 8},
                "chunkTrace": {},
                "clientCache": {"actualPresent": {"positions": [
                    [0, 0], [1, 0], [2, 0], [0, 1], [2, 1], [0, 2], [1, 2], [2, 2]]}},
            }
            analysis = analyze_result({"SessionId": "classic", "Scenario": "classic",
                                       "ServerSwitched": True,
                                       "Probe": {"Round1": probe, "Round2": probe}}, root)
            self.assertNotIn("TRACE_ENCLOSED_HOLE", {item["code"] for item in analysis["failures"]})
            self.assertIn("TRACE_ENCLOSED_HOLE_SMALL", {item["code"] for item in analysis["warnings"]})


if __name__ == "__main__":
    unittest.main()
