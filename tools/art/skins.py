"""HUD 판·제목 줄 그림(NPC 세트 17벌). 나인슬라이스 그림을 만든다.

판 그림 40×40: 바깥 3칸(O)은 판 밖으로 튀어나오는 장식 자리, 그 안 34×34 가 판.
  나인슬라이스 테두리 8칸(= O 3 + 판 안 5) → 귀퉁이는 그대로, 변은 24칸 무늬가 되풀이, 가운데는 바탕색만.
  판 안쪽 테두리는 3칸까지만(HUD 글자·아이콘이 판 왼쪽에서 4칸부터 시작).
제목 줄 그림 40×17: 판 위 3칸 + 제목 줄 14칸. 글자는 제목 줄 6~13번째 줄 → 그 자리는 한 가지 색.
가운데 바탕은 무늬 없이(글자가 잘 보이게) — 2026-10-22 사용자: "중앙 패널도 깔끔하고 가시성·가독성 좋게".
"""
from PIL import Image

from px import hexc, mix

O = 3          # 판 밖 장식 자리
PW = 40        # 판 그림 크기
B = 8          # 나인슬라이스 테두리
HH = 17        # 제목 줄 그림 높이(O + 14)
P0, P1 = O, PW - O  # 판 안쪽 범위 [P0, P1)


def c_(v):
    return hexc(v) if isinstance(v, str) else v


class Tex:
    def __init__(self, w, h):
        self.im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        self.w, self.h = w, h

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h and c is not None:
            self.im.putpixel((int(x), int(y)), c_(c))

    def get(self, x, y):
        return self.im.getpixel((x, y))

    def rect(self, x0, y0, x1, y1, c):
        for x in range(x0, x1):
            for y in range(y0, y1):
                self.px(x, y, c)

    def sprite(self, x, y, rows, pal, flip_x=False, flip_y=False):
        h = len(rows)
        for r, row in enumerate(rows):
            w = len(row)
            for k, ch in enumerate(row):
                if ch in pal:
                    self.px(x + (w - 1 - k if flip_x else k), y + (h - 1 - r if flip_y else r), pal[ch])

    def corners(self, rows, pal, inset=0):
        """같은 그림을 네 귀퉁이에(좌우·상하 뒤집어서)"""
        h, w = len(rows), len(rows[0])
        self.sprite(inset, inset, rows, pal)
        self.sprite(self.w - w - inset, inset, rows, pal, flip_x=True)
        self.sprite(inset, self.h - h - inset, rows, pal, flip_y=True)
        self.sprite(self.w - w - inset, self.h - h - inset, rows, pal, flip_x=True, flip_y=True)


def edge_x():
    """가로 변 무늬 칸(되풀이 24칸)과 그 안 위치"""
    return [(x, (x - B) % 24) for x in range(B, PW - B)]


def frame(t, outline, light, dark, body, thick=2, hi=None, lo=None, round_=True):
    """판 기본: 둥근 외곽선 + 두께 thick 의 테두리(위·왼쪽 밝게, 아래·오른쪽 어둡게) + 바탕"""
    t.rect(P0 + 1, P0 + 1, P1 - 1, P1 - 1, body)
    for i in range(thick):
        a, b = P0 + 1 + i, P1 - 2 - i
        t.rect(a, a, b + 1, a + 1, light)
        t.rect(a, a, a + 1, b + 1, light)
        t.rect(a, b, b + 1, b + 1, dark)
        t.rect(b, a, b + 1, b + 1, dark)
    if hi:
        a, b = P0 + 1 + thick, P1 - 2 - thick
        t.rect(a, a, b + 1, a + 1, hi)
        t.rect(a, a, a + 1, b + 1, hi)
    if lo:
        a, b = P0 + 1 + thick, P1 - 2 - thick
        t.rect(a, b, b + 1, b + 1, lo)
        t.rect(b, a + 1, b + 1, b + 1, lo)
    # 외곽선(둥근 귀퉁이면 꼭짓점 한 칸을 비우고 안쪽에 한 칸)
    t.rect(P0 + 1, P0, P1 - 1, P0 + 1, outline)
    t.rect(P0 + 1, P1 - 1, P1 - 1, P1, outline)
    t.rect(P0, P0 + 1, P0 + 1, P1 - 1, outline)
    t.rect(P1 - 1, P0 + 1, P1, P1 - 1, outline)
    if round_:
        for x, y in ((P0 + 1, P0 + 1), (P1 - 2, P0 + 1), (P0 + 1, P1 - 2), (P1 - 2, P1 - 2)):
            t.px(x, y, outline)
    else:
        for x, y in ((P0, P0), (P1 - 1, P0), (P0, P1 - 1), (P1 - 1, P1 - 1)):
            t.px(x, y, outline)


