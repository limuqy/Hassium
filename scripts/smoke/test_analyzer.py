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

    def test_flyroundtrip_accepts_its_single_round_probe(self):
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
                "counters": {"clientDarkLightProbeChunks": 0, "clientDarkRegressionChunks": 0},
            }
            result = {"SessionId": "s", "Scenario": "flyroundtrip", "Probe": {"Round1": probe}}
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

    def test_ready_not_applied_excludes_seedgen_local_residency(self):
        """seedgen：本地柱进 ready/applied，但不在 networkReceived → actualPresent。
        readyNotApplied 必须按 ready−clientApplied，不能减 actualPresent。"""
        from scripts.smoke.analyzer import _trace_analysis

        network = [[0, 0]]
        local = [[1, 0], [2, 0]]
        report = _trace_analysis({
            "chunkTrace": {
                "networkReceived": {"positions": network},
                "shadowInjected": {"positions": network + local},
                "shadowReady": {"positions": network + local},
                "clientApplied": {"positions": network + local},
                "meshCompiled": {"positions": network + local},
            },
            "clientCache": {"actualPresent": {"positions": network}},
        })
        self.assertEqual(0, report["gaps"]["readyNotApplied"]["count"])
        self.assertEqual(0, report["gaps"]["expectedNotPresent"]["count"])

    def test_ready_not_applied_detects_true_unapplied(self):
        from scripts.smoke.analyzer import _trace_analysis

        report = _trace_analysis({
            "chunkTrace": {
                "networkReceived": {"positions": [[0, 0]]},
                "shadowReady": {"positions": [[0, 0], [1, 0]]},
                "clientApplied": {"positions": [[0, 0]]},
                "meshCompiled": {"positions": [[0, 0]]},
            },
            "clientCache": {"actualPresent": {"positions": [[0, 0]]}},
        })
        self.assertEqual(1, report["gaps"]["readyNotApplied"]["count"])
        self.assertEqual([[1, 0]], report["gaps"]["readyNotApplied"]["positions"])

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
    def _ring_with_center_gap():
        """3x3 少正中一格：唯一 1 格封闭空洞（P1 量级）。"""
        return [[x, z] for x in range(3) for z in range(3) if (x, z) != (1, 1)]

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

    def _analyze(self, scenario, positions):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = self._root_with_logs(directory, scenario)
            count = len(positions)
            probe = {
                "stats": {"clientAppliedChunkCount": count, "clientLandedChunkCount": count},
                "chunkTrace": {},
                "clientCache": {"actualPresent": {"positions": positions}},
            }
            return analyze_result({"SessionId": scenario, "Scenario": scenario,
                                   "ServerSwitched": True,
                                   "Probe": {"Round1": probe, "Round2": probe}}, root)

    def _analyze_ring(self, scenario):
        return self._analyze(scenario, self._ring_around_3x3())

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

    def test_dimension_enclosed_hole_is_diagnostic(self):
        """切维场景用 loadedChunks/clientApplied 门禁；候选采样洞只能作诊断。"""
        analysis = self._analyze_ring("dimension")
        self.assertNotIn("TRACE_ENCLOSED_HOLE", {item["code"] for item in analysis["failures"]})

    def test_dimension_small_hole_is_not_gated(self):
        """dimension 只判 P0：零散单格小洞不告警（维边界/采样边缘噪声）。"""
        analysis = self._analyze("dimension", self._ring_with_center_gap())
        self.assertNotIn("TRACE_ENCLOSED_HOLE", {item["code"] for item in analysis["failures"]})
        self.assertNotIn("TRACE_ENCLOSED_HOLE_SMALL", {item["code"] for item in analysis["warnings"]})

    def test_small_enclosed_hole_is_warning_not_failure(self):
        analysis = self._analyze("classic", self._ring_with_center_gap())
        self.assertNotIn("TRACE_ENCLOSED_HOLE", {item["code"] for item in analysis["failures"]})
        self.assertIn("TRACE_ENCLOSED_HOLE_SMALL", {item["code"] for item in analysis["warnings"]})


