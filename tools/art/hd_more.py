"""HD(2배 해상도) 세트 나머지 13명: 캐릭터마다 다른 포즈·소품 + 화려한 판·제목 줄. 보리·하니·샤키·포스는 hd_sets.py"""
import math

from hdkit import (AX, W, H, m, Tone, canvas, head, tufts, crown_tuft, eyes, happy_eyes, sleepy_eyes, wink, blush,
                   nose, mouth_open, mouth_w, mouth_smile, mouth_flat, mouth_tongue, body, ellipse_pair, mirror_add, EYE)
import hdkit as K
from hd_sets import Tex, frame, band, corners4, fline, fpaint, paw, lines, PW, O, B, HH, HB
from px import hexc, mix

LEAF = Tone('b8e890', '6fbf52', '4c8e3a', '24501a')
WOOD = Tone('e6c08a', 'c08a52', '966434', '4a2e14')
GOLD = Tone('fff4b0', 'f4c84c', 'c89a26', '6a4c0a')
STEEL = Tone('eef2f8', 'b8c0cc', '8a94a4', '3a4250')


def arm(c, x0, y0, x1, y1, tone, r=2.3, size=3.0, palm=False):
    """팔(둥근 막대) + 팔 방향으로 손가락이 향한 손"""
    c.add(c.capsule(x0, y0, x1, y1, r * 0.92), tone, shade=2, light=True, line=True)
    paw(c, x1, y1, size * 0.9, tone, x1 - x0, y1 - y0, palm)


def sparkle(c, x, y, col='fff6c8', big=False):
    """고운 반짝(80칸 좌표)"""
    pts = [(x, y), (x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)]
    if big:
        pts += [(x - 2, y), (x + 2, y), (x, y - 2), (x, y + 2)]
    for p in pts:
        c.fdot(*p, col)
    c.fdot(x, y, 'ffffff')


BROW = {
    'soft': [(-5, 1), (-2, -0.6), (2, -0.6), (5, 1)],        # 둥근 아치(다정)
    'up': [(-5, 1.6), (-2, -1), (2, -1.4), (5, -0.2)],       # 치켜든(신남·자신만만)
    'flat': [(-5, 0.8), (-1, 0), (3, 0), (6, 0.6)],          # 느긋(졸림·무심)
    'bold': [(-5, -1), (-1, -0.6), (3, 0.4), (5, 1.4)],      # 안쪽이 내려간(듬직·의지)
}


def brows(c, t, color, kind='soft', lcx=26, rcx=54):
    """눈썹(80칸 좌표): t 는 눈썹 높이, 두 칸 굵기. 왼·오른쪽은 거울(안쪽 끝이 가운데 쪽)"""
    for cx, sgn in ((lcx, -1), (rcx, 1)):
        fline(c, [(cx + sgn * dx, t + dy) for dx, dy in BROW[kind]], color, 2)


# ================================================================ HUD 친구 13명

def dodam():
    """도담: 판 위에 팔을 포개고 턱을 괸 채 꾸벅꾸벅, 입에 문 지푸라기, 머리 위 Zzz"""
    c = canvas()
    fur = Tone('ffffff', 'ececf0', 'd2d3dc', '8c8e9a')
    horn = Tone('fff8e2', 'ecdcb8', 'cfba90', '7c6a48')
    apron = Tone('a2dc8e', '68b058', '4c8e44', '2a5626')
    inner = Tone('ffd6de', 'f8b6c6', 'e098ac')
    body(c, apron, rx=10)
    mirror_add(c, [(14, 13), (12.6, 7.6), (10, 4.2), (7.4, 3.6), (8.8, 7), (10.4, 12.6)], horn, shade=2, light=True)
    c.add(ellipse_pair(c, 3.8, 21.6, 4.6, 2.2), fur, shade=2)
    c.add(ellipse_pair(c, 3.4, 21.6, 2.4, 1.0), inner, shade=0)
    head(c, fur, rx=14.2, cy=22.4)
    tufts(c, fur)
    c.add(c.poly([(17, 30.6), (23, 30.6), (20, 35.4)]), Tone('ffffff', 'ffffff', 'e6e6ec'), shade=1)  # 턱수염
    # 포갠 두 팔(판 위에 가로로)
    c.add(c.capsule(8, 34.6, 17.6, 36.4, 2.3), fur, shade=2, light=True)
    c.add(c.capsule(32, 34.6, 22.4, 36.4, 2.3), fur, shade=2, light=True)
    paw(c, 17.6, 36.4, 2.4, fur, 1, 0.25)
    paw(c, 22.4, 36.4, 2.4, fur, -1, 0.25)
    sleepy_eyes(c, 23)
    brows(c, 44, '7a7a8c', 'flat')
    blush(c, 27, 5)
    mouth_smile(c, 28, '8a8a96')
    fline(c, [(46, 58), (52, 57), (58, 55), (62, 54)], 'f2cc50', 2)  # 입에 문 지푸라기
    c.fdot(63, 53, 'fff2a8')
    for i, (x, y, s) in enumerate(((60, 12, 5), (68, 6, 4), (74, 1, 3))):  # Zzz
        fline(c, [(x, y), (x + s, y), (x, y + s), (x + s, y + s)], '7a9ae0')
    for x0 in (9, 13):
        c.dot(x0, 7, 'b8a47c'); c.dot(m(x0), 7, 'b8a47c')
    K.shine(c, 10, 15)
    return c


def seoki():
    """석이: 곡괭이를 얼굴 옆에 세워 쥐고 한 손엔 반짝이는 광석, 안전모 램프, 무심한 반쯤 감은 눈과 땀 한 방울"""
    c = canvas()
    fur = Tone('e2b688', 'c4955f', 'a47848', '5e4024')
    snout = Tone('b88656', 'a07242', '845a32', '4a3018')
    helmet = Tone('fff2a8', 'f6cc3a', 'd4a426', '8a6410')
    vest = Tone('ffc884', 'f69632', 'd0721a', '7a3e06')
    gem = Tone('d8fbff', '7ae8f8', '3ab8d8', '1a5a7a')
    body(c, vest)
    c.add(c.rect(10, 36, 30, 37), Tone('ffffff', 'f2f6f8', 'cfd8de'), shade=0)
    c.add(ellipse_pair(c, 8.4, 12.8, 2.8, 2.4), fur)
    c.add(c.ellipse(AX, 22, 15.6, 11.2) | c.rect(6, 15, 34, 30), fur, shade=3, light=True)
    c.add(c.ellipse(AX, 27, 8.6, 4.6), snout, shade=2, light=True)
    c.add(c.ellipse(AX, 13.4, 11.6, 6.8) - c.rect(0, 14.4, W, H), helmet, shade=2, light=True)
    c.add(c.rect(6, 13, 34, 15.6), helmet, shade=1)
    c.add(c.rect(17, 6.6, 23, 11), Tone('ffffff', 'fffbe0', 'ece0a0', '8a7a3a'), shade=1)
    # 오른손: 얼굴 바깥에 곡괭이를 세워 쥠(자루 → 날 → 손 순서로 그려 손이 자루를 감쌈)
    c.add(c.capsule(37.6, 37.6, 37.6, 15.4, 0.8), WOOD, shade=1, light=True)
    c.add(c.poly([(32.8, 16.8), (35, 13.8), (37.6, 13), (40, 13.4), (40, 15.6), (37.6, 15.2), (35.4, 15.8), (33.4, 17.8)]), STEEL, shade=1, light=True)
    arm(c, 28, 33.4, 37.2, 29.8, fur, 2.2, 2.6)
    # 왼손: 광석 번쩍
    arm(c, 10, 33, 6, 26, fur, 2.2, 2.7)
    c.add(c.poly([(3, 23.6), (5, 19), (8.6, 18.6), (9.4, 22.6), (6.6, 24.4)]), gem, shade=1, light=True)
    sparkle(c, 9, 36, big=True); sparkle(c, 21, 34)
    K.cast(c, 16, 7, 33, '5e4024', 0.36)
    sleepy_eyes(c, 20)
    brows(c, 38.4, '2e1a0a', 'flat')
    blush(c, 25, 5, 0.5)
    lines(c, [(32, 50), (33, 50), (46, 50), (47, 50)], '4a2c14')  # 콧구멍
    mouth_flat(c, 29, '5a3818')
    fpaint(c, c.fellipse(63, 36, 1.6, 2.4), 'b8e2ff'); c.fdot(62, 34, 'ffffff')  # 땀
    c.fsoft([(x, 10) for x in range(34, 46)], 'fff4b0', 0.5)
    return c


