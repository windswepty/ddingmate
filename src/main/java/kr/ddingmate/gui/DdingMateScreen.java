package kr.ddingmate.gui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.gear.DurabilityTracker;
import kr.ddingmate.hud.ScheduleHud;
import kr.ddingmate.hud.CounterHud;
import kr.ddingmate.notify.AlertLog;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.playtime.PlaytimeTracker;
import kr.ddingmate.todo.TodoList;
import kr.ddingmate.schedule.EventRule;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.ui.Theme;
import kr.ddingmate.ui.Widgets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * /띵메이트, Mod Menu 의 설정 버튼, 단축키로 여는 띵메이트 창. 모든 기능을 여기서 보고 조작한다.
 * 띵타이쿤 설정 창처럼 하늘색 머리 띠·파란 분류 탭·회청색 줄·ON/OFF 스위치로 구성한다.
 * 바꾼 값은 바로 적용되고 닫을 때 저장된다.
 */
public class DdingMateScreen extends Screen {

    private static final int GAP = 4;
    private static final int UNIT_W = 30;
    private static final int FIELD_W = 64;
    static final int HEADER_H = 26;
    private static final int TAB_ROWS = 2;

    enum Tab {
        HOME("홈", Items.RED_BED), NOTIFY("알림 방식", Items.BELL), EVENTS("일정", Items.CLOCK), CHANNELS("내 채널", Items.COMPASS),
        KEYWORDS("키워드", Items.NAME_TAG), COUNTER("재료", Items.CHEST), GEAR("장비", Items.ANVIL),
        TODO("할 일", Items.WRITABLE_BOOK), LOG("기록", Items.BOOK);

        final String title;
        final ItemStack icon;

        Tab(String title, net.minecraft.item.Item icon) {
            this.title = title;
            this.icon = new ItemStack(icon);
        }
    }

    /** 설정 화면에 보여 줄 일정 이름과 시각 안내. 순서대로 표시한다. */
    private static final Map<String, String> EVENT_NAMES = new LinkedHashMap<>();
    static {
        EVENT_NAMES.put("daily_reset", "일일 초기화 (매일 03:00)");
        EVENT_NAMES.put("midnight_reset", "자정 초기화 (매일 00:00)");
        EVENT_NAMES.put("fishing_contest", "낚시 대회 (10·22시)");
        EVENT_NAMES.put("invest_deadline", "마을 투자 마감 (수 02:59)");
        EVENT_NAMES.put("maintenance_start", "정기 점검 시작 (수 09:00)");
        EVENT_NAMES.put("maintenance_end", "정기 점검 종료 (수 15:00)");
        EVENT_NAMES.put("weekly_reset", "주간 초기화 (목 03:00)");
        EVENT_NAMES.put("hunter_affinity", "수렵꾼 호감도 (매월 1일)");
        EVENT_NAMES.put("village_reboot", "마을 채널 재시작");
    }

    private static Tab lastTab = Tab.HOME;

    private final Screen parent;
    private final DdingMateConfig cfg = DdingMate.config();
    private Tab tab = lastTab;
    private ConfigList list;
    /** 레시피 고르기 화면에 다녀와도 보던 위치를 유지한다. */
    private double savedScroll;
    private boolean confirmClearBags;
    private boolean confirmClearLog;
    /** 진행 중인 튜토리얼. 없으면 null */
    private Tutorial tutorial;
    private boolean tutorialOffered;
    private final java.util.Map<Tab, TabButton> tabButtons = new java.util.EnumMap<>(Tab.class);

    private int px, py, pw, ph;
    private int cx, cy, cw, ch;

    public DdingMateScreen(Screen parent) {
        super(Text.literal("띵메이트 설정"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!cfg.opened) {
            cfg.opened = true; // 이제부터 HUD 를 보여 준다
            cfg.save();
        }
        pw = Math.min(400, width - 16);
        ph = height - 12;
        px = (width - pw) / 2;
        py = 6;
        cx = px + 6;
        cy = py + HEADER_H + 5 + TAB_ROWS * 23 + 2;
        cw = pw - 12;
        ch = ph - (cy - py) - 32;

        // 탭은 4개씩 두 줄
        Tab[] tabs = Tab.values();
        int perRow = (tabs.length + TAB_ROWS - 1) / TAB_ROWS;
        int tabW = (cw - (perRow - 1) * 3) / perRow;
        for (int i = 0; i < tabs.length; i++) {
            TabButton b = new TabButton(tabs[i], tabW);
            b.setX(cx + (i % perRow) * (tabW + 3));
            b.setY(py + HEADER_H + 5 + (i / perRow) * 23);
            addDrawableChild(b);
            tabButtons.put(tabs[i], b);
        }
        if (tab == Tab.LOG) AlertLog.markRead();

        list = addDrawableChild(new ConfigList(client, cw - 4, ch - 4, cy + 2, 24));
        list.setX(cx + 2);
        buildRows();
        list.setScrollY(savedScroll);

        Widgets.Button test = new Widgets.Button(118, 20, Text.literal("테스트 알림"), false, b -> Notifier.test())
                .icon(new ItemStack(Items.BELL));
        test.setX(px + 8);
        test.setY(py + ph - 27);
        test.setTooltip(Tooltip.of(Text.literal("지금 설정대로 소리·팝업·채팅 알림을 한 번 보내요.")));
        addDrawableChild(test);
        Widgets.Button done = new Widgets.Button(118, 20, Text.literal("닫기"), true, b -> close());
        done.setX(px + pw - 126);
        done.setY(py + ph - 27);
        addDrawableChild(done);

        // 처음 열면 튜토리얼을 한 번 보여 준다
        if (!cfg.tutorialDone && !tutorialOffered) {
            tutorialOffered = true;
            client.send(this::startTutorial);
        }
    }

    // ---------------------------------------------------------------- 튜토리얼

    /** 튜토리얼 시작(홈의 '튜토리얼 다시 보기'에서도 부른다) */
    public void startTutorial() {
        tutorial = new Tutorial(this::switchTab, t -> {
            TabButton b = tabButtons.get(t);
            return b == null ? null : new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()};
        }, key -> list == null ? null : list.focus(key), this::endTutorial);
        tutorial.start();
    }

    private void endTutorial() {
        tutorial = null;
        cfg.tutorialDone = true;
        cfg.save();
        switchTab(Tab.HOME);
    }

    /** 시험용 */
    public boolean tutorialActive() {
        return tutorial != null;
    }

    public int tutorialStep() {
        return tutorial == null ? -1 : tutorial.index();
    }

    public int tabIndex() {
        return tab.ordinal();
    }

    /** 지금 튜토리얼이 강조하는 자리. 시험용 */
    public int[] tutorialHighlight() {
        return tutorial == null ? null : tutorial.highlight();
    }

    public int[] tutorialPanel() {
        return tutorial == null ? null : tutorial.panel();
    }

    /** 튜토리얼 단계 수. 시험용 */
    public static int tutorialSteps() {
        return Tutorial.STEPS.size();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (tutorial == null) {
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }
        // 튜토리얼 중에는 아래 창에 마우스가 닿지 않은 것처럼 그리고, 그 위에 안내를 덧그린다
        super.render(ctx, -1, -1, delta);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        tutorial.render(ctx, textRenderer, width, height, new int[]{px, py, pw, ph}, mouseX, mouseY, delta);
        ctx.getMatrices().pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tutorial != null) {
            tutorial.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return tutorial != null || super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return tutorial != null || super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tutorial != null) {
            tutorial.keyPressed(keyCode);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return tutorial != null || super.charTyped(chr, modifiers);
    }

    /** 탭 번호(0부터)로 바로 이동. 시험·단축 이동용 */
    public void openTab(int index) {
        Tab[] tabs = Tab.values();
        switchTab(tabs[Math.floorMod(index, tabs.length)]);
    }

    private void switchTab(Tab t) {
        if (t == tab) return;
        tab = t;
        lastTab = t;
        savedScroll = 0;
        clearAndInit();
    }

    private int rowWidth() {
        return cw - 24;
    }

    // ---------------------------------------------------------------- 탭별 내용

    private void buildRows() {
        switch (tab) {
            case HOME -> buildHome();
            case NOTIFY -> buildNotify();
            case EVENTS -> buildEvents();
            case CHANNELS -> buildChannels();
            case KEYWORDS -> buildKeywords();
            case COUNTER -> buildCounter();
            case GEAR -> buildGear();
            case TODO -> buildTodo();
            case LOG -> buildLog();
        }
    }

