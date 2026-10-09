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

    private enum Tab {
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
        EVENT_NAMES.put("serenity_reset", "세레니티 초기화 (02·14시)");
        EVENT_NAMES.put("fishing_contest", "낚시 대회 (10·22시)");
        EVENT_NAMES.put("invest_deadline", "마을 투자 마감 (수 02:59)");
        EVENT_NAMES.put("maintenance_start", "정기 점검 시작 (수 09:00)");
        EVENT_NAMES.put("maintenance_end", "정기 점검 종료 (수 15:00)");
        EVENT_NAMES.put("weekly_reset", "주간 초기화 (목 03:00)");
        EVENT_NAMES.put("hunter_affinity", "수렵꾼 호감도 (매월 1일)");
        EVENT_NAMES.put("village_reboot", "마을 채널 재시작");
        EVENT_NAMES.put("hunt_reboot", "노크틸라 채널 재시작");
        EVENT_NAMES.put("wild_reboot", "야생 채널 재시작");
        EVENT_NAMES.put("island_reboot", "아일랜드 채널 재시작");
        EVENT_NAMES.put("spawn_reboot", "스폰 채널 재시작");
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

    private int px, py, pw, ph;
    private int cx, cy, cw, ch;

    public DdingMateScreen(Screen parent) {
        super(Text.literal("띵메이트 설정"));
        this.parent = parent;
    }

    @Override
    protected void init() {
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
        list.header("알림을 받을 방법");
        list.pair(toggle("일정 HUD", cfg.hudEnabled, v -> cfg.hudEnabled = v),
                toggle("소리", cfg.soundEnabled, v -> cfg.soundEnabled = v));
        list.pair(toggle("팝업 알림", cfg.toastEnabled, v -> cfg.toastEnabled = v),
                toggle("채팅 알림 (내 화면만)", cfg.chatEnabled, v -> cfg.chatEnabled = v));
        list.single(new Widgets.Slider(rowWidth(), cfg.soundVolume,
                v -> "소리 크기  " + Math.round(v * 100) + "%", v -> cfg.soundVolume = (float) v));

        list.header("HUD 모양");
        list.pair(new Widgets.Cycle<>(half(), "일정 HUD 위치", List.of(DdingMateConfig.Corner.values()), cfg.hudCorner,
                        DdingMateScreen::cornerName, v -> cfg.hudCorner = v),
                new Widgets.Cycle<>(half(), "보일 일정 수", IntStream.rangeClosed(1, 10).boxed().toList(), cfg.hudLines,
                        n -> n + "개", v -> cfg.hudLines = v));
        list.pair(new Widgets.Cycle<>(half(), "HUD 스타일", List.of(DdingMateConfig.HudStyle.values()), cfg.hudStyle,
                        DdingMateScreen::styleName, v -> cfg.hudStyle = v),
                new Widgets.Cycle<>(half(), "HUD 진하기", List.of(30, 40, 50, 60, 70, 80, 85, 90, 100), nearest(cfg.hudOpacity),
                        v -> v + "%", v -> cfg.hudOpacity = v));
        list.pair(toggle("띵타이쿤 글꼴(갈무리)", cfg.galmuriFont, v -> cfg.galmuriFont = v), null);
        Widgets.Button move = new Widgets.Button(rowWidth(), 20, Text.literal("HUD 위치 직접 옮기기"), true, b -> {
            savedScroll = list.getScrollY();
            client.setScreen(new HudEditorScreen(this));
        });
        boolean inGame = client != null && client.player != null;
        move.active = inGame;
        move.setTooltip(Tooltip.of(Text.literal(inGame
                ? "게임 화면에서 HUD 를 마우스로 끌어 원하는 곳에 놓아요. 옮긴 HUD 는 위의 '위치' 설정 대신 그 자리에 보여요."
                : "게임에 들어가 있을 때 쓸 수 있어요.")));
        list.single(move);
        list.note("HUD 는 화면 구석에만 그려요. 서버 HUD·NPC 말풍선이 쓰는 화면 위 가운데는 비워 둬요.");
    }

    private void buildEvents() {
        list.header("일정별 알림 · 오른쪽 칸은 몇 분 전에 알릴지 (쉼표로 여러 개, 0 = 시작할 때)");
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
            String label = "오늘 접속 " + (PlaytimeTracker.syncedAt() > 0 ? "" : "약 ") + ScheduleHud.hm(sec);
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
            list.single(new InfoRow(new ItemStack(Items.CHEST), "재료 카운터", "핀이 비어 있어요", Theme.TEXT_SOFT, Tab.COUNTER));
        }
        for (MaterialCounter.PinView v : pins) {
            ItemStack icon = v.recipe().materials.isEmpty() ? null : IconCache.forMaterial(v.recipe().materials.get(0).name);
            list.single(new InfoRow(icon == null ? new ItemStack(Items.CHEST) : icon,
                    v.recipe().displayName() + " ×" + v.pin().crafts,
                    v.done() ? "준비 완료" : "재료 " + v.doneCount() + "/" + v.lines().size(),
                    v.done() ? Theme.GREEN : Theme.TEXT_SOFT, Tab.COUNTER));
        }

        list.header("장비");
        List<DurabilityTracker.Row> gear = DurabilityTracker.rows();
        if (gear.isEmpty()) {
            list.single(new InfoRow(new ItemStack(Items.ANVIL), "장비 내구도", "추적 중인 장비가 없어요", Theme.TEXT_SOFT, Tab.GEAR));
        }
        for (DurabilityTracker.Row r : gear) {
            DurabilityTracker.Durability d = r.durability();
            list.single(new InfoRow(r.stack() == null ? new ItemStack(Items.ANVIL) : r.stack(), r.item().name,
                    d == null ? "인벤토리에 없음" : d.left() + "/" + d.max(),
                    d == null ? Theme.TEXT_SOFT : d.ratio() > 0.5 ? Theme.GREEN : d.ratio() > 0.2 ? Theme.GOLD : Theme.RED, Tab.GEAR));
        }
    }

    private int homeTicks;

    @Override
    public void tick() {
        super.tick();
        // 홈은 남은 시간·개수가 계속 바뀌므로 1초마다 다시 그린다
        if (tab == Tab.HOME && ++homeTicks % 20 == 0) rebuild();
    }

    private void buildChannels() {
        list.header("내가 주로 있는 채널 · 재시작 알림에 써요");
        list.pair(channel("마을", 35, cfg.villageChannel, v -> cfg.villageChannel = v),
                channel("노크틸라", 24, cfg.huntChannel, v -> cfg.huntChannel = v));
        list.pair(channel("야생", 16, cfg.wildChannel, v -> cfg.wildChannel = v),
                channel("아일랜드", 15, cfg.islandChannel, v -> cfg.islandChannel = v));
        list.pair(channel("스폰", 4, cfg.spawnChannel, v -> cfg.spawnChannel = v), null);
        list.note("'끔'이면 그 채널 재시작은 알리지 않아요. 클릭하면 다음 번호, 오른쪽 클릭이나 Shift+클릭은 이전 번호예요.");
    }

    private void buildKeywords() {
        list.header("키워드 알림 · 내 채팅창에 뜬 메시지에서 찾아요");
        list.pair(toggle("키워드 알림", cfg.keywords.enabled, v -> cfg.keywords.enabled = v),
                toggle("내 닉네임 언급", cfg.keywords.includeOwnName, v -> cfg.keywords.includeOwnName = v));
        Widgets.Field words = new Widgets.Field(rowWidth(), Text.literal("키워드"));
        words.setMaxLength(500);
        words.setText(String.join(", ", cfg.keywords.words));
        words.setTooltip(Tooltip.of(Text.literal("쉼표로 구분해서 적어요. 예: 오션오더, 풍선, 청소")));
        words.setChangedListener(text -> cfg.keywords.words = splitList(text));
        list.labeled("키워드", words);
        list.note("내가 쓴 채팅에는 반응하지 않아요. 같은 단어는 " + cfg.keywords.cooldownSeconds + "초 안에 다시 울리지 않아요.");
    }

    private void buildCounter() {
        DdingMateConfig.Counter cc = cfg.counter;
        list.header("핀 레시피 · 재료를 인벤토리와 가방에서 자동으로 세요");
        for (int i = 0; i < cc.pins.size(); i++) {
            int index = i;
            DdingMateConfig.Pin pin = cc.pins.get(i);
            PinButton pick = new PinButton(index, pin, b -> {
                savedScroll = list.getScrollY();
                client.setScreen(new RecipePickerScreen(this, index));
            });
            list.withUnit(pick, craftsField(pin), "개");
        }
        list.header("표시와 알림");
        list.pair(toggle("카운터 HUD", cc.enabled, v -> cc.enabled = v),
                toggle("가방도 합산", cc.includeBags, v -> cc.includeBags = v));
        list.pair(new Widgets.Cycle<>(half(), "카운터 위치", List.of(DdingMateConfig.Corner.values()), cc.corner,
                        DdingMateScreen::cornerName, v -> cc.corner = v),
                toggle("다 모이면 알림", cc.notifyComplete, v -> cc.notifyComplete = v));
        Widgets.Toggle expand = toggle("원재료까지 펼치기", cc.expandSubMaterials, v -> cc.expandSubMaterials = v);
        expand.setTooltip(Tooltip.of(Text.literal("모자란 재료가 다른 레시피로 만드는 것이면(예: 조약돌 뭉치 = 조약돌 64개) 모자란 만큼 필요한 원재료를 아래에 보여 줘요.")));
        Widgets.Toggle storage = toggle("아이템 저장소도 합산", cc.includeStorage, v -> cc.includeStorage = v);
        storage.setTooltip(Tooltip.of(Text.literal("아이템 저장소를 열었을 때 슬롯별 보관 개수를 기억해 합산해요. 주운 아이템이 저장소로 바로 들어가면 다음에 열 때까지 반영되지 않아요.")));
        list.pair(expand, storage);

        list.header("모험 가방");
        Widgets.Field aliases = new Widgets.Field(rowWidth(), Text.literal("가방 단축어"));
        aliases.setMaxLength(100);
        aliases.setText(String.join(", ", cc.bagAliases));
        aliases.setTooltip(Tooltip.of(Text.literal("가방을 열 때 보내는 단축어예요. 뒤에 붙는 번호로 몇 번 가방인지 알아요.\n"
                + "예: ㄱ 이면 ㄱ1·/ㄱ1, 가방 이면 /가방 1")));
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
    }

    private void buildGear() {
        DdingMateConfig.Durability dc = cfg.durability;
        list.header("추적할 장비 고르기 · 지금 인벤토리에 있는 내구도 있는 아이템");
        Map<String, ItemStack> found = client != null ? DurabilityTracker.candidates(client) : Map.of();
        if (client == null || client.player == null) {
            list.note("게임에 들어가 있을 때 인벤토리의 장비가 여기에 보여요.");
        } else if (found.isEmpty()) {
            list.note("인벤토리에 내구도가 있는 장비가 없어요.");
        }
        for (Map.Entry<String, ItemStack> e : found.entrySet()) {
            ItemStack s = e.getValue();
            String name = s.getName().getString();
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
        list.pair(toggle("내구도 HUD", dc.enabled, v -> dc.enabled = v),
                new Widgets.Cycle<>(half(), "HUD 위치", List.of(DdingMateConfig.Corner.values()), dc.corner,
                        DdingMateScreen::cornerName, v -> dc.corner = v));
        list.pair(new Widgets.Cycle<>(half(), "부족 알림", List.of(0, 5, 10, 15, 20, 30),
                nearestOf(dc.warnPercent, 0, 5, 10, 15, 20, 30),
                v -> v == 0 ? "끔" : v + "% 이하", v -> dc.warnPercent = v), null);
        list.note("같은 장비가 여러 개면 손에 든 것 → 왼손 → 입은 것 → 단축바 → 나머지 칸 순서로 먼저 찾은 것을 보여 줘요.");
    }

    private void buildTodo() {
        DdingMateConfig.Todo tc = cfg.todo;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        list.header("오늘 할 일 · 체크는 정해진 초기화 시각에 알아서 풀려요");
        for (DdingMateConfig.TodoItem item : tc.items) {
            Widgets.Toggle t = new Widgets.Toggle(rowWidth(), item.name, TodoList.isDone(item, now),
                    v -> TodoList.setDone(item, v)).detail(TodoList.resetName(item.reset));
            Widgets.Button del = new Widgets.Button(44, 20, Text.literal("삭제"), false, b -> {
                tc.items.remove(item);
                rebuild();
            });
            list.withButton(t, del);
        }
        Widgets.Field name = new Widgets.Field(rowWidth() - 120 - 44 - GAP * 2, Text.literal("새 할 일"));
        name.setMaxLength(30);
        name.setPlaceholder(Text.literal("새 할 일 이름"));
        DdingMateConfig.Reset[] chosen = {DdingMateConfig.Reset.DAILY};
        Widgets.Cycle<DdingMateConfig.Reset> reset = new Widgets.Cycle<>(120, "", List.of(DdingMateConfig.Reset.values()),
                chosen[0], TodoList::resetName, v -> chosen[0] = v);
        Widgets.Button add = new Widgets.Button(44, 20, Text.literal("추가"), true, b -> {
            String n = name.getText().trim();
            if (n.isEmpty()) return;
            tc.items.add(new DdingMateConfig.TodoItem(n, chosen[0]));
            rebuild();
        });
        list.row(name, reset, add);

        list.header("표시와 알림");
        list.pair(toggle("할 일 HUD", tc.hudEnabled, v -> tc.hudEnabled = v),
                new Widgets.Cycle<>(half(), "HUD 위치", List.of(DdingMateConfig.Corner.values()), tc.corner,
                        DdingMateScreen::cornerName, v -> tc.corner = v));
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
        list.pair(toggle("보상 구간 알림", pc.enabled, v -> pc.enabled = v),
                toggle("일정 HUD 에 표시", pc.showInHud, v -> pc.showInHud = v));
        list.pair(toggle("23:30 초기화 전 알림", pc.midnightReminder, v -> pc.midnightReminder = v), null);
        list.note("보상 구간을 넘기면 채팅으로 알려 줘요. /접속시간 창을 열면 서버 기록(초 단위)과 받은 보상을 읽어 맞춰요. 보상은 그 창에서 직접 받아야 하고, 자정에 초기화돼요.");
    }

    private void buildLog() {
        List<AlertLog.Entry> entries = AlertLog.entries();
        list.header("최근 알림 " + entries.size() + "개 · 소리·팝업을 꺼 둬도 여기 남아요");
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

    private Widgets.Field craftsField(DdingMateConfig.Pin pin) {
        Widgets.Field f = new Widgets.Field(FIELD_W, Text.literal("만들 개수"));
        f.setMaxLength(4);
        f.setText(String.valueOf(pin.crafts));
        f.setTooltip(Tooltip.of(Text.literal("몇 번 만들지 (1~9999). 필요한 재료 수가 이만큼 곱해져요.")));
        f.setChangedListener(text -> {
            try {
                int v = Integer.parseInt(text.trim());
                if (v >= 1 && v <= 9999) pin.crafts = v;
            } catch (NumberFormatException ignored) {
                // 입력 중인 값은 무시하고 마지막으로 올바른 값을 유지한다
            }
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
        // 제목
        Text t = Theme.title(title);
        int tw = tr.getWidth(t);
        int tx = x + (w - tw) / 2;
        ctx.drawText(tr, t, tx + 1, y + 9, 0x55FFFFFF, false);
        ctx.drawText(tr, t, tx, y + 8, Theme.INK, false);
        // 비공식 꼬리표
        String badge = "비공식";
        int bw = Theme.width(tr, badge) + 6;
        int bx = tx + tw + 5;
        ctx.fill(bx, y + 9, bx + bw, y + 18, Theme.SLATE);
        Theme.text(ctx, tr, badge, bx + 3, y + 10, Theme.WHITE);
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
            Theme.boldText(ctx, textRenderer, s, x + 14, getY() + 6 + dy, on ? 0xFFDCE8FF : Theme.WHITE, true);
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
            Theme.boldText(ctx, textRenderer, n, x + 7 - Theme.boldWidth(textRenderer, n) / 2, getY() + 6, Theme.WHITE, true);
            x += 18;
            RecipeBook.Recipe r = RecipeBook.get(pin.recipe);
            if (r == null) {
                Theme.text(ctx, textRenderer, "비어 있음 · 눌러서 레시피 고르기", x + 2, getY() + 6, Theme.TEXT_SOFT);
                return;
            }
            int shown = 0;
            for (RecipeBook.Material m : r.materials) {
                if (shown == 3) break;
                ItemStack icon = IconCache.forMaterial(m.name);
                if (icon != null) Theme.item(ctx, icon, x, getY() + 2, 16);
                else Theme.unknownIcon(ctx, textRenderer, x + 1, getY() + 3, 14);
                x += 15;
                shown++;
            }
            x += 4;
            String name = Theme.ellipsize(textRenderer, r.displayName(), getX() + width - 6 - x, true);
            Theme.boldText(ctx, textRenderer, name, x, getY() + 6, Theme.TEXT, false);
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

        void note(String text) {
            addEntry(new Row(List.of(), text, RowKind.NOTE));
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

    private enum RowKind { HEADER, NOTE, WIDGETS, UNIT, LABELED, LOG }

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
                    String s = Theme.ellipsize(textRenderer, text, entryWidth - 10, true);
                    Theme.boldText(ctx, textRenderer, s, x + 7, y + 9, Theme.INK, false);
                    ctx.fill(x, y + 20, x + entryWidth, y + 21, Theme.ROW_SHADE);
                    ctx.fill(x, y + 21, x + entryWidth, y + 22, Theme.WINDOW_LIGHT);
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
                    int fw = kind == RowKind.LABELED ? entryWidth - LABEL_W : f.outer();
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