def duri():
    """두리: 망치를 손에 쥐고 번쩍, 다른 손은 엄지 척, 웃는 눈 위 둥근 눈썹, 큰 분홍 코, 멜빵 바지"""
    c = canvas()
    fur = Tone('9a7a66', '725444', '583e32', '2c1e18')
    snout = Tone('ffdce2', 'f7aab6', 'de8898', '8a4454')
    helmet = Tone('fff2a8', 'f6cc3a', 'd4a426', '8a6410')
    shirt = Tone('fae8c4', 'e2c69a', 'c4a476', '6a5434')
    paw = Tone('ffdce2', 'f8b6c0', 'de94a0')
    body(c, shirt)
    c.add(c.rect(13, 34, 15, 40) | c.rect(25, 34, 27, 40), Tone('a06a44', '8a5636', '6a3e24'), shade=0)
    head(c, fur)
    c.add(c.ellipse(AX, 13, 12.6, 7.4) - c.rect(0, 14, W, H), helmet, shade=2, light=True)
    c.add(c.rect(5, 13, 35, 15.8), helmet, shade=1)
    c.add(c.rect(16.4, 6, 23.6, 11.6), Tone('ffffff', 'fbfbf4', 'e0e0d4', '6a6a60'), shade=1)
    c.add(c.ellipse(AX, 26.4, 4.8, 3.2), snout, shade=2, light=True)
    # 오른손: 망치를 쥐고 번쩍(얼굴 바깥, 자루 → 머리 → 손)
    c.add(c.capsule(36.8, 33, 36.8, 20.4, 0.85), WOOD, shade=1, light=True)
    c.add(c.rect(33.4, 15.2, 40, 20.4), STEEL, shade=2, light=True)
    c.add(c.rect(33.4, 15.2, 34.4, 20.4), Tone('ffffff', 'dfe4ec', 'b0b8c4'), shade=0)
    arm(c, 28, 33.4, 36.6, 28.4, paw, 2.2, 2.6)
    # 왼손: 엄지 척
    arm(c, 11, 33.4, 6.4, 29.6, paw, 2.2, 2.7)
    c.add(c.capsule(5.8, 28, 5.8, 24.6, 0.95), paw, shade=1, light=True)
    sparkle(c, 4, 44, 'ffd860', True); sparkle(c, 15, 42, 'fff2a8'); sparkle(c, 64, 24, 'ffffff'); sparkle(c, 62, 34, 'ffd860')
    K.cast(c, 16, 6, 34, '2c1e18', 0.4)
    happy_eyes(c, 20)
    brows(c, 39, '2c1e18', 'soft')
    lines(c, [(36, 50), (37, 50), (38, 50)], 'fff2f4')
    for x0, y0 in ((10, 50), (10, 56)):
        fline(c, [(x0, y0), (x0 + 6, y0 + 1)], 'd2bcae'); fline(c, [(80 - x0, y0), (74 - x0, y0 + 1)], 'd2bcae')
    mouth_open(c, 29)
    blush(c, 25, 5)
    return c


def deumjik():
    """듬직: 큰 나무 상자를 두 팔로 번쩍 든 일꾼, 목에 두른 수건, 투구·뿔, 굳센 눈썹, 이마의 땀 한 방울"""
    c = canvas()
    fur = Tone('f8ca92', 'e4a866', 'c68846', '7a4e24')
    muzzle = Tone('ffffff', 'fbf5ef', 'e8ddd2', '9a8a80')
    horn = Tone('ece6dc', 'cbc0b0', 'a29684', '5a5044')
    helm = Tone('948e86', '6e6860', '524c46', '2a2824')
    over = Tone('94bcf6', '5e8ed8', '4270b8', '23447a')
    towel = Tone('ffffff', 'f4f8fc', 'd6e2ee', '7a8a9a')
    body(c, Tone('5e5e68', '46464e', '323238'))
    c.add(c.rect(12, 34, 28, 40), over, shade=1)
    mirror_add(c, [(8, 16), (3.4, 14), (1.2, 9.4), (1.6, 6), (3.4, 7.4), (5, 11.4), (8.8, 12.4)], horn, shade=2, light=True)
    c.add(ellipse_pair(c, 4, 20, 3.8, 2.2), fur, shade=2)
    h = head(c, fur)
    tufts(c, fur)
    c.add(c.ellipse(AX, 14.6, 14.2, 6.6) & (h | c.rect(4, 10, 36, 16)), helm, shade=2, light=True)
    c.add(c.ellipse(AX, 27.4, 8.2, 4.4), muzzle, shade=2, light=True)
    # 목 수건
    c.add(c.capsule(12, 32.4, 28, 32.4, 1.2), towel, shade=1, light=True)
    # 두 팔로 든 큰 나무 상자(위로 건초가 삐죽): 팔 → 상자 → 상자 옆을 꽉 쥔 손
    for side in (-1, 1):
        c.add(c.capsule(AX + side * 9.6, 31.4, AX + side * 16.2, 33.4, 2.4), fur, shade=2, light=True, line=True)
    box = Tone('f2c88a', 'd49a58', 'a8743c', '4a2e14')
    c.add(c.rect(7, 31.6, 33, 40), box, shade=2, light=True)
    for side in (-1, 1):
        paw(c, AX + side * 13.8, 34.6, 2.6, fur, -side, 0.35)

    for x0, y0 in ((9, 12), (13, 10), (17, 9), (22, 9), (26, 10), (30, 12)):
        c.dot(x0, y0, 'd6bf86')
    for x in (30, 34, 46, 50):  # 수건 줄무늬
        c.fdot(x, 64, '8ab4e8')
    fline(c, [(18, 71), (62, 71)], '8a5a2a')  # 상자 널빤지 · 엇걸이 · 못
    fline(c, [(18, 73), (62, 79)], 'a8743c'); fline(c, [(18, 79), (62, 73)], 'a8743c')
    for x, y in ((18, 66), (61, 66), (18, 77), (61, 77)):
        c.fdot(x, y, 'fff0c8')
    for x in (22, 25, 29, 50, 54, 57):  # 건초
        fline(c, [(x, 63), (x + 1, 59)], 'f2d060')
    fpaint(c, c.fellipse(40, 68, 3.2, 2.6), 'b84a3a'); c.fdot(39, 67, 'ffb0a0')  # 상자 표시(빨간 사과)
    eyes(c, 22)
    brows(c, 40.6, '5a3418', 'bold')
    blush(c, 27, 5)
    lines(c, [(33, 55), (34, 55), (45, 55), (46, 55)], 'f2a2ae')
    mouth_smile(c, 29, 'a89a90')
    fpaint(c, c.fellipse(60, 33, 1.5, 2.2), 'b8e2ff'); c.fdot(59, 32, 'ffffff')  # 땀
    return c


def hotang():
    """호탕: 손을 번쩍 흔들며 껄껄 웃음(웃는 눈·크게 벌린 입·치켜든 눈썹), 다른 손은 판 위에"""
    c = canvas()
    fur = Tone('d6dae2', 'aeb4c0', '8e94a2', '4a4e5a')
    white = Tone('ffffff', 'f7f8fb', 'e0e3ea', '9ca0aa')
    coat = Tone('f6826e', 'da4c3e', 'b2362a', '6a1810')
    inner = Tone('ffd4da', 'f6aeb8', 'da929e')
    body(c, coat)
    c.add(c.ellipse(AX, 33.6, 8, 2), white, shade=1)
    mirror_add(c, [(6.6, 16), (6, 3.4), (8, 2.2), (15.4, 10.6)], fur, shade=2, light=True)
    mirror_add(c, [(8.4, 12), (8, 6.2), (12.4, 10.6)], inner, shade=0)
    head(c, fur)
    tuft = [(4.6, 23), (2, 26.6), (6, 26), (5, 29.6), (9, 28)]
    c.add(c.poly(tuft) | c.poly(c.mirror(tuft, AX)) | ellipse_pair(c, 11.4, 26.4, 5.6, 4.2) | c.ellipse(AX, 27.4, 5.4, 4), white, shade=1)
    crown_tuft(c, fur)
    # 왼손: 판 위에 툭
    paw(c, 10.6, 35.2, 2.7, fur, 0.2, 1)
    # 오른손: 얼굴 옆에서 반갑게 흔들기(손바닥이 보이게) + 흔들림 선
    arm(c, 28.6, 32.6, 34.8, 23.6, fur, 2.1, 2.9, palm=True)
    fline(c, [(76, 36), (78, 40)], '8e94a2'); fline(c, [(74, 30), (77, 28)], '8e94a2')
    happy_eyes(c, 20)
    brows(c, 38, '4a4e5a', 'up')
    nose(c, 24, '3a3440')
    mouth_open(c, 26)
    blush(c, 25, 5, 0.6)
    for y in (70, 74):
        c.fdot(32, y, 'f6ca3a'); c.fdot(47, y, 'f6ca3a')
    return c


