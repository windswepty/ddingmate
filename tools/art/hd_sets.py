"""HD 세트(2026-10-23 사용자 승인 "적용해줘"): 캐릭터마다 다른 포즈·소품·개성 + 화려한 판·제목 줄, 2배 해상도(80칸).
mcmeta 는 지금과 같은 논리 크기(판 40×40·테두리 8, 제목 줄 40×17·좌우 10)라서 게임에서 같은 크기로 더 촘촘하게 그려진다.
반복 칸: 판 변은 80칸 그림의 16~63(48칸), 제목 줄은 20~59(40칸) → 무늬 주기는 그 약수로."""
from PIL import Image, ImageDraw
import hdkit as M
from hdkit import AX, W, H, m, Tone, canvas, head, tufts, eyes, wink, happy_eyes, blush, nose  # noqa: F401
from hdkit import mouth_open, mouth_w, mouth_smile, body, ellipse_pair, shine  # noqa: F401
from px import hexc, mix


def fline(c, pts, color, w=1):
    """고운(80칸) 꺾은선. w=2 면 두 칸 굵기"""
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
        for i in range(n + 1):
            t = i / n
            x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            c.fdot(x, y, color)
            if w > 1:
                c.fdot(x, y + 1, color)


def fpaint(c, mask, color):
    for x, y in mask:
        c.fdot(x, y, color)


def paw(c, x, y, r, tone, dx, dy, palm=False):
    """손(40칸 좌표): 둥근 손바닥 + 끝에 손가락 세 마디(살짝 볼록) + 마디 사이 고운 주름 두 줄.
    (dx, dy) 는 손가락이 향하는 쪽. palm=True 면 손바닥이 보이게 분홍 볼록살"""
    import math
    n = math.hypot(dx, dy) or 1.0
    dx, dy = dx / n, dy / n
    px_, py_ = -dy, dx
    mask = c.ellipse(x, y, r * 0.95, r * 0.88)
    for k in (-1, 0, 1):
        mask |= c.ellipse(x + dx * r * 0.62 + px_ * k * r * 0.5, y + dy * r * 0.62 + py_ * k * r * 0.5, r * 0.44, r * 0.44)
    c.add(mask, tone, shade=2, light=True, line=True)
    crease = '%02x%02x%02x' % tuple(int(v) for v in mix(tone.shade, tone.dark, 0.4)[:3])
    for k in (-0.5, 0.5):
        bx, by = 2 * (x + px_ * k * r * 0.5), 2 * (y + py_ * k * r * 0.5)
        fline(c, [(bx + dx * r * 1.9, by + dy * r * 1.9), (bx + dx * r * 1.0, by + dy * r * 1.0)], crease)
    if palm:
        fpaint(c, c.fellipse(2 * x - dx * r * 0.3, 2 * y - dy * r * 0.3, r * 0.7, r * 0.6), 'f8b6c6')
        for k in (-1, 0, 1):
            c.fdot(2 * (x + dx * r * 0.62 + px_ * k * r * 0.5), 2 * (y + dy * r * 0.62 + py_ * k * r * 0.5), 'f8b6c6')


def lines(c, pts, color):
    """고운(80칸) 점 여러 개"""
    for x, y in pts:
        c.fdot(x, y, color)


# ================================================================ HUD 친구 4명

def bori():
    """보리: 한 손 번쩍 흔들고 한 손엔 해바라기, 살랑 꼬리, 장난스럽게 젖힌 한쪽 귀, 기울어진 밀짚모자"""
    c = canvas()
    fur = Tone('fff8ec', 'f4e0c0', 'dfc49e', '9a7e5c')
    ear = Tone('f0d0a4', 'dcb486', 'c09466', '7a5c3a')
    straw = Tone('fff4b8', 'f6d470', 'd8b04c', '8c6a20')
    band = Tone('ffbc76', 'f48e3c', 'cc6c2a', '7a3a10')
    over = Tone('94bcf6', '5e8ed8', '4270b8', '23447a')
    petal = Tone('fff2a0', 'ffd23a', 'f0a020', '8a5a10')
    seed = Tone('a8642a', '7a4418', '5a2e0e', '2a1406')
    leaf = Tone('b8e890', '6fbf52', '4c8e3a', '24501a')
    c.add(c.ellipse_r(34.4, 27.6, 2.0, 5.0, -38), fur, shade=2, light=True)  # 살랑 꼬리(오른쪽 뒤)
    body(c, over)
    c.add(c.ellipse(AX, 33.8, 5, 1.6), Tone('ffffff', 'ffffff', 'e6e6ea'), shade=0)
    c.add(c.ellipse_r(5.4, 23.4, 4.0, 7.6, 10), ear, shade=2, light=True)   # 처진 왼쪽 귀
    c.add(c.ellipse_r(33.8, 17.6, 3.6, 6.6, -48), ear, shade=2, light=True)  # 젖혀진 오른쪽 귀
    head(c, fur)
    tufts(c, fur)
    c.add(c.ellipse(AX, 28, 6, 3.8), Tone('ffffff', 'fffbf4', 'f2e6d4'), shade=1)
    crown = c.ellipse_r(21.6, 8.4, 8.4, 5.4, -9) - c.ellipse_r(21, 13.4, 20, 2.6, -9)
    c.add(crown, straw, shade=2, light=True)
    c.add(c.ellipse_r(21, 11.6, 17.4, 3.0, -9), straw, shade=2, light=True)
    c.add(c.ellipse_r(21.6, 8.4, 8.4, 5.4, -9) & c.ellipse_r(21.2, 10.0, 9.4, 1.2, -9), band, shade=0)
    # 왼손: 해바라기를 쥐고 판을 짚음
    c.add(c.capsule(9, 34.5, 6.4, 22.5, 0.6), leaf, shade=0)
    c.add(c.ellipse_r(5.2, 29, 2.4, 1.1, -35), leaf, shade=1, light=True)
    c.add(c.ellipse(6.2, 19.8, 4.4, 4.4), petal, shade=2, light=True)
    c.add(c.ellipse(6.2, 19.8, 2.0, 2.0), seed, shade=1, light=True)
    paw(c, 10.6, 35.2, 3.0, fur, 0.3, 1)
    # 오른팔: 번쩍 들어 흔들기(손바닥이 보이게)
    c.add(c.capsule(27.6, 34, 33.4, 25.8, 2.3), fur, shade=2, light=True)
    paw(c, 34.2, 23.6, 3.0, fur, 0.45, -1, palm=True)
    # 얼굴
    eyes(c, 20)
    fline(c, [(21, 37), (24, 35.4), (28, 35.4), (31, 37)], 'b08a62', 2)  # 다정한 아치 눈썹
    fline(c, [(49, 37), (52, 35.4), (56, 35.4), (59, 37)], 'b08a62', 2)
    nose(c, 24)
    mouth_open(c, 26)
    blush(c, 25, 5)
    M.shine(c, 10, 15)
    # 꽃잎 결 · 손 흔드는 선 · 발가락
    lines(c, [(11, 36), (12, 35), (13, 34), (17, 38), (18, 38), (19, 38)], 'e8a018')
    lines(c, [(75, 38), (76, 39), (77, 41), (76, 54), (77, 56), (78, 58)], 'c49a80')
    lines(c, [(66, 46), (67, 46), (70, 45), (71, 45)], 'c9a888')
    c.dot(15, 36, 'ffd65a'); c.dot(m(15), 36, 'ffd65a')
    return c


