"""위키(크롤 글)의 '항목 / 재료' 표와 recipes.json 을 전수 비교. 사용: python -I recipes_diff.py <위키 폴더> <recipes.json>"""
import collections
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
W, RJ = sys.argv[1], sys.argv[2]
MAT = re.compile(r'^\s*(.+?)\s+([\d,]+)\s*개\s*$')
ITEM = re.compile(r'^\s*(?:\[([^\]]+)\]\s*)?(.+?)(?:\s*[xX×]\s*(\d+))?\s*$')
FAC_HINT = re.compile(r'(시설|제작대|커피 머신|믹서기|공방|모루|플로리스트|조합법)')


def parse_mats(line):
    parts = [p.strip() for p in line.split('+')]
    out = collections.Counter()
    for p in parts:
        m = MAT.match(p)
        if not m:
            # 개수 없이 적힌 재료(위키: "꿀 블록 + 금 주괴 2개")는 1개
            if len(parts) > 1 and p and '개' not in p and len(p) < 20:
                out[p] += 1
                continue
            return None
        out[m.group(1).strip()] += int(m.group(2).replace(',', ''))
    return out


wiki = []  # (article, heading, category, name, output, mats)
# 문서별 표 방향: 1이면 재료→이름(아래 줄이 이름). 첫 번째 훑기에서 정확히 맞은 쪽 다수로 정한다
ORIENT = {}
import json as _j
_d = _j.load(open(RJ, encoding='utf-8'))
_mod = collections.defaultdict(list)
for _r in _d['recipes']:
    _c = collections.Counter()
    for _x in _r['materials']:
        _c[_x['name']] += _x['count']
    _mod[(_r['name'], _r['output'])].append(_c)
for f in sorted(os.listdir(W)):
    if not f.endswith('.txt'):
        continue
    ls = open(os.path.join(W, f), encoding='utf-8').read().split('\n')
    up = down = 0
    for i, l in enumerate(ls):
        mm = parse_mats(l.strip()) if '개' in l else None
        if not mm:
            continue
        for j, sign in ((i - 1, 'up'), (i + 1, 'down')):
            if 0 <= j < len(ls):
                m = ITEM.match(ls[j].strip())
                if m and mm in _mod.get((m.group(2).strip(), int(m.group(3) or 1)), []):
                    if sign == 'up':
                        up += 1
                    else:
                        down += 1
    ORIENT[ls[0]] = 1 if down > up else -1
for f in sorted(os.listdir(W)):
    if not f.endswith('.txt'):
        continue
    lines = open(os.path.join(W, f), encoding='utf-8').read().split('\n')
    title = lines[0]
    heading = title
    for i, l in enumerate(lines):
        s = l.strip()
        if FAC_HINT.search(s) and len(s) < 30 and not parse_mats(s) and '개' not in s:
            heading = s
        mats = parse_mats(s) if ('개' in s) else None
        if not mats or i == 0:
            continue
        # 표 방향이 문서마다 다르다(이름→재료, 추가 조합법은 재료→이름): 위·아래 줄 둘 다 후보로
        cands = []
        for j in (i - 1, i + 1) if ORIENT.get(title, -1) < 0 else (i + 1, i - 1):
            if 0 <= j < len(lines):
                c = lines[j].strip()
                if c and '개' not in c and c not in ('재료', '항목') and len(c) < 40:
                    m = ITEM.match(c)
                    cands.append((m.group(1) or '', m.group(2).strip(), int(m.group(3) or 1)))
        if cands:
            wiki.append((title, heading, cands, mats))

d = json.load(open(RJ, encoding='utf-8'))
mod = collections.defaultdict(list)
for r in d['recipes']:
    mod[(r['name'], r['output'])].append(r)

seen = set()
same = diff = missing = 0
report = []
for (title, heading, cands, mats) in wiki:
    def mats_of(r):
        mm = collections.Counter()
        for x in r['materials']:
            mm[x['name']] += x['count']
        return mm
    pick = None
    for (cat, name, out) in cands:
        if any(mats_of(r) == mats for r in mod.get((name, out), [])):
            pick = (cat, name, out)
            break
    if pick is None:
        pick = cands[0]  # 정확히 맞는 쪽이 없으면 그 문서의 표 방향 쪽 이름
    cat, name, out = pick
    key = (name, out)
    rs = mod.get(key)
    if not rs:
        missing += 1
        report.append(f'모드에 없음  [{title} / {heading}] [{cat}] {name} x{out}: {dict(mats)}')
        continue
    ok = None
    for r in rs:
        mm = collections.Counter()
        for x in r['materials']:
            mm[x['name']] += x['count']
        if mm == mats:
            ok = r
            break
    if ok:
        same += 1
        seen.add(ok['id'])
        if cat and ok.get('category') and cat != ok['category']:
            report.append(f'분류 다름    {name} x{out}: 위키 [{cat}] / 모드 [{ok["category"]}]')
    else:
        diff += 1
        report.append(f'재료 다름    [{title} / {heading}] {name} x{out}\n      위키: {dict(mats)}\n      모드: '
                      + ' / '.join(str({x["name"]: x["count"] for x in r["materials"]}) + '(' + r['facility'] + ')' for r in rs))
        for r in rs:
            seen.add(r['id'])
unseen = [r for r in d['recipes'] if r['id'] not in seen]
print(f'위키 표 줄 {len(wiki)} · 같음 {same} · 재료 다름 {diff} · 모드에 없음 {missing} · 위키에서 못 찾은 모드 레시피 {len(unseen)}')
print('\n'.join(report))
print('\n== 위키 표에서 못 찾은 모드 레시피')
for r in unseen:
    print(f'  {r["facility"]} / [{r.get("category")}] {r["name"]} x{r["output"]}: ' + str({x["name"]: x["count"] for x in r["materials"]}))