    private void buildNotify() {
        list.guide("알림을 어떻게 받을지 정해요. HUD 는 화면 구석에 늘 떠 있는 작은 정보 판이에요.");
        list.header("알림을 받을 방법");
        list.pair(tip(toggle("일정 HUD", cfg.hudEnabled, v -> cfg.hudEnabled = v), "다음 일정과 남은 시간을 화면 구석에 늘 보여 줘요"),
                tip(toggle("소리", cfg.soundEnabled, v -> cfg.soundEnabled = v), "알림이 올 때 '띵' 소리를 내요"));
        list.pair(tip(toggle("팝업 알림", cfg.toastEnabled, v -> cfg.toastEnabled = v), "화면 오른쪽 위에 잠깐 떴다 사라지는 알림 상자예요"),
                tip(toggle("채팅 알림 (내 화면만)", cfg.chatEnabled, v -> cfg.chatEnabled = v),
                        "채팅창에 [띵메이트] 로 시작하는 줄을 남겨요. 다른 사람에게는 보이지 않아요"));
        list.single(new Widgets.Slider(rowWidth(), cfg.soundVolume,
                v -> "소리 크기  " + Math.round(v * 100) + "%", v -> cfg.soundVolume = (float) v));

        list.header("HUD 모양");
        list.pair(hudButton(),
                new Widgets.Cycle<>(half(), "보일 일정 수", IntStream.rangeClosed(1, 10).boxed().toList(), cfg.hudLines,
                        n -> n + "개", v -> cfg.hudLines = v));
        list.pair(new Widgets.Cycle<>(half(), "HUD 스타일", List.of(DdingMateConfig.HudStyle.values()), cfg.hudStyle,
                        DdingMateScreen::styleName, v -> cfg.hudStyle = v),
                new Widgets.Cycle<>(half(), "HUD 진하기", List.of(30, 40, 50, 60, 70, 80, 85, 90, 100), nearest(cfg.hudOpacity),
                        v -> v + "%", v -> cfg.hudOpacity = v));
        Widgets.Cycle<Integer> size = new Widgets.Cycle<>(half(), "HUD 전체 크기", List.of(50, 60, 70, 80, 90, 100),
                nearestOf(cfg.hudScale, 50, 60, 70, 80, 90, 100), v -> v + "%", v -> cfg.hudScale = v);
        size.setTooltip(Tooltip.of(Text.literal("일정·할 일·재료·내구도 HUD 를 한꺼번에 줄여요. HUD 마다 따로 바꾸려면 아래 'HUD 위치·크기 설정'을 쓰세요. GUI 배율이 작으면 글자가 흐려질 수 있어요.")));
        Widgets.Toggle avoid = toggle("서버 HUD 피하기", cfg.avoidServerHud, v -> cfg.avoidServerHud = v);
        avoid.setTooltip(Tooltip.of(Text.literal("구석 HUD 를 서버의 위쪽 재화 바·날짜/채널 줄 아래, 왼쪽 아래 채팅창 위에 놓아요.")));
        list.pair(size, avoid);
        list.pair(toggle("띵타이쿤 글꼴(갈무리)", cfg.galmuriFont, v -> cfg.galmuriFont = v), null);
        Widgets.Button move = new Widgets.Button(rowWidth(), 20, Text.literal("HUD 위치·크기 설정"), true, b -> {
            savedScroll = list.getScrollY();
            client.setScreen(new HudEditorScreen(this));
        });
        boolean inGame = client != null && client.player != null;
        move.active = inGame;
        move.setTooltip(Tooltip.of(Text.literal(inGame
                ? "게임 화면에서 HUD 를 끌어 옮기고, 휠이나 오른쪽 아래 모서리로 HUD 마다 크기를 바꿔요. 옮긴 HUD 는 위의 '위치' 설정 대신 그 자리에 보여요."
                : "게임에 들어가 있을 때 쓸 수 있어요.")));
        list.single(move);
        list.note("HUD 남은 시간 색: 빨강 = 10분 안, 노랑 = 1시간 안, 파랑 = 그보다 많이 남음");
        list.note("HUD 는 화면 구석에만 그려요. 화면 위 가운데(서버 HUD·NPC 말풍선 자리)는 비워 둬요.");
        list.header("처음 쓰는 사람을 위한 안내");
        list.pair(tip(toggle("접속할 때 사용법 안내", cfg.joinTip, v -> cfg.joinTip = v),
                "게임을 켜고 처음 접속할 때 채팅에 '/띵메이트 로 열 수 있어요' 를 한 번 남겨요"), null);
    }

    private void buildEvents() {
        list.guide("띵타이쿤 서버 일정이에요. 끄면 HUD·알림에서 빠져요. 오른쪽 칸에 몇 분 전에 알릴지 적어요(예: 10,3).");
        list.header("일정별 알림 · 0 = 시작할 때 알림");
        Map<String, EventRule> rules = ScheduleEngine.byId(
                ScheduleEngine.buildRules(new ScheduleEngine.Channels(1, 1, 1, 1, 1)));
        for (Map.Entry<String, String> e : EVENT_NAMES.entrySet()) {
            DdingMateConfig.EventSetting s = cfg.events.get(e.getKey());
            EventRule rule = rules.get(e.getKey());
            if (s == null || rule == null) continue;
            Widgets.Toggle t = toggle(e.getValue(), s.enabled, v -> s.enabled = v);
            t.setTooltip(Tooltip.of(Text.literal(rule.detail())));
            t.detail(nextText(e.getKey()));
            list.withUnit(t, leadsField(s), "분 전");
        }
    }

    /** 일정의 다음 시각까지 남은 시간. 채널 번호를 정하지 않은 재시작 일정은 "채널 끔" */
    private static String nextText(String ruleId) {
        EventRule live = ScheduleEngine.byId(DdingMate.rules()).get(ruleId);
        if (live == null) return "채널 끔";
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        ZonedDateTime next = ScheduleEngine.next(live, now);
        return next == null ? "" : ScheduleEngine.formatRemaining(Duration.between(now, next)) + " 후";
    }

    // ---------------------------------------------------------------- 홈

