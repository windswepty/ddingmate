"""작은 픽셀 아트 도구(띵메이트 HUD 그림 원본).

층(모양 + 4단 색)을 쌓으면 명암(빛은 왼쪽 위)·안쪽 선·바깥 외곽선이 자동으로 들어가고,
눈·코 같은 점은 손으로 찍는다. 볼터치처럼 아래 색과 섞을 점은 soft(반투명)로 찍는다.
좌표는 oy 만큼 내려서 그린다.
"""
from PIL import Image


def hexc(s):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


INK = hexc('2a1a14')


class Tone:
    """한 재질의 네 단계: 밝음·기본·그늘·짙음(안쪽 선·외곽선 바탕)"""

    def __init__(self, light, base, shade, dark=None):
        self.light, self.base, self.shade = hexc(light), hexc(base), hexc(shade)
        self.dark = hexc(dark) if dark else mix(self.shade, INK, 0.45)
        self.rim = mix(self.shade, self.dark, 0.35)  # 가장 바깥 그늘(한 칸)


class Canvas:
    def __init__(self, w, h, oy=0):
        self.w, self.h, self.oy = w, h, oy
        self.layers = []  # (mask set, tone, opts)
        self.dots = {}
        self.softs = []  # (x, y, rgba, alpha)

    # ---- 모양(좌표는 oy 만큼 아래로)
    def ellipse(self, cx, cy, rx, ry):
        cy += self.oy
        return {(x, y) for x in range(self.w) for y in range(self.h)
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0}

    def rect(self, x0, y0, x1, y1):
        y0, y1 = y0 + self.oy, y1 + self.oy
        return {(x, y) for x in range(max(0, int(x0)), min(self.w, int(x1))) for y in range(max(0, int(y0)), min(self.h, int(y1)))}

    def poly(self, pts):
        pts = [(x, y + self.oy) for x, y in pts]
        out = set()
        for x in range(self.w):
            for y in range(self.h):
                px, py = x + 0.5, y + 0.5
                inside = False
                j = len(pts) - 1
                for i in range(len(pts)):
                    xi, yi = pts[i]
                    xj, yj = pts[j]
                    if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                        inside = not inside
                    j = i
                if inside:
                    out.add((x, y))
        return out

    def mirror(self, pts, axis=14.5):
        """왼쪽 그림의 점들을 axis 를 기준으로 뒤집은 오른쪽 짝"""
        return [(2 * axis - x, y) for x, y in pts]

    def line(self, pts):
        """한 칸 굵기의 꺾은선"""
        out = set()
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
            for i in range(n + 1):
                t = i / n
                out.add((int(x0 + (x1 - x0) * t), int(y0 + (y1 - y0) * t + self.oy)))
        return {p for p in out if 0 <= p[0] < self.w and 0 <= p[1] < self.h}

    def add(self, mask, tone, line=False, shade=1, light=False):
        """shade: 오른쪽 아래 그늘 두께(0~3, 2 이상이면 맨 가장자리 한 칸은 더 짙게), light: 왼쪽 위 밝은 테두리,
        line: 아래 층과 맞닿은 곳에 안쪽 선"""
        self.layers.append((set(mask), tone, dict(line=line, shade=int(shade), light=light)))
        return mask

    def dot(self, x, y, color):
        y += self.oy
        if 0 <= x < self.w and 0 <= y < self.h:
            self.dots[(int(x), int(y))] = hexc(color) if isinstance(color, str) else color

    def dot_all(self, pts, color):
        for x, y in pts:
            self.dot(x, y, color)

    def soft(self, pts, color, alpha):
        """아래 색과 섞는 반투명 점(볼터치·은은한 빛)"""
        for x, y in pts:
            self.softs.append((int(x), int(y + self.oy), hexc(color), alpha))

    # ---- 그리기
    def render(self, outline=True, aa=True):
        owner = {}
        for li, (mask, tone, o) in enumerate(self.layers):
            for p in mask:
                owner[p] = li
        img = {}
        for p, li in owner.items():
            mask, tone, o = self.layers[li]
            x, y = p

            def out(px_, py_):
                return (px_, py_) not in mask

            col = tone.base
            # 이 층 자신의 모양 기준(위에 덮인 층 때문에 얼룩지지 않게). 빛은 왼쪽 위
            k = o['shade']
            if k and any(out(x + d, y + d) for d in range(1, k + 1)):
                col = tone.rim if (k >= 2 and out(x + 1, y + 1)) else tone.shade
            elif o['light'] and out(x - 1, y - 1):
                col = tone.light
            if o['line']:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    q = owner.get((x + dx, y + dy))
                    if q is not None and q < li:
                        col = tone.dark
                        break
            img[p] = col
        im = Image.new('RGBA', (self.w, self.h), (0, 0, 0, 0))
        for (x, y), c in img.items():
            im.putpixel((x, y), c)
        line_px = {}
        if outline:
            for x in range(self.w):
                for y in range(self.h):
                    if (x, y) in owner:
                        continue
                    near = [owner[(x + dx, y + dy)] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in owner]
                    if near:
                        tone = self.layers[max(near)][1]
                        c = mix(tone.dark, INK, 0.3)  # 셀렉티브 아웃라인: 맞닿은 부위 색을 어둡게
                        im.putpixel((x, y), c)
                        line_px[(x, y)] = c
            if aa:
                # 계단 모서리 부드럽게: 바깥 빈칸이 외곽선 두 개(ㄱ자)에 끼어 있으면 외곽선 색을 반투명으로
                for x in range(self.w):
                    for y in range(self.h):
                        if (x, y) in owner or (x, y) in line_px:
                            continue
                        for (ax, ay), (bx, by) in (((1, 0), (0, 1)), ((1, 0), (0, -1)), ((-1, 0), (0, 1)), ((-1, 0), (0, -1))):
                            a, b = (x + ax, y + ay), (x + bx, y + by)
                            if a in line_px and b in line_px and (x + ax + bx, y + ay + by) in owner:
                                c = line_px[a]
                                im.putpixel((x, y), (c[0], c[1], c[2], 110))
                                break
        for (x, y), c in self.dots.items():
            im.putpixel((x, y), c)
        for x, y, c, a in self.softs:
            if 0 <= x < self.w and 0 <= y < self.h:
                base = im.getpixel((x, y))
                if base[3] == 0:
                    continue
                im.putpixel((x, y), mix(base, c, a))
        return im
