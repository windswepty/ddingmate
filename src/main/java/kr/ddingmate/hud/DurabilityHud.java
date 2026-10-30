package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gear.DurabilityTracker;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/** 추적 중인 장비의 남은 내구도. 50% 넘으면 초록, 20% 넘으면 노랑, 그 아래는 빨강 */
public final class DurabilityHud {

    private static final int ROW_H = 14;
    private static final int ICON = 12;
    private static final ItemStack ANVIL = new ItemStack(Items.ANVIL);

    private DurabilityHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (ScheduleHud.hidden(MinecraftClient.getInstance())) return;
        HudLayout.push(ctx, HudLayout.DURABILITY);
        try {
            draw(ctx);
        } finally {
            HudLayout.pop(ctx);
        }
    }

    private static void draw(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig all = DdingMate.config();
        DdingMateConfig.Durability cfg = all.durability;
        List<DurabilityTracker.Row> rows = DurabilityTracker.rows();
        if (ScheduleHud.hidden(client)) return;
        if (!cfg.enabled || rows.isEmpty()) {
            HudLayout.placeholder(ctx, HudLayout.DURABILITY, cfg.corner, ANVIL, cfg.enabled ? "추적 중인 장비가 없어요" : "꺼져 있어요");
            return;
        }
        TextRenderer tr = client.textRenderer;
        boolean classic = all.hudStyle == DdingMateConfig.HudStyle.CLASSIC;
        boolean dark = all.hudStyle != DdingMateConfig.HudStyle.LIGHT;
        int alpha = all.hudOpacity * 255 / 100;

        String title = "장비 내구도";
        int nameW = 0, valW = 0;
        for (DurabilityTracker.Row r : rows) {
            nameW = Math.max(nameW, Theme.width(tr, r.item().name));
            valW = Math.max(valW, Theme.boldWidth(tr, value(r)));
        }
        int boxW = Math.max(ICON + 4 + nameW + 8 + valW, 18 + Theme.boldWidth(tr, title)) + 12;
        int boxH = ScheduleHud.HEAD_H + 2 + rows.size() * ROW_H + 2;
        boxW = HudLayout.widen(HudLayout.DURABILITY, boxW); // 편집 화면에서 가로로 늘린 너비
        int[] o = HudLayout.place(ctx, HudLayout.DURABILITY, cfg.corner, boxW, boxH);
        int x = o[0], y = o[1];

        if (classic) ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        else ScheduleHud.panel(ctx, x, y, boxW, boxH, alpha, dark);
        ScheduleHud.header(ctx, tr, x, y, boxW, title, ANVIL, dark, alpha);
        int text = Theme.text(dark);
        int soft = Theme.soft(dark);
        int cy = y + ScheduleHud.HEAD_H + 2;
        for (DurabilityTracker.Row r : rows) {
            int rx = x + 6;
            // 지금 안 들고 있으면 전에 본 모양으로(본 적 없으면 물음표)
            ItemStack icon = r.stack() != null ? DurabilityTracker.iconOf(r.stack()) : kr.ddingmate.counter.IconCache.forKey(r.item().key);
            if (icon != null) Theme.item(ctx, icon, rx, cy, ICON);
            else Theme.unknownIcon(ctx, tr, rx, cy, ICON);
            // 맞은 뒤라 줄었을 수 있는 치장 아래 값은 흐리게
            int color = r.durability() == null || !r.exact() ? soft : colorOf(r.durability().ratio(), dark);
            Theme.textShadow(ctx, tr, r.item().name, rx + ICON + 4, cy + 1, r.durability() == null ? soft : text, dark);
            String v = value(r);
            Theme.boldText(ctx, tr, v, x + boxW - 6 - Theme.boldWidth(tr, v), cy + 1, color, false);
            if (r.durability() != null) {
                int barX = rx + ICON + 4;
                int barW = x + boxW - 6 - barX;
                ctx.fill(barX, cy + 10, barX + barW, cy + 12, Theme.withAlpha(dark ? 0xFF2A2E35 : Theme.SLATE, alpha));
                ctx.fill(barX, cy + 10, barX + (int) Math.round(barW * r.durability().ratio()), cy + 12, color);
            }
            cy += ROW_H;
        }
    }

    private static String value(DurabilityTracker.Row r) {
        return DurabilityTracker.valueText(r);
    }

    static int colorOf(double ratio, boolean dark) {
        return ratio > 0.5 ? Theme.green(dark) : ratio > 0.2 ? Theme.gold(dark) : Theme.red(dark);
    }
}