class MobileSessionTraceTest(unittest.TestCase):
    """F14：`-MoveSeconds > 0` 的会话走开后柱会合法卸载，trace 的「驻留」口径不适用。"""

    _RETENTION_GAP_PROBE = {
        "chunkTrace": {"networkReceived": {"positions": [[0, 0]]},
                       "shadowInjected": {"positions": [[0, 0]]},
                       "shadowReady": {"positions": [[0, 0]]},
                       "clientApplied": {"positions": []},
                       "meshCompiled": {"positions": []}},
        "clientCache": {"actualPresent": {"positions": []}},
    }

    def _analyze(self, move_seconds, probe, vd1=0, vd2=0, move_log=""):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "s.log").write_text(
                "HassiumSmokeTest:PASS\n"
                "CLIENT_STATS ROUND1 begin\nCLIENT_STATS ROUND1 end\n"
                "CLIENT_STATS ROUND2 begin\nCLIENT_STATS ROUND2 end\n")
            (root / "logs" / "client_s.log").write_text(move_log)
            result = {"SessionId": "s", "Scenario": "classic", "ServerSwitched": True,
                       "MoveSeconds": move_seconds, "Vd1": vd1, "Vd2": vd2,
                       "Ver": "1.21.1",
                      "Probe": {"Round1": probe, "Round2": probe},
                      "GatewayRound1": {"gatewayState": "ACTIVE", "gatewayC2s": 1},
                      "GatewayRound2": {"gatewayState": "ACTIVE", "gatewayC2s": 1, "gatewayS2c": 1}}
            return analyze_result(result, root)

    def test_stationary_session_retention_gap_is_failure(self):
        analysis = self._analyze(0, self._RETENTION_GAP_PROBE)
        self.assertIn("TRACE_EXPECTED_NOT_PRESENT", {item["code"] for item in analysis["failures"]})

    def test_mobile_session_retention_gap_is_diagnostic(self):
        analysis = self._analyze(12, self._RETENTION_GAP_PROBE)
        codes = {item["code"] for item in analysis["failures"]}
        self.assertNotIn("TRACE_EXPECTED_NOT_PRESENT", codes)
        self.assertNotIn("TRACE_READY_NOT_APPLIED", codes)
        self.assertIn("TRACE_EXPECTED_NOT_PRESENT", {item["code"] for item in analysis["skipped"]})

    def test_mobile_requires_java_movement_and_chunk_displacement(self):
        missing = self._analyze(10, self._RETENTION_GAP_PROBE)
        self.assertIn("MOVEMENT_NOT_EXECUTED", {item["code"] for item in missing["failures"]})
        tp = "HassiumSmokeTest:MOVE_TP_REQUEST targetY=256 pos=(0, 80, 0)\n"
        start = "HassiumSmokeTest:MOVE_START cruise 10s pos=(0, 256, 0) targetY=256\n"
        zero = (tp + start + "HassiumSmokeTest:MOVE_END pos=(0, 256, 0)\n") * 2
        stationary = self._analyze(10, self._RETENTION_GAP_PROBE, move_log=zero)
        self.assertIn("MOVEMENT_NOT_EXECUTED", {item["code"] for item in stationary["failures"]})
        moved = (tp + start + "HassiumSmokeTest:MOVE_END pos=(0, 256, 64)\n") * 2
        executed = self._analyze(10, self._RETENTION_GAP_PROBE, move_log=moved)
        self.assertNotIn("MOVEMENT_NOT_EXECUTED", {item["code"] for item in executed["failures"]})
        tp_only = (tp + start + "HassiumSmokeTest:MOVE_END pos=(0, 256, 0)\n") * 2
        self.assertIn("MOVEMENT_NOT_EXECUTED", {item["code"] for item in
                      self._analyze(10, self._RETENTION_GAP_PROBE, move_log=tp_only)["failures"]})

    def test_mobile_session_delivery_gap_still_fails(self):
        """投递链缺口（networkReceived→injected→ready）与驻留无关，移动会话同样把守。"""
        probe = {
            "chunkTrace": {"networkReceived": {"positions": [[0, 0]]},
                           "shadowInjected": {"positions": []},
                           "shadowReady": {"positions": []},
                           "clientApplied": {"positions": []},
                           "meshCompiled": {"positions": []}},
            "clientCache": {"actualPresent": {"positions": []}},
        }
        analysis = self._analyze(12, probe)
        self.assertIn("TRACE_RECEIVED_NOT_INJECTED", {item["code"] for item in analysis["failures"]})

    def test_mobile_session_enclosed_hole_still_fails(self):
        """真正的虚空门禁不受移动口径影响：成片封闭空洞仍然是 P0。"""
        ring = [[x, z] for x in range(-2, 3) for z in range(-2, 3) if max(abs(x), abs(z)) == 2]
        probe = {"chunkTrace": {}, "clientCache": {"actualPresent": {"positions": ring}}}
        analysis = self._analyze(12, probe)
        failures = [item for item in analysis["failures"] if item["code"] == "TRACE_ENCLOSED_HOLE"]
        self.assertTrue(failures)
        self.assertEqual(failures[0]["largestComponent"], 9)