def bareum():
    """바름: 두 손으로 설계도를 활짝 펼쳐 보임, 귀에 꽂은 연필, 뒤로 줄무늬 꼬리, 흰 눈썹"""
    c = canvas()
    fur = Tone('dcd6cc', 'bab3a8', '9a9286', '544c44')
    mask = Tone('7e746c', '645a52', '4c443e', '24201c')
    white = Tone('ffffff', 'fbf9f5', 'e8e2d8')
    cap = Tone('c0bcb6', '96928c', '74706a', '38342e')
    paper = Tone('e8f2ff', 'a8c8f0', '7aa0d8', '2a4a80')
    c.add(c.ellipse_r(35, 27, 2.6, 6.6, -28), fur, shade=2, light=True)  # 줄무늬 꼬리
    for t in (0.35, 0.65):
        c.add(c.ellipse_r(35 + 2.2 * (t - 0.5) * 2, 27 - 4.4 * (t - 0.5) * 2, 2.4, 0.9, -28) & c.ellipse_r(35, 27, 2.6, 6.6, -28), mask, shade=0)
    body(c, Tone('ffffff', 'f4f4f6', 'd8dae0'))
    c.add(c.rect(12, 34, 28, 40), Tone('7a6050', '5e4838', '46342a'), shade=1)
    c.add(ellipse_pair(c, 7.4, 13, 3.8, 3.8), fur, shade=2)
    c.add(ellipse_pair(c, 7.4, 13.4, 1.9, 1.9), mask, shade=0)
    head(c, fur)
    tufts(c, white)
    c.add(ellipse_pair(c, 11.8, 22.4, 5.6, 3.8) | c.rect(10, 20, 30, 23.6), mask, shade=1)
    c.add(c.ellipse(AX, 28, 6.6, 3.8), white, shade=1)
    c.add(ellipse_pair(c, 11.4, 18, 3.2, 1), white, shade=0)
    c.add(c.ellipse(AX, 12.6, 12.6, 4) - c.rect(0, 13.6, W, H), cap, shade=2, light=True)
    c.add(c.rect(6, 12.6, 34, 14.8), cap, shade=1)
    c.add(c.capsule(31, 13.4, 36.6, 7.4, 0.7), Tone('fff09a', 'f6c945', 'c9961c'), shade=1)  # 귀에 꽂은 연필
    c.add(c.ellipse(36.8, 7.2, 0.8, 0.8), Tone('ffb0b0', 'f07070', 'c04040'), shade=0)
    # 펼친 설계도(두 손으로)
    c.add(c.rect(3, 31, 37, 38.6), paper, shade=1, light=True)
    c.add(ellipse_pair(c, 3.4, 34.8, 1.6, 4.2), paper, shade=1, light=True)  # 말린 양끝
    for x in range(10, 72, 6):
        fline(c, [(x, 63), (x, 76)], 'd0e4ff')
    for y in (66, 70, 74):
        fline(c, [(10, y), (70, y)], 'd0e4ff')
    fline(c, [(22, 74), (22, 66), (40, 64), (58, 66), (58, 74)], '2a4a80')  # 집 그림
    fline(c, [(34, 74), (34, 69), (40, 69), (40, 74)], '2a4a80')
    paw(c, 6.4, 33.4, 2.5, white, 0.3, 1); paw(c, 33.6, 33.4, 2.5, white, -0.3, 1)
    eyes(c, 20, color='18121a')
    lines(c, [(36, 50), (37, 50), (38, 50), (39, 50), (40, 50), (41, 50), (42, 50), (43, 50), (37, 51), (38, 51), (39, 51), (40, 51),
              (41, 51), (42, 51), (38, 52), (39, 52), (40, 52), (41, 52)], '1e1820')
    c.fdot(37, 50, '6a6068')
    mouth_w(c, 28, '7a7068')
    blush(c, 25, 4, 0.5)
    return c


def nuri():
    """누리: 낚싯대 끝에 물고기가 파닥, 부리 주머니에서 물고기 꼬리가 삐죽, 빨간 스카프가 휘날림"""
    c = canvas()
    feather = Tone('ffffff', 'f3f3f5', 'd8d8de', '8a8a92')
    beak = Tone('ffd27e', 'f8a23a', 'd87a1e', '7a3e08')
    pouch = Tone('ffe6ac', 'f8c466', 'dc9c3c', '7a4e12')
    scarf = Tone('ff8e7e', 'e2524a', 'ba362e', '6a1810')
    fish = Tone('d8f0ff', '8ac8f0', '5a98d0', '2a4a80')
    body(c, Tone('6ccdd8', '32a2b4', '1e7a8a', '0e4048'))
    c.add(c.poly([(10.6, 32.6), (29.4, 32.6), (26.4, 36), (20, 37.4), (13.6, 36)]), scarf, shade=1)
    c.add(c.poly([(10, 33), (3, 30), (2.4, 33.6), (7, 35)]), scarf, shade=1, light=True)  # 휘날리는 끝
    head(c, feather, ry=11.4)
    crown_tuft(c, feather)
    c.add(c.ellipse(AX, 13.6, 13.4, 3.2) & c.ellipse(AX, 21.6, 15.4, 11.4), Tone('4a4a52', '2a2a32', '1a1a20'), shade=0)
    c.add(c.ellipse(AX, 29.4, 7.4, 4.6), pouch, shade=2, light=True)
    c.add(c.poly([(24.4, 30), (27.6, 28.4), (27.4, 31.6)]), fish, shade=0)  # 주머니 속 물고기 꼬리
    c.add(c.ellipse(AX, 25.4, 8.6, 3.2), beak, shade=2, light=True)
    c.add(c.line([(36.6, 34), (37.4, 18), (38.6, 3)]), Tone('d8dce4', 'aab0ba', '7a808c'), shade=0)  # 낚싯대
    c.add(c.line([(38.6, 3), (39.4, 9)]), Tone('ffffff', 'e0e0e0', 'b0b0b0'), shade=0)  # 낚싯줄
    c.add(c.ellipse_r(38.6, 12, 1.4, 2.8, 12), fish, shade=1, light=True)  # 걸린 물고기
    c.add(c.poly([(38, 14.2), (39.8, 17), (37.4, 16.6)]), fish, shade=0)
    arm(c, 28, 34, 36.2, 30.6, feather, 2.0, 2.4)  # 낚싯대 쥔 손
    paw(c, 10.6, 35.2, 2.7, feather, 0.2, 1)
    eyes(c, 16)
    brows(c, 28, 'f0f0f6', 'up')
    blush(c, 21, 4)
    fline(c, [(26, 54), (54, 54)], 'c4681c')
    c.fdot(34, 50, 'c4681c'); c.fdot(45, 50, 'c4681c')
    lines(c, [(28, 48), (29, 48), (30, 48)], 'ffecc4')
    for x, y in ((70, 34), (73, 40), (79, 30)):
        c.fdot(x, y, 'cfe6fa')
    return c


def kai():
    """카이: 바나나를 번쩍 들고 메롱 · 윙크, 동그랗게 말린 꼬리, 이마의 금테 고글"""
    c = canvas()
    fur = Tone('ba8458', '9a6642', '7a4c2e', '42261a')
    face = Tone('fff2e0', 'f6d6ae', 'ddb68a', '8a6440')
    gog = Tone('ffea94', 'e4ba42', 'ba8e24', '6a4c0a')
    lens = Tone('ffffff', 'eaf6fa', 'c8dee6')
    banana = Tone('fff8b8', 'ffe04a', 'e0b020', '7a5a10')
    tail = (c.ellipse(36, 30, 3.6, 3.6) - c.ellipse(36, 30, 2.0, 2.0)) | c.capsule(30, 34, 34, 32.6, 1.0)
    c.add(tail, fur, shade=1, light=True)  # 말린 꼬리
    body(c, Tone('a2d478', '6ea84e', '50863a', '284618'))
    c.add(c.poly([(17, 32.6), (23, 32.6), (20, 36.4)]), Tone('f8f6f0', 'ece8de', 'cfc8b8'), shade=0)
    c.add(ellipse_pair(c, 3.4, 22.4, 4.4, 4.6), fur, shade=2)
    c.add(ellipse_pair(c, 3.4, 22.4, 2.4, 2.8), face, shade=0)
    head(c, fur, rx=14.4)
    crown_tuft(c, fur)
    c.add(ellipse_pair(c, 14, 22.6, 5.4, 5) | c.ellipse(AX, 27.2, 9, 4.8), face, shade=2, light=True)
    c.add(ellipse_pair(c, 12.4, 14.4, 3.8, 3.2), gog, shade=1)
    c.add(ellipse_pair(c, 12.4, 14.4, 2.2, 1.7), lens, shade=0)
    c.add(c.rect(16, 13.6, 24, 15.2), Tone('6a4a34', '4e3424', '362418'), shade=0)
    # 왼손: 바나나 번쩍
    arm(c, 10, 33, 6, 25.6, fur, 2.2, 2.6)
    ban = c.ellipse_r(5, 19.4, 1.8, 5.6, -25) - c.ellipse_r(6.4, 18.6, 1.4, 5.0, -25)
    c.add(ban | c.ellipse_r(5.4, 19.6, 1.4, 5.0, -25), banana, shade=1, light=True)
    paw(c, 28.8, 35.2, 2.7, fur, -0.2, 1)
    lines(c, [(22, 26), (23, 26), (54, 26), (55, 26)], 'ffffff')
    wink(c, 21, 10, 'left')
    blush(c, 26, 5)
    lines(c, [(38, 50), (39, 50), (40, 50), (41, 50)], '8a5a3a')
    mouth_tongue(c, 27, '7a4430')
    return c


