package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
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
public final class ScheduleHud {

    static final int MARGIN = 4;
    static final int HEAD_H = 14;

    private static final ItemStack CLOCK = new ItemStack(Items.CLOCK);

    /** color 가 0 이 아니면 남은 시간 색 대신 그 색으로 그린다(접속 시간 줄) */
    private record Entry(String label, String left, long minutes, int color) {}

    private static long lastBuildMs;
    private static List<Entry> cached = List.of();

    private ScheduleHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        HudLayout.beginFrame(); // 프레임마다 가장 먼저 그리는 HUD
        if (hidden(MinecraftClient.getInstance())) return;
        HudLayout.push(ctx, HudLayout.SCHEDULE);
        try {
            draw(ctx);
        } finally {
            HudLayout.pop(ctx);
        }
    }

    private static void draw(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig cfg = DdingMate.config();
        if (!cfg.hudEnabled) {
            HudLayout.placeholder(ctx, HudLayout.SCHEDULE, cfg.hudCorner, CLOCK, "꺼져 있어요");
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBuildMs >= 1000) {
            cached = build(cfg);
            lastBuildMs = nowMs;
        }
        if (cached.isEmpty()) {
            HudLayout.placeholder(ctx, HudLayout.SCHEDULE, cfg.hudCorner, CLOCK, "표시할 일정이 없어요");
            return;
        }
        if (cfg.hudStyle == DdingMateConfig.HudStyle.CLASSIC) drawClassic(ctx, client.textRenderer, cfg);
        else drawPanel(ctx, client.textRenderer, cfg, cfg.hudStyle == DdingMateConfig.HudStyle.DARK);

    }

    private static int drawPanel(DrawContext ctx, TextRenderer tr, DdingMateConfig cfg, boolean dark) {
        int alpha = cfg.hudOpacity * 255 / 100;
        String header = "다음 일정";
        int labelW = 0, leftW = 0;
        for (Entry e : cached) {
            labelW = Math.max(labelW, Theme.width(tr, e.label()));
            leftW = Math.max(leftW, Theme.boldWidth(tr, e.left()));
        }
        int rowH = 12;
        int boxW = Math.max(12 + labelW + 10 + leftW, 18 + Theme.boldWidth(tr, header)) + 10;
        int boxH = HEAD_H + cached.size() * rowH + 5;
        int[] o = HudLayout.place(ctx, HudLayout.SCHEDULE, cfg.hudCorner, boxW, boxH);
        int x = o[0], y = o[1];

        panel(ctx, x, y, boxW, boxH, alpha, dark);
        header(ctx, tr, x, y, boxW, header, CLOCK, dark, alpha);
        int ty = y + HEAD_H + 2;
        int text = Theme.text(dark);
        for (int i = 0; i < cached.size(); i++) {
            Entry e = cached.get(i);
            // 제작 줄: 다 됨 초록, 진행 중 파랑(곧 끝나도 빨갛게 재촉하지 않는다), 시간 모름 흐리게
            int color = e.color() == Theme.GREEN ? Theme.green(dark) : e.color() == Theme.TEXT_SOFT ? Theme.soft(dark)
                    : e.color() == Theme.BLUE ? Theme.blue(dark)
                    : e.color() != 0 ? Theme.gold(dark)
                    : e.minutes() < 10 ? Theme.red(dark) : e.minutes() < 60 ? Theme.gold(dark) : Theme.blue(dark);
            ctx.fill(x + 6, ty + 3, x + 9, ty + 6, color);
            Theme.textShadow(ctx, tr, e.label(), x + 13, ty + 1, text, dark);
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

    private static int drawClassic(DrawContext ctx, TextRenderer tr, DdingMateConfig cfg) {
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
        int[] o = HudLayout.place(ctx, HudLayout.SCHEDULE, cfg.hudCorner, boxW, boxH);
        int x = o[0], y = o[1];
        ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        for (int i = 0; i < lines.size(); i++) {
            long m = cached.get(i).minutes();
            int color = cached.get(i).color() != 0 ? 0xFFFFD27A : m < 10 ? 0xFFFF6B6B : m < 60 ? 0xFFFFE066 : 0xFFFFFFFF;
            ctx.drawTextWithShadow(tr, lines.get(i), x + pad, y + pad + i * lineH, color);
        }
        return boxH;
    }

    /** 일정 HUD 에 보여 줄 제작·항해 줄 수 */
    static final int MAX_CRAFT_ROWS = 6;

    private static List<Entry> build(DdingMateConfig cfg) {
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
            // 서버 기록을 오늘 한 번도 못 봤으면 틀릴 수 있는 추정값 대신 확인 방법을 보여 준다
            if (synced) out.add(new Entry("오늘 접속 " + hm(played), left, Long.MAX_VALUE, Theme.GOLD));
            else out.add(new Entry("오늘 접속 시간", "/접속시간 열면 표시", Long.MAX_VALUE, Theme.GOLD));
        }
        // 서버가 예고한 지금 채널 재부팅: 가장 급하니 맨 위에
        long reboot = kr.ddingmate.game.ServerHud.rebootAt();
        if (reboot > 0) {
            long sec = Math.max(0, (reboot - System.currentTimeMillis()) / 1000);
            kr.ddingmate.game.ServerHud.Area a = kr.ddingmate.game.ServerHud.area();
            String where = a == null ? "지금 채널" : a.label + " " + kr.ddingmate.game.ServerHud.channel() + "채널";
            out.add(0, new Entry(where + " 재부팅", sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초", sec / 60, 0));
        }
        // 제작 중인 아이템: 완료가 가까운 순서로
        if (cfg.craft.enabled && cfg.craft.showInHud) {
            long nowMs = System.currentTimeMillis();
            // 같은 아이템·개수를 여러 번 넣었으면 한 줄로: "조약돌 뭉치 ×7 [3]" · 마지막 것이 끝날 때까지
            // 너무 많으면 HUD 가 화면을 덮으므로 위에서 몇 줄만, 나머지는 개수로 알려 준다(전체는 /띵메이트 홈)
            List<kr.ddingmate.craft.CraftTimers.Group> groups = kr.ddingmate.craft.CraftTimers.groups();
            int more = Math.max(0, groups.size() - MAX_CRAFT_ROWS);
            for (kr.ddingmate.craft.CraftTimers.Group g : groups.subList(0, groups.size() - more)) {
                long mins = g.unknown() ? Long.MAX_VALUE - 1 : Math.max(0, (g.endsAt() - nowMs) / 60000);
                out.add(new Entry(g.label(), kr.ddingmate.craft.CraftTimers.remainingText(g, nowMs),
                        mins, g.done() ? Theme.GREEN : g.unknown() ? Theme.TEXT_SOFT : Theme.BLUE));
            }
            if (more > 0) out.add(new Entry("그 밖의 제작 " + more + "개", "/띵메이트 홈", Long.MAX_VALUE - 2, Theme.TEXT_SOFT));
        }
        for (ScheduleEngine.Occurrence o : ScheduleEngine.upcoming(DdingMate.rules(), cfg::isEnabled, now, cfg.hudLines)) {
            Duration left = Duration.between(now, o.time());
            out.add(new Entry(o.rule().label(), ScheduleEngine.formatRemaining(left), left.toMinutes(), 0));
        }
        return out;
    }

    /** "1시간 5분", "42분" */
    public static String hm(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "시간 " + m + "분" : Math.max(m, seconds > 0 ? 1 : 0) + "분";
    }

    /** F1(HUD 숨기기)·F3 화면이거나 월드 밖이면 그리지 않는다. */
    public static boolean hidden(MinecraftClient client) {
        return client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud();
    }
}