class HaloAnchorTest(unittest.TestCase):
    """光环判定的两个锚点陷阱：玩家区块必须 floor 取整；形状公式必须随 mc 版本分段。

    背景（2026-09-20 `1.21.4_fabric_I_m1` 假 P0 实证）：
    1. `_player_chunk` 曾用 `int(coord) >> 4`（截断）——玩家 z=-0.5 时锚点偏 1 格，
       设计上不交付的光环环被判成形状内缺口 → 假 P0（本场 P0 的直接原因：
       同一份 trace 按正确锚点 0 个缺口在形状内，按截断锚点 21 个）。
    2. `_in_authority_shape` 曾把 1.21.3 及更早的 chebyshev 折算公式写死——原版 1.21.4
       改为纯欧氏圆（VD=20 形状 1529 → 1573 柱，旧形状是新形状的真子集，差 44 柱）。
       旧公式会把「新形状外环漏交」误判成光环（假 PASS 门洞），必须随版本分段。
    """

    def test_player_chunk_floors_negative_fraction(self):
        from scripts.smoke.analyzer import _player_chunk
        # z=-0.5 属于区块 -1（floor），不是 0（截断）。
        self.assertEqual((-2, -1), _player_chunk({"playerPos": [-29.5, 64.0, -0.5]}))
        self.assertEqual((-3, 0), _player_chunk({"playerPos": [-40.5, 64.0, 8.5]}))
        self.assertIsNone(_player_chunk({"playerPos": [-29.5]}))

    def test_authority_shape_is_version_segmented(self):
        from scripts.smoke.analyzer import _in_authority_shape
        # (cx+21, cz+8)：1.21.3 公式在形状外，1.21.4 纯欧氏圆在形状内——两代形状的判别点。
        self.assertFalse(_in_authority_shape(0, 0, 20, 21, 8, "1.21.3"))
        self.assertTrue(_in_authority_shape(0, 0, 20, 21, 8, "1.21.4"))
        self.assertTrue(_in_authority_shape(0, 0, 20, 21, 8, "1.21.11"))
        # 1.20.1 与 1.21.1–1.21.3 走旧公式。
        self.assertFalse(_in_authority_shape(0, 0, 20, 21, 8, "1.20.1"))
        self.assertFalse(_in_authority_shape(0, 0, 20, 21, 8, "1.21.1"))
        # 未知版本按旧公式兜底（保守：不放宽缺口判定）。
        self.assertFalse(_in_authority_shape(0, 0, 20, 21, 8, None))

    def test_shape_size_matches_vanilla_per_version(self):
        from scripts.smoke.analyzer import _in_authority_shape

        def size(ver):
            return sum(1 for x in range(-30, 31) for z in range(-30, 31)
                       if _in_authority_shape(0, 0, 20, x, z, ver))

        self.assertEqual(1529, size("1.21.3"))
        self.assertEqual(1573, size("1.21.4"))

    def test_modern_halo_gap_is_not_p0(self):
        """修复后的产品（计算域 = 1.21.4 新形状膨胀）+ 负小数玩家坐标：
        整环缺口必须判成光环（INFO）——锚点 floor 与版本公式两个修复同时生效。"""
        from scripts.smoke.analyzer import _halo_only_gap, _in_authority_shape
        # 玩家 [0.5,·,-0.5] → 真实区块 (0,-1)；交付/计算域都围绕真实区块（与实跑一致）。
        cx, cz = 0, -1
        authority = {(x, z) for x in range(-30, 31) for z in range(-30, 31)
                     if _in_authority_shape(cx, cz, 20, x, z, "1.21.4")}
        # 计算域 = 1.21.4 形状的切比雪夫膨胀（Java 侧 = 收缩 1 后委托新公式 ⇒ offset 3）。
        compute = {(x, z) for x in range(-30, 31) for z in range(-30, 31)
                   if max(0, abs(x - cx) - 3) ** 2 + max(0, abs(z - cz) - 3) ** 2 < 400}
        halo = sorted(compute - authority)
        self.assertEqual(176, len(halo))
        probe = probe_for(authority, compute, (cx, cz))
        gaps = {"injectedNotReady": {"count": len(halo), "positions": [list(p) for p in halo]},
                "expectedNotPresent": {"count": len(halo), "positions": [list(p) for p in halo]}}
        self.assertTrue(_halo_only_gap(probe, gaps, 20, "1.21.4"))

    def test_stale_compute_domain_is_judged_halo_under_correct_formula(self):
        """1.21.4_m1 实况复现：产品计算域还是旧形状膨胀（1705）、交付已是新形状（1573），
        132 缺口在正确公式下全部是光环；旧公式（小形状）也会全部放行——因为它把
        新形状外环当成「形状外」。真正把这场判成 P0 的是锚点截断（见
        test_player_chunk_floors_negative_fraction）。"""
        from scripts.smoke.analyzer import _halo_only_gap, _in_authority_shape
        new_shape = {(x, z) for x in range(-30, 31) for z in range(-30, 31)
                     if _in_authority_shape(0, 0, 20, x, z, "1.21.4")}
        old_shape = {(x, z) for x in range(-30, 31) for z in range(-30, 31)
                     if _in_authority_shape(0, 0, 20, x, z, "1.21.3")}
        # 旧公式形状是新形状的真子集（差 44 柱）——写死旧公式会把这 44 柱的漏交误判成光环。
        self.assertEqual(44, len(new_shape - old_shape))
        self.assertFalse(old_shape - new_shape)
        stale_compute = {(x, z) for x in range(-30, 31) for z in range(-30, 31)
                         if _old_dilated(x, z, 20)}
        gap = sorted(stale_compute - new_shape)
        self.assertEqual(132, len(gap))
        probe = probe_for(new_shape, stale_compute, (0, 0))
        gaps = {"injectedNotReady": {"count": len(gap), "positions": [list(p) for p in gap]},
                "expectedNotPresent": {"count": len(gap), "positions": [list(p) for p in gap]}}
        self.assertTrue(_halo_only_gap(probe, gaps, 20, "1.21.4"))
        self.assertTrue(_halo_only_gap(probe, gaps, 20, "1.21.3"))

    def test_mobile_trace_retires_only_outside_final_complete_window(self):
        """累计注入来自旧中心；最终窗完整驻留时旧窗差集无交付义务。"""
        from scripts.smoke.analyzer import _mobile_retired_gap, _in_authority_shape

        cx, cz, vd = -2, -1, 20
        current = {(x, z) for x in range(cx - 22, cx + 23)
                   for z in range(cz - 22, cz + 23)
                   if _in_authority_shape(cx, cz, vd, x, z, "1.21.1")}
        old = {(x, z) for x in range(-22, 23) for z in range(-22, 23)
               if _in_authority_shape(0, 0, vd, x, z, "1.21.1")}
        retired = old - current
        self.assertTrue(retired)
        probe = {"playerPos": [-23.5, 76, -4.5],
                 "clientCache": {"actualPresent": {
                     "count": len(current), "positions": [list(p) for p in current]}}}
        self.assertTrue(_mobile_retired_gap(probe, retired, vd, "1.21.1"))
        # 一个真正的窗内未 ready 柱，即使排在 64 项报告之外，也必须仍为 P0。
        self.assertFalse(_mobile_retired_gap(probe, retired | {(cx, cz)}, vd, "1.21.1"))
        probe["clientCache"]["actualPresent"]["positions"].remove([cx, cz])
        self.assertFalse(_mobile_retired_gap(probe, retired, vd, "1.21.1"))

    def test_mobile_retired_gap_uses_26x_authority_shape(self):
        from scripts.smoke.analyzer import _mobile_retired_gap, _in_authority_shape

        cx, cz, vd = -2, -1, 20
        current = {(x, z) for x in range(cx - 22, cx + 23)
                   for z in range(cz - 22, cz + 23)
                   if _in_authority_shape(cx, cz, vd, x, z, "26.3")}
        old = {(x, z) for x in range(-22, 23) for z in range(-22, 23)
               if _in_authority_shape(0, 0, vd, x, z, "26.3")}
        self.assertEqual(len(current), 1573)
        probe = {"playerPos": [-23.5, 76, -4.5],
                 "clientCache": {"actualPresent": {
                     "count": len(current), "positions": [list(p) for p in current]}}}
        self.assertTrue(_mobile_retired_gap(probe, old - current, vd, "26.3"))
        self.assertFalse(_mobile_retired_gap(probe, (old - current) | {(cx, cz)}, vd, "26.3"))

    def test_mobile_final_window_missing_column_is_p0(self):
        from scripts.smoke.analyzer import _in_authority_shape

        vd = 20
        current = {(x, z) for x in range(-22, 23) for z in range(-22, 23)
                   if _in_authority_shape(0, 0, vd, x, z, "1.21.1")}
        old = {(x, z) for x in range(-23, 22) for z in range(-23, 22)
               if _in_authority_shape(-1, -1, vd, x, z, "1.21.1")}
        resident = current - {(0, 0)}
        probe = probe_for(resident, current | old, (0, 0))
        probe["chunkTrace"]["shadowReady"]["count"] = len(resident)
        probe["clientCache"]["actualPresent"]["count"] = len(resident)
        analysis = MobileSessionTraceTest()._analyze(10, probe, vd1=vd, vd2=10)
        self.assertIn(("TRACE_INJECTED_NOT_READY", 1),
                      {(item["code"], item.get("round")) for item in analysis["failures"]})

    def test_mobile_r2_ovd_belt_still_p0(self):
        from scripts.smoke.analyzer import _in_authority_shape

        vd = 10
        authority = {(x, z) for x in range(-12, 13) for z in range(-12, 13)
                     if _in_authority_shape(0, 0, vd, x, z, "1.21.1")}
        probe = probe_for(authority, authority | {(14, 0)}, (0, 0))
        probe["clientCache"]["actualPresent"]["count"] = len(authority)
        # 精确命中旧 cache_only_reconnect 分支；(14,0) 在 OVD16 内但在 VD10 外。
        probe["chunkTrace"]["networkReceived"]["positions"] = []
        probe["stats"] = {"fullChunkRequestCount": 0,
                          "clientLandedChunkCount": len(authority)}
        probe["clientCache"]["loadedChunks"] = len(authority)
        analysis = MobileSessionTraceTest()._analyze(10, probe, vd1=20, vd2=vd)
        self.assertIn(("TRACE_INJECTED_NOT_READY", 2),
                      {(item["code"], item.get("round")) for item in analysis["failures"]})

    def test_cache_only_r2_retired_outside_ovd_is_p1(self):
        from scripts.smoke.analyzer import _in_authority_shape

        vd = 10
        authority = {(x, z) for x in range(-12, 13) for z in range(-12, 13)
                     if _in_authority_shape(0, 0, vd, x, z, "1.21.1")}
        probe = probe_for(authority, authority | {(17, 0)}, (0, 0))
        probe["chunkTrace"]["networkReceived"]["positions"] = []
        probe["stats"] = {"fullChunkRequestCount": 0,
                          "clientLandedChunkCount": len(authority)}
        probe["clientCache"]["loadedChunks"] = len(authority)
        analysis = MobileSessionTraceTest()._analyze(10, probe, vd1=20, vd2=vd)
        self.assertIn(("TRACE_INJECTED_NOT_READY", 2),
                      {(item["code"], item.get("round")) for item in analysis["warnings"]})

    def test_stationary_cache_only_r2_ovd_belt_is_not_halo(self):
        from scripts.smoke.analyzer import _in_authority_shape

        vd = 10
        authority = {(x, z) for x in range(-12, 13) for z in range(-12, 13)
                     if _in_authority_shape(0, 0, vd, x, z, "1.21.1")}
        for gap_pos, expected_bucket in (((14, 0), "failures"),
                                         ((17, 0), "skipped")):
            with self.subTest(gap_pos=gap_pos):
                probe = probe_for(authority, authority | {gap_pos}, (0, 0))
                probe["chunkTrace"]["networkReceived"]["positions"] = []
                probe["stats"] = {"fullChunkRequestCount": 0,
                                  "clientLandedChunkCount": len(authority)}
                probe["clientCache"]["loadedChunks"] = len(authority)
                analysis = MobileSessionTraceTest()._analyze(0, probe, vd1=20, vd2=vd)
                self.assertIn(("TRACE_INJECTED_NOT_READY", 2),
                              {(item["code"], item.get("round"))
                               for item in analysis[expected_bucket]})

    def test_halo_check_uses_full_probe_not_truncated_report(self):
        from scripts.smoke.analyzer import _halo_only_gap, _in_authority_shape

        vd = 20
        authority = {(x, z) for x in range(-22, 23) for z in range(-22, 23)
                     if _in_authority_shape(0, 0, vd, x, z, "1.21.1")}
        halo = {(x, z) for x in range(-23, 24) for z in range(-23, 24)
                if (x, z) not in authority and max(abs(x), abs(z)) <= 23}
        # 报告前 64 个全是窗外；第 65 个窗内残差不能被截断隐藏。
        gap = halo | {(0, 0)}
        probe = probe_for(authority, authority | gap, (0, 0))
        report = {"injectedNotReady": {"count": len(gap),
                                        "positions": [list(p) for p in sorted(halo)[:64]],
                                        "truncated": True},
                  "expectedNotPresent": {"count": len(gap),
                                         "positions": [list(p) for p in sorted(halo)[:64]],
                                         "truncated": True}}
        # probe_for 会把当前形状全部标 ready；明确把中心移出 ready/applied/actual。
        probe["chunkTrace"]["shadowReady"]["positions"] = [
            p for p in probe["chunkTrace"]["shadowReady"]["positions"] if p != [0, 0]]
        probe["chunkTrace"]["clientApplied"]["positions"] = [
            p for p in probe["chunkTrace"]["clientApplied"]["positions"] if p != [0, 0]]
        probe["clientCache"]["actualPresent"]["positions"] = [
            p for p in probe["clientCache"]["actualPresent"]["positions"] if p != [0, 0]]
        self.assertFalse(_halo_only_gap(probe, report, vd, "1.21.1"))