def roni():
    """로니: 렌치를 번쩍 들어 보이고 다른 손은 판 위에, 용접 고글을 이마에, 과묵한 반쯤 감은 눈, 가죽 앞치마"""
    c = canvas()
    fur = Tone('745e52', '54423a', '3e302a', '1a1412')
    ear = Tone('d08272', 'ae6050', '884236', '4a1e14')
    patch = Tone('ffffff', 'f2f2f4', 'd6d8de')
    muzzle = Tone('d6a882', 'b88a66', '9a6c4a', '4a2e1e')
    gog = Tone('5e5e68', '36363e', '24242a', '000000')
    body(c, Tone('ffffff', 'f2f2f4', 'd4d6dc'))
    c.add(c.rect(12, 34, 28, 40), Tone('b48e70', '906c52', '6e5038', '30201a'), shade=1)
    c.add(ellipse_pair(c, 7.4, 12.6, 4, 4), ear, shade=2, light=True)
    c.add(ellipse_pair(c, 7.4, 12.8, 2, 2), Tone('e0a090', 'c0806e', 'a0624e'), shade=0)
    h = head(c, fur)
    c.add(ellipse_pair(c, 12, 23, 4.4, 3.4), patch, shade=1)
    c.add(c.rect(4, 13, 36, 16) & (h | c.rect(4, 12, 36, 17)), gog, shade=0)
    c.add(ellipse_pair(c, 12.6, 14.4, 3.8, 3), gog, shade=1)
    c.add(c.ellipse(AX, 27.6, 6, 3.8), muzzle, shade=2, light=True)
    # 오른손: 렌치를 얼굴 바깥으로 들어 보임(렌치 → 손) / 왼손: 판 위에 툭
    c.add(c.capsule(36.6, 30, 37.2, 18.4, 0.9), STEEL, shade=1, light=True)
    c.add(c.ellipse(37.2, 16.6, 2.4, 2.4) - c.ellipse(37.8, 15.4, 1.1, 1.3), STEEL, shade=1, light=True)
    arm(c, 28.4, 33.4, 36.4, 28.8, fur, 2.3, 2.6)
    paw(c, 10.4, 35.4, 2.7, fur, 0.2, 1)
    lines(c, [(22, 26), (23, 26), (22, 27), (52, 26), (53, 26), (52, 27)], '9a9aa8')
    sleepy_eyes(c, 21)
    K.nose(c, 25, '140e10')
    mouth_flat(c, 29, '6a4632')
    blush(c, 26, 4, 0.5)
    sparkle(c, 66, 22, 'ffd860'); sparkle(c, 64, 30, 'ffb030')
    return c


def milky():
    """밀키: 국자로 맛보며 혀를 날름, 다른 손엔 김이 나는 냄비, 키 큰 요리사 모자"""
    c = canvas()
    fur = Tone('ffffff', 'f7f5f2', 'ddd7d0', '9a928a')
    spot = Tone('5a5a66', '3c3c46', '2a2a32', '16161a')
    muzzle = Tone('ffdada', 'f7b2b2', 'de8e8e', '8a4a4c')
    hat = Tone('ffffff', 'f9f9f9', 'e0e0e4', '9a9aa2')
    pot = Tone('f0f4fa', 'c0c8d4', '8a94a4', '3a4250')
    body(c, Tone('ffffff', 'f6f6f6', 'dcdce0'))
    c.add(c.poly([(12, 32.6), (28, 32.6), (25, 36), (20, 37.4), (15, 36)]), Tone('ff8e7e', 'e2524a', 'ba362e', '6a1810'), shade=1)
    c.add(c.ellipse(3.6, 19.6, 4.4, 2.2), fur, shade=2)
    c.add(c.ellipse(36.4, 19.6, 4.4, 2.2), spot, shade=2)
    c.add(ellipse_pair(c, 9, 13.4, 1.8, 2.4), Tone('fffaea', 'efe2c0', 'cfbf98'), shade=1)
    h = head(c, fur)
    tufts(c, fur)
    c.add(c.ellipse(28.6, 18.6, 5.4, 4.8) & h, spot, shade=0)
    c.add(c.ellipse(AX, 28.2, 8.4, 4.4), muzzle, shade=2, light=True)
    c.add(c.ellipse(14.4, 3.6, 4.4, 3.6) | c.ellipse(AX, 2.6, 5, 3.6) | c.ellipse(25.6, 3.6, 4.4, 3.6) | c.rect(11.6, 4, 28.4, 11.6), hat, shade=2, light=True)
    c.add(c.rect(11, 9.6, 29, 11.8), hat, shade=1)
    # 오른손: 냄비(김) / 왼손: 국자를 입가로
    c.add(c.rect(27, 31, 38, 38) | c.ellipse(32.5, 31, 5.8, 1.4), pot, shade=2, light=True)
    c.add(c.ellipse(32.5, 31, 4.6, 1.0), Tone('ffd0a0', 'f0a050', 'c87830'), shade=0)  # 수프
    paw(c, 28.2, 35, 2.5, fur, 1, 0.3)
    arm(c, 10, 34, 9, 29.4, fur, 2.2, 2.4)
    c.add(c.capsule(9, 29.4, 15.6, 30, 0.6), pot, shade=0)
    c.add(c.ellipse(16.6, 30, 1.8, 1.2), pot, shade=1)
    for x0 in (60, 66, 72):
        fline(c, [(x0, 58), (x0 - 2, 54), (x0, 50), (x0 - 2, 46)], 'f0f0f6')
    c.fdot(9, 3, 'e8e8ec'); c.fdot(16, 4, 'e8e8ec')
    happy_eyes(c, 19)
    lines(c, [(30, 54), (31, 54), (30, 55), (31, 55), (48, 54), (49, 54), (48, 55), (49, 55)], 'b05a62')
    mouth_tongue(c, 29, '8a4a4c')
    blush(c, 23, 4)
    return c


