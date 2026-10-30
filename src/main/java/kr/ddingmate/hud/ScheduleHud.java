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
        long nowMs = kr.ddingmate.util.Clock.now();
        // 남은 초가 1초 간격으로 고르게 줄어들도록(갱신 간격이 1초면 숫자가 두 번 보이거나 건너뛴다) 자주 다시 만든다
        if (nowMs - lastBuildMs >= 200) {
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
        boxW = HudLayout.widen(HudLayout.SCHEDULE, boxW); // 편집 화면에서 가로로 늘린 너비
        // HUD 친구를 골랐으면 일정 판 위에 친구 자리를 같이 잡는다(화면 맨 위에 붙은 판이면 친구가 앉을 곳이 없어서)
        int lift = HudMascot.reserve(ctx);
        int[] o = HudLayout.place(ctx, HudLayout.SCHEDULE, cfg.hudCorner, boxW, boxH + lift);
        int x = o[0], y = o[1] + lift;

        HudMascot.prefer();
        panel(ctx, x, y, boxW, boxH, alpha, dark);
        header(ctx, tr, x, y, boxW, header, CLOCK, dark, alpha);
        int ty = y + HEAD_H + 2;
        int text = Theme.text(dark);
        for (int i = 0; i < cached.size(); i++) {
            Entry e = cached.get(i);
            // 제작 줄: 다 됨 초록, 진행 중 파랑(곧 끝나도 빨갛게 재촉하지 않는다), 시간 모름 흐리게
            int color = e.color() == Theme.GREEN ? Theme.green(dark) : e.color() == Theme.TEXT_SOFT ? Theme.soft(dark)
                    : e.color() == Theme.RED ? Theme.red(dark)
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
        HudMascot.record(ctx, x, y, w, h); // HUD 친구가 기댈 판 후보
    }

    /** HUD 판 위쪽 제목 줄: 작은 아이콘 + 굵은 제목 + 파란 밑줄 */
    static void header(DrawContext ctx, TextRenderer tr, int x, int y, int boxW, String title, ItemStack icon,
                       boolean dark, int alpha) {
        kr.ddingmate.ui.HudTheme t = kr.ddingmate.ui.HudTheme.current();
        boolean themed = (t.style != DdingMateConfig.HudStyle.LIGHT) == dark;
        // NPC 제목 줄(세트에서 고른 그림) 또는 띠 없이 글자만
        kr.ddingmate.ui.HudSkins.Skin head = kr.ddingmate.ui.HudSkins.header();
        boolean plain = kr.ddingmate.ui.HudSkins.plainHeader();
        if (head != null || plain || kr.ddingmate.ui.HudSkins.panel() != null) {
            if (head != null) kr.ddingmate.ui.HudSkins.drawHeader(ctx, head, x, y, boxW, alpha);
            int color = head != null ? head.title() : Theme.text(dark);
            boolean shadow = head != null ? head.titleShadow() : dark;
            Theme.item(ctx, icon, x + 4, y + 2, 10);
            Theme.boldText(ctx, tr, title, x + 16, y + 3, color, shadow);
            if (head == null) ctx.fill(x + 3, y + HEAD_H - 1, x + boxW - 3, y + HEAD_H, Theme.withAlpha(Theme.soft(dark), 140));
            return;
        }
        if (!dark) {
            if (themed && t.headMid != 0) {
                // 테마 제목 줄: 가운데 → 가장자리 색으로
                int hw = boxW - 4;
                for (int i = 0; i < hw; i++) {
                    double k = Math.abs((i + 0.5) / hw - 0.5) * 2;
                    ctx.fill(x + 2 + i, y + 2, x + 3 + i, y + HEAD_H - 1, Theme.lerp(t.headMid, t.headEdge, k));
                }
            } else Theme.skyHeader(ctx, x + 2, y + 2, boxW - 4, HEAD_H - 3);
        }
        Theme.item(ctx, icon, x + 4, y + 2, 10);
        Theme.boldText(ctx, tr, title, x + 16, y + 3, themed ? t.title : dark ? Theme.WHITE : Theme.INK, dark);
        if (dark) ctx.fill(x + 3, y + HEAD_H - 1, x + boxW - 3, y + HEAD_H,
                Theme.withAlpha(themed && t.accent != 0 ? t.accent : Theme.BLUE, Math.max(alpha, 200)));
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
        boxW = HudLayout.widen(HudLayout.SCHEDULE, boxW); // 편집 화면에서 가로로 늘린 너비
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

    /** 분류를 켰으면 "[채광] 강화 횃불", 분류가 없거나 껐으면 이름 그대로 */
    static String tag(DdingMateConfig cfg, String category, String label) {
        return cfg.hudCategory && category != null && !category.isEmpty() ? "[" + category + "] " + label : label;
    }

    /** 시험용: 지금 일정 HUD 줄 이름들 */
    public static List<String> debugLabels() {
        return cached.stream().map(Entry::label).toList();
    }

    /** 시험용: 지금 설정으로 줄을 다시 만든다 */
    public static List<String> debugBuild() {
        cached = build(DdingMate.config());
        return debugLabels();
    }

    private static List<Entry> build(DdingMateConfig cfg) {
        ZonedDateTime now = kr.ddingmate.util.Clock.zoned(ScheduleEngine.KST);
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
            long sec = Math.max(0, (reboot - kr.ddingmate.util.Clock.now()) / 1000);
            kr.ddingmate.game.ServerHud.Area a = kr.ddingmate.game.ServerHud.area();
            String where = a == null ? "지금 채널" : a.label + " " + kr.ddingmate.game.ServerHud.channel() + "채널";
            out.add(0, new Entry(where + " 재시작", sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초", sec / 60, 0));
        }
        // 오션오더: 5분 안에 끝나야 해서 맨 위에
        long nowMs0 = kr.ddingmate.util.Clock.now();
        if (cfg.expert.oceanOrder && kr.ddingmate.expert.OceanOrder.active(nowMs0)) {
            out.add(0, new Entry(tag(cfg, "해양", kr.ddingmate.expert.OceanOrder.label()), kr.ddingmate.expert.OceanOrder.remainingText(nowMs0),
                    0, Theme.GOLD));
        }
        // 오션오더 카운터: 진행 중이 아니어도 오늘 받은 적이 있으면 "오늘 n/10"(2026-10-24 "오션오더 카운터 HUD 가 안 보여")
        else if (cfg.expert.oceanOrder && kr.ddingmate.expert.OceanOrder.showIdle(nowMs0)) {
            out.add(new Entry(tag(cfg, "해양", kr.ddingmate.expert.OceanOrder.idleLabel()), kr.ddingmate.expert.OceanOrder.idleStatus(nowMs0),
                    Long.MAX_VALUE - 4, Theme.TEXT_SOFT));
        }
        // 바닥 아이템 청소(10분마다, 서버 [청소] 안내로 맞춤)
        long clean = cfg.cleanupTimer && kr.ddingmate.game.ServerHud.cleansHere() ? kr.ddingmate.game.ServerHud.cleanAt() : 0;
        if (clean > 0) {
            long sec = Math.max(0, (clean - nowMs0 + 999) / 1000);
            out.add(new Entry("바닥 아이템 청소", sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초",
                    sec / 60, sec <= 60 ? Theme.GOLD : Theme.BLUE));
        }
        // 펫 전부 소환 해제(서버 [초기화] 안내가 온 때만)
        long petReset = cfg.petResetTimer ? kr.ddingmate.game.ServerHud.petResetAt() : 0;
        if (petReset > 0) {
            long sec = Math.max(0, (petReset - nowMs0 + 999) / 1000);
            out.add(new Entry("펫 전부 소환 해제", sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초", sec / 60, sec <= 60 ? Theme.GOLD : Theme.BLUE));
        }
        // 단축키로 켠 아일랜드 작물 15분 타이머
        if (kr.ddingmate.expert.CropTimer.running()) {
            long left = kr.ddingmate.expert.CropTimer.remaining(nowMs0);
            out.add(new Entry(tag(cfg, "농사", "아일랜드 작물"), kr.ddingmate.expert.CropTimer.remainingText(nowMs0), left / 60000,
                    kr.ddingmate.expert.CropTimer.paused() ? Theme.TEXT_SOFT : left <= 60_000 ? Theme.GOLD : Theme.BLUE));
        } else if (kr.ddingmate.expert.CropTimer.ripe()) {
            out.add(new Entry(tag(cfg, "농사", "아일랜드 작물"), "다 자람!", 0, Theme.GREEN));
        }
        // 마을 경작지 아일랜드 작물: 종류별로 다 자랄 때까지
        if (cfg.expert.islandCrops) {
            for (kr.ddingmate.expert.IslandCrops.Summary s : kr.ddingmate.expert.IslandCrops.summaries(nowMs0).values()) {
                boolean done = s.ready() == s.count();
                out.add(new Entry(tag(cfg, "농사", kr.ddingmate.expert.IslandCrops.label(s)), kr.ddingmate.expert.IslandCrops.status(s, nowMs0),
                        done ? 0 : Math.max(0, (s.allReadyAt() - nowMs0) / 60000), done ? Theme.GREEN : Theme.BLUE));
            }
        }
        // 광석 골렘: 다시 나올 수 있을 때까지(쿨타임 중에만)
        if (kr.ddingmate.expert.OreGolem.remaining(nowMs0) > 0) {
            out.add(new Entry(tag(cfg, "채광", "광석 골렘 쿨타임"), kr.ddingmate.expert.OreGolem.remainingText(nowMs0),
                    kr.ddingmate.expert.OreGolem.remaining(nowMs0) / 60000, Theme.BLUE));
        }
        // 해양 탐사: 출항 준비 중인 탐사선(남은 납품 칸). 출항 중인 탐사선은 아래 제작 줄에 남은 시간으로 보인다
        if (cfg.expert.exploration) {
            for (kr.ddingmate.expert.Exploration.Prep p : kr.ddingmate.expert.Exploration.preps()) {
                int left = p.slotsLeft();
                out.add(new Entry(tag(cfg, "해양", p.grade() + "급 탐사선 준비"), left > 0 ? "재료 " + left + "칸 남음" : "출항할 수 있어요",
                        Long.MAX_VALUE - 3, left > 0 ? Theme.TEXT_SOFT : Theme.GREEN));
            }
        }
        // 반려어: 밥 줄 때까지
        kr.ddingmate.expert.PetFish.Fish fish = cfg.expert.petFish ? kr.ddingmate.expert.PetFish.current() : null;
        if (fish != null) {
            int lv = kr.ddingmate.expert.PetFish.level(fish, nowMs0);
            out.add(new Entry(tag(cfg, "해양", kr.ddingmate.expert.PetFish.label(fish)), kr.ddingmate.expert.PetFish.status(fish, nowMs0),
                    lv >= 2 ? 0 : Long.MAX_VALUE - 3, lv >= 3 ? Theme.RED : lv == 2 ? Theme.GOLD : lv == 1 ? Theme.GREEN : Theme.BLUE));
        }
        // 제작 중인 아이템: 완료가 가까운 순서로
        if (cfg.craft.enabled && cfg.craft.showInHud) {
            long nowMs = kr.ddingmate.util.Clock.now();
            // 같은 아이템·개수를 여러 번 넣었으면 한 줄로: "조약돌 뭉치 ×7 [3]" · 마지막 것이 끝날 때까지
            // 너무 많으면 HUD 가 화면을 덮으므로 위에서 몇 줄만, 나머지는 개수로 알려 준다(전체는 /띵메이트 홈)
            List<kr.ddingmate.craft.CraftTimers.Group> groups = kr.ddingmate.craft.CraftTimers.groups();
            // 보일 일정 수가 '제한 없음'이면 제작도 모두 보인다(문의 96ad0fb8: 제한 없음인데 "그 밖의 제작"이 뜸)
            int more = cfg.hudLines >= DdingMateConfig.HUD_LINES_ALL ? 0 : Math.max(0, groups.size() - MAX_CRAFT_ROWS);
            for (kr.ddingmate.craft.CraftTimers.Group g : groups.subList(0, groups.size() - more)) {
                long mins = g.unknown() ? Long.MAX_VALUE - 1 : Math.max(0, (g.endsAt() - nowMs) / 60000);
                out.add(new Entry(tag(cfg, kr.ddingmate.craft.CraftTimers.category(g.name()), kr.ddingmate.craft.CraftTimers.displayLabel(g, nowMs)),
                        kr.ddingmate.craft.CraftTimers.remainingText(g, nowMs),
                        mins, g.unknown() || g.estimated() ? Theme.TEXT_SOFT : g.done() ? Theme.GREEN : Theme.BLUE));
            }
            if (more > 0) out.add(new Entry("그 밖의 제작 " + more + "개", "/띵메이트 홈", Long.MAX_VALUE - 2, Theme.TEXT_SOFT));
        }
        for (ScheduleEngine.Occurrence o : ScheduleEngine.upcoming(DdingMate.rules(), cfg::isEnabled, now, cfg.hudLines)) {
            // 같은 재시작을 서버 예고(초 단위)와 시간표로 두 줄 보이지 않게: 예고가 있으면 그 시각 근처 시간표 줄은 뺀다
            if (sameRestart(o, reboot)) continue;
            Duration left = Duration.between(now, o.time());
            out.add(new Entry(o.rule().label(), ScheduleEngine.formatRemaining(left), left.toMinutes(), 0));
        }
        return cfg.hudGroupCategory ? grouped(out) : out;
    }

    private static final java.util.regex.Pattern CATEGORY = java.util.regex.Pattern.compile("^\\[([^\\]]+)]\\s");

    /**
     * 분류별로 묶기(2026-10-29 사용자 요청): "[채광] … [강화] … [채광] … [해양]" → 같은 분류끼리 모은다.
     * 묶음 순서는 묶음 안에서 가장 빨리 끝나는 줄 순서, 묶음 안은 남은 시간 순. 분류 없는 줄은 제자리
     */
    static List<Entry> grouped(List<Entry> in) {
        List<Integer> slots = new ArrayList<>();
        java.util.Map<String, List<Entry>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < in.size(); i++) {
            java.util.regex.Matcher m = CATEGORY.matcher(in.get(i).label());
            if (!m.find()) continue;
            slots.add(i);
            groups.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(in.get(i));
        }
        if (groups.size() < 2) return in;
        List<List<Entry>> order = new ArrayList<>(groups.values());
        for (List<Entry> g : order) g.sort(java.util.Comparator.comparingLong(Entry::minutes));
        order.sort(java.util.Comparator.comparingLong(g -> g.get(0).minutes()));
        List<Entry> flat = new ArrayList<>();
        for (List<Entry> g : order) flat.addAll(g);
        List<Entry> out = new ArrayList<>(in);
        for (int i = 0; i < slots.size(); i++) out.set(slots.get(i), flat.get(i));
        return out;
    }

    /** 시험용: (이름, 남은 분) 줄들을 분류별로 묶은 이름 순서 */
    public static List<String> debugGrouped(List<String> labels, List<Long> minutes) {
        List<Entry> in = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) in.add(new Entry(labels.get(i), "", minutes.get(i), 0));
        return grouped(in).stream().map(Entry::label).toList();
    }

    /** 시간표의 마을 재시작이 서버가 예고한 지금 채널 재부팅과 같은 것인지(3분 안) */
    public static boolean sameRestart(ScheduleEngine.Occurrence o, long rebootAt) {
        if (rebootAt <= 0 || !o.rule().id().equals("village_reboot")) return false;
        return Math.abs(o.time().toInstant().toEpochMilli() - rebootAt) <= 3 * 60_000L;
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
