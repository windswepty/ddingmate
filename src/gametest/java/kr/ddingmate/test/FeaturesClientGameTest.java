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
        check(TodoList.lastReset(DdingMateConfig.Reset.WEEKLY, fri2am).equals(ZonedDateTime.of(2026, 10, 8, 3, 0, 0, 0, ScheduleEngine.KST)),
                "목요일 03시 초기화 계산 오류");
        check(TodoList.lastReset(DdingMateConfig.Reset.WEEKLY, fri2am.minusDays(1).withHour(2))
                        .equals(ZonedDateTime.of(2026, 10, 1, 3, 0, 0, 0, ScheduleEngine.KST)),
                "목요일 03시 직전 주간 초기화 계산 오류");
        check(TodoList.lastReset(DdingMateConfig.Reset.MIDNIGHT, fri2am).equals(fri2am.withHour(0)), "자정 초기화 계산 오류");

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(c -> {
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

            // 3) 오늘 할 일: 하나 체크하면 남은 개수가 하나 줄어든다
            ctx.runOnClient(c -> {
                ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
                int before = TodoList.remaining(now).size();
                TodoList.setDone(DdingMate.config().todo.items.get(0), true);
                check(TodoList.remaining(now).size() == before - 1, "할 일 체크가 반영되지 않음");
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
                check(RecipeBook.all().size() >= 373, "레시피 수가 줄어듦: " + RecipeBook.all().size());
                check(facilities.size() == 16, "시설 수가 16개가 아님: " + facilities);
                pin.recipe = keep;
                pin.crafts = keepCrafts;
                MaterialCounter.tick(c);
            });

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
                int cx = c.getWindow().getScaledWidth() / 2, cy = c.getWindow().getScaledHeight() / 2;
                check(Math.abs(r.x() + 5 - cx) <= 2 && Math.abs(r.y() + 5 - cy) <= 2, "옮긴 자리에 그려지지 않음: " + r);
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
                HudLayout.Rect d = HudLayout.bounds().get(HudLayout.DURABILITY);
                double hx = d.x() + d.w() - 2, hy = d.y() + d.h() - 2;
                s.mouseClicked(hx, hy, 0);
                s.mouseDragged(hx + d.w() * 0.3, hy, 0, 0, 0);
                s.mouseReleased(hx + d.w() * 0.3, hy, 0);
                check(HudLayout.size(HudLayout.DURABILITY) == 130, "모서리 손잡이로 크기가 바뀌지 않음: " + HudLayout.size(HudLayout.DURABILITY));
                press(c, "−");
                check(HudLayout.size(HudLayout.DURABILITY) == 120, "[−] 로 고른 HUD 가 작아지지 않음");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("features-4c-hud-sizes");
            ctx.runOnClient(c -> {
                press(c, "저장");
                check(DdingMate.config().hudSizes.get(HudLayout.COUNTER) == 120
                        && DdingMate.config().hudSizes.get(HudLayout.DURABILITY) == 120, "HUD 별 크기가 저장되지 않음");
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

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