def fix():
    """픽스: 두 날개를 머리 옆으로 번쩍 펼친 "짜잔!", 삐죽 머리깃에 꽂은 보라 깃, 자신만만 눈썹"""
    c = canvas()
    white = Tone('ffffff', 'f5f3ef', 'ddd9d0', '8c867a')
    beak = Tone('ffea90', 'f6c03c', 'd8981e', '7a4a06')
    plume = Tone('f0dcff', 'c49eee', '9c72d0', '4a2a7a')
    wing = Tone('d8bea4', 'b4967a', '8a6e58', '3a2a20')
    # 활짝 편 날개: 깃털 네 장이 부채처럼(아래 → 위), 깃털마다 밝은 끝
    tip = Tone('fffaf0', 'f6eedf', 'dccfb8', '8a7a66')
    wing2 = Tone('c8a888', 'a07e60', '7a5c44', '3a2a20')
    for side in (1, -1):  # 두 날개를 머리 옆으로 번쩍 "짜잔!"(뿌리는 머리 뒤에 숨고 깃은 머리 밖으로)
        bx, by = AX + side * 13, 27
        for k, (ang, ln, r) in enumerate(((96, 11.6, 2.2), (74, 11.8, 2.3), (52, 9.6, 2.2), (30, 7.4, 2.0))):
            a = math.radians(ang)
            ex, ey = bx + side * ln * math.cos(a), by - ln * math.sin(a)
            c.add(c.capsule(bx, by, ex, ey, r), wing2 if k % 2 else wing, shade=2, light=True, line=True)
            c.add(c.ellipse(ex + side * 0.5 * math.cos(a), ey - 0.5 * math.sin(a), r * 0.8, r * 0.8), tip, shade=1, line=True)
    body(c, Tone('86705e', '624e42', '483a30', '1a1210'))
    c.add(c.poly([(10, 34), (30, 39.4), (28.4, 40), (9, 35.4)]), Tone('ffe08a', 'e6b030', 'b88418'), shade=0)
    head(c, white)
    c.add(c.poly([(23.4, 8.4), (27.4, 0.6), (29.4, 0.6), (26.6, 9.4)]), plume, shade=2, light=True)  # 머리깃에 꽂은 보라 깃
    c.add(c.poly([(12, 11.6), (14.6, 6.6), (17, 10.6), (20, 5.4), (23, 10.6), (25.4, 6.6), (28, 11.6)]), white, shade=1)
    c.add(c.poly([(13.6, 24.6), (26.4, 24.6), (27.4, 28), (24.4, 32.4), (21.6, 33.6), (22.4, 30), (13, 27.4)]), beak, shade=2, light=True)
    eyes(c, 18)
    fline(c, [(18, 35), (21, 33), (27, 33), (30, 34)], '8a5a38', 2); fline(c, [(50, 34), (53, 33), (59, 33), (62, 35)], '8a5a38', 2)
    lines(c, [(12, 48), (14, 48), (16, 48), (64, 48), (66, 48), (68, 48)], 'e05a4a')
    c.fdot(46, 54, '7a4a06'); c.fdot(47, 54, '7a4a06')
    sparkle(c, 8, 4, 'ffb03a', True); sparkle(c, 72, 4, 'fff0a0', True)
    return c


def garam():
    """가람: 한 손엔 가위, 한 손엔 실타래, 목에 건 줄자, 복슬 털 머리와 빨간 꽃, 부드러운 웃는 눈"""
    c = canvas()
    wool = Tone('ffffff', 'f9f7f3', 'e4dfd6', 'a29c92')
    face = Tone('f6f4f0', 'e2ded8', 'cac4bc', '7a746c')
    horn = Tone('e6b07a', 'c6864c', '9a6034', '5c3519')
    flower = Tone('ff94a0', 'e44650', 'b42c36', '6a1018')
    thread = Tone('ffc8e0', 'ff8fc0', 'e05a98', '7a1a48')
    tape = Tone('fff8c0', 'ffe066', 'e0b020', '7a5a10')
    body(c, Tone('ffffff', 'f4f4f6', 'd8dae0'))
    c.add(c.rect(12, 34, 28, 40), Tone('c8926a', 'a8704c', '845436', '42261a'), shade=1)
    fluff = set()
    for cx, cy, r in [(7, 16.6, 4.6), (10.4, 11.4, 4.8), (15.6, 9, 4.8), (24.4, 9, 4.8), (29.6, 11.4, 4.8), (33, 16.6, 4.6),
                      (6, 22.6, 4), (34, 22.6, 4), (AX, 8.4, 4.6)]:
        fluff |= c.ellipse(cx, cy, r, r)
    c.add(fluff, wool, shade=2, light=True)
    c.add(c.ellipse(AX, 23.4, 12, 9.8), face, shade=3, light=True)
    c.add(ellipse_pair(c, 13.6, 15.6, 4.2, 2.6) | c.ellipse(AX, 15, 4.2, 2.8), wool, shade=1)
    c.add(c.ellipse(34.4, 18, 3.4, 4), horn, shade=2, light=True)
    c.add(c.ellipse(31, 9.4, 3.2, 3), flower, shade=2, light=True)
    # 목에 건 줄자(양쪽으로 늘어짐)
    c.add(c.capsule(13, 32.6, 27, 32.6, 0.8) | c.capsule(13, 32.6, 12, 37.6, 0.8) | c.capsule(27, 32.6, 28, 38, 0.8), tape, shade=0)
    # 왼손 가위 · 오른손 실타래
    arm(c, 10, 34, 5.6, 28, wool, 2.2, 2.6)
    c.add(c.capsule(4, 26, 1.6, 20, 0.6) | c.capsule(5.6, 26, 5, 19.6, 0.6), STEEL, shade=1, light=True)
    c.add(c.ellipse(4, 27.4, 1.4, 1.2) | c.ellipse(6.6, 27.6, 1.4, 1.2), Tone('ffb0b0', 'f06060', 'c03030'), shade=1)
    c.add(c.ellipse(32.4, 32, 3.4, 3.2), thread, shade=2, light=True)
    paw(c, 29.6, 34.8, 2.4, wool, 1, -0.2)
    fline(c, [(60, 60), (66, 62), (72, 68), (74, 76)], 'e05a98')
    for x, y in ((30, 65), (36, 65), (44, 65), (50, 65)):
        c.fdot(x, y, 'c08a10')
    c.dot_all([(30, 9), (31, 9), (30, 10), (31, 10)], 'ffe066'); c.dot(30, 9, 'fff4b0')
    happy_eyes(c, 21, 10, '5a4c48')
    blush(c, 26, 5)
    lines(c, [(39, 52), (40, 52)], '5a4c48')
    mouth_w(c, 27, '9a8a82')
    return c


def lua():
    """루아: 한 손을 반갑게 흔들고 한 손엔 초승달 등불, 뿔에 작은 꽃, 나비넥타이, 초록 망토, 다정한 아치 눈썹"""
    c = canvas()
    fur = Tone('cc9468', 'ae7a50', '8e5e38', '4a2c16')
    antler = Tone('b89270', '946e50', '72523a', '362416')
    inner = Tone('f8e0c6', 'e8c6a4', 'caa480')
    lamp = Tone('fffbe0', 'fff0a0', 'f2d070', '8a6a1a')
    lant = [(13.4, 11.4), (11.4, 6.6), (8, 5.6), (5.4, 1.2), (7.2, 0.4), (9.2, 3.4), (11.2, 3.6), (10.6, 0.2), (12.8, 0), (13.8, 4), (15.4, 10.2)]
    c.add(c.poly(lant), antler, shade=2, light=True)
    c.add(c.poly(c.mirror(lant, AX)), antler, shade=2, light=True)
    body(c, Tone('96cc72', '62a04a', '4a8036', '22421a'))
    c.add(c.ellipse(AX, 33.8, 4.6, 1.6), Tone('ffffff', 'f8f6f0', 'e0dccf'), shade=0)
    c.add(c.poly([(17, 33), (20, 34.4), (17, 35.8)]) | c.poly([(23, 33), (20, 34.4), (23, 35.8)]), Tone('ff9ab0', 'e04a6a', 'b02a4a'), shade=1)
    c.add(ellipse_pair(c, 3.4, 19.4, 4.6, 2.4), fur, shade=2)
    c.add(ellipse_pair(c, 3.2, 19.4, 2.6, 1.1), inner, shade=0)
    head(c, fur)
    tufts(c, fur)
    c.add(c.ellipse(AX, 27.4, 4.6, 3.2), Tone('70483a', '523424', '3e2618', '1a0c06'), shade=1)
    # 왼손: 반갑게 흔들기(편 손바닥 + 흔들림 선)
    arm(c, 10.6, 33, 4.6, 26.6, fur, 2.2, 2.8)
    c.add(c.ellipse(4.6, 26.4, 1.5, 1.4), inner, shade=0)
    fline(c, [(1, 46), (0, 50)], 'caa480'); fline(c, [(4, 43), (2, 41)], 'caa480'); fline(c, [(15, 46), (17, 44)], 'caa480')
    # 오른손: 초승달 등불(손잡이 고리 → 금 뚜껑 → 빛나는 유리)
    for x, y in c.fellipse(68, 70, 11, 10):
        d = ((x + 0.5 - 68) / 11) ** 2 + ((y + 0.5 - 70) / 10) ** 2
        c.fsoft([(x, y)], 'fff4b0', 0.4 * (1 - d))
    c.add(c.line([(32, 30), (33.6, 31.6)]), GOLD, shade=0)
    c.add(c.poly([(31.6, 32.2), (35.6, 32.2), (34.8, 31), (32.4, 31)]) | c.rect(31.4, 37.4, 35.8, 38.4), GOLD, shade=1, light=True)
    c.add(c.rect(31.8, 32.2, 35.4, 37.4), lamp, shade=1, light=True)
    fpaint(c, c.fellipse(67, 69, 2.6, 3.2) - c.fellipse(68.6, 68, 2.2, 2.8), 'f2b830')  # 초승달
    c.fdot(65, 66, 'ffffff')
    arm(c, 28, 33, 31.6, 29.6, fur, 2.0, 2.4)
    for x, y in ((14, 6), (66, 6)):  # 뿔의 작은 꽃
        fpaint(c, c.fellipse(x, y, 2.2, 2.2), 'ffd0e0'); c.fdot(x, y, 'ffe066')
    lines(c, [(32, 26), (38, 24), (40, 24), (46, 26), (36, 30), (42, 30)], 'fff2e0')
    eyes(c, 20)
    lines(c, [(19, 41), (17, 39), (61, 41), (63, 39)], EYE)
    brows(c, 35, '6a4228', 'soft')
    blush(c, 25, 5)
    lines(c, [(36, 52), (38, 52)], 'a07858')
    mouth_smile(c, 29, '7a4a34')
    sparkle(c, 74, 52, 'ffffff')
    return c


