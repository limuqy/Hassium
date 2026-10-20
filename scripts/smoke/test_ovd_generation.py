import unittest

from scripts.smoke.analyzer import _check_ovd_generation_probe, _check_ovd_light_probe


class OvdGenerationProbeTest(unittest.TestCase):
    def probe(self, generated=((8, 0),), applied=None, resident=None):
        window = [(x, z) for x in range(-8, 9) for z in range(-8, 9)]
        if applied is None:
            applied = window
        if resident is None:
            resident = window
        return {
            "playerPos": [0, 100, 0],
            "chunkTrace": {
                "serverViewDistance": 4,
                "effectiveClientVD": 8,
                "ovdGenerated": {"positions": list(generated)},
                "clientApplied": {"positions": list(applied)},
            },
            "clientCache": {"actualPresent": {"positions": list(resident)}},
        }

    def codes(self, probe, version="26.3"):
        return {failure["code"] for failure in _check_ovd_generation_probe(probe, 1, version)}

    def test_fresh_outer_generation_must_be_resident(self):
        for version in ("1.20.1", "1.21.1", "1.21.11", "26.3"):
            with self.subTest(version=version):
                self.assertEqual(set(), self.codes(self.probe(), version))
        self.assertIn("OVD_GENERATION_NOT_PRESENT", self.codes(self.probe(resident=())))
        self.assertIn("OVD_GENERATION_NOT_PRESENT", self.codes(self.probe(applied=())))

    def test_authority_generation_and_cached_replay_cannot_pass(self):
        self.assertIn("OVD_GENERATION_MISSING", self.codes(self.probe(generated=())))
        self.assertIn("OVD_GENERATION_MISSING", self.codes(self.probe(generated=((0, 0),))))
        self.assertIn("OVD_GENERATION_MISSING", self.codes(self.probe(generated=((6, 0),))))

    def test_outside_client_window_cannot_pass(self):
        self.assertIn("OVD_GENERATION_MISSING", self.codes(self.probe(generated=((9, 0),))))

    def test_one_generated_column_cannot_hide_missing_outer_columns(self):
        self.assertIn("OVD_GENERATION_INCOMPLETE", self.codes(self.probe(resident=((8, 0),))))

    def test_received_outer_content_is_not_local_generation(self):
        probe = self.probe()
        probe["chunkTrace"]["networkReceived"] = {"positions": [[8, 0]]}
        self.assertIn("OVD_GENERATION_REMOTE_CONTENT", self.codes(probe))

    def test_requires_actual_dual_window(self):
        probe = self.probe()
        probe["chunkTrace"]["effectiveClientVD"] = 4
        self.assertEqual({"OVD_GENERATION_WINDOW"}, self.codes(probe))

    def test_negative_player_coordinates_use_floor(self):
        window = [(x, z) for x in range(-9, 8) for z in range(-9, 8)]
        probe = self.probe(generated=((-9, -1),), applied=window, resident=window)
        probe["playerPos"] = [-0.5, 100, -0.5]
        self.assertEqual(set(), self.codes(probe))


class OvdLightProbeTest(unittest.TestCase):
    def test_terrain_residency_cannot_hide_missing_server_buildings(self):
        for foot in (None, "minecraft:air", "minecraft:stone"):
            with self.subTest(foot=foot):
                probe = {"contentCheck": {"footBlock": foot},
                         "chunkTrace": {"ovdGenerated": {"positions": [[8, 0]]}}}
                self.assertIn("OVD_LIGHT_FIXTURE_MISSING",
                              {f["code"] for f in _check_ovd_light_probe(probe, 1)})

    def test_partial_cave_or_roof_cannot_pass_a_correct_standing_block(self):
        for fixture in ({}, {"sampled": 0, "mismatches": 0}, {"sampled": 80, "mismatches": 1}):
            with self.subTest(fixture=fixture):
                probe = {"contentCheck": {"footBlock": "minecraft:quartz_block"},
                         "lightFixture": fixture}
                self.assertIn("OVD_LIGHT_FIXTURE_CONTENT",
                              {f["code"] for f in _check_ovd_light_probe(probe, 2)})

    def test_stable_snapshot_cannot_hide_bad_moving_light_or_floor(self):
        for bad in ("movementFloorMissing", "movementSkyLeaks", "movementDarkSources"):
            fixture = {"sampled": 80, "mismatches": 0, "movementSamples": 600,
                       "movementFloorMissing": 0, "movementSkyLeaks": 0, "movementDarkSources": 0}
            fixture[bad] = 1
            probe = {"contentCheck": {"footBlock": "minecraft:quartz_block"}, "lightFixture": fixture}
            self.assertIn("OVD_LIGHT_MOVEMENT_FIXTURE",
                          {f["code"] for f in _check_ovd_light_probe(probe, 2)})

    def test_moving_light_measurements_cannot_be_omitted(self):
        probe = {"contentCheck": {"footBlock": "minecraft:quartz_block"},
                 "lightFixture": {"sampled": 80, "mismatches": 0}}
        self.assertIn("OVD_LIGHT_MOVEMENT_FIXTURE",
                      {f["code"] for f in _check_ovd_light_probe(probe, 2)})

    def test_fixture_and_actual_ovd_generation_are_required(self):
        probe = {"contentCheck": {"footBlock": "minecraft:quartz_block"},
                 "lightFixture": {"sampled": 80, "mismatches": 0, "movementSamples": 600,
                                  "movementFloorMissing": 0, "movementSkyLeaks": 0, "movementDarkSources": 0}}
        self.assertEqual(["OVD_GENERATION_MISSING"],
                         [f["code"] for f in _check_ovd_light_probe(probe, 1)])
        self.assertEqual([], _check_ovd_light_probe(probe, 2))
        probe["chunkTrace"] = {"ovdGenerated": {"positions": [[8, 0]]}}
        self.assertEqual([], _check_ovd_light_probe(probe, 1))


if __name__ == "__main__":
    unittest.main()
