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
        if (ScheduleHud.hidden(MinecraftClient.getInstance())) return;
        HudLayout.push(ctx, HudLayout.TODO);
        try {
            draw(ctx);
        } finally {
            HudLayout.pop(ctx);
        }
    }

    private static void draw(DrawContext ctx) {
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
        List<DdingMateConfig.TodoItem> items = cfg.items;
        int total = items.size();
        int done = total - TodoList.remaining(now).size();
        boolean dark = all.hudStyle != DdingMateConfig.HudStyle.LIGHT;
        int alpha = all.hudOpacity * 255 / 100;

        // 다 한 일도 지우지 않고 체크 표시로 남긴다
        String title = "오늘 할 일 " + done + "/" + total;
        List<DdingMateConfig.TodoItem> shown = items.subList(0, Math.min(MAX_ROWS, total));
        int nameW = Theme.width(tr, "외 00개");
        for (DdingMateConfig.TodoItem t : shown) nameW = Math.max(nameW, Theme.width(tr, TodoList.label(t, now)));
        int rows = shown.size() + (total > MAX_ROWS ? 1 : 0);
        int boxW = Math.max(14 + nameW, 18 + Theme.boldWidth(tr, title)) + 12;
        int boxH = ScheduleHud.HEAD_H + 2 + rows * ROW_H + 3;
        int[] o = HudLayout.place(ctx, HudLayout.TODO, cfg.corner, boxW, boxH);
        int x = o[0], y = o[1];

        if (all.hudStyle == DdingMateConfig.HudStyle.CLASSIC) ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        else ScheduleHud.panel(ctx, x, y, boxW, boxH, alpha, dark);
        ScheduleHud.header(ctx, tr, x, y, boxW, title, BOOK, dark, alpha);
        int text = Theme.text(dark);
        int soft = Theme.soft(dark);
        int green = Theme.green(dark);
        int a = Math.max(alpha, 200);
        int cy = y + ScheduleHud.HEAD_H + 3;
        for (DdingMateConfig.TodoItem t : shown) {
            boolean ok = TodoList.isDone(t, now);
            // 체크 상자: 다 하면 초록 상자에 흰 체크 표시
            ctx.fill(x + 6, cy + 1, x + 13, cy + 8, Theme.withAlpha(ok ? green : dark ? 0xFF9FB8D8 : Theme.SLATE, a));
            if (ok) {
                check(ctx, x + 7, cy + 2, Theme.withAlpha(0xFFFFFFFF, a));
            } else {
                ctx.fill(x + 7, cy + 2, x + 12, cy + 7, Theme.withAlpha(dark ? 0xFF14171C : Theme.WINDOW_LIGHT, a));
            }
            Theme.textShadow(ctx, tr, TodoList.label(t, now), x + 17, cy, ok ? soft : text, dark);
            cy += ROW_H;
        }
        if (total > MAX_ROWS) Theme.textShadow(ctx, tr, "외 " + (total - MAX_ROWS) + "개", x + 17, cy, text, dark);
    }

    /** 5×5 칸 안의 체크 표시(✓) */
    private static void check(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x, y + 2, x + 1, y + 3, color);
        ctx.fill(x + 1, y + 3, x + 2, y + 4, color);
        ctx.fill(x + 2, y + 4, x + 3, y + 5, color);
        ctx.fill(x + 3, y + 3, x + 4, y + 4, color);
        ctx.fill(x + 4, y + 1, x + 5, y + 3, color);
    }
}
