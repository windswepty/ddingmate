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
                TodoList.onChat("ꀂ 무역 아이템을 12,345골드에 판매하였습니다.");
                check(TodoList.isDone(trade, now) && TodoList.remaining(now).size() == before - 1, "무역 메시지로 체크되지 않음");
                // 의뢰: 주간 의뢰 창이 열려 있지 않으면 일일 의뢰, 열려 있었으면 주간 의뢰
                TodoList.QuestContext.debugSet(false);
                for (int i = 0; i < 7; i++) TodoList.onChat(" 의뢰를 완료하였습니다.");
                check(TodoList.progress(daily, now) == 5 && TodoList.isDone(daily, now), "일일 의뢰를 5번에서 멈추지 않음: " + TodoList.progress(daily, now));
                TodoList.QuestContext.debugSet(true);
                TodoList.onChat(" 의뢰를 완료하였습니다.");
                check(TodoList.progress(weekly, now) == 1, "주간 의뢰 창에서 완료한 것을 주간으로 세지 않음");
                TodoList.QuestContext.debugSet(false);
                TodoList.onChat(" 의뢰를 완료하지 않았습니다.");
                TodoList.onChat("[칭호] 누군가 : 의뢰를 완료하였습니다.");
                check(TodoList.progress(weekly, now) == 1, "관계없는 메시지로 셈");
                // 지난 초기화 이전에 센 것은 0
                daily.doneAt = TodoList.lastReset(daily.reset, now).minusMinutes(1).toInstant().toEpochMilli();
                check(TodoList.progress(daily, now) == 0, "지난 초기화 이전 기록이 0 으로 돌아가지 않음");
                // 접속 보상: /접속시간 창의 받은 보상 수로 맞추고 줄이지 않는다
                TodoList.sync(kr.ddingmate.todo.TodoPreset.PLAYTIME, 4);
                check(TodoList.progress(item("playtime"), now) == 4, "받은 보상 수로 맞추지 않음");
                TodoList.sync(kr.ddingmate.todo.TodoPreset.PLAYTIME, 2);
                check(TodoList.progress(item("playtime"), now) == 4, "받은 보상 수가 줄어듦");
                TodoList.onChat(" 5시간 접속 보상을 수령하였습니다.");
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
                check(old.todo.items.size() == 1, "알아서 못 세는 할 일이 남음: " + old.todo.items.stream().map(t -> t.name).toList());
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
                ((DdingMateScreen) c.currentScreen).openTab(7);
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
                ((DdingMateScreen) c.currentScreen).openTab(3);
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-2c-channels-auto");
            ctx.runOnClient(c -> c.setScreen(null));

            // 12-5) 다 한 할 일은 HUD 체크 상자에 체크 표시
            ctx.runOnClient(c -> {
                DdingMate.config().todo.items = new java.util.ArrayList<>(DdingMateConfig.Todo.defaults());
                DdingMate.config().todo.hudEnabled = true;
                TodoList.onChat(" 마인리스트 추천 보상 우편이 도착했습니다. [/우편함]에서 확인할 수 있습니다.");
                TodoList.QuestContext.debugSet(false);
                TodoList.onChat(" 의뢰를 완료하였습니다.");
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
                check(RecipeBook.all().size() >= 378, "레시피 수가 줄어듦: " + RecipeBook.all().size());
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
            ctx.runOnClient(c -> {
                DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(1);
                pin.recipe = "강화 제작 시설/하급 라이프스톤";
                pin.crafts = 3;
                MaterialCounter.tick(c);
                var views = MaterialCounter.views();
                var cols = kr.ddingmate.hud.CounterHud.layout(views, 1000, true);
                check(cols.size() == 1, "높이 제한이 없는데 칸이 나뉨: " + cols.size());
                var narrow = kr.ddingmate.hud.CounterHud.layout(views, 70, true);
                int rows = 0;
                for (var col : narrow) for (var sg : col) rows += sg.to() - sg.from();
                int all = 0;
                for (var v : views) all += v.lines().size() + v.lines().stream().mapToInt(l -> l.subs().size()).sum();
                check(narrow.size() > 1 && rows == all, "낮은 높이에서 줄이 옆 칸으로 안 넘어가거나 줄이 빠짐: " + narrow.size() + "칸 " + rows + "/" + all);
                check(narrow.stream().skip(1).anyMatch(col -> col.get(0).continued()), "이어지는 칸에 (이어서) 표시가 없음");
                for (var col : narrow) {
                    int h = 3;
                    for (var sg : col) h += sg.height();
                    check(h <= 70 || col.size() == 1, "칸 높이가 정한 높이를 넘음: " + h);
                }
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
                check(r != null && (r.h() <= Math.round(70 * sc) + 2 || r.w() >= screenW * 0.8) && r.x() + r.w() <= screenW + 1,
                        "높이를 줄였는데 HUD 가 낮아지지 않음: " + tall[1] + " → " + (r == null ? null : r.h()) + " (너비 " + tall[0] + " → " + (r == null ? null : r.w()) + ")");
                HudLayout.setHeight(HudLayout.COUNTER, 0);
                DdingMate.config().counter.pins.get(1).recipe = "";
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
                ItemStack platinum = new ItemStack(Items.PAPER, 3);
                platinum.set(DataComponentTypes.CUSTOM_NAME, Text.literal("플래티늄 주괴"));
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
            for (int i = 0; i < names.length; i++) {
                int tab = 6 + i;
                ctx.runOnClient(c -> {
                    DdingMateScreen s = new DdingMateScreen(null);
                    c.setScreen(s);
                    s.openTab(tab);
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