def hani():
    """하니: 한 손엔 팔레트, 다른 손엔 분홍 물감을 듬뿍 머금은 붓(끝에서 물감이 살짝 튐)을 세워 들고, 줄무늬 셔츠·주머니 앞치마·분홍 스카프 볼에 물감 한 방울, 한쪽 귀는 쫑긋·한쪽은 접힘, 기울인 베레모"""
    c = canvas()
    fur = Tone('ffffff', 'f8f7fb', 'e2deea', '9a96aa')
    inner = Tone('ffdae2', 'ffb8c8', 'ec98ac')
    beret = Tone('8e8ae0', '6a64c8', '4e48a8', '2a2660')
    apron = Tone('c89272', 'a8704f', '84543a', '42261a')
    wood = Tone('fbe4b8', 'e8c48a', 'c89a5c', '7a5428')
    brush = Tone('c89a5c', 'a8743c', '845428', '42260e')
    body(c, Tone('ffffff', 'f4f4f8', 'dadce4'))
    # 줄무늬 화가 셔츠(앞치마 밖으로 보이는 어깨) + 가죽 앞치마: 멜빵·주머니(연필·붓)·물감 얼룩·바느질 단
    shirt = c.ellipse(AX, 37.6, 10.4, 4.4) - c.rect(12, 34, 28, 40)
    fpaint(c, {(x, y) for x, y in shirt if y % 4 == 1}, 'a8a2e0')
    c.add(c.rect(12, 34, 28, 40), apron, shade=1)
    fline(c, [(26, 68), (29, 65)], '84543a', 2); fline(c, [(54, 68), (51, 65)], '84543a', 2)
    for x in range(32, 48):
        for y in range(72, 79):
            c.fdot(x, y, '8e5a3e')
    fline(c, [(32, 72), (47, 72)], 'c89272')
    for x in range(33, 47, 2):
        c.fdot(x, 77, 'e8c48a')
    fline(c, [(35, 71), (34, 66)], 'f6c945', 2); c.fdot(34, 65, '5a4030')
    fline(c, [(39, 71), (40, 67)], '5aa8f0', 2); c.fdot(40, 66, 'ff8fb0')
    for x, y, r, col in ((27, 74, 1.3, 'ff8fb0'), (52, 76, 1.1, '5aa8f0')):
        fpaint(c, c.fellipse(x, y, r, r), col)
    for x in range(25, 56, 3):
        c.fdot(x, 79, 'e8c48a')
    c.add(c.ellipse_r(13.2, 5.8, 2.9, 7.6, -10), fur, shade=2, light=True)   # 쫑긋 귀
    c.add(c.ellipse_r(13.4, 6.6, 1.3, 5.6, -10), inner, shade=0)
    c.add(c.ellipse_r(30.4, 10.4, 2.8, 7.0, 62), fur, shade=2, light=True)   # 접힌 귀
    c.add(c.ellipse_r(30.6, 10.4, 1.2, 5.0, 62), inner, shade=0)
    head(c, fur)
    tufts(c, fur)
    c.add(c.ellipse_r(22.6, 10.6, 10.4, 3.6, 10), beret, shade=2, light=True)
    c.add(c.capsule(24.6, 7.2, 25.4, 5.4, 0.7), beret, shade=0)
    # 왼손: 나무 팔레트(엄지 구멍 + 물감 다섯 색)
    c.add(c.ellipse_r(7.4, 32.4, 6.4, 3.8, -18), wood, shade=2, light=True)
    paw(c, 9.6, 35.4, 2.6, fur, 0.4, 1)
    # 목에 맨 분홍 스카프(매듭)
    scarf = Tone('ffd0e0', 'ff8fb0', 'e0507a', '8a2040')
    c.add(c.poly([(16.4, 32.4), (23.6, 32.4), (20, 35.6)]), scarf, shade=1, light=True, line=True)
    c.add(c.ellipse(20, 32.8, 1.2, 0.9), scarf, shade=1, line=True)
    eyes(c, 21)
    fline(c, [(21, 38.6), (24, 37), (28, 36.6), (31, 37.6)], '9a92b4', 2)  # 한쪽 살짝 치켜든 장난스러운 눈썹
    fline(c, [(49, 38.4), (52, 37.4), (56, 37.4), (59, 38.6)], '9a92b4', 2)
    blush(c, 26, 5)
    lines(c, [(39, 52), (40, 52), (39, 53), (40, 53)], 'ff9aae')  # 분홍 코
    mouth_w(c, 27)
    M.shine(c, 9, 16)
    # 오른팔: 볼 아래·바깥에서 붓을 세워 듦(볼터치보다 나중에 → 손이 가려지지 않음)
    # 붓: 나무 자루 → 쇠 고리 → 분홍 물감을 듬뿍 머금은 뾰족한 붓털(물감 한 방울 맺힘)
    pink = Tone('ffd0e0', 'ff8fb0', 'e0507a', '8a2040')
    dx, dy, px_, py_ = 0.26, -0.966, 0.966, 0.26
    sx, sy = 34.8, 36.6

    def at(k, side=0.0):
        return (sx + dx * k + px_ * side, sy + dy * k + py_ * side)
    c.add(c.capsule(*at(0), *at(9), 0.75), brush, shade=1, light=True)
    c.add(c.capsule(*at(9), *at(10.8), 0.95), Tone('eef2f8', 'b8c0cc', '8a94a4', '3a4250'), shade=1, light=True)
    c.add(c.poly([at(11, 1.05), at(12.6, 1.2), at(14.6), at(12.6, -1.2), at(11, -1.05)]), pink, shade=1, light=True, line=True)
    c.add(c.ellipse(*at(12.2, 1.7), 0.7, 0.9), pink, shade=0)  # 맺힌 물감 방울
    c.add(c.capsule(27.6, 35, 34.8, 33, 2.1), fur, shade=2, light=True, line=True)
    paw(c, 35.8, 32.4, 2.5, fur, 0.3, -1)
    # 팔레트 물감 · 볼에 묻은 물감 · 튄 물감
    for (x, y), col in {(4, 31): 'ff6b6b', (6, 29.6): 'ffd84a', (9, 29.4): '6fcf6a', (11.6, 30.4): '5aa8f0', (4.4, 34): 'b07ce8'}.items():
        c.add(c.ellipse(x, y, 1.1, 1.0), Tone('ffffff', col, '%02x%02x%02x' % mix(hexc(col), hexc('000000'), 0.25)[:3]), shade=0)
    c.add(c.ellipse(5.6, 33.2, 0.9, 0.8), Tone('5a3a1e', '7a5428', '5a3a1e'), shade=0)  # 엄지 구멍
    lines(c, [(58, 52), (59, 52), (58, 53)], 'ff8fb0')  # 볼에 물감
    # 붓 끝에서 살짝 튄 물감(크고 작은 방울 몇 개)
    for x, y, r in ((79, 40, 1.1), (78, 35, 0.9), (73, 37, 0.7), (79, 46, 0.7)):
        fpaint(c, c.fellipse(x, y, r, r), 'ff8fb0')
    c.fdot(78, 39, 'ffd0e0')
    return c


