"""HUD 판 더 많이(2026-10-23 사용자 요청 순서): 지역 → 계절·행사 → 전문가 → 시간대.
사람 NPC(바리스타 손님·밀렵꾼) 묶음은 만들었다가 사용자 요청으로 통째로 뺐다(2026-10-23).

skins.py 와 같은 틀(판 40×40 나인슬라이스, 제목 줄 40×17)을 쓰고 SKINS 에 더한다.
가운데 바탕은 늘 깨끗하게(무늬 없음), 장식은 테두리·귀퉁이·제목 줄·판 밖 3칸에만.
"""
from px import hexc, mix
from skins import (B, HH, O, P0, P1, PW, edge_x, frame, head_band, band_x, skin, lt, dk)


def grad_band(h, colors):
    """제목 줄 띠를 위에서 아래로 colors 순서의 줄무늬(그러데이션)로"""
    rows = list(range(O + 1, HH - 1))
    for i, y in enumerate(rows):
        t = i / max(1, len(rows) - 1)
        k = t * (len(colors) - 1)
        a, b = int(k), min(len(colors) - 1, int(k) + 1)
        col = mix(hexc(colors[a]), hexc(colors[b]), k - a)
        h.rect(O + 1, y, h.w - O - 1, y + 1, col)


def frame_blocks(p, colors, size=6):
    """테두리 띠를 색 블록이 번갈아 가는 무늬로(블록 size 칸 × 색 수 = 24)"""
    for x, k in edge_x():
        col = colors[(k // size) % len(colors)]
        for y in (P0 + 1, P0 + 2, P1 - 3, P1 - 2):
            p.px(x, y, col)
        for xx in (P0 + 1, P0 + 2, P1 - 3, P1 - 2):
            p.px(xx, x, col)


GROUP_REGION = '지역'
GROUP_SEASON = '계절·행사'
GROUP_EXPERT = '전문가'
GROUP_TIME = '시간대'


# ================================================================ 1. 지역

@skin('region_spawn', '스폰', '스폰 광장', False, lt(text='2e2a40', soft='6e6880'), 'ffffff', group=GROUP_REGION, mascot=False)
def region_spawn(p, h):
    """스폰 광장: 크림색 돌바닥 테두리 + 위쪽 알록달록 깃발 줄, 왕실 파랑 제목 줄"""
    frame(p, '4a3e2e', 'efe4cc', 'c8b494', 'fffbf2', thick=2, hi='ffffff', lo='f0e8d8')
    for x, k in edge_x():  # 돌바닥 이음매
        if k % 6 == 0:
            p.px(x, P0 + 1, 'b8a484'); p.px(x, P1 - 2, 'b8a484')
            p.px(P0 + 1, x, 'b8a484'); p.px(P1 - 2, x, 'b8a484')
    p.corners([".a.", "aba", ".a.", ".c.", ".c."], {'a': 'ffd84a', 'b': 'fff6c0', 'c': '4a3e2e'}, inset=0)  # 가로등 불빛
    head_band(h, '1a2450', '3a52a8', '5a72c8', '2a3c84')
    for x in range(O + 1, h.w - O - 1):
        h.px(x, HH - 2, 'f2c64a')
    flags = ['e8473f', 'f6c63a', '4a9ae8', '5fc45a']
    for x, k in band_x():  # 깃발 줄(판 위 3칸)
        if k % 5 == 0:
            col = flags[(k // 5) % len(flags)]
            h.px(x, 0, col); h.px(x + 1, 0, col); h.px(x + 2, 0, col); h.px(x, 1, col); h.px(x + 1, 1, col); h.px(x, 2, col)
    for x in range(1, h.w - 1):
        if h.get(x, 0)[3] == 0:
            h.px(x, 0, '8a7a5a')


@skin('region_wild', '야생', '야생 들판', False, lt(text='233a1e', soft='5e7050'), '1e3048', False, group=GROUP_REGION, mascot=False)
def region_wild(p, h):
    """야생: 풀밭 테두리에 작은 들꽃, 아래로 풀 술, 하늘색 제목 줄에 먼 산과 구름"""
    frame(p, '2a4a1e', '7ccc5e', '4e9a3c', 'f7fcf2', thick=2, hi='ffffff', lo='e6f2dc')
    flowers = ['ffffff', 'ffd84a', 'ff8ab0']
    for x, k in edge_x():
        if k % 8 == 3:
            col = flowers[(k // 8) % 3]
            p.px(x, P0 + 1, col); p.px(P1 - 2, x, col)
        if k % 6 in (0, 2):
            p.px(x, P1, '5fb44a')
        if k % 6 == 1:
            p.px(x, P1 + 1, '3e8a32')
    p.corners(["..aa..", ".abba.", "abbbba", ".abba.", "..c...", "..c..."], {'a': '3e8a32', 'b': '6fc45a', 'c': '7a5232'}, inset=0)  # 나무
    head_band(h, '1e3a5a', '9ad8ff')
    grad_band(h, ['b8e6ff', '9ad8ff', '8acff8'])
    for x, k in band_x():  # 먼 산
        hgt = [1, 2, 3, 3, 2, 1, 1, 2, 2, 1][k % 10]
        for y in range(HH - 1 - hgt, HH - 1):
            h.px(x, y, '7ab0d0' if y > HH - 1 - hgt else '9cc8e0')
    for x, k in band_x():
        if k in (3, 4, 5, 13, 14):
            h.px(x, O + 2, 'ffffff')
        if k in (4, 14):
            h.px(x, O + 1, 'ffffff')


@skin('region_village', '마을', '에버그린 마을', False, lt(text='3a2a1e'), 'fff4e0', group=GROUP_REGION, mascot=False)
def region_village(p, h):
    """마을: 나무 기둥 테두리에 담쟁이, 판자 간판이 밧줄에 매달린 제목 줄(서버 메뉴 간판 느낌)"""
    frame(p, '3a2414', 'a87a54', '7a5232', 'fff9f0', thick=3, hi='ffffff', lo='f2e8da')
    for x, k in edge_x():  # 나뭇결
        if k in (2, 3, 4, 14, 15, 16, 17):
            p.px(x, P0 + 2, '8a6040')
        if k in (8, 9, 10, 20, 21):
            p.px(x, P1 - 3, '6a4428')
    p.corners(["aab...", "abba..", ".bab..", "..ab..", "...a..", "......"], {'a': '4e9a3c', 'b': '7ccc5e'}, inset=0)  # 담쟁이
    head_band(h, '4a2e18', 'b8875a', 'd4a476', '8a6040')
    for x, k in band_x():  # 판자 결
        if k in (1, 2, 3, 4, 11, 12):
            h.px(x, O + 3, 'a07448')
        if k in (6, 7, 8, 16, 17, 18):
            h.px(x, HH - 4, 'a07448')
    for x in (O + 4, h.w - O - 5):  # 밧줄 + 못
        h.rect(x, 0, x + 1, O, 'e6d2a8')
        h.px(x, O + 2, 'f2ead8')


@skin('region_serenity', '세레니티', '세레니티 섬 마을', False, lt(text='1e3a44', soft='5a7a80'), 'ffffff', group=GROUP_REGION, mascot=False)
def region_serenity(p, h):
    """세레니티(아일랜드): 모래 테두리 안쪽에 청록 바다 줄, 청록 물결 제목 줄과 야자잎 귀퉁이"""
    frame(p, '4a3a1e', 'f6e2ae', 'd8bc7c', 'f4fcfc', thick=2, hi='7adcdc', lo='e0f4f4')
    for x, k in edge_x():  # 조개·모래알
        if k % 8 == 5:
            p.px(x, P1 - 2, 'ffffff'); p.px(P0 + 1, x, 'f2a0a0')
        if k % 4 == 1:
            p.px(x, P0 + 1, 'e8cc90')
    p.corners(["a.a.a", ".aaa.", "aabaa", "..c..", "..c.."], {'a': '3faa52', 'b': '7ad86a', 'c': '9a6a3a'}, inset=0)  # 야자
    head_band(h, '0a3a48', '2ab4c0')
    grad_band(h, ['5ad8dc', '2ab4c0', '1e94a8'])
    for x, k in band_x():  # 물결
        if k % 6 in (0, 1):
            h.px(x, HH - 2, 'ffffff')
        if k % 6 in (2, 3):
            h.px(x, HH - 3, 'c8f4f4')


TOYS = ['ffb8d2', 'a8ecd0', 'fff0a0', 'cab8f8']


@skin('region_tinyland', '파라다이스', '파라다이스', False, lt(text='4a3a5a', soft='8a7a98'), 'ffffff', group=GROUP_REGION, mascot=False)
def region_tinyland(p, h):
    """파라다이스(위키 지역 이름 타이니랜드): 파스텔 장난감 블록 테두리, 라일락 제목 줄에 별사탕"""
    frame(p, '5a4a6e', 'f8d8ea', 'd8b0cc', 'fffafd', thick=2, hi='ffffff', lo='f6eef6')
    frame_blocks(p, TOYS, 6)
    p.corners(["..a..", ".aba.", "abbba", ".aba.", "..a.."], {'a': 'ffd84a', 'b': 'fff6c0'}, inset=0)  # 별사탕
    head_band(h, '3a2a6a', 'b49cf0', 'cab8f8', '9a80e0')
    for x, k in band_x():
        if k % 5 == 2:
            h.px(x, O + 2, 'ffffff'); h.px(x + 1, HH - 3, TOYS[(k // 5) % 4])


@skin('region_noctila', '노크틸라', '노크틸라 사냥터', True, dk(text='f2ecfa', soft='a89cbc', gold='f2c860'), 'f2e6ff', group=GROUP_REGION, mascot=False)
def region_noctila(p, h):
    """노크틸라: 어두운 돌 테두리에 보라 룬이 빛나고, 귀퉁이 횃불, 짙은 보라 제목 줄에 초승달"""
    frame(p, '0a0812', '5a5068', '2e2838', '1a1626', thick=2, hi='2a2438', lo='120f1c')
    for x, k in edge_x():  # 빛나는 룬
        if k % 8 == 4:
            p.px(x, P0 + 1, 'c49cff'); p.px(x, P1 - 2, '9a6ae8')
            p.px(P0 + 1, x, 'c49cff'); p.px(P1 - 2, x, '9a6ae8')
            p.px(x, P0 - 1, '6a4aa8')
    p.corners([".a..", "aba.", ".c..", ".d..", ".d.."], {'a': 'ff9a3a', 'b': 'fff0a0', 'c': 'e86a2a', 'd': '6a5a70'}, inset=0)  # 횃불
    head_band(h, '06040e', '3a1e5a')
    grad_band(h, ['5a2e7a', '3e1e5e', '2a1444'])
    h.sprite(h.w - O - 7, O + 3, [".aa.", "a...", "a...", ".aa."], {'a': 'fff2c8'})  # 초승달
    for x, k in band_x():
        if k in (3, 11, 17):
            h.px(x, O + 2, 'd8c8ff')


# ================================================================ 2. 계절·행사

HOLLY = ["aa.aa", "abaab", ".acc.", "..c..", "....."]


@skin('season_winter', '겨울·크리스마스', '눈 오는 크리스마스', False, lt(text='3a1e1e', soft='7a5a5a', green='2e7a3e'), 'ffffff', group=GROUP_SEASON, mascot=False)
def season_winter(p, h):
    """겨울: 초록 테두리에 빨간 방울, 위아래로 소복한 눈, 빨간 제목 줄 위에 녹아 흐르는 눈"""
    frame(p, '1e3a24', '4e9a5a', '2e6e3a', 'fffdfc', thick=2, hi='ffffff', lo='f2eeee')
    for x, k in edge_x():
        if k % 8 == 2:
            p.px(x, P0 + 1, 'e8473f'); p.px(x + 4, P1 - 2, 'f6c63a')
            p.px(P0 + 1, x, 'f6c63a'); p.px(P1 - 2, x + 4, 'e8473f')
        if k % 4 in (0, 1, 2):  # 아래 눈
            p.px(x, P1, 'ffffff')
        if k % 8 in (1, 2):
            p.px(x, P1 + 1, 'eef4fa')
    p.corners(HOLLY, {'a': '2e7a3e', 'b': '4e9a5a', 'c': 'e8473f'}, inset=0)
    head_band(h, '5a1010', 'cc3a3a', 'e05a5a', '9e2424')
    for x in range(1, h.w - 1):  # 지붕 위 눈
        h.px(x, O, 'ffffff')
        h.px(x, O - 1, 'ffffff' if 2 < x < h.w - 3 else None)
    for x, k in band_x():
        if k % 6 in (0, 1):
            h.px(x, O + 1, 'ffffff')
        if k % 6 == 0:
            h.px(x, O + 2, 'eef4fa')


BLOSSOM = [".a.a.", "abcba", ".bbb.", "abcba", ".a.a."]


@skin('season_spring', '봄노래', '벚꽃 봄노래', False, lt(text='4a2a3a', soft='8a6a7a'), 'ffffff', group=GROUP_SEASON, mascot=False)
def season_spring(p, h):
    """봄: 연분홍 테두리에 흩날리는 꽃잎, 분홍 제목 줄에 음표"""
    frame(p, '6a3a4e', 'ffd0de', 'f0a6bc', 'fffafc', thick=2, hi='ffffff', lo='faf0f4')
    for x, k in edge_x():
        if k % 6 == 1:
            p.px(x, P0 + 1, 'ffffff'); p.px(P1 - 2, x, 'ffffff')
        if k % 12 == 7:
            p.px(x, P1, 'ffc0d4'); p.px(P0 - 1, x, 'ffc0d4')
    p.corners(BLOSSOM, {'a': 'ff9ab8', 'b': 'ffc8d8', 'c': 'ffe88a'}, inset=0)
    head_band(h, '6a2a44', 'f08aa8', 'ffb0c8', 'd86a8c')
    for x, k in band_x():  # 음표
        if k == 3:
            h.px(x, O + 6, 'ffffff'); h.px(x + 1, O + 6, 'ffffff'); h.px(x + 1, O + 3, 'ffffff'); h.px(x + 1, O + 4, 'ffffff'); h.px(x + 1, O + 5, 'ffffff'); h.px(x + 2, O + 3, 'ffffff')
        if k in (9, 15):
            h.px(x, O + 2, 'ffe0ea')


BALL = [".aab.", "aaabb", "ccccc", "ddeee", ".dee."]


@skin('season_summer', '여름 해변', '여름 휴양지 해변', False, lt(text='1e3a50', soft='5a7488'), 'ffffff', group=GROUP_SEASON, mascot=False)
def season_summer(p, h):
    """여름: 모래 테두리에 조개, 아래로 파도, 하늘 제목 줄 끝에 해"""
    frame(p, '4a3a1e', 'f8e4b0', 'dcc080', 'fffdf4', thick=2, hi='ffffff', lo='f6f0e0')
    for x, k in edge_x():
        if k % 8 == 4:
            p.px(x, P0 + 1, 'ff9a8a'); p.px(P1 - 2, x, 'ffffff')
        if k % 6 in (0, 1, 2):
            p.px(x, P1, '5ac8f0' if k % 6 != 1 else 'ffffff')
        if k % 6 == 4:
            p.px(x, P1 + 1, '7ad4f4')
    p.corners(BALL, {'a': 'ff5a5a', 'b': 'ffffff', 'c': 'ffd84a', 'd': '4a9ae8', 'e': 'ffffff'}, inset=0)  # 비치볼
    head_band(h, '0e3a5a', '5cc8f0')
    grad_band(h, ['8adcff', '5cc8f0', '3ab0e0'])
    h.sprite(h.w - 10, O + 2, [".aaa.", "aabaa", "abbba", "aabaa", ".aaa."], {'a': 'ffd84a', 'b': 'fff6c0'})  # 해
    for x, k in band_x():
        if k % 6 in (0, 1):
            h.px(x, HH - 2, 'ffffff')


@skin('season_moon', '가을 달토끼', '보름달 달토끼', True, dk(text='f4f0ff', soft='aaa6c8', gold='ffd870'), 'ffe9b0', group=GROUP_SEASON, mascot=False)
def season_moon(p, h):
    """가을 달토끼: 짙은 남색 밤하늘 테두리에 금빛 별, 단풍잎 귀퉁이, 제목 줄 끝 보름달에 토끼"""
    frame(p, '06081c', '3a3e78', '24285a', '1c1e3a', thick=2, hi='2c2e54', lo='14162c')
    for x, k in edge_x():
        if k % 6 == 2:
            p.px(x, P0 + 1, 'ffd870'); p.px(P1 - 2, x, 'ffd870')
        if k % 12 == 8:
            p.px(x, P0 - 1, 'fff2c0')
    p.corners(["..a..", ".aba.", "abbba", ".aba.", "..c.."], {'a': 'e86a2a', 'b': 'ff9a4a', 'c': '8a4a1a'}, inset=0)  # 단풍
    head_band(h, '04061a', '24285a')
    grad_band(h, ['343a7a', '24285a', '1a1e48'])
    h.sprite(h.w - 10, O + 1, [".aaaa.", "aaabaa", "aabbaa", "aaaaaa", "aaaaaa", ".aaaa."], {'a': 'fff2c8', 'b': 'e6d6a0'})  # 보름달 + 토끼
    for x, k in band_x():
        if k in (2, 8, 14):
            h.px(x, O + 2 + (k % 3), 'fff2c0')


DANCHEONG = ['c83a2e', '3e8a6a', '2e5a9a', 'e8b83a']


@skin('season_chuseok', '추석', '한가위 추석', False, lt(text='2a2438', soft='6a6478'), 'f2d07a', group=GROUP_SEASON, mascot=False)
def season_chuseok(p, h):
    """추석: 단청 오방색 블록 테두리, 남색 제목 줄에 금빛 구름 무늬, 송편 귀퉁이"""
    frame(p, '2a1e14', 'c83a2e', '8a2a20', 'fffbf2', thick=2, hi='ffffff', lo='f2ece0')
    frame_blocks(p, DANCHEONG, 6)
    p.corners([".aab.", "aabbb", "accbb", ".ccc."], {'a': 'ffc8d8', 'b': 'c8ecb8', 'c': 'fffaf0'}, inset=0)  # 송편 셋
    head_band(h, '0e1430', '26355e', '34467a', '1c2848')
    for x, k in band_x():  # 금빛 구름 무늬
        if k % 10 in (0, 1, 2):
            h.px(x, HH - 3, 'f2d07a')
        if k % 10 == 1:
            h.px(x, HH - 4, 'f2d07a')
        if k % 10 == 6:
            h.px(x, O + 2, 'c8a850')


GIFT = ["aabaa", "aabaa", "bbbbb", "aabaa", "aabaa"]


@skin('season_anniv', '1주년', '띵타이쿤 1주년', False, lt(text='2a2a4a', soft='6a6a88', gold='a87a10'), 'ffe38a', group=GROUP_SEASON, mascot=False)
def season_anniv(p, h):
    """1주년: 금빛 테두리에 색종이, 남색 제목 줄 양 끝에 풍선, 선물 상자 귀퉁이"""
    frame(p, '4a3a10', 'f2d070', 'c8a03a', 'fffdf6', thick=2, hi='ffffff', lo='f6f0e0')
    confetti = ['e8473f', '4a9ae8', '5fc45a', 'f08ab8', 'ffffff']
    for x, k in edge_x():
        if k % 4 == 1:
            col = confetti[(k // 4) % 5]
            p.px(x, P0 + 1 + (k // 4) % 2, col); p.px(P1 - 2 - (k // 4) % 2, x, col)
    p.corners(GIFT, {'a': 'e8473f', 'b': 'ffd84a'}, inset=0)
    head_band(h, '141a40', '2e3a7a', '4050a0', '1e2858')
    for x, k in band_x():
        if k % 4 == 2:
            h.px(x, O + 2 + (k // 4) % 2, confetti[(k // 4) % 5])
    for x, col in ((1, 'f08ab8'), (h.w - 3, '4a9ae8')):  # 풍선(판 위로)
        h.rect(x, 0, x + 2, 2, col)
        h.px(x, 2, '8a8aa0')


# ================================================================ 6. 전문가

@skin('expert_mining', '채광', '깊은 광산', True, dk(text='f4f0e8', soft='b0a89c', gold='ffd86a'), 'ffd86a', group=GROUP_EXPERT, mascot=False)
def expert_mining(p, h):
    """채광: 어두운 돌 테두리에 금·다이아·레드스톤 광석 알갱이, 굴 갈색 제목 줄 끝에 랜턴, 보석 귀퉁이"""
    frame(p, '0e0c10', '6e6a76', '46424e', '24222a', thick=2, hi='34303a', lo='18161c')
    ores = ['f2c64a', '7ae8ff', 'e84a3a', 'ffffff']
    for x, k in edge_x():
        if k % 6 == 2:
            col = ores[(k // 6) % 4]
            p.px(x, P0 + 1, col); p.px(P1 - 2, x + 1, col); p.px(x + 2, P1 - 2, ores[(k // 6 + 1) % 4])
    p.corners([".aa.", "abca", "abba", ".aa."], {'a': '1a6ab0', 'b': '3ab8e8', 'c': 'e8f8ff'}, inset=O)
    head_band(h, '1a100a', '5a3e2a')
    grad_band(h, ['6e4e36', '5a3e2a', '46301e'])
    h.sprite(h.w - O - 6, O + 2, [".a.", "aba", "aba", ".c."], {'a': '6a6a74', 'b': 'ffd84a', 'c': '6a6a74'})  # 랜턴
    for x, k in band_x():
        if k % 7 == 3:
            h.px(x, HH - 2, 'f2c64a')


@skin('expert_ocean', '해양', '깊고 푸른 바다', False, lt(text='16304e', soft='56708e'), 'ffffff', group=GROUP_EXPERT, mascot=False)
def expert_ocean(p, h):
    """해양: 짙은 바다색 테두리에 밧줄, 깊어지는 바다 제목 줄에 물방울, 닻 귀퉁이"""
    frame(p, '0a1a34', '3a6aa8', '1e4478', 'f2f8fc', thick=2, hi='ffffff', lo='e2edf6')
    for x, k in edge_x():
        if k % 4 in (0, 1):
            p.px(x, P0 + 1 + (k % 4), 'd8b47a'); p.px(P0 + 1 + (k % 4), x, 'd8b47a')
    p.corners(["..a..", ".aba.", "..a..", "a.a.a", ".aaa."], {'a': 'd8b47a', 'b': '0a1a34'}, inset=0)
    head_band(h, '06142a', '1e5a9a')
    grad_band(h, ['3a86c8', '1e5a9a', '14406e'])
    for x, k in band_x():
        if k in (3, 9, 16):
            h.px(x, O + 2 + (k % 3), 'c8ecff')


@skin('expert_farm', '재배', '푸른 텃밭', False, lt(text='2a3a1a', soft='6a7650'), 'ffffff', group=GROUP_EXPERT, mascot=False)
def expert_farm(p, h):
    """재배: 흙 테두리 위로 새싹, 잎 초록 제목 줄, 당근 귀퉁이"""
    frame(p, '2a1a0e', 'a07448', '7a5232', 'fafdf2', thick=2, hi='ffffff', lo='eef4e0')
    for x, k in edge_x():
        if k % 6 == 2:
            p.px(x, P0 - 1, '6fc45a'); p.px(x + 1, P0 - 2, '9ae07a'); p.px(x, P0 - 2, '4e9a3c')
        if k % 5 == 1:
            p.px(x, P1 - 2, '5a3a22')
    p.corners(["..ab", ".aa.", "aaa.", "aa..", "a..."], {'a': 'f08a2a', 'b': '5fb44a'}, inset=0)  # 당근
    head_band(h, '1e3a10', '5fae4a', '7cc862', '458a36')
    for x, k in band_x():
        if k % 6 == 3:
            h.px(x, HH - 2, '3a7a2c'); h.px(x + 1, HH - 3, '9ae07a')


@skin('expert_hunt', '사냥', '깊은 사냥 숲', False, lt(text='2a2014', soft='6a5e4e'), 'f6eed8', group=GROUP_EXPERT, mascot=False)
def expert_hunt(p, h):
    """사냥: 가죽 테두리에 바느질, 짙은 숲 제목 줄 양 끝에 엇갈린 화살, 발자국 귀퉁이"""
    frame(p, '24140c', '8a5a36', '5e3a20', 'f8f2e8', thick=2, hi='ffffff', lo='efe6d8')
    for x, k in edge_x():
        if k % 3 == 1:
            p.px(x, P0 + 1, 'e0c08a'); p.px(P1 - 2, x, 'c8a874')
    p.corners([".a.a.", "a...a", ".bbb.", ".bbb."], {'a': '5e3a20', 'b': '5e3a20'}, inset=O)  # 발자국
    head_band(h, '0e1e12', '2e5a3a', '42764e', '1e4228')
    for sx in (O + 2, h.w - 10):
        h.sprite(sx, O + 3, ["a...a", ".a.a.", "..b..", ".a.a.", "a...a"], {'a': 'e8d8b8', 'b': 'c8ccd4'})


# ================================================================ 7. 시간대

@skin('time_dawn', '새벽', '동트는 새벽', False, lt(text='3a304a', soft='7a7088'), 'ffffff', group=GROUP_TIME, mascot=False)
def time_dawn(p, h):
    """새벽: 라벤더 테두리에 사라지는 별, 라벤더→복숭아빛 제목 줄"""
    frame(p, '3a2e5a', 'c8b8f0', 'a090d8', 'fbf8ff', thick=2, hi='ffffff', lo='f2eefa')
    for x, k in edge_x():
        if k % 8 == 5:
            p.px(x, P0 + 1, 'ffffff'); p.px(P1 - 2, x, 'ffe0d0')
    p.corners(["..a..", ".aba.", "..a.."], {'a': 'e8e0ff', 'b': 'ffffff'}, inset=1)
    head_band(h, '2a2050', 'a99be0')
    grad_band(h, ['a99be0', 'c8a8e0', 'f6c2b0'])
    for x, k in band_x():
        if k in (4, 15):
            h.px(x, O + 2, 'ffffff')


@skin('time_day', '낮', '맑은 한낮', False, lt(text='1e3048', soft='5a6e84'), 'ffffff', group=GROUP_TIME, mascot=False)
def time_day(p, h):
    """낮: 하늘색 테두리 아래로 뭉게구름, 파란 하늘 제목 줄에 흰 구름"""
    frame(p, '1a3a5a', 'a8dcf8', '74b8e8', 'fafdff', thick=2, hi='ffffff', lo='eaf4fc')
    for x, k in edge_x():
        if k % 8 in (1, 2, 3, 4):
            p.px(x, P1, 'ffffff')
        if k % 8 in (2, 3):
            p.px(x, P1 + 1, 'eef6fc')
    p.corners([".a.a.", "..b..", "abbba", "..b..", ".a.a."], {'a': 'ffd84a', 'b': 'fff0a0'}, inset=0)  # 해
    head_band(h, '0e3a64', '6cc4f4')
    grad_band(h, ['8ad4fa', '6cc4f4', '58b4ec'])
    for x, k in band_x():
        if k in (2, 3, 4, 5, 12, 13, 14):
            h.px(x, O + 2, 'ffffff')
        if k in (3, 4, 13):
            h.px(x, O + 1, 'ffffff')


@skin('time_sunset', '노을', '붉은 노을', False, lt(text='3a1e14', soft='7a5a50'), 'ffffff', group=GROUP_TIME, mascot=False)
def time_sunset(p, h):
    """노을: 산호빛 테두리 위로 날아가는 새, 주황→분홍 제목 줄 아래 반쯤 잠긴 해"""
    frame(p, '4a1e14', 'f6a07a', 'e07050', 'fff8f2', thick=2, hi='ffffff', lo='f8ece4')
    for x, k in edge_x():
        if k % 12 == 5:
            p.px(x, P0 - 2, '5a2a24'); p.px(x + 1, P0 - 1, '5a2a24'); p.px(x + 2, P0 - 2, '5a2a24')
    p.corners(["a.a", ".a."], {'a': '5a2a24'}, inset=0)
    head_band(h, '4a1410', 'f28a4a')
    grad_band(h, ['ffb04a', 'f2864a', 'e86272'])
    h.sprite(h.w - 10, HH - 5, [".aaaa.", "aabbaa", "abbbba"], {'a': 'ffd84a', 'b': 'fff0a0'})  # 지는 해


@skin('time_night', '밤', '별 밤', True, dk(text='eef0ff', soft='a8acc8', gold='ffe08a'), 'e8ecff', group=GROUP_TIME, mascot=False)
def time_night(p, h):
    """밤: 짙은 남색 테두리에 별, 남색 제목 줄에 반짝이는 별, 초승달 귀퉁이"""
    frame(p, '06081a', '343a72', '1e2450', '141832', thick=2, hi='242a4e', lo='0e1026')
    for x, k in edge_x():
        if k % 6 == 1:
            p.px(x, P0 + 1, 'e8ecff'); p.px(P1 - 2, x, 'b8c0f0')
        if k % 12 == 7:
            p.px(x, P0 - 1, 'ffffff')
    p.corners([".aa.", "a...", "a...", ".aa."], {'a': 'fff2c8'}, inset=1)
    head_band(h, '04061a', '1e2450')
    grad_band(h, ['2c3470', '1e2450', '161a3c'])
    for x, k in band_x():
        if k in (2, 9, 15):
            h.px(x, O + 2 + (k % 3), 'ffffff')
        if k == 9:
            h.px(x - 1, O + 2, 'c8d0ff'); h.px(x + 1, O + 2, 'c8d0ff')
