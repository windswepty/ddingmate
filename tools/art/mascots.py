"""HUD 친구: 띵타이쿤 동물 NPC 17명(위키 상점·상점가·특수 상점 문서, 2026-10-22).

서버 그림을 옮기지 않고, 사용자가 보내 준 게임 속 NPC 모델 사진의 동물·옷·소품·색만 보고 이 모드에서 새로 그렸다.
NPC 머리 위 케이크·촛불(행사 장식)은 빼라는 요청대로 넣지 않았다.

SD(꼬마) 도트 원칙(2026-10-22 사용자: "캐주얼하고 귀염뽀짝하게", "픽셀을 더 써서라도 더 이쁘게"):
- 40×40, 머리가 그림의 2/3: 넓적한 찹쌀떡 머리 + 아주 작은 몸 + 판을 짚은 동그란 앞발
- 눈·코·입은 얼굴 아래쪽에 모으고 이마는 넓게, 눈 사이는 넓게
- 눈은 4×5 SD 눈: 짙은 색 + 아래쪽 은은한 보라 반사 + 왼쪽 위 흰 반짝 한 점(두 눈 같은 쪽)
- 볼터치는 반투명으로 털에 섞고, 입은 작게(ω·ㅅ). 표정은 NPC 성격대로
- 명암 3단(밝은 테두리·그늘·가장 바깥 짙은 그늘), 외곽선은 그 부위 색을 어둡게 + 계단 모서리는 반투명으로 부드럽게
- NPC 마다 알아볼 표시 하나를 크게(밀짚모자·용접 고글·요리사 모자·뿔 …)
가운데 세로선은 x=20(왼쪽 칸 x 의 짝은 39 - x, 연속 좌표는 40 - x).
"""
from px import Canvas, Tone

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
    c = Canvas(W, H, 0)
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


def _eye(c, x0, y, color=EYE, color2=EYE2):
    for dy in range(5):
        for dx in range(4):
            if dy in (0, 4) and dx in (0, 3):
                continue
            c.dot(x0 + dx, y + dy, color2 if dy >= 3 and dx in (1, 2) else color)
    # 반짝 두 개(2026-10-23 HD): 큰 것 왼쪽 위 세로 2칸 + 작은 것 오른쪽 아래(옅은 보라)
    c.dot(x0 + 1, y + 1, WHITE); c.dot(x0 + 1, y + 2, WHITE)
    c.dot(x0 + 2, y + 3, 'c9bdf0')


def eyes(c, y=22, lx=10, color=EYE):
    """SD 눈 두 개(4×5). 반짝은 두 눈 모두 왼쪽 위"""
    _eye(c, lx, y, color)
    _eye(c, m(lx) - 3, y, color)


def happy_eyes(c, y=23, lx=10, color=EYE):
    """웃는 눈(^ ^)"""
    for x0 in (lx, m(lx) - 3):
        c.dot(x0, y + 2, color); c.dot(x0 + 1, y + 1, color); c.dot(x0 + 2, y + 1, color); c.dot(x0 + 3, y + 2, color)
        c.dot(x0, y + 3, color); c.dot(x0 + 3, y + 3, color)


def sleepy_eyes(c, y=23, lx=10, color=EYE):
    """느긋하게 반쯤 감은 눈: 눈꺼풀 선 + 납작한 눈"""
    for x0 in (lx, m(lx) - 3):
        c.dot_all([(x0 - 1, y), (x0, y), (x0 + 1, y), (x0 + 2, y), (x0 + 3, y), (x0 + 4, y)], color)
        c.dot_all([(x0, y + 1), (x0 + 1, y + 1), (x0 + 2, y + 1), (x0 + 3, y + 1)], color)
        c.dot_all([(x0 + 1, y + 2), (x0 + 2, y + 2)], EYE2)
        c.dot(x0 + 1, y + 1, WHITE)


def wink(c, y=22, lx=10, closed='right', color=EYE):
    """한쪽 눈은 SD 눈, 다른 쪽은 ^"""
    left, right = lx, m(lx) - 3
    open_x, shut_x = (left, right) if closed == 'right' else (right, left)
    _eye(c, open_x, y, color)
    c.dot(shut_x, y + 3, color); c.dot(shut_x + 1, y + 2, color); c.dot(shut_x + 2, y + 2, color); c.dot(shut_x + 3, y + 3, color)
    c.dot(shut_x, y + 4, color); c.dot(shut_x + 3, y + 4, color)


def blush(c, y=27, lx=6, alpha=0.55):
    pts = [(lx + dx, y) for dx in range(4)] + [(lx + 1, y + 1), (lx + 2, y + 1)]
    c.soft(pts + [(m(x), yy) for x, yy in pts], BLUSH, alpha)
    c.dot(lx + 1, y, 'ffc0cc'); c.dot(m(lx + 1), y, 'ffc0cc')  # 빗금 한 점(HD)


def nose(c, y=25, color='2e2228'):
    c.dot_all([(18, y), (19, y), (20, y), (21, y), (19, y + 1), (20, y + 1)], color)
    c.dot(18, y, '6a5a60')


def mouth_w(c, y=27, color='7a5262'):
    c.dot_all([(17, y), (18, y + 1), (19, y), (20, y), (21, y + 1), (22, y)], color)


def mouth_smile(c, y=27, color='7a5262'):
    c.dot_all([(17, y), (18, y + 1), (19, y + 1), (20, y + 1), (21, y + 1), (22, y)], color)


def mouth_flat(c, y=28, color='5a3a30'):
    c.dot_all([(18, y), (19, y), (20, y), (21, y)], color)


def mouth_open(c, y=27, color='5a2430', inside='a03a4c', tongue='ff8fa3'):
    """활짝 웃는 입(D)"""
    c.dot_all([(x, y) for x in range(17, 23)], color)
    c.dot(16, y, color); c.dot(23, y, color)
    c.dot(16, y + 1, color); c.dot(23, y + 1, color)
    c.dot_all([(17, y + 1), (22, y + 1)], inside)
    c.dot_all([(18, y + 1), (19, y + 1), (20, y + 1), (21, y + 1)], tongue)
    c.dot(17, y + 2, color); c.dot(22, y + 2, color)
    c.dot_all([(18, y + 2), (19, y + 2), (20, y + 2), (21, y + 2)], tongue)
    c.dot_all([(18, y + 3), (19, y + 3), (20, y + 3), (21, y + 3)], color)
    c.dot(18, y + 1, 'ffc0cc')


def mouth_o(c, y=27, color='5a2430'):
    c.dot_all([(19, y), (20, y), (18, y + 1), (21, y + 1), (18, y + 2), (21, y + 2), (19, y + 3), (20, y + 3)], color)
    c.dot_all([(19, y + 1), (20, y + 1), (19, y + 2), (20, y + 2)], 'ff8fa3')


def mouth_tongue(c, y=27, color='6a3a30'):
    """메롱"""
    mouth_smile(c, y, color)
    c.dot_all([(20, y + 2), (21, y + 2), (20, y + 3), (21, y + 3), (21, y + 4)], 'ff7f98')
    c.dot(20, y + 2, 'ffb0c0')


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


def tufts(c, tone, pts=((5.4, 25.6), (2.8, 27.4), (5.4, 28.2), (4, 30.4), (7.6, 29.8))):
    """볼 옆 털 뭉치(좌우 대칭): 매끈한 찹쌀떡 윤곽을 깨서 털 느낌"""
    pts = list(pts)
    c.add(c.poly(pts) | c.poly(c.mirror(pts, AX)), tone, shade=1, light=True)


def crown_tuft(c, tone, x=20):
    """정수리 삐죽 털"""
    c.add(c.poly([(x - 2.4, 10.8), (x - 1.4, 7.4), (x, 9.6), (x + 1.4, 6.8), (x + 2.6, 10.8)]), tone, shade=1, light=True)