MASCOTS = {'dodam': dodam, 'seoki': seoki, 'duri': duri, 'deumjik': deumjik, 'hotang': hotang, 'bareum': bareum, 'nuri': nuri,
           'kai': kai, 'roni': roni, 'milky': milky, 'fix': fix, 'garam': garam, 'lua': lua}


# ================================================================ 판 · 제목 줄 13벌


def head_tile(h, fn):
    """제목 줄 반복 칸(20~59)에 20칸 무늬"""
    for x in range(HB, PW - HB):
        fn(x, (x - HB) % 20)


def set_dodam():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '2e4a24', 'c8f0a8', '86c06a', '4f8a3a', 'f6fbef', 'ffffff', 'e4efd6')
    for x in range(B, PW - B):
        k = (x - B) % 16
        if k in (0, 3, 9):
            p.px(x, P1(), '5fa845'); p.px(x + 1, P1() + 1, '3f7f32')
        if k == 6:
            p.px(x, P1() + 2, '7fc85a')
    def sprout(t, x, y):
        t.oval(x + 8, y + 12, 5, 3, 'a8774a', '5a3a1e')
        t.d.line([(x + 8, y + 11), (x + 8, y + 5)], fill=hexc('4c8e3a'), width=1)
        t.oval(x + 5.5, y + 5, 3, 1.8, '7fc85a', '3f7f32'); t.oval(x + 10.5, y + 4, 3, 1.8, '8fd86a', '3f7f32')
    def wheat(t, x, y):
        for dx in (5, 8, 11):
            t.d.line([(x + dx, y + 14), (x + dx, y + 4)], fill=hexc('c8a040'))
            for dy in (4, 6, 8):
                t.oval(x + dx, y + dy, 1, 1.4, 'f2cc50')
        t.rect(x + 4, y + 10, x + 13, y + 12, 'c84a3a')
    corners4(p, (sprout, wheat, wheat, sprout))
    band(h, '4a2e18', 'a8774a', 'c9935e', '7a5232')
    head_tile(h, lambda x, k: (h.px(x, O + 5, '946640') if k in (2, 3, 4, 12, 13) else None, h.px(x, HH - 6, '946640') if k in (7, 8, 16) else None,
                                (h.oval(x, 3, 1.6, 2.2, 'f2cc50') if k == 10 else None)))
    for x in (O + 5, PW - O - 6):
        h.px(x, O + 5, 'e6e0d0'); h.px(x, HH - 6, 'e6e0d0')
    h.d.text((PW - 16, 0), 'z', fill=hexc('7a9ae0')); h.d.text((PW - 10, -3), 'z', fill=hexc('9ab6f0'))
    sprout(h, 0, -3)
    return p.im, h.im


def P1():
    return PW - O


def set_seoki():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '111216', 'a8adb6', '8a8f98', '5a5e66', '26282d', '3a3d44', '1c1d21')
    for x in range(B, PW - B):
        k = (x - B) % 24
        if k in (4, 5, 17):
            p.px(x, O + 4, 'c8ccd4'); p.px(O + 4, x, 'c8ccd4')
        if k in (10, 11, 12):
            p.px(x, P1() - 5, '45484f'); p.px(P1() - 5, x, '45484f')
    def crystal(t, x, y, col='7ae8f8', dk='1a5a7a'):
        t.d.polygon([(x + 8, y + 1), (x + 12, y + 6), (x + 10, y + 14), (x + 6, y + 14), (x + 4, y + 6)], fill=hexc(col), outline=hexc(dk))
        t.d.line([(x + 8, y + 2), (x + 8, y + 13)], fill=hexc('ffffff'))
    corners4(p, (lambda t, x, y: crystal(t, x, y), lambda t, x, y: crystal(t, x, y, 'ffd860', '7a5a10'),
                 lambda t, x, y: crystal(t, x, y, 'ff8ab0', '7a1a48'), lambda t, x, y: crystal(t, x, y)))
    band(h, '111216', 'e87a1a', 'f6a24a', 'b85a0a')
    for x in range(O + 2, PW - O - 2):
        for y in (O + 2, O + 3, O + 4):
            h.px(x, y, 'f2c230' if ((x + y) // 5) % 2 == 0 else '2a2a2e')
    h.oval(PW - 9, 6, 4.4, 4.4, 'fff4b0', '8a6410'); h.oval(PW - 9, 6, 2.4, 2.4, 'ffffff')  # 램프
    crystal(h, 0, -2)
    return p.im, h.im


def set_duri():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '120c0a', '8a8e96', '7a7e86', '4a4e56', '2b2220', '3e3230', '1e1716')
    for x in range(B, PW - B):
        k = (x - B) % 16
        if k == 8:
            for xx, yy in ((x, O + 4), (x, P1() - 5), (O + 4, x), (P1() - 5, x)):
                p.oval(xx, yy, 1.4, 1.4, 'c8ccd4'); p.px(xx - 1, yy - 1, 'ffffff')
        if k in (3, 4):
            p.px(x, P1() + 1, 'ff9a3a'); p.px(x, P1() + 2, 'ffd860')  # 아래 밖 잉걸
    def anvil(t, x, y):
        t.d.polygon([(x + 2, y + 5), (x + 14, y + 5), (x + 12, y + 8), (x + 10, y + 8), (x + 11, y + 13), (x + 5, y + 13), (x + 6, y + 8), (x + 4, y + 8)],
                    fill=hexc('6a6e78'), outline=hexc('2a2c30'))
        t.px(x + 4, y + 6, 'c8ccd4')
    def flame(t, x, y):
        t.d.polygon([(x + 8, y + 1), (x + 13, y + 9), (x + 11, y + 14), (x + 5, y + 14), (x + 3, y + 9)], fill=hexc('ff7a2a'), outline=hexc('7a2008'))
        t.d.polygon([(x + 8, y + 6), (x + 10, y + 11), (x + 8, y + 13), (x + 6, y + 11)], fill=hexc('ffe066'))
    corners4(p, (anvil, flame, flame, anvil))
    band(h, '120c0a', 'd4602a', 'f2a040', '200602')
    grad = ['f2a040', 'ec9038', 'e47e32', 'dc6c2c', 'd45e28', 'c85020', 'bc4418', 'b03a14', 'a43212', '982c10', '8c260e', '80200c',
            '761c0a', '6c1a08', '621806', '581604', '4e1404', '461204', '3e1004', '360e04', '300c02', '2a0a02', '240802', '200602']
    for i, col in enumerate(grad):
        h.rect(O + 2, O + 2 + i, PW - O - 2, O + 3 + i, col)
    head_tile(h, lambda x, k: (h.px(x, 1 + k % 3, 'ffd860') if k in (3, 13) else None, h.px(x, 0, 'ffb030') if k == 8 else None))
    flame(h, PW - 16, -2)
    return p.im, h.im


def set_deumjik():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '3a2412', 'b48a5c', '8a6040', '5a3a20', 'fbf5ea', 'ffffff', 'efe2cc')
    for x in range(B, PW - B):
        k = (x - B) % 48
        if k in (2, 3, 4, 5, 18, 19, 30, 31, 32):
            p.px(x, O + 4, '5a3a20'); p.px(O + 4, x, '5a3a20')
        if k in (9, 10, 24, 25, 40, 41, 42):
            p.px(x, P1() - 5, '3e2614'); p.px(P1() - 5, x, '3e2614')
    def stump(t, x, y):
        t.oval(x + 8, y + 8, 6.5, 6.5, 'd9b07a', '5a3a20'); t.oval(x + 8, y + 8, 4.2, 4.2, 'c99a62'); t.oval(x + 8, y + 8, 2, 2, 'b98a52')
        t.px(x + 8, y + 8, '7a5232')
    def leaf(t, x, y):
        t.d.polygon([(x + 3, y + 12), (x + 8, y + 3), (x + 13, y + 6), (x + 10, y + 13)], fill=hexc('e8a03a'), outline=hexc('8a4a14'))
        t.d.line([(x + 4, y + 12), (x + 11, y + 6)], fill=hexc('b86a1e'))
    corners4(p, (stump, leaf, leaf, stump))
    band(h, '3a1410', 'b8322c', 'd04a40', '8e2620')
    for x in range(O + 2, PW - O - 2):
        for y in (O + 2, O + 3, HH - 4, HH - 3):
            if (x // 2 + y // 2) % 2 == 0:
                h.px(x, y, '6e1a17')
    head_tile(h, lambda x, k: h.oval(x, 3, 2, 1.6, 'e8a03a' if k == 5 else '6fbf52', '8a4a14' if k == 5 else '3f7f32') if k in (5, 15) else None)
    stump(h, -1, -2)
    return p.im, h.im


def set_hotang():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '0e0a08', 'b88a62', '9a6a46', '6a4228', '241e1c', '342a26', '1a1412')
    for x in range(B, PW - B):
        if (x - B) % 6 in (0, 1, 2):
            p.px(x, O + 3, 'e0c08a'); p.px(x, P1() - 4, 'c9a874'); p.px(O + 3, x, 'e0c08a'); p.px(P1() - 4, x, 'c9a874')
    def claw(t, x, y):
        for dx in (0, 4, 8):
            t.d.line([(x + 3 + dx, y + 2), (x + 5 + dx, y + 13)], fill=hexc('d8d2c6'), width=2)
    def arrow(t, x, y):
        t.d.line([(x + 2, y + 14), (x + 13, y + 3)], fill=hexc('c08a52'), width=2)
        t.d.polygon([(x + 14, y + 1), (x + 15, y + 6), (x + 10, y + 3)], fill=hexc('c8ccd4'), outline=hexc('3a4250'))
        t.d.polygon([(x + 1, y + 15), (x + 1, y + 10), (x + 4, y + 13)], fill=hexc('e8463c'))
    corners4(p, (claw, arrow, arrow, claw))
    band(h, '1e0806', '9e2a24', 'b8403a', '7a1e1a')
    head_tile(h, lambda x, k: (h.px(x, HH - 3, 'f2ede4') if k % 3 == 0 else None, h.px(x, HH - 2, 'd8d2c6') if k % 3 == 1 else None))
    arrow(h, PW - 16, -2)
    return p.im, h.im


