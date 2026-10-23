"""HD(80칸) 그림 공통 부품: 2배 캔버스(px2.Canvas2x) + 고운 칸 얼굴(큰 눈·웃는 눈·졸린 눈·윙크·볼터치·코·입) +
머리·몸·볼 털 등. 시안(2026-10-23 "전체적으로 다듬고 픽셀을 더 써서 디테일, 미니멀·캐주얼")에서 확정.
좌표는 mascots.py 와 같은 40칸 기준(0.5 단위로 고운 칸), fdot·fellipse 만 80칸 좌표."""

from px import Canvas, Tone, hexc, mix
from px2 import Canvas2x

W, H = 40, 40
AX = 20.0
EYE = '221a2e'
EYE2 = '4e3e6c'
WHITE = 'ffffff'
BLUSH = 'ff8fa8'

# 판 위쪽 테두리가 지나갈 줄(그림 맨 위 기준). Java 가 이 줄을 판 윗변에 맞춘다
PAW_ROW = 36

HX, HY, HRX, HRY = AX, 21.6, 15.4, 11.8  # 머리(찹쌀떡): 9.8~33.4줄


def canvas():
    c = Canvas2x(W, H, 0)
    c.hd = True  # HD 색 띠(2026-10-23): 그늘은 차갑게, 밝음은 따뜻하게
    return c


def m(x):
    """칸 x 의 좌우 짝"""
    return 39 - x


# ---------------------------------------------------------------- 공통 부품

def head(c, tone, rx=HRX, ry=HRY, cy=HY):
    return c.add(c.ellipse(HX, cy, rx, ry), tone, shade=3, light=True)


def shine(c, x=10, y=14):
    """머리 왼쪽 위 은은한 빛 + 반짝 점"""
    c.soft([(x + dx, y + dy) for dx in range(-1, 4) for dy in range(-1, 3) if abs(dx - 1) + abs(dy) < 3], 'ffffff', 0.35)
    c.dot(x, y, WHITE); c.dot(x + 1, y, WHITE); c.dot(x, y + 1, WHITE)


def _paint(c, mask, color):
    for x, y in mask:
        c.fdot(x, y, color)


def _eye(c, x0, y, color=EYE, color2=EYE2):
    """큰 눈(80칸 12×12): 위는 짙게 → 아래로 보라빛, 아래 초승달 반사, 큰 둥근 반짝 + 작은 반짝"""
    cx, cy = 2 * x0 + 6, 2 * y + 6
    eye = c.fellipse(cx, cy, 5.7, 6.1)
    top = cy - 6
    for x, yy in eye:
        t = (yy - top) / 12.0
        c.fdot(x, yy, color if t < 0.5 else mix(hexc(color), hexc(color2), min(1.0, (t - 0.5) / 0.4)))
    _paint(c, c.fellipse(cx, cy + 3.6, 3.4, 1.5) & eye, '9a8ad6')
    _paint(c, c.fellipse(cx - 1.9, cy - 2.1, 2.2, 2.2), 'ffffff')
    c.fdot(cx + 2, cy + 1, 'ffffff'); c.fdot(cx + 3, cy + 1, 'f0ecff'); c.fdot(cx + 2, cy + 2, 'f0ecff')


def eyes(c, y=21, lx=10, color=EYE):
    """큰 눈 두 개, 미간 8칸(40칸 기준 왼눈 10~15 · 오른눈 24~29)"""
    _eye(c, lx, y, color)
    _eye(c, m(lx) - 5, y, color)


def _arc(c, x0, y, color, down=False):
    """웃는 눈 ∩(두 칸 굵기 둥근 호)"""
    cx, cy = 2 * x0 + 6, 2 * y + (5 if down else 9)
    ring = c.fellipse(cx, cy, 5.4, 4.6) - c.fellipse(cx, cy, 3.4, 2.7)
    _paint(c, {(x, yy) for x, yy in ring if (yy >= cy if down else yy <= cy)}, color)


def happy_eyes(c, y=21, lx=10, color=EYE):
    for x0 in (lx, m(lx) - 5):
        _arc(c, x0, y, color)


def sleepy_eyes(c, y=21, lx=10, color=EYE):
    """반쯤 감은 눈: 눈꺼풀 선 + 아래 반달 눈동자(반짝 하나)"""
    for x0 in (lx, m(lx) - 5):
        cx, cy = 2 * x0 + 6, 2 * y + 3
        _paint(c, {(x, yy) for x, yy in c.fellipse(cx, cy, 6.6, 1.4)}, color)
        half = {(x, yy) for x, yy in c.fellipse(cx, cy + 1, 5.2, 4.2) if yy >= cy + 1}
        for x, yy in half:
            c.fdot(x, yy, mix(hexc(color), hexc(EYE2), min(1.0, (yy - cy) / 5.0)))
        _paint(c, c.fellipse(cx - 2, cy + 2.4, 1.3, 1.1), 'ffffff')


def wink(c, y=21, lx=10, closed='right', color=EYE):
    left, right = lx, m(lx) - 5
    open_x, shut_x = (left, right) if closed == 'right' else (right, left)
    _eye(c, open_x, y, color)
    _arc(c, shut_x, y, color)


def blush(c, y=26, lx=5, alpha=0.7):
    """볼터치: 고운 타원 반투명(가운데 진하게) + 작은 빗금 두 줄"""
    for side in (0, 1):
        cx = 2 * lx + 5 if side == 0 else 80 - (2 * lx + 5)
        cy = 2 * y + 2
        for x, yy in c.fellipse(cx, cy, 5.4, 2.6):
            d = ((x + 0.5 - cx) / 5.4) ** 2 + ((yy + 0.5 - cy) / 2.6) ** 2
            c.fsoft([(x, yy)], BLUSH, alpha * (1.0 - 0.45 * d))
        for k in (-2, 1):
            c.fsoft([(cx + k, cy), (cx + k + 1, cy - 1)], 'ffffff', 0.55)


