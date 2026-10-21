import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('sleep_traffic', Path(__file__).with_name('analyze-sleep-traffic.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SleepTrafficTest(unittest.TestCase):
    def fixture(self, directory, delayed=False):
        rows=[]
        now=count=output=0
        previous='setup'
        def boundary(phase, seconds, bytes_, items=0):
            nonlocal now, count, previous, output
            now += int(seconds*1e9)
            count += bytes_
            output += items
            rows.append(f'[SLEEP_TRAFFIC] phase={previous} next={phase} nanoTime={now} tcpBytes={count} players=1 outputItems={output}')
            previous=phase
        for mode in ('idle','hold_attack','periodic_attack'):
            boundary(f'sleep_traffic_{mode}_normal',1,0)
            boundary(f'sleep_traffic_{mode}_entry',30,3000,30)
            boundary(f'sleep_traffic_{mode}_sleep',1,10)
            boundary(f'sleep_traffic_{mode}_recovery',38 if delayed else 30,300,30)
            boundary('sleep_traffic_between',20,1800,20)
        (directory/'server_test.log').write_text('\n'.join(rows), encoding='utf-8')
        (directory/'result_test.json').write_text(json.dumps({'Result':'PASS'}), encoding='utf-8')

    def test_recovery_cost_includes_complete_window_and_cannot_be_called_free(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            self.fixture(directory)
            result=module.analyze(directory)
            cycle=result['cycles']['idle']
            self.assertEqual(cycle['recoveryBytes'],1800)
            self.assertAlmostEqual(cycle['conservativeBreakEvenSeconds'],1810/90)
            self.assertEqual(cycle['incrementalBreakEvenSeconds'],0)
            self.assertFalse(result['qualityProblems'])

    def test_delayed_sleep_marker_cannot_silently_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            self.fixture(directory, delayed=True)
            self.assertTrue(any('phase marker' in p for p in module.analyze(directory)['qualityProblems']))

    def test_frozen_world_is_rejected_even_if_smoke_harness_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            self.fixture(directory)
            with (directory/'server_test.log').open('a', encoding='utf-8') as stream:
                stream.write('\n[SLEEP_TRAFFIC] phase=sleep_traffic_idle_sleep next=sleep_traffic_idle_sleep nanoTime=1 tcpBytes=1 players=1 frozen=true')
            self.assertTrue(any('world simulation' in p for p in module.analyze(directory)['qualityProblems']))


if __name__ == '__main__':
    unittest.main()
