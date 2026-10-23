"""Real Windows launch checks: topology, inheritance and restoration on failure."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from smoke.cpu_isolation import _kernel, _mask, cpu_plan, popen_pinned, verify_affinity


@unittest.skipUnless(os.name == "nt", "Windows process affinity")
class CpuIsolationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = cpu_plan()

    def current_mask(self):
        native = _kernel()
        return _mask(native, native.GetCurrentProcess())

    def test_off_and_unavailable_are_explicit(self):
        self.assertFalse(cpu_plan("Off")["enabled"])
        missing = cpu_plan("Auto", 63, 63)
        self.assertFalse(missing["enabled"])
        self.assertTrue(missing["reason"])
        with self.assertRaises(subprocess.CalledProcessError):
            cpu_plan("Required", 0, 0)

    def test_topology_and_swapped_domains(self):
        if not self.plan["enabled"]:
            self.skipTest(self.plan["reason"])
        left, right = self.plan["server"], self.plan["client"]
        self.assertEqual(int(left["mask"], 16) & int(right["mask"], 16), 0)
        for domain in (left, right):
            self.assertEqual(sum(1 << cpu for cpu in domain["logicalProcessors"]), int(domain["mask"], 16))
        swapped = cpu_plan("Required", 1, 0)
        self.assertEqual(swapped["server"], right)
        self.assertEqual(swapped["client"], left)

    def test_child_and_grandchild_inherit_both_domains(self):
        if not self.plan["enabled"]:
            self.skipTest(self.plan["reason"])
        probe = (f"import sys; sys.path.insert(0, {str(Path(__file__).resolve().parents[1])!r}); "
                 "from smoke.cpu_isolation import _kernel, _mask; n=_kernel(); print(_mask(n,n.GetCurrentProcess()))")
        nested = ("import subprocess,sys; " + probe + "; "
                  f"print(subprocess.check_output([sys.executable,'-c',{probe!r}],text=True).strip())")
        original = self.current_mask()
        for side in ("server", "client"):
            child = popen_pinned([sys.executable, "-c", nested], self.plan[side],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(self.current_mask(), original)
            verify_affinity(child, self.plan[side])
            out, err = child.communicate(timeout=20)
            self.assertEqual(child.returncode, 0, err.decode())
            self.assertEqual([int(line) for line in out.splitlines()], [int(self.plan[side]["mask"], 16)] * 2)

    def test_failed_launch_restores_parent(self):
        if not self.plan["enabled"]:
            self.skipTest(self.plan["reason"])
        original = self.current_mask()
        with self.assertRaises(FileNotFoundError):
            popen_pinned([str(Path(__file__).with_name("does-not-exist.exe"))], self.plan["server"])
        self.assertEqual(self.current_mask(), original)


if __name__ == "__main__":
    unittest.main()
