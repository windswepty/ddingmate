package kr.ddingmate.ui;

import net.minecraft.client.gui.DrawContext;

/**
 * HUD 판 꾸미기(픽셀 아트, 2026-10-22 사용자 요청: 모서리만이 아니라 테두리·모서리·가운데까지).
 * 한 벌마다 네 가지를 그린다: 둘레 무늬(변을 따라 되풀이) · 네 귀퉁이 그림 · 위쪽 가운데 큰 그림(엠블럼) · 판 안의 아주 옅은 바탕 무늬.
 * 그림은 이 모드에서 직접 찍은 점이다(문자 한 칸 = 점 하나, '.' 은 빈칸, a·b·c… 는 colors 순서).
 */
public enum HudFrame {
    NONE("없음", null, null, null, 0, 0, new int[0]),

    FLOWER("꽃 정원",
            new String[]{
                    "..a..",
                    ".aba.",
                    "abcba",
                    ".aba.",
                    "..a.."},
            new String[]{
                    ".a.",
                    "aca",
                    ".a."},
            new String[]{
                    "...aaa...",
                    "..aabaa..",
                    "..abcba..",
                    "..aabaa..",
                    "d..aaa..d",
                    ".dd.d.dd.",
                    "...ddd..."},
            0xFF4E9A4A, 11, new int[]{0xFFFF7FAE, 0xFFFFC6DC, 0xFFFFD84D, 0xFF4E9A4A}),

    STAR("별빛",
            new String[]{
                    "..a..",
                    "..b..",
                    "abbba",
                    ".bab.",
                    ".a.a."},
            new String[]{
                    ".a.",
                    "aba",
                    ".a."},
            new String[]{
                    "...a...",
                    "...a...",
                    "..aba..",
                    "aabbbaa",
                    ".abbba.",
                    "..aba..",
                    ".aa.aa.",
                    "aa...aa"},
            0xFFE0B020, 12, new int[]{0xFFE0A800, 0xFFFFF2A0}),

    HEART("하트",
            new String[]{
                    ".a.a.",
                    "abaaa",
                    "aaaaa",
                    ".aaa.",
                    "..a.."},
            new String[]{
                    "a.a",
                    "aaa",
                    ".a."},
            new String[]{
                    "c.aa.aa.c",
                    "ccabaaacc",
                    ".caaaaac.",
                    "..aaaaa..",
                    "...aaa...",
                    "....a...."},
            0xFFFF7FA0, 10, new int[]{0xFFFF4D6D, 0xFFFFC2CF, 0xFFFFFFFF}),

    LEAF("숲",
            new String[]{
                    "...aa",
                    "..aba",
                    ".aba.",
                    "aba..",
                    "a...."},
            new String[]{
                    ".aa",
                    "ab.",
                    "a.."},
            new String[]{
                    "aa...aa",
                    "aba.aba",
                    ".aabaa.",
                    "...c...",
                    "...c...",
                    "..ccc.."},
            0xFF3F8A3F, 9, new int[]{0xFF2F7D3A, 0xFF8AD07A, 0xFF7A5230}),

    /** 둘레를 나무 판자처럼(결·못), 위쪽 가운데 나무 명패 */
    WOOD("나무 판자",
            new String[]{
                    "aaaaa",
                    "abbba",
                    "abcba",
                    "abbba",
                    "aaaaa"},
            null,
            new String[]{
                    "aaaaaaaaaaa",
                    "abbbbbbbbba",
                    "abcbbbbbcba",
                    "abbbbbbbbba",
                    "aaaaaaaaaaa"},
            0xFF6B3F1E, 0, new int[]{0xFF4A2A12, 0xFFA36A3A, 0xFFE2C38A});

    public final String label;
    private final String[] corner;
    private final String[] mini;
    private final String[] emblem;
    /** 둘레 선 색, 둘레 작은 그림 사이 간격(0 = 작은 그림 없음) */
    private final int edge, step;
    private final int[] colors;

    HudFrame(String label, String[] corner, String[] mini, String[] emblem, int edge, int step, int[] colors) {
        this.label = label;
        this.corner = corner;
        this.mini = mini;
        this.emblem = emblem;
        this.edge = edge;
        this.step = step;
        this.colors = colors;
    }

    /** 그림 한 점의 크기(화면 픽셀). 귀퉁이·엠블럼은 2배, 둘레 작은 그림·바탕 무늬는 1배 */
    static final int PX = 2;

    /** 판(x, y, w, h)을 꾸민다. alpha 는 HUD 진하기(장식은 너무 흐려지지 않게 200 이상) */
    public void draw(DrawContext ctx, int x, int y, int w, int h, int alpha) {
        if (this == NONE || w < 40 || h < 24) return;
        int a = Math.max(alpha, 200);
        pattern(ctx, x, y, w, h);
        // 가운데 그림(워터마크): 판 안 가운데에 크고 옅게. 판 밖으로 튀어나오지 않아 HUD 배치가 그대로다
        // (처음엔 윗변 위로 올렸는데 작은 화면에서 HUD 끼리 겹쳤다, 2026-10-22 시험)
        int body = 14;
        int ex = emblem[0].length(), ey = emblem.length;
        int scale = ex * 3 <= w - 24 && ey * 3 <= h - body - 6 ? 3 : 2;
        if (ex * scale <= w - 12 && ey * scale <= h - body - 4) {
            sprite(ctx, emblem, x + (w - ex * scale) / 2, y + body + (h - body - ey * scale) / 2, scale, false, false, WATERMARK, false);
        }
        edges(ctx, x, y, w, h, a);
        int cs = 5 * PX;
        sprite(ctx, corner, x - 4, y - 4, PX, false, false, a, true);
        sprite(ctx, corner, x + w - cs + 4, y - 4, PX, true, false, a, true);
        sprite(ctx, corner, x - 4, y + h - cs + 4, PX, false, true, a, true);
        sprite(ctx, corner, x + w - cs + 4, y + h - cs + 4, PX, true, true, a, true);
    }

