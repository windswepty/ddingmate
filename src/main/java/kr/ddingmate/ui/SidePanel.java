package kr.ddingmate.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * 서버 창(바리스타 손님·밀키 상점·제작 시설) 옆에 붙이는 작은 판. 창마다 어울리는 색 묶음으로 그린다.
 * 카페: 크림색 몸통 + 갈색·크림 줄무늬 차양 머리(손님 창의 차양 그림과 맞춤), 부엌: 크림 + 토마토색, 공방: 띵메이트 기본 회청색.
 */
public final class SidePanel {

    private SidePanel() {}

    /**
     * @param stripe 머리 띠 줄무늬 두 번째 색(0 이면 줄무늬 없음)
     */
    public record Palette(int outline, int body, int header, int stripe, int headerText, int ink, int soft,
                          int accent, int good, int bad, int rowA, int rowB) {}

    /** 카페: 에스프레소 갈색 테두리, 라떼 크림 몸통, 캐러멜 강조 */
    public static final Palette CAFE = new Palette(0xFF2E1B10, 0xFFFBF1E3, 0xFF7A4A2A, 0xFFF3DDBE, 0xFFFFF7EA,
            0xFF3A2416, 0xFF6E5140, 0xFF9C5B1E, 0xFF2D6E2A, 0xFFA8322A, 0xFFF1E1CB, 0xFFFBF1E3);
    /** 부엌(밀키 요리 상점): 크림 몸통, 토마토색 머리 */
    public static final Palette KITCHEN = new Palette(0xFF3A1F14, 0xFFFFF7EE, 0xFFC4523A, 0xFFF6C9A8, 0xFFFFFFFF,
            0xFF3A2418, 0xFF6F5242, 0xFF9A5A00, 0xFF2D6E2A, 0xFFA8322A, 0xFFF6E6D4, 0xFFFFF7EE);
    /** 공방(제작 시설): 띵메이트 기본 색 */
    public static final Palette WORKSHOP = new Palette(Theme.OUTLINE, Theme.WINDOW, Theme.BLUE_MID, 0, Theme.WHITE,
            Theme.INK, Theme.TEXT_SOFT, Theme.BLUE_ON_LIGHT, Theme.GREEN_ON_LIGHT, Theme.RED_ON_LIGHT, 0xFFCDD3DC, Theme.WINDOW);

    public static final int HEAD_H = 16;

    /**
     * 서버 창 옆 자리 {x, y}. 손님 창 그림(가운데서 좌우 100칸)을 가리지 않는 오른쪽 → 왼쪽 → 바닐라 상자 창(좌우 88칸) 바로 옆 오른쪽 → 왼쪽
     * 순서로 들어가는 곳을 고르고, 어디도 안 되면 화면 오른쪽 끝에 붙인다.
     */
    public static int[] place(int screenW, int screenH, int w, int h) {
        int mid = screenW / 2;
        int x;
        if (mid + 104 + w <= screenW - 2) x = mid + 104;
        else if (mid - 104 - w >= 2) x = mid - 104 - w;
        else if (mid + 92 + w <= screenW - 2) x = mid + 92;
        else if (mid - 92 - w >= 2) x = mid - 92 - w;
        else x = Math.max(2, screenW - 2 - w);
        int y = Math.max(2, screenH / 2 - 90);
        if (y + h > screenH - 2) y = Math.max(2, screenH - 2 - h);
        return new int[]{x, y};
    }

    /**
     * 판 너비 상한: 기본 150, 화면이 넓어 창 오른쪽에 남는 자리가 있으면 그만큼(최대 210) 넓혀 긴 이름을 줄이지 않는다.
     * 좁은 화면(854×480 등)에서는 판이 창을 더 덮지 않도록 150 그대로다
     */
    public static int maxWidth(int screenW) {
        return Math.max(150, Math.min(210, screenW - 2 - (screenW / 2 + 104)));
    }

    /** 판 몸통 + 머리 띠(아이콘 + 굵은 제목) */
    public static void frame(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, Palette p, ItemStack icon, String title) {
        Theme.outline(ctx, x, y, w, h, p.outline());
        ctx.fill(x + 2, y + 1, x + w - 2, y + h - 1, p.body());
        ctx.fill(x + 1, y + 2, x + 2, y + h - 2, p.body());
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, p.body());
        // 머리 띠: 줄무늬 차양이면 4칸 간격으로 두 색을 번갈아 칠하고, 아래 가장자리를 물결 모양으로 깎는다
        int top = y + 1, bottom = y + HEAD_H;
        for (int i = x + 1; i < x + w - 1; i++) {
            int c = p.stripe() != 0 && ((i - x) / 5) % 2 == 1 ? p.stripe() : p.header();
            int y0 = i == x + 1 || i == x + w - 2 ? top + 1 : top;
            int wave = p.stripe() != 0 && (i - x) % 5 == 2 ? 1 : 0;
            ctx.fill(i, y0, i + 1, bottom + wave, c);
        }
        ctx.fill(x + 1, bottom + 1, x + w - 1, bottom + 2, Theme.withAlpha(p.outline(), 60));
        int tx = x + 6;
        if (icon != null) {
            Theme.item(ctx, icon, x + 4, y + 3, 11);
            tx = x + 18;
        }
        // 줄무늬 위에서도 읽히게 제목 밑에 어두운 띠
        int tw = Theme.boldWidth(tr, title);
        if (p.stripe() != 0) ctx.fill(tx - 2, y + 3, tx + tw + 2, y + 13, Theme.withAlpha(p.header(), 235));
        Theme.boldText(ctx, tr, title, tx, y + 5, p.headerText(), false);
    }

    /** 번갈아 칠하는 줄 바탕 */
    public static void rowBg(DrawContext ctx, int x, int y, int w, int h, Palette p, int index, boolean highlight) {
        int c = highlight ? Theme.lerp(p.rowA(), p.accent(), 0.18) : index % 2 == 0 ? p.rowA() : p.rowB();
        ctx.fill(x, y, x + w, y + h, c);
        if (highlight) ctx.fill(x, y, x + 2, y + h, p.accent());
    }

    /** 점선 구분선 */
    public static void divider(DrawContext ctx, int x, int y, int w, Palette p) {
        int c = Theme.withAlpha(p.soft(), 140);
        for (int i = 0; i < w; i += 3) ctx.fill(x + i, y, x + Math.min(w, i + 2), y + 1, c);
    }

    /** 작은 둥근 꼬리표. 그린 너비를 돌려준다 */
    public static int chip(DrawContext ctx, TextRenderer tr, int x, int y, String text, int bg, int fg) {
        int w = Theme.width(tr, text) + 6;
        Theme.outlineSmall(ctx, x, y, w, 10, Theme.lerp(bg, 0xFF000000, 0.35));
        ctx.fill(x + 1, y + 1, x + w - 1, y + 9, bg);
        Theme.text(ctx, tr, text, x + 3, y + 1, fg);
        return w;
    }
}