def head_band(t, outline, base, light=None, dark=None, ends=True):
    """제목 줄 기본 띠: 판 윗변(3번째 줄)부터 14줄, 양 끝 둥글게"""
    y0, y1 = O, HH  # 3..16
    t.rect(O + 1, y0 + 1, t.w - O - 1, y1 - 1, base)
    if light:
        t.rect(O + 2, y0 + 1, t.w - O - 2, y0 + 2, light)
    if dark:
        t.rect(O + 1, y1 - 2, t.w - O - 1, y1 - 1, dark)
    t.rect(O + 1, y0, t.w - O - 1, y0 + 1, outline)
    t.rect(O + 1, y1 - 1, t.w - O - 1, y1, outline)
    t.rect(O, y0 + 1, O + 1, y1 - 1, outline)
    t.rect(t.w - O - 1, y0 + 1, t.w - O, y1 - 1, outline)


def band_x():
    """제목 줄 가로 무늬 칸: (x, 되풀이 24칸 안 위치)"""
    return [(x, (x - 10) % 20) for x in range(10, PW - 10)]


# ================================================================ NPC 세트

SKINS = []


def skin(id_, name, place, dark, colors, title, title_shadow=True):
    def deco(fn):
        SKINS.append(dict(id=id_, name=name, place=place, dark=dark, colors=colors, title=title,
                          titleShadow=title_shadow, build=fn))
        return fn
    return deco


LIGHT_TEXT = dict(text='3e2a1e', soft='8a7262', green='2f8a3a', red='c8402f', gold='a86a10', blue='2f62b0')
DARK_TEXT = dict(text='f4efe6', soft='aca49c', green='7bd88f', red='ff7b72', gold='f2c860', blue='8ab4f8')


def lt(**kw):
    d = dict(LIGHT_TEXT)
    d.update(kw)
    return d


def dk(**kw):
    d = dict(DARK_TEXT)
    d.update(kw)
    return d


SUNFLOWER = ["..aba..", ".abbba.", "abbcbba", "bbcdcbb", "abbcbba", ".abbba.", "..aba.."]


@skin('bori', '보리', '햇살 농가', False, lt(text='3e2a14'), '4a2e10', False)
def bori(p, h):
    frame(p, '5a3a1e', 'e6b878', 'b98448', 'fff9ec', thick=2, hi='ffffff', lo='f2e6cc')
    for x, k in edge_x():  # 울타리 판자 이음매
        if k % 6 == 0:
            for y in (P0 + 1, P0 + 2, P1 - 3, P1 - 2):
                p.px(x, y, '8a5a2e')
            p.px(P0 + 1, x, '8a5a2e'); p.px(P0 + 2, x, '8a5a2e')
            p.px(P1 - 3, x, '8a5a2e'); p.px(P1 - 2, x, '8a5a2e')
    p.corners(SUNFLOWER, {'a': 'f4b41a', 'b': 'ffd23a', 'c': 'a8642a', 'd': '6a3a14'})
    head_band(h, '5a3a1e', 'efc660', 'ffe08a', 'c99a35')
    for x, k in band_x():
        if k % 4 == 0:
            h.px(x, O + 2, 'e0b44e'); h.px(x + 2, HH - 3, 'e0b44e')
    h.sprite(0, 0, SUNFLOWER, {'a': 'f4b41a', 'b': 'ffd23a', 'c': 'a8642a', 'd': '6a3a14'})
    h.sprite(h.w - 7, 0, SUNFLOWER, {'a': 'f4b41a', 'b': 'ffd23a', 'c': 'a8642a', 'd': '6a3a14'}, flip_x=True)


SPROUT = ["......", ".aa.bb", "aaacbb", ".a.c..", "...c..", "...c.."]