    /** 가운데 그림 진하기(0~255): 글자 뒤에서 은은하게 */
    static final int WATERMARK = 56;

    /** 둘레: 판 바깥 테두리 선을 이 꾸미기 색으로 덧칠하고, 그 위에 작은 그림을 간격마다 */
    private void edges(DrawContext ctx, int x, int y, int w, int h, int a) {
        if (this == WOOD) {
            int dark = Theme.withAlpha(colors[0], a), mid = Theme.withAlpha(colors[1], a), nail = Theme.withAlpha(colors[2], a);
            ctx.fill(x - 2, y - 2, x + w + 2, y + 1, dark);
            ctx.fill(x - 2, y + h - 1, x + w + 2, y + h + 2, dark);
            ctx.fill(x - 2, y + 1, x + 1, y + h - 1, dark);
            ctx.fill(x + w - 1, y + 1, x + w + 2, y + h - 1, dark);
            // 판자 결: 밝은 줄과 이음매
            ctx.fill(x - 1, y - 1, x + w + 1, y, mid);
            ctx.fill(x - 1, y + h, x + w + 1, y + h + 1, mid);
            ctx.fill(x - 1, y, x, y + h, mid);
            ctx.fill(x + w, y, x + w + 1, y + h, mid);
            for (int i = 24; i < w - 12; i += 30) {
                ctx.fill(x + i, y - 2, x + i + 1, y + 1, Theme.withAlpha(0xFF2A160A, a));
                ctx.fill(x + i, y + h - 1, x + i + 1, y + h + 2, Theme.withAlpha(0xFF2A160A, a));
                ctx.fill(x + i + 3, y - 1, x + i + 4, y, nail);
                ctx.fill(x + i + 3, y + h, x + i + 4, y + h + 1, nail);
            }
            return;
        }
        int line = Theme.withAlpha(edge, a);
        ctx.fill(x, y, x + w, y + 1, line);
        ctx.fill(x, y + h - 1, x + w, y + h, line);
        ctx.fill(x, y, x + 1, y + h, line);
        ctx.fill(x + w - 1, y, x + w, y + h, line);
        if (mini == null || step <= 0) return;
        int ms = mini.length;
        for (int i = 12; i < w - 12 - ms; i += step) {
            sprite(ctx, mini, x + i, y - 1, 1, false, false, a, false);
            sprite(ctx, mini, x + i, y + h - ms + 1, 1, false, true, a, false);
        }
        for (int j = 12; j < h - 12 - ms; j += step) {
            sprite(ctx, mini, x - 1, y + j, 1, false, false, a, false);
            sprite(ctx, mini, x + w - ms + 1, y + j, 1, true, false, a, false);
        }
    }

    /** 판 안의 아주 옅은 바탕 무늬(글자는 이 위에 그려진다). 제목 줄 아래부터 */
    private void pattern(DrawContext ctx, int x, int y, int w, int h) {
        if (mini == null) return;
        int faint = 34; // 0~255: 글자를 가리지 않게 아주 옅게
        int top = y + 16, ms = mini.length;
        int row = 0;
        for (int py = top; py < y + h - ms - 4; py += 18, row++) {
            for (int px = x + 10 + (row % 2) * 12; px < x + w - ms - 6; px += 24) {
                sprite(ctx, mini, px, py, 1, false, false, faint, false);
            }
        }
    }

    /** 문자 그림을 (x, y)에 한 점 px 크기로. outline 이면 둘레에 어두운 테두리 */
    private void sprite(DrawContext ctx, String[] s, int x, int y, int px, boolean flipX, boolean flipY, int a, boolean outline) {
        if (s == null) return;
        int rows = s.length, cols = s[0].length();
        int dark = Theme.withAlpha(Theme.lerp(colors[0], 0xFF000000, 0.6), a);
        for (int pass = outline ? 0 : 1; pass < 2; pass++) {
            for (int r = 0; r < rows; r++) {
                String row = s[flipY ? rows - 1 - r : r];
                for (int c = 0; c < cols; c++) {
                    char ch = row.charAt(flipX ? cols - 1 - c : c);
                    if (ch == '.') continue;
                    int qx = x + c * px, qy = y + r * px;
                    if (pass == 0) ctx.fill(qx - 1, qy - 1, qx + px + 1, qy + px + 1, dark);
                    else ctx.fill(qx, qy, qx + px, qy + px, Theme.withAlpha(colors[ch - 'a'], a));
                }
            }
        }
    }

    /** 시험용: 귀퉁이 그림 점 수(빈 칸 빼고) */
    public int pixels() {
        if (corner == null) return 0;
        int n = 0;
        for (String row : corner) for (char ch : row.toCharArray()) if (ch != '.') n++;
        return n;
    }
}