    private void buildHome() {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        if (!cfg.guideSeen) {
            list.header("처음이세요? · 이것만 해 보세요");
            list.single(new InfoRow(new ItemStack(Items.CHEST), "① 만들 레시피 핀하기", "재료 탭", Theme.BLUE_MID, Tab.COUNTER));
            list.single(new InfoRow(new ItemStack(Items.WRITABLE_BOOK), "② 오늘 할 일 체크하기", "할 일 탭", Theme.BLUE_MID, Tab.TODO));
            list.single(new InfoRow(new ItemStack(Items.ANVIL), "③ 도구 내구도 지켜보기", "장비 탭", Theme.BLUE_MID, Tab.GEAR));
            list.single(new InfoRow(new ItemStack(Items.BELL), "④ HUD 자리·크기 바꾸기", "알림 방식 탭", Theme.BLUE_MID, Tab.NOTIFY));
            list.note("일정 알림은 이미 켜져 있어요. 각 탭 맨 위에 그 탭에서 할 수 있는 일이 적혀 있어요.");
            list.single(new Widgets.Button(rowWidth(), 20, Text.literal("알겠어요, 안내 숨기기"), true, b -> {
                cfg.guideSeen = true;
                rebuild();
            }));
        }
        list.header("다음 일정");
        for (ScheduleEngine.Occurrence o : ScheduleEngine.upcoming(DdingMate.rules(), cfg::isEnabled, now, 3)) {
            list.single(new InfoRow(new ItemStack(Items.CLOCK), o.rule().label(),
                    ScheduleEngine.formatRemaining(Duration.between(now, o.time())) + " 후", Theme.BLUE_MID, Tab.EVENTS));
        }

        list.header("오늘");
        long sec = PlaytimeTracker.seconds();
        if (sec >= 0) {
            List<Integer> unclaimed = PlaytimeTracker.unclaimedRewards();
            int next = PlaytimeTracker.nextRewardHour(sec);
            String state = unclaimed != null && !unclaimed.isEmpty() ? "받을 보상 " + unclaimed.size() + "개"
                    : next == 0 ? "보상 구간 다 채움" : "다음 보상까지 " + ScheduleHud.hm(next * 3600L - sec);
            boolean synced = PlaytimeTracker.syncedAt() > 0;
            String label = synced ? "오늘 접속 " + ScheduleHud.hm(sec) : "오늘 접속 시간";
            if (!synced) state = "/접속시간 창을 열면 맞춰져요";
            list.single(new InfoRow(new ItemStack(Items.NETHER_STAR), label, state,
                    unclaimed != null && !unclaimed.isEmpty() ? Theme.GOLD : Theme.TEXT_SOFT, Tab.TODO));
        }
        long done = cfg.todo.items.stream().filter(t -> TodoList.isDone(t, now)).count();
        list.single(new InfoRow(new ItemStack(Items.WRITABLE_BOOK), "오늘 할 일",
                done + "/" + cfg.todo.items.size() + " 완료", done == cfg.todo.items.size() ? Theme.GREEN : Theme.TEXT_SOFT, Tab.TODO));
        int unread = AlertLog.unread();
        list.single(new InfoRow(new ItemStack(Items.BOOK), "알림 기록",
                unread > 0 ? "새 알림 " + unread + "개" : "최근 " + AlertLog.entries().size() + "개",
                unread > 0 ? Theme.BLUE_MID : Theme.TEXT_SOFT, Tab.LOG));

        list.header("재료");
        List<MaterialCounter.PinView> pins = MaterialCounter.views();
        if (pins.isEmpty()) {
            list.single(new InfoRow(new ItemStack(Items.CHEST), "재료 카운터 · 핀이 비어 있어요", "레시피 고르기", Theme.BLUE_MID, Tab.COUNTER));
        }
        for (MaterialCounter.PinView v : pins) {
            ItemStack icon = IconCache.forRecipe(v.recipe()); // 만드는 아이템 모양
            list.single(new InfoRow(icon == null ? new ItemStack(Items.CHEST) : icon,
                    MaterialCounter.title(v.pin(), v.recipe()),
                    v.done() ? "준비 완료" : "재료 " + v.doneCount() + "/" + v.lines().size(),
                    v.done() ? Theme.GREEN : Theme.TEXT_SOFT, Tab.COUNTER));
        }

        list.header("장비");
        List<DurabilityTracker.Row> gear = DurabilityTracker.rows();
        if (gear.isEmpty()) {
            list.single(new InfoRow(new ItemStack(Items.ANVIL), "장비 내구도 · 지켜보는 장비 없음", "장비 고르기", Theme.BLUE_MID, Tab.GEAR));
        }
        for (DurabilityTracker.Row r : gear) {
            DurabilityTracker.Durability d = r.durability();
            ItemStack seen = r.stack() != null ? r.stack() : kr.ddingmate.counter.IconCache.forKey(r.item().key);
            list.single(new InfoRow(seen == null ? new ItemStack(Items.ANVIL) : seen, r.item().name,
                    d == null ? "인벤토리에 없음" : d.left() + "/" + d.max(),
                    d == null ? Theme.TEXT_SOFT : d.ratio() > 0.5 ? Theme.GREEN : d.ratio() > 0.2 ? Theme.GOLD : Theme.RED, Tab.GEAR));
        }

        List<kr.ddingmate.craft.CraftTimers.Group> crafting = cfg.craft.enabled ? kr.ddingmate.craft.CraftTimers.groups() : List.of();
        if (!crafting.isEmpty()) {
            list.header("제작 중");
            long nowMs = System.currentTimeMillis();
            for (kr.ddingmate.craft.CraftTimers.Group j : crafting) {
                ItemStack icon = kr.ddingmate.counter.IconCache.forMaterial(j.name());
                list.single(new InfoRow(icon == null ? new ItemStack(Items.CRAFTING_TABLE) : icon,
                        j.label(), kr.ddingmate.craft.CraftTimers.remainingText(j, nowMs),
                        j.done() ? Theme.GREEN : j.unknown() ? Theme.TEXT_SOFT : Theme.BLUE, Tab.COUNTER));
            }
        }

        list.header("도움말");
        Widgets.Button replay = new Widgets.Button(rowWidth(), 20, Text.literal("튜토리얼 다시 보기"), false, b -> startTutorial())
                .icon(new ItemStack(Items.BOOK));
        replay.setTooltip(Tooltip.of(Text.literal("기능을 하나씩 소개하는 안내를 처음부터 다시 봐요")));
        list.single(replay);
    }

    private int homeTicks;

    @Override
    public void tick() {
        super.tick();
        // 홈은 남은 시간·개수가 계속 바뀌므로 1초마다 다시 그린다
        if (tab == Tab.HOME && ++homeTicks % 20 == 0) rebuild();
    }

    private void buildChannels() {
        list.guide("내가 있는 채널이 재시작되기 전에 미리 알려 줘요. 화면 오른쪽 위 채널 표시(예: 마을30)를 읽어 알아서 맞춰요.");
        list.header("채널 자동 인식");
        list.single(tip(toggle("지금 있는 채널 자동 인식", cfg.autoChannel, v -> {
            cfg.autoChannel = v;
            rebuild();
        }), "채널을 옮기면 그 지역의 내 채널을 알아서 바꿔요. 끄면 아래에서 직접 골라요"));
        kr.ddingmate.game.ServerHud.Area here = kr.ddingmate.game.ServerHud.area();
        list.note(here == null
                ? "지금 있는 곳: 아직 못 읽었어요 (스폰에 들어가면 화면 오른쪽 위 표시를 읽어요)"
                : "지금 있는 곳: " + here.label + " " + kr.ddingmate.game.ServerHud.channel() + "채널"
                        + (cfg.autoChannel ? " · 이 지역의 내 채널로 맞췄어요" : ""));
        list.header(cfg.autoChannel ? "지역별 내 채널 · 마지막으로 있던 채널" : "지역별 내 채널");
        list.pair(channel("마을", 35, cfg.villageChannel, v -> cfg.villageChannel = v), null);
        list.note(cfg.autoChannel
                ? "가 본 지역은 알아서 채워져요. 직접 바꿔도 되고, '끔'이면 그 지역 재시작은 알리지 않아요(그 지역에 다시 가면 다시 켜져요)."
                : "'끔'이면 그 채널 재시작은 알리지 않아요. 클릭하면 다음 번호, 오른쪽 클릭이나 Shift+클릭은 이전 번호예요.");
    }

    private void buildKeywords() {
        list.guide("채팅에 정해 둔 단어가 뜨거나 누가 내 닉네임을 부르면 소리와 팝업으로 알려 줘요.");
        list.header("키워드 알림");
        list.pair(tip(toggle("키워드 알림", cfg.keywords.enabled, v -> cfg.keywords.enabled = v),
                        "아래 칸에 적은 단어가 채팅에 뜨면 알려 줘요"),
                tip(toggle("내 닉네임 언급", cfg.keywords.includeOwnName, v -> cfg.keywords.includeOwnName = v),
                        "다른 사람이 채팅에서 내 닉네임을 부르면 알려 줘요"));
        Widgets.Field words = new Widgets.Field(rowWidth(), Text.literal("키워드"));
        words.setMaxLength(500);
        words.setText(String.join(", ", cfg.keywords.words));
        words.setTooltip(Tooltip.of(Text.literal("쉼표로 구분해서 적어요. 예: 오션오더, 풍선, 경매")));
        words.setChangedListener(text -> cfg.keywords.words = splitList(text));
        list.labeled("키워드", words);
        list.header("이렇게 울려요");
        list.note("채팅에 키워드가 들어간 메시지가 뜨면 소리와 함께 팝업 \"채팅에 '오션오더'\" 가 뜨고, 아래에 그 메시지가 그대로 보여요.");
        list.note("다른 사람이 채팅으로 내 닉네임을 부르면 \"누가 내 닉네임을 불렀어요\" 가 떠요. 내 이름이 들어간 시스템 공지에는 울리지 않아요.");
        list.note("내가 쓴 채팅에는 반응하지 않고, 같은 단어는 " + cfg.keywords.cooldownSeconds + "초 안에 다시 울리지 않아요. 영어는 대소문자를 가리지 않아요.");
    }

