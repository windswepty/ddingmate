package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.playtime.PlaytimeTracker;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 다음 일정 몇 개를 화면 구석에 표시한다. 서버 HUD·NPC 말풍선은 화면 위 가운데(보스바 영역)를 쓰므로
 * 구석에만 그린다. 기본 위치는 왼쪽 위.
 */
public final class AlarmHud {

    static final int MARGIN = 4;
    /** 아래쪽 구석은 채팅창·핫바와 겹치지 않도록 띄운다 */
    static final int BOTTOM_GAP = 60;
    static final int HEAD_H = 14;

    private static final ItemStack CLOCK = new ItemStack(Items.CLOCK);

    /** color 가 0 이 아니면 남은 시간 색 대신 그 색으로 그린다(접속 시간 줄) */
    private record Entry(String label, String left, long minutes, int color) {}

    private static long lastBuildMs;
    private static List<Entry> cached = List.of();

    private AlarmHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        AlarmConfig cfg = DdingMate.config();
        HudStack.reset(); // 프레임마다 가장 먼저 그리는 HUD
        if (!cfg.hudEnabled || hidden(client)) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBuildMs >= 1000) {
            cached = build(cfg);
            lastBuildMs = nowMs;
        }
        if (cached.isEmpty()) return;
        int h = cfg.hudStyle == AlarmConfig.HudStyle.CLASSIC
                ? drawClassic(ctx, client.textRenderer, cfg)
                : drawPanel(ctx, client.textRenderer, cfg, cfg.hudStyle == AlarmConfig.HudStyle.DARK);
        HudStack.push(cfg.hudCorner, h);
    }

    private static int drawPanel(DrawContext ctx, TextRenderer tr, AlarmConfig cfg, boolean dark) {
        int alpha = cfg.hudOpacity * 255 / 100;
        String header = "다음 일정";
        int labelW = 0, leftW = 0;
        for (Entry e : cached) {
            labelW = Math.max(labelW, Theme.width(tr, e.label()));
            leftW = Math.max(leftW, Theme.boldWidth(tr, e.left()));
        }
        int rowH = 11;
        int boxW = Math.max(12 + labelW + 10 + leftW, 18 + Theme.boldWidth(tr, header)) + 10;
        int boxH = HEAD_H + cached.size() * rowH + 5;
        int x = cornerX(ctx, cfg.hudCorner, boxW);
        int y = cornerY(ctx, cfg.hudCorner, boxH, 0);

        panel(ctx, x, y, boxW, boxH, alpha, dark);
        header(ctx, tr, x, y, boxW, header, CLOCK, dark, alpha);
        int ty = y + HEAD_H + 2;
        int text = dark ? 0xFFE8ECF2 : Theme.TEXT;
        for (int i = 0; i < cached.size(); i++) {
            Entry e = cached.get(i);
            int color = e.color() != 0 ? e.color()
                    : e.minutes() < 10 ? Theme.RED : e.minutes() < 60 ? Theme.GOLD : dark ? 0xFF9FB8D8 : Theme.BLUE_MID;
            ctx.fill(x + 6, ty + 3, x + 9, ty + 6, color);
            Theme.text(ctx, tr, e.label(), x + 13, ty + 1, text);
            Theme.boldText(ctx, tr, e.left(), x + boxW - 6 - Theme.boldWidth(tr, e.left()), ty + 1, color, dark);
            ty += rowH;
        }
        return boxH;
    }

    static void panel(DrawContext ctx, int x, int y, int w, int h, int alpha, boolean dark) {
        if (dark) Theme.hudDark(ctx, x, y, w, h, alpha);
        else Theme.hudLight(ctx, x, y, w, h, alpha);
    }

    /** HUD 판 위쪽 제목 줄: 작은 아이콘 + 굵은 제목 + 파란 밑줄 */
    static void header(DrawContext ctx, TextRenderer tr, int x, int y, int boxW, String title, ItemStack icon,
                       boolean dark, int alpha) {
        if (!dark) Theme.skyHeader(ctx, x + 2, y + 2, boxW - 4, HEAD_H - 3);
        Theme.item(ctx, icon, x + 4, y + 2, 10);
        Theme.boldText(ctx, tr, title, x + 16, y + 3, dark ? Theme.WHITE : Theme.INK, dark);
        if (dark) ctx.fill(x + 3, y + HEAD_H - 1, x + boxW - 3, y + HEAD_H, Theme.withAlpha(Theme.BLUE, Math.max(alpha, 200)));
    }

    private static int drawClassic(DrawContext ctx, TextRenderer tr, AlarmConfig cfg) {
        int pad = 3, lineH = 10;
        int w = 0;
        List<String> lines = new ArrayList<>();
        for (Entry e : cached) {
            String s = e.label() + "  " + e.left();
            lines.add(s);
            w = Math.max(w, tr.getWidth(s));
        }
        int boxW = w + pad * 2;
        int boxH = lines.size() * lineH + pad * 2 - 1;
        int x = cornerX(ctx, cfg.hudCorner, boxW);
        int y = cornerY(ctx, cfg.hudCorner, boxH, 0);
        ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        for (int i = 0; i < lines.size(); i++) {
            long m = cached.get(i).minutes();
            int color = cached.get(i).color() != 0 ? 0xFFFFD27A : m < 10 ? 0xFFFF6B6B : m < 60 ? 0xFFFFE066 : 0xFFFFFFFF;
            ctx.drawTextWithShadow(tr, lines.get(i), x + pad, y + pad + i * lineH, color);
        }
        return boxH;
    }

    static int cornerX(DrawContext ctx, AlarmConfig.Corner corner, int boxW) {
        return switch (corner) {
            case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
            case TOP_RIGHT, BOTTOM_RIGHT -> ctx.getScaledWindowWidth() - boxW - MARGIN;
        };
    }

    static int cornerY(DrawContext ctx, AlarmConfig.Corner corner, int boxH, int stack) {
        return switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> MARGIN + stack;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> ctx.getScaledWindowHeight() - boxH - BOTTOM_GAP - stack;
        };
    }

    private static List<Entry> build(AlarmConfig cfg) {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        List<Entry> out = new ArrayList<>();
        long played = cfg.playtime.showInHud ? PlaytimeTracker.seconds() : -1;
        if (played >= 0) {
            int next = PlaytimeTracker.nextRewardHour(played);
            // 서버 기록(/접속시간 창)에 맞춘 적이 없으면 추정값이라 "약"을 붙인다
            boolean synced = PlaytimeTracker.syncedAt() > 0;
            List<Integer> unclaimed = PlaytimeTracker.unclaimedRewards();
            String left;
            if (unclaimed != null && !unclaimed.isEmpty()) left = "받을 보상 " + unclaimed.size() + "개";
            else if (next == 0) left = unclaimed != null ? "보상 다 받음" : "보상 다 채움";
            else left = "다음 보상 " + hm(next * 3600L - played);
            out.add(new Entry("오늘 접속 " + (synced ? "" : "약 ") + hm(played), left, Long.MAX_VALUE, Theme.GOLD));
        }
        for (ScheduleEngine.Occurrence o : ScheduleEngine.upcoming(DdingMate.rules(), cfg::isEnabled, now, cfg.hudLines)) {
            Duration left = Duration.between(now, o.time());
            out.add(new Entry(o.rule().label(), ScheduleEngine.formatRemaining(left), left.toMinutes(), 0));
        }
        return out;
    }

    /** "1시간 5분", "42분" */
    static String hm(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "시간 " + m + "분" : Math.max(m, seconds > 0 ? 1 : 0) + "분";
    }

    /** F1(HUD 숨기기)·F3 화면이거나 월드 밖이면 그리지 않는다. */
    public static boolean hidden(MinecraftClient client) {
        return client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud();
    }
}