def set_bareum():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '2a3a52', 'e08a72', 'c46a52', '9a4a36', 'f4f8fc', 'ffffff', 'e2eaf2')
    for x in range(B, PW - B):
        k = (x - B) % 12
        if k == 0:
            for d in range(2, 8):
                p.px(x, O + d, 'e8d6c6'); p.px(O + d, x, 'e8d6c6')
        if k == 6:
            for d in range(2, 8):
                p.px(x, P1() - 1 - d, 'e8d6c6'); p.px(P1() - 1 - d, x, 'e8d6c6')
    def ruler(t, x, y):
        t.d.polygon([(x + 1, y + 14), (x + 14, y + 1), (x + 14, y + 14)], fill=hexc('ffe066'), outline=hexc('8a6410'))
        for k in range(3, 14, 3):
            t.px(x + k, y + 13, '8a6410')
    def pencil(t, x, y):
        t.d.line([(x + 2, y + 13), (x + 12, y + 3)], fill=hexc('f6c945'), width=3)
        t.d.polygon([(x + 12, y + 1), (x + 15, y + 1), (x + 14, y + 4)], fill=hexc('5a4030'))
        t.oval(x + 2, y + 13, 1.6, 1.6, 'f07070')
    corners4(p, (ruler, pencil, pencil, ruler))
    band(h, '1a2e52', '3c6fb8', '5a8ad0', '2c5590')
    head_tile(h, lambda x, k: (h.px(x, O + 2, '8ab4e8') if k % 4 == 0 else None, h.px(x, HH - 3, '8ab4e8') if k % 4 == 0 else None,
                                h.px(x, O + 3, '6a9ad8') if k % 4 == 2 else None, h.px(x, 2, 'e8f2ff') if k in (0, 10) else None))
    ruler(h, -1, -2)
    return p.im, h.im


def set_nuri():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '1e3450', 'fff0c8', 'e8c890', 'b48e57', 'f2f7fa', 'ffffff', 'e0eaf0')
    for x in range(B, PW - B):
        k = (x - B) % 8
        if k in (0, 1, 2, 3):
            for xx, yy in ((x, O + 2 + k), (x, P1() - 3 - k)):
                p.px(xx, yy, '8a6a3a')
            p.px(O + 2 + k, x, '8a6a3a'); p.px(P1() - 3 - k, x, '8a6a3a')
    def buoy(t, x, y):
        t.oval(x + 8, y + 8, 6.4, 6.4, 'e8473f', '6a1a14'); t.oval(x + 8, y + 8, 3, 3, 'ffffff')
        for a in (0, 90, 180, 270):
            t.d.pieslice((x + 2, y + 2, x + 14, y + 14), a, a + 30, fill=hexc('ffffff'))
        t.oval(x + 8, y + 8, 2.4, 2.4, 'f2f7fa')
    def anchor(t, x, y):
        t.d.line([(x + 8, y + 2), (x + 8, y + 13)], fill=hexc('2f5e9e'), width=2)
        t.d.line([(x + 4, y + 5), (x + 12, y + 5)], fill=hexc('2f5e9e'), width=2)
        t.d.arc((x + 2, y + 6, x + 14, y + 15), 20, 160, fill=hexc('2f5e9e'), width=2)
    corners4(p, (buoy, anchor, anchor, buoy))
    band(h, '0e1e3a', '2f5e9e', '4a7cc0', '234a80')
    for x in range(O + 2, PW - O - 2):
        h.px(x, O + 3, 'ffffff'); h.px(x, HH - 4, 'ffffff')
    head_tile(h, lambda x, k: (h.px(x, 2 + (1 if k % 10 < 5 else 0), 'cfe6fa'),))
    buoy(h, -2, -3)
    return p.im, h.im


def set_kai():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '3a4a1a', 'fff4b0', 'e6d27a', 'b8a040', 'fafdf0', 'ffffff', 'edf3dc')
    for x in range(B, PW - B):
        k = (x - B) % 16
        if k == 0:
            for d in range(2, 8):
                p.px(x, O + d, '8a7a2a'); p.px(x, P1() - 1 - d, '8a7a2a'); p.px(O + d, x, '8a7a2a'); p.px(P1() - 1 - d, x, '8a7a2a')
        if k == 1:
            p.px(x, O + 3, 'fff8d0')
    def hibiscus(t, x, y):
        for a in range(5):
            ang = a * 2 * math.pi / 5 - math.pi / 2
            t.oval(x + 8 + 3.6 * math.cos(ang), y + 8 + 3.6 * math.sin(ang), 3, 3, 'ff5a7a')
        t.oval(x + 8, y + 8, 2, 2, 'ffe066'); t.px(x + 7, y + 7, 'ffffff')
    def bananas(t, x, y):
        for dx in (0, 3, 6):
            t.d.arc((x + 1 + dx, y + 1, x + 9 + dx, y + 15), 200, 340, fill=hexc('ffe04a'), width=2)
        t.rect(x + 7, y + 1, x + 10, y + 3, '6a4a24')
    corners4(p, (hibiscus, bananas, bananas, hibiscus))
    band(h, '1e3a10', '4fa84a', '6fc45a', '3a8a35')
    head_tile(h, lambda x, k: (h.px(x, HH - 1, '3a8a35') if k % 5 == 0 else None, h.px(x, O + 3, '8fd86a') if k % 4 == 2 else None,
                                h.oval(x, 3, 2, 1.4, '6fbf52', '3f7f32') if k in (4, 14) else None))
    hibiscus(h, -2, -3)
    return p.im, h.im