    private void buildCounter() {
        DdingMateConfig.Counter cc = cfg.counter;
        list.guide("만들 레시피를 고르고 개수를 정하면, 모은 재료를 HUD 에 계속 세어 보여 줘요.");
        list.header("핀 레시피 · 최대 3개");
        for (int i = 0; i < cc.pins.size(); i++) {
            int index = i;
            DdingMateConfig.Pin pin = cc.pins.get(i);
            PinButton pick = new PinButton(index, pin, b -> {
                savedScroll = list.getScrollY();
                client.setScreen(new RecipePickerScreen(this, index));
            });
            list.single(pick);
            RecipeBook.Recipe r = RecipeBook.get(pin.recipe);
            if (r != null) {
                // 핀 아래 줄: 만들 개수 [−] 숫자 [+] [+10] [+64] (+ 번 만들기/개 얻기)
                CountBar bar = new CountBar(pin.crafts, pin.byOutput, r.output > 1,
                        v -> pin.crafts = v, v -> pin.byOutput = v);
                list.labeledRow("만들 개수", bar.widgets());
            }
        }
        list.header("표시와 알림");
        list.pair(tip(toggle("카운터 HUD", cc.enabled, v -> cc.enabled = v), "핀한 레시피의 재료 현황을 화면 구석에 보여 줘요"),
                tip(toggle("가방도 합산", cc.includeBags, v -> cc.includeBags = v), "한 번 열어 본 모험 가방 안의 재료도 함께 세요"));
        list.pair(hudButton(),
                toggle("다 모이면 알림", cc.notifyComplete, v -> cc.notifyComplete = v));
        Widgets.Toggle expand = toggle("원재료까지 펼치기", cc.expandSubMaterials, v -> cc.expandSubMaterials = v);
        expand.setTooltip(Tooltip.of(Text.literal("모자란 재료가 다른 레시피로 만드는 것이면(예: 조약돌 뭉치 = 조약돌 64개) 모자란 만큼 필요한 원재료를 아래에 보여 줘요.")));
        Widgets.Toggle storage = toggle("아이템 저장소도 합산", cc.includeStorage, v -> cc.includeStorage = v);
        storage.setTooltip(Tooltip.of(Text.literal("아이템 저장소를 열었을 때 슬롯별 보관 개수를 기억해 합산해요. 주운 아이템이 저장소로 바로 들어가면 다음에 열 때까지 반영되지 않아요.")));
        list.pair(expand, storage);

        list.header("모험 가방");
        Widgets.Field aliases = new Widgets.Field(rowWidth(), Text.literal("가방 단축어(선택)"));
        aliases.setMaxLength(100);
        aliases.setText(String.join(", ", cc.bagAliases));
        aliases.setTooltip(Tooltip.of(Text.literal("/ㄱ1 · /가방 2 · /.ㄱ2 같은 띵타이쿤 가방 명령어는 적지 않아도 알아서 알아봐요.\n"
                + "다른 단축어를 쓸 때만 적어요. 예: 짐 (→ 짐1·짐2) 또는 짐1, 짐2")));
        aliases.setChangedListener(text -> cc.bagAliases = splitList(text));
        list.labeled("가방 단축어", aliases);
        Widgets.Button clear = new Widgets.Button(rowWidth(), 20, Text.literal("이 계정의 가방 기록 지우기"), false, b -> {
            if (!confirmClearBags) {
                confirmClearBags = true;
                b.setMessage(Text.literal("한 번 더 누르면 지워요"));
                return;
            }
            BagTracker.clearCurrent();
            confirmClearBags = false;
            b.setMessage(Text.literal("지웠어요"));
            b.active = false;
        });
        clear.setTooltip(Tooltip.of(Text.literal("가방 내용 기록과 기억한 가방 제목을 지워요. 가방을 다시 열면 새로 기록돼요.")));
        clear.active = client != null && client.player != null && BagTracker.recordedCount() > 0;
        list.single(clear);
        list.note("가방은 열었을 때만 내용을 알 수 있어서, 한 번씩 열어 둔 가방만 합산돼요.");

        list.header("기록된 가방 · 저장소");
        Map<Integer, Long> bags = BagTracker.recordedBags();
        long storageAt = BagTracker.storageSeenAt();
        if (bags.isEmpty() && storageAt == 0) {
            list.note(client != null && client.player != null
                    ? "아직 기록이 없어요. 모험 가방과 아이템 저장소를 한 번씩 열어 주세요."
                    : "게임에 들어가 있을 때 기록이 보여요.");
        }
        bags.forEach((n, at) -> list.single(new InfoRow(new ItemStack(Items.CHEST), n + "번 가방",
                CounterHud.ago(at) + " 확인", Theme.TEXT_SOFT, null)));
        if (storageAt > 0) {
            list.single(new InfoRow(new ItemStack(Items.BARREL), "아이템 저장소",
                    CounterHud.ago(storageAt) + " 확인", Theme.TEXT_SOFT, null));
        }

        // 제작 완료 타이머
        DdingMateConfig.Craft cr = cfg.craft;
        list.header("제작 완료 타이머");
        list.pair(tip(toggle("제작 완료 타이머", cr.enabled, v -> {
                    cr.enabled = v;
                    rebuild();
                }), "제작·가공 시설에서 만드는 아이템마다 완료까지 남은 시간을 세요"),
                tip(toggle("다 되면 알림", cr.notify, v -> cr.notify = v), "다 되면 소리와 팝업으로 알려 줘요"));
        list.pair(tip(toggle("일정 HUD 에 표시", cr.showInHud, v -> cr.showInHud = v), "일정 HUD 에 제작 중인 아이템과 남은 시간을 보여 줘요"), null);
        list.note("제작을 시작하면 알아서 등록돼요. 시설 창을 한 번 열면 레시피의 \"제작 시간\"을 배워서, 넣은 순서대로 "
                + "하나씩 끝나는 시각을 계산해요. 같은 걸 여러 번 넣으면 \"×7 [3]\" 처럼 한 줄로 묶고 마지막 것이 끝날 때까지 세요. 꺼내면 빠져요.");
        list.header("제작 시간 줄이기 · 배운 전문가 스킬");
        list.pair(fastSkill("채광 · 초고속 용광로", cr.mining, v -> cr.mining = v, "채광물 가공 시설·강화 제작 시설", false),
                fastSkill("재배 · 불 더 올려!", cr.farming, v -> cr.farming = v, "농작물 가공 시설·요리 제작 시설", false));
        list.pair(fastSkill("해양 · 물 흐르듯 술술", cr.marine, v -> cr.marine = v, "해양 제작 시설·연금 제작 시설", true),
                fastSkill("사냥 · 고속 영혼 가공기", cr.hunting, v -> cr.hunting = v, "영혼 가공 시설", false));
        list.note("시설 창에 적힌 제작 시간은 스킬을 적용하기 전 시간이에요. 배운 레벨을 골라 두면 줄어든 실제 시간으로 계산해요.");
        list.header("지금 만드는 중");
        List<kr.ddingmate.craft.CraftTimers.Group> jobs = kr.ddingmate.craft.CraftTimers.groups();
        if (jobs.isEmpty()) {
            list.note(client != null && client.player != null
                    ? "만들고 있는 아이템이 없어요. 시설에서 제작을 시작하면 여기에 나와요."
                    : "게임에 들어가 있을 때 보여요.");
        }
        long nowMs = System.currentTimeMillis();
        for (kr.ddingmate.craft.CraftTimers.Group j : jobs) {
            ItemStack icon = kr.ddingmate.counter.IconCache.forMaterial(j.name());
            InfoRow row = new InfoRow(icon == null ? new ItemStack(Items.CRAFTING_TABLE) : icon,
                    j.label(), kr.ddingmate.craft.CraftTimers.remainingText(j, nowMs),
                    j.done() ? Theme.GREEN : j.unknown() ? Theme.TEXT_SOFT : Theme.BLUE, null);
            Widgets.Button del = new Widgets.Button(44, 20, Text.literal("지우기"), false, b -> {
                kr.ddingmate.craft.CraftTimers.remove(j);
                rebuild();
            });
            del.setTooltip(Tooltip.of(Text.literal("이미 꺼냈거나 잘못 등록된 제작을 목록에서 지워요")));
            list.withButton(row, del);
        }
    }