def sharky():
    """샤키: 물보라를 일으키며 튀어 올라 지느러미로 물고기를 번쩍, 뒤로 꼬리지느러미, 뾰족 이빨 씩"""
    c = canvas()
    skin = Tone('94caf8', '5294de', '3c70be', '1a3a70')
    belly = Tone('ffffff', 'f6f9fc', 'dde7f2', '7a8aa0')
    fish = Tone('ffd8a0', 'ff9a4a', 'e0702a', '7a3410')
    water = Tone('e6f6ff', 'a8dcfa', '6ab4ea', '2a6aa8')
    c.add(c.poly([(31, 26), (38.6, 19.4), (37.4, 26.6), (39.6, 33), (32, 30)]), skin, shade=2, light=True)  # 꼬리지느러미
    c.add(c.poly([(16.4, 12), (21.6, 1.4), (24.6, 1.8), (25.6, 12)]), skin, shade=2, light=True)
    h = head(c, skin)
    c.add(c.ellipse(AX, 30.4, 13.4, 7.6) & h, belly, shade=2)
    # 왼쪽 지느러미로 물고기를 번쩍
    c.add(c.capsule(9.6, 32, 4.6, 22.4, 2.2), skin, shade=2, light=True)
    c.add(c.ellipse_r(4.2, 17.4, 3.6, 2.0, -25), fish, shade=2, light=True)
    c.add(c.poly([(7.2, 15.6), (10, 12.8), (10, 17.6)]), fish, shade=1)
    c.fdot(4, 33, '1a1a20'); c.fdot(5, 33, '1a1a20'); c.fdot(4, 32, 'ffffff')
    # 물보라(판 위를 덮는 물결 + 거품 + 물방울)
    wave = c.rect(0, 35, W, H)
    for x in range(0, 41, 5):
        wave |= c.ellipse(x + 1.5, 35, 3.2, 2.2)
    c.add(wave, water, shade=2, light=True)
    foam = set()
    for x in range(0, 41, 5):
        foam |= c.ellipse(x + 1.5, 34.2, 1.8, 1.1)
    c.add(foam, Tone('ffffff', 'ffffff', 'e6f6ff', '8ac0e8'), shade=0)
    for x, y, r in ((2, 30, 1.0), (38, 23, 0.9), (34.5, 36.6, 0.7), (1, 36, 0.8)):
        c.add(c.ellipse(x, y, r, r), water, shade=0, light=True)
    shine(c, 10, 14)
    wink(c, 18, 10, 'right')
    blush(c, 23, 5)
    # 자신만만 눈썹(왼쪽은 살짝 치켜, 오른쪽은 윙크 위 둥글게)
    fline(c, [(20, 33), (23, 31), (27, 31), (30, 32)], '2a5096', 2)
    fline(c, [(49, 33), (52, 31.5), (56, 31.5), (59, 33)], '2a5096', 2)
    # 아가미 결(양쪽 세 줄씩, 살짝 휜 짧은 선)
    for x0 in (11, 14, 17):
        fline(c, [(x0, 44), (x0 - 1, 47), (x0, 50)], '2a5096')
        fline(c, [(79 - x0, 44), (80 - x0, 47), (79 - x0, 50)], '2a5096')
    # 콧구멍
    c.fdot(36, 47, '1a3a70'); c.fdot(43, 47, '1a3a70')
    # 크게 웃는 입: 양끝이 올라간 윗입술 선 + 아래로 둥근 입 안 + 혀 + 이빨 줄 + 송곳니
    mouth = {(x, y) for x, y in c.fellipse(40, 52, 13.4, 8.2) if y >= 52}
    inner = {(x, y) for x, y in c.fellipse(40, 52, 11.8, 6.6) if y >= 53}
    fpaint(c, mouth, '1a3a70')
    fpaint(c, inner, '6a2440')
    fpaint(c, c.fellipse(40, 57.6, 5.6, 2.4) & inner, 'ff8fa3')
    c.fdot(37, 57, 'ffc0cc')
    fline(c, [(24, 49), (26, 51), (28, 52), (52, 52), (54, 51), (56, 49)], '1a3a70', 2)
    for x in range(29, 52, 3):  # 위 이빨(작은 세모)
        c.fdot(x, 53, 'ffffff'); c.fdot(x + 1, 53, 'ffffff'); c.fdot(x, 54, 'f0f4fa')
    c.fdot(30, 55, 'ffffff'); c.fdot(49, 55, 'ffffff')  # 송곳니
    # 머리 윤기(둥근 빛 띠) + 반짝 점
    for x in range(18, 36):
        y = 22 + int(((x - 27) / 9.0) ** 2 * 4)
        c.fsoft([(x, y), (x, y + 1)], 'ffffff', 0.5)
    c.fdot(19, 26, 'ffffff'); c.fdot(20, 25, 'ffffff')
    return c


