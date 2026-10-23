"""Regression for the UUID mismatch observed by FTB Teams in the real offline server."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("modpack_base", Path(__file__).resolve().parents[1] / "modpack-compat.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


class ModpackIdentityTest(unittest.TestCase):
    def test_matches_the_uuid_reported_by_the_offline_minecraft_server(self):
        self.assertEqual("7043208f6fb836078935eb556f107d3a", base.offline_player_uuid("HassiumCompat"))


if __name__ == "__main__":
    unittest.main()