def nose(c, y=25, color='2e2228'):
    """작은 둥근 세모 코 + 윤기"""
    fy = 2 * y
    for r, w in enumerate((7, 5, 3)):
        for x in range(40 - (w + 1) // 2, 40 - (w + 1) // 2 + w):
            c.fdot(x, fy + r, color)
    c.fdot(38, fy, mix(hexc(color), hexc('ffffff'), 0.45))


def _pts(c, pts, color):
    for x, y in pts:
        c.fdot(x, y, color)


def mouth_w(c, y=27, color='7a5262'):
    """ω 입(가는 선)"""
    fy = 2 * y
    _pts(c, [(34, fy), (35, fy + 1), (36, fy + 2), (37, fy + 2), (38, fy + 1), (39, fy), (40, fy),
             (41, fy + 1), (42, fy + 2), (43, fy + 2), (44, fy + 1), (45, fy)], color)


def mouth_smile(c, y=27, color='7a5262'):
    fy = 2 * y
    _pts(c, [(35, fy), (36, fy + 1), (37, fy + 2), (38, fy + 2), (39, fy + 2), (40, fy + 2), (41, fy + 2), (42, fy + 2), (43, fy + 1), (44, fy)], color)


def mouth_flat(c, y=28, color='5a3a30'):
    fy = 2 * y
    _pts(c, [(x, fy) for x in range(36, 44)], color)


def mouth_open(c, y=27, color='5a2430', inside='a03a4c', tongue='ff8fa3'):
    """활짝 웃는 입(D): 위는 반듯, 아래는 둥글게, 혀와 윤기"""
    fy = 2 * y
    shape = {(x, yy) for x, yy in c.fellipse(39.5, fy, 5.6, 6.2) if yy >= fy}
    inner = {(x, yy) for x, yy in c.fellipse(39.5, fy, 4.4, 5.0) if yy >= fy + 1}
    _paint(c, shape, color)
    _paint(c, inner, inside)
    _paint(c, c.fellipse(39.5, fy + 5.4, 3.2, 2.2) & inner, tongue)
    c.fdot(36, fy + 1, 'ffc0cc')


def mouth_o(c, y=27, color='5a2430'):
    fy = 2 * y + 3
    ring = c.fellipse(39.5, fy, 2.6, 2.6)
    _paint(c, ring, color)
    _paint(c, c.fellipse(39.5, fy + 0.4, 1.5, 1.5), 'ff8fa3')


def mouth_tongue(c, y=27, color='6a3a30'):
    """메롱"""
    mouth_smile(c, y, color)
    fy = 2 * y
    _paint(c, c.fellipse(41.5, fy + 4.2, 2.4, 2.6), 'ff7f98')
    c.fdot(41, fy + 3, 'ffb0c0')


def body(c, tone, rx=10.4):
    return c.add(c.ellipse(AX, 37.6, rx, 4.4), tone, shade=2, light=True)


def paws(c, tone, lx=12.6, w=3.9, y=35.4):
    """판을 짚은 동그란 앞발 두 개"""
    c.add(c.ellipse(lx, y, w, 2.8), tone, shade=2, light=True)
    c.add(c.ellipse(40 - lx, y, w, 2.8), tone, shade=2, light=True)


def toes(c, color, y=36):
    c.dot_all([(11, y), (14, y), (m(11), y), (m(14), y)], color)


def mirror_add(c, pts, tone, **kw):
    c.add(c.poly(pts), tone, **kw)
    c.add(c.poly(c.mirror(pts, AX)), tone, **kw)


def ellipse_pair(c, cx, cy, rx, ry):
    return c.ellipse(cx, cy, rx, ry) | c.ellipse(40 - cx, cy, rx, ry)


# ---------------------------------------------------------------- HD 손질 부품(2026-10-23, PIXEL_GUIDE.md)

def cast(c, y, x0, x1, color, alpha=0.32):
    """모자·고글이 이마에 드리우는 그림자 한 줄(양끝 세 칸은 옅게)"""
    for x in range(x0, x1 + 1):
        edge = min(x - x0, x1 - x)
        c.soft([(x, y)], color, alpha * (0.55 if edge < 3 else 1))


def tufts(c, tone, pts=None):
    """볼 옆 털 뭉치(v3 미니멀): 뾰족한 가시 대신 둥근 몽글 두 개(좌우 대칭)"""
    c.add(ellipse_pair(c, 5.4, 27.4, 2.4, 2.1) | ellipse_pair(c, 6.6, 30.0, 2.2, 1.8), tone, shade=1, light=True)


def crown_tuft(c, tone, x=20):
    """정수리 삐죽 털"""
    c.add(c.poly([(x - 2.4, 10.8), (x - 1.4, 7.4), (x, 9.6), (x + 1.4, 6.8), (x + 2.6, 10.8)]), tone, shade=1, light=True)


def stitch(c, y, x0, x1, color, step=2):
    """옷 바늘땀(v3 미니멀: 고운 점으로 성기게)"""
    for x in range(x0, x1 + 1, step * 2):
        c.fdot(2 * x, 2 * y + 1, color)


def glint(c, x, y, hi='fff6c8'):
    """단추·금속 윤기: 왼쪽 위 한 칸"""
    c.dot(x, y, hi)
