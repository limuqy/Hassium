"""Analyze same-connection 3x3 sleep using the existing successful-write TCP meter."""
import argparse
import importlib.util
import json
import re
from pathlib import Path

spec = importlib.util.spec_from_file_location('sleep_traffic', Path(__file__).with_name('analyze-sleep-traffic.py'))
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)


def analyze(directory):
    report = legacy.analyze(directory)
    clients = list(directory.glob('client_*.log'))
    if len(clients) != 1:
        raise ValueError('Expected exactly one client log')
    client = clients[0].read_text(encoding='utf-8', errors='replace')
    server = next(directory.glob('server_*.log')).read_text(encoding='utf-8', errors='replace')
    pull_kinds = {}
    phase = None
    for line in client.splitlines():
        marker = re.search(r"sending client command '/tag @s add sleep_traffic_([a-z_]+)'", line)
        if marker:
            phase = marker.group(1)
        result = re.search(r'\[SHADOW_PULL\] result .*kind=([A-Z_]+)', line)
        if result and phase and phase.endswith('_recovery'):
            counts = pull_kinds.setdefault(phase, {})
            kind = result.group(1)
            counts[kind] = counts.get(kind, 0) + 1
    recoveries = re.findall(r'SLEEP traffic recovery sameConnection=true visiblePending=0 readyMs=(\d+) observationMs=(\d+)', client)
    if len(recoveries) != 3:
        report['qualityProblems'].append('Expected three completed same-connection, full visible-window recoveries')
    resumes = re.findall(r'\[SLEEP\] resumed inPlace[^\n]*leases=0', server)
    if len(resumes) != 3:
        report['qualityProblems'].append('Expected three server resumes without reconnect leases')
    if '[SLEEP_PROBE] acquired' in server:
        report['qualityProblems'].append('Unexpected reconnect lease acquisition')
    for i, mode in enumerate(('idle', 'hold_attack', 'periodic_attack')):
        cycle = report['cycles'][mode]
        normal, asleep = cycle['normalBytesPerSecond'], cycle['sleepBytesPerSecond']
        cycle['reductionPercent'] = 100 * (1 - asleep / normal) if normal else None
        cycle['recoveryBytesPerSecond'] = cycle['recoveryBytes'] / cycle['recoverySeconds']
        cycle['recoveryPullResults'] = pull_kinds.get(mode + '_recovery', {})
        if i < len(recoveries):
            ready, observed = map(int, recoveries[i])
            cycle.update(visibleWindowReadyMs=ready, recoveryObservationMs=observed)
            if observed < 30_000:
                report['qualityProblems'].append(f'{mode}: recovery observation shorter than 30 seconds')
    report.update(capability='SLEEP_WINDOW', visibleColumns=9, lightColumns=25,
                  fixture='Same generated 16-lane vanilla factory layout on separate fresh worlds; VD=10; fixed dictionary',
                  sampling='One adjacent 30-second normal/sleep pair per input mode; recovery >=30 seconds and all paused visible columns ready; no injected offline wait',
                  recoveryInput='No attack actions during recovery observation; normal and sleep windows use matching scripted native interactions',
                  serverResumeCount=len(resumes))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report = analyze(args.directory)
    (args.directory / 'sleep-window-traffic.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('session', 'harness', 'qualityProblems', 'cycles')}, indent=2))
    raise SystemExit(1 if report['qualityProblems'] else 0)
