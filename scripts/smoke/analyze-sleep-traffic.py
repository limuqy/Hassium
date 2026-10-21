"""Full TCP payload (all protocols, successful writes), not pre-compression byte sums."""
import argparse
import json
from pathlib import Path

def analyze(directory):
    logs = list(directory.glob('server_*.log'))
    if len(logs) != 1:
        raise ValueError('Expected one server log')
    boundaries = []
    frozen_phases = set()
    for line in logs[0].read_text(encoding='utf-8', errors='replace').splitlines():
        if '[SLEEP_TRAFFIC] ' not in line:
            continue
        row = dict(item.split('=',1) for item in line.split('[SLEEP_TRAFFIC] ',1)[1].split())
        if row.get('frozen') == 'true':
            frozen_phases.add(row['phase'].removeprefix('sleep_traffic_'))
        if row['phase'] != row['next']:
            boundaries.append(dict(phase=row['next'].removeprefix('sleep_traffic_'), nanos=int(row['nanoTime']), bytes=int(row['tcpBytes']),
                millis=int(row['timestampMs']) if 'timestampMs' in row else None,
                worldTicks=int(row['worldTicks']) if 'worldTicks' in row else None,
                output=int(row['outputItems']) if row.get('outputItems','').isdigit() else None))
    windows = {}
    problems = []
    for a, b in zip(boundaries, boundaries[1:]):
        seconds = (b['nanos']-a['nanos'])/1e9
        count = b['bytes']-a['bytes']
        windows[a['phase']] = dict(seconds=seconds, tcpPayloadBytes=count, bytesPerSecond=count/seconds)
        if a['millis'] is not None:
            windows[a['phase']].update(startMs=a['millis'], endMs=b['millis'], nativeBlockAcks=0)
        if a['output'] is not None and b['output'] is not None:
            windows[a['phase']]['outputItems']=b['output']-a['output']
        if a['worldTicks'] is not None and b['worldTicks'] is not None:
            windows[a['phase']]['actualWorldTPS']=(b['worldTicks']-a['worldTicks'])/seconds
    for path in directory.glob('agg-*.jsonl'):
        for line in path.read_text(encoding='utf-8').splitlines():
            packet=json.loads(line)
            if packet.get('src')=='native_v3' and 'block_changed_ack' in packet.get('type',''):
                for window in windows.values():
                    if window.get('startMs',float('inf')) <= packet['ts'] < window.get('endMs',0):
                        window['nativeBlockAcks'] += 1
    cycles = {}
    for mode in ('idle', 'hold_attack', 'periodic_attack'):
        normal, entry, asleep, wake = (windows[f'{mode}_{suffix}'] for suffix in ('normal', 'entry', 'sleep', 'recovery'))
        for label, window in (('normal', normal), ('sleep', asleep)):
            if not 29 <= window['seconds'] <= 33:
                problems.append(f'{mode}_{label}: phase marker did not bound the 30-second input window')
            if window.get('outputItems', 1) <= 0:
                problems.append(f'{mode}_{label}: no collected factory output')
            if window.get('actualWorldTPS',20)<19 or f'{mode}_{label}' in frozen_phases:
                problems.append(f'{mode}_{label}: frozen or slow world simulation')
        saving = normal['bytesPerSecond']-asleep['bytesPerSecond']
        overhead = entry['tcpPayloadBytes']+wake['tcpPayloadBytes']
        cycles[mode] = dict(normalBytesPerSecond=normal['bytesPerSecond'], sleepBytesPerSecond=asleep['bytesPerSecond'],
            entryBytes=entry['tcpPayloadBytes'], recoveryBytes=wake['tcpPayloadBytes'], recoverySeconds=wake['seconds'],
            measuredCycleBytes=sum(w['tcpPayloadBytes'] for w in (normal, entry, asleep, wake)),
            conservativeBreakEvenSeconds=overhead/saving if saving > 0 else None,
            incrementalBreakEvenSeconds=max(0, overhead-normal['bytesPerSecond']*(entry['seconds']+wake['seconds']))/saving if saving > 0 else None)
    result_files = list(directory.glob('result_*.json'))
    result = json.loads(result_files[0].read_text(encoding='utf-8-sig')) if len(result_files)==1 else {}
    if result.get('Result') != 'PASS':
        problems.append('Smoke harness did not pass')
    return dict(session=directory.name, harness=result.get('Result'), qualityProblems=problems,
        measurement='Successful all-protocol TCP application payload writes, including Minecraft framing; excludes TCP/IP headers, retransmissions and client upload.',
        windows=windows, cycles=cycles)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report = analyze(args.directory)
    target = args.directory/'sleep-traffic.json'
    target.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, indent=2))
