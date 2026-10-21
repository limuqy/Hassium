"""Reuse the reviewed native factory setup, with bounded normal/sleep/recovery windows."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = (root / 'common/src/main/resources/hassium/smoke/scenario/trafficprofile.scenario').read_text(encoding='utf-8').splitlines()
start = source.index('command text="setblock 23 142 12 sticky_piston[facing=up]"')
end = source.index('command text="tag @s add traffic_factory_r1"')
lines = source[:source.index('wait ms=60000')] + source[start:end]
lines += ['command text="setblock 30 139 5 bedrock"', 'command text="tp @s 30.5 140 5.5 0 90"', 'wait ms=10000', 'dump label=ROUND1 round=1 world=true']
previous = None

def phase(name):
    global previous
    if previous:
        lines.append(f'command text="tag @s remove sleep_traffic_{previous}"')
    lines.append(f'command text="tag @s add sleep_traffic_{name}"')
    # Chat command execution can complete after a following custom control packet.
    # Let the authoritative tag land before ENTER/WAKE can change or close the connection.
    lines.append('wait ms=250')
    previous = name

for mode in ('idle', 'hold_attack', 'periodic_attack'):
    phase(f'{mode}_normal')
    lines.append(f'input mode={mode} ms=30000')
    phase(f'{mode}_entry')
    lines.append('sleep action=enter threshold=6')
    phase(f'{mode}_sleep')
    lines.append(f'input mode={mode} ms=30000')
    phase(f'{mode}_recovery')
    lines += ['sleep action=wake offlineMs=5000', 'wait ms=10000']
phase('done')
lines += ['wait ms=2000', 'dump label=ROUND2 round=2 world=true', 'exit rounds=2']
(root / 'common/src/main/resources/hassium/smoke/scenario/sleeptraffic.scenario').write_text('\n'.join(lines)+'\n', encoding='utf-8')

real = ['# Native 1.21.1 ZIP copy only; never downgrade to 1.20.1.',
        'join label=SLEEP_FACTORY_JOIN since=start timeoutMs=${joinTimeoutMs} requirePositiveY=false',
        'wait ms=3000', 'command text="gamerule sendCommandFeedback false"',
        'command text="gamemode spectator"', 'command text="tp @s 72.5 78 79.5 0 10"',
        'command text="gamemode creative"', 'input mode=hover ms=45000',
        '# One unbreakable input target in the disposable test copy, below the AFK position.',
        'command text="setblock 72 77 79 bedrock"', 'command text="gamemode survival"',
        'command text="effect give @s resistance 1200 4 true"',
        'command text="tp @s 72.5 78 79.5 0 90"', 'command text="tick rate 20"',
        'command text="tick unfreeze"', 'wait ms=5000', 'dump label=ROUND1 round=1 world=true']
sequence = lines[lines.index('command text="tag @s add sleep_traffic_idle_normal"'):]
real += [line+' targetX=72 targetY=77 targetZ=79' if line.startswith('input ') else line for line in sequence]
(root / 'common/src/main/resources/hassium/smoke/scenario/sleepfactory.scenario').write_text('\n'.join(real)+'\n', encoding='utf-8')
