#!/usr/bin/env bash
./gradlew common:compileJava 2>&1 | grep -E "^  D:.*\.java:[0-9]+: 错误|错误: |符号:|位置:|找到:|需要:" > build/errs-raw.txt
python - <<'PYEOF'
import re, io
raw = io.open('build/errs-raw.txt', encoding='utf-8', errors='replace').read().splitlines()
errs = []; cur = None
for ln in raw:
    m = re.match(r'\s*D:.+?hassium-poc26(.+?\.java):(\d+): 错误: (.+)', ln)
    if m:
        cur = {'file': m.group(1), 'line': int(m.group(2)), 'msg': m.group(3), 'detail': []}
        errs.append(cur)
    elif cur is not None and ln.startswith('    '):
        cur['detail'].append(ln.strip())
io.open('build/errs.json.txt', 'w', encoding='utf-8').write(
    '\n'.join(f"{e['file'].replace(chr(92),'/')}|{e['line']}|{e['msg']}|{' ~ '.join(e['detail'])}" for e in errs))
from collections import Counter
def kind(e):
    msg = e['msg']
    if 'private 访问控制' in msg: return 'private-xz'
    if '构造器' in msg and 'ChunkPos' in msg: return 'new-ChunkPos-long'
    if '找不到符号' in msg:
        d = ' '.join(e['detail'])
        if 'asLong(int,int)' in d: return 'asLong'
        if 'toLong()' in d: return 'toLong'
        return 'symbol'
    return 'other:' + msg[:36]
c = Counter(kind(e) for e in errs)
for k, v in c.most_common(25): print(v, k)
PYEOF
