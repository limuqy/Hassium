"""Check the real R1/R2 BE cache fixture, including read-stage evidence and hit classification."""
import argparse
import json
import re
from pathlib import Path


def analyze(directory):
    client_path = next(directory.glob('client_*.log'))
    server_path = next(directory.glob('server_*.log'))
    client = client_path.read_text(encoding='utf-8', errors='replace').splitlines()
    server = server_path.read_text(encoding='utf-8', errors='replace').splitlines()
    result = json.loads(next(directory.glob('result_*.json')).read_text(encoding='utf-8-sig'))
    problems = []
    checks = [line for line in client if 'CACHE_NBT client ' in line]
    for round_ in (1, 2, 3):
        for case in range(8):
            matches = [line for line in checks if f'client R{round_} case={case} ' in line]
            if len(matches) != 1 or 'correct=true' not in matches[0]:
                problems.append(f'R{round_} case {case} missing/failed')
    loaded = [line for line in client if 'CACHE_NBT loaded ' in line]
    for case in range(8):
        if not any(f'text=R1_{case} waxed=false' in line for line in loaded):
            problems.append(f'R1_{case} not restored from disk before authority refresh')
    if not any('offline controlChanged=true nbtOnlyUnchanged=true' in line for line in server):
        problems.append('Missing offline block hash isolation')
    pulls = [line for line in client if '[SHADOW_PULL] result ' in line
             and ('pos=(0, 0)' in line or 'pos=(2, 0)' in line)]
    near = [line for line in pulls if 'pos=(0, 0)' in line]
    far = [line for line in pulls if 'pos=(2, 0)' in line]
    if not any('kind=UNCHANGED' in line for line in near):
        problems.append('NBT-only column did not exercise UNCHANGED')
    if not near or 'kind=FULL' not in near[-1] or not any('force_full sameBlockHash=true' in line for line in client):
        problems.append('Missing same-block-hash authoritative FULL recovery')
    if not far or not re.search(r'kind=(DELTA|FULL)', far[-1]):
        problems.append('Block-change control did not refresh terrain')
    failures = [line for line in client if 'HassiumSmokeTest:FAIL' in line or 'NBT snapshot failed' in line]
    if failures or result['Result'] != 'PASS':
        problems.append('Harness or NBT application failed')
    return dict(session=directory.name, harness=result['Result'], qualityProblems=problems,
                clientChecks=checks, diskRestores=loaded,
                storedNbt=[line for line in client if 'CACHE_NBT disk ' in line],
                serverMutations=[line for line in server if 'CACHE_NBT' in line],
                pullResults=pulls,
                nbtSnapshots=[line for line in client if '[SHADOW_PULL_NBT]' in line and 'entries=0' not in line],
                failures=failures)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report = analyze(args.directory)
    (args.directory / 'cache-nbt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('session', 'harness', 'qualityProblems')}, indent=2))
    raise SystemExit(bool(report['qualityProblems']))
