"""전문가 문서 4개에서 스킬마다 레벨별 설명을 뽑아 JSON 으로. 사용: python -I skills.py <위키 폴더> <출력 json>"""
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
W, OUT = sys.argv[1], sys.argv[2]
skills = {}
for f in sorted(os.listdir(W)):
    if not re.match(r'^(채광|재배|해양|사냥)-전문가-', f):
        continue
    expert = f.split('-')[0]
    lines = open(os.path.join(W, f), encoding='utf-8').read().split('\n')
    cur = None
    lv = None
    for i, l in enumerate(lines):
        s = l.strip()
        m = re.match(r'^\S*\s*\[([^\]]+)\]\s*(.+)$', s)
        if m and i + 1 < len(lines) and re.match(r'^\d+\s*LV$|^MAX', lines[i + 1].strip()):
            cur = {'expert': expert, 'where': m.group(1), 'levels': []}
            skills[m.group(2).strip()] = cur
            continue
        if cur is None:
            continue
        m = re.match(r'^(\d+)\s*LV$', s) or (re.match(r'^MAX$', s) and None)
        if m:
            lv = int(m.group(1))
            continue
        if lv is not None and s and not s.startswith('요구') and not s.startswith('필요') and 'Point' not in s and not re.match(r'^[\d,]+\s*(G|개)$', s):
            cur['levels'].append({'lv': lv, 'text': s})
            lv = None
json.dump(skills, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(len(skills), 'skills')
for k, v in list(skills.items())[:3]:
    print(k, v['expert'], [x['text'][:40] for x in v['levels']][:3])