@skin('dodam', '도담', '햇살 농가', False, lt(text='2a3a1e', soft='6a7a5a'), 'fff6e0')
def dodam(p, h):
    frame(p, '2e4a24', '86c06a', '4f8a3a', 'f6fbef', thick=2, hi='ffffff', lo='e4efd6')
    for x, k in edge_x():  # 아래 변 밖 풀
        if k % 8 in (0, 3):
            p.px(x, P1, '5fa845'); p.px(x + 1, P1, '3f7f32')
        if k % 8 == 1:
            p.px(x, P1 + 1, '5fa845')
    p.corners(SPROUT, {'a': '6fbf52', 'b': '8fd86a', 'c': '3f7f32'})
    head_band(h, '4a2e18', 'a8774a', 'c9935e', '7a5232')
    for x, k in band_x():  # 나뭇결
        if k in (2, 3, 4, 5, 12, 13, 14):
            h.px(x, O + 3, '946640')
        if k in (8, 9, 10, 17, 18):
            h.px(x, HH - 4, '946640')
    for x in (O + 3, h.w - O - 4):  # 못
        h.px(x, O + 3, 'e6e0d0'); h.px(x, HH - 4, 'e6e0d0')


BOLT = [".aa.", "abba", "abca", ".aa."]


@skin('seoki', '석이', '강철 채석장', True, dk(text='f2eee8', gold='ffd060'), 'ffffff')
def seoki(p, h):
    frame(p, '111216', '8a8f98', '5a5e66', '26282d', thick=2, hi='3a3d44', lo='1c1d21')
    for x, k in edge_x():  # 돌 조각
        if k in (2, 9, 15, 20):
            p.px(x, P0 + 1, 'a8adb6'); p.px(x + 1, P1 - 2, '45484f')
            p.px(P0 + 1, x, 'a8adb6'); p.px(P1 - 2, x + 1, '45484f')
    p.corners(BOLT, {'a': '3a3d44', 'b': 'b8bcc6', 'c': '6a6e78'}, inset=O + 1)
    head_band(h, '111216', 'e87a1a', 'f6a24a', 'b85a0a')
    for x in range(O + 1, h.w - O - 1):  # 위 두 줄 안전 줄무늬(노랑·검정 사선)
        for y in (O + 1, O + 2):
            h.px(x, y, 'f2c230' if ((x + y) // 3) % 2 == 0 else '2a2a2e')


@skin('duri', '두리', '강철 채석장', True, dk(text='fff2e0', soft='b8a89c'), 'fff4d8')
def duri(p, h):
    frame(p, '120c0a', '7a7e86', '4a4e56', '2b2220', thick=2, hi='3e3230', lo='1e1716')
    for x, k in edge_x():  # 쇠 리벳
        if k % 8 == 4:
            p.px(x, P0 + 1, 'c8ccd4'); p.px(x, P1 - 2, 'a8acb4')
            p.px(P0 + 1, x, 'c8ccd4'); p.px(P1 - 2, x, 'a8acb4')
    p.corners(["aaaaa", "abbba", "abcb.", "abb..", "aa..."], {'a': '2a2c30', 'b': '9a9ea8', 'c': 'e0e4ea'}, inset=O)
    head_band(h, '120c0a', 'd4602a')
    grad = ['f2a040', 'ea8a34', 'e0742e', 'd4602a', 'c8501e', 'bc4418', 'b03a14', 'a43212', '982c10', '8c260e', '80200c', '74200c']
    for i, col in enumerate(grad):
        h.rect(O + 1, O + 1 + i, h.w - O - 1, O + 2 + i, col)
    for x, k in band_x():  # 불티
        if k in (3, 13):
            h.px(x, 1, 'ffd860')
        if k == 8:
            h.px(x, 0, 'ffb030')


LOG = [".aaaa.", "abbbba", "abccba", "abccba", "abbbba", ".aaaa."]


@skin('deumjik', '듬직', '숲속 공방', False, lt(text='3a2412'), 'fff2e6')
def deumjik(p, h):
    frame(p, '3a2412', '8a6040', '5a3a20', 'fbf5ea', thick=3, hi='ffffff', lo='efe2cc')
    for x, k in edge_x():  # 나무껍질 결
        if k % 5 == 0:
            p.px(x, P0 + 2, '5a3a20'); p.px(x, P1 - 3, '3e2614')
            p.px(P0 + 2, x, '5a3a20'); p.px(P1 - 3, x, '3e2614')
    p.corners(LOG, {'a': '5a3a20', 'b': 'd9b07a', 'c': 'b98a52'}, inset=1)
    head_band(h, '3a1410', 'b8322c', 'd04a40', '8e2620')
    for x in range(O + 1, h.w - O - 1):  # 위아래 체크 무늬
        for y in (O + 1, O + 2, HH - 3, HH - 2):
            if (x // 2 + y // 2) % 2 == 0:
                h.px(x, y, '6e1a17')


CLAW = ["a...", ".a.a", "..a.", "...a"]


@skin('hotang', '호탕', '숲속 공방', True, dk(text='f6ece4', gold='f2c860'), 'ffe8d0')
def hotang(p, h):
    frame(p, '0e0a08', '9a6a46', '6a4228', '241e1c', thick=2, hi='342a26', lo='1a1412')
    for x, k in edge_x():  # 가죽 바느질
        if k % 3 == 0:
            p.px(x, P0 + 1, 'e0c08a'); p.px(x, P1 - 2, 'c9a874')
            p.px(P0 + 1, x, 'e0c08a'); p.px(P1 - 2, x, 'c9a874')
    p.corners([".a.....", "aba....", ".abca..", "..cbba.", "...bbba", "....aba", ".....a."], {'a': '3a2a20', 'b': 'd8d2c6', 'c': 'a8a296'}, inset=0)
    head_band(h, '1e0806', '9e2a24', 'b8403a', '7a1e1a')
    for x, k in band_x():  # 아래 털 장식
        if k % 3 == 0:
            h.px(x, HH - 2, 'f2ede4'); h.px(x + 1, HH - 1, 'd8d2c6')


RAINBOW = ['ff6b6b', 'ffa54a', 'ffd84a', '6fcf6a', '5aa8f0', 'b07ce8']


@skin('hani', '하니', '꽃잎 공방', False, lt(text='4a3048', soft='8a7488'), 'ffffff')
def hani(p, h):
    frame(p, '6a4a5a', 'f9c4d2', 'e595ab', 'fffdfa', thick=2, hi='ffffff', lo='f6ecef')
    for x, k in edge_x():  # 물감 점(여섯 색 × 4칸 = 24칸)
        if k % 4 == 1:
            col = RAINBOW[k // 4]
            p.px(x, P0 + 1, col); p.px(x + 1, P1 - 2, col)
            p.px(P0 + 1, x, col); p.px(P1 - 2, x + 1, col)
    p.corners([".aa..", "abba.", "abbba", ".abba", "..aa."], {'a': 'e0507a', 'b': 'ff8fb0'}, inset=0)
    head_band(h, '1d2347', '4e5c9a', '6a7ab6', '3a4680')
    for x, k in band_x():  # 아래 무지개 띠
        h.px(x, HH - 2, RAINBOW[(k // 4) % 6])


BRICK_PAL = {'a': 'b5563f', 'b': 'e8d6c6', 'c': '8e3e2c'}


@skin('bareum', '바름', '꽃잎 공방', False, lt(text='22324a', soft='6a7686'), 'ffffff')
def bareum(p, h):
    frame(p, '2a3a52', 'c46a52', '9a4a36', 'f4f8fc', thick=3, hi='ffffff', lo='e2eaf2')
    for x, k in edge_x():  # 벽돌 줄눈
        for y, off in ((P0 + 2, 0), (P1 - 3, 3)):
            if (k + off) % 6 == 0:
                p.px(x, y - 1, 'e8d6c6'); p.px(x, y, 'e8d6c6'); p.px(x, y + 1, 'e8d6c6')
        if k % 6 == 0:
            p.px(P0 + 2, x, 'e8d6c6'); p.px(P1 - 3, x + 3 if x + 3 < PW - B else x, 'e8d6c6')
    p.corners(["aaaaa", "abbba", "abcba", "abbba", "aaaaa"], {'a': '6a7280', 'b': 'c8ccd4', 'c': 'e6e8ee'}, inset=O)
    head_band(h, '1a2e52', '3c6fb8', '5a8ad0', '2c5590')
    for x, k in band_x():  # 설계도 눈금(위·아래 줄만)
        if k % 4 == 0:
            h.px(x, O + 1, '8ab4e8'); h.px(x, HH - 2, '8ab4e8')
        if k % 4 == 2:
            h.px(x, O + 2, '6a9ad8')


BUOY = [".aaaa.", "abbcca", "ab..ca", "ac..ba", "accbba", ".aaaa."]


@skin('nuri', '누리', '갯바위 선착장', False, lt(text='1e3450', soft='667a8e'), 'ffffff')
def nuri(p, h):
    frame(p, '1e3450', 'e8c890', 'b48e57', 'f2f7fa', thick=2, hi='ffffff', lo='e0eaf0')
    for x, k in edge_x():  # 꼰 밧줄 무늬
        if k % 4 in (0, 1):
            p.px(x, P0 + 1 + (k % 4), '8a6a3a'); p.px(x, P1 - 2 - (k % 4), '8a6a3a')
            p.px(P0 + 1 + (k % 4), x, '8a6a3a'); p.px(P1 - 2 - (k % 4), x, '8a6a3a')
    p.corners(BUOY, {'a': '6a1a14', 'b': 'e8473f', 'c': 'ffffff'}, inset=1)
    head_band(h, '0e1e3a', '2f5e9e', '4a7cc0', '234a80')
    for x in range(O + 1, h.w - O - 1):  # 선원 줄무늬
        h.px(x, O + 2, 'ffffff'); h.px(x, HH - 3, 'ffffff')


@skin('kai', '카이', '세레니티 상점가', False, lt(text='2e3a14', soft='6a7650'), 'ffffff')
def kai(p, h):
    frame(p, '3a4a1a', 'e6d27a', 'b8a040', 'fafdf0', thick=2, hi='ffffff', lo='edf3dc')
    for x, k in edge_x():  # 대나무 마디
        if k % 8 == 0:
            for y in (P0 + 1, P0 + 2, P1 - 3, P1 - 2):
                p.px(x, y, '8a7a2a')
            p.px(P0 + 1, x, '8a7a2a'); p.px(P0 + 2, x, '8a7a2a'); p.px(P1 - 3, x, '8a7a2a'); p.px(P1 - 2, x, '8a7a2a')
    p.corners(["..a..", ".aba.", "abcba", ".bcb.", "..c.."], {'a': 'ff5a7a', 'b': 'ff9ab0', 'c': 'ffe066'}, inset=0)  # 히비스커스
    head_band(h, '1e3a10', '4fa84a', '6fc45a', '3a8a35')
    for x, k in band_x():  # 잎 가장자리 톱니
        if k % 4 == 0:
            h.px(x, HH - 1, '3a8a35'); h.px(x + 1, HH, '3a8a35') if HH < h.h else None
        if k % 4 == 2:
            h.px(x, O + 2, '8fd86a')


BRACKET = ["aaaaa.", "abbbba", "ab....", "ab....", "ab....", ".a...."]


@skin('roni', '로니', '세레니티 상점가', False, lt(text='3a2418'), 'fff4e0')
def roni(p, h):
    frame(p, '3a2418', '8a5e3c', '5a3a22', 'fbf3e9', thick=3, hi='ffffff', lo='efe2d2')
    for x, k in edge_x():  # 나뭇결
        if k in (3, 4, 5, 6, 15, 16, 17):
            p.px(x, P0 + 2, '6e4628')
        if k in (9, 10, 11, 20, 21):
            p.px(x, P1 - 3, '4a2e18')
        if k in (5, 6, 7, 18, 19):
            p.px(P0 + 2, x, '6e4628'); p.px(P1 - 3, x, '4a2e18')
    p.corners(BRACKET, {'a': '2a2c30', 'b': '9a9ea8'}, inset=O)
    # 빨간 지붕: 비늘 기와 + 처마, 양 끝이 판 밖으로 살짝
    head_band(h, '4a1410', 'c8402f', 'e0584a', '962a20')
    for x in range(1, h.w - 1):
        h.px(x, 2, '4a1410'); h.px(x, 3, 'd84a38')
    h.px(0, 3, '4a1410'); h.px(h.w - 1, 3, '4a1410')
    h.rect(1, 3, 3, 5, 'c8402f'); h.rect(h.w - 3, 3, h.w - 1, 5, 'c8402f')
    h.px(1, 5, '4a1410'); h.px(2, 5, '4a1410'); h.px(h.w - 2, 5, '4a1410'); h.px(h.w - 3, 5, '4a1410')
    for x, k in band_x():  # 기와 비늘(아래 두 줄)
        if k % 5 == 0:
            h.px(x, HH - 3, '962a20'); h.px(x + 1, HH - 2, '962a20'); h.px(x - 1, HH - 2, '962a20')


@skin('milky', '밀키', '세레니티 상점가', False, lt(text='4a1e1a', soft='8a6a66'), '8e2620', False)
def milky(p, h):
    frame(p, '5a1e1a', 'd9473f', 'b8322c', 'fffdf8', thick=3, hi='ffffff', lo='f6ece4')
    for x in range(P0 + 1, P1 - 1):  # 깅엄 체크
        for y in range(P0 + 1, P1 - 1):
            if P0 + 1 + 3 <= x < P1 - 1 - 3 and P0 + 1 + 3 <= y < P1 - 1 - 3:
                continue
            if (x - B) % 2 == 0 and (y - B) % 2 == 0:
                p.px(x, y, 'ffffff')
            elif (x - B) % 2 == 0 or (y - B) % 2 == 0:
                p.px(x, y, 'f08a84')
    p.corners([".aa.", "abba", "abba", ".aa."], {'a': '8e2620', 'b': 'ffffff'}, inset=O)
    head_band(h, '5a1e1a', 'ffffff', None, 'f2e6e0')
    for x in range(O + 1, h.w - O - 1):  # 위·아래 빨간 체크 줄
        for y in (O + 1, O + 2, HH - 3, HH - 2):
            h.px(x, y, 'd9473f' if (x // 2 + y // 2) % 2 == 0 else 'f6b4ae')


@skin('sharky', '샤키', '세레니티 상점가', False, lt(text='163463', soft='5a7090'), 'ffffff')
def sharky(p, h):
    frame(p, '163463', '5a9ae6', '2c62ad', 'f3f8fc', thick=2, hi='ffffff', lo='e2edf8')
    for x, k in edge_x():  # 아래 변 밖 물거품
        if k % 8 in (0, 1, 2):
            p.px(x, P1, 'ffffff' if k % 8 == 1 else 'cfe6fa')
        if k % 8 == 1:
            p.px(x, P1 + 1, 'cfe6fa')
    p.corners([".aa..", "abba.", "abba.", ".aa..", "....a"], {'a': '7db6f2', 'b': 'ffffff'}, inset=0)  # 물방울
    head_band(h, '0e2a56', '2c62ad', '3f82d8', '1c4a8a')
    for x, k in band_x():  # 물결
        if k % 6 in (0, 1):
            h.px(x, HH - 2, 'cfe6fa')
        if k % 6 in (2, 3):
            h.px(x, HH - 3, 'ffffff')


ANCHOR = ["..a..", ".aba.", "..a..", "a.a.a", ".aaa."]


@skin('pos', '포스', '세레니티 상점가', True, dk(text='f2ece0', gold='f2d06a'), 'f2d06a')
def pos(p, h):
    frame(p, '0a0e14', '7a5236', '4a2e1c', '1e2633', thick=3, hi='d9b04a', lo='141a24')
    for x, k in edge_x():  # 갑판 판자 이음매
        if k % 12 == 0:
            for y in (P0 + 1, P0 + 2, P0 + 3, P1 - 4, P1 - 3, P1 - 2):
                p.px(x, y, '2e1c10')
            for y in (P0 + 1, P0 + 2, P0 + 3, P1 - 4, P1 - 3, P1 - 2):
                p.px(y, x, '2e1c10')
    p.corners(ANCHOR, {'a': 'd9b04a', 'b': '1e2633'}, inset=0)
    head_band(h, '000000', '1a1a22', '2a2a34', '121218')
    for x in range(O + 1, h.w - O - 1):
        h.px(x, O + 1, 'd9b04a'); h.px(x, HH - 2, 'd9b04a')
    h.sprite(h.w - O - 7, O + 4, [".aaa.", "abcba", "aaaaa", ".a.a."], {'a': 'f2ece0', 'b': '1a1a22', 'c': 'f2ece0'})  # 해골


FEATHER = ["....a", "...ab", "..abb", ".abb.", "abb..", "c...."]


@skin('fix', '픽스', '세레니티 상점가', True, dk(text='efe8f8', soft='a8a0b8', gold='f2c860'), 'e8d8ff')
def fix(p, h):
    frame(p, '0a0810', 'b8885a', '7a5430', '1e1a28', thick=2, hi='2e2840', lo='16121e')
    for x, k in edge_x():  # 바깥 영혼 불빛
        if k in (5, 17):
            p.px(x, P0 - 1, 'b07ce8'); p.px(x + 1, P1, '7ae8ff')
        if k == 11:
            p.px(P0 - 1, x, 'b07ce8'); p.px(P1, x, '7ae8ff')
    p.corners(FEATHER, {'a': 'ffffff', 'b': 'd8c8b0', 'c': '7a5430'}, inset=0)
    head_band(h, '0a0614', '3e2a64')
    grad = ['5a3e8c', '523886', '4a327c', '442e74', '3e2a6c', '382664', '34225c', '301f56', '2c1c50', '281a4a', '24184a', '201644']
    for i, col in enumerate(grad):
        h.rect(O + 1, O + 1 + i, h.w - O - 1, O + 2 + i, col)
    for x, k in band_x():
        if k in (4, 14):
            h.px(x, 1, 'c8a8ff'); h.px(x, 2, '8a6ad8')
        if k == 9:
            h.px(x, 0, '7ae8ff')


BELL = ["..a..", ".aba.", ".abb.", "abbba", "aaaaa", "..c.."]


@skin('garam', '가람', '코스메틱 스토어', False, lt(text='4a2a14', soft='8a6a52'), '6a3a1a', False)
def garam(p, h):
    frame(p, '6a3a1a', 'f09a52', 'c2621e', 'fff8f2', thick=2, hi='ffffff', lo='f6ebe0')
    for x, k in edge_x():  # 주황 기와 비늘
        if k % 4 == 0:
            p.px(x, P0 + 2, 'a84e14'); p.px(x + 1, P0 + 1, 'ffc08a')
            p.px(x, P1 - 2, 'a84e14')
            p.px(P0 + 1, x, 'a84e14'); p.px(P1 - 2, x, 'a84e14')
    p.corners(BELL, {'a': 'b8860e', 'b': 'f2c230', 'c': '8a5a0a'}, inset=0)
    # 걸린 간판: 크림색 종이 + 양 끝에서 위로 올라가는 줄
    head_band(h, '6a3a1a', 'fbebc8', 'fff6e0', 'e6cc9a')
    for x, k in band_x():
        if k in (3, 11):
            h.px(x, O + 2, 'f0dcb0')
        if k == 16:
            h.px(x, HH - 3, 'f0dcb0')
    for x in (O + 3, h.w - O - 4):
        h.rect(x, 0, x + 1, O, 'c9a874')
        h.px(x, O + 1, '8a6a3a')


GEM = ["..a..", ".abc.", "abbcd", ".bcd.", "..d.."]


@skin('lua', '루아', '로얄 상점', True, dk(text='f6f0ff', soft='b0a8c8', gold='ffe08a', blue='9ad0ff'), 'ffe9a8')
def lua(p, h):
    frame(p, '0a0814', 'f2d070', 'b8902e', '1b1730', thick=2, hi='2c2648', lo='120f22')
    for x, k in edge_x():  # 금 마름모 무늬
        if k % 6 == 3:
            p.px(x, P0 + 1, 'fff6c8'); p.px(x, P1 - 2, '8a6a1e')
            p.px(P0 + 1, x, 'fff6c8'); p.px(P1 - 2, x, '8a6a1e')
    p.corners(GEM, {'a': 'e8f8ff', 'b': '7ae8ff', 'c': '3ab8e8', 'd': '1a6ab0'}, inset=0)
    head_band(h, '1a0a30', '5b3a8c', '7450a8', '44286e')
    for x in range(O + 1, h.w - O - 1):
        h.px(x, HH - 2, 'd9b04a')
    h.sprite(O + 2, 0, ["a.a.a", "aaaaa", "abbba"], {'a': 'f2c230', 'b': 'ffe08a'})  # 작은 왕관
    h.sprite(h.w - O - 7, 0, ["a.a.a", "aaaaa", "abbba"], {'a': 'f2c230', 'b': 'ffe08a'})


def build(sk):
    p = Tex(PW, PW)
    h = Tex(PW, HH)
    sk['build'](p, h)
    return p.im, h.im
