package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.ui.HudSkins;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * HUD 꾸미기 미리보기 판: 지금 설정(판 · 제목 줄 · 친구 · 진하기)으로 일정 HUD 와 똑같이 그린다.
 * 꾸미기 창이 후보마다 설정을 잠깐 바꿔 끼우고 이걸 불러 실제 HUD 와 같은 모습으로 비교하게 한다.
 */
public final class HudPreview {
    private HudPreview() {
    }

    private static final ItemStack CLOCK = new ItemStack(Items.CLOCK);
    private static final String[][] ROWS = {
            {"오늘 접속 2시간 10분", "보상 2개", "gold"},
            {"바닥 아이템 청소", "3분 12초", "blue"},
            {"조약돌 뭉치 ×7", "전부 완료!", "green"},
            {"마을 4채널 재시작", "8분", "red"},
    };

    public static int height(int rows) {
        return ScheduleHud.HEAD_H + rows * 12 + 5;
    }

    /** (x, y)에 너비 w, 줄 rows 개짜리 일정 판. mascot 이면 고른 친구도 판 윗변에 */
    public static void draw(DrawContext ctx, TextRenderer tr, int x, int y, int w, int rows, boolean mascot) {
        DdingMateConfig cfg = DdingMate.config();
        boolean classic = cfg.hudStyle == DdingMateConfig.HudStyle.CLASSIC;
        boolean dark = cfg.hudStyle != DdingMateConfig.HudStyle.LIGHT;
        int alpha = cfg.hudOpacity * 255 / 100;
        int h = height(rows);
        if (classic) {
            ctx.fill(x, y, x + w, y + h, 0x80000000);
            Theme.boldText(ctx, tr, "다음 일정", x + 16, y + 3, Theme.WHITE, true);
            Theme.item(ctx, CLOCK, x + 4, y + 2, 10);
        } else {
            ScheduleHud.panel(ctx, x, y, w, h, alpha, dark);
            ScheduleHud.header(ctx, tr, x, y, w, "다음 일정", CLOCK, dark, alpha);
        }
        int ty = y + ScheduleHud.HEAD_H + 2;
        for (int i = 0; i < Math.min(rows, ROWS.length); i++) {
            String[] r = ROWS[i];
            int color = switch (r[2]) {
                case "gold" -> Theme.gold(dark);
                case "green" -> Theme.green(dark);
                case "red" -> Theme.red(dark);
                default -> Theme.blue(dark);
            };
            ctx.fill(x + 6, ty + 3, x + 9, ty + 6, color);
            String label = Theme.ellipsize(tr, r[0], w - 22 - Theme.boldWidth(tr, r[1]));
            Theme.textShadow(ctx, tr, label, x + 13, ty + 1, classic ? Theme.WHITE : Theme.text(dark), dark);
            Theme.boldText(ctx, tr, r[1], x + w - 6 - Theme.boldWidth(tr, r[1]), ty + 1, color, dark);
            ty += 12;
        }
        HudSkins.Skin s = mascot ? HudSkins.mascot() : null;
        if (s != null) HudSkins.drawMascot(ctx, s, x + w - HudSkins.mascotSize - 6, y - HudSkins.pawRow);
    }
}