def tentacle(c, p0, p1, p2, r0, r1, tone, curl=0, sucker='f8eeff'):
    """문어 다리: 2차 곡선을 따라 굵기가 r0→r1 로 가늘어지는 둥근 마디들 + 끝이 curl(±1) 쪽으로 동글 말림 + 안쪽 빨판"""
    import math
    mask = set()
    pts = []
    n = 18
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        r = r0 + (r1 - r0) * t
        mask |= c.ellipse(x, y, r, r)
        pts.append((x, y, r))
    if curl:
        ex, ey, er = pts[-1]
        mask |= c.ellipse(ex + curl * er * 0.9, ey - er * 0.9, er * 0.95, er * 0.95)
    c.add(mask, tone, line=True, shade=2, light=True)  # 겹친 다리 사이에 경계선
    # 빨판: 곡선 아래쪽(바깥으로 휜 쪽)에 작은 동그라미
    for i in range(3, n, 3):
        x, y, r = pts[i]
        c.add(c.ellipse(x, y + r * 0.55, max(0.45, r * 0.32), max(0.4, r * 0.28)), Tone('ffffff', sucker, 'd8c0f0'), shade=0)


def pos():
    """포스: 금 견장 빨간 선장 코트를 입은 문어 선장. 코트 아래로 자연스럽게 휘어 판 위에 걸친 다리 여섯(끝은 동글 말림·빨판),
    한 다리로 망원경, 머리 둥근 선을 따라 모자 밑으로 이어진 안대 끈, 여유로운 반쯤 덮은 눈매, 씩 웃음, 살짝 기운 깃털 삼각모"""
    import math
    c = canvas()
    skin = Tone('ecd4fe', 'bc92e8', '966ac8', '4a2a70')
    hat = Tone('4e4e5c', '24242c', '16161c', '000000')
    gold = Tone('fff4b0', 'f4c84c', 'c89a26', '6a4c0a')
    plume = Tone('ff9a8a', 'e8463c', 'b42a24', '5a0e0a')
    brass = Tone('fff0b8', 'e8b84a', 'b0802a', '5a3c0a')
    coat = Tone('d8665a', 'a8382e', '7c2620', '3a0c08')
    patch = Tone('4a4a58', '1c1c24', '101016', '000000')
    # 다리(뒤쪽부터): 코트 아래에서 나와 양옆·앞으로 휘어 판 위에 걸침
    tentacle(c, (11.6, 32), (3.4, 30.6), (3.6, 23.4), 2.5, 1.0, skin, curl=1)      # 왼쪽 뒤: 높이 들어 흔듦(끝이 말림)
    tentacle(c, (29, 32), (35.6, 31), (35.4, 25.2), 2.4, 1.4, skin)               # 오른쪽 뒤: 망원경을 듦
    tentacle(c, (13, 34.6), (5.4, 38.2), (2.6, 33.4), 2.7, 1.0, skin, curl=1)      # 왼쪽 앞: 판 위로 크게 휘어 끝이 위로 말림
    tentacle(c, (27, 34.6), (34.6, 38.2), (37.4, 33.4), 2.7, 1.0, skin, curl=-1)   # 오른쪽 앞
    tentacle(c, (16.6, 36.6), (14, 39), (9.8, 37.6), 2.4, 1.0, skin, curl=1)       # 가운데 왼쪽: 판 위로 짧게
    tentacle(c, (23.4, 36.6), (26, 39), (30.2, 37.6), 2.4, 1.0, skin, curl=-1)     # 가운데 오른쪽
    # 선장 코트 + 금 견장 + 깃·단추
    c.add(c.ellipse(AX, 35.2, 9.8, 4.3), coat, shade=2, light=True)
    c.add(c.poly([(16, 31.6), (20, 37.6), (24, 31.6)]), Tone('ffffff', 'f4f4f6', 'd6d8de'), shade=0)  # 흰 셔츠 깃
    c.add(ellipse_pair(c, 11, 32.2, 3.0, 1.6), gold, shade=1, light=True)
    # 망원경(오른쪽 뒤 다리 끝에서 비스듬히 위로)
    c.add(c.capsule(33.4, 24.6, 38, 18.4, 1.9), brass, shade=2, light=True)
    c.add(c.capsule(37.4, 19.2, 39.2, 16.8, 2.4), brass, shade=1, light=True)
    c.add(c.ellipse(39.4, 16.6, 1.2, 1.5), Tone('e6f6ff', 'a8dcfa', '6ab4ea', '2a4a70'), shade=0)
    # 머리
    c.add(c.ellipse(AX, 20.6, 14.2, 11.8), skin, shade=3, light=True)
    # 안대 끈(머리 둥근 선을 따라 모자 챙 밑으로) + 안대 — 모자보다 먼저 그려 챙이 끈 끝을 덮게
    # 한 줄은 안대 바깥에서 머리 옆선으로(머리 뒤로 돌아감), 한 줄은 이마를 비스듬히 가로질러 모자 챙 밑으로
    strap = c.capsule(9.6, 22.4, 6.2, 21.4, 0.55) | c.capsule(15.6, 20.4, 19.6, 16.6, 0.55) | c.capsule(19.6, 16.6, 23.6, 13.8, 0.5)
    c.add(strap, patch, shade=0)
    c.add(c.ellipse_r(13, 23, 3.8, 3.2, -12), patch, shade=2, light=True)
    # 깃털 삼각모(살짝 기울임: 오른쪽으로 6도)
    a = math.radians(6)

    def rot(p):
        x, y = p[0] - 20, p[1] - 9
        return (20 + x * math.cos(a) - y * math.sin(a), 9 + x * math.sin(a) + y * math.cos(a))
    brim = [rot(p) for p in [(2.6, 15), (7.6, 6.6), (13.4, 3.8), (20, 5.6), (26.6, 3.8), (32.4, 6.6), (37.4, 15), (29, 12.2), (20, 13.4), (11, 12.2)]]
    c.add(c.poly([rot(p) for p in [(30, 6.6), (33.4, 0.4), (37, -0.4), (36, 3), (33, 8.6)]]), plume, shade=2, light=True)
    c.add(c.poly(brim), hat, shade=2, light=True)
    c.add(c.line([rot(p) for p in [(3.6, 14.4), (11, 11.6), (20, 12.8), (29, 11.6), (36.4, 14.4)]]), gold, shade=0)
    c.add(c.line([rot(p) for p in [(8.4, 7), (13.4, 4.6), (20, 6.4), (26.6, 4.6), (31.6, 7)]]), gold, shade=0)
    M.shine(c, 9, 17)
    # 모자 해골 + 엇갈린 뼈다귀
    sx, sy = rot((20, 8))
    sx, sy = sx * 2, sy * 2
    fline(c, [(sx - 7, sy + 6), (sx + 7, sy - 5)], 'e8e8ec', 2); fline(c, [(sx - 7, sy - 5), (sx + 7, sy + 6)], 'e8e8ec', 2)
    for dx, dy in ((-7, 6), (7, -5), (-7, -5), (7, 6)):
        fpaint(c, c.fellipse(sx + dx, sy + dy, 1.6, 1.4), 'f4f4f6')
    fpaint(c, c.fellipse(sx, sy, 4.4, 3.8), 'fafafa')
    for dx in (-2, 2):
        c.fdot(sx + dx, sy, '16161c'); c.fdot(sx + dx, sy - 1, '16161c')
    fline(c, [(sx - 1.5, sy + 4), (sx + 1.5, sy + 4)], '9a9aa8')
    # 문어 피부 점무늬
    for x, y, r in ((30, 32, 1.6), (35, 29, 1.0), (56, 30, 1.5), (62, 38, 1.1), (64, 51, 1.4), (28, 62, 1.1), (52, 62, 1.3)):
        fpaint(c, c.fellipse(x, y, r, r), '9a6ad0')
    # 안대 윤기
    fline(c, [(21, 42), (24, 40.5), (28, 41)], '6a6a7a')
    # 여유로운 오른눈: 큰 눈 위를 눈꺼풀이 살짝 덮음 + 치켜든 눈썹
    M._eye(c, m(10) - 5, 20)
    fline(c, [(47, 41), (50, 40), (54, 40), (58, 40.6), (60, 42)], '3a1a5a', 2)
    fline(c, [(46, 36), (49, 33.4), (54, 32.6), (58, 33.4), (61, 35.6)], '4a2a70', 2)
    # 안대 위 눈썹: 살짝 내려 깐 굵은 눈썹 + 가로지르는 흉터 한 줄
    fline(c, [(18, 35.4), (22, 34.2), (27, 34.4), (32, 36.2)], '4a2a70', 2)
    fline(c, [(23, 32), (25, 38)], 'd8b8f4')
    blush(c, 25, 5, 0.5)
    # 자연스러운 미소: 짧은 곡선, 오른쪽 끝만 살짝 올라감 + 작은 송곳니
    fline(c, [(34, 57), (36, 58.4), (39, 59), (42, 58.8), (45, 57.6), (47, 55.8)], '4a2070')
    fline(c, [(35, 57.6), (38, 58.6), (42, 58.4)], '6a3a98')
    c.fdot(43, 59, 'ffffff'); c.fdot(43, 60, 'f0e8ff')
    # 금 고리 귀걸이 · 코트 금 단추 · 견장 술
    ring = c.fellipse(68, 52, 3.0, 3.4) - c.fellipse(68, 52, 1.5, 1.9)
    fpaint(c, ring, 'f4c84c'); c.fdot(66, 49, 'fff4b0')
    for y in (68, 73):
        fpaint(c, c.fellipse(40, y, 1.3, 1.3), 'f4c84c'); c.fdot(39, y - 1, 'fff4b0')
    for x in (16, 19, 22, 25):
        c.fdot(x, 67, 'f4c84c'); c.fdot(x, 68, 'c89a26')
        c.fdot(80 - x, 67, 'f4c84c'); c.fdot(80 - x, 68, 'c89a26')
    lines(c, [(73, 35), (74, 34), (75, 33)], 'fff8d8')  # 망원경 윤기
    for t in range(3):
        c.fdot(70 + t, 41 - t, '6a4c0a')
    return c