    private void buildGear() {
        DdingMateConfig.Durability dc = cfg.durability;
        list.guide("도구·갑옷을 켜 두면 남은 내구도를 HUD 에 보여 주고, 얼마 안 남으면 알려 줘요.");
        list.header("추적할 장비 고르기 · 지금 인벤토리에 있는 것");
        Map<String, ItemStack> found = client != null ? DurabilityTracker.candidates(client) : Map.of();
        if (client == null || client.player == null) {
            list.note("게임에 들어가 있을 때 인벤토리의 장비가 여기에 보여요.");
        } else if (found.isEmpty()) {
            list.note("인벤토리에 내구도가 있는 장비가 없어요.");
        }
        for (Map.Entry<String, ItemStack> e : found.entrySet()) {
            ItemStack s = e.getValue();
            String name = kr.ddingmate.counter.ItemKeys.stripGlyphs(s.getName().getString()).trim();
            DurabilityTracker.Durability d = DurabilityTracker.read(s);
            list.single(new Widgets.Toggle(rowWidth(), name, DurabilityTracker.isTracked(e.getKey()),
                    v -> DurabilityTracker.setTracked(e.getKey(), name, v))
                    .icon(s.copyWithCount(1)).detail(d.left() + "/" + d.max()));
        }
        List<DdingMateConfig.TrackedItem> missing = dc.tracked.stream().filter(t -> !found.containsKey(t.key)).toList();
        if (!missing.isEmpty()) {
            list.header("추적 중이지만 지금 인벤토리에 없는 장비");
            for (DdingMateConfig.TrackedItem t : missing) {
                list.single(new Widgets.Toggle(rowWidth(), t.name, true,
                        v -> DurabilityTracker.setTracked(t.key, t.name, v)).detail("인벤토리에 없음"));
            }
        }
        list.header("표시와 알림");
        list.pair(tip(toggle("내구도 HUD", dc.enabled, v -> dc.enabled = v), "켜 둔 장비의 남은 내구도를 화면 구석에 보여 줘요"),
                hudButton());
        list.pair(new Widgets.Cycle<>(half(), "부족 알림", List.of(0, 5, 10, 15, 20, 30),
                nearestOf(dc.warnPercent, 0, 5, 10, 15, 20, 30),
                v -> v == 0 ? "끔" : v + "% 이하", v -> dc.warnPercent = v), null);
        list.note("같은 장비가 여러 개면 손에 든 것 → 왼손 → 입은 것 → 단축바 → 나머지 칸 순서로 먼저 찾은 것을 보여 줘요.");
    }

    private void buildTodo() {
        DdingMateConfig.Todo tc = cfg.todo;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        list.guide("할 일은 서버가 보내는 메시지를 보고 알아서 세요(직접 체크하지 않아요). 초기화 시각이 지나면 0 으로 돌아가요.");
        list.header("오늘 할 일 · 알아서 세요");
        if (tc.items.isEmpty()) list.note("목록이 비었어요. 아래에서 넣을 할 일을 골라 주세요.");
        for (DdingMateConfig.TodoItem item : tc.items) {
            TodoRow main = new TodoRow(item);
            Widgets.Button remove = new Widgets.Button(44, 20, Text.literal("빼기"), false, b -> {
                tc.items.remove(item);
                rebuild();
            });
            remove.setTooltip(Tooltip.of(Text.literal("목록에서 빼요. 아래 '넣을 수 있는 할 일'에서 다시 넣을 수 있어요")));
            list.withButton(main, remove);
        }
        list.header("넣을 수 있는 할 일");
        boolean any = false;
        for (kr.ddingmate.todo.TodoPreset p : kr.ddingmate.todo.TodoPreset.values()) {
            if (tc.items.stream().anyMatch(t -> p.id.equals(t.preset))) continue;
            any = true;
            Widgets.Button add = new Widgets.Button(rowWidth(), 20,
                    Text.literal("＋ " + p.label + (p.target > 1 ? " [0/" + p.target + "]" : "") + " · " + TodoList.resetName(p.reset)), false, b -> {
                tc.items.add(p.create());
                rebuild();
            });
            add.setTooltip(Tooltip.of(Text.literal(p.how)));
            list.single(add);
        }
        if (!any) list.note("알아서 셀 수 있는 할 일을 모두 넣었어요.");

        list.header("표시와 알림");
        list.pair(tip(toggle("할 일 HUD", tc.hudEnabled, v -> tc.hudEnabled = v), "아직 안 한 할 일을 화면 구석에 보여 줘요"),
                hudButton());
        list.pair(new Widgets.Cycle<>(half(), "남은 할 일 알림", List.of(0, 30, 60, 120),
                nearestOf(tc.remindMinutes, 0, 30, 60, 120),
                v -> v == 0 ? "끔" : "03시 " + v + "분 전", v -> tc.remindMinutes = v), null);

        DdingMateConfig.Playtime pc = cfg.playtime;
        list.header("접속 시간 보상 · 위키 기준 1·2·3·4·5·8시간");
        long sec = PlaytimeTracker.seconds();
        if (sec >= 0) {
            int next = PlaytimeTracker.nextRewardHour(sec);
            long synced = PlaytimeTracker.syncedAt();
            String basis = synced > 0
                    ? "서버 기준 " + Instant.ofEpochMilli(synced).atZone(ScheduleEngine.KST).format(DateTimeFormatter.ofPattern("HH:mm")) + "에 맞춤"
                    : "추정값 · /접속시간 창을 한 번 열면 서버 기록에 맞춰요";
            list.note("오늘 접속 " + (sec / 3600) + "시간 " + (sec % 3600 / 60) + "분 (" + basis + ")"
                    + (next == 0 ? " · 오늘 보상 구간을 다 채웠어요"
                    : " · 다음 보상 " + next + "시간: " + PlaytimeTracker.REWARDS.get(next)));
        }
        list.pair(tip(toggle("보상 구간 알림", pc.enabled, v -> pc.enabled = v), "접속 1·2·3·4·5·8시간을 넘길 때마다 채팅으로 알려 줘요"),
                tip(toggle("일정 HUD 에 표시", pc.showInHud, v -> pc.showInHud = v), "일정 HUD 맨 위에 오늘 접속 시간을 보여 줘요"));
        list.pair(toggle("23:30 초기화 전 알림", pc.midnightReminder, v -> pc.midnightReminder = v), null);
        list.note("보상 구간을 넘기면 채팅으로 알려 줘요. /접속시간 창을 열면 서버 기록(초 단위)과 받은 보상을 읽어 맞춰요. 보상은 그 창에서 직접 받아야 하고, 자정에 초기화돼요.");
    }

    private void buildLog() {
        List<AlertLog.Entry> entries = AlertLog.entries();
        list.guide("놓친 알림을 여기서 다시 볼 수 있어요. 소리·팝업을 꺼 둬도 기록은 남아요.");
        list.header("최근 알림 " + entries.size() + "개 · 최대 100개");
        if (entries.isEmpty()) list.note("아직 알림이 없어요.");
        for (AlertLog.Entry e : entries) list.log(e);
        if (!entries.isEmpty()) {
            list.single(new Widgets.Button(rowWidth(), 20, Text.literal("기록 모두 지우기"), false, b -> {
                if (!confirmClearLog) {
                    confirmClearLog = true;
                    b.setMessage(Text.literal("한 번 더 누르면 지워요"));
                    return;
                }
                AlertLog.clear();
                confirmClearLog = false;
                rebuild();
            }));
        }
    }

    /** 목록을 다시 만들되 보던 위치는 유지 */
    private void rebuild() {
        savedScroll = list.getScrollY();
        clearAndInit();
    }

    private static int nearestOf(int v, int... options) {
        int best = options[0];
        for (int o : options) if (Math.abs(o - v) < Math.abs(best - v)) best = o;
        return best;
    }

    // ---------------------------------------------------------------- 위젯 만들기

    private int half() {
        return (rowWidth() - GAP) / 2;
    }

    /** 마우스를 올리면 뜨는 설명을 붙여서 돌려준다 */
    private static <W extends ClickableWidget> W tip(W widget, String text) {
        widget.setTooltip(Tooltip.of(Text.literal(text)));
        return widget;
    }

    /** 제작 시간을 줄여 주는 전문가 스킬 레벨 고르기: "안 배움" / "1레벨 · 10%" … */
    private Widgets.Cycle<Integer> fastSkill(String name, int level, java.util.function.IntConsumer set, String facilities, boolean marine) {
        int[] pct = marine ? new int[]{0, 10, 20, 30, 50, 70} : new int[]{0, 10, 30, 40, 60, 80};
        Widgets.Cycle<Integer> c = new Widgets.Cycle<>(half(), name, IntStream.rangeClosed(0, 5).boxed().toList(), level,
                v -> v == 0 ? "안 배움" : v + "레벨 · " + pct[v] + "%", v -> {
                    set.accept(v);
                    kr.ddingmate.craft.CraftTimers.recalculate();
                });
        c.setTooltip(Tooltip.of(Text.literal(facilities + "의 제작 시간을 줄여 주는 스킬이에요. /전문가 에서 해당 스킬보기 창을 한 번 열면 레벨을 자동으로 읽어 맞춰요(직접 골라도 돼요).")));
        return c;
    }

