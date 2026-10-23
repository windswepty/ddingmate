"""2배 해상도 캔버스(시안 v3): 기존 40칸 좌표를 그대로 받아 80칸에 그린다.
모양(타원·네모·다각형)은 2배로 매끈하게, 점(dot)은 2×2 로(예전 모습 유지), 고운 점(fdot)·고운 타원(fellipse)은 80칸 좌표 그대로."""
from px import Canvas, Tone, hexc, mix, hd_tone  # noqa: F401


class Canvas2x(Canvas):
    def __init__(self, w, h, oy=0):
        super().__init__(w * 2, h * 2, oy * 2)

    def ellipse(self, cx, cy, rx, ry):
        return super().ellipse(cx * 2, cy * 2, rx * 2, ry * 2)

    def rect(self, x0, y0, x1, y1):
        return super().rect(x0 * 2, y0 * 2, x1 * 2, y1 * 2)

    def poly(self, pts):
        return super().poly([(x * 2, y * 2) for x, y in pts])

    def line(self, pts):
        base = super().line([(x * 2, y * 2) for x, y in pts])
        return base | {(x + 1, y) for x, y in base if x + 1 < self.w}  # 2칸 굵기

    def add(self, mask, tone, line=False, shade=1, light=False):
        return super().add(mask, tone, line, int(shade) * 2, light)

    def dot(self, x, y, color):
        fx, fy = int(x * 2), int(y * 2)
        for dx in (0, 1):
            for dy in (0, 1):
                super().dot(fx + dx, fy + dy, color)

    def soft(self, pts, color, alpha):
        super().soft([(int(x * 2) + dx, int(y * 2) + dy) for x, y in pts for dx in (0, 1) for dy in (0, 1)], color, alpha)

    # ---- 80칸 좌표 그대로
    def fdot(self, x, y, color):
        super().dot(int(x), int(y), color)

    def fsoft(self, pts, color, alpha):
        super().soft(list(pts), color, alpha)

    def fellipse(self, cx, cy, rx, ry):
        return Canvas.ellipse(self, cx, cy, rx, ry)

    # ---- 시안 v4: 기울인 타원·둥근 막대(팔·꼬리·손잡이), 40칸 좌표
    def ellipse_r(self, cx, cy, rx, ry, deg):
        import math
        a = math.radians(deg)
        ca, sa = math.cos(a), math.sin(a)
        cx, cy, rx, ry = cx * 2, cy * 2, rx * 2, ry * 2
        out = set()
        for x in range(self.w):
            for y in range(self.h):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                u, v = dx * ca + dy * sa, -dx * sa + dy * ca
                if (u / rx) ** 2 + (v / ry) ** 2 <= 1.0:
                    out.add((x, y))
        return out

    def capsule(self, x0, y0, x1, y1, r):
        """둥근 막대: (x0,y0)~(x1,y1), 반지름 r(40칸 단위)"""
        x0, y0, x1, y1, r = x0 * 2, y0 * 2, x1 * 2, y1 * 2, r * 2
        vx, vy = x1 - x0, y1 - y0
        ll = vx * vx + vy * vy or 1
        out = set()
        for x in range(self.w):
            for y in range(self.h):
                px, py = x + 0.5, y + 0.5
                t = max(0.0, min(1.0, ((px - x0) * vx + (py - y0) * vy) / ll))
                if (px - x0 - vx * t) ** 2 + (py - y0 - vy * t) ** 2 <= r * r:
                    out.add((x, y))
        return out
