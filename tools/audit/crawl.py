"""띵타이쿤 위키 전체를 받아 문서마다 본문 글(표는 칸을 ' | '로)을 저장한다. 사용: python -I crawl.py <출력 폴더>"""
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from html.parser import HTMLParser

BASE = 'https://wiki.ddingtycoon.kr'
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def get(url):
    for i in range(3):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'ddingmate-wiki-audit (personal, low rate)'})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode('utf-8', 'replace')
        except Exception as e:
            time.sleep(2 + i * 3)
    return ''


class Text(HTMLParser):
    """<main> 안의 글. 줄 단위(블록·표 행), 표 칸은 ' | '"""
    BLOCK = {'p', 'div', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'tr', 'br', 'section', 'article', 'blockquote', 'ul', 'ol', 'table'}

    def __init__(self):
        super().__init__()
        self.depth_main = 0
        self.in_main = False
        self.out = []
        self.cur = []
        self.skip = 0

    def flush(self):
        line = re.sub(r'\s+', ' ', ''.join(self.cur)).strip(' |')
        if line:
            self.out.append(line)
        self.cur = []

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style', 'svg'):
            self.skip += 1
        if tag == 'main':
            self.in_main = True
        if not self.in_main:
            return
        if tag in ('td', 'th'):
            self.cur.append(' | ')
        elif tag in self.BLOCK:
            self.flush()

    def handle_endtag(self, tag):
        if tag in ('script', 'style', 'svg'):
            self.skip = max(0, self.skip - 1)
        if not self.in_main:
            return
        if tag in self.BLOCK or tag in ('td', 'th'):
            if tag not in ('td', 'th'):
                self.flush()
        if tag == 'main':
            self.flush()
            self.in_main = False

    def handle_data(self, data):
        if self.in_main and not self.skip:
            self.cur.append(data)


def links(page, kind):
    return set(urllib.parse.unquote(m) for m in re.findall(r'href="(?:https://wiki\.ddingtycoon\.kr)?(/ko/' + kind + r'/[^"#?]+)"', page))


start = get(BASE + '/ko')
cats = links(start, 'categories')
arts = links(start, 'articles')
seen_cats = set()
while cats - seen_cats:
    c = sorted(cats - seen_cats)[0]
    seen_cats.add(c)
    p = get(BASE + urllib.parse.quote(c))
    cats |= links(p, 'categories')
    arts |= links(p, 'articles')
    time.sleep(0.3)

done = {}
queue = sorted(arts)
while queue:
    a = queue.pop(0)
    if a in done:
        continue
    p = get(BASE + urllib.parse.quote(a))
    t = Text()
    t.feed(p)
    t.flush()
    title = re.search(r'<title>([^<]*)</title>', p)
    title = html.unescape(title.group(1)).strip() if title else a
    name = re.sub(r'[\\/:*?"<>|]', '_', a.rsplit('/', 1)[-1])[:120]
    with open(os.path.join(OUT, name + '.txt'), 'w', encoding='utf-8') as f:
        f.write(title + '\n' + BASE + a + '\n\n' + '\n'.join(t.out))
    done[a] = title
    for n in links(p, 'articles'):
        if n not in done and n not in queue:
            queue.append(n)
    time.sleep(0.3)

with open(os.path.join(OUT, '_index.json'), 'w', encoding='utf-8') as f:
    json.dump({'categories': sorted(seen_cats), 'articles': done}, f, ensure_ascii=False, indent=1)
print(len(seen_cats), 'categories', len(done), 'articles')