def set_roni():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '3a2418', 'b48a64', '8a5e3c', '5a3a22', 'fbf3e9', 'ffffff', 'efe2d2')
    for x in range(B, PW - B):
        k = (x - B) % 48
        if k in (3, 4, 5, 6, 30, 31, 32):
            p.px(x, O + 4, '6e4628'); p.px(O + 4, x, '6e4628')
        if k in (12, 13, 14, 38, 39):
            p.px(x, P1() - 5, '4a2e18'); p.px(P1() - 5, x, '4a2e18')
    def bracket(t, x, y):
        t.d.line([(x + 2, y + 2), (x + 14, y + 2)], fill=hexc('5a5e66'), width=3); t.d.line([(x + 2, y + 2), (x + 2, y + 14)], fill=hexc('5a5e66'), width=3)
        for xx, yy in ((x + 6, y + 2), (x + 2, y + 6), (x + 11, y + 2), (x + 2, y + 11)):
            t.px(xx, yy, 'd8dce4')
    def wrench(t, x, y):
        t.d.line([(x + 3, y + 13), (x + 11, y + 5)], fill=hexc('b8c0cc'), width=3)
        t.oval(x + 12, y + 4, 3, 3, 'b8c0cc', '3a4250'); t.oval(x + 13, y + 3, 1.2, 1.2, 'fbf3e9')
    corners4(p, (bracket, wrench, wrench, bracket))
    band(h, '4a1410', 'c8402f', 'e0584a', '962a20')
    head_tile(h, lambda x, k: (h.px(x, HH - 4, '962a20') if k % 10 in (0, 1) else None, h.px(x, HH - 3, '962a20') if k % 10 in (9, 2) else None,
                                h.px(x, O + 3, 'f07a6a') if k % 2 == 0 else None))
    for x in range(1, PW - 1):  # 처마
        h.px(x, 4, '4a1410'); h.px(x, 5, 'd84a38')
    return p.im, h.im


def set_milky():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '5a1e1a', 'f07a72', 'd9473f', 'b8322c', 'fffdf8', 'ffffff', 'f6ece4')
    for x in range(O + 2, P1() - 2):
        for y in range(O + 2, P1() - 2):
            if O + 8 <= x < P1() - 8 and O + 8 <= y < P1() - 8:
                continue
            if (x // 2) % 2 == 0 and (y // 2) % 2 == 0:
                p.px(x, y, 'ffffff')
            elif (x // 2) % 2 == 0 or (y // 2) % 2 == 0:
                p.px(x, y, 'f6a8a2')
    def spoon(t, x, y):
        t.d.line([(x + 4, y + 14), (x + 10, y + 6)], fill=hexc('c0c8d4'), width=2); t.oval(x + 11, y + 4, 3, 3.6, 'e0e6ee', '5a6270')
    def cake(t, x, y):
        t.rect(x + 3, y + 7, x + 14, y + 14, 'ffe0c0'); t.rect(x + 3, y + 7, x + 14, y + 9, 'ff9ab0'); t.oval(x + 8.5, y + 5, 1.8, 1.8, 'e0303a')
        t.d.rectangle((x + 3, y + 7, x + 13, y + 13), outline=hexc('8a4a3a'))
    corners4(p, (spoon, cake, cake, spoon))
    band(h, '5a1e1a', 'ffffff', 'ffffff', 'f2e6e0')
    for x in range(O + 2, PW - O - 2):
        for y in (O + 2, O + 3, O + 4, HH - 5, HH - 4, HH - 3):
            h.px(x, y, 'd9473f' if ((x // 2) + (y // 2)) % 2 == 0 else 'f6b4ae')
    head_tile(h, lambda x, k: h.px(x, 2 - (k % 10 > 4), 'e8e8f0') if k % 10 in (2, 3, 7) else None)
    cake(h, PW - 17, -4)
    return p.im, h.im


def set_fix():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '0a0810', 'd8a87a', 'b8885a', '7a5430', '1e1a28', '2e2840', '16121e')
    for x in range(B, PW - B):
        k = (x - B) % 24
        if k in (5, 17):
            p.oval(x, O - 2, 1.2, 1.2, 'b07ce8'); p.oval(x + 3, P1() + 2, 1.2, 1.2, '7ae8ff')
        if k == 11:
            p.oval(O - 2, x, 1.2, 1.2, 'b07ce8'); p.oval(P1() + 2, x, 1.2, 1.2, '7ae8ff')
    def feather(t, x, y, col='ffffff'):
        t.d.polygon([(x + 2, y + 14), (x + 6, y + 6), (x + 13, y + 1), (x + 10, y + 8)], fill=hexc(col), outline=hexc('6a5a8a'))
        t.d.line([(x + 2, y + 14), (x + 12, y + 2)], fill=hexc('9a8ac0'))
    corners4(p, (feather, lambda t, x, y: feather(t, x, y, 'c49eee'), lambda t, x, y: feather(t, x, y, 'c49eee'), feather))
    band(h, '0a0614', '3e2a64', '5a3e8c', '1c1240')
    for i in range(22):
        h.rect(O + 2, O + 2 + i, PW - O - 2, O + 3 + i, '%02x%02x%02x' % mix(hexc('5a3e8c'), hexc('1c1240'), i / 21)[:3])
    head_tile(h, lambda x, k: (h.px(x, 1, 'c8a8ff') if k in (4, 14) else None, h.px(x, 0, '7ae8ff') if k == 9 else None))
    feather(h, -1, -3, 'c49eee')
    return p.im, h.im


def set_garam():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '6a3a1a', 'ffc08a', 'f09a52', 'c2621e', 'fff8f2', 'ffffff', 'f6ebe0')
    for x in range(B, PW - B):
        k = (x - B) % 8
        if k == 0:
            p.oval(x, O + 4, 2.4, 1.6, 'e07a32'); p.oval(O + 4, x, 1.6, 2.4, 'e07a32')
    def bell(t, x, y):
        t.d.polygon([(x + 8, y + 2), (x + 12, y + 6), (x + 13, y + 12), (x + 3, y + 12), (x + 4, y + 6)], fill=hexc('f2c230'), outline=hexc('8a5a0a'))
        t.oval(x + 8, y + 13, 1.6, 1.4, '8a5a0a'); t.px(x + 6, y + 6, 'fff4b0')
    def button(t, x, y):
        t.oval(x + 8, y + 8, 5, 5, 'ff8fc0', '7a1a48')
        for dx, dy in ((-1.5, -1.5), (1.5, -1.5), (-1.5, 1.5), (1.5, 1.5)):
            t.px(x + 8 + dx, y + 8 + dy, '7a1a48')
    corners4(p, (bell, button, button, bell))
    band(h, '6a3a1a', 'fbebc8', 'fff6e0', 'e6cc9a')
    head_tile(h, lambda x, k: (h.px(x, O + 4, 'f0dcb0') if k in (3, 11) else None, h.px(x, HH - 5, 'e05a98') if k % 4 == 0 else None))
    for x in (O + 5, PW - O - 6):
        h.rect(x, 0, x + 1, O, 'c9a874')
    button(h, PW - 16, -3)
    return p.im, h.im


def set_lua():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '0a0814', 'fff0a0', 'f2d070', 'b8902e', '1b1730', '2c2648', '120f22')
    for x in range(B, PW - B):
        k = (x - B) % 12
        if k == 6:
            for xx, yy in ((x, O + 4), (x, P1() - 5), (O + 4, x), (P1() - 5, x)):
                p.d.polygon([(xx, yy - 2), (xx + 2, yy), (xx, yy + 2), (xx - 2, yy)], fill=hexc('7ae8ff'))
                p.px(xx, yy - 1, 'ffffff')
    def gem(t, x, y):
        t.d.polygon([(x + 8, y + 2), (x + 13, y + 7), (x + 8, y + 14), (x + 3, y + 7)], fill=hexc('3ab8e8'), outline=hexc('1a6ab0'))
        t.d.polygon([(x + 8, y + 2), (x + 10, y + 7), (x + 8, y + 9), (x + 6, y + 7)], fill=hexc('e8f8ff'))
    def crown(t, x, y):
        t.d.polygon([(x + 2, y + 13), (x + 2, y + 5), (x + 5, y + 9), (x + 8, y + 3), (x + 11, y + 9), (x + 14, y + 5), (x + 14, y + 13)],
                    fill=hexc('f4c84c'), outline=hexc('6a4c0a'))
        t.oval(x + 8, y + 10, 1.4, 1.4, 'e04a6a')
    corners4(p, (crown, gem, gem, crown))
    band(h, '1a0a30', '4a2a7a', '6a4a9a', '2a1a50')
    for x in range(O + 2, PW - O - 2):
        h.px(x, O + 2, 'f4c84c'); h.px(x, HH - 3, 'f4c84c')
    head_tile(h, lambda x, k: (h.px(x, 2, 'fff0a0') if k in (0, 10) else None, h.px(x, 3, 'f4c84c') if k in (9, 10, 11) else None))
    crown(h, -1, -3); crown(h, PW - 16, -3)
    return p.im, h.im


SETS = {'dodam': set_dodam, 'seoki': set_seoki, 'duri': set_duri, 'deumjik': set_deumjik, 'hotang': set_hotang, 'bareum': set_bareum,
        'nuri': set_nuri, 'kai': set_kai, 'roni': set_roni, 'milky': set_milky, 'fix': set_fix, 'garam': set_garam, 'lua': set_lua}
