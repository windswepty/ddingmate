"""모드 자바 소스의 ("이름", 숫자) 짝을 위키 글에서 찾아 그 이름 근처(같은 줄~4줄 아래)에 그 숫자가 있는지 본다.
사용: python -I prices_diff.py <위키 폴더> <자바 파일> [문서 이름 접두어...]"""
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
W, J = sys.argv[1], sys.argv[2]
prefixes = sys.argv[3:]
src = open(J, encoding='utf-8').read()
src = re.sub(r'//.*', '', src)
pairs = re.findall(r'"([^"]{2,40})",\s*([\d_]+)(?:,\s*([\d_]+)(?=\s*[,)]\s*(?:"|\)|$)))?', src)
texts = []
for f in sorted(os.listdir(W)):
    if f.endswith('.txt') and (not prefixes or any(f.startswith(p) for p in prefixes)):
        texts.append((f, open(os.path.join(W, f), encoding='utf-8').read().split('\n')))


def norm(s):
    return re.sub(r'\s+', '', s.replace('﻿', ''))


GRADE = re.compile(r'^\((\w+)\)\s*(?:탁월한\s*)?(.+)$')
ok = bad = notfound = 0
for name, a, b in pairs:
    nums = [int(a.replace('_', ''))] + ([int(b.replace('_', ''))] if b else [])
    want = ['{:,}'.format(n) for n in nums if n > 0]
    g = GRADE.match(name)
    alt = (g.group(2) + ' (' + g.group(1) + ')') if g else None
    hit_name = False
    found = False
    near = ''
    for f, lines in texts:
        for i, l in enumerate(lines):
            if norm(name) in norm(l) or (alt and norm(alt) == norm(l)):
                hit_name = True
                ctx = ' / '.join(lines[i:i + 5])
                if all(w in ctx for w in want):
                    found = True
                    break
                if not near:
                    near = f'{f}: ' + ctx[:200]
        if found:
            break
    if found:
        ok += 1
    elif hit_name:
        bad += 1
        print(f'숫자 다름  {name} {want}\n      위키: {near}')
    else:
        notfound += 1
        print(f'위키에 이름 없음  {name} {want}')
print(f'같음 {ok} · 숫자 다름 {bad} · 이름 없음 {notfound}')
