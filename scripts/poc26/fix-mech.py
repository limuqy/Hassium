import re, io, sys
from collections import defaultdict
errs = []
for ln in io.open(sys.argv[1], encoding='utf-8'):
    parts = ln.rstrip('\n').split('|', 3)
    if len(parts) == 4:
        errs.append({'file': parts[0].lstrip('/'), 'line': int(parts[1]), 'msg': parts[2], 'detail': parts[3]})
fixes = defaultdict(lambda: {'xz': set(), 'kinds': set()})
for e in errs:
    key = (e['file'], e['line'])
    f = fixes[key]
    if 'private 访问控制' in e['msg']:
        fld = e['msg'].split(' 在 ')[0].strip()
        if fld in ('x', 'z'): f['xz'].add(fld)
    d = e['detail']
    if 'asLong(int,int)' in d: f['kinds'].add('asLong')
    if 'toLong()' in d: f['kinds'].add('toLong')
    if '构造器' in e['msg'] and 'ChunkPos' in e['msg']: f['kinds'].add('unpack')
byfile = defaultdict(list)
for (fp, ln), act in fixes.items(): byfile[fp].append((ln, act))
changed = 0
for fp, items in byfile.items():
    lines = io.open(fp, encoding='utf-8').read().split('\n')
    for ln, act in sorted(items, key=lambda t: -t[0]):
        idx = ln - 1
        if idx >= len(lines): print('OOB', fp, ln); continue
        s = lines[idx]; orig = s
        if act['xz']:
            for fld in act['xz']:
                s = re.sub(r'\.' + fld + r'\b(?!\s*\()', '.' + fld + '()', s)
        if 'asLong' in act['kinds']:
            s = s.replace('ChunkPos.asLong(', 'ChunkPos.pack(').replace('asLong(', 'pack(')
        if 'toLong' in act['kinds']:
            s = s.replace('.toLong()', '.pack()')
        if 'unpack' in act['kinds']:
            s = s.replace('new ChunkPos(', 'ChunkPos.unpack(')
        if s != orig: lines[idx] = s; changed += 1
    io.open(fp, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
print('lines changed:', changed)