def _old_dilated(x: int, z: int, vd: int) -> bool:
    """旧公式（≤1.21.3）的切比雪夫膨胀 d=1：i=max(0,|dx|-2) + chebyshev 折算（改前实现）。"""
    i = max(0, abs(x) - 2)
    j = max(0, abs(z) - 2)
    k = max(0, max(i, j) - 1)
    l = min(i, j)
    return l * l + k * k < vd * vd


def probe_for(authority: set[tuple[int, int]], compute: set[tuple[int, int]],
              center: tuple[int, int]) -> dict:
    positions_authority = [list(p) for p in sorted(authority)]
    positions_compute = [list(p) for p in sorted(compute)]
    return {"playerPos": [center[0] * 16 + 0.5, 64.0, center[1] * 16 + 0.5],
            "chunkTrace": {"networkReceived": {"positions": positions_compute},
                           "shadowInjected": {"positions": positions_compute},
                           "shadowReady": {"positions": positions_authority},
                           "clientApplied": {"positions": positions_authority},
                           "meshCompiled": {"positions": positions_authority}},
            "clientCache": {"actualPresent": {"positions": positions_authority}}}


class RuntimeEvidenceTest(unittest.TestCase):
    def test_flight_requires_unload_return_and_current_presence(self):
        from scripts.smoke.analyzer import _check_flight_probe
        probe = {"flight": {"anchorUnloaded": True, "anchorReturned": True,
                            "anchorPresent": True, "distanceChunks": 2},
                 "counters": {"clientDarkRegressionChunks": 0}}
        self.assertEqual([], _check_flight_probe(probe))
        probe["flight"]["anchorUnloaded"] = False
        self.assertIn("FLIGHT_ANCHOR_NOT_UNLOADED", {f["code"] for f in _check_flight_probe(probe)})
        probe["flight"]["anchorUnloaded"] = True
        probe["flight"]["distanceChunks"] = 8
        self.assertIn("FLIGHT_RETURN_TOO_FAR", {f["code"] for f in _check_flight_probe(probe)})

    def test_flight_missing_evidence_and_nonzero_dark_count_fail(self):
        from scripts.smoke.analyzer import _check_flight_probe
        self.assertIn("FLIGHT_ANCHOR_NOT_RETURNED", {f["code"] for f in _check_flight_probe({})})
        probe = {"flight": {"anchorUnloaded": True, "anchorReturned": True,
                            "anchorPresent": True, "distanceChunks": 0},
                 "counters": {"clientDarkRegressionChunks": 1}}
        self.assertIn("FLIGHT_DARK_REGRESSION", {f["code"] for f in _check_flight_probe(probe)})

    def test_dimension_disk_requires_snapshot_from_this_session(self):
        from scripts.smoke.analyzer import _check_dimension_disk
        result = {"SessionId": "s", "PostExitDisk": {
            "captured": True, "sessionId": "s",
            "dimensions": {d: {"regionFileCount": 1} for d in ("overworld", "nether", "end")}}}
        self.assertEqual([], _check_dimension_disk(result))
        result["PostExitDisk"]["dimensions"]["end"]["regionFileCount"] = 0
        self.assertEqual("DIMENSION_REGION_MISSING", _check_dimension_disk(result)[0]["code"])
        result["PostExitDisk"]["sessionId"] = "another-session"
        self.assertEqual("DIMENSION_DISK_SNAPSHOT_MISSING", _check_dimension_disk(result)[0]["code"])
        self.assertEqual("DIMENSION_DISK_SNAPSHOT_MISSING", _check_dimension_disk({})[0]["code"])

    def test_dimension_checks_end_and_return_probes(self):
        from scripts.smoke.analyzer import analyze_result
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs" / "s.log").write_text(
                "HassiumSmokeTest:PASS\nHassium: play init (caps=1)\n"
                "Hassium: Aggregation enabled for player\n")
            dims = ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "minecraft:overworld"]
            probes = {f"Round{n}": {"joined": True, "dimension": dim,
                       "clientCache": {"loadedChunks": 100},
                       "chunkTrace": {"clientApplied": {"count": 100, "positions": []}}}
                      for n, dim in enumerate(dims, 1)}
            result = {"SessionId": "s", "Scenario": "dimension", "Probe": probes,
                      "PostExitDisk": {"captured": True, "sessionId": "s", "dimensions": {
                          d: {"regionFileCount": 1} for d in ("overworld", "nether", "end")}}}
            self.assertTrue(analyze_result(result, root)["pass"])
            probes["Round4"]["dimension"] = "minecraft:the_end"
            self.assertIn("DIMENSION_UNEXPECTED", {f["code"] for f in analyze_result(result, root)["failures"]})
            del probes["Round3"]
            self.assertIn("PROBE_MISSING", {f["code"] for f in analyze_result(result, root)["failures"]})


if __name__ == "__main__":
    unittest.main()
