package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.skill.SkillTimers;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/**
 * 쓰고 있거나 다시 쓰기를 기다리는 전문가 스킬. 쓸 일이 없으면 아무것도 그리지 않는다.
 * 사용 중: 초록 막대가 줄어듦 · 재사용 대기: 노란 막대 · 다시 쓸 수 있게 되면 잠깐 "사용 가능"
 */
public final class SkillHud {

    private static final int ROW_H = 14;
    private static final int ICON = 12;
    private static final ItemStack BOTTLE = new ItemStack(Items.EXPERIENCE_BOTTLE);

    private SkillHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (ScheduleHud.hidden(MinecraftClient.getInstance())) return;
        HudLayout.push(ctx, HudLayout.SKILL);
        try {
            draw(ctx);
        } finally {
            HudLayout.pop(ctx);
        }
    }

    private static void draw(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig all = DdingMate.config();
        DdingMateConfig.Skills cfg = all.skills;
        long now = kr.ddingmate.util.Clock.now();
        List<SkillTimers.Row> rows = cfg.enabled ? SkillTimers.rows(now) : List.of();
        if (rows.isEmpty()) {
            HudLayout.placeholder(ctx, HudLayout.SKILL, cfg.corner, BOTTLE,
                    cfg.enabled ? "스킬을 쓰면 여기에 나와요" : "꺼져 있어요");
            return;
        }
        TextRenderer tr = client.textRenderer;
        boolean classic = all.hudStyle == DdingMateConfig.HudStyle.CLASSIC;
        boolean dark = all.hudStyle != DdingMateConfig.HudStyle.LIGHT;
        int alpha = all.hudOpacity * 255 / 100;

        String title = "전문가 스킬";
        int nameW = 0, valW = 0;
        for (SkillTimers.Row r : rows) {
            nameW = Math.max(nameW, Theme.width(tr, r.name()));
            // 숫자가 줄어들 때 상자 너비가 흔들리지 않게 가장 긴 모양으로 잰다
            valW = Math.max(valW, Theme.boldWidth(tr, widest(r, now)));
        }
        int boxW = Math.max(ICON + 4 + nameW + 8 + valW, 18 + Theme.boldWidth(tr, title)) + 12;
        int boxH = ScheduleHud.HEAD_H + 2 + rows.size() * ROW_H + 2;
        boxW = HudLayout.widen(HudLayout.SKILL, boxW); // 편집 화면에서 가로로 늘린 너비
        int[] o = HudLayout.place(ctx, HudLayout.SKILL, cfg.corner, boxW, boxH);
        int x = o[0], y = o[1];

        if (classic) ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        else ScheduleHud.panel(ctx, x, y, boxW, boxH, alpha, dark);
        ScheduleHud.header(ctx, tr, x, y, boxW, title, BOTTLE, dark, alpha);
        int text = Theme.text(dark);
        int soft = Theme.soft(dark);
        int cy = y + ScheduleHud.HEAD_H + 2;
        for (SkillTimers.Row r : rows) {
            int rx = x + 6;
            Theme.item(ctx, SkillTimers.icon(r.skill()), rx, cy, ICON); // 스킬마다 자기 그림(사용자: 보석보다 이쪽이 좋다)
            String v = SkillTimers.text(r, now);
            boolean known = SkillTimers.ratio(r, now) >= 0;
            int color = switch (r.phase()) {
                case ACTIVE -> Theme.green(dark);
                case READY -> Theme.green(dark);
                case COOLDOWN -> known ? Theme.gold(dark) : soft;
            };
            Theme.textShadow(ctx, tr, r.name(), rx + ICON + 4, cy + 1, text, dark);
            Theme.boldText(ctx, tr, v, x + boxW - 6 - Theme.boldWidth(tr, v), cy + 1, color, false);
            double ratio = SkillTimers.ratio(r, now);
            if (ratio >= 0) {
                int barX = rx + ICON + 4;
                int barW = x + boxW - 6 - barX;
                ctx.fill(barX, cy + 10, barX + barW, cy + 12, Theme.withAlpha(dark ? 0xFF2A2E35 : Theme.SLATE, alpha));
                ctx.fill(barX, cy + 10, barX + (int) Math.round(barW * ratio), cy + 12, color);
            }
            cy += ROW_H;
        }
    }

    /** 이 줄에 나올 수 있는 가장 넓은 글자(지금 남은 시간의 자릿수를 8로 채운 모양) */
    private static String widest(SkillTimers.Row r, long now) {
        return SkillTimers.text(r, now).replaceAll("\\d", "8");
    }
}