MASCOTS = {'bori': bori, 'hani': hani, 'sharky': sharky, 'pos': pos}


# ================================================================ 판 · 제목 줄(80칸, 바깥 장식 6칸 · 나인슬라이스 테두리 16칸)

PW, O, B, HH = 80, 6, 16, 34
HB = 20  # 제목 줄 좌우 고정 칸(논리 10 × 2). 반복 칸은 HB ~ PW - HB(40칸)
P0, P1 = O, PW - O


class Tex:
    def __init__(self, w, h):
        self.im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)
        self.w, self.h = w, h

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.im.putpixel((int(x), int(y)), hexc(c) if isinstance(c, str) else c)

    def rect(self, x0, y0, x1, y1, c):
        if x1 > x0 and y1 > y0:
            self.d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=hexc(c))

    def oval(self, cx, cy, rx, ry, c, outline=None):
        self.d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=hexc(c), outline=hexc(outline) if outline else None)


def corners4(t, fns):
    """네 귀퉁이에 서로 다른 장식(각 16×16 안)"""
    for (x, y), fn in zip(((0, 0), (PW - 16, 0), (0, PW - 16), (PW - 16, PW - 16)), fns):
        fn(t, x, y)


def frame(t, outline, li, mid, dk, body, inner_hi='ffffff', inner_lo=None):
    """판: 둥근 외곽선 2칸 + 테두리 6칸(위·왼쪽 밝게, 아래·오른쪽 그늘) + 안쪽 빛 줄 + 바탕"""
    t.rect(P0, P0, P1, P1, outline)
    for i in range(6):
        a, b = P0 + 2 + i, P1 - 3 - i
        col_l = li if i < 2 else mid
        col_d = dk if i < 4 else mid
        t.rect(a, a, b + 1, a + 1, col_l); t.rect(a, a, a + 1, b + 1, col_l)
        t.rect(a, b, b + 1, b + 1, col_d); t.rect(b, a, b + 1, b + 1, col_d)
    t.rect(P0 + 8, P0 + 8, P1 - 8, P1 - 8, body)
    a, b = P0 + 8, P1 - 9
    t.rect(a, a, b + 1, a + 1, inner_hi); t.rect(a, a, a + 1, b + 1, inner_hi)
    if inner_lo:
        t.rect(a, b, b + 1, b + 1, inner_lo); t.rect(b, a + 1, b + 1, b + 1, inner_lo)
    for x, y in ((P0, P0), (P1 - 1, P0), (P0, P1 - 1), (P1 - 1, P1 - 1)):  # 둥근 귀퉁이
        t.px(x, y, (0, 0, 0, 0))


