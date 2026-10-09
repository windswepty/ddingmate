package kr.ddingmate.gui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.notify.Notifier;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Mod Menu 의 설정 버튼, 단축키, /알리미 설정 으로 여는 설정 화면.
 * 띵타이쿤 설정 창처럼 하늘색 머리 띠·파란 분류 탭·회청색 줄·ON/OFF 스위치로 구성한다.
 * 바꾼 값은 바로 적용되고 닫을 때 저장된다.
 */
public class AlarmConfigScreen extends Screen {

    private static final int GAP = 4;
    private static final int UNIT_W = 30;
    private static final int FIELD_W = 64;
    static final int HEADER_H = 26;

    private enum Tab {
        NOTIFY("알림 방식", Items.BELL), EVENTS("일정", Items.CLOCK), CHANNELS("내 채널", Items.COMPASS),
        KEYWORDS("키워드", Items.NAME_TAG), COUNTER("재료", Items.CHEST);

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

    private static Tab lastTab = Tab.NOTIFY;

    private final Screen parent;
    private final AlarmConfig cfg = DdingMate.config();
    private Tab tab = lastTab;
    private ConfigList list;
    /** 레시피 고르기 화면에 다녀와도 보던 위치를 유지한다. */
    private double savedScroll;
    private boolean confirmClearBags;

    private int px, py, pw, ph;
    private int cx, cy, cw, ch;

    public AlarmConfigScreen(Screen parent) {
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
        cy = py + HEADER_H + 30;
        cw = pw - 12;
        ch = ph - (cy - py) - 32;

        Tab[] tabs = Tab.values();
        int tabW = (cw - (tabs.length - 1) * 3) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            TabButton b = new TabButton(tabs[i], tabW);
            b.setX(cx + i * (tabW + 3));
            b.setY(py + HEADER_H + 6);
            addDrawableChild(b);
        }

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
            case NOTIFY -> buildNotify();
            case EVENTS -> buildEvents();
            case CHANNELS -> buildChannels();
            case KEYWORDS -> buildKeywords();
            case COUNTER -> buildCounter();
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
        list.pair(new Widgets.Cycle<>(half(), "일정 HUD 위치", List.of(AlarmConfig.Corner.values()), cfg.hudCorner,
                        AlarmConfigScreen::cornerName, v -> cfg.hudCorner = v),
                new Widgets.Cycle<>(half(), "보일 일정 수", IntStream.rangeClosed(1, 10).boxed().toList(), cfg.hudLines,
                        n -> n + "개", v -> cfg.hudLines = v));
        list.pair(new Widgets.Cycle<>(half(), "HUD 스타일", List.of(AlarmConfig.HudStyle.values()), cfg.hudStyle,
                        AlarmConfigScreen::styleName, v -> cfg.hudStyle = v),
                new Widgets.Cycle<>(half(), "HUD 진하기", List.of(30, 40, 50, 60, 70, 80, 85, 90, 100), nearest(cfg.hudOpacity),
                        v -> v + "%", v -> cfg.hudOpacity = v));
        list.pair(toggle("띵타이쿤 글꼴(갈무리)", cfg.galmuriFont, v -> cfg.galmuriFont = v), null);
        list.note("HUD 는 화면 구석에만 그려요. 서버 HUD·NPC 말풍선이 쓰는 화면 위 가운데는 비워 둬요.");
    }

    private void buildEvents() {
        list.header("일정별 알림 · 오른쪽 칸은 몇 분 전에 알릴지 (쉼표로 여러 개, 0 = 시작할 때)");
        Map<String, EventRule> rules = ScheduleEngine.byId(
                ScheduleEngine.buildRules(new ScheduleEngine.Channels(1, 1, 1, 1, 1)));
        for (Map.Entry<String, String> e : EVENT_NAMES.entrySet()) {
            AlarmConfig.EventSetting s = cfg.events.get(e.getKey());
            EventRule rule = rules.get(e.getKey());
            if (s == null || rule == null) continue;
            Widgets.Toggle t = toggle(e.getValue(), s.enabled, v -> s.enabled = v);
            t.setTooltip(Tooltip.of(Text.literal(rule.detail())));
            list.withUnit(t, leadsField(s), "분 전");
        }
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
        AlarmConfig.Counter cc = cfg.counter;
        list.header("핀 레시피 · 재료를 인벤토리와 가방에서 자동으로 세요");
        for (int i = 0; i < cc.pins.size(); i++) {
            int index = i;
            AlarmConfig.Pin pin = cc.pins.get(i);
            PinButton pick = new PinButton(index, pin, b -> {
                savedScroll = list.getScrollY();
                client.setScreen(new RecipePickerScreen(this, index));
            });
            list.withUnit(pick, craftsField(pin), "개");
        }
        list.header("표시와 알림");
        list.pair(toggle("카운터 HUD", cc.enabled, v -> cc.enabled = v),
                toggle("가방도 합산", cc.includeBags, v -> cc.includeBags = v));
        list.pair(new Widgets.Cycle<>(half(), "카운터 위치", List.of(AlarmConfig.Corner.values()), cc.corner,
                        AlarmConfigScreen::cornerName, v -> cc.corner = v),
                toggle("다 모이면 알림", cc.notifyComplete, v -> cc.notifyComplete = v));

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
        list.note("가방은 열었을 때만 내용을 알 수 있어서, 한 번씩 열어 둔 가방만 합산돼요. 대신 열어 주지는 않아요.");
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

    private static String cornerName(AlarmConfig.Corner c) {
        return switch (c) {
            case TOP_LEFT -> "왼쪽 위";
            case TOP_RIGHT -> "오른쪽 위";
            case BOTTOM_LEFT -> "왼쪽 아래";
            case BOTTOM_RIGHT -> "오른쪽 아래";
        };
    }

    private static String styleName(AlarmConfig.HudStyle s) {
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

    private Widgets.Field leadsField(AlarmConfig.EventSetting s) {
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

    private Widgets.Field craftsField(AlarmConfig.Pin pin) {
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
            String s = Theme.ellipsize(textRenderer, target.title, width - 24, true);
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

    /** 핀 칸: 번호 꼬리표 + 재료 아이콘 몇 개 + 레시피 이름. 누르면 레시피 고르기 화면 */
    private class PinButton extends PressableWidget {
        private final int index;
        private final AlarmConfig.Pin pin;
        private final Consumer<PinButton> action;

        PinButton(int index, AlarmConfig.Pin pin, Consumer<PinButton> action) {
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
    }

    private enum RowKind { HEADER, NOTE, WIDGETS, UNIT, LABELED }

    private class Row extends ElementListWidget.Entry<Row> {
        private static final int LABEL_W = 70;
        private final List<ClickableWidget> widgets;
        private final String text;
        private final RowKind kind;

        Row(List<ClickableWidget> widgets, String text, RowKind kind) {
            this.widgets = widgets;
            this.text = text;
            this.kind = kind;
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
                    int fw = kind == RowKind.LABELED ? entryWidth - LABEL_W : FIELD_W;
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
