"""Read-stage acceptance must not mistake a first-round or late restore for reconnect evidence."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("modpack_dedicated", Path(__file__).resolve().parents[1] / "modpack-dedicated.py")
dedicated = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dedicated)

ASSEMBLY = "[SHADOW-CACHE] assembly=connection"
PROBE1 = "HassiumSmokeTest:PROBE_WRITTEN ROUND1 -> probe/round1.json"
PROBE2 = "HassiumSmokeTest:PROBE_WRITTEN ROUND2 -> probe/round2.json"
TREASURE = "[DIAGNOSTIC-CACHE] restored treasure2:proximity_mobset_spawner BlockPos{x=140, y=46, z=31}"
SIGN = "[DIAGNOSTIC-CACHE] restored minecraft:sign BlockPos{x=1, y=2, z=3}"


class CacheRestoreEvidenceTest(unittest.TestCase):
    def read(self, *lines):
        return dedicated.cache_restore_read_stage("\n".join(lines))

    def test_only_successful_returns_in_the_second_owner_window_count(self):
        report = self.read(TREASURE, ASSEMBLY, SIGN, PROBE1, TREASURE, ASSEMBLY, TREASURE, SIGN, PROBE2, TREASURE)
        self.assertTrue(report["complete"])
        self.assertEqual(1, report["rounds"][0]["total"])
        self.assertEqual(2, report["rounds"][1]["total"])
        self.assertEqual(["BlockPos{x=140, y=46, z=31}"], report["rounds"][1]["treasureSpawnerPositions"])

    def test_first_round_and_late_treasure_cannot_pass_reconnect(self):
        report = self.read(ASSEMBLY, TREASURE, PROBE1, ASSEMBLY, SIGN, PROBE2, TREASURE)
        self.assertEqual([], report["rounds"][1]["treasureSpawnerPositions"])

    def test_missing_duplicate_or_misordered_owner_boundaries_fail_closed(self):
        for lines in ((ASSEMBLY, TREASURE, PROBE2),
                      (ASSEMBLY, PROBE1, ASSEMBLY, TREASURE),
                      (ASSEMBLY, ASSEMBLY, PROBE1, TREASURE, PROBE2),
                      (ASSEMBLY, PROBE1, ASSEMBLY, TREASURE, PROBE2, ASSEMBLY)):
            with self.subTest(lines=lines):
                self.assertFalse(self.read(*lines)["complete"])


if __name__ == "__main__":
    unittest.main()
