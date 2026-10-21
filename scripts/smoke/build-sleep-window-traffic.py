"""Preserve the legacy reconnect benchmark; derive the window benchmark from its factory."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
scenarios = root / 'common/src/main/resources/hassium/smoke/scenario'
source = (scenarios / 'sleeptraffic.scenario').read_text(encoding='utf-8')
lines = []
phase = None
for line in source.splitlines():
    if line.startswith('command text="tag @s add sleep_traffic_'):
        phase = line.split('sleep_traffic_', 1)[1].rstrip('"')
    if line == 'sleep action=wake offlineMs=5000':
        line = 'sleep action=traffic_wake'
    elif line == 'wait ms=10000' and phase and phase.endswith('_recovery'):
        continue  # traffic_wake includes at least 30 s and waits for all visible columns.
    elif line.startswith('input ') and phase:
        line += ' sleepState=' + ('SLEEPING' if phase.endswith('_sleep') else 'IDLE')
    lines.append(line)
(scenarios / 'sleepwindowtraffic.scenario').write_text('\n'.join(lines)+'\n', encoding='utf-8')
profiles = root / 'scripts/smoke/profiles'
(profiles / 'sleepwindowtraffic.profile.properties').write_bytes((profiles / 'sleeptraffic.profile.properties').read_bytes())
