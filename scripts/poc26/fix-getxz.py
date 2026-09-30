import io, re
from collections import defaultdict
errs = []
for ln in io.open('build/errs.json.txt', encoding='utf-8'):
    parts = ln.rstrip('\n').split('|', 3)
    if len(parts) != 4: continue
    fp, no, msg, det = parts[0].lstrip('/'), int(parts[1]), parts[2], parts[3]
    if '找不到符号' not in msg: continue
    if '位置: 类 ChunkPos' not in det: continue
    flds = set()
    if '方法 getX()' in det: flds.add('getX')
    if '方法 getZ()' in det: flds.add('getZ')
    if flds: errs.append((fp, no, flds))
byfile = defaultdict(list)
for fp, no, flds in errs: byfile[fp].append((no, flds))
changed = 0
for fp, items in byfile.items():
    lines = io.open(fp, encoding='utf-8').read().split('\n')
    for no, flds in sorted(items, key=lambda t: -t[0]):
        s = lines[no-1]; orig = s
        for f in flds:
            s = s.replace('.' + f + '()', '.x()' if f == 'getX' else '.z()')
        if s != orig: lines[no-1] = s; changed += 1
    io.open(fp, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
print('changed', changed)
