package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.gear.DurabilityTracker;
import kr.ddingmate.playtime.PlaytimeTracker;
import kr.ddingmate.schedule.EventRule;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 게임 화면 없이 확인할 수 있는 계산·저장·글 읽기를 기대값과 하나하나 비교한다.
 * 기준 시각: 2026-10-09(금) 12:00 한국 시간.
 */
public class LogicClientGameTest implements FabricClientGameTest {

    private static final ZonedDateTime NOON = ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, ScheduleEngine.KST);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            schedule();
            alerts();
            texts();
            recipes();
            config();
        });
    }

    // ---------------------------------------------------------------- 일정 계산

    private static void schedule() {
        Map<String, EventRule> r = ScheduleEngine.byId(ScheduleEngine.buildRules(new ScheduleEngine.Channels(7, 2, 3, 2, 3)));
        expectNext(r, "daily_reset", NOON, at(10, 10, 3, 0));
        expectNext(r, "daily_reset", at(10, 9, 2, 59), at(10, 9, 3, 0));
        expectNext(r, "daily_reset", at(10, 9, 3, 0), at(10, 10, 3, 0)); // 같은 시각은 다음 날
        expectNext(r, "midnight_reset", NOON, at(10, 10, 0, 0));
        expectNext(r, "fishing_contest", NOON, at(10, 9, 22, 0));
        expectNext(r, "invest_deadline", NOON, at(10, 14, 2, 59));       // 다음 수요일
        expectNext(r, "maintenance_start", NOON, at(10, 14, 9, 0));
        expectNext(r, "maintenance_end", NOON, at(10, 14, 15, 0));
        expectNext(r, "weekly_reset", NOON, at(10, 15, 3, 0));           // 다음 목요일
        expectNext(r, "weekly_reset", at(10, 8, 2, 0), at(10, 8, 3, 0));
        expectNext(r, "hunter_affinity", NOON, at(11, 1, 3, 0));         // 다음 달 1일
        expectNext(r, "village_reboot", NOON, at(10, 9, 13, 0));         // 마을 7채널: 1·7·13·19시
        check(!r.containsKey("serenity_reset") && !r.containsKey("hunt_reboot"), "지운 세레니티·노크틸라 일정이 남음");
        // 야생·아일랜드·스폰은 정해진 재시작 시각이 없어 일정을 만들지 않는다
        check(!r.containsKey("wild_reboot") && !r.containsKey("island_reboot") && !r.containsKey("spawn_reboot"),
                "근거 없는 야생·아일랜드·스폰 재시작 일정이 있음");
        // 채널 0 이면 재시작 일정이 없다
        List<EventRule> none = ScheduleEngine.buildRules(new ScheduleEngine.Channels(0, 0, 0, 0, 0));
        check(none.size() == 8 && none.stream().noneMatch(e -> e.id().endsWith("_reboot")), "채널 끔인데 재시작 일정이 있음");
        // 모든 채널 번호에서 규칙이 만들어지고 다음 시각이 있다
        for (int ch = 1; ch <= 35; ch++) {
            for (EventRule e : ScheduleEngine.buildRules(new ScheduleEngine.Channels(ch, ch, ch, ch, ch))) {
                check(ScheduleEngine.next(e, NOON) != null, "다음 시각 없음: " + e.id() + " 채널 " + ch);
            }
        }
        check(ScheduleEngine.formatRemaining(Duration.ofSeconds(3725)).equals("1시간 2분"), "남은 시간 표기(시간)");
        check(ScheduleEngine.formatRemaining(Duration.ofSeconds(185)).equals("3:05"), "남은 시간 표기(분:초)");
        check(ScheduleEngine.formatRemaining(Duration.ofSeconds(90000)).equals("1일 1시간"), "남은 시간 표기(일)");
        check(ScheduleEngine.formatRemaining(Duration.ofSeconds(-5)).equals("0:00"), "지난 시간 표기");
    }

    // ---------------------------------------------------------------- 알림 시점

    private static void alerts() {
        List<EventRule> rules = ScheduleEngine.buildRules(new ScheduleEngine.Channels(0, 0, 0, 0, 0));
        Map<String, Long> fired = new HashMap<>();
        // 02:50:00 → 일일 초기화 10분 전 알림 한 번
        List<ScheduleEngine.Alert> a = ScheduleEngine.dueAlerts(rules, e -> true, EventRule::defaultLeads, at(10, 9, 2, 50), fired);
        check(a.size() == 1 && a.get(0).rule().id().equals("daily_reset") && a.get(0).leadMinutes() == 10,
                "03:00 10분 전 알림 오류: " + a);
        check(ScheduleEngine.dueAlerts(rules, e -> true, EventRule::defaultLeads, at(10, 9, 2, 50).plusSeconds(20), fired).isEmpty(),
                "같은 알림이 두 번 울림");
        // 2분 넘게 늦으면 울리지 않음
        check(ScheduleEngine.dueAlerts(rules, e -> true, EventRule::defaultLeads, at(10, 9, 2, 52).plusSeconds(1), new HashMap<>()).isEmpty(),
                "지난 알림이 늦게 울림");
        // 끈 일정은 울리지 않음
        check(ScheduleEngine.dueAlerts(rules, e -> !e.id().equals("daily_reset"), EventRule::defaultLeads, at(10, 9, 2, 50), new HashMap<>()).isEmpty(),
                "끈 일정이 울림");
        // 0분 알림: 점검 종료 15:00 → 15:00:30 에 울림
        a = ScheduleEngine.dueAlerts(rules, e -> true, EventRule::defaultLeads, at(10, 14, 15, 0).plusSeconds(30), new HashMap<>());
        check(a.stream().anyMatch(x -> x.rule().id().equals("maintenance_end") && x.leadMinutes() == 0), "점검 종료 알림 없음: " + a);
        // 사용자가 정한 알림 시점(3분 전)
        a = ScheduleEngine.dueAlerts(rules, e -> true, e -> List.of(3), at(10, 9, 2, 57), new HashMap<>());
        check(a.size() == 1 && a.get(0).leadMinutes() == 3, "직접 정한 3분 전 알림 오류: " + a);
        // 오래된 기록 정리
        fired.put("old", at(10, 9, 0, 0).toEpochSecond());
        ScheduleEngine.dueAlerts(rules, e -> true, EventRule::defaultLeads, at(10, 9, 2, 50), fired);
        check(!fired.containsKey("old"), "지난 알림 기록이 정리되지 않음");
    }

    // ---------------------------------------------------------------- 글 읽기

    private static void texts() {
        check(ItemKeys.normalize("§6[강화석] 코룸  주괴").equals("코룸주괴"), "아이템 이름 정리 오류");
        check(stored("보관 중: 1,234개") == 1234, "저장소 개수 '보관 중: 1,234개'");
        check(stored("저장된 수량 50") == 50, "저장소 개수 '저장된 수량 50'");
        check(stored("적재량 : 5,000 / 5,000") == 5000, "저장소 개수 '적재량'");
        check(stored("우클릭: 꺼내기") == -1, "숫자 없는 설명을 개수로 읽음");
        check(ItemKeys.storedAmount(new ItemStack(Items.PAPER)) == -1, "설명 없는 아이템을 개수로 읽음");

        ItemStack lore = new ItemStack(Items.PAPER);
        lore.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("내구도 : 236 / 1,938"))));
        DurabilityTracker.Durability d = DurabilityTracker.read(lore);
        check(d != null && d.left() == 236 && d.max() == 1938, "설명 문구 내구도 읽기 오류: " + d);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.setDamage(100);
        d = DurabilityTracker.read(pick);
        check(d != null && d.left() == 1461 && d.max() == 1561, "다이아 곡괭이 내구도 오류: " + d);
        check(DurabilityTracker.read(new ItemStack(Items.DIRT)) == null, "흙에 내구도가 있다고 읽음");

        List<String> aliases = List.of("ㄱ", "가방");
        check(BagTracker.parseBagNumber("ㄱ3", aliases) == 3, "ㄱ3");
        check(BagTracker.parseBagNumber("/ㄱ 3", aliases) == 3, "/ㄱ 3");
        check(BagTracker.parseBagNumber("가방 5", aliases) == 5, "가방 5");
        check(BagTracker.parseBagNumber("/가방 9", aliases) == 9, "/가방 9");
        check(BagTracker.parseBagNumber("ㄱ10", aliases) == 0, "ㄱ10 은 가방 번호가 아님");
        check(BagTracker.parseBagNumber("ㄱ0", aliases) == 0, "ㄱ0 은 가방 번호가 아님");
        check(BagTracker.parseBagNumber("ㄱㄱ1", aliases) == 0, "ㄱㄱ1 은 가방 번호가 아님");
        check(BagTracker.parseBagNumber("안녕 ㄱ1", aliases) == 0, "문장 속 ㄱ1 을 단축어로 봄");
        // 띵타이쿤 가방 명령어는 설정이 비어 있어도 알아본다(실제 명령 기록: /ㄱ1, /가방 2, /.ㄱ2)
        check(BagTracker.parseBagNumber("ㄱ1", List.of()) == 1, "설정 없이 ㄱ1 을 못 알아봄");
        check(BagTracker.parseBagNumber("가방 2", List.of()) == 2, "설정 없이 가방 2 를 못 알아봄");
        check(BagTracker.parseBagNumber(".ㄱ2", List.of()) == 2, "설정 없이 .ㄱ2 를 못 알아봄");
        // 실제 사용자 설정처럼 번호까지 적은 단축어("ㄱ1", "ㄱ2")도 그대로 그 번호
        check(BagTracker.parseBagNumber("ㄱ2", List.of("ㄱ1", "ㄱ2")) == 2, "번호까지 적은 단축어를 못 알아봄");
        check(BagTracker.parseBagNumber("ㄹ1", List.of("ㄹ")) == 1, "직접 적은 앞부분 단축어를 못 알아봄");
        check(BagTracker.parseBagNumber("짐3", List.of("짐3")) == 3, "번호까지 적은 직접 단축어를 못 알아봄");
        check(BagTracker.parseBagNumber("짐33", List.of("짐3")) == 0, "짐33 을 3번으로 봄");
        // 가방 번호를 몰라도(서버 가방 창에 번호가 안 보임) 내용으로 같은 가방을 알아본다.
        // 가방 안은 그 창을 열었을 때만 바뀌므로, 열었을 때 내용이 지난 기록과 같으면 같은 가방
        List<String> a = List.of("v:minecraft:cobblestone*64", "", "v:minecraft:coal*3");
        List<String> b2 = List.of("", "v:minecraft:raw_copper*12", "");
        List<String> c3 = List.of("v:minecraft:torch*5", "", "");
        java.util.Map<String, List<String>> bags = new java.util.TreeMap<>();
        check(BagTracker.debugResolve(bags, a, 0) == 1, "처음 보는 가방을 1번 자리로 기록하지 않음");
        bags.put("1", a);
        check(BagTracker.debugResolve(bags, a, 0) == 1, "같은 내용인데 다른 가방으로 봄");
        check(BagTracker.debugResolve(bags, b2, 0) == 2, "다른 내용인데 같은 가방으로 봄(덮어써서 사라짐)");
        bags.put("2", b2);
        // 명령어로 번호를 알게 되면 바로잡는다: 2번 자리에 둔 내용(b2)이 사실 1번 가방이었다 → 서로 자리를 바꾼다
        check(BagTracker.debugResolve(bags, b2, 1) == 1 && bags.get("1").equals(b2) && bags.get("2").equals(a),
                "명령어 번호로 자리를 바로잡지 않음: " + bags);
        // 그 번호 자리에 전혀 다른 가방 기록이 있으면 지우지 않고 빈 번호로 옮긴다(합계에서 사라지지 않게)
        check(BagTracker.debugResolve(bags, c3, 2) == 2 && bags.containsValue(a) && !bags.containsKey("2") && bags.size() == 2,
                "그 번호 자리의 다른 가방 기록을 옮기지 않음: " + bags);

        check(PlaytimeTracker.nextRewardHour(0) == 1 && PlaytimeTracker.nextRewardHour(3600) == 2
                && PlaytimeTracker.nextRewardHour(5 * 3600) == 8 && PlaytimeTracker.nextRewardHour(8 * 3600) == 0,
                "다음 접속 보상 구간 계산 오류");
    }

    private static int stored(String line) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("우클릭: 꺼내기"), Text.literal(line))));
        return ItemKeys.storedAmount(s);
    }

    // ---------------------------------------------------------------- 레시피 자료

    private static void recipes() {
        Set<String> ids = new HashSet<>();
        for (RecipeBook.Recipe r : RecipeBook.all()) {
            check(ids.add(r.id), "레시피 id 중복: " + r.id);
            check(r.name != null && !r.name.isBlank() && r.facility != null && r.category != null, "레시피 정보 빠짐: " + r.id);
            check(r.output >= 1, "결과 개수 오류: " + r.id);
            check(!r.materials.isEmpty(), "재료 없음: " + r.id);
            for (RecipeBook.Material m : r.materials) {
                check(m.name != null && !m.name.isBlank() && m.count >= 1, "재료 오류: " + r.id + " " + m.name);
            }
            check(RecipeBook.get(r.id) == r, "id 로 다시 찾지 못함: " + r.id);
        }
        check(ids.size() == 373, "레시피 수가 373 이 아님: " + ids.size());
        check(RecipeBook.keysFor("구리 블록").contains("v:minecraft:copper_block"), "구리 블록 바닐라 연결 없음");
        check(RecipeBook.keysFor("코룸 주괴").equals(Set.of("n:코룸주괴")), "서버 아이템 키 오류");
        check(RecipeBook.recipeFor("조약돌 뭉치") != null, "조약돌 뭉치 레시피를 찾지 못함");
    }

    // ---------------------------------------------------------------- 설정 저장

    private static void config() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("ddingmate.json");
        DdingMateConfig cfg = DdingMate.config();
        cfg.sanitize();
        cfg.save();
        try {
            String original = Files.readString(p, StandardCharsets.UTF_8);
            // 바꾼 값이 저장·불러오기 뒤에도 그대로인가
            cfg.villageChannel = 12;
            cfg.hudScale = 70;
            cfg.keywords.words = new java.util.ArrayList<>(List.of("테스트", "단어"));
            cfg.events.get("daily_reset").leads = new java.util.ArrayList<>(List.of(30, 5));
            cfg.hudSizes.put("counter", 130);
            cfg.save();
            DdingMateConfig loaded = DdingMateConfig.load();
            check(loaded.villageChannel == 12 && loaded.hudScale == 70 && loaded.keywords.words.equals(List.of("테스트", "단어"))
                    && loaded.events.get("daily_reset").leads.equals(List.of(30, 5)) && loaded.hudSizes.get("counter") == 130,
                    "설정 저장·불러오기 후 값이 다름");
            // 범위를 벗어난 값은 바로잡힌다
            loaded.hudScale = 999;
            loaded.hudLines = 0;
            loaded.counter.pins.get(0).crafts = -5;
            loaded.sanitize();
            check(loaded.hudScale == 100 && loaded.hudLines == 1 && loaded.counter.pins.get(0).crafts == 1, "범위 밖 값 보정 오류");
            // 깨진 설정 파일이면 기본값으로 시작하고 게임은 멈추지 않는다
            Files.writeString(p, "{ 이건 깨진 파일 ", StandardCharsets.UTF_8);
            DdingMateConfig broken = DdingMateConfig.load();
            check(broken != null && broken.hudEnabled && broken.counter.pins.size() == DdingMateConfig.PIN_COUNT
                    && broken.events.containsKey("daily_reset"), "깨진 설정 파일에서 기본값으로 돌아가지 못함");
            // 원래 설정으로 되돌림
            Files.writeString(p, original, StandardCharsets.UTF_8);
            DdingMate.reloadConfig();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- 도구

    private static ZonedDateTime at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ScheduleEngine.KST);
    }

    private static void expectNext(Map<String, EventRule> rules, String id, ZonedDateTime now, ZonedDateTime expected) {
        ZonedDateTime got = ScheduleEngine.next(rules.get(id), now);
        check(expected.equals(got), id + " 다음 시각: 기대 " + expected + ", 실제 " + got);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