def band(h, outline, base, light, dark, text_row_color=None):
    """제목 줄 띠: 판 윗변(6줄)부터 28줄. 글자 자리(12~27줄)는 한 색"""
    h.rect(O, O, PW - O, HH, outline)
    h.rect(O + 2, O + 2, PW - O - 2, HH - 2, base)
    h.rect(O + 2, O + 2, PW - O - 2, O + 4, light)
    h.rect(O + 2, HH - 5, PW - O - 2, HH - 2, dark)
    for x, y in ((O, O), (PW - O - 1, O)):
        h.px(x, y, (0, 0, 0, 0))


# ---- 보리: 해바라기 울타리 ----
def sunflower(t, cx, cy, r=6.5):
    for k in range(12):
        import math
        a = k * math.pi / 6
        t.oval(cx + math.cos(a) * r * 0.62, cy + math.sin(a) * r * 0.62, r * 0.42, r * 0.42, 'ffcf38' if k % 2 else 'f6b420')
    t.oval(cx, cy, r * 0.48, r * 0.48, '7a3e14')
    t.oval(cx - 0.6, cy - 0.6, r * 0.25, r * 0.25, 'a8642a')
    t.px(cx - 2, cy - 2, 'fff0a0')


def bori_set():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '5a3420', 'f6d29a', 'e6ad6c', 'b77a46', 'fffaf0', 'ffffff', 'f1e2c6')
    for x in range(B, PW - B):  # 판자 결(길이 엇갈림) · 이음매 · 못
        k = (x - B) % 48
        if k in (3, 4, 5, 6, 20, 21, 33, 34, 35):
            p.px(x, P0 + 4, 'd09858'); p.px(x, P1 - 5, '9c6440')
            p.px(P0 + 4, x, 'd09858'); p.px(P1 - 5, x, '9c6440')
        if k in (16, 40):
            for d in range(2, 8):
                p.px(x, P0 + d, '9c6440'); p.px(x, P1 - 1 - d, '7a4a2a'); p.px(P0 + d, x, '9c6440'); p.px(P1 - 1 - d, x, '7a4a2a')
            p.px(x + 3, P0 + 4, '6e5a4e'); p.px(x + 3, P1 - 5, '6e5a4e')
        if k % 12 == 6:  # 아래 밖 풀잎
            p.px(x, P1, '5fa845'); p.px(x, P1 + 1, '3f7f32'); p.px(x + 1, P1, '7fc85a')
    for cx, cy in ((7, 7), (PW - 8, 7), (7, PW - 8), (PW - 8, PW - 8)):
        for dx, dy in ((5, 5), (6, 3)):
            p.px(cx + (dx if cx < 40 else -dx), cy + (dy if cy < 40 else -dy), '5fa845')
        sunflower(p, cx, cy)
    band(h, '5a3a1e', 'eebd52', 'fff1b8', 'c99035')
    for x in range(O + 2, PW - O - 2):  # 밀짚 엮음(위 두 줄 · 아래 두 줄만)
        if x % 4 == 0:
            h.px(x, O + 5, 'c99035'); h.px(x + 2, HH - 6, 'd9a63e')
    for x in range(HB, PW - HB):  # 위 밖: 작은 꽃 화관(20칸마다 되풀이)
        k = (x - HB) % 20
        if k == 3:
            h.oval(x, 3, 2, 2, 'ffcf38'); h.px(x, 3, '7a3e14')
        if k in (8, 9, 15):
            h.px(x, 4, '6fbf52'); h.px(x, 5, '4c8e3a')
        if k == 12:
            h.oval(x, 2.5, 1.4, 1.4, 'ffffff'); h.px(x, 2, 'ffe066')
    for x in (O + 6, PW - O - 7):
        h.oval(x, 4, 5.4, 5.4, '2a1406')
        sunflower(h, x, 4, 5.2)
    # 꿀벌(오른쪽 끝)
    h.oval(PW - 2, 14, 2.4, 1.8, 'ffd23a', '2a1a10'); h.px(PW - 2, 13, '2a1a10'); h.px(PW - 3, 11, 'ffffff'); h.px(PW - 1, 11, 'ffffff')
    return p.im, h.im


