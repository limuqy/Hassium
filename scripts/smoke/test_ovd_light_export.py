import unittest

from scripts.smoke.export_ovd_light import limits, positions, samples


class OvdLightExportTest(unittest.TestCase):
    def test_positions_cover_the_full_route_and_use_the_same_light_limits(self):
        route = positions()
        self.assertEqual(753, len(route))
        self.assertEqual((-56, 696), (route[0]["z"], route[-1]["z"]))
        self.assertTrue(all(row["y"] == 299 and row["floor_y"] == 297 for row in route))
        self.assertEqual(6, limits(138)["sky_max"])
        self.assertEqual(0, limits(448)["sky_max"])
        self.assertEqual(10, limits(448)["block_min"])
        self.assertIsNone(limits(200)["sky_max"])

    def test_back_traversal_and_shadow_only_leak_are_preserved(self):
        line = ("[14:00:00] [Render thread/INFO] [Hassium/SmokeTest/]: [OVD_LIGHT_SAMPLE] "
                "z=448 clientSky=0 shadowSky=14 clientBlock=10 shadowBlock=10 "
                "floor=Block{minecraft:quartz_block} ceiling=Block{minecraft:stone} "
                "shadowCeiling=Block{minecraft:stone} correct=false")
        measured = samples(line + "\nWAIT_JOIN_2 player entered world\n" + line)
        self.assertEqual([1, 2], [row["round"] for row in measured])
        self.assertEqual(["OUT", "BACK"], [row["direction"] for row in measured])
        self.assertFalse(measured[1]["client_sky_leak"])
        self.assertTrue(measured[1]["shadow_sky_leak"])
        self.assertFalse(measured[1]["client_dark_source"])
        self.assertFalse(measured[1]["floor_missing"])

    def test_missing_floor_and_both_dark_sources_fail(self):
        line = ("[14:00:00] [Render thread/INFO] [Hassium/SmokeTest/]: [OVD_LIGHT_SAMPLE] "
                "z=320 clientSky=0 shadowSky=0 clientBlock=6 shadowBlock=0 "
                "floor=Block{minecraft:air} ceiling=Block{minecraft:stone} "
                "shadowCeiling=Block{minecraft:stone} correct=true")
        row = samples(line)[0]
        self.assertTrue(row["floor_missing"])
        self.assertTrue(row["client_dark_source"])
        self.assertTrue(row["shadow_dark_source"])
