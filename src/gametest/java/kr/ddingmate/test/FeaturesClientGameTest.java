package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.gear.DurabilityTracker;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.gui.HudEditorScreen;
import kr.ddingmate.gui.RecipePickerScreen;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.hud.HudLayout;
import kr.ddingmate.ui.Widgets;
import kr.ddingmate.notify.AlertLog;
import kr.ddingmate.notify.KeywordWatcher;
import kr.ddingmate.playtime.PlaytimeTracker;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.todo.TodoList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 장비 내구도·오늘 할 일·원재료 펼치기·알림 기록함·아이템 저장소·접속 시간 보상을 싱글 월드에서 확인한다.
 */
public class FeaturesClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // 초기화 시각 계산(게임 밖에서도 확인 가능한 순수 계산)
        ZonedDateTime fri2am = ZonedDateTime.of(2026, 10, 9, 2, 0, 0, 0, ScheduleEngine.KST);
        check(TodoList.lastReset(DdingMateConfig.Reset.DAILY, fri2am).equals(fri2am.minusDays(1).withHour(3)),
                "매일 03시 초기화 계산 오류");
        check(TodoList.lastReset(DdingMateConfig.Reset.WEEKLY, fri2am).equals(ZonedDateTime.of(2026, 10, 7, 3, 0, 0, 0, ScheduleEngine.KST)),
                "수요일 03시 초기화 계산 오류");
        check(TodoList.lastReset(DdingMateConfig.Reset.WEEKLY, fri2am.minusDays(2).withHour(2))
                        .equals(ZonedDateTime.of(2026, 9, 30, 3, 0, 0, 0, ScheduleEngine.KST)),
                "수요일 03시 직전 주간 초기화 계산 오류");
        check(TodoList.lastReset(DdingMateConfig.Reset.MIDNIGHT, fri2am).equals(fri2am.withHour(0)), "자정 초기화 계산 오류");

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(c -> {
                DdingMate.config().tutorialDone = true;
                DdingMate.config().opened = true;
                BagTracker.clearCurrent();
                AlertLog.clear();
                DdingMateConfig cfg = DdingMate.config();
                cfg.counter.pins.get(0).recipe = "강화 제작 시설/하급 라이프스톤";
                cfg.counter.pins.get(0).crafts = 2;
                cfg.counter.pins.get(1).recipe = "";
                cfg.counter.pins.get(2).recipe = "";
                cfg.counter.expandSubMaterials = true;
                cfg.counter.includeStorage = true;
                cfg.durability.enabled = true;
                cfg.durability.warnPercent = 10;
                cfg.durability.tracked.clear();
                cfg.todo.hudEnabled = true;
                for (DdingMateConfig.TodoItem t : cfg.todo.items) t.doneAt = 0;
                cfg.playtime.enabled = true;
                cfg.playtime.showInHud = true;
            });

            // 2) 장비 내구도: 다이아 곡괭이 최대 1561, 1500 닳음 → 61 남음(4%) → 부족 알림
            sp.getServer().runCommand("give @a minecraft:diamond_pickaxe[minecraft:damage=1500]");
            sp.getServer().runCommand("give @a minecraft:iron_sword[minecraft:damage=50]");
            sp.getServer().runCommand("give @a minecraft:cobblestone 100");
            ctx.waitTicks(20);
            ctx.runOnClient(c -> {
                check(DurabilityTracker.candidates(c).size() == 2, "내구도 장비 후보가 2개가 아님");
                DurabilityTracker.setTracked("v:minecraft:diamond_pickaxe", "다이아몬드 곡괭이", true);
                DurabilityTracker.setTracked("v:minecraft:iron_sword", "철 검", true);
            });
            ctx.waitTicks(20);
            ctx.runOnClient(c -> {
                List<DurabilityTracker.Row> rows = DurabilityTracker.rows();
                check(rows.size() == 2, "내구도 HUD 줄 수가 2가 아님");
                check(rows.get(0).durability().left() == 61 && rows.get(0).durability().max() == 1561,
                        "곡괭이 내구도가 61/1561 이 아님: " + rows.get(0).durability());
                check(hasLog("내구도", "다이아몬드 곡괭이"), "내구도 부족 알림이 기록되지 않음");
                check(!hasLog("내구도", "철 검"), "멀쩡한 철 검에 내구도 알림이 울림");
            });

            // 3) 오늘 할 일: 알아서 세는 것만. 서버 메시지로 세고, 다 채우면 더 세지 않고, 지난 초기화 이전 기록은 0
            ctx.runOnClient(c -> {
                ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
                DdingMateConfig.Todo tc = DdingMate.config().todo;
                tc.items = new java.util.ArrayList<>(DdingMateConfig.Todo.defaults());
                check(tc.items.size() == kr.ddingmate.todo.TodoPreset.values().length, "기본 할 일이 전부가 아님");
                check(tc.items.stream().noneMatch(t -> t.name.contains("출석")), "출석 체크가 남아 있음");
                DdingMateConfig.TodoItem daily = item("daily_quest"), weekly = item("weekly_quest"), trade = item("trade");
                check(daily.target == 5 && weekly.target == 3, "의뢰 목표가 일일 5·주간 3 이 아님: " + daily.target + "/" + weekly.target);
                check(TodoList.label(daily, now).equals("일일 의뢰 · 의뢰 창을 열어 주세요!"), "창을 안 열었는데 안내가 아님: " + TodoList.label(daily, now));
                TodoList.setExact(kr.ddingmate.todo.TodoPreset.DAILY_QUEST, 0);
                TodoList.setExact(kr.ddingmate.todo.TodoPreset.WEEKLY_QUEST, 0);
                check(TodoList.label(daily, now).equals("일일 의뢰 [0/5]"), "표시가 [0/5] 가 아님: " + TodoList.label(daily, now));
                int before = TodoList.remaining(now).size();
                TodoList.onChat("ꀂ\uE001 무역 아이템을 12,345골드에 판매하였습니다.");
                check(TodoList.isDone(trade, now) && TodoList.remaining(now).size() == before - 1, "무역 메시지로 체크되지 않음");
                // 의뢰: 주간 의뢰 창이 열려 있지 않으면 일일 의뢰, 열려 있었으면 주간 의뢰
                TodoList.QuestContext.debugSet(false);
                for (int i = 0; i < 7; i++) TodoList.onChat("\uE001 의뢰를 완료하였습니다.");
                check(TodoList.progress(daily, now) == 5 && TodoList.isDone(daily, now), "일일 의뢰를 5번에서 멈추지 않음: " + TodoList.progress(daily, now));
                TodoList.QuestContext.debugSet(true);
                TodoList.onChat("\uE001 의뢰를 완료하였습니다.");
                check(TodoList.progress(weekly, now) == 1, "주간 의뢰 창에서 완료한 것을 주간으로 세지 않음");
                TodoList.QuestContext.debugSet(false);
                TodoList.onChat("\uE001 의뢰를 완료하지 않았습니다.");
                TodoList.onChat("[칭호] 누군가 : 의뢰를 완료하였습니다.");
                check(TodoList.progress(weekly, now) == 1, "관계없는 메시지로 셈");
                // 지난 초기화 이전에 센 것은 0
                daily.doneAt = TodoList.lastReset(daily.reset, now).minusMinutes(1).toInstant().toEpochMilli();
                check(TodoList.progress(daily, now) == 0, "지난 초기화 이전 기록이 0 으로 돌아가지 않음");
                // 스태미나 소모(3,000): 의뢰 트래커 줄·보상 안내 숫자로 맞추고, 보상을 받으면 다 채움. 자주 바뀌어 다 채울 때만 채팅
                DdingMateConfig.TodoItem stamina = item("stamina");
                check(stamina.target == 3000 && TodoList.label(stamina, now).equals("스태미나 소모하기 (0/3,000)"),
                        "스태미나 표시가 다름: " + TodoList.label(stamina, now));
                int logBeforeSt = AlertLog.entries().size();
                c.inGameHud.getChatHud().clear(false);
                TodoList.debugSidebarLine("스태미나 소모하기 (1,200/100)");
                check(TodoList.progress(stamina, now) == 1200 && TodoList.label(stamina, now).equals("스태미나 소모하기 (1,200/3,000)"),
                        "의뢰 트래커 줄로 못 맞춤: " + TodoList.label(stamina, now));
                TodoList.onChat("오늘 스태미나 소모량이 부족합니다. (2,940 / 3,000)");
                check(TodoList.progress(stamina, now) == 2940, "보상 안내 숫자로 못 맞춤: " + TodoList.progress(stamina, now));
                TodoList.onChat("[칭호] 누군가 : 오늘 스태미나 소모량이 부족합니다. (3,000 / 3,000)");
                check(TodoList.progress(stamina, now) == 2940, "사람이 쓴 채팅으로 맞춤");
                TodoList.onChat("오늘 스태미나 3,000 소모 보상으로 빙고 티켓 10개를 지급받았습니다!");
                check(TodoList.isDone(stamina, now) && TodoList.label(stamina, now).equals("스태미나 소모하기 (3,000/3,000)"), "보상 받았는데 다 안 채움");
                // 예전 설정(할 일 목록 v3)에도 스태미나를 넣어 준다
                tc.items.removeIf(t -> "stamina".equals(t.preset));
                tc.version = 3;
                DdingMate.config().sanitize();
                check(tc.version == 4 && tc.items.stream().anyMatch(t -> "stamina".equals(t.preset)), "예전 목록에 스태미나를 안 넣음");
                // 접속 보상: /접속시간 창의 받은 보상 수로 맞추고 줄이지 않는다
                TodoList.sync(kr.ddingmate.todo.TodoPreset.PLAYTIME, 4);
                check(TodoList.progress(item("playtime"), now) == 4, "받은 보상 수로 맞추지 않음");
                TodoList.sync(kr.ddingmate.todo.TodoPreset.PLAYTIME, 2);
                check(TodoList.progress(item("playtime"), now) == 4, "받은 보상 수가 줄어듦");
                TodoList.onChat("\uE001 5시간 접속 보상을 수령하였습니다.");
                check(TodoList.progress(item("playtime"), now) == 5, "보상 수령 메시지로 세지 않음");
            });

            // 3-1) 예전 설정 정리: 같은 할 일은 센 값을 지키며 바꾸고, 출석 체크·직접 만든 할 일은 뺀다. 이름·목표는 고칠 수 없다
            ctx.runOnClient(c -> {
                DdingMateConfig old = new DdingMateConfig();
                DdingMateConfig.TodoItem q = new DdingMateConfig.TodoItem("의뢰 완료", DdingMateConfig.Reset.DAILY, 3);
                q.progress = 2;
                q.doneAt = System.currentTimeMillis();
                old.todo.items = new java.util.ArrayList<>(List.of(q,
                        new DdingMateConfig.TodoItem("출석 체크", DdingMateConfig.Reset.MIDNIGHT, 1),
                        new DdingMateConfig.TodoItem("내가 만든 일", DdingMateConfig.Reset.DAILY, 10),
                        new DdingMateConfig.TodoItem("오션오더", DdingMateConfig.Reset.DAILY, 10)));
                old.todo.version = 2;
                old.sanitize();
                // 남는 것: 일일 의뢰(센 값 유지) + 0.3.2 에 새로 넣어 주는 스태미나 소모하기
                check(old.todo.items.size() == 2 && old.todo.items.get(1).preset.equals("stamina"),
                        "알아서 못 세는 할 일이 남음: " + old.todo.items.stream().map(t -> t.name).toList());
                DdingMateConfig.TodoItem kept = old.todo.items.get(0);
                check(kept.preset.equals("daily_quest") && kept.name.equals("일일 의뢰") && kept.target == 5 && kept.progress == 2,
                        "의뢰 완료가 일일 의뢰(센 값 유지)로 바뀌지 않음: " + kept.name + " " + kept.target + " " + kept.progress);
                kept.target = 99;
                kept.name = "고친 이름";
                old.sanitize();
                check(kept.target == 5 && kept.name.equals("일일 의뢰"), "할 일 이름·목표를 고칠 수 있음");
            });

            // 3-2) 할 일 탭: 줄을 눌러도 바뀌지 않고, [빼기]로 빼고, '넣을 수 있는 할 일'에서 다시 넣는다
            ctx.runOnClient(c -> {
                c.setScreen(new DdingMateScreen(null));
                ((DdingMateScreen) c.currentScreen).openTabNamed("할 일");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-0b-todo-tab");
            ctx.runOnClient(c -> {
                ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
                DdingMateConfig.TodoItem recommend = item("recommend");
                int before = TodoList.progress(recommend, now);
                var widgets = UiSmokeClientGameTest.widgets(c.currentScreen);
                widgets.stream().filter(w -> w.getMessage().getString().equals("마인리스트 추천"))
                        .findFirst().orElseThrow().onClick(10, 10);
                check(TodoList.progress(recommend, now) == before, "할 일 줄을 눌렀는데 값이 바뀜");
                int n = DdingMate.config().todo.items.size();
                widgets.stream().filter(w -> w instanceof Widgets.Button b && b.getMessage().getString().equals("빼기"))
                        .findFirst().orElseThrow().onClick(10, 10);
                check(DdingMate.config().todo.items.size() == n - 1, "[빼기]로 빠지지 않음");
            });
            ctx.waitTicks(2);
            ctx.runOnClient(c -> {
                int n = DdingMate.config().todo.items.size();
                UiSmokeClientGameTest.widgets(c.currentScreen).stream()
                        .filter(w -> w instanceof Widgets.Button b && b.getMessage().getString().startsWith("＋ "))
                        .findFirst().orElseThrow().onClick(10, 10);
                check(DdingMate.config().todo.items.size() == n + 1, "넣을 수 있는 할 일에서 넣어지지 않음");
                c.setScreen(null);
            });
            ctx.waitTicks(2);

            // 7-1) 아이콘 기억: 다른 창(제작 시설 등)에서 본 서버 아이템 모양을 기억하고, 파일에 남아 다시 켜도 그대로,
            //      내 인벤토리에서 다시 보면 그 모양으로 바뀐다
            ctx.runOnClient(c -> {
                net.minecraft.util.Identifier m1 = net.minecraft.util.Identifier.of("ddingtest", "icon_a");
                net.minecraft.util.Identifier m2 = net.minecraft.util.Identifier.of("ddingtest", "icon_b");
                ItemStack a = new ItemStack(Items.PAPER);
                a.set(DataComponentTypes.CUSTOM_NAME, Text.literal("시험용 서버 아이템"));
                a.set(DataComponentTypes.ITEM_MODEL, m1);
                a.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("설명은 기억하지 않음"))));
                kr.ddingmate.counter.IconCache.rememberSeen(a);
                // 바닐라 재료는 같은 이름의 다른 칸(실제: 서버 창의 "구리 블록" = 산화된 구리 격자)을 배워도 바닐라 그림 그대로
                ItemStack grate = new ItemStack(Items.WAXED_OXIDIZED_COPPER_GRATE);
                grate.set(DataComponentTypes.CUSTOM_NAME, Text.literal("구리 블록"));
                kr.ddingmate.counter.IconCache.rememberSeen(grate);
                ItemStack copper = kr.ddingmate.counter.IconCache.forMaterial("구리 블록");
                check(copper != null && copper.isOf(Items.COPPER_BLOCK), "구리 블록 그림이 바닐라 구리 블록이 아님: " + copper);
                ItemStack got = kr.ddingmate.counter.IconCache.forMaterial("시험용 서버 아이템");
                check(got != null && m1.equals(got.get(DataComponentTypes.ITEM_MODEL)), "창에서 본 아이템 모양을 기억하지 못함");
                check(got.get(DataComponentTypes.LORE) == null || got.get(DataComponentTypes.LORE).lines().isEmpty(), "설명까지 기억함");
                kr.ddingmate.counter.IconCache.reloadForTest();
                got = kr.ddingmate.counter.IconCache.forMaterial("시험용 서버 아이템");
                check(got != null && m1.equals(got.get(DataComponentTypes.ITEM_MODEL)), "다시 불러온 뒤 아이콘이 사라짐");
                ItemStack b = a.copy();
                b.set(DataComponentTypes.ITEM_MODEL, m2);
                kr.ddingmate.counter.IconCache.rememberSeen(b);
                check(m1.equals(kr.ddingmate.counter.IconCache.forMaterial("시험용 서버 아이템").get(DataComponentTypes.ITEM_MODEL)),
                        "다른 창에서 본 것이 이미 아는 모양을 덮어씀");
                kr.ddingmate.counter.IconCache.remember(b);
                check(m2.equals(kr.ddingmate.counter.IconCache.forMaterial("시험용 서버 아이템").get(DataComponentTypes.ITEM_MODEL)),
                        "인벤토리에서 본 모양으로 바뀌지 않음");
            });

            // 8) 원재료 펼치기: 조약돌 뭉치 4개 필요(0개) → 조약돌 256개 필요, 인벤토리 100개
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                MaterialCounter.Line bundle = line("조약돌 뭉치");
                check(bundle.need() == 4 && bundle.subs().size() == 1, "조약돌 뭉치 아래 원재료가 없음: " + bundle);
                MaterialCounter.Line raw = bundle.subs().get(0);
                check(raw.name().equals("조약돌") && raw.need() == 256 && raw.inventory() == 100,
                        "원재료 조약돌 계산 오류: " + raw);
            });
            ctx.takeScreenshot("features-1-hud-all");

            // 11) 아이템 저장소: 설명 문구의 보관 개수(1,234)를 읽어 합산
            ctx.runOnClient(c -> c.setScreen(storageScreen(c)));
            ctx.waitTicks(20);
            ctx.runOnClient(c -> check(BagTracker.storageOpen(), "아이템 저장소 창을 알아보지 못함"));
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                MaterialCounter.Line copper = line("구리 블록");
                check(copper.storage() == 1234, "저장소 구리 블록이 1234 가 아님: " + copper);
                check(copper.done(), "저장소까지 합쳐 구리 블록 16개가 채워져야 함");
            });

            // 12) 접속 시간: 59분 58초에서 시작 → 1시간을 넘기면 채팅·기록에 보상 알림
            ctx.runOnClient(c -> PlaytimeTracker.debugSetSeconds(3598));
            ctx.waitTicks(80);
            ctx.runOnClient(c -> {
                check(PlaytimeTracker.seconds() >= 3600, "접속 시간이 늘지 않음: " + PlaytimeTracker.seconds());
                check(hasLog("접속", "접속 1시간"), "접속 1시간 보상 알림이 기록되지 않음");
            });
            ctx.takeScreenshot("features-2-playtime-alert");

            // 12-1) /접속시간 창을 열면 서버 기록(11시간 49분 33초)과 받은 보상(1시간)에 맞추고, 지난 구간 알림은 한꺼번에 울리지 않는다
            int[] before = new int[1];
            ctx.runOnClient(c -> {
                before[0] = (int) AlertLog.entries().stream().filter(e -> e.kind.equals("접속")).count();
                c.setScreen(playtimeScreen(c));
            });
            ctx.waitTicks(25);
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                long s = PlaytimeTracker.seconds();
                check(s >= 42573 && s <= 42573 + 5, "서버 기록에 맞추지 못함: " + s);
                check(PlaytimeTracker.syncedAt() > 0, "서버 기록에 맞춘 시각이 없음");
                check(List.of(2, 3, 4, 5, 8).equals(PlaytimeTracker.unclaimedRewards()),
                        "안 받은 보상 계산 오류: " + PlaytimeTracker.unclaimedRewards());
                long after = AlertLog.entries().stream().filter(e -> e.kind.equals("접속")).count();
                check(after == before[0], "맞춘 뒤 지난 구간 알림이 한꺼번에 울림");
            });
            ctx.takeScreenshot("features-2b-playtime-synced");

            // 12-2) 게임 서버에 들어가면 서버가 채팅으로 보내는 일일 접속 시간에 맞춘다. 남이 채팅으로 흉내 낸 줄은 무시
            ctx.runOnClient(c -> {
                PlaytimeTracker.onChat("\uE001\uA002├ 일일 접속 시간 : 16시간 37분 57초");
                check(PlaytimeTracker.seconds() == 16 * 3600 + 37 * 60 + 57, "서버 채팅의 일일 접속 시간으로 맞추지 않음: " + PlaytimeTracker.seconds());
                PlaytimeTracker.onChat("[칭호] 누군가 : ├ 일일 접속 시간 : 1시간 0분 0초");
                check(PlaytimeTracker.seconds() >= 16 * 3600, "다른 사람이 쓴 채팅으로 접속 시간이 바뀜");
                check(PlaytimeTracker.syncedAt() > 0, "채팅으로 맞춘 뒤에도 맞춘 시각이 없음");
            });

            // 12-3) 서버 아이템 이름의 등급 그림 글자·강화 수치는 빼고 비교한다(아이콘·개수 세기 공통)
            ctx.runOnClient(c -> {
                check(ItemKeys.normalize("\uA006세이지 곡괭이 [+11]").equals("세이지곡괭이"),
                        "그림 글자·강화 수치를 지우지 않음: " + ItemKeys.normalize("\uA006세이지 곡괭이 [+11]"));
                check(ItemKeys.normalize("\uDB80\uDC00§6[강화석] 코룸 주괴").equals("코룸주괴"), "사용자 정의 글자·분류를 지우지 않음");
                check(ItemKeys.rekey("n:\uA006네더라이트 검").equals("n:네더라이트검"), "예전 키를 다시 만들지 않음");
                ItemStack named = new ItemStack(Items.PAPER);
                named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("\uA006치즈 조각"));
                check(ItemKeys.keyOf(named).equals(ItemKeys.nameKey("치즈 조각")), "그림 글자가 붙은 아이템을 레시피 재료와 같은 것으로 보지 않음");
            });

            // 12-4) 화면 위쪽 보스바의 "에버그린 마을 (마을30)" 을 읽어 지금 채널을 알고, 그 지역의 내 채널을 맞춘다
            ctx.runOnClient(c -> {
                DdingMate.config().autoChannel = true;
                DdingMate.config().villageChannel = 7;
                java.util.UUID id = java.util.UUID.randomUUID();
                var bars = ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars();
                bars.put(id, new net.minecraft.client.gui.hud.ClientBossBar(id,
                        Text.literal("\uE010 2026.10.09 17:06 에버그린 마을 (마을30)"), 1f,
                        net.minecraft.entity.boss.BossBar.Color.YELLOW, net.minecraft.entity.boss.BossBar.Style.PROGRESS, false, false, false));
                kr.ddingmate.game.ServerHud.tick(c);
                bars.remove(id);
                check(kr.ddingmate.game.ServerHud.area() == kr.ddingmate.game.ServerHud.Area.VILLAGE
                        && kr.ddingmate.game.ServerHud.channel() == 30, "보스바의 채널 표시를 읽지 못함");
                check(DdingMate.config().villageChannel == 30, "지역별 내 채널을 지금 채널로 맞추지 않음: " + DdingMate.config().villageChannel);
                check(kr.ddingmate.game.ServerHud.inGame(), "싱글플레이·채널 표시가 있는데 게임 밖으로 봄");
                DdingMate.config().autoChannel = false;
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.WILD, 3);
                check(DdingMate.config().wildChannel != 3, "자동 인식을 껐는데 채널이 바뀜");
                DdingMate.config().autoChannel = true;
                // 서버 재부팅 예고(실제 로그 문구) → 지금 채널 남은 시간, 유저 채팅 흉내는 무시, 채널을 옮기면 지움
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.ISLAND, 7);
                kr.ddingmate.game.ServerHud.onChat("[칭호] 누군가 : [서버 리붓] 3분 뒤 현재 채널이 재부팅됩니다.");
                check(kr.ddingmate.game.ServerHud.rebootAt() == 0, "유저 채팅으로 재부팅 예고를 받음");
                kr.ddingmate.game.ServerHud.onChat("ꀂꀁ[서버 리붓] 3분 뒤 현재 채널이 재부팅됩니다.");
                long left = kr.ddingmate.game.ServerHud.rebootAt() - System.currentTimeMillis();
                check(left > 170_000 && left <= 180_000, "재부팅 예고를 3분으로 읽지 못함: " + left);
                // 같은 재시작이 시간표 줄과 예고 줄로 두 번 보이지 않는다(실제: "마을 35채널 재부팅 18분 8초" + "마을 35채널 재시작 18분")
                kr.ddingmate.schedule.EventRule vr = DdingMate.rules().stream().filter(r -> r.id().equals("village_reboot")).findFirst().orElse(null);
                if (vr != null) {
                    long at = kr.ddingmate.game.ServerHud.rebootAt();
                    java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(at + 8_000).atZone(kr.ddingmate.schedule.ScheduleEngine.KST);
                    check(kr.ddingmate.hud.ScheduleHud.sameRestart(new kr.ddingmate.schedule.ScheduleEngine.Occurrence(vr, t), at), "예고와 같은 재시작을 따로 보임");
                    check(!kr.ddingmate.hud.ScheduleHud.sameRestart(new kr.ddingmate.schedule.ScheduleEngine.Occurrence(vr, t.plusHours(6)), at), "다른 시각 재시작까지 숨김");
                }
                check(!kr.ddingmate.game.ServerHud.inVillageChannel(30), "아일랜드에 있는데 마을 채널 안으로 봄");
                // 아일랜드 왼쪽 위는 서버 프로필(얼굴·체력) 아래부터, 다른 구석·다른 지역은 그대로
                check(kr.ddingmate.hud.HudLayout.debugTop(DdingMateConfig.Corner.TOP_LEFT) >= 55, "아일랜드에서 서버 프로필을 피하지 않음");
                check(kr.ddingmate.hud.HudLayout.debugTop(DdingMateConfig.Corner.TOP_RIGHT) == 16, "아일랜드 오른쪽 위 여백이 바뀜");
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.VILLAGE, 30);
                check(kr.ddingmate.game.ServerHud.rebootAt() == 0, "채널을 옮겼는데 이전 채널 재부팅 예고가 남음");
                check(kr.ddingmate.hud.HudLayout.debugTop(DdingMateConfig.Corner.TOP_LEFT) == 16, "마을에서도 프로필 여백을 둠");
            });
            ctx.runOnClient(c -> {
                c.setScreen(new DdingMateScreen(null));
                ((DdingMateScreen) c.currentScreen).openTabNamed("일정");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-2c-channels-auto");
            ctx.runOnClient(c -> c.setScreen(null));

            // 12-5) 다 한 할 일은 HUD 체크 상자에 체크 표시
            ctx.runOnClient(c -> {
                DdingMate.config().todo.items = new java.util.ArrayList<>(DdingMateConfig.Todo.defaults());
                DdingMate.config().todo.hudEnabled = true;
                TodoList.onChat("\uE001 마인리스트 추천 보상 우편이 도착했습니다. [/우편함]에서 확인할 수 있습니다.");
                TodoList.QuestContext.debugSet(false);
                TodoList.onChat("\uE001 의뢰를 완료하였습니다.");
                check(TodoList.isDone(item("recommend"), ZonedDateTime.now(ScheduleEngine.KST)), "추천 메시지로 체크되지 않음");
            });
            ctx.waitTicks(25);
            ctx.takeScreenshot("features-2d-todo-checked");

            // 모든 레시피를 하나씩 핀해 재료 줄이 레시피대로 나오는지, 검색으로 찾을 수 있는지 확인
            ctx.runOnClient(c -> {
                DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(0);
                String keep = pin.recipe;
                int keepCrafts = pin.crafts;
                java.util.Set<String> facilities = new java.util.TreeSet<>();
                for (RecipeBook.Recipe r : RecipeBook.all()) {
                    facilities.add(r.facility);
                    check(!r.materials.isEmpty(), "재료 없는 레시피: " + r.id);
                    pin.recipe = r.id;
                    pin.crafts = 3;
                    MaterialCounter.tick(c);
                    MaterialCounter.PinView v = MaterialCounter.views().get(0);
                    check(v.recipe().id.equals(r.id) && v.lines().size() == r.materials.size(), "핀 결과가 레시피와 다름: " + r.id);
                    for (int i = 0; i < r.materials.size(); i++) {
                        check(v.lines().get(i).need() == r.materials.get(i).count * 3, "필요 개수 계산 오류: " + r.id);
                    }
                    check(RecipePickerScreen.matches(r, ItemKeys.normalize(r.name)), "검색으로 못 찾음: " + r.id);
                }
                check(RecipeBook.all().size() >= 386, "레시피 수가 줄어듦: " + RecipeBook.all().size());
                check(facilities.size() == 17, "시설 수가 17개가 아님: " + facilities);
                pin.recipe = keep;
                pin.crafts = keepCrafts;
                MaterialCounter.tick(c);
            });

            // 만들 개수: 레시피 고르기에서 [−64]·[+64]·[+10]·[−10]·[−] 로 75 를 만든 뒤 하급 라이프스톤을 누르면 ×75 로 핀
            ctx.runOnClient(c -> {
                DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(1);
                pin.recipe = "";
                pin.crafts = 1;
                pin.byOutput = false;
                c.setScreen(new RecipePickerScreen(null, 1));
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                press(c, "−64"); // 1 에서 줄여도 1 아래로 내려가지 않는다
                press(c, "+64");
                press(c, "+10");
                press(c, "+");
                press(c, "−");
                press(c, "+64");
                press(c, "−64"); // 139 → 75
                press(c, "−10");
                press(c, "+10"); // 65 → 75
                ElementListWidget<?> list = (ElementListWidget<?>) c.currentScreen.children().stream()
                        .filter(e -> e instanceof ElementListWidget<?>).findFirst().orElseThrow();
                c.currentScreen.children().stream().filter(e -> e instanceof net.minecraft.client.gui.widget.TextFieldWidget)
                        .map(e -> (net.minecraft.client.gui.widget.TextFieldWidget) e)
                        .filter(f -> f.getText().isEmpty() || !f.getText().matches("\\d+")).findFirst()
                        .ifPresent(f -> f.setText("하급 라이프"));
                check(list.children().size() == 1, "검색 결과가 하급 라이프스톤 하나가 아님: " + list.children().size());
                ((net.minecraft.client.gui.ParentElement) list.children().get(0)).children().stream()
                        .filter(e -> e instanceof net.minecraft.client.gui.widget.PressableWidget)
                        .map(e -> (net.minecraft.client.gui.widget.PressableWidget) e).findFirst().orElseThrow().onPress();
                DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(1);
                check(pin.recipe.equals("강화 제작 시설/하급 라이프스톤") && pin.crafts == 75 && !pin.byOutput,
                        "레시피 고르기에서 정한 개수로 핀되지 않음: " + pin.recipe + " ×" + pin.crafts);
                MaterialCounter.tick(c);
                MaterialCounter.Line copper = MaterialCounter.views().get(1).lines().stream()
                        .filter(l -> l.name().equals("구리 블록")).findFirst().orElseThrow();
                check(copper.need() == 8 * 75, "×75 인데 구리 블록이 600 이 아님: " + copper.need());
                // 결과물 기준: 깐 새우 x2 레시피로 33개를 얻으려면 17번 → 새우 17개, 제목은 "깐 새우 34개 · 17번"
                pin.recipe = "대형 제작대/깐 새우 x2";
                pin.crafts = 33;
                pin.byOutput = true;
                MaterialCounter.tick(c);
                MaterialCounter.PinView v = MaterialCounter.views().get(1);
                check(v.lines().get(0).need() == 17, "결과물 33개 기준 필요 새우가 17 이 아님: " + v.lines().get(0).need());
                check(MaterialCounter.title(pin, v.recipe()).equals("깐 새우 34개 · 17번"), "제목 오류: " + MaterialCounter.title(pin, v.recipe()));
                pin.byOutput = false;
                MaterialCounter.tick(c);
                check(MaterialCounter.views().get(1).lines().get(0).need() == 33, "번 만들기 기준 필요 새우가 33 이 아님");
                pin.recipe = "";
            });
            ctx.waitTicks(2);

            // 재료 HUD 높이: 편집 화면에서 높이를 줄이면 넘치는 줄은 옆 칸으로(가로로 길고 세로로 짧은 HUD)
            int[] tall = new int[2];
            String[] keepPin0 = new String[1];
            ctx.runOnClient(c -> {
                DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(1);
                pin.recipe = "강화 제작 시설/하급 라이프스톤";
                pin.crafts = 3;
                MaterialCounter.tick(c);
                var views = MaterialCounter.views();
                // 높이 제한이 넉넉하면 핀마다 한 칸, 낮으면 같은 사각형 안에서 줄이 옆 칸으로(제목은 한 번, "이어서" 없음)
                var roomy = kr.ddingmate.hud.CounterHud.blocks(views, 1000, false);
                check(roomy.stream().allMatch(b -> b.cols() == 1), "높이가 넉넉한데 옆 칸으로 나뉨");
                var narrow = kr.ddingmate.hud.CounterHud.blocks(views, 70, false);
                check(narrow.size() == views.size(), "핀 하나가 사각형 여러 개로 나뉨: " + narrow.size() + "/" + views.size());
                for (var b : narrow) {
                    int rows = b.view().lines().size() + b.view().lines().stream().mapToInt(l -> l.subs().size()).sum();
                    check(b.cols() * b.perCol() >= rows && (b.cols() - 1) * b.perCol() < rows, "칸 수가 줄 수와 안 맞음: " + b);
                }
                check(narrow.stream().anyMatch(b -> b.cols() > 1), "낮은 높이에서 줄이 옆 칸으로 안 넘어감");
                var r = HudLayout.bounds().get(HudLayout.COUNTER);
                tall[0] = r == null ? 0 : r.w();
                tall[1] = r == null ? 0 : r.h();
                HudLayout.setHeight(HudLayout.COUNTER, 70);
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-2f-counter-short-wide");
            ctx.runOnClient(c -> {
                var r = HudLayout.bounds().get(HudLayout.COUNTER);
                float sc = HudLayout.scale(HudLayout.COUNTER);
                int screenW = c.getWindow().getScaledWidth();
                // 정한 높이 안에 들어가거나, 화면 너비가 모자라 그만큼만 키웠으면(화면 밖으로 안 나가게) 맞다
                // 핀마다 사각형 높이가 정한 높이(70) 안이고, 핀끼리는 아래로 쌓여 화면 안에 들어간다
                int screenH = c.getWindow().getScaledHeight();
                check(r != null && r.x() + r.w() <= screenW + 1 && r.y() + r.h() <= screenH + 1 && r.h() > Math.round(70 * sc) - 2,
                        "높이를 줄였는데 HUD 가 낮아지지 않음: " + tall[1] + " → " + (r == null ? null : r.h()) + " (너비 " + tall[0] + " → " + (r == null ? null : r.w()) + ")");
            });
            // 실제처럼 왼쪽 위로 옮겨 둔 모습도 남긴다(채팅·다른 HUD 와 안 겹치게)
            ctx.runOnClient(c -> {
                var r = HudLayout.bounds().get(HudLayout.COUNTER);
                if (r != null) HudLayout.setPos(HudLayout.COUNTER, 4, 60, r.w(), r.h(), c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight());
                DdingMate.config().hudEnabled = false;
                // 핀 하나만: 사각형 하나 안에서 재료 줄이 옆 칸으로
                keepPin0[0] = DdingMate.config().counter.pins.get(0).recipe;
                DdingMate.config().counter.pins.get(0).recipe = "";
                MaterialCounter.tick(c);
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                // 핀 하나로 바뀐 크기로 다시 맨 위에 붙인다
                var r = HudLayout.bounds().get(HudLayout.COUNTER);
                if (r != null) HudLayout.setPos(HudLayout.COUNTER, 4, 4, r.w(), r.h(), c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight());
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-2g-counter-short-wide-moved");
            ctx.runOnClient(c -> {
                HudLayout.setHeight(HudLayout.COUNTER, 0);
                DdingMate.config().counter.pins.get(1).recipe = "";
                HudLayout.reset(HudLayout.COUNTER);
                DdingMate.config().hudEnabled = true;
                DdingMate.config().counter.pins.get(0).recipe = keepPin0[0];
                MaterialCounter.tick(c);
            });

            // 형광 모루(위키 형광 광산 '조합법'): 오르딘 미니 망치 ×2 → 재료 6줄, 하위 재료 보기면 오르딘 큐브 밑에 오르딘
            ctx.runOnClient(c -> {
                DdingMateConfig.Counter cc = DdingMate.config().counter;
                boolean keepExpand = cc.expandSubMaterials;
                cc.expandSubMaterials = true;
                DdingMateConfig.Pin pin = cc.pins.get(1);
                pin.recipe = "형광 모루/오르딘 미니 망치";
                pin.crafts = 2;
                pin.byOutput = false;
                MaterialCounter.tick(c);
                MaterialCounter.PinView v = MaterialCounter.views().get(1);
                check(v.recipe().id.equals(pin.recipe) && v.lines().size() == 6, "형광 모루 핀 재료 줄이 6개가 아님: " + v.lines());
                java.util.Map<String, MaterialCounter.Line> by = new java.util.HashMap<>();
                for (MaterialCounter.Line l : v.lines()) by.put(l.name(), l);
                check(by.get("오르딘 큐브").need() == 2 && by.get("사파이어 블록").need() == 2 && by.get("스킬 펄스").need() == 2
                        && by.get("다이아몬드").need() == 8 && by.get("철 블록").need() == 4 && by.get("안산암").need() == 64,
                        "오르딘 미니 망치 ×2 필요 개수 오류: " + v.lines());
                check(by.get("오르딘 큐브").subs().stream().anyMatch(s -> s.name().equals("오르딘") && s.need() == 2),
                        "오르딘 큐브 하위 재료(오르딘 2)가 없음: " + by.get("오르딘 큐브").subs());
                for (String[] r : new String[][]{{"루밀리아 디퓨저 ★", "플로리스트 제조"}, {"오르딘 미니 망치", "형광 모루"}, {"루미트 강철 방패", "형광 모루"},
                        {"크레온 장인 석궁", "형광 모루"}, {"벨릭 사냥 스피어", "형광 모루"}, {"세르칸 초승달 단검", "형광 모루"}}) {
                    RecipeBook.Recipe rr = RecipeBook.recipeFor(r[0]);
                    check(rr != null && rr.facility.equals(r[1]), r[0] + " 레시피 시설이 " + r[1] + " 이 아님: " + (rr == null ? null : rr.facility));
                }
                check(RecipeBook.get("형광 모루/세르칸 초승달 단검").materials.stream().anyMatch(m -> m.name.equals("이끼 블록") && m.count == 24),
                        "세르칸 초승달 단검 재료(이끼 블록 24) 오류");
                // 그림: 제련품·광물 블록은 서버 그림(종이 모델 번호), 안산암·꽁꽁 언 얼음·테라코타·이끼 블록은 바닐라
                Object[][] icons = {{"오르딘 미니 망치", 2058101f}, {"크레온 장인 석궁", 2058102f}, {"벨릭 사냥 스피어", 2058103f},
                        {"세르칸 초승달 단검", 2058104f}, {"루미트 강철 방패", 2058105f},
                        {"플래티넘 블록", 6001009f}, {"사파이어 블록", 6001011f}, {"토파즈 블록", 6001013f}};
                for (Object[] o : icons) {
                    ItemStack s = kr.ddingmate.counter.IconCache.forMaterial((String) o[0]);
                    var data = s == null ? null : s.get(DataComponentTypes.CUSTOM_MODEL_DATA);
                    check(s != null && s.isOf(Items.PAPER) && data != null && data.floats().get(0) == (float) o[1], o[0] + " 그림 오류: " + data);
                }
                check(kr.ddingmate.counter.IconCache.forMaterial("안산암").isOf(Items.ANDESITE)
                        && kr.ddingmate.counter.IconCache.forMaterial("꽁꽁 언 얼음").isOf(Items.PACKED_ICE)
                        && kr.ddingmate.counter.IconCache.forMaterial("테라코타").isOf(Items.TERRACOTTA)
                        && kr.ddingmate.counter.IconCache.forMaterial("이끼 블록").isOf(Items.MOSS_BLOCK), "바닐라 블록 그림 오류");
                check(RecipeBook.keysFor("꽁꽁 언 얼음").contains("v:minecraft:packed_ice"), "꽁꽁 언 얼음 바닐라 연결 없음");
                cc.expandSubMaterials = keepExpand;
                pin.recipe = "";
                pin.crafts = 1;
                MaterialCounter.tick(c);
                // 인벤토리에 오르딘 큐브(실제 서버 아이템: 종이 2058051)만 있으면 레시피 고르기 맨 위가 오르딘 미니 망치
                var pinv = c.player.getInventory();
                for (int i = 0; i < pinv.size(); i++) pinv.setStack(i, ItemStack.EMPTY);
                ItemStack cube = new ItemStack(Items.PAPER);
                cube.set(DataComponentTypes.CUSTOM_NAME, Text.literal("오르딘 큐브"));
                cube.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                        new net.minecraft.component.type.CustomModelDataComponent(List.of(2058051f), List.of(), List.of(), List.of()));
                pinv.setStack(0, cube);
                c.setScreen(new RecipePickerScreen(null, 1));
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-2e-anvil-suggest");
            ctx.runOnClient(c -> {
                ElementListWidget<?> list = (ElementListWidget<?>) c.currentScreen.children().stream()
                        .filter(e -> e instanceof ElementListWidget<?>).findFirst().orElseThrow();
                ((net.minecraft.client.gui.ParentElement) list.children().get(0)).children().stream()
                        .filter(e -> e instanceof net.minecraft.client.gui.widget.PressableWidget)
                        .map(e -> (net.minecraft.client.gui.widget.PressableWidget) e).findFirst().orElseThrow().onPress();
                DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(1);
                check(pin.recipe.equals("형광 모루/오르딘 미니 망치"), "오르딘 큐브가 있는데 추천 맨 위가 오르딘 미니 망치가 아님: " + pin.recipe);
                // 실제로 세는지: 위키가 '철'로 줄여 쓴 철 주괴(바닐라), 이름이 두 가지인 플래티넘 주괴(서버 아이템)
                var pinv = c.player.getInventory();
                pinv.setStack(0, new ItemStack(Items.IRON_INGOT, 8));
                ItemStack platinum = TestItems.server("플래티늄 주괴", 3);
                pinv.setStack(1, platinum);
                pin.recipe = "도구 제작 시설/육식 동물 덫 x2";
                pin.crafts = 1;
                MaterialCounter.tick(c);
                MaterialCounter.Line iron = MaterialCounter.views().get(1).lines().stream().filter(l -> l.name().equals("철")).findFirst().orElseThrow();
                check(iron.inventory() == 8 && iron.done(), "철 주괴 8개를 '철'로 세지 않음: " + iron);
                pin.recipe = "채광물 가공 시설/(Rare) 제피르 라이터";
                MaterialCounter.tick(c);
                MaterialCounter.Line pl = MaterialCounter.views().get(1).lines().stream().filter(l -> l.name().equals("플래티넘 주괴")).findFirst().orElseThrow();
                check(pl.inventory() == 3, "플래티늄 주괴라는 이름의 아이템을 플래티넘 주괴로 세지 않음: " + pl);
                platinum.set(DataComponentTypes.CUSTOM_NAME, Text.literal("플래티넘 주괴"));
                MaterialCounter.tick(c);
                pl = MaterialCounter.views().get(1).lines().stream().filter(l -> l.name().equals("플래티넘 주괴")).findFirst().orElseThrow();
                check(pl.inventory() == 3, "플래티넘 주괴를 세지 않음: " + pl);
                pin.recipe = "";
                MaterialCounter.tick(c);
                pinv.setStack(1, ItemStack.EMPTY);
                c.player.getInventory().setStack(0, ItemStack.EMPTY);
                c.setScreen(null);
            });
            ctx.waitTicks(2);

            // 키워드 알림: 남이 내 닉네임을 부른 채팅만 언급으로 보고, 내 이름이 든 시스템 공지·내 채팅은 무시
            ctx.runOnClient(c -> {
                String me = c.getSession().getUsername();
                DdingMateConfig.Keywords kw = DdingMate.config().keywords;
                kw.enabled = true;
                kw.includeOwnName = true;
                kw.cooldownSeconds = 0;
                kw.words = new java.util.ArrayList<>(List.of("오션오더", "풍선"));
                AlertLog.clear();
                KeywordWatcher.onMessage(Text.literal(me + "님이 업적을 달성했습니다"), null);
                check(AlertLog.entries().isEmpty(), "시스템 공지의 내 이름에 언급 알림이 울림");
                KeywordWatcher.onMessage(Text.literal("[칭호] " + me + " : 오션오더 왔다"), null);
                check(AlertLog.entries().isEmpty(), "내가 쓴 채팅에 키워드 알림이 울림");
                KeywordWatcher.onMessage(Text.literal("[꼬마송편] kanghan15 : " + me + " 거기 있어?"), null);
                check(AlertLog.entries().size() == 1 && AlertLog.entries().get(0).title.equals("누가 내 닉네임을 불렀어요"),
                        "다른 사람의 닉네임 언급 알림이 없음");
                KeywordWatcher.onMessage(Text.literal("[알림] 풍선이 근처에 도착했어요!"), null);
                check(AlertLog.entries().size() == 2 && AlertLog.entries().get(0).title.equals("채팅에 '풍선'"),
                        "키워드 알림 제목이 다름: " + AlertLog.entries().get(0).title);
                kw.cooldownSeconds = 10;
            });
            ctx.waitTicks(20);
            ctx.takeScreenshot("features-4a-keyword-toast");

            // HUD 크기·서버 HUD 피하기: 50% 면 일정 HUD 가 절반 크기, 위쪽은 서버 재화 바 아래(16px)부터
            int[] full = new int[2];
            ctx.runOnClient(c -> {
                DdingMate.config().hudPos.clear();
                DdingMate.config().avoidServerHud = true;
                DdingMate.config().hudScale = 100;
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.SCHEDULE);
                full[0] = r.w();
                full[1] = r.h();
                check(r.y() >= 16, "서버 재화 바 자리를 가림: y=" + r.y());
                DdingMate.config().hudScale = 50;
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.SCHEDULE);
                check(Math.abs(r.w() - full[0] / 2) <= 2 && Math.abs(r.h() - full[1] / 2) <= 2,
                        "HUD 크기 50% 가 적용되지 않음: " + r + " / " + full[0] + "x" + full[1]);
                check(r.y() >= 16, "줄였을 때 서버 재화 바 자리를 가림: y=" + r.y());
            });
            ctx.takeScreenshot("features-4b-hud-50");
            ctx.runOnClient(c -> DdingMate.config().hudScale = 100);

            // HUD 위치 편집: 일정 HUD 를 화면 가운데로 끌어 [저장] → 그 자리에 그려짐. Esc 는 취소, [초기화]는 구석으로
            ctx.runOnClient(c -> {
                DdingMate.config().hudPos.clear();
                c.setScreen(new HudEditorScreen(null));
            });
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                var b = HudLayout.bounds();
                check(b.keySet().containsAll(HudLayout.NAMES.keySet()), "편집 화면에 HUD 4개가 다 보이지 않음: " + b.keySet());
                HudEditorScreen s = (HudEditorScreen) c.currentScreen;
                HudLayout.Rect r = b.get(HudLayout.SCHEDULE);
                s.mouseClicked(r.x() + 5, r.y() + 5, 0);
                s.mouseDragged(s.width / 2.0, s.height / 2.0, 0, 0, 0);
                s.mouseReleased(s.width / 2.0, s.height / 2.0, 0);
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-4-hud-editor");
            ctx.runOnClient(c -> press(c, "저장"));
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                check(c.currentScreen == null, "저장 후 편집 화면이 닫히지 않음");
                check(HudLayout.custom(HudLayout.SCHEDULE), "옮긴 자리가 저장되지 않음");
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.SCHEDULE);
                int cx = c.getWindow().getScaledWidth() / 2, cy = c.getWindow().getScaledHeight() / 2, sh = c.getWindow().getScaledHeight();
                // 놓은 자리에 그린다. 다만 아래쪽이 핫바·체력 줄 자리에 걸리면 그만큼만 위로 비킨다
                check(Math.abs(r.x() + 5 - cx) <= 2 && (Math.abs(r.y() + 5 - cy) <= 2 || (r.y() + 5 < cy && r.y() + r.h() <= sh - 50)),
                        "옮긴 자리에 그려지지 않음: " + r);
                // 다시 열어 할 일 HUD 를 옮긴 뒤 Esc → 취소
                c.setScreen(new HudEditorScreen(null));
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                HudEditorScreen s = (HudEditorScreen) c.currentScreen;
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.TODO);
                s.mouseClicked(r.x() + 3, r.y() + 3, 0);
                s.mouseDragged(40, 120, 0, 0, 0);
                s.mouseReleased(40, 120, 0);
                s.close();
                check(!HudLayout.custom(HudLayout.TODO), "Esc 로 나갔는데 옮긴 자리가 남음");
                check(HudLayout.custom(HudLayout.SCHEDULE), "취소했는데 저장해 둔 자리까지 사라짐");
                c.setScreen(new HudEditorScreen(null));
            });
            // HUD 별 크기: 재료 HUD 위에서 휠 두 칸 → 120%, 내구도 HUD 모서리 손잡이를 끌어 키우기, [−] 로 줄이기
            int[] counterW = new int[1];
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                HudEditorScreen s = (HudEditorScreen) c.currentScreen;
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.COUNTER);
                counterW[0] = r.w();
                s.mouseScrolled(r.x() + 10, r.y() + 10, 0, 1);
                s.mouseScrolled(r.x() + 10, r.y() + 10, 0, 1);
                check(HudLayout.size(HudLayout.COUNTER) == 120, "휠로 크기가 바뀌지 않음: " + HudLayout.size(HudLayout.COUNTER));
                check(HudLayout.COUNTER.equals(s.selected()), "휠을 굴린 HUD 가 골라지지 않음");
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                HudEditorScreen s = (HudEditorScreen) c.currentScreen;
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.COUNTER);
                check(Math.abs(r.w() - counterW[0] * 1.2) <= 3, "120% 인데 너비가 그만큼 커지지 않음: " + r.w() + " / " + counterW[0]);
                // 편집 패널(화면 아래)에 가리지 않는 HUD 의 손잡이로: 작은 시험 화면에서는 장비 HUD 가 패널 밑에 놓일 수 있다
                HudLayout.Rect d = HudLayout.bounds().get(HudLayout.TODO);
                double hx = d.x() + d.w() - 2, hy = d.y() + d.h() - 2;
                s.mouseClicked(hx, hy, 0);
                s.mouseDragged(hx + d.w() * 0.3, hy, 0, 0, 0);
                s.mouseReleased(hx + d.w() * 0.3, hy, 0);
                check(HudLayout.size(HudLayout.TODO) == 130, "모서리 손잡이로 크기가 바뀌지 않음: " + HudLayout.size(HudLayout.TODO) + " " + d);
                press(c, "−");
                check(HudLayout.size(HudLayout.TODO) == 120, "[−] 로 고른 HUD 가 작아지지 않음");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-4c-hud-sizes");
            ctx.runOnClient(c -> {
                press(c, "저장");
                check(DdingMate.config().hudSizes.get(HudLayout.COUNTER) == 120
                        && DdingMate.config().hudSizes.get(HudLayout.TODO) == 120, "HUD 별 크기가 저장되지 않음");
                // 다시 열어 크기를 바꾼 뒤 Esc → 저장해 둔 크기로
                c.setScreen(new HudEditorScreen(null));
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                HudEditorScreen s = (HudEditorScreen) c.currentScreen;
                HudLayout.Rect r = HudLayout.bounds().get(HudLayout.SCHEDULE);
                s.mouseScrolled(r.x() + 5, r.y() + 5, 0, -1);
                s.close();
                check(HudLayout.size(HudLayout.SCHEDULE) == 100, "Esc 로 나갔는데 크기가 남음");
                check(HudLayout.size(HudLayout.COUNTER) == 120, "취소했는데 저장해 둔 크기까지 사라짐");
                c.setScreen(new HudEditorScreen(null));
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                press(c, "초기화");
                press(c, "저장");
                check(DdingMate.config().hudPos.isEmpty(), "초기화 후 저장했는데 옮긴 자리가 남음");
                check(DdingMate.config().hudSizes.isEmpty(), "초기화 후 저장했는데 HUD 별 크기가 남음");
            });
            ctx.waitTicks(3);

            // 10) 알림 기록함과 새 탭 화면
            ctx.runOnClient(c -> check(AlertLog.entries().size() >= 2, "알림 기록이 쌓이지 않음"));
            String[] names = {"gear", "todo", "log"};
            String[] titles = {"장비", "할 일", "기록"};
            for (int i = 0; i < names.length; i++) {
                String tab = titles[i];
                ctx.runOnClient(c -> {
                    DdingMateScreen s = new DdingMateScreen(null);
                    c.setScreen(s);
                    s.openTabNamed(tab);
                });
                ctx.waitTicks(5);
                ctx.takeScreenshot("features-3-settings-" + names[i]);
            }
            ctx.runOnClient(c -> {
                check(AlertLog.unread() == 0, "기록 탭을 열었는데 새 알림 표시가 남음");
                c.currentScreen.close();
                DdingMate.config().todo.hudEnabled = false;
            });
            ctx.waitTicks(5);

            // 11) 문의 탭: 분류·내용과 플레이어 정보를 문의 중계로(시험에서는 인터넷으로 보내지 않고 보낼 내용만 받는다)
            String[] sent = new String[1];
            ctx.runOnClient(c -> {
                kr.ddingmate.inquiry.Replies.debugReset();
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> {
                    if (path.equals("/inquiry")) sent[0] = body;
                    return new kr.ddingmate.inquiry.Inquiry.Http(200, "{\"id\":\"abcd1234\"}");
                });
                check(kr.ddingmate.inquiry.Inquiry.validate("짧음") != null && kr.ddingmate.inquiry.Inquiry.validate("가".repeat(1501)) != null
                        && kr.ddingmate.inquiry.Inquiry.validate("구리 블록 그림이 이상해요") == null, "문의 내용 검사가 다름");
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("문의");
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("features-5-inquiry-tab");
            // 문의 칸은 한 줄 입력칸 바탕이라 caramelChat 같은 한글 입력 모드가 붙는다. 한글을 친 그대로 받고, 긴 글은 여러 줄로 보인다
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                kr.ddingmate.ui.TextArea box = s.inquiryBox();
                check(box instanceof net.minecraft.client.gui.widget.TextFieldWidget, "문의 칸이 한 줄 입력칸 바탕이 아님");
                s.setFocused(box);
                for (char ch : "한글 입력 확인".toCharArray()) s.charTyped(ch, 0);
                check(box.getText().equals("한글 입력 확인"), "한글을 친 대로 안 받음: " + box.getText());
                s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                check(box.getText().equals("한글 입력 확") && box.getCursor() == 7, "지우기가 다름: " + box.getText());
                String longText = "재료 HUD 에서 구리 블록 그림이 청록색으로 보여요. 시설 창 옆 레시피 판에서도 같은 그림이 나오고, 다시 접속해도 그대로예요. "
                        + "가방을 열었다 닫으면 잠깐 맞게 보였다가 다시 바뀌어요. 스크린샷 두 장 첨부할게요. 확인 부탁드려요!";
                box.setText(longText);
                java.util.List<Integer> starts = box.lineStarts();
                check(starts.size() >= 3 && box.getText().equals(longText), "긴 글이 여러 줄로 안 나뉨: " + starts);
                for (int k = 1; k < starts.size(); k++)
                    check(longText.charAt(starts.get(k) - 1) == ' ', "줄이 띄어쓰기에서 안 바뀜: " + starts);
                box.setCursor(starts.get(1) + 2, false);
                box.moveLine(1, false);
                check(box.getCursor() >= starts.get(2) && box.getCursor() <= starts.get(2) + 4, "아래 줄로 커서가 안 감: " + box.getCursor() + " / " + starts);
                box.moveLine(-1, false);
                check(box.getCursor() >= starts.get(1) && box.getCursor() <= starts.get(1) + 4, "위 줄로 커서가 안 감: " + box.getCursor() + " / " + starts);
                box.setCursorToEnd(false);
                // 여러 줄 글을 붙여 넣으면 줄바꿈은 띄어쓰기로(낱말이 붙지 않게)
                box.setText("");
                box.write("첫 줄입니다\r\n둘째 줄입니다\n셋째");
                check(box.getText().equals("첫 줄입니다 둘째 줄입니다 셋째"), "붙여 넣은 줄바꿈이 다름: " + box.getText());
                box.setText(longText);
                box.setCursorToEnd(false);
                // 서버에 들어가 있지 않으면(혼자 하기) 지역은 "모름"으로 보낸다(나간 뒤 남은 표시가 섞이지 않게)
                check(kr.ddingmate.inquiry.Inquiry.info(c).place().equals("모름"), "서버 밖인데 지역을 보냄: " + kr.ddingmate.inquiry.Inquiry.info(c).place());
                // caramelChat 이 함께 깔려 있으면 이 칸에 한글 입력을 붙였는지(없으면 건너뜀)
                try {
                    Class<?> ctl = Class.forName("moe.caramel.chat.controller.EditBoxController");
                    Object wrapper = ctl.getMethod("caramelChat$wrapper").invoke(box);
                    check(wrapper != null, "caramelChat 이 문의 칸에 안 붙음");
                    kr.ddingmate.DdingMate.LOG.info("[시험] caramelChat 이 문의 칸에 붙음: {}", wrapper.getClass().getName());
                } catch (ClassNotFoundException e) {
                    kr.ddingmate.DdingMate.LOG.info("[시험] caramelChat 없음, 붙는지 확인은 건너뜀");
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-5d-inquiry-korean");
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                s.inquiryBox().setText("");
                s.setFocused(null);
            });
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                s.debugInquiry(kr.ddingmate.inquiry.Inquiry.Category.BUG, "짧음");
                check(sent[0] == null && !DdingMateScreen.inquiryStatus().isEmpty(), "너무 짧은 문의를 보냄");
                s.debugInquiry(kr.ddingmate.inquiry.Inquiry.Category.BUG, "재료 HUD 구리 블록 그림이 청록색이에요 @everyone");
                check(sent[0] != null, "문의를 보내지 않음");
                // 중계로는 정해진 칸만 보낸다(디스코드 메시지 모양·멘션은 중계가 만든다)
                com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(sent[0]).getAsJsonObject();
                com.google.gson.JsonObject info = j.getAsJsonObject("info");
                check(j.get("category").getAsString().equals("BUG") && j.get("text").getAsString().contains("구리 블록"),
                        "문의 분류·내용이 다름: " + sent[0]);
                check(info.get("uuid").getAsString().equals(c.player.getUuidAsString()) && info.get("name").getAsString().equals(c.player.getGameProfile().getName())
                                && info.get("modVersion").getAsString().length() > 0 && info.get("gameVersion").getAsString().equals("1.21.4"),
                        "플레이어 정보가 빠짐: " + sent[0]);
                String token = j.get("token").getAsString();
                check(token.matches("[A-Za-z0-9_-]{43}"), "답변 번호표 모양이 다름: " + token);
                check(!j.has("embeds") && !j.has("content") && !j.has("allowed_mentions"), "디스코드 메시지를 직접 만들어 보냄: " + sent[0]);
                // 보낸 문의는 답변을 받아 가도록 번호표와 함께 따로 남긴다(설정 파일 ddingmate.json 에는 안 남김)
                java.util.List<kr.ddingmate.inquiry.Replies.Ticket> tickets = kr.ddingmate.inquiry.Replies.debugTickets();
                check(tickets.size() == 1 && tickets.get(0).id.equals("abcd1234") && tickets.get(0).token.equals(token)
                        && tickets.get(0).category.equals("버그 제보") && tickets.get(0).preview.startsWith("재료 HUD"), "보낸 문의 기록이 다름");
                try {
                    String cfgText = java.nio.file.Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ddingmate.json"));
                    String ticketText = java.nio.file.Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ddingmate-inquiries.json"));
                    check(!cfgText.contains(token) && ticketText.contains(token), "번호표를 저장한 곳이 다름");
                } catch (java.io.IOException ex) {
                    throw new RuntimeException(ex);
                }
                check(DdingMateScreen.inquiryStatus().contains("보냈어요"), "보낸 뒤 안내가 다름: " + DdingMateScreen.inquiryStatus());
                // 이미지 첨부: 이미지가 아닌 파일은 빼고, 3장까지. 보내면 첫 장이 메시지 안에 크게, 원래 파일 이름은 안 보낸다
                try {
                    java.nio.file.Path dir = c.runDirectory.toPath().resolve("ddingmate-test-images");
                    java.nio.file.Files.createDirectories(dir);
                    java.util.List<java.nio.file.Path> pngs = new java.util.ArrayList<>();
                    for (int k = 1; k <= 4; k++) {
                        java.nio.file.Path f = dir.resolve("내 스크린샷 " + k + ".png");
                        java.nio.file.Files.write(f, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
                        pngs.add(f);
                    }
                    java.nio.file.Path txt = dir.resolve("메모.txt");
                    java.nio.file.Files.writeString(txt, "글");
                    s.debugAttach(List.of(txt));
                    check(DdingMateScreen.inquiryImageCount() == 0 && DdingMateScreen.inquiryStatus().contains("이미지"), "이미지가 아닌 파일을 첨부함");
                    s.debugAttach(pngs);
                    check(DdingMateScreen.inquiryImageCount() == 3 && DdingMateScreen.inquiryStatus().contains("3장까지"), "이미지를 3장 넘게 첨부함");
                } catch (java.io.IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-5c-inquiry-attached");
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> {
                    if (path.equals("/inquiry")) sent[0] = body;
                    return new kr.ddingmate.inquiry.Inquiry.Http(200, "{\"id\":\"0000beef\"}");
                });
                s.debugInquiry(kr.ddingmate.inquiry.Inquiry.Category.TEXT, "첨부한 스크린샷의 글자가 잘려요");
                check(kr.ddingmate.inquiry.Inquiry.debugLastFiles.equals(List.of("image1.png", "image2.png", "image3.png"))
                        && !sent[0].contains("내 스크린샷"),
                        "첨부 이미지 이름이 다름: " + kr.ddingmate.inquiry.Inquiry.debugLastFiles + " / " + sent[0]);
                check(DdingMateScreen.inquiryImageCount() == 0, "보낸 뒤에도 첨부가 남음");
                // 1분 안에 또 누르면 보내지 않는다(도배 막기)
                sent[0] = null;
                s.debugInquiry(kr.ddingmate.inquiry.Inquiry.Category.FEATURE, "기능 하나 더 부탁해요");
                check(sent[0] == null && DdingMateScreen.inquiryStatus().contains("초 뒤에"), "1분 안에 다시 보냄");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-5b-inquiry-sent");
            // 중계가 안 될 때 알려 주는 말(쓴 내용은 남는다)
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> new kr.ddingmate.inquiry.Inquiry.Http(429, "{\"error\":\"cooldown\"}"));
                s.debugInquiry(kr.ddingmate.inquiry.Inquiry.Category.BUG, "중계가 바쁠 때 시험");
                check(DdingMateScreen.inquiryStatus().contains("잠시 뒤에"), "429 안내가 다름: " + DdingMateScreen.inquiryStatus());
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> new kr.ddingmate.inquiry.Inquiry.Http(502, "{\"error\":\"discord\"}"));
                s.debugInquiry(kr.ddingmate.inquiry.Inquiry.Category.BUG, "중계가 멈췄을 때 시험");
                check(DdingMateScreen.inquiryStatus().contains("조금 뒤에") && s.inquiryBox().getText().equals("중계가 멈췄을 때 시험"),
                        "502 안내가 다름: " + DdingMateScreen.inquiryStatus());
                check(kr.ddingmate.inquiry.Replies.debugTickets().size() == 2, "보내지 못한 문의를 기록함");
                s.inquiryBox().setText("");
                c.currentScreen.close();
            });
            ctx.waitTicks(5);

            // 12) 개발자 답변 받기: 접속하면 5초 뒤·10분마다 묻고, 처음 보는 답변만 귓속말처럼 내 채팅·팝업·기록에
            String[] asked = new String[1];
            ctx.runOnClient(c -> {
                c.inGameHud.getChatHud().clear(false);
                kr.ddingmate.inquiry.Replies.onJoin();
                check(kr.ddingmate.inquiry.Replies.debugNextPollAt() > System.currentTimeMillis() + 3000, "접속하자마자 물음");
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> {
                    asked[0] = body;
                    return new kr.ddingmate.inquiry.Inquiry.Http(200, "{\"tickets\":[{\"found\":true,\"id\":\"abcd1234\",\"replies\":["
                            + "{\"n\":1,\"text\":\"구리 블록 그림 고쳤어요!\\n0.3.2 에서 확인해 주세요\",\"at\":1760700000000}]},"
                            + "{\"found\":true,\"id\":\"0000beef\",\"replies\":[]}]}");
                });
                int logBefore = AlertLog.entries().size();
                kr.ddingmate.inquiry.Replies.debugPoll(c);
                com.google.gson.JsonArray toks = com.google.gson.JsonParser.parseString(asked[0]).getAsJsonObject().getAsJsonArray("tokens");
                check(toks.size() == 2 && toks.get(0).getAsString().equals(kr.ddingmate.inquiry.Replies.debugTickets().get(0).token), "물어본 번호표가 다름: " + asked[0]);
                check(AlertLog.entries().size() == logBefore + 1 && AlertLog.entries().get(0).body.contains("고쳤어요")
                        && AlertLog.entries().get(0).title.contains("[버그 제보] 재료 HUD"), "답변이 기록에 안 남음");
                check(kr.ddingmate.inquiry.Replies.debugTickets().get(0).replies.size() == 1, "받은 답변을 저장 안 함");
                // 다시 물어도 같은 답변은 또 알리지 않는다
                kr.ddingmate.inquiry.Replies.debugPoll(c);
                check(AlertLog.entries().size() == logBefore + 1, "같은 답변을 또 알림");
                // 중계에 기록이 없는(오래된) 문의는 하루가 지나면 더 묻지 않는다
                kr.ddingmate.inquiry.Replies.debugTickets().get(1).sentAt -= 2L * 24 * 3600 * 1000;
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> new kr.ddingmate.inquiry.Inquiry.Http(200,
                        "{\"tickets\":[{\"found\":true,\"id\":\"abcd1234\",\"replies\":[]},{\"found\":false,\"replies\":[]}]}"));
                kr.ddingmate.inquiry.Replies.debugPoll(c);
                check(kr.ddingmate.inquiry.Replies.debugTickets().get(1).gone && !kr.ddingmate.inquiry.Replies.debugTickets().get(0).gone,
                        "없는 문의를 계속 물음");
                kr.ddingmate.inquiry.Replies.onDisconnect();
            });
            ctx.waitTicks(25);
            ctx.takeScreenshot("features-5e-dev-reply");
            ctx.runOnClient(c -> {
                kr.ddingmate.inquiry.Inquiry.debugSender(null);
                kr.ddingmate.inquiry.Replies.debugReset();
            });
            ctx.waitTicks(5);
            // 13) 0.3.2: 제작 [대기중..] · 반려어 밥 타이머 · 오션오더 타이머 · 답장 빨리 확인
            ctx.runOnClient(c -> {
                long now = System.currentTimeMillis();
                // 같은 시설에서 앞 제작(어빌리티 스톤)이 안 끝나 조약돌 뭉치는 시작도 안 함 → [대기중..]
                kr.ddingmate.craft.CraftTimers.debugClear();
                String q = "채광물 가공 시설@VILLAGE";
                kr.ddingmate.craft.CraftTimers.debugAddJob("어빌리티 스톤", 64, now - 60_000, now + 74 * 60_000, q);
                kr.ddingmate.craft.CraftTimers.debugAddJob("조약돌 뭉치", 7, now - 50_000, now + 75 * 60_000, q);
                kr.ddingmate.craft.CraftTimers.debugAddJob("코룸 주괴", 24, now - 10_000, now + 5 * 60_000, "강화 제작 시설@VILLAGE");
                java.util.Map<String, String> labels = new java.util.HashMap<>();
                for (kr.ddingmate.craft.CraftTimers.Group g : kr.ddingmate.craft.CraftTimers.groups())
                    labels.put(g.name(), kr.ddingmate.craft.CraftTimers.displayLabel(g, now));
                check(labels.get("조약돌 뭉치").endsWith("[대기중..]") && !labels.get("어빌리티 스톤").contains("대기")
                        && !labels.get("코룸 주괴").contains("대기"), "대기 표시가 다름: " + labels);
                check(!kr.ddingmate.craft.CraftTimers.displayLabel(kr.ddingmate.craft.CraftTimers.groups().stream()
                        .filter(g -> g.name().equals("조약돌 뭉치")).findFirst().orElseThrow(), now + 74 * 60_000 + 1000).contains("대기"),
                        "앞 제작이 끝났는데도 대기 중으로 보임");

                // 같은 아이템 여러 번: "전부 끝나면 한 번에 완료"(기본 켬)면 먼저 끝난 것도 한 줄에 두고, 전부 끝나야 "전부 완료!"
                kr.ddingmate.craft.CraftTimers.debugAddJob("시험 묶음 아이템", 7, now - 60_000, now - 1000, "시험 대기열");
                for (int k = 1; k <= 4; k++)
                    kr.ddingmate.craft.CraftTimers.debugAddJob("시험 묶음 아이템", 7, now - 60_000, now + k * 60_000, "시험 대기열");
                java.util.function.Supplier<java.util.List<kr.ddingmate.craft.CraftTimers.Group>> bundle = () -> kr.ddingmate.craft.CraftTimers.groups()
                        .stream().filter(g -> g.name().equals("시험 묶음 아이템")).toList();
                DdingMate.config().craft.mergeDone = true;
                check(bundle.get().size() == 1 && bundle.get().get(0).times() == 5
                                && !kr.ddingmate.craft.CraftTimers.remainingText(bundle.get().get(0), now).contains("완료"),
                        "먼저 끝난 1개를 따로 뺌: " + bundle.get());
                check(kr.ddingmate.craft.CraftTimers.remainingText(bundle.get().get(0), now + 5 * 60_000).equals("전부 완료!"),
                        "전부 끝났는데 '전부 완료!'가 아님: " + kr.ddingmate.craft.CraftTimers.remainingText(bundle.get().get(0), now + 5 * 60_000));
                DdingMate.config().craft.mergeDone = false;
                check(bundle.get().size() == 2, "끄면 끝난 것을 따로 보여야 함: " + bundle.get());
                DdingMate.config().craft.mergeDone = true;
                kr.ddingmate.craft.CraftTimers.groups().stream().filter(g -> g.name().equals("시험 묶음 아이템"))
                        .forEach(kr.ddingmate.craft.CraftTimers::remove);

                // 형광 광산: 골렘 칸의 남은 시간으로 타이머, 남은 시간이 없는 칸이 되면(받아 감) 정리
                net.minecraft.item.ItemStack mineItem = new net.minecraft.item.ItemStack(Items.PAPER);
                mineItem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("[ 형광 광물 채광 중 ]"));
                java.util.List<Text> mineLore = java.util.List.of(Text.literal("일꾼 골렘이 현재 채광을 진행 중입니다."),
                        Text.literal("남은 시간이 지나면 광물 수령이 가능합니다."), Text.literal(""),
                        Text.literal("  - 일꾼 골렘 : 에픽"), Text.literal("  - 남은 시간 : 13초"));
                check(kr.ddingmate.craft.CraftTimers.debugMine(mineItem, mineLore), "형광 광산 칸을 못 읽음");
                kr.ddingmate.craft.CraftTimers.Group mineG = kr.ddingmate.craft.CraftTimers.groups().stream()
                        .filter(g -> g.name().equals(kr.ddingmate.craft.CraftTimers.MINE)).findFirst().orElse(null);
                check(mineG != null && Math.abs(mineG.endsAt() - (now + 13_000)) < 1500 && !mineG.unknown() && !mineG.estimated(),
                        "형광 광산 남은 시간이 다름: " + mineG);
                check(!kr.ddingmate.craft.CraftTimers.debugMine(mineItem, mineLore), "같은 시간인데 또 바꿈");
                // 창을 열어 둔 동안 서버의 '남은 시간'은 그대로(실제 7분 47초 고정) → 5초 뒤 같은 글을 읽어도 끝나는 시각은 그대로
                long mineEnds = mineG.endsAt();
                check(!kr.ddingmate.craft.CraftTimers.debugMine(mineItem, mineLore, now + 5000), "멈춘 창 글을 다시 읽어 타이머가 멈춤");
                long mineEnds2 = kr.ddingmate.craft.CraftTimers.groups().stream()
                        .filter(g -> g.name().equals(kr.ddingmate.craft.CraftTimers.MINE)).findFirst().orElseThrow().endsAt();
                check(mineEnds2 == mineEnds, "같은 글인데 끝나는 시각이 바뀜: " + (mineEnds2 - mineEnds) + "ms");
                // 창 전체로 맞추기(2026-10-25 실제): 골렘 칸마다 누적 남은 시간 "9초"·"10분 9초"·"20분 9초"(상태 칸이 같은 값을 한 번 더)
                // → 골렘 3마리. 남은 시간이 하나도 없으면(다 받음) 줄이 모두 빠진다
                check(kr.ddingmate.craft.CraftTimers.debugSyncMine(java.util.List.of(9L, 609L, 1209L, 1209L), now) == 3, "형광 광산 창 골렘 수가 3이 아님");
                check(kr.ddingmate.craft.CraftTimers.debugSyncMine(java.util.List.of(), now) == 0, "다 받은 형광 광산 줄이 안 빠짐");
                // 키워드 제외어(문의 6d1b50b0): 키워드 "찬", 제외 "이찬"
                check(!kr.ddingmate.notify.KeywordWatcher.debugWithoutExcludes("이찬 : 안녕", java.util.List.of("이찬")).contains("찬")
                                && kr.ddingmate.notify.KeywordWatcher.debugWithoutExcludes("이찬 : 찬 어디", java.util.List.of("이찬")).contains("찬"),
                        "제외할 말이 안 빠지거나 다른 데 든 키워드까지 빠짐");
                // 펫 전부 소환 해제(2026-10-28 실제 채팅)
                long petNow = System.currentTimeMillis();
                kr.ddingmate.game.ServerHud.debugPetReset("[초기화] 5분 후 펫이 전부 소환 해제됩니다.", petNow);
                check(Math.abs(kr.ddingmate.game.ServerHud.petResetAt() - (petNow + 300_000)) < 2000, "펫 소환 해제 5분을 못 읽음");
                kr.ddingmate.game.ServerHud.debugPetReset("[초기화] 펫 이 소환 해제되었습니다.", petNow);
                check(kr.ddingmate.game.ServerHud.petResetAt() == 0, "펫 소환 해제 뒤에도 남음");
                // 의뢰 트래커(2026-10 실제 사이드바) → 일일·주간 의뢰 내용
                java.util.List<java.util.List<String>> quests = kr.ddingmate.todo.TodoList.debugTracker(java.util.List.of(
                        "의뢰 트래커", "", "일일 의뢰", "레드스톤 채광하기 (0/8)", "금붕어 낚기 (10/10)", "", "주간 의뢰", "청어 낚기 (0/30)",
                        "현재 진행 중인 의뢰의 자세한 상황은"));
                check(quests.get(0).equals(java.util.List.of("레드스톤 채광하기 (0/8)", "금붕어 낚기 (10/10)"))
                                && quests.get(1).equals(java.util.List.of("청어 낚기 (0/30)"))
                                && kr.ddingmate.todo.TodoList.questDone("금붕어 낚기 (10/10)") && !kr.ddingmate.todo.TodoList.questDone("청어 낚기 (0/30)"),
                        "의뢰 트래커 읽기 오류: " + quests);
                check(kr.ddingmate.craft.CraftTimers.debugSyncMine(java.util.List.of((mineEnds - now) / 1000), now) == 1, "형광 광산 줄을 다시 못 만듦");
                // 골렘을 넣는 채팅(실제 로그)만으로도 센다: 위키 등급별 시간(레어 5분), 앞 골렘 뒤에 줄 선다. 사람 채팅은 무시
                kr.ddingmate.craft.CraftTimers.onChat("[칭호] 누군가 : 레어 일꾼 골렘을 채광 대기열에 투입했습니다.");
                kr.ddingmate.craft.CraftTimers.onChat("레어 일꾼 골렘을 채광 대기열에 투입했습니다.");
                java.util.function.LongPredicate queuedAt = at -> kr.ddingmate.craft.CraftTimers.groups().stream()
                        .anyMatch(g -> g.name().equals(kr.ddingmate.craft.CraftTimers.MINE) && Math.abs(g.endsAt() - at) < 1500);
                check(queuedAt.test(mineEnds + 5 * 60_000), "대기열에 넣은 레어 골렘이 앞 골렘 뒤 5분으로 안 잡힘: " + kr.ddingmate.craft.CraftTimers.groups());
                check(kr.ddingmate.craft.CraftTimers.mineMs("에픽") == 60_000 && kr.ddingmate.craft.CraftTimers.mineMs("노말") == 600_000, "등급별 채광 시간");
                // 창을 다시 보니 앞 골렘이 20초 남음(7초 늦어짐) → 뒤 골렘도 7초 뒤로
                java.util.List<Text> mineLore20 = java.util.List.of(Text.literal("일꾼 골렘이 현재 채광을 진행 중입니다."),
                        Text.literal("  - 일꾼 골렘 : 에픽"), Text.literal("  - 남은 시간 : 20초"));
                kr.ddingmate.craft.CraftTimers.debugMine(mineItem, mineLore20, now);
                check(queuedAt.test(now + 20_000 + 5 * 60_000), "앞 골렘 시간이 바뀌었는데 뒤 골렘을 안 옮김: " + kr.ddingmate.craft.CraftTimers.groups());

                // 반려어: 위키대로 10분마다 가장 높은 스탯이 25%씩(안전하게 큰 값) → 30분 뒤 평균 75%(성장 느려짐), 70분 뒤 25%(폐사 위험)
                kr.ddingmate.expert.PetFish.debugReset();
                long t0 = now - 1000;
                kr.ddingmate.expert.PetFish.debugRead("아기 먹", List.of("허기 : 100%", "기분 : 100%", "활력 : 100%", "성장도 : 0"), t0);
                kr.ddingmate.expert.PetFish.Fish f = kr.ddingmate.expert.PetFish.current();
                check(f != null && f.insertedAt == t0 && f.hunger == 100, "반려어를 못 읽음");
                kr.ddingmate.expert.PetFish.Forecast fc = kr.ddingmate.expert.PetFish.forecast(f, t0 + 60_000);
                check(fc.slowAt() == t0 + 30 * 60_000 && fc.dangerAt() == t0 + 70 * 60_000,
                        "반려어 예상 시각이 다름: 느려짐 " + (fc.slowAt() - t0) / 60000 + "분, 위험 " + (fc.dangerAt() - t0) / 60000 + "분");
                check(kr.ddingmate.expert.PetFish.status(f, t0 + 5 * 60_000).equals("25분 뒤 밥 주기"), "밥 시간 안내: " + kr.ddingmate.expert.PetFish.status(f, t0 + 5 * 60_000));
                check(kr.ddingmate.expert.PetFish.status(f, t0 + 31 * 60_000).equals("밥 줄 때!"), "밥 줄 때 안내: " + kr.ddingmate.expert.PetFish.status(f, t0 + 31 * 60_000));
                check(kr.ddingmate.expert.PetFish.status(f, t0 + 71 * 60_000).equals("폐사 위험!"), "폐사 위험 안내: " + kr.ddingmate.expert.PetFish.status(f, t0 + 71 * 60_000));
                // 알림: 밥 줄 때 한 번, 폐사 위험 한 번(같은 것을 또 울리지 않음)
                int pfBefore = AlertLog.entries().size();
                kr.ddingmate.expert.PetFish.tick(t0 + 31 * 60_000);
                kr.ddingmate.expert.PetFish.tick(t0 + 32 * 60_000);
                check(AlertLog.entries().size() == pfBefore + 1 && AlertLog.entries().get(0).title.contains("밥 줄 때"), "밥 알림이 다름");
                kr.ddingmate.expert.PetFish.tick(t0 + 71 * 60_000);
                check(AlertLog.entries().size() == pfBefore + 2 && AlertLog.entries().get(0).title.contains("폐사 위험"), "폐사 위험 알림이 다름");
                // 밥을 줘서 다시 채워진 걸 읽으면 알림을 다시 할 수 있다
                kr.ddingmate.expert.PetFish.debugRead("아기 먹", List.of("허기 : 100%", "기분 : 100%", "활력 : 100%", "성장도 : 40"), t0 + 72 * 60_000);
                check(!f.warnedFeed && !f.warnedDanger && f.growth == 40, "밥 준 뒤 알림을 다시 못 함");
                // 창에 남은 성장 시간이 있으면 넣은 시각을 거기서
                kr.ddingmate.expert.PetFish.debugRead("먹", List.of("허기 85%", "기분 90%", "활력 100%", "성장도 50", "남은 성장 시간 : 1시간 30분"), now);
                check(kr.ddingmate.expert.PetFish.current().insertedAt == now + 90 * 60_000 - kr.ddingmate.expert.PetFish.GROW_MS, "남은 성장 시간으로 넣은 시각을 못 맞춤");
                // 2시간 지나 성장도 90 이상이면 더 안 줄어서 다 큼
                kr.ddingmate.expert.PetFish.debugRead("먹", List.of("허기 60%", "기분 70%", "활력 80%", "성장도 95", "남은 성장 시간 : 0분"), now);
                check(kr.ddingmate.expert.PetFish.status(kr.ddingmate.expert.PetFish.current(), now + 3 * 3600_000L).equals("다 컸어요"),
                        "다 컸는데 밥을 재촉함: " + kr.ddingmate.expert.PetFish.status(kr.ddingmate.expert.PetFish.current(), now + 3 * 3600_000L));
                // HUD 에 보일 상태로 다시(30분 남음)
                kr.ddingmate.expert.PetFish.debugReset();
                // 반려어가 아닌 아이템(실제 로그: 신나는 풍선 뭉치)은 "기분"·"활력" 낱말만 있고 숫자가 없다 → 일정에 안 생겨야
                kr.ddingmate.expert.PetFish.debugRead("[ 신나는 풍선 뭉치 ]", List.of("풍선이 둥실둥실 떠다닙니다.", "기분 좋은 활력이 차오릅니다.",
                        "[옵션 목록]", "- 회복력 : 1", "- 점프력 : 1", "- 블록설치 : 1"), now);
                check(kr.ddingmate.expert.PetFish.current() == null, "풍선 뭉치를 반려어로 읽음");
                // 어항에서 꺼내면 일정에서 빠진다(사용자 문의): 제목이 그림 글자라 "어항" 글자가 없어도, 반려어를 읽었던 창과 같은 창이면
                kr.ddingmate.expert.PetFish.debugRead("아기 먹", List.of("허기 : 100%", "기분 : 100%", "활력 : 100%"), now);
                kr.ddingmate.expert.PetFish.debugSetScreen("\uE001\uE002");
                kr.ddingmate.expert.PetFish.debugAfterScan("\uE009", false, true, now);
                kr.ddingmate.expert.PetFish.debugAfterScan("\uE009", false, true, now + 2000);
                check(kr.ddingmate.expert.PetFish.current() != null, "다른 창인데 반려어 기록을 지움");
                kr.ddingmate.expert.PetFish.debugAfterScan("\uE001\uE002", false, false, now);
                kr.ddingmate.expert.PetFish.debugAfterScan("\uE001\uE002", false, false, now + 2000);
                check(kr.ddingmate.expert.PetFish.current() != null, "창 칸이 아직 안 왔는데 지움");
                kr.ddingmate.expert.PetFish.debugAfterScan("\uE001\uE002", false, true, now);
                kr.ddingmate.expert.PetFish.debugAfterScan("\uE001\uE002", false, true, now + 1500);
                check(kr.ddingmate.expert.PetFish.current() == null, "어항에서 꺼냈는데 일정에 남음");
                kr.ddingmate.expert.PetFish.debugRead("아기 루모스", List.of("허기 : 100%", "기분 : 100%", "활력 : 80%", "성장도 : 12"), now - 9 * 60_000);

                // 오션오더: 도착하면 5분, 진행·성공·실패, 사람 채팅은 무시
                kr.ddingmate.expert.OceanOrder.debugReset();
                kr.ddingmate.expert.OceanOrder.onMessage("[초보모험가] 누군가 : 오션오더 도착했다");
                check(!kr.ddingmate.expert.OceanOrder.active(now), "사람 채팅으로 오션오더를 켬");
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더가 도착했습니다! 300초 안에 주문한 물고기를 낚아 주세요.", now);
                check(kr.ddingmate.expert.OceanOrder.active(now) && kr.ddingmate.expert.OceanOrder.endsAt() == now + 300_000
                        && kr.ddingmate.expert.OceanOrder.todayCount() == 1, "오션오더 도착을 못 읽음");
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더 물고기 도미를 낚았습니다! (1/3) 남은 시간 : 4분 32초", now);
                check(kr.ddingmate.expert.OceanOrder.endsAt() == now + 272_000 && kr.ddingmate.expert.OceanOrder.label().contains("1/3"),
                        "오션오더 진행을 못 읽음: " + kr.ddingmate.expert.OceanOrder.label());
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더를 성공적으로 완료했습니다! 골든티켓 2개를 획득했습니다.", now);
                check(!kr.ddingmate.expert.OceanOrder.active(now), "오션오더 성공인데 계속 셈");
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더가 도착했습니다!", now);
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더 실패! 제한시간이 지났습니다.", now);
                check(!kr.ddingmate.expert.OceanOrder.active(now) && kr.ddingmate.expert.OceanOrder.todayCount() == 2, "오션오더 실패 처리가 다름");
                check(kr.ddingmate.expert.OceanOrder.remainingIn("남은 시간 : 2분 5초") == 125_000, "남은 시간 읽기");
                // 1분 남으면 한 번 알림
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더가 도착했습니다!", now - 241_000);
                int ob = AlertLog.entries().size();
                kr.ddingmate.expert.OceanOrder.tick(now);
                kr.ddingmate.expert.OceanOrder.tick(now + 1000);
                check(AlertLog.entries().size() == ob + 1 && AlertLog.entries().get(0).title.contains("1분"), "오션오더 1분 알림이 다름");
                // 서버 오션오더 HUD(보스바, 리소스팩 글꼴 hud_oceanorder_hud_*): 보이면 진행 중, 3초 넘게 사라지면 끝
                kr.ddingmate.expert.OceanOrder.debugReset();
                kr.ddingmate.expert.OceanOrder.debugHudReset();
                String ns = "41f8b3b6-0374-40e5-ae0e-c2a99c95b8e4";
                net.minecraft.text.Text oceanBar = net.minecraft.text.Text.literal("󞀂").setStyle(net.minecraft.text.Style.EMPTY
                                .withFont(net.minecraft.util.Identifier.of(ns, "hud_oceanorder_hud_image")))
                        .append(net.minecraft.text.Text.literal("도미 (0/1)").setStyle(net.minecraft.text.Style.EMPTY
                                .withFont(net.minecraft.util.Identifier.of(ns, "hud_oceanorder_hud_text_1_2_1"))))
                        .append(net.minecraft.text.Text.literal("4:10").setStyle(net.minecraft.text.Style.EMPTY
                                .withFont(net.minecraft.util.Identifier.of(ns, "hud_oceanorder_hud_text_2_3_1"))));
                String hudLine = kr.ddingmate.expert.OceanOrder.hudText(oceanBar);
                check("도미 (0/1) | 4:10".equals(hudLine), "오션오더 HUD 글을 못 읽음: " + hudLine);
                check(kr.ddingmate.expert.OceanOrder.hudText(net.minecraft.text.Text.literal("에버그린 마을 (스폰4)")) == null, "채널 표시를 오션오더 HUD 로 봄");
                kr.ddingmate.expert.OceanOrder.onHud(hudLine, now);
                check(kr.ddingmate.expert.OceanOrder.active(now) && kr.ddingmate.expert.OceanOrder.endsAt() == now + 250_000
                        && kr.ddingmate.expert.OceanOrder.label().contains("0/1"), "오션오더 HUD 로 타이머를 못 켬: " + kr.ddingmate.expert.OceanOrder.label());
                kr.ddingmate.expert.OceanOrder.hudPresent(false, now + 2000);
                check(kr.ddingmate.expert.OceanOrder.active(now + 2000), "HUD 가 잠깐 안 보였는데 끔");
                kr.ddingmate.expert.OceanOrder.hudPresent(false, now + 4000);
                check(!kr.ddingmate.expert.OceanOrder.active(now + 4000), "오션오더 HUD 가 사라졌는데 계속 셈");
                kr.ddingmate.expert.OceanOrder.debugHudReset();
                // 2026-10-23 사용자: 오션오더가 오면 띵메이트 HUD 가 모두 사라짐 → 재화 바·채널 표시 보스바에 오션오더 HUD 가 함께 실려도
                // 채널 표시는 계속 읽는다(같은 보스바 한 줄)
                net.minecraft.text.Text merged = net.minecraft.text.Text.literal("1,187,020 2026.10.23 17:16 에버그린 마을 (스폰4)").copy()
                        .append(oceanBar);
                check(kr.ddingmate.expert.OceanOrder.hudText(merged) != null
                        && kr.ddingmate.expert.OceanOrder.withoutHud(merged).contains("(스폰4)")
                        && !kr.ddingmate.expert.OceanOrder.withoutHud(merged).contains("도미"), "합쳐진 보스바에서 채널 표시를 못 가름");
                var bars = ((kr.ddingmate.mixin.BossBarHudAccessor) MinecraftClient.getInstance().inGameHud.getBossBarHud()).ddingmate$bossBars();
                java.util.UUID barId = java.util.UUID.randomUUID();
                bars.put(barId, new net.minecraft.client.gui.hud.ClientBossBar(barId, merged, 0f, net.minecraft.entity.boss.BossBar.Color.WHITE,
                        net.minecraft.entity.boss.BossBar.Style.PROGRESS, false, false, false));
                int keepVillage = DdingMate.config().villageChannel, keepSpawn = DdingMate.config().spawnChannel;
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.VILLAGE, 7);
                kr.ddingmate.game.ServerHud.tick(MinecraftClient.getInstance());
                bars.remove(barId);
                DdingMate.config().villageChannel = keepVillage; // 채널 자동 인식이 바꾼 '내 채널'을 되돌린다
                DdingMate.config().spawnChannel = keepSpawn;
                DdingMate.rebuildRules();
                check(kr.ddingmate.game.ServerHud.area() == kr.ddingmate.game.ServerHud.Area.SPAWN && kr.ddingmate.game.ServerHud.channel() == 4,
                        "오션오더 HUD 가 실린 보스바에서 채널 표시를 못 읽음: " + kr.ddingmate.game.ServerHud.area() + " " + kr.ddingmate.game.ServerHud.channel());
                check(kr.ddingmate.expert.OceanOrder.active(System.currentTimeMillis()), "합쳐진 보스바의 오션오더 HUD 로 타이머를 못 켬");
                // 끝난 뒤 남은 서버 HUD 로 다시 '도착'을 세지 않는다(2026-10-23: 성공 후 "오션오더 도착!"과 횟수 +1)
                kr.ddingmate.expert.OceanOrder.debugReset();
                kr.ddingmate.expert.OceanOrder.debugHudReset();
                kr.ddingmate.expert.OceanOrder.onHud("도미 (0/1) | 4:10", now);
                int n1 = kr.ddingmate.expert.OceanOrder.todayCount();
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더 물고기 도미 를 낚았습니다. (1/1)", now);
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더를 성공하여 골든티켓 10장을 획득하였습니다.", now);
                kr.ddingmate.expert.OceanOrder.onHud("도미 (0/1) | 4:10", now + 500);
                kr.ddingmate.expert.OceanOrder.onHud("도미 (1/1) | 4:08", now + 1000);
                check(!kr.ddingmate.expert.OceanOrder.active(now + 1000) && kr.ddingmate.expert.OceanOrder.todayCount() == n1,
                        "끝난 오션오더 HUD 로 다시 셈: " + kr.ddingmate.expert.OceanOrder.todayCount());
                kr.ddingmate.expert.OceanOrder.onHud("새우 (0/2) | 4:59", now + 2000); // 새 주문(글이 바뀌고 거의 5분)
                check(kr.ddingmate.expert.OceanOrder.active(now + 2000) && kr.ddingmate.expert.OceanOrder.todayCount() == n1 + 1, "새 오션오더를 못 셈");
                // 서버의 오늘 횟수 "[오션오더] 8/10" 로 맞춤(물고기 진행으로 착각하지 않음)
                kr.ddingmate.expert.OceanOrder.debugHandle("[오션오더] 8/10", now + 2500);
                check(kr.ddingmate.expert.OceanOrder.todayCount() == 8 && kr.ddingmate.expert.OceanOrder.label().contains("8/10")
                        && kr.ddingmate.expert.OceanOrder.label().contains("0/2"), "서버 오늘 횟수로 못 맞춤: " + kr.ddingmate.expert.OceanOrder.label());
                // 해양 스킬 "오션오더 더 더!": 지금 레벨 설명만 읽고 다음 레벨 미리보기는 건너뜀
                kr.ddingmate.expert.OceanOrder.debugSkill("오션오더 더 더! (+2)", java.util.List.of("[마을/야생]",
                        "수령할 수 있는 오션오더의 일일제한이 14회로 증가합니다.", "─────────", "강화 시", "수령할 수 있는 오션오더의 일일제한이 16회로 증가합니다."));
                check(kr.ddingmate.expert.OceanOrder.limit() == 14 && kr.ddingmate.expert.OceanOrder.label().contains("/14"),
                        "오션오더 더 더! 스킬 한도를 못 읽음: " + kr.ddingmate.expert.OceanOrder.limit());
                kr.ddingmate.expert.OceanOrder.debugHandle("[오션오더] 9/16", now + 3000);
                check(kr.ddingmate.expert.OceanOrder.limit() == 16 && kr.ddingmate.expert.OceanOrder.todayCount() == 9, "서버 한도로 못 맞춤");
                kr.ddingmate.expert.OceanOrder.debugSkill("오션오더 더 더! (+0)", java.util.List.of("수령할 수 있는 오션오더의 일일제한이 12회로 증가합니다."));
                check(kr.ddingmate.expert.OceanOrder.limit() == 10, "안 배운 스킬인데 한도가 늘어남");
                kr.ddingmate.expert.OceanOrder.debugReset();
                kr.ddingmate.expert.OceanOrder.debugHudReset();
                kr.ddingmate.expert.OceanOrder.debugHandle("오션오더가 도착했습니다!", now);

                // 아일랜드 작물 15분 타이머(단축키): 누르면 시작, 한 번 더 누르면 취소, 멈춤 표시
                kr.ddingmate.expert.CropTimer.cancel();
                kr.ddingmate.expert.CropTimer.debugToggle(now);
                check(kr.ddingmate.expert.CropTimer.running() && kr.ddingmate.expert.CropTimer.remaining(now) == 15 * 60_000,
                        "단축키로 15분 타이머가 안 켜짐");
                kr.ddingmate.expert.CropTimer.debugSetPaused(5 * 60_000);
                check(kr.ddingmate.expert.CropTimer.remainingText(now).equals("5분 0초 · 멈춤(마을 밖)"), "멈춤 표시: " + kr.ddingmate.expert.CropTimer.remainingText(now));
                kr.ddingmate.expert.CropTimer.debugSetPaused(0);
                kr.ddingmate.expert.CropTimer.debugToggle(now);
                check(!kr.ddingmate.expert.CropTimer.running(), "단축키를 한 번 더 눌렀는데 안 꺼짐");
                check(kr.ddingmate.expert.CropTimer.keyName(java.util.List.of(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL, org.lwjgl.glfw.GLFW.GLFW_KEY_G)).contains(" + ")
                        && kr.ddingmate.expert.CropTimer.keyName(java.util.List.of()).equals("지정 안 함"), "단축키 이름");
                // 단축키 지정 창: 함께 누른 키(최대 3개)를 떼는 순간 정한다
                java.util.List<java.util.List<Integer>> captured = new java.util.ArrayList<>();
                kr.ddingmate.gui.KeyCaptureScreen cap = new kr.ddingmate.gui.KeyCaptureScreen(null, captured::add);
                c.setScreen(cap);
                cap.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL, 0, 0);
                cap.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0);
                cap.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_G, 0, 0);
                cap.keyReleased(org.lwjgl.glfw.GLFW.GLFW_KEY_G, 0, 0);
                check(captured.size() == 1 && captured.get(0).equals(java.util.List.of(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT, org.lwjgl.glfw.GLFW.GLFW_KEY_G)), "키 조합을 못 정함: " + captured);
                c.setScreen(null);
                // 작물 자동 읽기(실험): 1단계를 보면 막 심은 것(정확, 15분), 단계가 바뀌면 그 순간으로 맞춤, 중간 단계는 '최대'
                kr.ddingmate.expert.IslandCrops.debugReset();
                long c0 = now;
                kr.ddingmate.expert.IslandCrops.debugObserve(new net.minecraft.util.math.BlockPos(1, 64, 1), "tomato", 1, 6, c0);
                var tom = kr.ddingmate.expert.IslandCrops.summaries(c0).get("tomato");
                check(tom != null && tom.allReadyAt() == c0 + 15 * 60_000 && tom.exact()
                        && kr.ddingmate.expert.IslandCrops.status(tom, c0).equals("15분 뒤"), "막 심은 토마토 15분이 아님: " + tom);
                kr.ddingmate.expert.IslandCrops.debugObserve(new net.minecraft.util.math.BlockPos(1, 64, 1), "tomato", 2, 6, c0 + 200_000);
                tom = kr.ddingmate.expert.IslandCrops.summaries(c0 + 200_000).get("tomato");
                check(tom.allReadyAt() == c0 + 200_000 + 4 * 3 * 60_000, "단계가 바뀐 순간으로 안 맞춤: " + (tom.allReadyAt() - c0));
                kr.ddingmate.expert.IslandCrops.debugObserve(new net.minecraft.util.math.BlockPos(2, 64, 1), "onion", 3, 4, c0);
                var oni = kr.ddingmate.expert.IslandCrops.summaries(c0).get("onion");
                check(kr.ddingmate.expert.IslandCrops.status(oni, c0).equals("최대 5분 뒤"), "중간 단계 양파: " + kr.ddingmate.expert.IslandCrops.status(oni, c0));
                int cropAlerts = AlertLog.entries().size();
                kr.ddingmate.expert.IslandCrops.debugObserve(new net.minecraft.util.math.BlockPos(2, 64, 1), "onion", 4, 4, c0 + 1000);
                kr.ddingmate.expert.IslandCrops.debugAnnounce(c0 + 1000);
                kr.ddingmate.expert.IslandCrops.debugAnnounce(c0 + 2000);
                check(AlertLog.entries().size() == cropAlerts + 1 && AlertLog.entries().get(0).title.equals("양파 다 자랐어요"),
                        "양파가 다 자랐는데 한 번 알리지 않음");
                kr.ddingmate.expert.IslandCrops.debugReset();

                // 바닥 아이템 청소(실제 로그: 10분마다, "[청소] 5분 후 …" → "[청소] 떨어진 아이템 N개가 청소되었습니다.")
                kr.ddingmate.game.ServerHud.debugClean("[청소] 5분 후 떨어진 아이템이 전부 청소됩니다.", now);
                check(Math.abs(kr.ddingmate.game.ServerHud.cleanAt() - (now + 5 * 60_000)) < 1500, "청소 5분 전 안내를 못 읽음");
                kr.ddingmate.game.ServerHud.debugClean("[청소] 10초 후 떨어진 아이템이 전부 청소됩니다.", now);
                check(Math.abs(kr.ddingmate.game.ServerHud.cleanAt() - (now + 10_000)) < 1500, "청소 10초 전 안내를 못 읽음");
                kr.ddingmate.game.ServerHud.debugClean("[청소] 떨어진 아이템 170개가 청소되었습니다.", now);
                check(Math.abs(kr.ddingmate.game.ServerHud.cleanAt() - (now + 10 * 60_000)) < 1500, "청소 뒤 다음 청소(10분)를 못 잡음");

                // 광석 골렘: "광석 골렘이 등장했습니다!"(실제 채팅) → 5분 쿨타임. 5분 안 돼 또 나오면 프레스티지(하루 20마리까지 1분)
                kr.ddingmate.expert.OreGolem.debugReset();
                kr.ddingmate.expert.OreGolem.onChat("[칭호] 누군가 : 광석 골렘이 등장했습니다!");
                check(kr.ddingmate.expert.OreGolem.remaining(now) == 0, "사람 채팅으로 골렘 쿨타임을 켬");
                kr.ddingmate.expert.OreGolem.debugHandle("광석 골렘이 등장했습니다!", now - 4 * 60_000);
                check(kr.ddingmate.expert.OreGolem.remaining(now) == 60_000 && !kr.ddingmate.expert.OreGolem.debugPrestige(),
                        "골렘 쿨타임 5분이 아님: " + kr.ddingmate.expert.OreGolem.remaining(now));
                kr.ddingmate.expert.OreGolem.debugHandle("광석 골렘 길들이기에 성공하여 에픽 일꾼 골렘을 획득했습니다.", now - 3 * 60_000);
                check(kr.ddingmate.expert.OreGolem.remaining(now) == 60_000, "길들이기 문구로 쿨타임이 바뀜");
                kr.ddingmate.expert.OreGolem.debugHandle("광석 골렘이 등장했습니다!", now - 2 * 60_000);
                check(kr.ddingmate.expert.OreGolem.debugPrestige() && kr.ddingmate.expert.OreGolem.remaining(now) == 0,
                        "2분 만에 또 나왔는데 프레스티지(1분)로 안 봄: " + kr.ddingmate.expert.OreGolem.remaining(now));
                kr.ddingmate.expert.OreGolem.debugHandle("광석 골렘이 등장했습니다!", now - 10_000);
                check(kr.ddingmate.expert.OreGolem.remaining(now) == 50_000, "프레스티지 쿨타임 1분이 아님: " + kr.ddingmate.expert.OreGolem.remaining(now));

                // 광물 블록 하위 재료: 블록 1개 = 9개(시설 레시피 목록에는 안 넣는다)
                kr.ddingmate.counter.RecipeBook.Recipe dia = kr.ddingmate.counter.RecipeBook.subRecipeFor("다이아몬드 블록");
                check(dia != null && dia.materials.size() == 1 && dia.materials.get(0).name.equals("다이아몬드") && dia.materials.get(0).count == 9,
                        "다이아몬드 블록 하위 재료가 다름");
                check(kr.ddingmate.counter.RecipeBook.subRecipeFor("레드스톤 블록").materials.get(0).name.equals("레드스톤 가루"), "레드스톤 블록 하위 재료");
                check(kr.ddingmate.counter.RecipeBook.recipeFor("구리 블록") == null
                        && kr.ddingmate.counter.RecipeBook.all().stream().noneMatch(r -> r.id.startsWith("block:")), "광물 블록이 시설 레시피에 섞임");

                // 핀 고정: 고정 안 한 핀은 그 레시피 제작을 시작하면 빠지고, 고정한 핀은 남는다. 사람 채팅은 무시
                kr.ddingmate.counter.RecipeBook.Recipe torch = kr.ddingmate.counter.RecipeBook.recipeFor("강화 횃불");
                check(torch != null, "강화 횃불 레시피가 없음");
                DdingMateConfig.Pin p0 = DdingMate.config().counter.pins.get(0);
                String keepRecipe = p0.recipe;
                p0.recipe = torch.id;
                p0.locked = false;
                kr.ddingmate.counter.MaterialCounter.onChat("[칭호] 누군가 : 강화 횃불 아이템 2개 제작을 시작했습니다.");
                check(p0.recipe.equals(torch.id), "사람 채팅으로 핀을 뺌");
                p0.locked = true;
                kr.ddingmate.counter.MaterialCounter.onChat("강화 횃불 아이템 2개 제작을 시작했습니다.");
                check(p0.recipe.equals(torch.id), "고정한 핀을 뺌");
                p0.locked = false;
                kr.ddingmate.counter.MaterialCounter.onChat("강화 횃불 아이템 2개 제작을 시작했습니다.");
                check(p0.recipe.isEmpty(), "고정 안 한 핀을 한 번 만들었는데 안 뺌");
                p0.recipe = keepRecipe;

                // 한글 닉네임: 귓속말·돌아온 내 채팅에서 배우고, 문의에 "windswepty(멜트)"로
                kr.ddingmate.game.Nickname.debugReset();
                kr.ddingmate.game.Nickname.onChat("(바다 → 단무지<받음>) 안녕");
                check(kr.ddingmate.game.Nickname.get() == null || kr.ddingmate.game.Nickname.get().equals("단무지"), "귓속말 받는 쪽");
                kr.ddingmate.game.Nickname.debugReset();
                kr.ddingmate.game.Nickname.onSent("안녕하세요 여러분");
                kr.ddingmate.game.Nickname.onChat("[초보모험가] 멜트 : 안녕하세요 여러분");
                check("멜트".equals(kr.ddingmate.game.Nickname.get()), "돌아온 내 채팅에서 닉네임을 못 배움: " + kr.ddingmate.game.Nickname.get());
                kr.ddingmate.game.Nickname.onChat("(멜트2 → day_to_day<보냄>) 오션오더 타이머는 될꺼같습니다!");
                check("멜트2".equals(kr.ddingmate.game.Nickname.get()), "귓속말 보낸 쪽에서 닉네임을 못 배움");
                kr.ddingmate.game.Nickname.onChat("[달토끼] 망령 : 안녕하세요 여러분");
                check("멜트2".equals(kr.ddingmate.game.Nickname.get()), "남의 채팅으로 닉네임이 바뀜");
                kr.ddingmate.inquiry.Inquiry.Info ni = kr.ddingmate.inquiry.Inquiry.info(c);
                check(ni.displayName().equals(ni.name() + "(멜트2)") && kr.ddingmate.inquiry.Inquiry.payload(
                                kr.ddingmate.inquiry.Inquiry.Category.QUESTION, "닉네임 시험입니다", ni, "x").contains("\"nick\":\"멜트2\""),
                        "문의에 한글 닉네임이 안 감: " + ni.displayName());
                kr.ddingmate.game.Nickname.debugReset();

                // 아이콘: 스킬은 전문가 창 보석(종이 모양 번호), 전문가 도구는 세이지 도구
                net.minecraft.item.ItemStack gem = kr.ddingmate.skill.SkillIcons.forSkill("곡괭이 나가신다!", true, "pickaxe");
                check(gem.isOf(net.minecraft.item.Items.PAPER)
                                && gem.get(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA).floats().get(0) == 2009143f,
                        "곡괭이 나가신다! 아이콘이 다름");
                net.minecraft.item.ItemStack rod = kr.ddingmate.skill.SkillIcons.sageTool("해양");
                check(rod.isOf(net.minecraft.item.Items.FISHING_ROD)
                        && rod.get(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA).floats().get(0) == 2016017f, "세이지 낚싯대가 아님");
                check(kr.ddingmate.skill.SkillIcons.forSkill("없는 스킬", true, "hoe").isOf(net.minecraft.item.Items.GOLDEN_HOE), "모르는 스킬은 세이지 괭이로");

                // 답장 확인 간격: 하루 안에 보낸 문의가 있으면 짧게, 중계가 정하면 그 값
                kr.ddingmate.inquiry.Replies.debugReset();
                kr.ddingmate.inquiry.Replies.debugFastPoll(120_000);
                check(kr.ddingmate.inquiry.Replies.debugInterval() == 10 * 60_000, "기다리는 문의가 없는데 자주 물음");
                kr.ddingmate.inquiry.Inquiry.debugSender((path, body) -> new kr.ddingmate.inquiry.Inquiry.Http(200,
                        path.equals("/inquiry") ? "{\"id\":\"aaaa0000\"}" : "{\"tickets\":[{\"found\":true,\"id\":\"aaaa0000\",\"replies\":[]}],\"pollSec\":90}"));
                kr.ddingmate.inquiry.Inquiry.send(c, kr.ddingmate.inquiry.Inquiry.Category.QUESTION, "답장 빨리 오나 시험", r -> {});
                check(kr.ddingmate.inquiry.Replies.debugInterval() == 120_000, "방금 보낸 문의인데 10분마다 물음");
                kr.ddingmate.inquiry.Replies.debugPoll(c);
                check(kr.ddingmate.inquiry.Replies.debugInterval() == 90_000, "중계가 정한 간격을 안 씀: " + kr.ddingmate.inquiry.Replies.debugInterval());
                kr.ddingmate.inquiry.Inquiry.debugSender(null);
                kr.ddingmate.inquiry.Replies.debugReset();
                kr.ddingmate.inquiry.Replies.debugFastPoll(120_000);
            });
            ctx.waitTicks(25);
            ctx.takeScreenshot("features-6-ocean-petfish-waiting");
            ctx.runOnClient(c -> {
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("전문가");
                s.jumpTo("반려어");
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("features-6b-expert-marine");
            ctx.runOnClient(c -> {
                c.currentScreen.close();
                kr.ddingmate.expert.OceanOrder.debugReset();
                kr.ddingmate.expert.PetFish.debugReset();
                kr.ddingmate.craft.CraftTimers.debugClear();
            });
            ctx.waitTicks(3);

            // 실제 디스코드로 한 번 보내 보기(DDINGMATE_REAL_INQUIRY=1 로 실행할 때만, 평소 시험에서는 보내지 않는다)
            if (System.getenv("DDINGMATE_REAL_INQUIRY") != null) {
                kr.ddingmate.inquiry.Inquiry.Result[] real = new kr.ddingmate.inquiry.Inquiry.Result[1];
                ctx.runOnClient(c -> {
                    check(kr.ddingmate.inquiry.Inquiry.endpoint() != null, "inquiry.properties 의 보낼 곳을 못 읽음");
                    // 이 시험에서 찍은 스크린샷 한 장을 함께 보낸다(실제 첨부 경로 확인)
                    java.util.List<java.nio.file.Path> shot = new java.util.ArrayList<>();
                    try (var files = java.nio.file.Files.list(c.runDirectory.toPath().resolve("screenshots"))) {
                        files.filter(f -> f.toString().endsWith(".png")).sorted().limit(1).forEach(shot::add);
                    } catch (java.io.IOException ex) {
                        throw new RuntimeException(ex);
                    }
                    kr.ddingmate.inquiry.Inquiry.send(c, kr.ddingmate.inquiry.Inquiry.Category.OTHER,
                            "[테스트] 띵메이트 문의 이미지 첨부 확인이에요. 이 메시지는 개발 중 자동 시험에서 보냈어요.", shot, r -> real[0] = r);
                });
                for (int i = 0; i < 30 && real[0] == null; i++) ctx.waitTicks(20);
                ctx.runOnClient(c -> {
                    check(real[0] != null && real[0].ok(), "실제 문의를 보내지 못함: " + (real[0] == null ? "응답 없음" : real[0].message()));
                    kr.ddingmate.DdingMate.LOG.info("실제 문의 보내기 결과: {} / 문의 번호 {}", real[0].message(),
                            kr.ddingmate.inquiry.Replies.debugTickets().get(kr.ddingmate.inquiry.Replies.debugTickets().size() - 1).id);
                });
                // DDINGMATE_REAL_REPLY=1 이면 개발자가 디스코드에서 답할 때까지(최대 5분) 10초마다 실제 중계에 묻는다
                if (System.getenv("DDINGMATE_REAL_REPLY") != null) {
                    boolean[] got = new boolean[1];
                    for (int i = 0; i < 30 && !got[0]; i++) {
                        ctx.runOnClient(c -> {
                            c.inGameHud.getChatHud().clear(false);
                            kr.ddingmate.inquiry.Replies.debugPoll(c);
                        });
                        ctx.waitTicks(200);
                        ctx.runOnClient(c -> {
                            java.util.List<kr.ddingmate.inquiry.Replies.Ticket> ts = kr.ddingmate.inquiry.Replies.debugTickets();
                            got[0] = !ts.get(ts.size() - 1).replies.isEmpty();
                        });
                    }
                    ctx.takeScreenshot("features-real-reply");
                    ctx.runOnClient(c -> {
                        java.util.List<kr.ddingmate.inquiry.Replies.Ticket> ts = kr.ddingmate.inquiry.Replies.debugTickets();
                        check(got[0], "5분 안에 실제 답변을 못 받음");
                        kr.ddingmate.DdingMate.LOG.info("실제 답변 받음: {}", ts.get(ts.size() - 1).replies.get(0).text);
                    });
                }
            }
        }
    }

    private static GenericContainerScreen storageScreen(MinecraftClient c) {
        SimpleInventory inv = new SimpleInventory(54);
        ItemStack copper = new ItemStack(Items.COPPER_BLOCK);
        copper.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("우클릭: 꺼내기"), Text.literal("보관 중: 1,234개"))));
        inv.setStack(0, copper);
        inv.setStack(1, new ItemStack(Items.GRAY_STAINED_GLASS_PANE));
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(78, c.player.getInventory(), inv);
        return new GenericContainerScreen(h, c.player.getInventory(), Text.literal("아이템 저장소"));
    }

    /** 서버 /접속시간 창처럼 보상 별 아이템에 이름·설명을 붙인다(1시간은 받음, 2시간은 안 받음) */
    private static GenericContainerScreen playtimeScreen(MinecraftClient c) {
        SimpleInventory inv = new SimpleInventory(54);
        inv.setStack(10, reward(1, true));
        inv.setStack(12, reward(2, false));
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(79, c.player.getInventory(), inv);
        return new GenericContainerScreen(h, c.player.getInventory(), Text.literal(""));
    }

    private static ItemStack reward(int hour, boolean claimed) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("[ " + hour + "시간 접속 보상 ]"));
        List<Text> lore = new java.util.ArrayList<>(List.of(
                Text.literal(""),
                Text.literal("  현재 접속 시간을 확인할 수 있습니다."),
                Text.literal("  접속을 유지해서 보상을 받아보세요."),
                Text.literal(""),
                Text.literal("   - 오늘 접속 시간 : ").append(Text.literal("11시간 49분 33초"))));
        if (claimed) {
            lore.add(Text.literal(""));
            lore.add(Text.literal("  ! 이미 수령한 보상입니다."));
        }
        s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return s;
    }

    /** 지금 화면에서 이름이 같은 단추를 누른다 */
    private static void press(MinecraftClient c, String label) {
        c.currentScreen.children().stream()
                .filter(e -> e instanceof Widgets.Button b && b.getMessage().getString().equals(label))
                .findFirst().map(e -> (Widgets.Button) e)
                .orElseThrow(() -> new AssertionError("단추 없음: " + label)).onPress();
    }

    private static MaterialCounter.Line line(String name) {
        return MaterialCounter.views().get(0).lines().stream().filter(l -> l.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("재료 줄 없음: " + name));
    }

    private static boolean hasLog(String kind, String titlePart) {
        return AlertLog.entries().stream().anyMatch(e -> e.kind.equals(kind) && e.title.contains(titlePart));
    }

    private static DdingMateConfig.TodoItem item(String preset) {
        return DdingMate.config().todo.items.stream().filter(t -> preset.equals(t.preset)).findFirst().orElseThrow();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