# ---- 하니: 물감 공방 ----
RAINBOW = ['ff6b6b', 'ffa54a', 'ffd84a', '6fcf6a', '5aa8f0', 'b07ce8']


def hani_set():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '6a4a5a', 'ffe2ea', 'f9c4d2', 'e595ab', 'fffdfa', 'ffffff', 'f6ecef')
    for x in range(B, PW - B):  # 테두리 물감 점(여섯 색) + 아래로 흐르는 물감
        k = (x - B) % 48
        if k % 8 == 2:
            col = RAINBOW[k // 8]
            p.oval(x, P0 + 4, 1.4, 1.4, col); p.px(x - 1, P0 + 3, 'ffffff')
            p.oval(P0 + 4, x, 1.4, 1.4, col)
            p.oval(P1 - 5, x + 3, 1.4, 1.4, col)
        if k % 16 == 10:
            col = RAINBOW[(k // 16 + 2) % 6]
            p.rect(x, P1 - 2, x + 2, P1 + 3, col); p.oval(x + 0.5, P1 + 3, 1.4, 1.6, col)
    # 귀퉁이: 팔레트 · 물감 튜브 · 붓 · 물감 자국
    def palette(t, x, y):
        t.oval(x + 7.5, y + 7.5, 7, 5.6, 'e8c48a', '7a5428'); t.oval(x + 5, y + 9.5, 1.4, 1.2, '7a5428')
        for (dx, dy), col in zip(((4, 5), (8, 4), (11, 6), (11, 10), (8, 11)), RAINBOW):
            t.oval(x + dx, y + dy, 1.2, 1.2, col)

    def tube(t, x, y):
        t.d.polygon([(x + 3, y + 3), (x + 12, y + 3), (x + 11, y + 11), (x + 4, y + 11)], fill=hexc('f4f4f8'), outline=hexc('6a6a7a'))
        t.rect(x + 4, y + 5, x + 11, y + 8, 'ff8fb0'); t.rect(x + 6, y + 11, x + 9, y + 14, '9a9aa8'); t.oval(x + 7.5, y + 15, 1.6, 1.2, 'ff8fb0')

    def brush(t, x, y):
        t.d.line([(x + 2, y + 13), (x + 11, y + 4)], fill=hexc('a8743c'), width=2)
        t.d.polygon([(x + 10, y + 3), (x + 14, y + 1), (x + 13, y + 5)], fill=hexc('5aa8f0'), outline=hexc('2a4a80'))

    def splat(t, x, y):
        t.oval(x + 8, y + 8, 5, 4, 'b07ce8'); t.oval(x + 3, y + 4, 1.4, 1.4, 'b07ce8'); t.oval(x + 13, y + 12, 1.2, 1.2, 'b07ce8')
        t.px(x + 6, y + 6, 'e8d8ff')
    corners4(p, (palette, tube, brush, splat))
    band(h, '1d2347', '4e5c9a', '6a7ab6', '3a4680')
    for x in range(O + 2, PW - O - 2):  # 위 무지개 붓질 두 줄
        h.px(x, O + 2, RAINBOW[(x // 4) % 5]); h.px(x, O + 3, RAINBOW[(x // 4) % 5])
    for x in range(HB, PW - HB):  # 위 밖: 물감 방울
        k = (x - HB) % 20
        if k in (5, 15):
            h.oval(x, 3, 1.6, 1.6, RAINBOW[0 if k == 5 else 4])
    h.oval(O + 5, 6, 5, 4, 'e8c48a', '7a5428')
    for (dx, dy), col in zip(((3, 5), (6, 4), (8, 6)), RAINBOW):
        h.oval(O + dx, dy, 1, 1, col)
    h.d.line([(PW - 14, 12), (PW - 3, 1)], fill=hexc('a8743c'), width=2)
    h.oval(PW - 3, 1.5, 1.6, 1.6, 'ff8fb0')
    return p.im, h.im


# ---- 샤키: 파도 ----
def sharky_set():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '163463', '9ad0ff', '5a9ae6', '2c62ad', 'f3f8fc', 'ffffff', 'e2edf8')
    for x in range(B, PW - B):  # 아래 밖 파도 · 위 거품 · 옆 물방울
        k = (x - B) % 16
        y = P1 + int(2 + 1.6 * __import__('math').sin(k / 16 * 6.283))
        p.px(x, y, 'cfe6fa'); p.px(x, y + 1, '7db6f2')
        if k in (2, 3, 10):
            p.px(x, P0 + 4, 'ffffff'); p.px(x + 1, P0 + 3, 'cfe6fa')
        if k == 7:
            p.oval(P0 + 4, x, 1.2, 1.2, 'ffffff'); p.oval(P1 - 5, x + 6, 1, 1, 'cfe6fa')

    def star(t, x, y):
        pts = []
        import math
        for i in range(10):
            r = 7 if i % 2 == 0 else 3
            a = -math.pi / 2 + i * math.pi / 5
            pts.append((x + 8 + math.cos(a) * r, y + 8 + math.sin(a) * r))
        t.d.polygon(pts, fill=hexc('ffb060'), outline=hexc('b8601a'))
        t.px(x + 8, y + 7, 'fff0d0'); t.px(x + 6, y + 9, 'e07a2a'); t.px(x + 10, y + 9, 'e07a2a')

    def shell(t, x, y):
        t.d.pieslice((x + 2, y + 3, x + 14, y + 15), 180, 360, fill=hexc('ffd6e0'), outline=hexc('b0607a'))
        for dx in (4, 8, 12):
            t.d.line([(x + 8, y + 9), (x + dx, y + 4)], fill=hexc('e8a0b4'))
        t.rect(x + 6, y + 9, x + 11, y + 11, 'f0b0c4')

    def bubbles(t, x, y):
        for cx, cy, r in ((8, 8, 3.5), (3, 4, 1.6), (12, 3, 1.4), (4, 13, 1.2)):
            t.oval(x + cx, y + cy, r, r, 'e6f6ff', '5a9ae6'); t.px(x + cx - r / 2, y + cy - r / 2, 'ffffff')
    corners4(p, (star, shell, bubbles, star))
    band(h, '0e2a56', '2c62ad', '5a9ae6', '1c4a8a')
    for x in range(HB, PW - HB):  # 위 밖: 물결 거품(20칸)
        k = (x - HB) % 20
        y = int(3 - 2 * abs(k - 10) / 10)
        h.px(x, y + 2, 'ffffff'); h.px(x, y + 3, 'cfe6fa')
        if k == 10:
            h.oval(x, 1, 1, 1, 'ffffff')
    for x in range(O + 2, PW - O - 2):
        if x % 5 in (0, 1):
            h.px(x, HH - 4, 'cfe6fa')
    h.d.polygon([(PW - 12, 6), (PW - 4, 2), (PW - 4, 10)], fill=hexc('ff9a4a'), outline=hexc('7a3410'))  # 꼬리
    h.oval(PW - 15, 6, 4, 2.6, 'ff9a4a', '7a3410'); h.px(PW - 17, 5, '1a1a20')
    h.oval(O + 4, 5, 3, 3, 'e6f6ff', '5a9ae6'); h.oval(O + 1, 1, 1.4, 1.4, 'e6f6ff', '5a9ae6')
    return p.im, h.im


# ---- 포스: 해적선 ----
def pos_set():
    p, h = Tex(PW, PW), Tex(PW, HH)
    frame(p, '0a0e14', 'a87a52', '7a5236', '4a2e1c', '1e2633', 'd9b04a', '141a24')
    for x in range(B, PW - B):  # 바깥 꼰 밧줄 + 금 리벳
        k = (x - B) % 8
        if k in (0, 1, 2):
            p.px(x, P0 + 1, 'e8d0a0'); p.px(x, P1 - 2, 'c8a878'); p.px(P0 + 1, x, 'e8d0a0'); p.px(P1 - 2, x, 'c8a878')
        if (x - B) % 24 == 12:
            for xx, yy in ((x, P0 + 5), (x, P1 - 6), (P0 + 5, x), (P1 - 6, x)):
                p.oval(xx, yy, 1.2, 1.2, 'f4c84c'); p.px(xx - 1, yy - 1, 'fff4b0')

    def anchor(t, x, y):
        t.d.line([(x + 8, y + 2), (x + 8, y + 13)], fill=hexc('f4c84c'), width=2)
        t.d.line([(x + 4, y + 5), (x + 12, y + 5)], fill=hexc('f4c84c'), width=2)
        t.d.arc((x + 2, y + 6, x + 14, y + 15), 20, 160, fill=hexc('f4c84c'), width=2)
        t.oval(x + 8, y + 2, 1.6, 1.6, '1e2633', 'f4c84c')

    def compass(t, x, y):
        t.oval(x + 8, y + 8, 6.5, 6.5, '2a3240', 'f4c84c')
        t.d.polygon([(x + 8, y + 3), (x + 9.5, y + 8), (x + 8, y + 13), (x + 6.5, y + 8)], fill=hexc('e8463c'))
        t.px(x + 8, y + 8, 'fff4b0')
    corners4(p, (anchor, compass, compass, anchor))
    band(h, '000000', '1a1a22', '2a2a34', '121218')
    for x in range(O + 2, PW - O - 2):
        h.px(x, O + 2, 'd9b04a'); h.px(x, HH - 3, 'd9b04a')
        if x % 8 in (0, 1, 2):
            h.px(x, O + 3, 'f4e0a0')
    for x in range(HB, PW - HB):  # 위 밖: 늘어진 밧줄 + 깃발(20칸)
        k = (x - HB) % 20
        y = int(1 + 3 * (1 - ((k - 10) / 10) ** 2))
        h.px(x, y, 'c8a878'); h.px(x, y + 1, '8a6a3a')
        if k == 10:
            h.d.polygon([(x - 2, y + 1), (x + 2, y + 1), (x, y + 5)], fill=hexc('e8463c'))
    # 해골 깃발(오른쪽 끝) · 금화 더미(왼쪽 끝)
    h.rect(PW - 16, 0, PW - 2, 11, '16161c'); h.oval(PW - 9, 4.5, 3, 2.6, 'f2ece0'); h.px(PW - 10, 4, '16161c'); h.px(PW - 8, 4, '16161c')
    h.d.line([(PW - 13, 9), (PW - 5, 9)], fill=hexc('f2ece0'))
    for cx, cy in ((O + 2, 5), (O + 6, 4), (O + 4, 1)):
        h.oval(cx, cy, 2.4, 1.6, 'f4c84c', '8a6a1e')
    return p.im, h.im


SETS = {'bori': bori_set, 'hani': hani_set, 'sharky': sharky_set, 'pos': pos_set}
