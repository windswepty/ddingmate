package kr.ddingmate.ui;

import net.minecraft.client.gui.DrawContext;

/**
 * HUD 판 테두리 장식(픽셀 아트, 2026-10-22 사용자 요청). 네 귀퉁이에 5×5 픽셀 그림을 좌우·위아래 대칭으로 얹거나,
 * 나무 테두리처럼 판 둘레를 두른다. 그림은 이 모드에서 직접 찍은 점이다.
 */
public enum HudFrame {
    NONE("없음", null, new int[0]),
    FLOWER("꽃", new String[]{
            "..a..",
            ".aba.",
            "abcba",
            ".aba.",
            "..a.."}, new int[]{0xFFFF7FAE, 0xFFFFC6DC, 0xFFFFD84D}),
    STAR("별", new String[]{
            "..a..",
            "..b..",
            "abbba",
            ".bab.",
            ".a.a."}, new int[]{0xFFE0A800, 0xFFFFE066}),
    HEART("하트", new String[]{
            ".a.a.",
            "abaaa",
            "aaaaa",
            ".aaa.",
            "..a.."}, new int[]{0xFFFF4D6D, 0xFFFFC2CF}),
    LEAF("잎사귀", new String[]{
            "...aa",
            "..aba",
            ".aba.",
            "aba..",
            "a...."}, new int[]{0xFF2F7D3A, 0xFF8AD07A}),
    /** 둘레를 나무 판자처럼(귀퉁이에 못) */
    WOOD("나무 테두리", null, new int[]{0xFF5A3418, 0xFFA36A3A, 0xFFE2C38A});

    public final String label;
    private final String[] sprite;
    private final int[] colors;

    HudFrame(String label, String[] sprite, int[] colors) {
        this.label = label;
        this.sprite = sprite;
        this.colors = colors;
    }

    /** 판(x, y, w, h)에 장식을 그린다. alpha 는 HUD 진하기(장식은 너무 흐려지지 않게 200 이상) */
    public void draw(DrawContext ctx, int x, int y, int w, int h, int alpha) {
        if (this == NONE || w < 12 || h < 12) return;
        int a = Math.max(alpha, 200);
        if (this == WOOD) {
            int dark = Theme.withAlpha(colors[0], a), mid = Theme.withAlpha(colors[1], a), nail = Theme.withAlpha(colors[2], a);
            // 바깥 2칸 짙은 나무 + 안쪽 1칸 밝은 결
            ctx.fill(x - 1, y - 1, x + w + 1, y + 1, dark);
            ctx.fill(x - 1, y + h - 1, x + w + 1, y + h + 1, dark);
            ctx.fill(x - 1, y + 1, x + 1, y + h - 1, dark);
            ctx.fill(x + w - 1, y + 1, x + w + 1, y + h - 1, dark);
            ctx.fill(x + 1, y + 1, x + w - 1, y + 2, mid);
            ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, mid);
            for (int[] p : new int[][]{{x, y}, {x + w - 1, y}, {x, y + h - 1}, {x + w - 1, y + h - 1}}) {
                ctx.fill(p[0], p[1], p[0] + 1, p[1] + 1, nail);
            }
            return;
        }
        corner(ctx, x - 2, y - 2, false, false, a);
        corner(ctx, x + w - 3, y - 2, true, false, a);
        corner(ctx, x - 2, y + h - 3, false, true, a);
        corner(ctx, x + w - 3, y + h - 3, true, true, a);
    }

    private void corner(DrawContext ctx, int x, int y, boolean flipX, boolean flipY, int a) {
        for (int r = 0; r < 5; r++) {
            String row = sprite[flipY ? 4 - r : r];
            for (int c = 0; c < 5; c++) {
                char ch = row.charAt(flipX ? 4 - c : c);
                if (ch == '.') continue;
                int color = colors[ch - 'a'];
                ctx.fill(x + c, y + r, x + c + 1, y + r + 1, Theme.withAlpha(color, a));
            }
        }
    }

    /** 시험용: 그림 칸 수(빈 칸 빼고) */
    public int pixels() {
        if (sprite == null) return this == WOOD ? 1 : 0;
        int n = 0;
        for (String row : sprite) for (char ch : row.toCharArray()) if (ch != '.') n++;
        return n;
    }
}
