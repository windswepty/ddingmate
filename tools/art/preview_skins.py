"""HUD 세트 미리보기(게임과 같은 나인슬라이스 되풀이 방식): python -I tools/art/preview_skins.py <출력.png>"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from PIL import Image, ImageDraw, ImageFont  # noqa: E402
import mascots  # noqa: E402
import skins as S  # noqa: E402
import skins_more  # noqa: E402,F401

FONT_DIR = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'ddingmate', 'font')
BODY = ImageFont.truetype(os.path.join(FONT_DIR, 'galmuri9.ttf'), 10)
BOLD = ImageFont.truetype(os.path.join(FONT_DIR, 'galmuri11-bold.ttf'), 11)
K = 3


def nine(tex, w, h, b):
    """마인크래프트 GUI nine_slice: 귀퉁이 그대로, 변·가운데는 되풀이"""
    tw, th = tex.size
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    bl = bt = br = bb = b if isinstance(b, int) else 0
    if not isinstance(b, int):
        bl, bt, br, bb = b
    cw, ch = tw - bl - br, th - bt - bb

    def tile(src, x0, y0, x1, y1):
        if x1 <= x0 or y1 <= y0 or src.width == 0 or src.height == 0:
            return
        for y in range(y0, y1, src.height):
            for x in range(x0, x1, src.width):
                piece = src.crop((0, 0, min(src.width, x1 - x), min(src.height, y1 - y)))
                out.alpha_composite(piece, (x, y))

    cols = [(0, bl, 0, bl), (bl, tw - br, bl, w - br), (tw - br, tw, w - br, w)]
    rows = [(0, bt, 0, bt), (bt, th - bb, bt, h - bb), (th - bb, th, h - bb, h)]
    for sx0, sx1, dx0, dx1 in cols:
        for sy0, sy1, dy0, dy1 in rows:
            tile(tex.crop((sx0, sy0, sx1, sy1)), dx0, dy0, dx1, dy1)
    return out


def hexrgb(s):
    return '#' + s


def text(d, xy, s, col, font, shadow):
    if shadow:
        d.text((xy[0] + 1, xy[1] + 1), s, fill=(0, 0, 0, 110), font=font)
    d.text(xy, s, fill=hexrgb(col), font=font)


def mock(sk, mascot_im):
    panel, head = S.build(sk)
    w, h = 132, 70
    can = Image.new("RGBA", (w + 40, h + 54), (0, 0, 0, 0))
    x, y = 8, 42
    can.alpha_composite(nine(panel, w + 2 * S.O, h + 2 * S.O, S.B), (x - S.O, y - S.O))
    can.alpha_composite(nine(head, w + 2 * S.O, S.HH, (10, 0, 10, 0)), (x - S.O, y - S.O))
    d = ImageDraw.Draw(can)
    col = sk['colors']
    dark = sk['dark']
    text(d, (x + 16, y + 2), '다음 일정', sk['title'], BOLD, sk['titleShadow'])
    d.rectangle((x + 4, y + 3, x + 13, y + 12), fill=(250, 200, 80))
    rows = [('오늘 접속 21시간', '보상 다 받음', col['gold']), ('바닥 아이템 청소', '5분 37초', col['blue']),
            ('코룸 주괴 ×24', '15분 21초', col['blue']), ('마을 4채널 재시작', '5분', col['red'])]
    ty = y + 16
    for label, left, c in rows:
        d.rectangle((x + 6, ty + 3, x + 8, ty + 5), fill=hexrgb(c))
        text(d, (x + 13, ty), label, col['text'], BODY, dark)
        lw = d.textlength(left, font=BODY)
        text(d, (x + w - 6 - lw, ty), left, c, BODY, dark)
        ty += 12
    if mascot_im is not None:
        can.alpha_composite(mascot_im, (x + w - mascots.W - 6, y - mascots.PAW_ROW))
    return can


def main(out, group=None):
    by_id = {m[0]: m for m in mascots.ALL}
    tiles = []
    for sk in S.SKINS:
        if group and sk.get('group') != group:
            continue
        m = by_id.get(sk['id'])
        im = mock(sk, m[3]().render() if m and sk.get('hasMascot', True) else None)
        big = im.resize((im.width * K, im.height * K), Image.NEAREST)
        t = Image.new('RGBA', (big.width, big.height + 30), (54, 60, 54, 255))
        # 게임 화면 같은 바탕(풀·하늘 느낌)
        for yy in range(t.height):
            g = int(70 + 60 * yy / t.height)
            ImageDraw.Draw(t).line([(0, yy), (t.width, yy)], fill=(g // 2, g, g // 2 + 20, 255))
        t.alpha_composite(big, (0, 30))
        ImageDraw.Draw(t).text((8, 4), f"{sk['name']} · {sk['place']}  {'(어두운 판)' if sk['dark'] else ''}", fill=(255, 230, 120),
                               font=ImageFont.truetype(r'C:\Windows\Fonts\malgunbd.ttf', 20))
        tiles.append(t)
    cols = 4
    tw, th = tiles[0].size
    sheet = Image.new('RGBA', (cols * (tw + 6), ((len(tiles) + cols - 1) // cols) * (th + 6)), (12, 12, 14, 255))
    for i, t in enumerate(tiles):
        sheet.alpha_composite(t, ((i % cols) * (tw + 6), (i // cols) * (th + 6)))
    sheet.save(out)
    print(sheet.size)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