    /** 반쪽 너비 "HUD 위치·크기 설정" 단추: 게임 화면에서 HUD 를 끌어 옮기고 크기를 바꾸는 편집 화면을 연다 */
    private Widgets.Button hudButton() {
        Widgets.Button b = new Widgets.Button(half(), 20, Text.literal("HUD 위치·크기 설정"), false, x -> {
            savedScroll = list.getScrollY();
            client.setScreen(new HudEditorScreen(this));
        });
        boolean inGame = client != null && client.player != null;
        b.active = inGame;
        b.setTooltip(Tooltip.of(Text.literal(inGame
                ? "누르면 게임 화면에서 HUD 를 마우스로 끌어 옮기고, 휠이나 모서리로 크기를 바꿔요. 아래쪽 [저장]·[초기화] 단추로 마무리해요."
                : "게임에 들어가 있을 때 쓸 수 있어요")));
        return b;
    }

    private Widgets.Toggle toggle(String name, boolean value, Consumer<Boolean> set) {
        return new Widgets.Toggle(half(), name, value, set);
    }

    private Widgets.Cycle<Integer> channel(String name, int max, int value, java.util.function.IntConsumer set) {
        List<Integer> values = IntStream.rangeClosed(0, max).boxed().toList();
        return new Widgets.Cycle<>(half(), name, values, Math.max(0, Math.min(max, value)),
                v -> v == 0 ? "끔" : v + "채널",
                v -> {
                    set.accept(v);
                    DdingMate.rebuildRules();
                });
    }

    private static String cornerName(DdingMateConfig.Corner c) {
        return switch (c) {
            case TOP_LEFT -> "왼쪽 위";
            case TOP_RIGHT -> "오른쪽 위";
            case BOTTOM_LEFT -> "왼쪽 아래";
            case BOTTOM_RIGHT -> "오른쪽 아래";
        };
    }

    private static String styleName(DdingMateConfig.HudStyle s) {
        return switch (s) {
            case DARK -> "어두운 판";
            case LIGHT -> "밝은 창";
            case CLASSIC -> "반투명";
        };
    }

    private static int nearest(int v) {
        int[] options = {30, 40, 50, 60, 70, 80, 85, 90, 100};
        int best = options[0];
        for (int o : options) if (Math.abs(o - v) < Math.abs(best - v)) best = o;
        return best;
    }