def stitch(c, y, x0, x1, color, step=2):
    """옷 바늘땀"""
    for x in range(x0, x1 + 1, step):
        c.dot(x, y, color)


def glint(c, x, y, hi='fff6c8'):
    """단추·금속 윤기: 왼쪽 위 한 칸"""
    c.dot(x, y, hi)

# ================================================================ 스폰 상점

# 보리 HD 색 띠: 그늘은 붉은·보라 쪽, 밝음은 레몬 쪽(손으로 고름)
FUR = Tone('fffbf2', 'f3dcb6', 'd8ae8e', '8f6656')       # 그늘이 붉은 갈색 쪽(차갑게)
EAR = Tone('ecc89a', 'd6a476', 'b07c62', '6e4636')
STRAW = Tone('fff6c2', 'f5d168', 'd9a64a', '7f5520')     # 밝음은 레몬, 그늘은 주황 갈색
BAND = Tone('ffc07a', 'f08a3e', 'c45e34', '6e2a14')
OVER = Tone('a6c8fa', '6694dc', '4a6cb8', '23346e')      # 그늘이 보라 쪽
SHIRT = Tone('ffffff', 'fbf7f0', 'e6dcd0', '9a8c80')


def bori():
    """보리 · 햇살 농가의 성실한 강아지 농부(2026-10-23 HD 판: 털 뭉치 윤곽, 챙 그림자, 엮은 밀짚, 분홍 발바닥)"""
    c = Canvas(W, H, 0)  # 손으로 고른 HD 색 띠라 자동 hue shift 는 끈다
    # 몸: 흰 셔츠 위 파란 멜빵바지(가운데 가슴판 + 멜빵 두 줄)
    c.add(c.ellipse(AX, 37.6, 10.6, 4.6), SHIRT, shade=2, light=True)
    c.add(c.ellipse(AX, 38.4, 7.4, 3.6) | c.rect(13, 35, 15, 40) | c.rect(25, 35, 27, 40), OVER, shade=2, light=True)
    # 귀: 늘어진 귀 + 끝의 털 뭉치
    ears = ellipse_pair(c, 5.4, 23.4, 4.4, 7.8) | ellipse_pair(c, 4.6, 30.4, 2.6, 2.2)
    c.add(ears, EAR, shade=2, light=True)
    # 머리 + 볼 털 뭉치(실루엣이 매끈한 타원이 되지 않게)
    head = c.ellipse(AX, 21.8, 15.2, 11.6) | ellipse_pair(c, 6.6, 27.6, 2.4, 2.2) | ellipse_pair(c, 8.4, 30.2, 2.2, 1.8)
    c.add(head, FUR, shade=3, light=True)
    c.add(c.ellipse(AX, 27.8, 6.2, 3.9), Tone('ffffff', 'fffcf6', 'f0e2d0', 'a89480'), shade=1)  # 흰 주둥이
    # 모자: 위 덩어리 + 넓은 챙 + 띠
    crown = c.ellipse(AX, 7.2, 8.8, 5.4) - c.rect(0, 10, W, H)
    c.add(crown, STRAW, shade=2, light=True)
    brim = c.ellipse(AX, 10.8, 17.2, 3.2)
    c.add(brim, STRAW, shade=2, light=True)
    c.add(c.rect(11, 7, 30, 9.6) & c.ellipse(AX, 7.2, 8.8, 5.4), BAND, shade=0)
    # 앞발(분홍 발바닥이 살짝 보이게)
    for lx in (12.6, 27.4):
        c.add(c.ellipse(lx, 35.4, 3.9, 2.8), FUR, shade=2, light=True)
    # ---- 그리기 위에 얹는 칸들
    # 모자 챙이 이마에 드리우는 그림자(가운데 진하게, 양끝 옅게)
    for x in range(8, 32):
        a = 0.42 if 12 <= x <= 27 else 0.25
        c.soft([(x, 14), (x, 15)] if 10 <= x <= 29 else [(x, 14)], '8f6656', a)
    # 밀짚 엮음: 챙에만, 두 줄마다 엇갈린 짧은 결(점이 흩뿌려진 노이즈가 되지 않게 성기게)
    for y in (10, 12):
        for x in range(5, 36):
            if (x, y) in brim and (x + (y // 2) * 3) % 6 in (0, 1):
                c.dot(x, y, 'e2b252')
    for x in range(12, 30, 3):  # 띠 결
        c.dot(x, 8, 'ffb26a')
    # 해바라기(5×5, 꽃잎 두 단 + 씨 두 단)
    for (x, y), col in {(25, 5): 'ffd23a', (27, 5): 'ffd23a', (26, 4): 'ffe27a', (26, 6): 'f4a81a', (24, 5): 'f4a81a', (28, 5): 'f4a81a',
                        (26, 3): 'ffd23a', (26, 7): 'e8901a'}.items():
        c.dot(x, y, col)
    c.dot(26, 5, '7a3e14'); c.dot(25, 4, 'ffd23a'); c.dot(27, 6, 'e8901a')
    shine(c, 10, 16)
    c.dot(14, 4, 'fffbe0'); c.dot(15, 4, 'fffbe0'); c.dot(14, 5, 'fffbe0')  # 모자 윤기
    # 눈 · 코 · 입
    eyes(c, 20)
    c.dot_all([(18, 24), (19, 24), (20, 24), (21, 24), (19, 25), (20, 25)], '2e2228')
    c.dot(19, 24, '7a6a74')  # 코 윤기
    mouth_open(c, 27)
    c.dot(19, 29, 'ffc4d0')
    # 볼터치(반투명 + 빗금 두 점)
    pts = [(6, 26), (7, 26), (8, 26), (9, 26), (7, 27), (8, 27)]
    c.soft(pts + [(m(x), y) for x, y in pts], 'ff8fa8', 0.55)
    c.dot_all([(7, 26), (m(7), 26)], 'ffc0cc')
    # 멜빵 단추 · 가슴 주머니 바늘땀
    for x in (14, m(14)):
        c.dot(x, 36, 'ffd65a'); c.dot(x, 35, 'fff2a8')
    for x in range(17, 23, 2):
        c.dot(x, 37, '8fb4f0')
    # 발가락 사이 · 분홍 발바닥
    for x in (11, 14, m(11), m(14)):
        c.dot(x, 36, 'c49a80')
    c.dot_all([(12, 37), (13, 37), (m(12), 37), (m(13), 37)], 'f6b0b8')
    return c


def dodam():
    """도담 · 햇살 농가의 느긋한 염소 농부: 연회색 털, 뒤로 휜 크림색 뿔, 옆으로 뻗은 귀, 흰 턱수염, 졸린 눈, 초록 앞치마"""
    c = canvas()
    fur = Tone('ffffff', 'ececf0', 'd2d3dc', '8c8e9a')
    horn = Tone('fff8e2', 'ecdcb8', 'cfba90', '7c6a48')
    apron = Tone('a2dc8e', '68b058', '4c8e44', '2a5626')
    inner = Tone('ffd6de', 'f8b6c6', 'e098ac')
    body(c, Tone('c49a74', 'a87a54', '86603e', '4a3018'))
    c.add(c.rect(13, 34, 27, 40), apron, shade=1)
    mirror_add(c, [(14, 12), (12.6, 6.6), (10, 3.2), (7.4, 2.6), (8.8, 6), (10.4, 11.6)], horn, shade=2, light=True)
    c.add(ellipse_pair(c, 3.6, 19.6, 4.6, 2.2), fur, shade=2)
    c.add(ellipse_pair(c, 3.2, 19.6, 2.4, 1), inner, shade=0)
    head(c, fur, rx=14.2)
    c.add(c.poly([(16.6, 30), (23.4, 30), (20, 37)]), Tone('ffffff', 'ffffff', 'e6e6ec'), shade=1)  # 턱수염
    paws(c, fur)
    for x0 in (9, 13):  # 뿔 마디
        c.dot(x0, 6, 'b8a47c'); c.dot(m(x0), 6, 'b8a47c')
    shine(c, 10, 14)
    sleepy_eyes(c, 23)
    blush(c, 26, 6)
    c.dot_all([(19, 26), (20, 26)], '8a8a96')
    mouth_smile(c, 27, '8a8a96')
    c.dot_all([(22, 29), (23, 29), (24, 28), (25, 28), (26, 27), (27, 27)], 'f2cc50')  # 입에 문 지푸라기
    c.dot_all([(31, 4), (32, 4), (33, 4), (32, 5), (31, 6), (32, 6), (33, 6)], '9cb6e8')  # 졸음 z
    c.dot_all([(35, 1), (36, 1), (35, 2), (36, 2)], 'b8ccf0')
    # HD: 볼 털 뭉치, 뿔 윤기, 앞치마 주머니 바늘땀
    tufts(c, fur)
    c.dot_all([(9, 4), (10, 3), (m(9), 4), (m(10), 3)], 'fffdf2')
    stitch(c, 37, 15, 25, 'a8e08e')
    return c


def seoki():
    """석이 · 강철 채석장의 무뚝뚝한 카피바라 광부: 넓적한 갈색 얼굴, 큰 주둥이, 램프 단 노란 안전모, 반사띠 주황 조끼, 땀 한 방울"""
    c = canvas()
    fur = Tone('e2b688', 'c4955f', 'a47848', '5e4024')
    snout = Tone('b88656', 'a07242', '845a32', '4a3018')
    helmet = Tone('fff2a8', 'f6cc3a', 'd4a426', '8a6410')
    vest = Tone('ffc884', 'f69632', 'd0721a', '7a3e06')
    body(c, vest)
    c.add(c.rect(10, 36, 30, 37), Tone('ffffff', 'f2f6f8', 'cfd8de'), shade=0)  # 반사띠
    c.add(ellipse_pair(c, 8.4, 12.8, 2.8, 2.4), fur)
    c.add(c.ellipse(AX, 22, 15.6, 11.2) | c.rect(6, 15, 34, 30), fur, shade=3, light=True)
    c.add(c.ellipse(AX, 27, 8.6, 4.6), snout, shade=2, light=True)
    c.add(c.ellipse(AX, 13.4, 11.6, 6.8) - c.rect(0, 14.4, W, H), helmet, shade=2, light=True)
    c.add(c.rect(6, 13, 34, 15.6), helmet, shade=1)
    c.add(c.rect(17, 6.6, 23, 11), Tone('ffffff', 'fffbe0', 'ece0a0', '8a7a3a'), shade=1)  # 램프
    paws(c, fur)
    c.dot_all([(18, 7), (19, 7), (18, 8)], 'ffffff')
    c.soft([(x, 5) for x in range(17, 23)], 'fff4b0', 0.5)
    c.dot_all([(12, 9), (13, 9), (12, 10)], 'fffbe0')
    for x0 in (10, m(10) - 3):  # 무뚝뚝 눈: 납작
        c.dot_all([(x0 + dx, 21) for dx in range(4)] + [(x0 + dx, 22) for dx in range(4)], EYE)
        c.dot(x0 + 1, 21, WHITE)
    blush(c, 24, 5, 0.45)
    c.dot_all([(16, 25), (17, 25), (22, 25), (23, 25)], '4a2c14')  # 콧구멍
    mouth_flat(c, 29, '5a3818')
    c.dot_all([(31, 17), (31, 18), (30, 18), (31, 19)], 'b8e2ff'); c.dot(31, 17, 'ffffff')  # 땀 한 방울
    toes(c, '7a5432')
    # HD: 안전모 챙 그림자, 모자 윗줄 윤기, 볼 털 뭉치, 반사띠 반짝
    cast(c, 16, 7, 33, '5e4024', 0.38)
    c.dot_all([(x, 8) for x in range(14, 17)], 'fffbe0')
    tufts(c, fur, ((5.6, 25), (3, 27), (5.6, 27.6), (4.4, 29.6), (7.6, 29.2)))
    c.dot_all([(12, 36), (26, 36)], 'ffffff')
    return c


def duri():
    """두리 · 강철 채석장의 밝은 두더지 대장장이: 짙은 갈색 털, 큰 분홍 코와 수염, 램프 단 노란 안전모, 분홍 앞발과 하얀 발톱"""
    c = canvas()
    fur = Tone('9a7a66', '725444', '583e32', '2c1e18')
    snout = Tone('ffdce2', 'f7aab6', 'de8898', '8a4454')
    helmet = Tone('fff2a8', 'f6cc3a', 'd4a426', '8a6410')
    shirt = Tone('fae8c4', 'e2c69a', 'c4a476', '6a5434')
    paw = Tone('ffdce2', 'f8b6c0', 'de94a0')
    body(c, shirt)
    c.add(c.rect(13, 34, 15, 40) | c.rect(25, 34, 27, 40), Tone('a06a44', '8a5636', '6a3e24'), shade=0)  # 멜빵
    head(c, fur)
    c.add(c.ellipse(AX, 13, 12.6, 7.4) - c.rect(0, 14, W, H), helmet, shade=2, light=True)
    c.add(c.rect(5, 13, 35, 15.8), helmet, shade=1)
    c.add(c.rect(16.4, 6, 23.6, 11.6), Tone('ffffff', 'fbfbf4', 'e0e0d4', '6a6a60'), shade=1)  # 하얀 램프
    c.add(c.ellipse(AX, 26.4, 4.8, 3.2), snout, shade=2, light=True)  # 큰 분홍 코
    paws(c, paw)
    c.dot_all([(17, 7), (18, 7), (17, 8)], 'fffff0')
    c.soft([(x, 4) for x in range(16, 24)], 'fff4b0', 0.4)
    c.dot_all([(11, 9), (12, 9), (11, 10)], 'fffbe0')
    happy_eyes(c, 19)
    c.dot_all([(18, 25), (19, 25)], 'fff2f4')
    c.dot_all([(5, 25), (6, 25), (7, 26), (5, 28), (6, 28), (7, 28)], 'd2bcae')  # 수염
    c.dot_all([(m(5), 25), (m(6), 25), (m(7), 26), (m(5), 28), (m(6), 28), (m(7), 28)], 'd2bcae')
    mouth_smile(c, 30, '3a2620')
    blush(c, 24, 5, 0.5)
    for x0 in (10, 26):  # 하얀 발톱
        c.dot_all([(x0, 37), (x0 + 2, 37), (x0 + 4, 37)], 'fff8f8')
    # HD: 안전모 챙 그림자, 멜빵 단추 윤기
    cast(c, 16, 6, 34, '2c1e18', 0.4)
    c.dot_all([(14, 35), (m(14), 35)], 'f6cc3a'); glint(c, 14, 34); glint(c, m(14), 34)
    c.dot_all([(x, 8) for x in range(13, 16)], 'fffbe0')
    return c


def deumjik():
    """듬직 · 숲속 공방의 우직한 황소 나무꾼: 황갈색 얼굴, 흰 주둥이, 바깥으로 휜 뿔, 무늬 새긴 투구, 굳은 눈썹, 기운 자국 파란 멜빵"""
    c = canvas()
    fur = Tone('f8ca92', 'e4a866', 'c68846', '7a4e24')
    muzzle = Tone('ffffff', 'fbf5ef', 'e8ddd2', '9a8a80')
    horn = Tone('ece6dc', 'cbc0b0', 'a29684', '5a5044')
    helm = Tone('948e86', '6e6860', '524c46', '2a2824')
    over = Tone('94bcf6', '5e8ed8', '4270b8', '23447a')
    body(c, Tone('5e5e68', '46464e', '323238'))
    c.add(c.rect(12, 34, 28, 40), over, shade=1)
    mirror_add(c, [(8, 16), (3.4, 14), (1.2, 9.4), (1.6, 6), (3.4, 7.4), (5, 11.4), (8.8, 12.4)], horn, shade=2, light=True)
    c.add(ellipse_pair(c, 4, 20, 3.8, 2.2), fur, shade=2)
    h = head(c, fur)
    c.add(c.ellipse(AX, 14.6, 14.2, 6.6) & (h | c.rect(4, 10, 36, 16)), helm, shade=2, light=True)  # 투구
    c.add(c.ellipse(AX, 27.4, 8.2, 4.4), muzzle, shade=2, light=True)
    paws(c, fur)
    for x0, y0 in ((9, 12), (13, 10), (17, 9), (22, 9), (26, 10), (30, 12)):  # 투구 소용돌이 무늬
        c.dot(x0, y0, 'd6bf86'); c.dot(x0 + 1, y0, 'd6bf86'); c.dot(x0 + 1, y0 + 1, 'b89c5c')
    c.dot_all([(8, 13), (9, 14)], 'b8b2aa')
    eyes(c, 20)
    c.dot_all([(9, 18), (10, 18), (11, 18), (12, 18), (13, 19)], '5a3a20')  # 굳은 눈썹
    c.dot_all([(m(9), 18), (m(10), 18), (m(11), 18), (m(12), 18), (m(13), 19)], '5a3a20')
    blush(c, 25, 5)
    c.dot_all([(16, 27), (17, 27), (22, 27), (23, 27)], 'f2a2ae')  # 분홍 콧구멍
    mouth_smile(c, 29, 'a89a90')
    c.dot_all([(15, 37), (16, 37), (15, 38), (16, 38)], '3a62a8'); c.dot_all([(14, 37), (17, 38)], 'ffffff')  # 기운 자국
    # HD: 볼 털 뭉치, 주둥이 윤기, 멜빵 바늘땀
    tufts(c, fur)
    c.dot_all([(15, 26), (16, 26)], 'ffffff')
    stitch(c, 39, 19, 27, '8fb4f0')
    return c


def hotang():
    """호탕 · 숲속 공방의 쾌활한 늑대 사냥꾼: 회색 털, 뾰족 귀, 삐죽 흰 볼털, 윙크, 크게 웃는 입, 털 깃 빨간 사냥 코트"""
    c = canvas()
    fur = Tone('d6dae2', 'aeb4c0', '8e94a2', '4a4e5a')
    white = Tone('ffffff', 'f7f8fb', 'e0e3ea', '9ca0aa')
    coat = Tone('f6826e', 'da4c3e', 'b2362a', '6a1810')
    inner = Tone('ffd4da', 'f6aeb8', 'da929e')
    body(c, coat)
    c.add(c.ellipse(AX, 33.6, 8, 2), white, shade=1)  # 털 깃
    mirror_add(c, [(6.6, 16), (6, 3.4), (8, 2.2), (15.4, 10.6)], fur, shade=2, light=True)
    mirror_add(c, [(8.4, 12), (8, 6.2), (12.4, 10.6)], inner, shade=0)
    head(c, fur)
    tuft = [(4.6, 23), (2, 26.6), (6, 26), (5, 29.6), (9, 28)]
    c.add(c.poly(tuft) | c.poly(c.mirror(tuft, AX)) | ellipse_pair(c, 11.4, 26.4, 5.6, 4.2) | c.ellipse(AX, 27.4, 5.4, 4), white, shade=1)  # 흰 볼털·주둥이
    paws(c, fur)
    shine(c, 10, 14)
    wink(c, 20, 10, 'right')
    c.dot_all([(9, 18), (10, 17), (11, 17), (12, 17)], '6a6e7a'); c.dot_all([(m(9), 18), (m(10), 17), (m(11), 17), (m(12), 17)], '6a6e7a')
    nose(c, 24, '3a3440')
    mouth_open(c, 27)
    c.dot(16, 26, 'ffffff'); c.dot(23, 26, 'ffffff')  # 송곳니
    c.dot_all([(16, 36), (16, 38), (m(16), 36), (m(16), 38)], 'f6ca3a')  # 금 단추
    toes(c, '8e94a2')
    # HD: 정수리 삐죽 털, 귀 안 그늘, 코트 깃 그늘
    crown_tuft(c, fur)
    c.dot_all([(9, 9), (m(9), 9)], 'da929e')
    c.soft([(x, 35) for x in range(12, 29)], '6a1810', 0.25)
    return c


def hani():
    """하니 · 꽃잎 공방의 수다스러운 토끼 디자이너: 흰 털, 한쪽 귀 쫑긋·한쪽 접힘, 회색 베레모, 귀에 꽂은 붓, 반짝 눈, 물감 묻은 앞치마"""
    c = canvas()
    fur = Tone('ffffff', 'f8f7fb', 'e2deea', '9a96aa')
    inner = Tone('ffdae2', 'ffb8c8', 'ec98ac')
    beret = Tone('c0bcb6', '9c9892', '7a766f', '3e3a34')
    apron = Tone('c89272', 'a8704f', '84543a', '42261a')
    body(c, Tone('ffffff', 'f4f4f8', 'dadce4'))
    c.add(c.rect(12, 34, 28, 40), apron, shade=1)
    c.add(c.poly([(10.4, 14), (9.2, 3.4), (10.8, 0.6), (13.8, 0.6), (15.4, 3.4), (15.6, 14)]), fur, shade=2, light=True)  # 쫑긋 귀
    c.add(c.poly([(11.8, 12), (11.6, 3.6), (12.6, 2.2), (13.8, 3.6), (14, 12)]), inner, shade=0)
    c.add(c.poly([(23, 13.4), (28.4, 8.6), (36.4, 8.8), (37.6, 11.6), (29.8, 12.4), (25.4, 15.4)]), fur, shade=2, light=True)  # 접힌 귀
    c.add(c.poly([(27.4, 10.4), (35.4, 10.2), (29.4, 11.4)]), inner, shade=0)
    head(c, fur)
    c.add(c.ellipse(23.6, 11, 10, 3.6), beret, shade=2, light=True)
    c.add(c.rect(23, 6, 26, 8), beret, shade=0)
    c.add(c.poly([(32, 19), (35, 13), (36.4, 13.6), (33.4, 19.6)]), Tone('fff09a', 'f6c945', 'c9961c'), shade=1)  # 귀에 꽂은 붓
    paws(c, fur)
    c.dot_all([(35, 13), (36, 13)], 'e05a6a')  # 붓 끝 물감
    c.dot_all([(17, 9), (18, 9), (19, 9)], 'd6d2cc')
    shine(c, 9, 16)
    eyes(c, 21)
    blush(c, 26, 5, 0.6)
    c.dot_all([(19, 25), (20, 25)], 'ff9aae')  # 분홍 코
    mouth_o(c, 27)
    c.dot_all([(1, 8), (1, 9), (1, 10), (0, 10), (2, 6), (3, 7), (2, 7)], 'ff8ab0')  # 수다 음표
    c.dot_all([(14, 36), (15, 36)], 'f05a5a'); c.dot_all([(23, 37), (24, 37)], '4a7ae0'); c.dot(19, 38, 'f6c63a')  # 물감 자국
    toes(c, 'd0ccdc')
    # HD: 베레모 그림자, 볼 털 뭉치, 앞치마 주머니 바늘땀
    cast(c, 14, 15, 32, '9a96aa', 0.3)
    tufts(c, fur, ((5.6, 26), (3.2, 27.6), (5.6, 28.4), (4.6, 30.2), (7.6, 29.6)))
    stitch(c, 38, 15, 25, 'd8a080')
    return c


def bareum():
    """바름 · 꽃잎 공방의 의젓한 라쿤 건축가: 회갈색 털, 눈가 짙은 무늬, 흰 눈썹·볼, 큰 까만 코, 회색 납작 모자, 흰 설계도 두루마리"""
    c = canvas()
    fur = Tone('dcd6cc', 'bab3a8', '9a9286', '544c44')
    mask = Tone('7e746c', '645a52', '4c443e', '24201c')
    white = Tone('ffffff', 'fbf9f5', 'e8e2d8')
    cap = Tone('c0bcb6', '96928c', '74706a', '38342e')
    body(c, Tone('ffffff', 'f4f4f6', 'd8dae0'))
    c.add(c.rect(12, 34, 28, 40), Tone('7a6050', '5e4838', '46342a'), shade=1)  # 조끼
    c.add(ellipse_pair(c, 7.4, 13, 3.8, 3.8), fur, shade=2)
    c.add(ellipse_pair(c, 7.4, 13.4, 1.9, 1.9), mask, shade=0)
    head(c, fur)
    c.add(ellipse_pair(c, 11.8, 22.4, 5.4, 3.8) | c.rect(10, 20, 30, 23.6), mask, shade=1)  # 눈가 무늬
    c.add(c.ellipse(AX, 28, 6.6, 3.8), white, shade=1)
    c.add(ellipse_pair(c, 11.4, 18, 3.2, 1), white, shade=0)  # 흰 눈썹
    c.add(c.ellipse(AX, 12.6, 12.6, 4) - c.rect(0, 13.6, W, H), cap, shade=2, light=True)  # 납작 모자
    c.add(c.rect(6, 12.6, 34, 14.8), cap, shade=1)
    c.add(c.poly([(4, 34.4), (22, 30.8), (23, 34.2), (5, 38)]), Tone('ffffff', 'f8f4ec', 'dcd4c4', '8a8070'), shade=1)  # 설계도
    paws(c, white)
    eyes(c, 20, color='18121a')
    c.dot_all([(18, 25), (19, 25), (20, 25), (21, 25), (18, 26), (19, 26), (20, 26), (21, 26), (19, 27), (20, 27)], '1e1820')  # 큰 까만 코
    c.dot(18, 25, '5a5058')
    mouth_smile(c, 29, '7a7068')
    blush(c, 26, 4, 0.45)
    c.dot_all([(8, 34), (12, 33), (16, 32)], '5a8ae0')  # 설계도 선
    c.dot_all([(14, 10), (15, 10), (16, 10)], 'd6d2cc')
    # HD: 납작 모자 그림자, 흰 볼 털 뭉치, 조끼 단추 윤기
    cast(c, 15, 7, 33, '544c44', 0.32)
    tufts(c, white, ((5.4, 26.4), (2.8, 28.4), (5.6, 28.8), (4.4, 30.8), (7.8, 30.2)))
    c.dot_all([(20, 36), (20, 38)], 'd8c8b4'); glint(c, 20, 35, 'ffffff')
    return c


def nuri():
    """누리 · 갯바위 선착장의 순박한 펠리컨 어부: 흰 머리, 이마 까만 띠, 앞으로 쑥 내민 큰 주황 부리, 빨간 스카프, 청록 셔츠, 낚싯대"""
    c = canvas()
    feather = Tone('ffffff', 'f3f3f5', 'd8d8de', '8a8a92')
    beak = Tone('ffd27e', 'f8a23a', 'd87a1e', '7a3e08')
    pouch = Tone('ffe6ac', 'f8c466', 'dc9c3c', '7a4e12')
    scarf = Tone('ff8e7e', 'e2524a', 'ba362e', '6a1810')
    c.add(c.line([(35, 39), (37, 20), (38, 3)]), Tone('d8dce4', 'aab0ba', '7a808c'), shade=0)  # 낚싯대
    body(c, Tone('6ccdd8', '32a2b4', '1e7a8a', '0e4048'))
    c.add(c.poly([(10.6, 32.6), (29.4, 32.6), (26.4, 36), (20, 37.4), (13.6, 36)]), scarf, shade=1)
    head(c, feather, ry=11.4)
    c.add(c.ellipse(AX, 13.6, 13.4, 3.2) & c.ellipse(HX, HY, HRX, 11.4), Tone('4a4a52', '2a2a32', '1a1a20'), shade=0)  # 이마 까만 띠
    c.add(c.ellipse(AX, 29.4, 7.4, 4.6), pouch, shade=2, light=True)  # 부리 주머니
    c.add(c.ellipse(AX, 25.4, 8.6, 3.2), beak, shade=2, light=True)  # 윗부리
    paws(c, feather)
    c.dot_all([(38, 2), (38, 3)], 'ff5a5a'); c.dot(38, 4, 'ffffff')  # 찌
    eyes(c, 17)
    blush(c, 22, 4)
    c.dot_all([(14, 24), (15, 24), (16, 24)], 'ffecc4')
    c.dot_all([(x, 27) for x in range(13, 27)], 'c4681c')  # 부리 맞물림
    c.dot_all([(17, 25), (22, 25)], 'c4681c')  # 콧구멍
    # HD: 정수리 깃털, 스카프 매듭 그늘, 부리 윤기
    crown_tuft(c, feather)
    c.dot_all([(19, 35), (20, 35), (21, 35)], 'ba362e')
    c.dot_all([(15, 24), (16, 24), (17, 24)], 'fff4d8')
    return c


# ================================================================ 세레니티 마을 상점가

def kai():
    """카이 · 세레니티의 장난꾸러기 원숭이 농부: 갈색 털, 하트 모양 살구색 얼굴, 큰 귀, 이마의 금테 고글, 윙크하며 메롱, 초록 재킷"""
    c = canvas()
    fur = Tone('ba8458', '9a6642', '7a4c2e', '42261a')
    face = Tone('fff2e0', 'f6d6ae', 'ddb68a', '8a6440')
    gog = Tone('ffea94', 'e4ba42', 'ba8e24', '6a4c0a')
    lens = Tone('ffffff', 'eaf6fa', 'c8dee6')
    body(c, Tone('a2d478', '6ea84e', '50863a', '284618'))
    c.add(c.poly([(17, 32.6), (23, 32.6), (20, 36.4)]), Tone('f8f6f0', 'ece8de', 'cfc8b8'), shade=0)  # 셔츠 깃
    c.add(ellipse_pair(c, 3.4, 22.4, 4.4, 4.6), fur, shade=2)
    c.add(ellipse_pair(c, 3.4, 22.4, 2.4, 2.8), face, shade=0)
    head(c, fur, rx=14.4)
    c.add(ellipse_pair(c, 14, 22.6, 5.4, 5) | c.ellipse(AX, 27.2, 9, 4.8), face, shade=2, light=True)  # 하트 얼굴
    c.add(ellipse_pair(c, 12.4, 14.4, 3.8, 3.2), gog, shade=1)  # 고글 테
    c.add(ellipse_pair(c, 12.4, 14.4, 2.2, 1.7), lens, shade=0)
    c.add(c.rect(16, 13.6, 24, 15.2), Tone('6a4a34', '4e3424', '362418'), shade=0)
    paws(c, fur)
    c.dot_all([(11, 13), (12, 13)], 'ffffff'); c.dot_all([(27, 13), (28, 13)], 'ffffff')
    wink(c, 21, 10, 'left')
    blush(c, 26, 6, 0.6)
    c.dot_all([(19, 25), (20, 25)], '8a5a3a')
    mouth_tongue(c, 28, '7a4430')
    toes(c, '7a4c2e')
    # HD: 정수리 털, 고글 띠 그림자, 재킷 지퍼
    crown_tuft(c, fur)
    cast(c, 16, 9, 31, '42261a', 0.28)
    for y in (37, 39):
        c.dot(20, y, 'd8e8c0')
    return c


def roni():
    """로니 · 세레니티의 과묵한 곰 대장장이: 짙은 흑갈색 털, 적갈색 귀, 이마의 까만 용접 고글, 눈가 흰 무늬, 무거운 눈꺼풀, 가죽 앞치마"""
    c = canvas()
    fur = Tone('745e52', '54423a', '3e302a', '1a1412')
    ear = Tone('d08272', 'ae6050', '884236', '4a1e14')
    patch = Tone('ffffff', 'f2f2f4', 'd6d8de')
    muzzle = Tone('d6a882', 'b88a66', '9a6c4a', '4a2e1e')
    gog = Tone('5e5e68', '36363e', '24242a', '000000')
    body(c, Tone('ffffff', 'f2f2f4', 'd4d6dc'))
    c.add(c.rect(12, 34, 28, 40), Tone('b48e70', '906c52', '6e5038', '30201a'), shade=1)  # 가죽 앞치마
    c.add(ellipse_pair(c, 7.4, 12.6, 4, 4), ear, shade=2, light=True)
    c.add(ellipse_pair(c, 7.4, 12.8, 2, 2), Tone('e0a090', 'c0806e', 'a0624e'), shade=0)
    h = head(c, fur)
    c.add(ellipse_pair(c, 12, 23, 4.4, 3.4), patch, shade=1)  # 눈가 흰 무늬
    c.add(c.rect(4, 13, 36, 16) & (h | c.rect(4, 12, 36, 17)), gog, shade=0)  # 고글 띠
    c.add(ellipse_pair(c, 12.6, 14.4, 3.8, 3), gog, shade=1)  # 렌즈
    c.add(c.ellipse(AX, 27.6, 6, 3.8), muzzle, shade=2, light=True)
    paws(c, fur)
    c.dot_all([(11, 13), (12, 13), (11, 14)], '9a9aa8'); c.dot_all([(26, 13), (27, 13), (26, 14)], '9a9aa8')  # 렌즈 반짝
    for x0 in (10, m(10) - 3):  # 과묵: 무거운 눈꺼풀
        c.dot_all([(x0 - 1, 22), (x0, 22), (x0 + 1, 22), (x0 + 2, 22), (x0 + 3, 22), (x0 + 4, 22)], '3e302a')
        c.dot_all([(x0, 23), (x0 + 1, 23), (x0 + 2, 23), (x0 + 3, 23), (x0 + 1, 24), (x0 + 2, 24)], EYE)
        c.dot(x0 + 1, 23, WHITE)
    nose(c, 25, '140e10')
    mouth_flat(c, 29, '6a4632')
    blush(c, 26, 4, 0.4)
    c.dot(16, 36, 'd8b47a'); c.dot(m(16), 36, 'd8b47a')
    # HD: 고글 띠 그림자, 귀 안 윤기, 앞치마 주머니 바늘땀
    cast(c, 17, 7, 33, '1a1412', 0.35)
    c.dot_all([(6, 11), (m(6), 11)], 'f0b4a4')
    stitch(c, 37, 15, 25, 'd0aa86')
    return c


def milky():
    """밀키 · 세레니티의 먹보 소 요리사: 흰 얼굴에 까만 얼룩, 작은 뿔, 넓은 분홍 주둥이, 키 큰 요리사 모자, 행복한 눈, 입맛 다시는 혀"""
    c = canvas()
    fur = Tone('ffffff', 'f7f5f2', 'ddd7d0', '9a928a')
    spot = Tone('5a5a66', '3c3c46', '2a2a32', '16161a')
    muzzle = Tone('ffdada', 'f7b2b2', 'de8e8e', '8a4a4c')
    hat = Tone('ffffff', 'f9f9f9', 'e0e0e4', '9a9aa2')
    body(c, Tone('ffffff', 'f6f6f6', 'dcdce0'))
    c.add(c.poly([(12, 32.6), (28, 32.6), (25, 36), (20, 37.4), (15, 36)]), Tone('ff8e7e', 'e2524a', 'ba362e', '6a1810'), shade=1)
    c.add(c.ellipse(3.6, 19.6, 4.4, 2.2), fur, shade=2)
    c.add(c.ellipse(36.4, 19.6, 4.4, 2.2), spot, shade=2)  # 까만 귀
    c.add(c.ellipse(3.2, 19.6, 2.4, 1), Tone('ffd6de', 'f6b6c4', 'e09aac'), shade=0)
    c.add(ellipse_pair(c, 9, 13.4, 1.8, 2.4), Tone('fffaea', 'efe2c0', 'cfbf98'), shade=1)  # 작은 뿔
    h = head(c, fur)
    c.add(c.ellipse(28.6, 18.6, 5.4, 4.8) & h, spot, shade=0)  # 까만 얼룩
    c.add(c.ellipse(AX, 28.2, 8.4, 4.4), muzzle, shade=2, light=True)
    c.add(c.ellipse(14.4, 3.6, 4.4, 3.6) | c.ellipse(AX, 2.6, 5, 3.6) | c.ellipse(25.6, 3.6, 4.4, 3.6) | c.rect(11.6, 4, 28.4, 11.6), hat, shade=2, light=True)  # 요리사 모자
    c.add(c.rect(11, 9.6, 29, 11.8), hat, shade=1)
    paws(c, fur)
    c.dot_all([(15, 4), (19, 3), (24, 4)], 'e8e8ec'); c.dot_all([(15, 6), (20, 5), (24, 6)], 'e8e8ec')
    happy_eyes(c, 19)
    c.dot_all([(15, 27), (16, 27), (15, 28), (16, 28), (23, 27), (24, 27), (23, 28), (24, 28)], 'b05a62')  # 콧구멍
    c.dot_all([(18, 30), (19, 30)], '8a4a4c'); c.dot_all([(20, 30), (21, 30), (21, 31), (22, 30)], 'ff7f96')  # 입맛 다시는 혀
    blush(c, 23, 4, 0.6)
    c.dot_all([(20, 36), (20, 38)], 'c0c0c6')  # 요리사 옷 단추
    toes(c, 'cfc8c0')
    # HD: 요리사 모자 그림자, 볼 털 뭉치, 주둥이 윤기
    cast(c, 12, 11, 28, '9a928a', 0.3)
    tufts(c, fur)
    c.dot_all([(17, 26), (18, 26)], 'ffecec')
    return c


def sharky():
    """샤키 · 세레니티의 장난꾸러기 상어 어부: 파란 상어 머리, 흰 배, 아가미, 뾰족 이빨로 씩 웃는 입, 파란 지느러미 앞발"""
    c = canvas()
    skin = Tone('94caf8', '5294de', '3c70be', '1a3a70')
    belly = Tone('ffffff', 'f6f9fc', 'dde7f2', '7a8aa0')
    body(c, Tone('fff6e6', 'f2e4cc', 'dccaa8', '8f7b58'))
    c.add(c.poly([(16.4, 12), (21.6, 1.4), (24.6, 1.8), (25.6, 12)]), skin, shade=2, light=True)  # 등지느러미
    h = head(c, skin)
    c.add(c.ellipse(AX, 30.4, 13.4, 7.6) & h, belly, shade=2)  # 흰 배(얼굴 아래)
    paws(c, skin)
    shine(c, 10, 14)
    eyes(c, 18)
    c.dot_all([(5, 22), (5, 23), (6, 25), (6, 26)], '2a5096'); c.dot_all([(m(5), 22), (m(5), 23), (m(6), 25), (m(6), 26)], '2a5096')  # 아가미
    blush(c, 24, 5)
    c.dot_all([(13, 26), (14, 27), (m(13), 26), (m(14), 27)], '1a3a70')  # 씩 웃는 입
    c.dot_all([(x, 28) for x in range(15, 25)], '1a3a70')
    c.dot_all([(x, 29) for x in range(16, 24)], '3a2a4a')
    c.dot_all([(x, 30) for x in range(17, 23)], '1a3a70')
    for x in (15, 17, 19, 21, 23):  # 뾰족 이빨
        c.dot(x, 28, 'ffffff')
    c.dot_all([(18, 29), (19, 29), (20, 29), (21, 29)], 'ff8fa3')
    toes(c, '3c70be')
    # HD: 머리 위 물빛 윤기 곡선, 지느러미 끝 밝게, 배 아래 옅은 그늘
    c.soft([(x, 11 + (abs(x - 15) // 3)) for x in range(10, 20)], 'ffffff', 0.45)
    c.dot_all([(22, 3), (23, 3)], 'c8e4ff')
    c.soft([(x, 33) for x in range(12, 29)], '7a8aa0', 0.25)
    return c


def pos():
    """포스 · 세레니티의 바다를 사랑하는 문어 해적 선장: 보라 문어, 깃털 단 금테 선장 삼각모(해골·뼈다귀), 안대, 치켜든 눈썹,
    씩 웃는 입, 금 귀걸이, 금 견장 선장 코트와 줄무늬 셔츠, 바깥으로 말린 다리"""
    c = canvas()
    skin = Tone('ecd4fe', 'bc92e8', '966ac8', '4a2a70')
    hat = Tone('4e4e5c', '24242c', '16161c', '000000')
    gold = Tone('fff4b0', 'f4c84c', 'c89a26', '6a4c0a')
    coat = Tone('a8504a', '84342e', '64241e', '30100c')
    plume = Tone('ff9a8a', 'e8463c', 'b42a24', '5a0e0a')
    # 선장 코트 + 줄무늬 셔츠 + 금 견장
    body(c, coat, rx=12)
    c.add(c.poly([(16.4, 33), (23.6, 33), (20, 40)]), Tone('ffffff', 'f4f4f6', 'd6d8de'), shade=0)
    c.add(ellipse_pair(c, 9.4, 34.6, 3.6, 1.8), gold, shade=1, light=True)
    # 바깥으로 말린 다리(양옆) + 판을 짚은 다리(앞)
    for side in (1, -1):
        cx = 20 + side * 15.4
        c.add(c.ellipse(cx, 32.6, 2.6, 4.4) | c.ellipse(cx + side * 1.6, 28.6, 2, 2), skin, shade=2, light=True)
    c.add(ellipse_pair(c, 12.6, 35.6, 4, 2.8), skin, shade=2, light=True)
    # 동그란 문어 머리
    c.add(c.ellipse(AX, 21.6, 14.4, 12), skin, shade=3, light=True)
    # 선장 삼각모: 넓게 휜 챙 + 금테 + 해골·뼈다귀 + 붉은 깃털
    brim = [(2.6, 15), (7.6, 6.6), (13.4, 3.8), (20, 5.6), (26.6, 3.8), (32.4, 6.6), (37.4, 15), (29, 12.2), (20, 13.4), (11, 12.2)]
    c.add(c.poly([(31, 7), (34.6, 0.6), (37.6, 0.2), (36.4, 3.4), (33.4, 8.6)]), plume, shade=2, light=True)
    c.add(c.poly(brim), hat, shade=2, light=True)
    c.add(c.line([(3.6, 14.4), (11, 11.6), (20, 12.8), (29, 11.6), (36.4, 14.4)]), gold, shade=0)
    c.add(c.line([(8.4, 7), (13.4, 4.6), (20, 6.4), (26.6, 4.6), (31.6, 7)]), gold, shade=0)
    c.dot_all([(19, 7), (20, 7), (18, 8), (19, 8), (20, 8), (21, 8), (19, 9), (20, 9)], 'fafafa')  # 해골
    c.dot_all([(18, 8), (21, 8)], '24242c'); c.dot_all([(19, 8), (20, 8)], 'fafafa')
    c.dot(19, 8, '24242c'); c.dot(20, 8, '24242c')
    c.dot_all([(16, 10), (17, 9.6), (22, 9.6), (23, 10), (16, 7), (23, 7)], 'e8e8ec')  # 뼈다귀
    c.dot_all([(9, 9), (10, 8)], '6a6a7a')  # 모자 윗빛
    c.dot_all([(35, 1), (36, 1)], 'ffd0c8')
    shine(c, 9, 17)
    # 안대(왼눈) + 모자 아래로 올라가는 끈
    c.dot_all([(9, 21), (10, 21), (11, 21), (12, 21), (8, 22), (9, 22), (10, 22), (11, 22), (12, 22), (13, 22),
               (9, 23), (10, 23), (11, 23), (12, 23), (10, 24), (11, 24)], '16161c')
    c.dot_all([(9, 21), (10, 21)], '4a4a58')
    c.dot_all([(8, 20), (7, 19), (6, 18), (5, 17), (4, 16)], '16161c')
    # 오른눈 + 치켜든 눈썹(자신만만)
    _eye(c, m(10) - 3, 21)
    c.dot_all([(25, 19), (26, 18), (27, 18), (28, 18), (29, 19)], '4a2a70')
    blush(c, 26, 6, 0.45)
    # 씩 웃는 입 + 한쪽 송곳니
    c.dot_all([(15, 28), (16, 29), (17, 29), (18, 29), (19, 29), (20, 29), (21, 29), (22, 28), (23, 27)], '4a2070')
    c.dot(21, 30, 'ffffff')
    # 금 귀걸이
    c.dot_all([(34, 25), (35, 26), (35, 27), (34, 28), (33, 27), (33, 26)], 'f4c84c'); c.dot(34, 25, 'fff4b0')
    # 빨판
    for x, y in ((10, 37), (12, 36), (27, 37), (29, 36), (4, 33), (35, 33), (4, 31), (35, 31)):
        c.dot(x, y, 'f8eeff')
    c.dot_all([(20, 35), (20, 37)], 'f4c84c')  # 코트 금 단추
    # HD: 모자 챙 그림자, 머리 무늬 점(문어 피부 결)
    cast(c, 16, 9, 31, '4a2a70', 0.3)
    for x, y in ((8, 26), (9, 27), (31, 22), (30, 23), (28, 30), (13, 31)):
        c.dot(x, y, 'a87ad8')
    return c


def fix():
    """픽스 · 세레니티의 열정적인 독수리 사냥꾼: 삐죽 머리깃 흰 머리, 매서운 눈썹, 반짝 눈, 큰 노란 갈고리 부리, 볼의 빨간 무늬, 보라 깃털"""
    c = canvas()
    white = Tone('ffffff', 'f5f3ef', 'ddd9d0', '8c867a')
    beak = Tone('ffea90', 'f6c03c', 'd8981e', '7a4a06')
    plume = Tone('f0dcff', 'c49eee', '9c72d0', '4a2a7a')
    body(c, Tone('86705e', '624e42', '483a30', '1a1210'))
    c.add(c.poly([(10, 34), (30, 39.4), (28.4, 40), (9, 35.4)]), Tone('ffe08a', 'e6b030', 'b88418'), shade=0)  # 금 띠
    c.add(c.poly([(6.4, 13), (2.4, 2.8), (4.4, 1.4), (10, 9.6), (11, 13.6)]), plume, shade=2, light=True)  # 보라 깃털
    head(c, white)
    c.add(c.poly([(12, 11.6), (14.6, 6.6), (17, 10.6), (20, 5.4), (23, 10.6), (25.4, 6.6), (28, 11.6)]), white, shade=1)  # 삐죽 머리깃
    c.add(c.poly([(13.6, 24.6), (26.4, 24.6), (27.4, 28), (24.4, 32.4), (21.6, 33.6), (22.4, 30), (13, 27.4)]), beak, shade=2, light=True)  # 갈고리 부리
    paws(c, Tone('927c6e', '6e5a4e', '54443a'))
    c.dot_all([(4, 2), (5, 3), (6, 5)], 'ffffff')
    shine(c, 10, 14)
    eyes(c, 18)
    c.dot_all([(8, 16), (9, 16), (10, 16), (11, 16.6), (12, 17), (13, 17.4)], '6a4028')  # 매서운 눈썹
    c.dot_all([(m(8), 16), (m(9), 16), (m(10), 16), (m(11), 16.6), (m(12), 17), (m(13), 17.4)], '6a4028')
    c.dot_all([(6, 24), (7, 24), (8, 24), (m(6), 24), (m(7), 24), (m(8), 24)], 'e05a4a')  # 볼 빨간 무늬
    c.dot_all([(6, 26), (7, 26), (m(6), 26), (m(7), 26)], 'e88a7a')
    c.dot_all([(16, 25), (17, 25), (16, 26)], 'fff6c8')
    c.dot_all([(23, 27), (24, 27)], '7a4a06')  # 콧구멍
    c.dot_all([(34, 6), (33, 5), (35, 5), (34, 4), (33, 7), (35, 7)], 'ffb03a'); c.dot(34, 5, 'fff0a0'); c.dot(34, 6, 'fff6c8')  # 열정 반짝
    toes(c, '54443a')
    # HD: 머리 옆 깃털 결(겹친 비늘 모양), 보라 깃털 윤기
    for x, y in ((6, 21), (7, 22), (5, 26), (6, 27), (m(6), 21), (m(7), 22), (m(5), 26), (m(6), 27)):
        c.dot(x, y, 'e2ddd2')
    c.dot_all([(3, 4), (4, 5), (5, 7)], 'fbf2ff')
    return c


# ================================================================ 특수 상점

def garam():
    """가람 · 코스메틱 스토어의 단정한 양 디자이너: 복슬복슬 흰 털 머리, 연회색 얼굴, 말린 갈색 뿔, 빨간 꽃 장식, 부드러운 웃는 눈, 갈색 앞치마"""
    c = canvas()
    wool = Tone('ffffff', 'f9f7f3', 'e4dfd6', 'a29c92')
    face = Tone('f6f4f0', 'e2ded8', 'cac4bc', '7a746c')
    horn = Tone('e6b07a', 'c6864c', '9a6034', '5c3519')
    flower = Tone('ff94a0', 'e44650', 'b42c36', '6a1018')
    body(c, Tone('ffffff', 'f4f4f6', 'd8dae0'))
    c.add(c.rect(12, 34, 28, 40), Tone('c8926a', 'a8704c', '845436', '42261a'), shade=1)
    fluff = set()
    for cx, cy, r in [(7, 16.6, 4.6), (10.4, 11.4, 4.8), (15.6, 9, 4.8), (24.4, 9, 4.8), (29.6, 11.4, 4.8), (33, 16.6, 4.6),
                      (6, 22.6, 4), (34, 22.6, 4), (AX, 8.4, 4.6)]:
        fluff |= c.ellipse(cx, cy, r, r)
    c.add(fluff, wool, shade=2, light=True)
    c.add(c.ellipse(AX, 23.4, 12, 9.8), face, shade=3, light=True)
    c.add(ellipse_pair(c, 13.6, 15.6, 4.2, 2.6) | c.ellipse(AX, 15, 4.2, 2.8), wool, shade=1)  # 앞머리 몽글
    c.add(c.ellipse(34.4, 18, 3.4, 4), horn, shade=2, light=True)  # 말린 뿔
    c.add(c.ellipse(31, 9.4, 3.2, 3), flower, shade=2, light=True)  # 빨간 꽃
    paws(c, wool)
    c.dot_all([(30, 9), (31, 9), (30, 10), (31, 10)], 'ffe066'); c.dot(30, 9, 'fff4b0')
    c.dot_all([(34, 17), (35, 18), (34, 19)], '7a4422')
    c.dot_all([(13, 7), (14, 7), (13, 8)], 'ffffff')
    happy_eyes(c, 21, 11, '5a4c48')
    blush(c, 26, 7, 0.6)
    c.dot_all([(19, 26), (20, 26)], '5a4c48')
    mouth_w(c, 27, '9a8a82')
    toes(c, 'd8d2c8')
    # HD: 복슬 털 결(곱슬 점), 앞머리 그림자, 앞치마 바늘땀
    for x, y in ((8, 14), (11, 9), (16, 7), (24, 7), (29, 9), (32, 14), (5, 21), (34, 21), (20, 6)):
        c.dot(x, y, 'e4dfd6'); c.dot(x + 1, y + 1, 'ffffff')
    cast(c, 18, 11, 29, 'a29c92', 0.25)
    stitch(c, 38, 15, 25, 'd8a882')
    return c


def lua():
    """루아 · 로얄 상점의 귀여운 사슴 지배인: 갈색 얼굴, 크게 뻗은 가지 뿔, 옆으로 뻗은 귀, 이마 흰 점, 속눈썹 반짝 눈, 짙은 갈색 코, 초록 망토"""
    c = canvas()
    fur = Tone('cc9468', 'ae7a50', '8e5e38', '4a2c16')
    antler = Tone('b89270', '946e50', '72523a', '362416')
    inner = Tone('f8e0c6', 'e8c6a4', 'caa480')
    lant = [(13.4, 11.4), (11.4, 6.6), (8, 5.6), (5.4, 1.2), (7.2, 0.4), (9.2, 3.4), (11.2, 3.6), (10.6, 0.2), (12.8, 0), (13.8, 4), (15.4, 10.2)]
    c.add(c.poly(lant), antler, shade=2, light=True)
    c.add(c.poly(c.mirror(lant, AX)), antler, shade=2, light=True)
    body(c, Tone('96cc72', '62a04a', '4a8036', '22421a'))
    c.add(c.ellipse(AX, 33.8, 4.6, 1.6), Tone('ffffff', 'f8f6f0', 'e0dccf'), shade=0)  # 셔츠 깃
    c.add(ellipse_pair(c, 3.4, 19.4, 4.6, 2.4), fur, shade=2)
    c.add(ellipse_pair(c, 3.2, 19.4, 2.6, 1.1), inner, shade=0)
    head(c, fur)
    c.add(c.ellipse(AX, 27.4, 4.6, 3.2), Tone('70483a', '523424', '3e2618', '1a0c06'), shade=1)  # 짙은 갈색 코
    paws(c, fur)
    c.dot_all([(16, 13), (19, 12), (20, 12), (23, 13), (18, 15), (21, 15)], 'fff2e0')  # 이마 흰 점
    shine(c, 10, 15)
    eyes(c, 20)
    c.dot_all([(9, 20), (8, 19), (m(9), 20), (m(8), 19)], EYE)  # 속눈썹
    c.dot_all([(18, 26), (19, 26)], 'a07858')
    blush(c, 25, 5, 0.6)
    c.dot_all([(20, 36), (19, 36), (20, 37), (19, 37)], 'f2c63a'); c.dot(19, 36, 'fff0a0')  # 망토 금 단추
    toes(c, '8e5e38')
    # HD: 볼 털 뭉치, 뿔 윤기, 망토 깃 그늘
    tufts(c, fur)
    c.dot_all([(7, 2), (11, 1), (m(7), 2), (m(11), 1)], 'd8b898')
    c.soft([(x, 35) for x in range(13, 28)], '22421a', 0.22)
    return c


# (아이디, 이름, 동물·직업, 그리는 함수) — 순서가 설정 화면 순서
ALL = [
    ('bori', '보리', '강아지 농부', bori),
    ('dodam', '도담', '염소 농부', dodam),
    ('seoki', '석이', '카피바라 광부', seoki),
    ('duri', '두리', '두더지 대장장이', duri),
    ('deumjik', '듬직', '황소 나무꾼', deumjik),
    ('hotang', '호탕', '늑대 사냥꾼', hotang),
    ('hani', '하니', '토끼 디자이너', hani),
    ('bareum', '바름', '라쿤 건축가', bareum),
    ('nuri', '누리', '펠리컨 어부', nuri),
    ('kai', '카이', '원숭이 농부', kai),
    ('roni', '로니', '곰 대장장이', roni),
    ('milky', '밀키', '소 요리사', milky),
    ('sharky', '샤키', '상어 어부', sharky),
    ('pos', '포스', '문어 해적', pos),
    ('fix', '픽스', '독수리 사냥꾼', fix),
    ('garam', '가람', '양 디자이너', garam),
    ('lua', '루아', '사슴 지배인', lua),
]
