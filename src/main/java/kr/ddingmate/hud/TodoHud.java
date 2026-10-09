package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.todo.TodoList;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.time.ZonedDateTime;
import java.util.List;

/** 아직 안 한 할 일 목록. 다 하면 "오늘 할 일 끝!" 한 줄만 남는다 */
public final class TodoHud {

    private static final int ROW_H = 11;
    private static final int MAX_ROWS = 8;
    private static final ItemStack BOOK = new ItemStack(Items.WRITABLE_BOOK);

    private TodoHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig all = DdingMate.config();
        DdingMateConfig.Todo cfg = all.todo;
        if (ScheduleHud.hidden(client)) return;
        if (!cfg.hudEnabled || cfg.items.isEmpty()) {
            HudLayout.placeholder(ctx, HudLayout.TODO, cfg.corner, BOOK, cfg.hudEnabled ? "할 일이 없어요" : "꺼져 있어요");
            return;
        }
        TextRenderer tr = client.textRenderer;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        List<DdingMateConfig.TodoItem> left = TodoList.remaining(now);
        int total = cfg.items.size();
        boolean dark = all.hudStyle != DdingMateConfig.HudStyle.LIGHT;
        int alpha = all.hudOpacity * 255 / 100;

        String title = "오늘 할 일 " + (total - left.size()) + "/" + total;
        List<DdingMateConfig.TodoItem> shown = left.subList(0, Math.min(MAX_ROWS, left.size()));
        int nameW = Theme.width(tr, left.isEmpty() ? "오늘 할 일 끝!" : "외 00개");
        for (DdingMateConfig.TodoItem t : shown) nameW = Math.max(nameW, Theme.width(tr, t.name));
        int rows = left.isEmpty() ? 1 : shown.size() + (left.size() > MAX_ROWS ? 1 : 0);
        int boxW = Math.max(14 + nameW, 18 + Theme.boldWidth(tr, title)) + 12;
        int boxH = ScheduleHud.HEAD_H + 2 + rows * ROW_H + 3;
        int[] o = HudLayout.place(ctx, HudLayout.TODO, cfg.corner, boxW, boxH);
        int x = o[0], y = o[1];

        if (all.hudStyle == DdingMateConfig.HudStyle.CLASSIC) ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        else ScheduleHud.panel(ctx, x, y, boxW, boxH, alpha, dark);
        ScheduleHud.header(ctx, tr, x, y, boxW, title, BOOK, dark, alpha);
        int text = dark ? 0xFFE8ECF2 : Theme.TEXT;
        int cy = y + ScheduleHud.HEAD_H + 3;
        if (left.isEmpty()) {
            Theme.text(ctx, tr, "오늘 할 일 끝!", x + 6, cy, dark ? 0xFF8BE08A : Theme.GREEN_DARK);
        } else {
            for (DdingMateConfig.TodoItem t : shown) {
                // 빈 체크 상자
                ctx.fill(x + 6, cy + 1, x + 13, cy + 8, Theme.withAlpha(dark ? 0xFF9FB8D8 : Theme.SLATE, Math.max(alpha, 200)));
                ctx.fill(x + 7, cy + 2, x + 12, cy + 7, Theme.withAlpha(dark ? 0xFF14171C : Theme.WINDOW_LIGHT, Math.max(alpha, 200)));
                Theme.text(ctx, tr, t.name, x + 17, cy, text);
                cy += ROW_H;
            }
            if (left.size() > MAX_ROWS) Theme.text(ctx, tr, "외 " + (left.size() - MAX_ROWS) + "개", x + 17, cy, text);
        }
    }
}