    private static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.split(",")) if (!w.isBlank() && !out.contains(w.trim())) out.add(w.trim());
        return out;
    }

    private Widgets.Field leadsField(DdingMateConfig.EventSetting s) {
        Widgets.Field f = new Widgets.Field(FIELD_W, Text.literal("분 전"));
        f.setMaxLength(30);
        f.setText(String.join(",", s.leads.stream().map(String::valueOf).toList()));
        f.setChangedListener(text -> {
            List<Integer> leads = new ArrayList<>();
            for (String part : text.split("[,\\s]+")) {
                if (part.isBlank()) continue;
                try {
                    int v = Integer.parseInt(part.trim());
                    if (v >= 0 && v <= 1440 && !leads.contains(v)) leads.add(v);
                } catch (NumberFormatException ignored) {
                    return; // 입력 중인 값은 무시하고 마지막으로 올바른 값을 유지한다
                }
            }
            s.leads = leads;
        });
        return f;
    }

    // ---------------------------------------------------------------- 화면

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        drawFrame(ctx, textRenderer, px, py, pw, ph, "띵메이트");
        Theme.inset(ctx, cx, cy, cw, ch);
        String hint = "바꾼 값은 바로 적용돼요";
        Theme.text(ctx, textRenderer, hint, px + (pw - Theme.width(textRenderer, hint)) / 2, py + ph - 20, Theme.TEXT_SOFT);
    }

    /**
     * 공통 창 틀: 둥근 회청색 창 + 하늘색 머리 띠 + 양옆 아이템 장식 + 굵은 제목 + 비공식 꼬리표.
     * 장식은 바닐라 아이템(종·시계·상자·나침반)을 그대로 그린다.
     */
    static void drawFrame(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, String title) {
        Theme.window(ctx, x, y, w, h);
        Theme.skyHeader(ctx, x + 2, y + 2, w - 4, HEADER_H - 2);
        // 양옆 장식: 겹쳐 놓은 아이템
        Theme.item(ctx, new ItemStack(Items.BELL), x + 6, y + 6, 16);
        Theme.item(ctx, new ItemStack(Items.CLOCK), x + 19, y + 9, 14);
        Theme.item(ctx, new ItemStack(Items.CHEST), x + w - 22, y + 6, 16);
        Theme.item(ctx, new ItemStack(Items.COMPASS), x + w - 34, y + 9, 14);
        // 제목 + 비공식 꼬리표를 한 덩어리로 보고 가운데에 놓는다(제목만 가운데면 꼬리표 때문에 오른쪽으로 쏠려 보인다)
        Text t = Theme.title(title);
        int tw = tr.getWidth(t);
        String badge = "비공식";
        int bw = Theme.width(tr, badge) + 6;
        int gap = 5;
        int tx = x + (w - (tw + gap + bw)) / 2;
        int mid = y + 2 + (HEADER_H - 2) / 2; // 하늘색 머리띠의 세로 가운데
        int ty = mid - 2; // 제목 글꼴(크기 10)은 글자가 줄 위쪽에 붙어 그려져서 2칸 내린다
        ctx.drawText(tr, t, tx + 1, ty + 1, 0x55FFFFFF, false);
        ctx.drawText(tr, t, tx, ty, Theme.INK, false);
        int bx = tx + tw + gap;
        int by = mid - 4;
        ctx.fill(bx, by, bx + bw, by + 9, Theme.SLATE);
        Theme.text(ctx, tr, badge, bx + 3, by + 1, Theme.WHITE);
    }

    @Override
    public void close() {
        cfg.sanitize();
        cfg.save();
        DdingMate.rebuildRules();
        if (client != null) client.setScreen(parent);
    }

    // ---------------------------------------------------------------- 탭 단추

    /** 서버 설정 창 분류 단추처럼 파란 판. 선택된 탭은 눌린 모양(짙은 남색) */
    private class TabButton extends PressableWidget {
        private final Tab target;

        TabButton(Tab target, int w) {
            super(0, 0, w, 20, Text.literal(target.title));
            this.target = target;
        }

        @Override
        public void onPress() {
            switchTab(target);
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            boolean on = tab == target;
            Theme.tile(ctx, getX(), getY(), width, height, on ? Theme.Tile.BLUE_PRESSED : Theme.Tile.BLUE, isSelected() && !on);
            String label = target == Tab.LOG && tab != Tab.LOG && AlertLog.unread() > 0
                    ? target.title + " " + Math.min(99, AlertLog.unread()) : target.title;
            String s = Theme.ellipsize(textRenderer, label, width - 24, true);
            int tw = 14 + Theme.boldWidth(textRenderer, s);
            int x = getX() + (width - tw) / 2;
            int dy = on ? 1 : 0;
            Theme.item(ctx, target.icon, x, getY() + 4 + dy, 12);
            Theme.boldText(ctx, textRenderer, s, x + 14, getY() + 6 + dy, on ? 0xFFDCE8FF : Theme.WHITE, false);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /** 홈·기록 목록의 한 줄: 아이콘 + 이름 + 오른쪽 값. 이동할 탭이 있으면 누르면 그 탭으로 간다 */
    private class InfoRow extends PressableWidget {
        private final ItemStack icon;
        private final String label;
        private final String value;
        private final int valueColor;
        private final Tab target;

        InfoRow(ItemStack icon, String label, String value, int valueColor, Tab target) {
            super(0, 0, rowWidth(), 20, Text.literal(label));
            this.icon = icon;
            this.label = label;
            this.value = value;
            this.valueColor = valueColor;
            this.target = target;
            this.active = target != null;
        }

        @Override
        public void onPress() {
            if (target != null) switchTab(target);
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.row(ctx, getX(), getY(), width, height, target != null && isSelected());
            Theme.item(ctx, icon, getX() + 4, getY() + 2, 16);
            int right = getX() + width - (target != null ? 14 : 6);
            int vw = Theme.boldWidth(textRenderer, value);
            Theme.boldText(ctx, textRenderer, value, right - vw, getY() + 6, valueColor, false);
            Theme.text(ctx, textRenderer, Theme.ellipsize(textRenderer, label, right - vw - 8 - (getX() + 24)),
                    getX() + 24, getY() + 6, Theme.TEXT);
            if (target != null) {
                // 오른쪽 화살표: 누르면 해당 탭으로 이동
                for (int i = 0; i < 4; i++) {
                    ctx.fill(getX() + width - 9 + i, getY() + 6 + i, getX() + width - 8 + i, getY() + 13 - i, Theme.SLATE);
                }
            }
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /**
     * 할 일 한 줄(보기만): 이름 + 초기화 시각 + 오른쪽에 ON/OFF(목표 1) 또는 [2/5] 와 진행 막대(목표 2 이상).
     * 서버 메시지를 보고 알아서 세므로 눌러도 바뀌지 않는다. 마우스를 올리면 어떻게 세는지 보여 준다.
     */
    private class TodoRow extends PressableWidget {
        private final DdingMateConfig.TodoItem item;

        TodoRow(DdingMateConfig.TodoItem item) {
            super(0, 0, 150, 20, Text.literal(item.name));
            this.item = item;
            kr.ddingmate.todo.TodoPreset p = kr.ddingmate.todo.TodoPreset.of(item);
            setTooltip(Tooltip.of(Text.literal(item.name + " · " + TodoList.resetName(item.reset) + " 초기화"
                    + (p == null ? "" : "\n" + p.how))));
        }

        @Override
        public void onPress() {
            // 직접 체크하지 않는다(알아서 셈)
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
            boolean done = TodoList.isDone(item, now);
            Theme.row(ctx, getX(), getY(), width, height, isSelected());
            int right = getX() + width - 6;
            if (TodoList.needsWindow(item, now)) {
                // 이번 초기화 뒤로 의뢰 창을 안 열었으면 개수 대신 안내
                String hint = TodoList.OPEN_WINDOW;
                int hw = Theme.boldWidth(textRenderer, hint);
                Theme.boldText(ctx, textRenderer, hint, right - hw, getY() + 5, Theme.GOLD_ON_LIGHT, false);
                right -= hw + 6;
            } else if (item.target > 1) {
                int p = TodoList.progress(item, now);
                String cnt = p + "/" + item.target;
                int cw = Theme.boldWidth(textRenderer, cnt);
                Theme.boldText(ctx, textRenderer, cnt, right - cw, getY() + 5, done ? Theme.GREEN_ON_LIGHT : Theme.BLUE_ON_LIGHT, false);
                right -= cw + 6;
                // 진행 막대
                int bx = getX() + 6, bw = width - 12;
                ctx.fill(bx, getY() + height - 4, bx + bw, getY() + height - 2, Theme.SLATE);
                ctx.fill(bx, getY() + height - 4, bx + Math.round(bw * (float) p / item.target), getY() + height - 2,
                        done ? Theme.GREEN : Theme.BLUE);
            } else {
                Theme.onOff(ctx, textRenderer, getX() + width - 39, getY() + 3, done, isSelected());
                right = getX() + width - 44;
            }
            String reset = TodoList.resetName(item.reset);
            int rw = Theme.width(textRenderer, reset);
            Theme.text(ctx, textRenderer, reset, right - rw, getY() + 5, Theme.TEXT_SOFT);
            String name = Theme.ellipsize(textRenderer, item.name, right - rw - 8 - (getX() + 7));
            Theme.text(ctx, textRenderer, name, getX() + 7, getY() + 5, done ? Theme.GREEN_ON_LIGHT : Theme.TEXT);
            if (done) {
                int nw = Theme.width(textRenderer, name);
                ctx.fill(getX() + 7, getY() + 9, getX() + 7 + nw, getY() + 10, Theme.GREEN_ON_LIGHT); // 다 한 일은 줄 긋기
            }
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /** 핀 칸: 번호 꼬리표 + 재료 아이콘 몇 개 + 레시피 이름. 누르면 레시피 고르기 화면 */
    private class PinButton extends PressableWidget {
        private final int index;
        private final DdingMateConfig.Pin pin;
        private final Consumer<PinButton> action;

        PinButton(int index, DdingMateConfig.Pin pin, Consumer<PinButton> action) {
            super(0, 0, 150, 20, Text.literal("핀 " + (index + 1)));
            this.index = index;
            this.pin = pin;
            this.action = action;
            RecipeBook.Recipe r = RecipeBook.get(pin.recipe);
            if (r != null) {
                setTooltip(Tooltip.of(Text.literal(r.facility + " · [" + r.category + "]\n"
                        + String.join("\n", r.materials.stream().map(m -> m.name + " " + m.count + "개").toList())
                        + "\n\n눌러서 다른 레시피 고르기")));
            } else {
                setTooltip(Tooltip.of(Text.literal("눌러서 레시피 고르기")));
            }
        }

        @Override
        public void onPress() {
            action.accept(this);
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.row(ctx, getX(), getY(), width, height, isSelected());
            int x = getX() + 3;
            Theme.tile(ctx, x, getY() + 3, 14, 14, Theme.Tile.BLUE, false);
            String n = String.valueOf(index + 1);
            Theme.boldText(ctx, textRenderer, n, x + 7 - Theme.boldWidth(textRenderer, n) / 2, getY() + 6, Theme.WHITE, false);
            x += 18;
            RecipeBook.Recipe r = RecipeBook.get(pin.recipe);
            if (r == null) {
                Theme.text(ctx, textRenderer, "비어 있음 · 눌러서 레시피 고르기", x + 2, getY() + 6, Theme.TEXT_SOFT);
                return;
            }
            // [만드는 아이템] 이름 │ 재료 그림들
            ItemStack out = IconCache.forRecipe(r);
            if (out != null) Theme.item(ctx, out, x, getY() + 2, 16);
            else Theme.unknownIcon(ctx, textRenderer, x + 1, getY() + 3, 14);
            x += 20;
            int mats = Math.min(4, r.materials.size());
            String name = Theme.ellipsize(textRenderer, MaterialCounter.title(pin, r), Math.max(40, getX() + width - 10 - mats * 15 - x), true);
            Theme.boldText(ctx, textRenderer, name, x, getY() + 6, Theme.TEXT, false);
            int mx = x + Theme.boldWidth(textRenderer, name) + 7;
            if (mats > 0) ctx.fill(mx - 4, getY() + 4, mx - 3, getY() + height - 4, Theme.withAlpha(Theme.TEXT_SOFT, 120));
            for (int i = 0; i < mats; i++) {
                ItemStack icon = IconCache.forMaterial(r.materials.get(i).name);
                if (icon != null) Theme.item(ctx, icon, mx, getY() + 3, 14);
                else Theme.unknownIcon(ctx, textRenderer, mx + 1, getY() + 4, 12);
                mx += 15;
            }
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    // ---------------------------------------------------------------- 목록

    /** 목록 바탕·구분선을 지우고 스크롤 막대를 회청색·파랑으로 그리는 목록 */
    abstract static class ThemedList<E extends ElementListWidget.Entry<E>> extends ElementListWidget<E> {
        ThemedList(MinecraftClient client, int width, int height, int y, int itemHeight) {
            super(client, width, height, y, itemHeight);
        }

        @Override
        protected int getScrollbarX() {
            return getX() + width - 7;
        }

        @Override
        protected void drawMenuListBackground(DrawContext ctx) {
        }

        @Override
        protected void drawHeaderAndFooterSeparators(DrawContext ctx) {
        }

        @Override
        protected void drawScrollbar(DrawContext ctx) {
            if (getMaxScrollY() <= 0) return;
            int x = getScrollbarX();
            ctx.fill(x, getY() + 1, x + 4, getBottom() - 1, Theme.ROW_SHADE);
            int ty = getScrollbarThumbY();
            int th = getScrollbarThumbHeight();
            ctx.fill(x, ty, x + 4, ty + th, Theme.BLUE);
            ctx.fill(x, ty, x + 4, ty + 1, Theme.BLUE_LIGHT);
            ctx.fill(x, ty + th - 1, x + 4, ty + th, Theme.BLUE_DARK);
        }
    }

    private class ConfigList extends ThemedList<Row> {
        ConfigList(MinecraftClient client, int width, int height, int y, int itemHeight) {
            super(client, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return rowWidth();
        }

        void header(String text) {
            addEntry(new Row(List.of(), text, RowKind.HEADER));
        }

        /**
         * 튜토리얼이 설명할 설정 줄을 찾아 보이게 스크롤하고 그 줄의 자리 {x, y, w, h} 를 돌려준다.
         * 위젯 이름이 똑같은 줄 → 글이 똑같은 줄 → 위젯 이름이 그 말로 시작하는 줄 순서로 찾는다.
         */
        int[] focus(String key) {
            Row found = null;
            for (Row r : children()) if (r.widgetNamed(key, true)) { found = r; break; }
            if (found == null) for (Row r : children()) if (key.equals(r.text)) { found = r; break; }
            if (found == null) for (Row r : children()) if (r.widgetNamed(key, false)) { found = r; break; }
            if (found == null) return null;
            int i = children().indexOf(found);
            int top = getRowTop(i);
            if (top < getY() + 2 || top + 24 > getBottom() - 2) {
                centerScrollOn(found);
                top = getRowTop(i);
            }
            return new int[]{getRowLeft() - 2, top - 1, getRowWidth() + 4, 23};
        }

        void note(String text) {
            addEntry(new Row(List.of(), text, RowKind.NOTE));
        }

        /** 탭 맨 위 안내 상자: 이 탭에서 무엇을 하는지 한두 줄로 */
        void guide(String text) {
            addEntry(new Row(List.of(), text, RowKind.GUIDE));
        }

        void single(ClickableWidget w) {
            w.setWidth(rowWidth());
            addEntry(new Row(List.of(w), null, RowKind.WIDGETS));
        }

        void pair(ClickableWidget a, ClickableWidget b) {
            a.setWidth(half());
            if (b == null) {
                addEntry(new Row(List.of(a), null, RowKind.WIDGETS));
                return;
            }
            b.setWidth(half());
            addEntry(new Row(List.of(a, b), null, RowKind.WIDGETS));
        }

        /** 넓은 위젯 + 숫자 칸 + 끝에 단위 글자 */
        void withUnit(ClickableWidget main, Widgets.Field field, String unit) {
            main.setWidth(rowWidth() - FIELD_W - GAP - UNIT_W);
            addEntry(new Row(List.of(main, field), unit, RowKind.UNIT));
        }

        /** 왼쪽에 이름 글자, 오른쪽에 입력 칸 */
        /** 왼쪽에 이름 글자, 오른쪽에 정해 둔 너비의 위젯 여러 개 */
        void labeledRow(String label, List<ClickableWidget> widgets) {
            addEntry(new Row(List.copyOf(widgets), label, RowKind.LABELED));
        }

        void labeled(String label, Widgets.Field field) {
            addEntry(new Row(List.of(field), label, RowKind.LABELED));
        }

        /** 넓은 위젯 + 오른쪽 작은 단추 */
        void withButton(ClickableWidget main, ClickableWidget button) {
            main.setWidth(rowWidth() - button.getWidth() - GAP);
            addEntry(new Row(List.of(main, button), null, RowKind.WIDGETS));
        }

        /** 위젯 여러 개를 정해 둔 너비 그대로 한 줄에 */
        void row(ClickableWidget... widgets) {
            addEntry(new Row(List.of(widgets), null, RowKind.WIDGETS));
        }

        void log(AlertLog.Entry e) {
            addEntry(new Row(List.of(), null, RowKind.LOG, e));
        }
    }

    private enum RowKind { HEADER, NOTE, GUIDE, WIDGETS, UNIT, LABELED, LOG }

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("M/d HH:mm");

    private class Row extends ElementListWidget.Entry<Row> {
        private static final int LABEL_W = 70;
        private final List<ClickableWidget> widgets;
        private final String text;
        private final RowKind kind;
        private final AlertLog.Entry entry;

        Row(List<ClickableWidget> widgets, String text, RowKind kind) {
            this(widgets, text, kind, null);
        }

        Row(List<ClickableWidget> widgets, String text, RowKind kind, AlertLog.Entry entry) {
            this.widgets = widgets;
            this.text = text;
            this.kind = kind;
            this.entry = entry;
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float tickDelta) {
            switch (kind) {
                case HEADER -> {
                    ctx.fill(x, y + 8, x + 3, y + 17, Theme.BLUE);
                    int split = text.indexOf(" · ");
                    String head = split < 0 ? text : text.substring(0, split);
                    String desc = split < 0 ? "" : text.substring(split + 3);
                    String s = Theme.ellipsize(textRenderer, head, entryWidth - 10, true);
                    Theme.boldText(ctx, textRenderer, s, x + 7, y + 9, Theme.INK, false);
                    if (!desc.isEmpty()) {
                        int dx = x + 7 + Theme.boldWidth(textRenderer, s) + 6;
                        Theme.text(ctx, textRenderer, Theme.ellipsize(textRenderer, desc, x + entryWidth - dx), dx, y + 9, Theme.TEXT_SOFT);
                    }
                    ctx.fill(x, y + 20, x + entryWidth, y + 21, Theme.ROW_SHADE);
                    ctx.fill(x, y + 21, x + entryWidth, y + 22, Theme.WINDOW_LIGHT);
                    return;
                }
                case GUIDE -> {
                    // 하늘색 안내 상자 + 왼쪽 i 표시
                    Theme.outlineSmall(ctx, x, y + 1, entryWidth, 22, 0xFF8FB1E6);
                    ctx.fill(x + 1, y + 2, x + entryWidth - 1, y + 22, 0xFFE3ECFB);
                    ctx.fill(x + 1, y + 2, x + 4, y + 22, Theme.BLUE);
                    Theme.tile(ctx, x + 8, y + 6, 12, 12, Theme.Tile.BLUE, false);
                    Theme.boldText(ctx, textRenderer, "i", x + 13, y + 8, Theme.WHITE, false);
                    ctx.drawWrappedText(textRenderer, Theme.body(text), x + 25, y + 4 + (Theme.width(textRenderer, text) > entryWidth - 30 ? 0 : 4),
                            entryWidth - 30, Theme.INK, false);
                    return;
                }
                case NOTE -> {
                    ctx.drawWrappedText(textRenderer, Theme.body(text), x + 2, y + 4, entryWidth - 4, Theme.TEXT_SOFT, false);
                    return;
                }
                case LOG -> {
                    Theme.row(ctx, x, y, entryWidth, 22, false);
                    String time = Instant.ofEpochMilli(entry.at).atZone(ScheduleEngine.KST).format(LOG_TIME);
                    int chipW = Theme.width(textRenderer, entry.kind) + 8;
                    Theme.tile(ctx, x + 4, y + 4, chipW, 14, Theme.Tile.BLUE, false);
                    Theme.text(ctx, textRenderer, entry.kind, x + 8, y + 7, Theme.WHITE);
                    int tx = x + 8 + chipW;
                    int tw = Theme.width(textRenderer, time);
                    Theme.text(ctx, textRenderer, time, x + entryWidth - 6 - tw, y + 3, Theme.TEXT_SOFT);
                    int avail = x + entryWidth - 10 - tw - tx;
                    Theme.boldText(ctx, textRenderer, Theme.ellipsize(textRenderer, entry.title, avail, true), tx, y + 3, Theme.TEXT, false);
                    String body = entry.body == null ? "" : entry.body;
                    Theme.text(ctx, textRenderer, Theme.ellipsize(textRenderer, body, x + entryWidth - 6 - tx), tx, y + 13, Theme.TEXT_SOFT);
                    return;
                }
                default -> {
                }
            }
            int cxp = x;
            if (kind == RowKind.LABELED) {
                Theme.text(ctx, textRenderer, text, x + 2, y + 6, Theme.TEXT);
                cxp += LABEL_W;
            }
            for (ClickableWidget w : widgets) {
                if (w instanceof Widgets.Field f) {
                    int fw = kind == RowKind.LABELED && widgets.size() == 1 ? entryWidth - LABEL_W : f.outer();
                    f.place(cxp, y, fw);
                    f.render(ctx, mouseX, mouseY, tickDelta);
                    cxp += fw + GAP;
                } else {
                    w.setX(cxp);
                    w.setY(y);
                    w.render(ctx, mouseX, mouseY, tickDelta);
                    cxp += w.getWidth() + GAP;
                }
            }
            if (kind == RowKind.UNIT) Theme.text(ctx, textRenderer, text, cxp, y + 6, Theme.TEXT_SOFT);
        }

        boolean widgetNamed(String key, boolean exact) {
            for (ClickableWidget w : widgets) {
                String m = w.getMessage().getString();
                if (exact ? m.equals(key) : m.startsWith(key)) return true;
            }
            return false;
        }

        @Override
        public List<? extends Element> children() {
            return widgets;
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return widgets;
        }
    }
}
