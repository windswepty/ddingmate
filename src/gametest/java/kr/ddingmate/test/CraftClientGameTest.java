package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.craft.CraftTimers;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.notify.AlertLog;
import kr.ddingmate.ui.Widgets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 제작 완료 타이머 점검. 서버 메시지와 시설 창 설명은 실제 게임 로그에서 확인한 문장을 그대로 쓴다:
 *  - "조약돌 뭉치 아이템 7개 제작을 시작했습니다." / "…7개를 제작대에서 회수했습니다." (앞에 서버 그림 글자)
 *  - 레시피 칸 설명: "제작 시간 : 20초", "(좌) 해당 레시피 선택", "(쉬프트+우) 레시피 제거"
 *  - 여러 번 넣으면 하나씩 차례대로 끝난다(20·40·60·80초)
 * 대기열 칸의 남은 시간 표기는 아직 실제로 못 봐서 여러 표기를 시험한다.
 * 덤으로 ◀ 단추(이전 값)와 장비 목록 중복 정리도 확인한다.
 */
public class CraftClientGameTest implements FabricClientGameTest {

    /** 서버 메시지 앞에 붙는 그림 글자(실제 로그: U+CFFFB, U+A002, U+11661) */
    private static final String G = new String(Character.toChars(0xCFFFB)) + "\uA002" + new String(Character.toChars(0x11661)) + " ";
    private static final String START = G + "조약돌 뭉치 아이템 7개 제작을 시작했습니다.";
    private static final String COLLECT = G + "조약돌 뭉치 아이템 7개를 제작대에서 회수했습니다.";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                cfg.tutorialDone = true;
                cfg.opened = true;
                cfg.hudEnabled = true;
                cfg.hudLines = 2;
                cfg.craft.enabled = true;
                cfg.craft.notify = true;
                cfg.craft.showInHud = true;
                cfg.craft.mining = cfg.craft.farming = cfg.craft.marine = cfg.craft.hunting = 0;
                CraftTimers.debugClear();
            });

            // 1) 설명 줄 읽기
            ctx.runOnClient(c -> {
                CraftTimers.Reading recipe = CraftTimers.read(lines("다양한 제작에 사용되는 재료입니다.", "", "  제작 시간 : 20초", "",
                        " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거"));
                check(recipe != null && recipe.recipe() && recipe.totalSec() == 20, "실제 레시피 칸 설명을 읽지 못함: " + recipe);
                // 레시피 칸이 아닌 칸(대기열)의 "제작 시간"은 그 제작의 남은 시간(실제: 1시간 30분 0초 → 1초 뒤 1시간 29분 59초)
                CraftTimers.Reading q = CraftTimers.read(lines("신비로운 힘이 깃들어 있는 스톤입니다.", "", "  제작 시간 : 1시간 29분 33초", ""));
                check(q != null && !q.recipe() && q.own() && q.remainingSec() == 5373, "대기열 칸의 제작 시간을 남은 시간으로 읽지 못함: " + q);
                check(CraftTimers.read(lines("  제작 시간 : 1분 0초")).remainingSec() == 60, "'1분 0초'를 60초로 읽지 못함");
                remain("남은 시간 : 2분 30초", 150);
                remain("§7남은 시간: §e2분 30초", 150);
                remain("제작 완료까지 1시간 2분 3초", 3723);
                remain("남은 시간 : 01:02:03", 3723);
                remain("남은 시간 02:30", 150);
                remain("남은 시간 : 45초", 45);
                remain("1일 2시간 남음", 26 * 3600);
                CraftTimers.Reading done = CraftTimers.read(lines("제작 완료!", "클릭하여 회수"));
                check(done != null && done.done(), "다 된 표시를 알아보지 못함");
                check(CraftTimers.read(lines("남은 시간 : 0초")).done(), "남은 시간 0초를 완료로 보지 않음");
                check(CraftTimers.read(lines("보관 중: 1,234개", "우클릭: 꺼내기")) == null, "시간이 아닌 설명을 시간으로 읽음");
                check(CraftTimers.read(lines("[강화석] 2등급")) == null, "숫자만 있는 설명을 시간으로 읽음");
                check(CraftTimers.read(lines("제작 완료까지 2분 10초")).remainingSec() == 130, "'완료까지'를 다 된 표시로 착각함");
            });

            // 2) 시작 메시지 4번(시간 아직 모름) → 한 줄 "조약돌 뭉치 ×7 [4]" · "시설 창 열면 표시". 관계없는 메시지는 무시
            ctx.runOnClient(c -> {
                for (int i = 0; i < 4; i++) CraftTimers.onChat(START);
                CraftTimers.onChat("[칭호] 누군가 : 조약돌 뭉치 아이템 7개 제작을 시작했습니다.");
                CraftTimers.onChat(G + "어빌리티 스톤 아이템을 회수하였습니다.");
                CraftTimers.onChat(G + "제작에 필요한 재료가 부족합니다.");
                CraftTimers.onChat(G + "아직 제작이 완료되지 않은 아이템입니다.");
                check(CraftTimers.jobs().size() == 4, "제작 수가 4가 아님: " + CraftTimers.jobs().size());
                List<CraftTimers.Group> g = CraftTimers.groups();
                check(g.size() == 1 && g.get(0).label().equals("조약돌 뭉치 ×7 [4]"), "한 줄로 묶이지 않음: " + labels(g));
                // 시설 창을 안 열었어도 모드에 넣어 둔 시간으로 바로 센다: 1개 20초 × 7개 = 140초씩 4번 = 약 560초
                String t0 = CraftTimers.remainingText(g.get(0), now());
                // 서버에서 확인하기 전(시설 창을 아직 안 봄)에는 어림 숫자("약 9분") 대신 확인 방법을 보여 준다
                check(t0.equals("시설 창 열면 표시"), "확인 전인데 어림 시간을 보여 줌: " + t0);
                // 시간을 정말 모르는 아이템은 "시설 창 열면 표시"
                CraftTimers.onChat(G + "시험용 미지 아이템 아이템 1개 제작을 시작했습니다.");
                CraftTimers.Group unknown = CraftTimers.groups().stream().filter(x -> x.name().equals("시험용 미지 아이템")).findFirst().orElseThrow();
                check(CraftTimers.remainingText(unknown, now()).equals("시설 창 열면 표시"), "시간을 모르는데 시간을 표시함");
                CraftTimers.remove(unknown);
                CraftTimers.onChat(G + "강화 횃불 아이템 1,024개 제작을 시작했습니다.");
                check(CraftTimers.jobs().stream().anyMatch(x -> x.name.equals("강화 횃불") && x.count == 1024), "쉼표가 든 개수를 읽지 못함");
                CraftTimers.debugClear();
                for (int i = 0; i < 4; i++) CraftTimers.onChat(START);
            });

            // 3) 시설 창의 레시피 칸(실제 설명)을 보면 1개 20초를 배우고, 7개씩 넣은 순서대로 140·280·420·560초로 계산. 레시피 칸은 제작으로 등록하지 않음
            ctx.runOnClient(c -> c.setScreen(facility(c,
                    recipeItem("\uA002조약돌 뭉치", "20초"),
                    recipeItem("하급 라이프스톤", "1분 0초"),
                    recipeItem("어빌리티 스톤", "2분 0초"))));
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.secondsPerJob("조약돌 뭉치") == 20, "제작 1번 시간을 배우지 못함: " + CraftTimers.secondsPerJob("조약돌 뭉치"));
                check(CraftTimers.secondsPerJob("하급 라이프스톤") == 60, "하급 라이프스톤 시간을 배우지 못함");
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(jobs.size() == 4, "레시피 칸을 제작으로 잘못 등록함: " + jobs.size());
                long first = jobs.get(0).startedAt;
                for (int i = 0; i < 4; i++) {
                    long expect = first + 140_000L * (i + 1);
                    check(Math.abs(jobs.get(i).endsAt - expect) < 3000, (i + 1) + "번째가 차례대로 계산되지 않음: " + (jobs.get(i).endsAt - first));
                }
                CraftTimers.Group g = CraftTimers.groups().get(0);
                check(g.label().equals("조약돌 뭉치 ×7 [4]"), "묶음 이름이 다름: " + g.label());
                String t = CraftTimers.remainingText(g, now());
                check(t.equals("시설 창 열면 표시"), "레시피 시간만 알고 실제 시간은 확인 전인데 숫자를 보여 줌: " + t);
                c.setScreen(null);
            });

            // 3-1) 전문가 스킬: 설명의 제작 시간은 스킬 전 시간. 채광 1레벨(10%)이면 강화 제작 시설의 조약돌 뭉치 20초 → 18초
            //      (실제 로그와 같음). 레벨을 바꾸면 안 끝난 제작을 다시 계산하고, 되돌리면 원래대로
            ctx.runOnClient(c -> {
                DdingMateConfig.Craft cr = DdingMate.config().craft;
                check(kr.ddingmate.counter.RecipeBook.facilityOf("조약돌 뭉치").equals("강화 제작 시설"), "조약돌 뭉치 시설을 모름");
                check(kr.ddingmate.counter.RecipeBook.facilityOf("강화 횃불").equals("채광물 가공 시설"), "강화 횃불 시설을 모름");
                cr.mining = 1;
                CraftTimers.recalculate();
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long first = jobs.get(0).startedAt;
                for (int i = 0; i < 4; i++) {
                    check(Math.abs(jobs.get(i).endsAt - (first + 126_000L * (i + 1))) < 3000,
                            "채광 1레벨인데 " + (i + 1) + "번째가 126초(7개×18초) 간격이 아님: " + (jobs.get(i).endsAt - first));
                }
                CraftTimers.debugLearn("강화 횃불", 2);
                // 서버는 줄어든 시간을 초 단위로 올린다: 2초 × 0.9 = 1.8초 → 2초
                check(CraftTimers.durationMs("강화 횃불") == 2000, "강화 횃불 2초 × 0.9 를 초 단위로 올리지 않음: " + CraftTimers.durationMs("강화 횃불"));
                cr.mining = 5;
                check(CraftTimers.durationMs("조약돌 뭉치") == 4000, "채광 5레벨(80%)이 아님: " + CraftTimers.durationMs("조약돌 뭉치"));
                cr.marine = 4;
                cr.hunting = 5;
                cr.farming = 3;
                check(CraftTimers.reductionPercent("해양 제작 시설") == 50 && CraftTimers.reductionPercent("연금 제작 시설") == 50,
                        "해양 4레벨(50%)이 아님");
                check(CraftTimers.reductionPercent("영혼 가공 시설") == 80, "사냥 5레벨(80%)이 아님");
                check(CraftTimers.reductionPercent("농작물 가공 시설") == 40 && CraftTimers.reductionPercent("요리 제작 시설") == 40,
                        "재배 3레벨(40%)이 아님");
                check(CraftTimers.reductionPercent("대형 제작대") == 0 && CraftTimers.reductionPercent("황금 요리 제작 시설") == 0,
                        "스킬이 없는 시설까지 줄임");
                cr.mining = cr.farming = cr.marine = cr.hunting = 0;
                CraftTimers.recalculate();
                jobs = CraftTimers.jobs();
                check(Math.abs(jobs.get(3).endsAt - (first + 560_000)) < 3000, "스킬을 되돌렸는데 원래 시간으로 돌아오지 않음");
            });

            // 4) 배운 뒤 하나 더 넣으면 맨 뒤에 붙는다(100초). 다른 아이템은 그 뒤에 이어서
            ctx.runOnClient(c -> {
                CraftTimers.onChat(START);
                CraftTimers.onChat(G + "하급 라이프스톤 아이템 12개 제작을 시작했습니다.");
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long first = jobs.get(0).startedAt;
                check(Math.abs(jobs.get(4).endsAt - (first + 700_000)) < 3000, "다섯 번째가 맨 뒤에 붙지 않음: " + (jobs.get(4).endsAt - first));
                // 하급 라이프스톤 1개 60초 × 12개 = 720초
                check(Math.abs(jobs.get(5).endsAt - (first + 1_420_000)) < 3000, "다른 아이템이 앞 제작 뒤에 이어지지 않음: " + (jobs.get(5).endsAt - first));
                List<String> order = labels(CraftTimers.groups());
                check(order.equals(List.of("조약돌 뭉치 ×7 [5]", "하급 라이프스톤 ×12")), "묶음·순서가 다름: " + order);
            });

            // 5) 대기열 칸에 남은 시간이 보이면 맨 앞 제작을 그 값으로 맞추고, 뒤 제작도 같은 만큼 옮긴다
            ctx.runOnClient(c -> c.setScreen(facility(c, queueItem("조약돌 뭉치", 7, "남은 시간 : 10초"))));
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                long now = now();
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(Math.abs(jobs.get(0).endsAt - (now + 10_000)) < 2500 && jobs.get(0).synced, "맨 앞 제작을 창 값으로 맞추지 않음");
                check(Math.abs(jobs.get(1).endsAt - (now + 150_000)) < 2500, "뒤 제작을 같이 옮기지 않음: " + (jobs.get(1).endsAt - now));
                check(Math.abs(jobs.get(5).endsAt - (now + 1_290_000)) < 2500, "다른 아이템도 같이 옮기지 않음: " + (jobs.get(5).endsAt - now));
                check(CraftTimers.jobs().size() == 6, "대기열 칸을 새 제작으로 또 등록함");
                c.setScreen(null);
            });
            ctx.waitTicks(25); // 일정 HUD 는 1초마다 새로 그린다
            ctx.takeScreenshot("craft-1-hud-grouped");

            // 6) 일부만 끝나면 알리지 않고 "[2] 완료!" 와 "[3] 남은 시간" 두 줄. 같은 것이 모두 끝나야 한 번 알림
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long now = now();
                for (CraftTimers.Job j : jobs) j.synced = true; // 서버에서 확인된 제작이라고 친다(확인 안 된 제작은 알리지 않는다)
                jobs.get(0).endsAt = now - 3000;
                jobs.get(1).endsAt = now - 1000;
                int before = countDone();
                CraftTimers.tick();
                check(countDone() == before, "같은 제작이 남았는데 알림이 울림");
                List<String> g = labels(CraftTimers.groups());
                check(g.contains("조약돌 뭉치 ×7 [2]") && g.contains("조약돌 뭉치 ×7 [3]"), "끝난 것과 남은 것이 나뉘지 않음: " + g);
                check(CraftTimers.groups().get(0).done(), "끝난 묶음이 맨 위가 아님");
                for (int i = 2; i < 5; i++) jobs.get(i).endsAt = now - 500;
                CraftTimers.tick();
                CraftTimers.tick();
                check(countDone() == before + 1, "모두 끝났을 때 한 번만 알리지 않음: " + (countDone() - before));
                String title = AlertLog.entries().stream().filter(e -> e.title != null && e.title.startsWith("제작 완료"))
                        .max(java.util.Comparator.comparingLong(e -> e.at)).orElseThrow().title;
                check(title.equals("제작 완료: 조약돌 뭉치 ×7 [5]"), "알림 제목이 다름: " + title);
            });
            ctx.waitTicks(25);
            ctx.takeScreenshot("craft-2-done");

            // 7) 꺼내면(회수 메시지) 끝난 것부터 하나씩 빠진다
            ctx.runOnClient(c -> {
                CraftTimers.onChat(COLLECT);
                CraftTimers.onChat(COLLECT);
                check(CraftTimers.jobs().stream().filter(j -> j.name.equals("조약돌 뭉치")).count() == 3, "회수한 만큼 빠지지 않음");
                check(labels(CraftTimers.groups()).contains("조약돌 뭉치 ×7 [3]"), "남은 묶음 표시가 다름: " + labels(CraftTimers.groups()));
            });

            // 8) 향장품: 대기열과 따로, 서버가 알려 준 10분으로 정확하게. 수령하면 빠진다
            ctx.runOnClient(c -> {
                long lifeBefore = CraftTimers.jobs().stream().filter(j -> j.name.equals("하급 라이프스톤")).findFirst().orElseThrow().endsAt;
                CraftTimers.onChat(G + "향장품 제조를 시작했습니다. 10분 후 제조가 완료됩니다.");
                CraftTimers.Job p = CraftTimers.jobs().stream().filter(j -> j.name.equals(CraftTimers.PERFUME)).findFirst().orElseThrow();
                check(p.separate && p.synced && Math.abs(p.endsAt - (now() + 600_000)) < 3000, "향장품 시간을 읽지 못함");
                long lifeAfter = CraftTimers.jobs().stream().filter(j -> j.name.equals("하급 라이프스톤")).findFirst().orElseThrow().endsAt;
                check(lifeBefore == lifeAfter, "향장품이 제작 대기열 시간을 바꿈");
                CraftTimers.onChat(G + "제조 완료된 향장품을 성공적으로 수령했습니다.");
                check(CraftTimers.jobs().stream().noneMatch(j -> j.name.equals(CraftTimers.PERFUME)), "향장품을 받았는데 남음");
            });

            // 9) 시작 메시지를 놓친 제작: 대기열 칸의 남은 시간으로 등록. 같은 이름의 레시피 칸은 등록하지 않음
            ctx.runOnClient(c -> c.setScreen(facility(c, queueItem("코룸 주괴", 24, "남은 시간 : 2분"), recipeItem("코룸 주괴", "30초"))));
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> coroom = CraftTimers.jobs().stream().filter(j -> j.name.equals("코룸 주괴")).toList();
                check(coroom.size() == 1 && coroom.get(0).count == 24 && coroom.get(0).synced
                        && Math.abs(coroom.get(0).endsAt - (now() + 120_000)) < 2500, "놓친 제작을 창에서 등록하지 못함: " + coroom.size());
                c.setScreen(null);
            });

            // 9-000) 실제 2026-10-10 조약돌 뭉치 7개(레시피 20초, 채광 2레벨 30%) → 서버 14초(개수만큼 안 늘어남).
            //        넣은 직후 칸의 "제작 시간 : 14초"로 실제 시간을 배워, 뒤에 넣은 7개 묶음·다른 개수도 맞게 센다
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 2;
                CraftTimers.onChat(G + "조약돌 뭉치 아이템 7개 제작을 시작했습니다.");
                c.setScreen(facility(c, queueItem("조약돌 뭉치", 7, "  제작 시간 : 14초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.durationMs("조약돌 뭉치", 7) == 14_000, "실제 14초를 배우지 못함: " + CraftTimers.durationMs("조약돌 뭉치", 7));
                c.setScreen(null);
                CraftTimers.onChat(G + "조약돌 뭉치 아이템 7개 제작을 시작했습니다.");
                CraftTimers.onChat(G + "조약돌 뭉치 아이템 14개 제작을 시작했습니다.");
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(Math.abs(jobs.get(1).endsAt - jobs.get(0).endsAt - 14_000) < 1_100, "두 번째 7개가 14초 뒤가 아님: " + (jobs.get(1).endsAt - jobs.get(0).endsAt));
                check(Math.abs(jobs.get(2).endsAt - jobs.get(1).endsAt - 28_000) < 1_100, "14개(7개씩 2번)가 28초가 아님: " + (jobs.get(2).endsAt - jobs.get(1).endsAt));
                String t = CraftTimers.remainingText(CraftTimers.groups().get(0), now());
                check(!t.contains("분") && !t.contains("약") && !t.equals("시설 창 열면 표시"),
                        "배운 실제 시간으로 정확히 보여 주지 않음(예전: 약 몇 분으로 부풀려짐): " + t);
                check(!CraftTimers.groups().get(0).estimated(), "실제 시간을 배웠는데 확인 전으로 봄");
                // 스킬이 바뀌면 그 전에 본 시간은 버린다(한 번에 7개라는 것은 남는다)
                DdingMate.config().craft.mining = 3;
                CraftTimers.skillChanged();
                check(CraftTimers.durationMs("조약돌 뭉치", 7) == 12_000, "채광 3레벨(40%)로 다시 계산하지 않음: " + CraftTimers.durationMs("조약돌 뭉치", 7));
                // 한 창에 같은 이름이 서로 다른 시간으로 보이면(실측: 조약돌 뭉치 5초·20초) 어느 쪽도 배우지 않는다
                c.setScreen(facility(c, recipeItem("조약돌 뭉치", "5초"), recipeItem("조약돌 뭉치", "20초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.secondsPerJob("조약돌 뭉치") == 20, "한 창의 서로 다른 레시피 시간 중 하나를 배움: " + CraftTimers.secondsPerJob("조약돌 뭉치"));
                c.setScreen(null);
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 0;
            });

            // 9-00000) 섬 그림: 섬 칸은 투명 버튼(종이 2025)이고 그림은 창 제목의 "sailing/ui/island" 글꼴 글자로 찍힌다
            //          (2026-10 리소스팩). 제목 글자가 그려지는 자리로 섬 칸과 짝짓는다(시험용 글꼴: U+A001 20×16 ascent -21, U+A102 22×15 ascent -39)
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                kr.ddingmate.craft.IslandIcons.debugClear();
                net.minecraft.util.Identifier font = net.minecraft.util.Identifier.of("e94a3c2f-10aa-4d92-9ed0-c73f74fef20e", "sailing/ui/island");
                Text title = Text.empty().append(Text.literal("ꀁꄂ").styled(st -> st.withFont(font)));
                SimpleInventory inv = new SimpleInventory(54);
                inv.setStack(9, item("[ 토르엔 빠른 항해 ]", 1, "  항해를 통해 다양한 아이템을 획득해보세요.", "  - 남은 시간 : 1시간 0분 0초"));
                inv.setStack(20, item("[ 라비온 보상 수령 ]", 1, "  항해를 통해 다양한 아이템을 획득해보세요.", "클릭 시 항해 보상을 수령합니다."));
                GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(91, c.player.getInventory(), inv);
                c.setScreen(new GenericContainerScreen(h, c.player.getInventory(), title));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                String a = kr.ddingmate.craft.IslandIcons.debugValue("토르엔");
                String b = kr.ddingmate.craft.IslandIcons.debugValue("라비온");
                check(a != null && a.endsWith("#" + 0xA001), "토르엔 칸 위 섬 그림(U+A001)과 짝짓지 못함: " + a);
                check(b != null && b.endsWith("#" + 0xA102), "라비온 칸 위 섬 그림(U+A102)과 짝짓지 못함: " + b);
                check(kr.ddingmate.craft.IslandIcons.has("토르엔 항해") && !kr.ddingmate.craft.IslandIcons.has("조약돌 뭉치"), "섬 그림 찾기가 틀림");
                c.setScreen(null);
                kr.ddingmate.gui.DdingMateScreen s = new kr.ddingmate.gui.DdingMateScreen(null);
                c.setScreen(s);
                s.openTab(0);
            });
            ctx.waitTicks(5);
            ctx.runOnClient(c -> c.currentScreen.children().stream()
                    .filter(e -> e instanceof net.minecraft.client.gui.widget.ElementListWidget<?>)
                    .forEach(e -> ((net.minecraft.client.gui.widget.ElementListWidget<?>) e).setScrollY(10000)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("craft-island-icons");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                CraftTimers.debugClear();
            });

            // 9-0000) 실제 2026-10-09 22:26 시작 버튼 "[  강화 횃불 제작 시작하기 ]" "- 제작 시간 : 6.3초" → 1개 시작 → 대기열 7초.
            //         넣는 순간 버튼 시간(올림 7초)으로 정확히 센다. 소수 초를 3초로 잘못 읽지 않는다
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                check(CraftTimers.read(lines("- 제작 시간 : 6.3초")).remainingSec() == 7, "6.3초를 7초(올림)로 읽지 않음: " + CraftTimers.read(lines("- 제작 시간 : 6.3초")));
                c.setScreen(facility(c, item("[  강화 횃불 제작 시작하기 ]", 1, "재료를 소모해 아이템을 제작할 수 있습니다.",
                        "제작 시작 후 제작 대기열에 추가됩니다.", "- 제작 시간 : 6.3초", "클릭 시 제작 을 시작합니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.jobs().isEmpty(), "시작 버튼을 제작으로 등록함");
                c.setScreen(null);
                CraftTimers.onChat(G + "강화 횃불 아이템 1개 제작을 시작했습니다.");
                check(CraftTimers.durationMs("강화 횃불", 1) == 7_000, "시작 버튼 시간(7초)을 쓰지 않음: " + CraftTimers.durationMs("강화 횃불", 1));
                String t = CraftTimers.remainingText(CraftTimers.groups().get(0), now());
                check(!t.equals("시설 창 열면 표시") && !CraftTimers.groups().get(0).estimated(), "버튼으로 확실한 시간인데 확인 전으로 보임: " + t);
                // 귓속말로 흉내 낸 서버 문구는 세지 않는다
                CraftTimers.onChat("(단무지 → 멜트<받음>) 강화 횃불 아이템 1개 제작을 시작했습니다.");
                check(CraftTimers.jobs().size() == 1, "귓속말을 제작 시작으로 셈");
                // 항해: 보내는 순간 줄이 생기고(시간은 항해 창에서), 항해도를 초기화하면 지난 줄은 모두 지운다
                CraftTimers.onChat(G + "벨로아(으)로 항해를 시작했습니다.");
                CraftTimers.Group v = CraftTimers.groups().stream().filter(g -> g.name().equals("벨로아 항해")).findFirst().orElse(null);
                check(v != null && CraftTimers.remainingText(v, now()).equals("항해 창 열면 표시"), "항해 시작 채팅으로 줄을 만들지 않음: " + labels(CraftTimers.groups()));
                CraftTimers.onChat(G + "성공적으로 항해도를 초기화했습니다.");
                check(CraftTimers.groups().stream().noneMatch(g -> g.name().endsWith("항해")), "항해도 초기화 뒤 지난 항해 줄이 남음");
                CraftTimers.debugClear();
            });

            // 9-000b) 2026-10 사용자 제보
            //  ① 귀중품 40초인데 1시간: 다른 시설의 긴 제작 뒤에 이어 붙였다 → 시설이 다르면 따로 센다
            //  ② 어빌리티 스톤 40개(진행 중, 모드가 모름) 뒤에 7개를 넣었는데 7개 완료 알림이 먼저 → 창의 모르는 진행 칸도 자리를 차지
            //  ③ 조약돌 뭉치 1개 5초 / 7개 20초 레시피가 따로 있다 → 7개를 5초×7로 세지 않는다
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 0;
                CraftTimers.debugLearn("어빌리티 스톤", 120);
                CraftTimers.debugLearn("(Common) 키론 오르골", 45);
                CraftTimers.onChat(G + "어빌리티 스톤 아이템 30개 제작을 시작했습니다.");   // 강화 제작 시설, 1시간
                CraftTimers.onChat(G + "키론 오르골 아이템 1개 제작을 시작했습니다.");      // 채광물 가공 시설, 45초
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                CraftTimers.Job orgel = jobs.stream().filter(j -> j.name.equals("키론 오르골")).findFirst().orElseThrow();
                long left = orgel.endsAt - orgel.startedAt;
                check(left > 0 && left < 60_000, "다른 시설의 1시간 제작 뒤에 귀중품(45초)을 이어 붙임: " + left + " / 대기열 " + orgel.queue);
                CraftTimers.debugClear();
                // ② 모드가 모르는 40개(남은 40분) 다음에 7개를 넣음 → 7개는 40분 + 7개 시간 뒤
                CraftTimers.onChat(G + "어빌리티 스톤 아이템 7개 제작을 시작했습니다.");
                c.setScreen(facility(c,
                        queueItem("어빌리티 스톤", 40, "  제작 시간 : 40분 0초"),
                        queueItem("어빌리티 스톤", 7, "  제작 시간 : 14분 0초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(jobs.size() == 1, "창의 모르는 진행 칸을 새 제작으로 등록함: " + jobs.size());
                long left = jobs.get(0).endsAt - now();
                check(Math.abs(left - (40 * 60_000 + 14 * 60_000)) < 3_000, "7개를 앞 40개(모르는 제작)가 끝난 뒤부터 세지 않음: " + left);
                c.setScreen(null);
                CraftTimers.debugClear();
                // ③ 시설 창에 조약돌 뭉치 1개(5초)·7개(20초) 레시피가 함께 보임 → 개수별로 따로 기억
                DdingMate.config().craft.mining = 2; // 30%
                ItemStack seven = item("조약돌 뭉치", 7, "설명", "", "  제작 시간 : 20초", "", " (좌) 해당 아이템 제작 시작", " (쉬프트+우) 레시피 고정");
                ItemStack one = item("조약돌 뭉치", 1, "설명", "", "  제작 시간 : 5초", "", " (좌) 해당 아이템 제작 시작", " (쉬프트+우) 레시피 고정");
                c.setScreen(facility(c, one, seven));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.durationMs("조약돌 뭉치", 7) == 14_000, "7개짜리(20초×0.7=14초)가 아님(5초×7?): " + CraftTimers.durationMs("조약돌 뭉치", 7));
                check(CraftTimers.durationMs("조약돌 뭉치", 1) == 4_000, "1개짜리(5초×0.7=3.5→4초)가 아님: " + CraftTimers.durationMs("조약돌 뭉치", 1));
                c.setScreen(null);
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 0;
                // 보석·귀중품: 이름엔 등급이 없고 배지로만(실제 "라온" 2045016) → 모델 번호로 "라온 (Common)"
                ItemStack raon = new ItemStack(Items.PAPER);
                raon.set(DataComponentTypes.CUSTOM_NAME, Text.literal("라온"));
                raon.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                        new net.minecraft.component.type.CustomModelDataComponent(List.of(2045016f), List.of(), List.of(), List.of()));
                check(kr.ddingmate.counter.ItemKeys.keyOf(raon).equals(kr.ddingmate.counter.ItemKeys.nameKey("라온 (Common)")),
                        "보석 등급을 못 알아봄: " + kr.ddingmate.counter.ItemKeys.keyOf(raon));
                ItemStack terraon = kr.ddingmate.counter.IconCache.forMaterial("테라온 (Normal)");
                var tcmd = terraon == null ? null : terraon.get(DataComponentTypes.CUSTOM_MODEL_DATA);
                check(tcmd != null && tcmd.floats().get(0) == 2045008f, "테라온 그림이 2번 묶음(2045008)이 아님: " + tcmd);
                // 이름 없이 온 서버 아이템(실제: 상점 창에서 툴팁이 "종이")은 모델 번호로 알아본다
                ItemStack bare = new ItemStack(Items.PAPER);
                bare.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                        new net.minecraft.component.type.CustomModelDataComponent(List.of(2037005f), List.of(), List.of(), List.of()));
                check(kr.ddingmate.counter.ItemKeys.keyOf(bare).equals(kr.ddingmate.counter.ItemKeys.nameKey("토파즈 주괴")),
                        "이름 없는 토파즈 주괴(모델 2037005)를 못 알아봄: " + kr.ddingmate.counter.ItemKeys.keyOf(bare));
                check(kr.ddingmate.counter.ItemKeys.keyOf(new ItemStack(Items.PAPER)).equals("v:minecraft:paper"), "그냥 종이를 다른 아이템으로 봄");
                // 세트 단위 보기
                DdingMate.config().counter.showSets = true;
                check(kr.ddingmate.hud.CounterHud.amount(80).equals("1세트 16") && kr.ddingmate.hud.CounterHud.amount(40).equals("40")
                        && kr.ddingmate.hud.CounterHud.amount(128).equals("2세트"), "세트 표시가 다름");
                DdingMate.config().counter.showSets = false;
                check(kr.ddingmate.hud.CounterHud.amount(80).equals("80"), "세트 보기를 껐는데 세트로 보임");
                // 요리 효율: 지금 가격을 아는 요리만, 원재료당 골드 순
                kr.ddingmate.expert.Cooking.debugClear();
                kr.ddingmate.expert.Cooking.debugPrice("토마토 스파게티", 570, now());
                kr.ddingmate.expert.Cooking.debugPrice("토마토 라자냐", 2757, now());
                var ranks = kr.ddingmate.expert.Cooking.ranking(false);
                check(ranks.size() == 2 && ranks.get(0).perItem() >= ranks.get(1).perItem(), "요리 순위가 효율 순이 아님");
                check(ranks.get(0).rawItems() > 0, "원재료 개수를 못 셈");
                kr.ddingmate.expert.Cooking.debugClear();
                // 바리스타: 가진 만큼만 팔아도 되고(나눠 팔기), 합계는 표의 판매가로
                var lines = List.of(new kr.ddingmate.expert.Barista.Line("아메리카노", 2, 1, 45_999),
                        new kr.ddingmate.expert.Barista.Line("콜드 브루", 1, 1, 51_110));
                check(kr.ddingmate.expert.Barista.total(lines) == 45_999 + 51_110 && kr.ddingmate.expert.Barista.fullTotal(lines) == 2 * 45_999 + 51_110,
                        "커피 판매 합계 계산이 다름");
                // 전문가 탭 화면
                kr.ddingmate.expert.Cooking.debugPrice("토마토 스파게티", 570, now());
                kr.ddingmate.expert.Cooking.debugPrice("토마토 라자냐", 2757, now());
                kr.ddingmate.expert.Cooking.debugPrice("양파 수프", 1800, now() - 30L * 24 * 3600_000);
                kr.ddingmate.gui.DdingMateScreen s = new kr.ddingmate.gui.DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("전문가");
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("expert-1-top");
            ctx.runOnClient(c -> c.currentScreen.children().stream()
                    .filter(e -> e instanceof net.minecraft.client.gui.widget.ElementListWidget<?>)
                    .forEach(e -> ((net.minecraft.client.gui.widget.ElementListWidget<?>) e).setScrollY(10000)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("expert-2-cooking");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                kr.ddingmate.expert.Cooking.debugClear();
            });

            // 9-000c) 2026-10-15 실제 서버 손님 창: 별 아이콘 설명에 주문이 적혀 있다(창에 커피 아이템은 없음)
            ctx.runOnClient(c -> {
                kr.ddingmate.expert.Barista.debugClear();
                DdingMate.config().expert.coffeeBonus = 1;
                var b = kr.ddingmate.expert.Barista.debugRead(star("[ (B등급) 빈센트의 주문 ]", "", "빈센트의 주문을 확인할 수 있습니다.",
                        "요구하는 고급 커피를 판매해 보세요.", "", "  - 우드 라떼 [판매 0/2개] : 183,900골드", "", "남은 판매 가격 : 183,900골드"));
                check(b != null && b.customer.equals("빈센트") && b.grade.equals("B") && b.lines.size() == 1, "빈센트 주문을 못 읽음: " + b);
                var line = b.lines.get(0);
                check(line.coffee.equals("우드 라떼") && line.want == 2 && line.sold == 0 && line.perCup == 91_950 && b.remaining == 183_900,
                        "우드 라떼 주문 값이 다름: " + line.coffee + " " + line.sold + "/" + line.want + " " + line.perCup + " / " + b.remaining);
                // 이미 1잔 판 주문: 줄 가격 합 = 남은 판매 가격이면 남은 잔 기준 → 한 잔 40,888G
                var s2 = kr.ddingmate.expert.Barista.debugRead(star("[ (C등급) 샐리의 주문 ]", "  - 블랙 커피 [판매 1/3개] : 81,776골드",
                        "  - 카페 모카 [판매 0/1개] : 30,666골드", "남은 판매 가격 : 112,442골드"));
                check(s2 != null && s2.lines.size() == 2 && s2.lines.get(0).perCup == 40_888 && s2.lines.get(0).left() == 2,
                        "판매한 잔이 있는 주문의 한 잔 값이 다름: " + (s2 == null ? null : s2.lines.get(0).perCup));
                check(kr.ddingmate.expert.Barista.orders().size() == 2 && kr.ddingmate.expert.Barista.orders().get(0).customer.equals("샐리"),
                        "손님 순서(샐리 → 빈센트)가 아님");
                var needs = kr.ddingmate.expert.Barista.needs(java.util.Map.of("우드 라떼", 1, "블랙 커피", 5));
                var wood = needs.stream().filter(n -> n.coffee().equals("우드 라떼")).findFirst().orElseThrow();
                var black = needs.stream().filter(n -> n.coffee().equals("블랙 커피")).findFirst().orElseThrow();
                check(wood.toMake() == 1 && black.toMake() == 0 && black.left() == 2, "만들 커피 계산이 다름: " + needs);
                check(kr.ddingmate.expert.Barista.sellableNow(needs) == 91_950 + 2 * 40_888, "지금 팔 수 있는 금액이 다름: " + kr.ddingmate.expert.Barista.sellableNow(needs));
                check(kr.ddingmate.expert.Barista.remainingTotal() == 183_900 + 112_442, "남은 주문 합계가 다름");
                // 03시 전에 읽은 주문은 합계에서 뺀다
                kr.ddingmate.expert.Barista.debugAge("샐리", now() - 2L * 24 * 3600_000);
                check(kr.ddingmate.expert.Barista.remainingTotal() == 183_900, "지난 주문을 합계에 넣음");
                kr.ddingmate.expert.Barista.debugAge("샐리", now());
                // 실제 창처럼: 별 아이콘만 있는 창 + 인벤토리에 우드 라떼 1잔
                var pinv = c.player.getInventory();
                for (int i = 0; i < pinv.size(); i++) pinv.setStack(i, ItemStack.EMPTY);
                pinv.setStack(0, item("우드 라떼", 1, "에픽 등급 커피"));
                ItemStack vincent = star("[ (B등급) 빈센트의 주문 ]", "", "빈센트의 주문을 확인할 수 있습니다.",
                        "요구하는 고급 커피를 판매해 보세요.", "", "  - 우드 라떼 [판매 0/2개] : 183,900골드", "", "남은 판매 가격 : 183,900골드");
                SimpleInventory inv = new SimpleInventory(54);
                inv.setStack(22, vincent);
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(91, c.player.getInventory(), inv),
                        c.player.getInventory(), Text.literal("빈센트")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> check(kr.ddingmate.expert.Barista.kind() == kr.ddingmate.expert.Barista.Kind.CUSTOMER
                    && "빈센트".equals(kr.ddingmate.expert.Barista.currentCustomer()), "손님 창으로 알아보지 못함: " + kr.ddingmate.expert.Barista.kind()));
            ctx.takeScreenshot("barista-1-customer-panel");
            // 인스턴트 커피 손님 창: 가진 잔 수 × 20,000G
            ctx.runOnClient(c -> {
                c.player.getInventory().setStack(1, item("인스턴트 커피", 3, "커피"));
                SimpleInventory inv = new SimpleInventory(54);
                inv.setStack(22, item("인스턴트 커피", 1, "손님에게 판매할 수 있는 커피입니다."));
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(92, c.player.getInventory(), inv),
                        c.player.getInventory(), Text.literal("손님")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> check(kr.ddingmate.expert.Barista.kind() == kr.ddingmate.expert.Barista.Kind.INSTANT, "인스턴트 커피 창으로 알아보지 못함"));
            ctx.takeScreenshot("barista-2-instant-panel");
            // 밀키 상점: 같은 황금 요리를 만드는 법이 둘이어도 순위에는 한 번만
            ctx.runOnClient(c -> {
                c.setScreen(null);
                kr.ddingmate.expert.Cooking.debugClear();
                kr.ddingmate.expert.Cooking.debugPrice("황금 토마토 파인애플 피자", 1270, now());
                kr.ddingmate.expert.Cooking.debugPrice("황금 딥 크림 빠네", 3369, now());
                kr.ddingmate.expert.Cooking.debugPrice("토마토 스파게티", 394, now());
                kr.ddingmate.expert.Cooking.debugPrice("딥 크림 빠네", 2003, now());
                var ranks = kr.ddingmate.expert.Cooking.ranking(false);
                check(ranks.stream().filter(r -> r.recipe().name.equals("황금 토마토 파인애플 피자")).count() == 1,
                        "같은 요리가 순위에 두 번 나옴: " + ranks.stream().map(r -> r.recipe().name).toList());
                check(ranks.stream().filter(r -> r.recipe().name.equals("황금 토마토 파인애플 피자")).allMatch(r -> !r.variant().isEmpty()),
                        "어느 만드는 법인지 표시가 없음");
                check(kr.ddingmate.expert.Cooking.dishCount() == 30, "요리 수가 30이 아님: " + kr.ddingmate.expert.Cooking.dishCount());
                check(kr.ddingmate.expert.Cooking.byPrice().get(0).recipe().name.equals("황금 딥 크림 빠네"), "가장 비싼 요리가 다름");
                c.player.getInventory().setStack(2, item("딥 크림 빠네", 5, "요리"));
                SimpleInventory inv = new SimpleInventory(54);
                inv.setStack(10, item("토마토 스파게티", 1, "판매 가격 : 394G"));
                inv.setStack(11, item("딥 크림 빠네", 1, "판매 가격 : 2,003G"));
                inv.setStack(12, item("황금 딥 크림 빠네", 1, "판매 가격 : 3,369G"));
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(93, c.player.getInventory(), inv),
                        c.player.getInventory(), Text.literal("밀키")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.expert.Cooking.shopOpen(), "요리 상점 창으로 알아보지 못함");
                check(kr.ddingmate.expert.Cooking.inventoryDishes(c).getOrDefault("딥 크림 빠네", 0) == 5, "인벤토리 요리를 세지 못함");
            });
            ctx.takeScreenshot("cooking-3-shop-panel");
            // 제작 시설 창: 가진 재료로 만들 수 있는 레시피 판, 누르면 빈 핀에 넣는다
            String[] keepPins = new String[3];
            boolean[] keepSum = new boolean[2];
            ctx.runOnClient(c -> {
                c.setScreen(null);
                keepSum[0] = DdingMate.config().counter.includeBags;
                keepSum[1] = DdingMate.config().counter.includeStorage;
                DdingMate.config().counter.includeBags = false; // 앞 시험에서 기록한 가방·저장소 조약돌은 빼고 센다
                DdingMate.config().counter.includeStorage = false;
                var pins = DdingMate.config().counter.pins;
                for (int i = 0; i < 3; i++) {
                    keepPins[i] = pins.get(i).recipe;
                    pins.get(i).recipe = "";
                }
                var pinv = c.player.getInventory();
                for (int i = 0; i < pinv.size(); i++) pinv.setStack(i, ItemStack.EMPTY);
                pinv.setStack(0, new ItemStack(Items.COBBLESTONE, 64));
                pinv.setStack(1, new ItemStack(Items.COBBLESTONE, 64));
                c.setScreen(facility(c,
                        item("조약돌 뭉치", 1, "다양한 제작에 사용되는 재료입니다.", "", "  제작 시간 : 5초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 고정"),
                        item("하급 라이프스톤", 1, "세이지 도구 강화에 필요합니다.", "", "  제작 시간 : 1분 0초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거"),
                        item("어빌리티 스톤", 1, "신비로운 힘이 깃들어 있는 스톤입니다.", "", "  제작 시간 : 2분 0초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거")));
            });
            ctx.waitTicks(12);
            ctx.takeScreenshot("facility-1-craftable-panel");
            ctx.runOnClient(c -> {
                var es = kr.ddingmate.craft.FacilityPanel.entries();
                check(!es.isEmpty() && es.stream().allMatch(e -> e.recipe().facility.equals("강화 제작 시설") && e.owned() > 0),
                        "시설 판이 강화 제작 시설의 보유 재료 레시피가 아님: " + es.stream().map(e -> e.recipe().facility + "/" + e.recipe().name + " " + e.owned()).toList());
                check(es.stream().noneMatch(e -> e.recipe().name.equals("하급 라이프스톤") || e.recipe().name.equals("어빌리티 스톤")),
                        "재료가 하나도 없는 레시피(고정 칸 하급 라이프스톤 등)를 추천함");
                check(es.get(0).recipe().name.equals("조약돌 뭉치") && es.get(0).craftable() == 2 && es.stream().skip(1).allMatch(e -> e.craftable() == 0),
                        "보유 재료 순서가 아님: " + es.stream().map(e -> e.recipe().name + " " + e.craftable()).toList());
                int[] at = kr.ddingmate.craft.FacilityPanel.debugRow(0);
                check(at != null, "시설 판을 그리지 않음");
                // 실제 게임과 같은 길: 마우스 누르기 전에 부르는 Fabric 화면 이벤트(띵메이트 판이 받으면 창으로 넘기지 않음)
                boolean passed = net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(c.currentScreen).invoker()
                        .allowMouseClick(c.currentScreen, at[0], at[1], 0);
                check(!passed, "판을 누른 것이 시설 창으로 넘어감");
                check(DdingMate.config().counter.pins.get(0).recipe.equals(es.get(0).recipe().id), "누른 레시피를 빈 핀에 넣지 않음: " + DdingMate.config().counter.pins.get(0).recipe);
                // 오른쪽 클릭: 그 핀을 뺀다
                boolean passed2 = net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(c.currentScreen).invoker()
                        .allowMouseClick(c.currentScreen, at[0], at[1], 1);
                check(!passed2 && DdingMate.config().counter.pins.get(0).recipe.isEmpty(), "오른쪽 클릭으로 핀을 빼지 않음: " + DdingMate.config().counter.pins.get(0).recipe);
                // 클릭 한 번 = 핀, 한 번 더 = 핀 빼기 / 쉬프트+클릭 한 번 = 레시피, 한 번 더 = 닫기
                kr.ddingmate.craft.FacilityPanel.debugAct(es.get(0).recipe(), 0, false);
                check(DdingMate.config().counter.pins.get(0).recipe.equals(es.get(0).recipe().id), "클릭으로 핀이 안 들어감");
                kr.ddingmate.craft.FacilityPanel.debugAct(es.get(0).recipe(), 0, false);
                check(DdingMate.config().counter.pins.get(0).recipe.isEmpty(), "핀 상태에서 한 번 더 눌러도 안 빠짐");
                kr.ddingmate.craft.FacilityPanel.debugAct(es.get(0).recipe(), 0, true);
                check(kr.ddingmate.craft.FacilityPanel.detail() == es.get(0).recipe(), "쉬프트+클릭으로 레시피가 안 열림");
                kr.ddingmate.craft.FacilityPanel.debugAct(es.get(0).recipe(), 0, true);
                check(kr.ddingmate.craft.FacilityPanel.detail() == null, "쉬프트+클릭 한 번 더 해도 레시피가 안 닫힘");
                // 눌러야 할 칸 강조: 쉬프트+클릭한 레시피가 창에 보이면 그 칸(안내 줄 없음), 안 보이면 어디로 가야 하는지 한 줄
                kr.ddingmate.craft.FacilityPanel.debugAct(es.get(0).recipe(), 0, true);
                check(es.get(0).recipe().id.equals(kr.ddingmate.craft.FacilityPanel.debugFocus())
                                && kr.ddingmate.craft.FacilityPanel.debugRecipeSlots().containsKey(es.get(0).recipe().id)
                                && kr.ddingmate.craft.FacilityPanel.debugHint() == null,
                        "창에 보이는 레시피 칸을 강조하지 않음: " + kr.ddingmate.craft.FacilityPanel.debugRecipeSlots());
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("facility-1b-highlight");
            ctx.runOnClient(c -> {
                var es = kr.ddingmate.craft.FacilityPanel.entries();
                if (es.size() > 1) {
                    kr.ddingmate.craft.FacilityPanel.debugAct(es.get(1).recipe(), 0, true);
                    check(kr.ddingmate.craft.FacilityPanel.debugHint() != null && kr.ddingmate.craft.FacilityPanel.debugHint().startsWith("→"),
                            "창에 없는 레시피인데 어디로 가야 하는지 안내가 없음");
                }
                // 스위치를 끄면 강조하지 않는다(재료 판은 그대로)
                DdingMate.config().counter.facilityHighlight = false;
                check(kr.ddingmate.craft.FacilityPanel.debugFocus() == null && kr.ddingmate.craft.FacilityPanel.debugHint() == null, "꺼도 강조함");
                DdingMate.config().counter.facilityHighlight = true;
                kr.ddingmate.craft.FacilityPanel.debugDetail(null);
                check(kr.ddingmate.craft.FacilityPanel.debugFocus() == null, "재료 판을 닫았는데 강조가 남음");
            });
            // 경로 배우기: 창에 없는 레시피는, 사용자가 한 번 칸(예: 레시피 목록)을 눌러 그 레시피가 나타나면 그 길을 기억해 다음부터 그 칸을 강조
            String[] target = new String[1];
            ctx.runOnClient(c -> {
                kr.ddingmate.craft.FacilityPanel.debugForgetPaths();
                target[0] = "하급 라이프스톤"; // 같은 강화 제작 시설의, 첫 화면에 없는 레시피
                // 첫 화면: 조약돌 뭉치(레시피) + 레시피 목록 단추(칸 11)
                c.setScreen(facility(c,
                        item("조약돌 뭉치", 1, "다양한 제작에 사용되는 재료입니다.", "", "  제작 시간 : 5초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 고정"),
                        item("레시피 목록", 1, "클릭하면 레시피 목록을 엽니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                var r1 = kr.ddingmate.counter.RecipeBook.get("강화 제작 시설/" + target[0]);
                check(r1 != null, "하급 라이프스톤 레시피가 없음");
                kr.ddingmate.craft.FacilityPanel.debugAct(r1, 0, true);
                check(kr.ddingmate.craft.FacilityPanel.debugTargetSlot() < 0 && kr.ddingmate.craft.FacilityPanel.debugHint().contains("처음엔"),
                        "길을 모르는데 강조함: " + kr.ddingmate.craft.FacilityPanel.debugHint());
                // 사용자가 '레시피 목록'을 누름 → 서버가 목록 창을 연다(그 레시피 줄이 보임)
                var button = ((GenericContainerScreen) c.currentScreen).getScreenHandler().slots.get(11);
                kr.ddingmate.craft.FacilityPanel.debugClickSlot(button);
                c.setScreen(facility(c,
                        item(target[0], 1, "재료입니다.", "", "  제작 시간 : 10초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 고정"),
                        item("뒤로", 1, "이전 화면으로")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                var es = kr.ddingmate.craft.FacilityPanel.entries();
                check(kr.ddingmate.craft.FacilityPanel.debugFocus() != null, "같은 시설의 다른 창을 열었는데 강조가 꺼짐");
                check(kr.ddingmate.craft.FacilityPanel.debugTargetSlot() == 10, "목록 창에서 그 레시피 칸을 강조하지 않음: " + kr.ddingmate.craft.FacilityPanel.debugTargetSlot());
                // 다시 첫 화면으로 → 이번엔 '레시피 목록' 칸(11)을 강조
                c.setScreen(facility(c,
                        item("조약돌 뭉치", 1, "다양한 제작에 사용되는 재료입니다.", "", "  제작 시간 : 5초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 고정"),
                        item("레시피 목록", 1, "클릭하면 레시피 목록을 엽니다.")));
            });
            ctx.waitTicks(12);
            ctx.takeScreenshot("facility-1c-learned-path");
            ctx.runOnClient(c -> {
                check(kr.ddingmate.craft.FacilityPanel.debugTargetSlot() == 11 && kr.ddingmate.craft.FacilityPanel.debugHint().contains("반짝이는"),
                        "기억한 길(레시피 목록)을 강조하지 않음: " + kr.ddingmate.craft.FacilityPanel.debugTargetSlot() + " / " + kr.ddingmate.craft.FacilityPanel.debugHint());
                kr.ddingmate.craft.FacilityPanel.debugDetail(null);
                kr.ddingmate.craft.FacilityPanel.debugForgetPaths();
                // 탭 순서 = 시설 레시피 분류 순서(실제 9개 시설 107개 중 105개 일치)
                check(kr.ddingmate.craft.FacilityPanel.debugTabOf("채광물 가공 시설/세르칸 큐브") == 50
                                && kr.ddingmate.craft.FacilityPanel.debugTabOf("연금 제작 시설/" + firstOf("연금 제작 시설", "핵")) == 46
                                && kr.ddingmate.craft.FacilityPanel.debugTabOf("해양 제작 시설/" + firstOf("해양 제작 시설", "열쇠")) == 45,
                        "탭 순서 규칙이 다름");
                check("레시피 목록 → 6번째 탭(형광 큐브) → 세르칸 큐브".equals(kr.ddingmate.craft.FacilityPanel.debugFullPath("채광물 가공 시설/세르칸 큐브")),
                        "전체 길이 다름: " + kr.ddingmate.craft.FacilityPanel.debugFullPath("채광물 가공 시설/세르칸 큐브"));
                // 창 모양을 아직 확인 못 한 시설은 길을 지어내지 않는다(목록 창을 한 번 보면 확인)
                check(kr.ddingmate.craft.FacilityPanel.debugFullPath("농작물 가공 시설/" + firstOf("농작물 가공 시설", "베이스")) == null,
                        "확인 못 한 시설에 길을 지어냄");
                // 첫 화면(0번 = 이름 없는 레시피 목록 단추, 위 줄 = 레시피 고정 칸) → 0번
                c.setScreen(ruleScreen(c, false, "조약돌 뭉치"));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                var target2 = kr.ddingmate.counter.RecipeBook.get("강화 제작 시설/" + firstOf("강화 제작 시설", "영혼 계약서"));
                kr.ddingmate.craft.FacilityPanel.debugAct(target2, 0, true);
                check(kr.ddingmate.craft.FacilityPanel.debugTargetSlot() == 0 && kr.ddingmate.craft.FacilityPanel.debugHint().contains("레시피 목록"),
                        "첫 화면에서 레시피 목록 단추를 강조하지 않음: " + kr.ddingmate.craft.FacilityPanel.debugTargetSlot() + " " + kr.ddingmate.craft.FacilityPanel.debugHint());
                // 목록 화면(45~50번 탭, 지금은 첫 탭 '뭉치') → 영혼 계약서 탭(47번, 세 번째)
                c.setScreen(ruleScreen(c, true, "조약돌 뭉치"));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.craft.FacilityPanel.debugFocus() != null && kr.ddingmate.craft.FacilityPanel.debugTargetSlot() == 47
                                && kr.ddingmate.craft.FacilityPanel.debugHint().contains("3번째 탭"),
                        "목록 화면에서 그 탭을 강조하지 않음: " + kr.ddingmate.craft.FacilityPanel.debugTargetSlot() + " " + kr.ddingmate.craft.FacilityPanel.debugHint());
                // 맞는 탭인데 그 레시피가 다음 쪽 → ↓(53번)
                String second = nthOf("강화 제작 시설", "영혼 계약서", 1);
                c.setScreen(ruleScreen(c, true, second));
                kr.ddingmate.craft.FacilityPanel.debugDetail(null);
                kr.ddingmate.craft.FacilityPanel.debugDetail(kr.ddingmate.counter.RecipeBook.get("강화 제작 시설/" + nthOf("강화 제작 시설", "영혼 계약서", 3)));
            });
            ctx.waitTicks(12);
            ctx.takeScreenshot("facility-1d-rule-arrow");
            ctx.runOnClient(c -> {
                check(kr.ddingmate.craft.FacilityPanel.debugTargetSlot() == 53 && kr.ddingmate.craft.FacilityPanel.debugHint().contains("다른 쪽"),
                        "같은 탭 다음 쪽인데 ↓를 강조하지 않음: " + kr.ddingmate.craft.FacilityPanel.debugTargetSlot() + " " + kr.ddingmate.craft.FacilityPanel.debugHint());
                kr.ddingmate.craft.FacilityPanel.debugDetail(null);
                // 다음 시험이 쓰는 원래 창으로
                c.setScreen(facility(c,
                        item("조약돌 뭉치", 1, "다양한 제작에 사용되는 재료입니다.", "", "  제작 시간 : 5초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 고정"),
                        item("하급 라이프스톤", 1, "세이지 도구 강화에 필요합니다.", "", "  제작 시간 : 1분 0초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거"),
                        item("어빌리티 스톤", 1, "신비로운 힘이 깃들어 있는 스톤입니다.", "", "  제작 시간 : 2분 0초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거")));
            });
            ctx.waitTicks(12);
            // 재료가 많아 추천이 8개를 넘으면 "그 밖에 N개"를 눌러 전체 목록 판을 연다
            ctx.runOnClient(c -> {
                var pinv = c.player.getInventory();
                int k = 2;
                for (var it : new net.minecraft.item.Item[]{Items.COPPER_BLOCK, Items.REDSTONE_BLOCK, Items.IRON_BLOCK, Items.GOLD_BLOCK, Items.DIAMOND,
                        Items.EMERALD, Items.LAPIS_BLOCK, Items.COAL_BLOCK, Items.DEEPSLATE, Items.NETHERITE_INGOT}) pinv.setStack(k++, new ItemStack(it, 8));
                for (String n : new String[]{"토파즈 주괴", "사파이어 주괴", "플래티넘 주괴", "스킬 펄스", "강화 횃불", "코룸"}) pinv.setStack(k++, item(n, 8, "재료"));
                // 귀중품이 많은 채광물 가공 시설 창(고정 칸에 코룸 주괴)
                c.setScreen(facility(c, item("코룸 주괴", 1, "", "  제작 시간 : 20초", "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                var es = kr.ddingmate.craft.FacilityPanel.entries();
                DdingMate.LOG.info("시설 판 추천 {}개: {}", es.size(), es.stream().map(e -> e.recipe().name).toList());
                check(es.size() > 8, "재료를 많이 가졌는데 추천이 8개 이하: " + es.size());
                if (es.size() > 8) {
                    int[] more = kr.ddingmate.craft.FacilityPanel.debugMore();
                    check(more != null, "추천이 8개를 넘는데 '그 밖에 N개'가 없음");
                    net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(c.currentScreen).invoker()
                            .allowMouseClick(c.currentScreen, more[0], more[1], 0);
                    check(kr.ddingmate.craft.FacilityPanel.expanded(), "'그 밖에 N개'를 눌러도 전체 목록이 안 열림");
                }
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("facility-2-full-list");
            // 쉬프트+클릭: 판 아래에 그 레시피의 재료(보유/필요) 판, 쉬프트+우클릭: 닫기
            int[] panelBefore = new int[4];
            ctx.runOnClient(c -> {
                var es = kr.ddingmate.craft.FacilityPanel.entries();
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(c.currentScreen).invoker()
                        .allowMouseClick(c.currentScreen, kr.ddingmate.craft.FacilityPanel.debugMore()[0], kr.ddingmate.craft.FacilityPanel.debugMore()[1], 0);
                System.arraycopy(kr.ddingmate.craft.FacilityPanel.debugPanelRect(), 0, panelBefore, 0, 4);
                kr.ddingmate.craft.FacilityPanel.debugDetail(es.get(0).recipe());
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("facility-3-recipe-detail");
            ctx.runOnClient(c -> {
                check(kr.ddingmate.craft.FacilityPanel.detail() != null && !kr.ddingmate.craft.FacilityPanel.expanded(), "레시피 판이 안 열리거나 전체 목록이 안 닫힘");
                // 레시피 판은 보유 재료 판을 덮지 않고 화면 안에(예전: 아래에 자리가 없으면 위로 올라가 판을 덮었다)
                int[] d = kr.ddingmate.craft.FacilityPanel.debugDetailRect(), pr = kr.ddingmate.craft.FacilityPanel.debugPanelRect();
                boolean overlap = d[0] < pr[0] + pr[2] && pr[0] < d[0] + d[2] && d[1] < pr[1] + pr[3] && pr[1] < d[1] + d[3];
                check(!overlap, "레시피 판이 보유 재료 판을 덮음: " + Arrays.toString(d) + " / " + Arrays.toString(pr));
                check(d[0] >= 0 && d[1] >= 0 && d[0] + d[2] <= c.getWindow().getScaledWidth() && d[1] + d[3] <= c.getWindow().getScaledHeight(),
                        "레시피 판이 화면 밖: " + Arrays.toString(d));
                // 레시피 판을 열어도 작은 판은 제자리(줄이 커서 밑에서 움직이면 같은 자리를 다시 눌러 닫을 수 없다)
                check(Arrays.equals(pr, panelBefore), "레시피 판을 열자 작은 판이 움직임: " + Arrays.toString(panelBefore) + " → " + Arrays.toString(pr));
                // 레시피 판 위를 누르면 레시피 판 몫(아래 가려진 줄을 핀에 넣지 않음)
                String pinsBefore = DdingMate.config().counter.pins.stream().map(x -> x.recipe).toList().toString();
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(c.currentScreen).invoker()
                        .allowMouseClick(c.currentScreen, d[0] + d[2] / 2, d[1] + d[3] / 2, 0);
                check(pinsBefore.equals(DdingMate.config().counter.pins.stream().map(x -> x.recipe).toList().toString())
                        && kr.ddingmate.craft.FacilityPanel.detail() != null, "레시피 판 위 클릭이 아래로 넘어감");
                kr.ddingmate.craft.FacilityPanel.debugDetail(null);
                // 좁은 판의 서버 등급 이름은 위키 표기(커먼·노멀·레어·에픽·전설·신화)로 줄인다
                check(kr.ddingmate.ui.Theme.compactGrade("(Legendary) 실바니움 만년필").equals("전설 실바니움 만년필")
                                && kr.ddingmate.ui.Theme.compactGrade("(Common)넬트 천체관측기").equals("커먼 넬트 천체관측기")
                                && kr.ddingmate.ui.Theme.compactGrade("코룸 주괴").equals("코룸 주괴"),
                        "등급 줄임이 다름: " + kr.ddingmate.ui.Theme.compactGrade("(Common)넬트 천체관측기"));
                // 판 너비 상한: 좁은 화면(427칸)은 150 그대로, 넓은 화면은 창을 덮지 않는 만큼(최대 210)
                check(kr.ddingmate.ui.SidePanel.maxWidth(427) == 150 && kr.ddingmate.ui.SidePanel.maxWidth(960) == 210
                        && kr.ddingmate.ui.SidePanel.maxWidth(600) == 194,
                        "판 너비 상한이 다름");
                // 줄 나누기: 모든 줄이 너비 안, 글자는 하나도 빠지지 않는다
                String longText = "제작을 시작하면 알아서 등록돼요. 시설 창을 한 번 열면 레시피의 \"제작 시간\"을 배워서, 넣은 순서대로 하나씩 끝나는 시각을 계산해요.";
                var lines = kr.ddingmate.ui.Theme.wrap(c.textRenderer, longText, 120);
                check(lines.size() > 2 && lines.stream().allMatch(l -> kr.ddingmate.ui.Theme.width(c.textRenderer, l) <= 120)
                        && String.join(" ", lines).equals(longText), "줄 나누기가 너비를 넘거나 글자를 잃음: " + lines);
                // 팝업 본문이 길면 줄을 나눠 다 보여 준다(예전: "시설에서…" 로 잘림)
                var toast = new kr.ddingmate.ui.DdingMateToast("제작 완료: 조약돌 뭉치 ×7 [5]", "넣어 둔 5번이 모두 끝났어요 · 시설에서 꺼내 가세요",
                        new ItemStack(Items.PAPER), 0xFF4FB54A);
                check(toast.lines().size() == 2 && String.join(" ", toast.lines()).equals("넣어 둔 5번이 모두 끝났어요 · 시설에서 꺼내 가세요")
                        && toast.getHeight() == 42, "팝업 본문 줄 나누기가 다름: " + toast.lines() + " h=" + toast.getHeight());
            });
            // 바로 만들어지는 아이템(커피·대형 제작대·형광 모루)은 제작 타이머에 넣지 않는다
            // (2026-10-16 실제: 커피 머신 창의 커피 칸 남은 기간이 "카페 모카 114시간 55분" 제작 중으로 HUD 에 떴다)
            ctx.runOnClient(c -> {
                check(kr.ddingmate.counter.RecipeBook.isInstant("카페 모카") && kr.ddingmate.counter.RecipeBook.isInstant("그린 트리 프라페")
                                && kr.ddingmate.counter.RecipeBook.isInstant("오르딘 미니 망치") && kr.ddingmate.counter.RecipeBook.isInstant("바다의 심장"),
                        "커피·형광 모루·대형 제작대 아이템을 바로 만드는 것으로 안 봄");
                check(!kr.ddingmate.counter.RecipeBook.isInstant("강화 횃불") && !kr.ddingmate.counter.RecipeBook.isInstant("반짝 광택제")
                                && !kr.ddingmate.counter.RecipeBook.isInstant("코룸 주괴") && !kr.ddingmate.counter.RecipeBook.isInstant("없는 아이템"),
                        "기다리는 제작(또는 모르는 이름)을 바로 만드는 것으로 봄");
                CraftTimers.onChat(G + "카페 모카 아이템 1개 제작을 시작했습니다.");
                check(CraftTimers.groups().stream().noneMatch(g -> g.name().equals("카페 모카")), "커피 제작 시작 메시지로 제작 타이머에 넣음");
                c.setScreen(facility(c, item("카페 모카", 1, "레시피북으로 활성화된 커피입니다.", "남은 시간 : 4일 18시간 55분"),
                        item("아메리카노", 1, "남은 시간 : 5일 0시간")));
            });
            ctx.waitTicks(6);
            ctx.runOnClient(c -> {
                check(CraftTimers.groups().stream().noneMatch(g -> g.name().equals("카페 모카") || g.name().equals("아메리카노")),
                        "커피 칸의 남은 기간을 제작 중으로 넣음: " + CraftTimers.groups());
                c.setScreen(null);
            });
            // 다른 전문가 판매 시세: 로니 상점 창(가격 적힌 칸 3개 이상) → 보유 판매품과 모두 팔면 받는 금액
            ctx.runOnClient(c -> {
                kr.ddingmate.expert.ShopPrices.debugClear();
                var pinv = c.player.getInventory();
                for (int i = 0; i < pinv.size(); i++) pinv.setStack(i, ItemStack.EMPTY);
                pinv.setStack(0, item("코룸 주괴", 10, "재료"));
                pinv.setStack(1, item("사슴의 영혼", 4, "영혼"));
                pinv.setStack(2, item("자개 부채", 1, "공예품"));
                var mine = kr.ddingmate.expert.ShopPrices.owned(c, "채광", null);
                check(mine.size() == 1 && mine.get(0).count() == 10 && mine.get(0).high() == 35_000, "채광 판매품 값이 다름: " + mine);
                var sea = kr.ddingmate.expert.ShopPrices.owned(c, "해양", null);
                check(sea.size() == 1 && sea.get(0).low() == 175_000 && sea.get(0).high() == 350_000, "공예품 범위가 다름: " + sea);
                // 전문가 스킬 창: 판매가 스킬의 지금 비율을 읽는다 → 상점에서 못 본 정동석에 쓴다
                DdingMate.config().expert.priceSkills.clear();
                SimpleInventory sk = new SimpleInventory(54);
                sk.setStack(10, item("[세레니티] 반짝반짝 눈이 부셔 (+4)", 1, "정동석의 판매가가 20% 증가합니다.", "상점과 무역에서 계산되어 보입니다.", "────────", "다음 레벨: 30%"));
                sk.setStack(11, item("[세레니티] 값어치 증명 (+0)", 1, "영혼의 판매가가 5% 증가합니다."));
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(94, c.player.getInventory(), sk),
                        c.player.getInventory(), Text.literal("전문가")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.expert.PriceSkills.pct("반짝반짝 눈이 부셔") == 20 && kr.ddingmate.expert.PriceSkills.pct("값어치 증명") == 0,
                        "판매가 스킬 비율을 못 읽음: " + DdingMate.config().expert.priceSkills);
                c.player.getInventory().setStack(5, item("세렌트 정동석", 1, "정동석"));
                var geo = kr.ddingmate.expert.ShopPrices.owned(c, "채광", null).stream().filter(o -> o.ware().name().equals("세렌트 정동석")).findFirst().orElseThrow();
                check(geo.each() == 9_720 && "스킬 창".equals(kr.ddingmate.expert.ShopPrices.ratioSource(geo.ware())),
                        "스킬 창 비율(+20%)을 안 본 정동석에 쓰지 않음: " + geo);
                c.player.getInventory().setStack(5, ItemStack.EMPTY);
                // 보석함 창: 귀중품 칸의 "가격 : 16,227골드"는 그 아이템 값이지 상점 가격이 아니다
                c.setScreen(facility(c, item("(Rare) 실바니움 만년필", 1, "귀중품 가치 : 4,414", "가격 : 16,227골드"),
                        item("(Common) 키론 오르골", 1, "가격 : 10,971골드"), item("(Rare) 라온 만화경", 1, "가격 : 9,000골드")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.expert.ShopPrices.openShop() == null, "보석함 창을 상점으로 봄");
                // 보석함 창: 귀중품마다 적힌 가격을 모아 귀중품 판에(보석함에 넣은 것 합계)
                var jw = kr.ddingmate.expert.ShopPrices.jewels();
                check(jw.size() == 1 && jw.get(0).inBox() && jw.get(0).price() == 16_227 && jw.get(0).name().equals("(Rare) 실바니움 만년필"),
                        "보석함 귀중품 가격을 못 모음: " + jw);
                DdingMate.config().expert.priceSkills.clear();
                // 보석함에서 본 귀중품 가격은 기억해 두고, 가격 줄이 없는 창(로니)에서도 같은 귀중품(이름·가치)에 쓴다
                c.player.getInventory().setStack(6, item("(Rare) 실바니움 만년필", 1, "값진 보석으로 만든 귀중품입니다.", "귀중품 가치 : 4,414"));
            });
            ctx.runOnClient(c -> {
                c.setScreen(facility(c, item("(Rare) 실바니움 만년필", 1, "귀중품 가치 : 4,414", "가격 : 16,227골드")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                c.setScreen(null);
                var pen = kr.ddingmate.expert.ShopPrices.owned(c, "채광", null).stream()
                        .filter(o -> o.ware().name().equals("(Rare) 실바니움 만년필")).findFirst().orElseThrow();
                check(pen.exact() && pen.high() == 16_227, "보석함에서 본 귀중품 가격(16,227골드)을 다른 창에서 안 씀: " + pen);
                c.player.getInventory().setStack(6, ItemStack.EMPTY);
                // 포스 칸 "판매가 : 824,662 골드 (↑796,645)" / "나의 판매가 : 865,895 골드", 무역 칸(판매 수량·묶음 값)은 상점 가격이 아니다
                c.setScreen(facility(c,
                        item("흑진주 시계", 1, "해양 아이템 판매 정보", "- 판매가 : 824,662 골드 (↑796,645)", "- 나의 판매가 : 865,895 골드", "(우) 판매 (쉬프트) 모두 판매"),
                        item("자개 부채", 1, "- 판매가 : 243,746 골드", "- 나의 판매가 : 255,933 골드"),
                        item("분홍 헤어핀", 1, "- 판매가 : 214,495 골드", "- 나의 판매가 : 225,220 골드"),
                        item("코룸 주괴", 24, "- 판매 수량 : 24개", "- 판매 가격 : 89,082골드 (101%가격)", "쉬프트 클릭하여 무역 물품을 판매합니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check("포스".equals(kr.ddingmate.expert.ShopPrices.openShop()), "포스 상점 창으로 알아보지 못함");
                var w = kr.ddingmate.expert.ShopPrices.WARES.stream().filter(x -> x.name().equals("흑진주 시계")).findFirst().orElseThrow();
                int[] p = kr.ddingmate.expert.ShopPrices.priceFor(w);
                check(p[0] == 824_662 && p[1] == 865_895, "포스 판매가·나의 판매가를 다르게 읽음: " + p[0] + "/" + p[1]);
                var ingot = kr.ddingmate.expert.ShopPrices.WARES.stream().filter(x -> x.name().equals("코룸 주괴")).findFirst().orElseThrow();
                check(kr.ddingmate.expert.ShopPrices.priceFor(ingot)[0] < 10_000, "무역 칸 묶음 값(89,082골드)을 코룸 주괴 한 개 값으로 저장함: "
                        + kr.ddingmate.expert.ShopPrices.priceFor(ingot)[0]);
                // 가방처럼 가격 없는 칸뿐이면 상점이 아니다
                c.setScreen(facility(c, item("코룸 주괴", 64, "재료"), item("리프톤 주괴", 64, "재료"), item("세렌트 주괴", 64, "재료")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.expert.ShopPrices.openShop() == null, "가격 없는 창을 상점으로 봄");
                // 실제 로니 칸 문구(2026-10-15): "판매가격 : 3,500 골드" / "나의 판매가 : 3,675 골드"(전문가 스킬 반영)
                c.setScreen(facility(c, item("코룸 주괴", 1, "세레니티의 커먼 등급 주괴입니다.", "판매가격 : 3,500 골드", "나의 판매가 : 3,675 골드", "(우) 판매 (쉬프트) 모두 판매"),
                        item("리프톤 주괴", 1, "판매가격 : 3,750 골드", "나의 판매가 : 3,938 골드"),
                        item("세렌트 주괴", 1, "판매가격 : 4,000 골드", "나의 판매가 : 4,200 골드"),
                        item("코룸 정동석", 1, "판매가격 : 7,200 골드", "나의 판매가 : 8,640 골드")));
                // 상점 창에 안 보인 리프톤 정동석, 아이템마다 가격이 적힌 귀중품
                c.player.getInventory().setStack(3, item("리프톤 정동석", 2, "리프톤 광석에서 나온 정동석입니다."));
                c.player.getInventory().setStack(4, item("(Common) 키론 오르골", 1, "값진 보석으로 만든 귀중품입니다.", "귀중품 가치 : 1,756", "가격 : 10,971골드"));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check("로니".equals(kr.ddingmate.expert.ShopPrices.openShop()), "로니 상점 창으로 알아보지 못함: " + kr.ddingmate.expert.ShopPrices.openShop());
                var mine = kr.ddingmate.expert.ShopPrices.owned(c, "채광", null);
                var ingot = mine.stream().filter(o -> o.ware().name().equals("코룸 주괴")).findFirst().orElseThrow();
                check(ingot.fromShop() && ingot.each() == 3_675 && ingot.high() == 36_750 && ingot.bonus() == 5,
                        "나의 판매가(3,675G, +5%)를 쓰지 않음: " + ingot);
                var geode = mine.stream().filter(o -> o.ware().name().equals("리프톤 정동석")).findFirst().orElseThrow();
                check(!geode.fromShop() && geode.each() == 9_180 && geode.bonus() == 20,
                        "같은 종류(정동석)에서 본 스킬 배율 1.2 를 안 본 정동석에 곱하지 않음: " + geode);
                var orgel = mine.stream().filter(o -> o.ware().name().equals("(Common) 키론 오르골")).findFirst().orElseThrow();
                check(orgel.exact() && orgel.high() == 10_971, "귀중품에 적힌 가격(10,971골드)을 쓰지 않음: " + orgel);
            });
            ctx.takeScreenshot("shop-1-roni-panel");
            // 예전 판(c0bbd0a 전)이 포스 칸의 나의 판매가를 기본가로도 저장한 기록(실제: 흑진주 시계 865,895/865,895)은 읽을 때 버린다
            ctx.runOnClient(c -> {
                try {
                    Path shopFile = FabricLoader.getInstance().getConfigDir().resolve("ddingmate-shop.json");
                    String key = kr.ddingmate.skill.SkillTimers.accountKey();
                    String body = "{\"accounts\":{\"" + key + "\":{\"흑진주 시계\":[865895,865895],\"자개 부채\":[243746,255933],\"코룸 주괴\":[3500,3675]}}}";
                    Files.writeString(shopFile, body, StandardCharsets.UTF_8);
                    kr.ddingmate.expert.ShopPrices.debugReload();
                    var pearl = kr.ddingmate.expert.ShopPrices.WARES.stream().filter(x -> x.name().equals("흑진주 시계")).findFirst().orElseThrow();
                    var fan = kr.ddingmate.expert.ShopPrices.WARES.stream().filter(x -> x.name().equals("자개 부채")).findFirst().orElseThrow();
                    var ingot = kr.ddingmate.expert.ShopPrices.WARES.stream().filter(x -> x.name().equals("코룸 주괴")).findFirst().orElseThrow();
                    int[] p1 = kr.ddingmate.expert.ShopPrices.priceFor(pearl);
                    check(p1[0] == 1_000_000 && Math.abs(p1[1] - Math.round(1_000_000 * 255_933.0 / 243_746)) <= 1, "예전 판의 포스 기록(기본가=나의 판매가)을 버리지 않음: " + Arrays.toString(p1));
                    check(kr.ddingmate.expert.ShopPrices.priceFor(fan)[0] == 243_746 && kr.ddingmate.expert.ShopPrices.priceFor(ingot)[1] == 3_675,
                            "멀쩡한 기록까지 버림");
                    // 새 판(version 2)이 저장한 것은 기본가=나의 판매가여도(스킬 안 배움) 그대로
                    Files.writeString(shopFile, "{\"version\":2,\"accounts\":{\"" + key + "\":{\"흑진주 시계\":[824662,824662]}}}", StandardCharsets.UTF_8);
                    kr.ddingmate.expert.ShopPrices.debugReload();
                    check(kr.ddingmate.expert.ShopPrices.priceFor(pearl)[0] == 824_662, "새 판 기록을 버림");
                    Files.deleteIfExists(shopFile);
                    kr.ddingmate.expert.ShopPrices.debugClear();
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
            // 상점에는 인벤토리에 든 것만 판다: 예전 가방 기록에 남은 같은 물건은 세지 않는다
            // (2026-10-16 실제: 가방에서 꺼낸 만년필 1개를 가방 기록과 인벤토리에서 두 번 세어 2개로 보였다)
            ctx.runOnClient(c -> {
                DdingMate.config().counter.includeBags = true;
                var pinv = c.player.getInventory();
                for (int i = 0; i < pinv.size(); i++) pinv.setStack(i, ItemStack.EMPTY);
                SimpleInventory bag = new SimpleInventory(54);
                bag.setStack(53, item("(Rare) 실바니움 만년필", 1, "값진 보석으로 만든 귀중품입니다.", "귀중품 가치 : 4,414"));
                kr.ddingmate.counter.BagTracker.onOutgoing("ㄱ1");
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(97, c.player.getInventory(), bag),
                        c.player.getInventory(), Text.literal("모험 가방")));
            });
            ctx.waitTicks(20);
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                c.player.getInventory().setStack(1, item("(Rare) 실바니움 만년필", 1, "값진 보석으로 만든 귀중품입니다.", "귀중품 가치 : 4,414"));
                int all = kr.ddingmate.counter.MaterialCounter.have(kr.ddingmate.counter.MaterialCounter.availableCounts(c), "(Rare) 실바니움 만년필");
                var pen = kr.ddingmate.expert.ShopPrices.owned(c, "채광", null).stream()
                        .filter(o -> o.ware().name().equals("(Rare) 실바니움 만년필")).findFirst().orElseThrow();
                check(all == 2, "시험 준비: 가방 기록이 안 됨(" + all + ")");
                check(pen.count() == 1, "판매 시세가 가방 기록까지 세어 인벤토리보다 많음: " + pen.count() + "개");
                c.player.getInventory().setStack(1, ItemStack.EMPTY);
                kr.ddingmate.counter.BagTracker.clearCurrent();
            });
            ctx.runOnClient(c -> {
                c.setScreen(null);
                for (int i = 0; i < 3; i++) DdingMate.config().counter.pins.get(i).recipe = keepPins[i];
                DdingMate.config().counter.includeBags = keepSum[0];
                DdingMate.config().counter.includeStorage = keepSum[1];
                var pinv = c.player.getInventory();
                for (int i = 0; i < pinv.size(); i++) pinv.setStack(i, ItemStack.EMPTY);
                // 전문가 탭: 오른쪽 목차로 바리스타·요리 칸에 바로 간다
                kr.ddingmate.expert.Cooking.debugPrice("토마토 스파게티", 394, now());
                kr.ddingmate.gui.DdingMateScreen s = new kr.ddingmate.gui.DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("전문가");
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                kr.ddingmate.gui.DdingMateScreen s = (kr.ddingmate.gui.DdingMateScreen) c.currentScreen;
                check(s.sectionLabels().containsAll(List.of("제작 시간", "스킬", "바리스타", "커피 가격", "요리", "판매 시세")), "전문가 탭 목차가 다름: " + s.sectionLabels());
                s.jumpTo("바리스타");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("expert-3-jump-barista");
            ctx.runOnClient(c -> {
                kr.ddingmate.gui.DdingMateScreen s = (kr.ddingmate.gui.DdingMateScreen) c.currentScreen;
                double before = s.listScroll();
                s.jumpTo("요리");
                check(s.listScroll() > before, "목차 '요리'를 눌러도 내려가지 않음");
                check("요리".equals(s.currentSectionLabel()), "누른 목차 '요리'를 강조하지 않음: " + s.currentSectionLabel());
                // 이미 끝까지 내려가 더 못 움직여도(실제 홈 탭 '제작 중') 누른 분류를 강조한다
                s.jumpTo("커피 가격");
                s.jumpTo("요리");
                check("요리".equals(s.currentSectionLabel()), "다시 눌러도 '요리'가 강조되지 않음");
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("expert-4-jump-cooking");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                kr.ddingmate.expert.Barista.debugClear();
                kr.ddingmate.expert.Cooking.debugClear();
                DdingMate.config().expert.coffeeBonus = 0;
            });

            // 9-000d) 제작 완료 순간: 시설 창이 열린 채 맨 앞 칸이 "제작이 완료되었습니다"로 바뀌면 그 순간이 완료 시각(창 숫자의 1초 끊김과 무관)
            long[] flip = new long[1];
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 0;
                CraftTimers.debugLearn("강화 횃불", 30);
                CraftTimers.onChat(G + "강화 횃불 아이템 1개 제작을 시작했습니다.");
                c.setScreen(facility(c, queueItem("강화 횃불", 1, "  제작 시간 : 30초")));
            });
            ctx.waitTicks(6);
            ctx.runOnClient(c -> {
                flip[0] = now();
                c.setScreen(facility(c, queueItem("강화 횃불", 1, "  제작이 완료되었습니다.", "  클릭 시 아이템을 회수합니다.")));
            });
            ctx.waitTicks(6);
            ctx.runOnClient(c -> {
                CraftTimers.Job j = CraftTimers.jobs().get(0);
                check(Math.abs(j.endsAt - flip[0]) < 300, "완료로 바뀐 순간을 완료 시각으로 잡지 않음: " + (j.endsAt - flip[0]) + "ms");
                c.setScreen(null);
                CraftTimers.debugClear();
            });

            // 9-000a) 앞 제작을 창 값으로 크게(+3초) 다시 맞추면 뒤에 기다리는 제작은 정확히 3초만 옮겨진다
            //         (예전 결함: 두 번째를 맞출 때 세 번째를 또 옮겨 6초 밀림. 2026-10-10 시험 진단으로 발견)
            long[] base = new long[4];
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 1;
                for (int i = 0; i < 4; i++) CraftTimers.onChat(G + "강화 횃불 아이템 1개 제작을 시작했습니다.");
                c.setScreen(facility(c,
                        queueItem("강화 횃불", 1, "  제작 시간 : 4초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                for (int i = 0; i < 4; i++) base[i] = jobs.get(i).endsAt;
                long left = Math.max(0, (base[0] - now()) / 1000) + 3; // 서버가 3초 더 남았다고 보여 준다
                c.setScreen(facility(c,
                        queueItem("강화 횃불", 1, "  제작 시간 : " + left + "초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long s0 = jobs.get(0).endsAt - base[0];
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < 4; i++) all.append(" #").append(i + 1).append("=").append(jobs.get(i).endsAt - base[i]);
                check(s0 > 1_500 && s0 < 4_500, "맨 앞을 서버 값(+3초)으로 맞추지 않음:" + all);
                for (int i = 1; i < 4; i++) {
                    check(Math.abs((jobs.get(i).endsAt - base[i]) - s0) <= 100, (i + 1) + "번째가 맨 앞과 다르게 옮겨짐:" + all);
                }
                c.setScreen(null);
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 0;
            });

            // 9-00) 실제 2026-10-10 강화 횃불 3개(7초, 채광 1레벨): 서버는 개당 7초(6.3초가 아님) → 7·14·21초.
            //       시설 창을 열 때마다 서버 값으로 맞춰도 뒤 제작이 1초씩 밀리지 않고, 끝난 것을 꺼내도 진행 중인 제작 시간은 그대로
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 1;
                for (int i = 0; i < 3; i++) CraftTimers.onChat(G + "강화 횃불 아이템 1개 제작을 시작했습니다.");
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long first = jobs.get(0).startedAt;
                for (int i = 0; i < 3; i++) {
                    check(Math.abs(jobs.get(i).endsAt - (first + 7_000L * (i + 1))) < 600,
                            "강화 횃불 " + (i + 1) + "번째가 7초 간격이 아님: " + (jobs.get(i).endsAt - first));
                }
                // 시설 창: 맨 앞 남은 4초, 나머지는 기다림(칸의 "제작 시간"은 그 제작만의 시간)
                c.setScreen(facility(c,
                        queueItem("강화 횃불", 1, "  제작 시간 : 4초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초")));
            });
            long[] before = new long[3];
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long now = now();
                check(Math.abs(jobs.get(0).endsAt - (now + 4_000)) < 1_200, "맨 앞을 창 값(4초)으로 맞추지 않음: " + (jobs.get(0).endsAt - now));
                check(Math.abs(jobs.get(1).endsAt - jobs.get(0).endsAt - 7_000) < 600, "두 번째가 맨 앞 뒤 7초가 아님");
                check(Math.abs(jobs.get(2).endsAt - jobs.get(1).endsAt - 7_000) < 600, "세 번째가 두 번째 뒤 7초가 아님");
                for (int i = 0; i < 3; i++) before[i] = jobs.get(i).endsAt;
            });
            // 조금 뒤 서버 값이 흐른 만큼 줄어든 창을 다시 읽어도(정상 진행) 완료 시각이 밀리지 않는다.
            // 서버처럼 남은 시간을 초 단위로 내려 보여 준다(실제 흐른 시간으로 계산해 시험이 느려져도 맞게)
            ctx.waitTicks(20);
            long[] shownAt = new long[1];
            ctx.runOnClient(c -> {
                shownAt[0] = now();
                long left = Math.max(0, (before[0] - now()) / 1000);
                c.setScreen(facility(c,
                        queueItem("강화 횃불", 1, "  제작 시간 : " + left + "초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                long slack = Math.max(1_500, 1_000 + (now() - shownAt[0]) + 100);
                StringBuilder diag = new StringBuilder(" [흐른 " + (now() - shownAt[0]) + "ms, 허용 " + slack + "ms]");
                for (int i = 0; i < jobs.size(); i++) {
                    CraftTimers.Job x = jobs.get(i);
                    diag.append(" #").append(i + 1).append(" 밀림=").append(i < 3 ? x.endsAt - before[i] : 0)
                            .append(" synced=").append(x.synced).append(" derived=").append(x.derived)
                            .append(" start=").append(x.startedAt == 0 ? 0 : now() - x.startedAt);
                }
                for (int i = 0; i < 3; i++) {
                    check(Math.abs(jobs.get(i).endsAt - before[i]) <= slack,
                            (i + 1) + "번째 완료 시각이 다시 읽을 때 밀림: " + (jobs.get(i).endsAt - before[i]) + "ms" + diag);
                }
                // 앞 제작을 다시 맞추면 뒤 제작은 같은 만큼만 옮겨진다(예전: 세 번째부터 두 번 옮겨짐)
                long s2 = jobs.get(1).endsAt - before[1], s3 = jobs.get(2).endsAt - before[2];
                check(Math.abs(s3 - s2) <= 100, "뒤 제작이 앞 제작과 다르게 옮겨짐(두 번 밀림?): " + s2 + " / " + s3 + diag);
                c.setScreen(null);
                // 맨 앞이 끝났다고 치고 꺼내면 맨 앞만 빠지고, 진행 중인 두 번째·기다리는 세 번째 시간은 그대로
                jobs.get(0).endsAt = now() - 500;
                long second = jobs.get(1).endsAt, third = jobs.get(2).endsAt;
                CraftTimers.onChat(G + "강화 횃불 아이템 1개를 제작대에서 회수했습니다.");
                List<CraftTimers.Job> after = CraftTimers.jobs();
                check(after.size() == 2 && after.get(0).endsAt == second && after.get(1).endsAt == third,
                        "끝난 것을 꺼냈더니 남은 제작 시간이 바뀜: " + after.stream().map(j -> j.endsAt - now()).toList());
                // 서버가 조금 빨라 모드가 아직 안 끝났다고 볼 때 꺼내도, 맨 앞(가장 먼저 끝나는 것)이 빠진다
                CraftTimers.onChat(G + "강화 횃불 아이템 1개를 제작대에서 회수했습니다.");
                List<CraftTimers.Job> last = CraftTimers.jobs();
                check(last.size() == 1 && last.get(0).endsAt == third, "먼저 끝나는 제작이 아니라 다른 것을 뺌");
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 0;
            });

            // 9-0) 실제 2026-10-10 뱃지 제작 창: 레시피 칸 문구가 "(좌) 해당 아이템 제작 시작 / (쉬프트+우) 레시피 고정".
            //      "제작 시간 : 5초"를 진행 중인 제작으로 착각해 가짜 제작·완료 알림이 쏟아지면 안 된다
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                c.setScreen(facility(c,
                        item("홍옥의 팔찌 획득권", 1, "쉬프트 우클릭하여 뱃지를 보관합니다.", "사용 시 사라지는 일회용 아이템입니다.", " 뱃지 아이템 정보", "",
                                " 체력 : 1", "", "  제작 시간 : 5초", "", " (좌) 해당 아이템 제작 시작", " (쉬프트+우) 레시피 고정"),
                        item("번개의 활 획득권", 1, " 공격력 : 1", "  제작 시간 : 5초", " (좌) 해당 아이템 제작 시작", " (쉬프트+우) 레시피 고정"),
                        // 레시피 표시가 없어도, 시작 메시지 없이 "제작 시간"만 보이는 칸으로 새 제작을 만들지 않는다
                        item("코룸 주괴", 3, "  제작 시간 : 1분 0초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.jobs().isEmpty(), "레시피 칸·제작 시간 칸으로 가짜 제작을 만듦: " + CraftTimers.jobs().stream().map(j -> j.name).toList());
                c.setScreen(null);
            });

            // 9-1) 실제 2026-10-10 창: 어빌리티 스톤 50·50·28개. 개수가 섞이면 "어빌리티 스톤 ×128" 한 줄, 시간은 합쳐서.
            //      대기열 칸의 "제작 시간"은 그 제작의 남은 시간 → 맨 앞 1시간 29분 33초 + 1시간 30분 + 50분 24초
            ctx.runOnClient(c -> {
                CraftTimers.debugClear();
                DdingMate.config().craft.mining = 1;
                CraftTimers.onChat(G + "어빌리티 스톤 아이템 50개 제작을 시작했습니다.");
                CraftTimers.onChat(G + "어빌리티 스톤 아이템 50개 제작을 시작했습니다.");
                CraftTimers.onChat(G + "어빌리티 스톤 아이템 28개 제작을 시작했습니다.");
                List<String> g = labels(CraftTimers.groups());
                check(g.equals(List.of("어빌리티 스톤 ×128")), "개수가 다른 같은 아이템을 합쳐 한 줄로 안 보임: " + g);
                // 시설 창을 안 열었어도: 2분 × 개수 × 0.9 → 50개 1시간 30분
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(Math.abs(jobs.get(0).endsAt - jobs.get(0).startedAt - 5_400_000) < 3000, "50개 제작 시간이 1시간 30분이 아님: " + (jobs.get(0).endsAt - jobs.get(0).startedAt));
                // 대기열 칸은 넣은 순서대로 놓인다: 진행 중(남은 1시간 29분 33초) → 기다리는 50개(1시간 30분) → 28개(50분 24초)
                c.setScreen(facility(c,
                        queueItem("어빌리티 스톤", 50, "  제작 시간 : 1시간 29분 33초"),
                        queueItem("어빌리티 스톤", 50, "신비로운 힘이 깃들어 있는 스톤입니다.", "  제작 시간 : 1시간 30분 0초"),
                        queueItem("어빌리티 스톤", 28, "  제작 시간 : 50분 24초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                long now = now();
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(jobs.size() == 3, "대기열 칸을 새 제작으로 또 등록함: " + jobs.size());
                check(Math.abs(jobs.get(0).endsAt - (now + 5_373_000)) < 2500, "맨 앞을 남은 시간으로 맞추지 않음: " + (jobs.get(0).endsAt - now));
                check(Math.abs(jobs.get(1).endsAt - (now + 5_373_000 + 5_400_000)) < 2500, "두 번째를 앞 제작 뒤부터 세지 않음: " + (jobs.get(1).endsAt - now));
                check(Math.abs(jobs.get(2).endsAt - (now + 5_373_000 + 5_400_000 + 3_024_000)) < 2500, "세 번째 시간이 다름: " + (jobs.get(2).endsAt - now));
                CraftTimers.Group g = CraftTimers.groups().get(0);
                check(g.label().equals("어빌리티 스톤 ×128") && !g.estimated(), "합친 줄이 다르거나 어림값으로 표시: " + g.label());
                check(CraftTimers.remainingText(g, now).startsWith("3시간 49분"), "합친 시간(5373+5400+3024초)이 3시간 49분이 아님: " + CraftTimers.remainingText(g, now));
                c.setScreen(null);
                DdingMate.config().craft.mining = 0;
                // 9-2) 일정·재료 HUD 를 둘 다 직접 옮겨 두었는데 일정에 제작 줄이 늘어 길어져도 서로 겹치지 않는다(실제 화면에서 겹쳤음)
                DdingMateConfig.HudPos a = new DdingMateConfig.HudPos();
                a.x = 0.01f;
                a.y = 0.05f;
                DdingMateConfig.HudPos b = new DdingMateConfig.HudPos();
                b.x = 0.01f;
                b.y = 0.12f;
                DdingMate.config().hudPos.put(kr.ddingmate.hud.HudLayout.SCHEDULE, a);
                DdingMate.config().hudPos.put(kr.ddingmate.hud.HudLayout.COUNTER, b);
            });
            ctx.waitTicks(30);
            ctx.runOnClient(c -> {
                var bounds = kr.ddingmate.hud.HudLayout.bounds();
                var s1 = bounds.get(kr.ddingmate.hud.HudLayout.SCHEDULE);
                var s2 = bounds.get(kr.ddingmate.hud.HudLayout.COUNTER);
                check(s1 != null && s2 != null, "HUD 가 그려지지 않음: " + bounds.keySet());
                boolean overlap = s1.x() < s2.x() + s2.w() && s2.x() < s1.x() + s1.w() && s1.y() < s2.y() + s2.h() && s2.y() < s1.y() + s1.h();
                check(!overlap, "직접 옮긴 HUD 끼리 겹침: " + s1 + " / " + s2);
                // 9-2-1) 위로도 아래로도 자리가 없을 만큼 긴 HUD 가 있으면 옆으로 비킨다(실제: 일정 HUD 가 화면 높이만큼 길어짐)
                java.util.List<kr.ddingmate.hud.HudLayout.Rect> tall = java.util.List.of(new kr.ddingmate.hud.HudLayout.Rect(6, 10, 100, 240));
                int[] side = kr.ddingmate.hud.HudLayout.nearestFree(8, 80, 110, 80, 480, 270, tall);
                check(side[0] >= 108 && side[1] == 80, "긴 HUD 옆으로 비키지 않음: " + side[0] + "," + side[1]);
                // 붙여 둔 두 HUD: 위 HUD 가 크게 길어져도(130줄 높이) 화면에 들어가면 옆이 아니라 아래로 밀린다
                java.util.List<kr.ddingmate.hud.HudLayout.Rect> grown = java.util.List.of(new kr.ddingmate.hud.HudLayout.Rect(6, 10, 100, 130));
                int[] down = kr.ddingmate.hud.HudLayout.nearestFree(6, 60, 110, 80, 480, 270, grown);
                check(down[0] == 6 && down[1] == 142, "붙여 둔 HUD 가 아래로 밀리지 않음: " + down[0] + "," + down[1]);
                // 띵타이쿤 기본 HUD 위로 끌어다 놓으면 비킨다(실제: 할 일 HUD 를 오른쪽 위 날짜·채널 줄에, 장비 HUD 를 재화 바 옆에 놓음)
                java.util.List<kr.ddingmate.hud.HudLayout.Rect> server = kr.ddingmate.hud.HudLayout.serverAreasFor(480, 270, true, false);
                int[] topRight = kr.ddingmate.hud.HudLayout.nearestFree(360, 0, 110, 40, 480, 270, server);
                check(topRight[1] >= 14, "날짜·채널 줄 위에 그대로 놓임: " + topRight[0] + "," + topRight[1]);
                int[] topLeft = kr.ddingmate.hud.HudLayout.nearestFree(150, 2, 110, 30, 480, 270, server);
                check(topLeft[1] >= 14 || topLeft[0] >= 207, "재화 바 위에 그대로 놓임: " + topLeft[0] + "," + topLeft[1]);
                int[] bottom = kr.ddingmate.hud.HudLayout.nearestFree(190, 240, 100, 30, 480, 270, server);
                check(bottom[1] + 30 <= 270 - 50 || bottom[0] + 100 <= 148 || bottom[0] >= 332, "핫바 위에 그대로 놓임: " + bottom[0] + "," + bottom[1]);
                int[] mid = kr.ddingmate.hud.HudLayout.nearestFree(190, 100, 100, 30, 480, 270, server);
                check(mid[0] == 190 && mid[1] == 100, "빈자리인데 옮김");
                int[] home = kr.ddingmate.hud.HudLayout.nearestFree(8, 80, 110, 80, 480, 270, java.util.List.of());
                check(home[0] == 8 && home[1] == 80, "겹치지 않으면 원래 자리여야 함");
                // 9-3) 아일랜드: 왼쪽 위로 직접 옮긴 HUD 도 서버 프로필(얼굴·체력) 아래로 비킨다(실제 화면에서 깔렸음)
                DdingMate.config().hudPos.get(kr.ddingmate.hud.HudLayout.SCHEDULE).y = 0f;
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.ISLAND, 6);
            });
            ctx.waitTicks(30);
            ctx.runOnClient(c -> {
                var s1 = kr.ddingmate.hud.HudLayout.bounds().get(kr.ddingmate.hud.HudLayout.SCHEDULE);
                check(s1 != null && s1.y() >= 55, "아일랜드에서 직접 옮긴 일정 HUD 가 프로필 밑에 깔림: " + s1);
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.VILLAGE, 30);
                DdingMate.config().hudPos.clear();
                CraftTimers.debugClear();
                // 9-4) 항해: 실제 2026-10-10 칸 "[ 유실라 보상 수령 ]"은 돌아온 항해. "항해 소요 시간"을 제작으로 착각하지 않는다
                c.setScreen(facility(c, item("[ 유실라 보상 수령 ]", 1, "", "  항해를 통해 다양한 아이템을 획득해보세요.",
                        "  항해선이 돌아와 보상을 수령할 수 있습니다!", "", "  - 항해 소요 시간 : 4시간 5분 0초", "", "클릭 시 항해 보상을 수령합니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                check(jobs.size() == 1 && jobs.get(0).name.equals("유실라 항해") && jobs.get(0).separate,
                        "돌아온 항해를 항해 타이머로 읽지 못하거나 제작으로 착각함: " + jobs.stream().map(j -> j.name).toList());
                check(CraftTimers.groups().get(0).done(), "돌아온 항해가 완료로 보이지 않음");
                // 실제 2026-10-10 19:19: 전문가 창의 항해 스킬 칸·수하물 칸을 섬으로 읽어, 보상을 안 받은 항해 줄을 지웠다
                c.setScreen(facility(c,
                        item("[세레니티] 가벼운 항해선 (잠김)", 1, "전문가 스킬 정보", ". 항해 포인트가 5% 높게 책정됩니다.", ". 항해도에서 계산되어 보입니다."),
                        item("[세레니티] 스피드 복귀 항로 (잠김)", 1, "전문가 스킬 정보", ". 항해 시간이 3% 감소합니다.", ". 항해도에서 반영되어 보입니다."),
                        item("데르무스의 가죽", 1, "항해 수하물로써 사용되는 유물입니다.", "◇ 항해 수하물 포인트 : 200")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.jobs().size() == 1 && CraftTimers.jobs().get(0).name.equals("유실라 항해"),
                        "항해 스킬·수하물 칸을 보고 보상을 안 받은 항해를 지우거나 새 줄을 만듦: " + CraftTimers.jobs().stream().map(j -> j.name).toList());
                // 항해 중인 칸에 남은 시간이 보이면 그 시각으로
                c.setScreen(facility(c, item("[ 벨로아 빠른 항해 ]", 1, "", "  항해를 통해 다양한 아이템을 획득해보세요.",
                        "  크리스탈을 소모해 빠른 항해를 진행할 수 있습니다.", "", "  - 남은 시간 : 2시간 16분 15초",
                        "  - 빠른 항해 비용 : 1,370크리스탈", "", "쉬프트 클릭 시 빠른 항해를 진행합니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                CraftTimers.Job j = CraftTimers.jobs().stream().filter(x -> x.name.equals("벨로아 항해")).findFirst().orElse(null);
                check(j != null && Math.abs(j.endsAt - (now() + 8_175_000)) < 2500, "항해 남은 시간을 읽지 못함(섬 이름 '벨로아'): "
                        + CraftTimers.jobs().stream().map(x -> x.name).toList());
                // 보상을 받아 칸이 바뀌면(보상 수령 → 보내기) 목록에서 빠진다
                c.setScreen(facility(c, item("[ 유실라 항해 완료 ]", 1, "", "  이미 항해를 마친 섬/대륙입니다.", "  모든 섬/대륙을 항해하면 초기화됩니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                check(CraftTimers.jobs().stream().noneMatch(x -> x.name.equals("유실라 항해")), "보상을 받은 항해가 남음");
                check(CraftTimers.jobs().stream().noneMatch(x -> !x.separate), "항해 칸을 제작으로 등록함");
                // 실제 채팅 "케르아 항해 보상을 수령했습니다." → 그 섬 항해를 뺀다. 남이 쓴 채팅은 무시
                CraftTimers.onChat("[칭호] 누군가 : 벨로아 항해 보상을 수령했습니다.");
                check(CraftTimers.jobs().stream().anyMatch(x -> x.name.equals("벨로아 항해")), "남이 쓴 채팅으로 항해를 뺌");
                CraftTimers.onChat("ꀂ 벨로아 항해 보상을 수령했습니다.");
                check(CraftTimers.jobs().stream().noneMatch(x -> x.name.equals("벨로아 항해")), "보상 수령 메시지로 항해를 빼지 않음");
                // 모든 섬을 마치면(실제 칸 "[ 벨로아 항해 완료 ]" · "초기화까지 남은 시간 : 12초") 섬 줄 대신
                // "항해 초기화" 한 줄을 마지막 보상 수령 1분 뒤까지 센다. 멈춰 있는 창 값(12초)으로 다시 맞추지 않는다
                // 끝난 섬 줄이 남아 있어도 초기화를 기다리는 칸을 보면 모두 지운다
                CraftTimers.debugVoyage(item("[ 유실라 보상 수령 ]", 1), lines("  항해를 통해 다양한 아이템을 획득해보세요.",
                        "  - 항해 소요 시간 : 4시간 5분 0초", "클릭 시 항해 보상을 수령합니다."));
                check(CraftTimers.jobs().stream().anyMatch(x -> x.name.equals("유실라 항해")), "돌아온 항해 줄이 안 생김");
                CraftTimers.debugVoyageClaimAt(now() - 20_000);
                java.util.List<Text> resetLore = lines("", "  이미 항해를 마친 섬/대륙입니다.", "  모든 섬/대륙을 항해하면 초기화됩니다.", "", "  - 초기화까지 남은 시간 : 12초");
                CraftTimers.debugVoyage(item("[ 벨로아 항해 완료 ]", 1), resetLore);
                CraftTimers.debugVoyage(item("[ 케르아 항해 완료 ]", 1), resetLore);
                List<String> names = CraftTimers.jobs().stream().map(x -> x.name).toList();
                check(names.equals(List.of("항해 초기화")), "초기화 대기 칸을 섬 항해로 읽음: " + names);
                long left = CraftTimers.jobs().get(0).endsAt - now();
                check(left > 38_000 && left <= 40_500, "마지막 보상 수령 1분 뒤로 세지 않음: " + left);
                CraftTimers.debugClear();
                // 키워드 알림 본문에 서버 그림 글자(네모로 보임)가 남지 않는다
                kr.ddingmate.notify.Notifier.keyword("풍선", "ꀂꀁ[질문]meeee10 : 혹시 풍선 선물상자는 한번만 뜨나요");
                String body = AlertLog.entries().stream().max(java.util.Comparator.comparingLong(e -> e.at)).orElseThrow().body;
                check(!body.contains("ꀂ") && !body.contains(""), "키워드 알림에 서버 그림 글자가 남음: " + body);
                c.setScreen(null);
                CraftTimers.debugClear();
            });

            // 9-5) Tab 목록이 떠 있는 동안은 HUD 를 그리지 않는다(서버 Tab 판과 겹쳐 이질적으로 보임)
            sp.getServer().runCommand("scoreboard objectives add tabtest dummy");
            sp.getServer().runCommand("scoreboard objectives setdisplay list tabtest");
            ctx.runOnClient(c -> CraftTimers.onChat(START));
            ctx.waitTicks(5);
            ctx.getInput().holdKey(o -> o.playerListKey);
            ctx.waitTicks(5);
            ctx.runOnClient(c -> kr.ddingmate.hud.HudLayout.bounds().clear());
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                check(DdingMate.playerListShown(), "Tab 목록이 떠 있는 걸 알아보지 못함");
                check(kr.ddingmate.hud.HudLayout.bounds().isEmpty(), "Tab 중에 HUD 를 그림: " + kr.ddingmate.hud.HudLayout.bounds().keySet());
            });
            ctx.takeScreenshot("craft-5-tab-hidden");
            ctx.getInput().releaseKey(o -> o.playerListKey);
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.hud.HudLayout.bounds().containsKey(kr.ddingmate.hud.HudLayout.SCHEDULE), "Tab 을 뗐는데 HUD 가 돌아오지 않음");
                CraftTimers.debugClear();
            });
            sp.getServer().runCommand("scoreboard objectives remove tabtest");

            // 9-6) 잠수 채널(실제 액션바 "292초 뒤 잠수 포인트를 획득합니다. | 현재 잠수 포인트 : 4239")에서는 HUD 를 띄우지 않고,
            //      채널 표시가 다시 보이면 돌아온다
            ctx.runOnClient(c -> {
                CraftTimers.onChat(START);
                c.inGameHud.setOverlayMessage(Text.literal("292초 뒤 잠수 포인트를 획득합니다. | 현재 잠수 포인트 : 4239"), false);
                check(kr.ddingmate.game.ServerHud.afk(), "잠수 채널을 알아보지 못함");
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> kr.ddingmate.hud.HudLayout.bounds().clear());
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.hud.HudLayout.bounds().isEmpty(), "잠수 채널에서 HUD 를 그림: " + kr.ddingmate.hud.HudLayout.bounds().keySet());
                c.inGameHud.setOverlayMessage(Text.literal("[칭호] 누군가 : 잠수 좋다"), false);
                kr.ddingmate.game.ServerHud.debugSet(kr.ddingmate.game.ServerHud.Area.VILLAGE, 30);
                check(!kr.ddingmate.game.ServerHud.afk(), "채널 표시가 다시 보였는데 잠수로 봄");
            });
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.hud.HudLayout.bounds().containsKey(kr.ddingmate.hud.HudLayout.SCHEDULE), "잠수 채널에서 나왔는데 HUD 가 돌아오지 않음");
                CraftTimers.debugClear();
            });

            // 10) 설정 화면: 재료 탭 목록, 홈 '제작 중'
            ctx.runOnClient(c -> {
                c.setScreen(new DdingMateScreen(null));
                ((DdingMateScreen) c.currentScreen).openTabNamed("재료");
            });
            ctx.waitTicks(3);
            ctx.runOnClient(CraftClientGameTest::scrollToBottom);
            ctx.waitTicks(3);
            ctx.takeScreenshot("craft-3-counter-tab");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).openTab(0));
            ctx.waitTicks(3);
            ctx.runOnClient(CraftClientGameTest::scrollToBottom);
            ctx.waitTicks(3);
            ctx.takeScreenshot("craft-4-home");
            ctx.runOnClient(c -> c.setScreen(null));

            // 11) 저장·불러오기, 예전 형식(시간 계산 방식이 다르던 때) 기록은 비우고 배운 시간은 남긴다
            ctx.runOnClient(c -> {
                int n = CraftTimers.jobs().size();
                CraftTimers.save();
                CraftTimers.debugClear();
                CraftTimers.load();
                check(CraftTimers.jobs().size() == n, "다시 불러온 제작 수가 다름: " + CraftTimers.jobs().size() + " / " + n);
                check(CraftTimers.secondsPerJob("조약돌 뭉치") == 20, "배운 시간이 사라짐");
                Path p = FabricLoader.getInstance().getConfigDir().resolve("ddingmate-crafts.json");
                String uuid = c.player.getUuid() + "@singleplayer";
                write(p, "{\"accounts\":{\"" + uuid + "\":[{\"name\":\"어빌리티 스톤\",\"count\":1,\"startedAt\":0,\"endsAt\":0}]},"
                        + "\"secondsPerJob\":{\"조약돌뭉치\":20}}");
                CraftTimers.load();
                check(CraftTimers.jobs().isEmpty(), "예전 형식의 가짜 제작이 남음: " + CraftTimers.jobs().size());
                check(CraftTimers.secondsPerJob("조약돌 뭉치") == 20, "예전 형식에서 배운 시간을 버림");
            });

            // 12) 끄면 아무것도 세지 않는다
            ctx.runOnClient(c -> {
                DdingMate.config().craft.enabled = false;
                CraftTimers.onChat(START);
                check(CraftTimers.jobs().isEmpty(), "꺼 두었는데 등록됨");
                DdingMate.config().craft.enabled = true;
                CraftTimers.debugClear();
                CraftTimers.save();
            });

            // 13) ◀ 를 누르면 이전 값, ▶ 를 누르면 다음 값(오른쪽 클릭은 이전 값)
            ctx.runOnClient(c -> {
                int[] value = {2};
                Widgets.Cycle<Integer> cycle = new Widgets.Cycle<>(200, "시험", IntStream.rangeClosed(0, 5).boxed().toList(), 2,
                        v -> v + "채널", v -> value[0] = v);
                cycle.setX(10);
                cycle.setY(10);
                int chipW = kr.ddingmate.ui.Theme.boldWidth(c.textRenderer, "2채널") + 26;
                int chipX = 10 + 200 - chipW - 3;
                cycle.mouseClicked(chipX + 5, 18, 0);
                check(value[0] == 1, "◀ 를 눌렀는데 이전 값이 아님: " + value[0]);
                cycle.mouseClicked(chipX + chipW - 5, 18, 0);
                check(value[0] == 2, "▶ 를 눌렀는데 다음 값이 아님: " + value[0]);
                cycle.mouseClicked(30, 18, 0);
                check(value[0] == 3, "이름 쪽을 누르면 다음 값이어야 함: " + value[0]);
                cycle.mouseClicked(30, 18, 1);
                check(value[0] == 2, "오른쪽 클릭이 이전 값이 아님: " + value[0]);
            });

            // 14) 장비: 예전 이름 규칙으로 저장한 같은 장비가 둘이면 하나로, 이름의 서버 그림 글자는 지운다
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = new DdingMateConfig();
                cfg.durability.tracked.clear();
                cfg.durability.tracked.add(new DdingMateConfig.TrackedItem("n:\uA006네더라이트곡괭이", "\uA006 네더라이트 곡괭이"));
                cfg.durability.tracked.add(new DdingMateConfig.TrackedItem("n:네더라이트곡괭이", "네더라이트 곡괭이"));
                cfg.sanitize();
                check(cfg.durability.tracked.size() == 1, "같은 장비가 둘로 남음: " + cfg.durability.tracked.size());
                check(cfg.durability.tracked.get(0).name.equals("네더라이트 곡괭이"), "이름에 그림 글자가 남음: " + cfg.durability.tracked.get(0).name);
            });

            // 14-1) 의뢰 창(2026-10-09 실제 창 모양): 보상까지 받은 칸("이미 완료한 의뢰입니다.")만 센다.
            //       진행도만 채우고 아직 안 받은 칸은 세지 않고, 많게 센 값(예전 버전에서 5/5)도 창에 맞춰 바로잡는다
            ctx.runOnClient(c -> {
                DdingMate.config().todo.items = new java.util.ArrayList<>(DdingMateConfig.Todo.defaults());
                DdingMateConfig.TodoItem daily = todo("daily_quest");
                daily.progress = 5; // 두 번 세던 예전 버전이 남긴 값
                daily.doneAt = System.currentTimeMillis();
                c.setScreen(facility(c,
                        quest("감자 심기 일일 의뢰", "매일의 의뢰 도전으로 성장해보세요.", "- 감자를 심어주세요.", "- 보상 : 3루비", "이미 완료한 의뢰입니다."),
                        quest("코끼리 사냥하기 일일 의뢰", "- 코끼리를 사냥하세요.", "- 보상 : 3루비", "이미 완료한 의뢰입니다."),
                        quest("감자 수확하기 일일 의뢰", "- 감자를 수확해주세요.", "- 의뢰 진행도 : 32 / 32", "- 보상 : 2루비"),
                        quest("당근 심기 일일 의뢰", "- 당근을 심어주세요.", "- 의뢰 진행도 : 0 / 32", "의뢰를 먼저 완료해주세요."),
                        quest("거미 사냥하기 일일 의뢰", "- 거미를 사냥해주세요.", "- 의뢰 진행도 : 0 / 10", "의뢰를 먼저 완료해주세요."),
                        // 같은 의뢰가 다른 칸에 한 번 더 보여도, 탭 단추에 비슷한 글이 있어도 더 세지 않는다(실제 서버에서 2개를 3개로 셈)
                        quest("감자 심기 일일 의뢰", "매일의 의뢰 도전으로 성장해보세요.", "- 감자를 심어주세요.", "- 보상 : 3루비", "이미 완료한 의뢰입니다."),
                        quest("일일 의뢰", "이미 완료한 의뢰입니다.")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                java.time.ZonedDateTime now = java.time.ZonedDateTime.now(kr.ddingmate.schedule.ScheduleEngine.KST);
                DdingMateConfig.TodoItem daily = todo("daily_quest");
                check(kr.ddingmate.todo.TodoList.progress(daily, now) == 2,
                        "창의 '이미 완료한 의뢰' 2개로 맞추지 않음(많게 센 값을 안 고치거나 안 받은 칸까지 셈): " + kr.ddingmate.todo.TodoList.progress(daily, now));
                // 창이 열린 채 보상을 받으면 완료 메시지가 오지만, 창이 정답이라 메시지로는 더 세지 않는다
                kr.ddingmate.todo.TodoList.onChat(G + "의뢰를 완료하였습니다.");
                check(kr.ddingmate.todo.TodoList.progress(daily, now) == 2, "창이 열린 채 온 완료 메시지로 두 번 셈");
                c.setScreen(facility(c,
                        quest("양파 씨앗 채집하기 주간 의뢰", "매주 새로운 목표에 도전해보세요.", "- 보상 : 5루비", "이미 완료한 의뢰입니다."),
                        quest("성게 채집하기 주간 의뢰", "- 의뢰 진행도 : 0 / 210", "의뢰를 먼저 완료해주세요."),
                        quest("미어캣 사냥하기 주간 의뢰", "- 의뢰 진행도 : 30 / 30", "- 보상 : 5루비")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                java.time.ZonedDateTime now = java.time.ZonedDateTime.now(kr.ddingmate.schedule.ScheduleEngine.KST);
                DdingMateConfig.TodoItem weekly = todo("weekly_quest");
                check(kr.ddingmate.todo.TodoList.progress(weekly, now) == 1, "주간 의뢰 창의 '이미 완료한 의뢰'를 세지 않음");
                c.setScreen(null);
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                // 주간 창을 닫은 직후(30초 안) 온 완료 메시지는 주간으로 +1
                java.time.ZonedDateTime now = java.time.ZonedDateTime.now(kr.ddingmate.schedule.ScheduleEngine.KST);
                kr.ddingmate.todo.TodoList.onChat(G + "의뢰를 완료하였습니다.");
                check(kr.ddingmate.todo.TodoList.progress(todo("weekly_quest"), now) == 2, "창을 닫은 직후 메시지를 주간으로 세지 않음");
                check(kr.ddingmate.todo.TodoList.progress(todo("daily_quest"), now) == 2, "주간 완료를 일일로도 셈");
            });

            // 14-2) 저장소에서 꺼내면("[아이템 저장소] 6개를 인출했습니다.") 그 무렵 인벤토리에 늘어난 아이템만큼 저장소 기록에서 뺀다
            //       (안 빼면 인벤토리 6 + 저장소 6 = 12 로 두 번 셈). 메시지가 먼저 와도, 아이템이 먼저 들어와도
            ctx.runOnClient(c -> {
                kr.ddingmate.counter.BagTracker.onOverlay("[아이템 저장소] 조약돌 1개 수납! (6/5,000)");
                kr.ddingmate.counter.BagTracker.onOverlay(" [아이템 저장소] 6개를 인출했습니다.");
            });
            sp.getServer().runCommand("give @a minecraft:cobblestone 6");
            ctx.waitTicks(10);
            ctx.runOnClient(c -> check(kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:cobblestone") == 0,
                    "꺼낸 6개를 저장소에서 빼지 않음(메시지 먼저): " + kr.ddingmate.counter.BagTracker.storageTotals()));
            ctx.runOnClient(c -> kr.ddingmate.counter.BagTracker.onOverlay("[아이템 저장소] 조약돌 1개 수납! (10/5,000)"));
            sp.getServer().runCommand("give @a minecraft:cobblestone 4");
            ctx.waitTicks(3);
            ctx.runOnClient(c -> kr.ddingmate.counter.BagTracker.onOverlay("[아이템 저장소] 4개를 인출했습니다."));
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:cobblestone") == 6,
                        "꺼낸 4개를 저장소에서 빼지 않음(아이템 먼저): " + kr.ddingmate.counter.BagTracker.storageTotals());
                check(kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:raw_copper") == null
                        || kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:raw_copper") >= 0, "다른 아이템 기록이 망가짐");
            });
            sp.getServer().runCommand("clear @a minecraft:cobblestone");
            ctx.waitTicks(3);

            // 14-3) 이번 초기화 뒤로 의뢰 창을 안 열었으면 [0/5] 대신 "의뢰 창을 열어 주세요!"
            ctx.runOnClient(c -> {
                DdingMate.config().todo.items = new java.util.ArrayList<>(DdingMateConfig.Todo.defaults());
                java.time.ZonedDateTime now = java.time.ZonedDateTime.now(kr.ddingmate.schedule.ScheduleEngine.KST);
                check(kr.ddingmate.todo.TodoList.label(todo("daily_quest"), now).equals("일일 의뢰 · 의뢰 창을 열어 주세요!"),
                        "창을 안 열었는데 안내가 아님: " + kr.ddingmate.todo.TodoList.label(todo("daily_quest"), now));
                check(kr.ddingmate.todo.TodoList.label(todo("weekly_quest"), now).equals("주간 의뢰 · 의뢰 창을 열어 주세요!"), "주간 의뢰 안내가 아님");
                kr.ddingmate.todo.TodoList.setExact(kr.ddingmate.todo.TodoPreset.DAILY_QUEST, 1);
                check(kr.ddingmate.todo.TodoList.label(todo("daily_quest"), now).equals("일일 의뢰 [1/5]"), "창을 본 뒤에도 개수가 안 나옴");
                check(kr.ddingmate.todo.TodoList.label(todo("weekly_quest"), now).equals("주간 의뢰 · 의뢰 창을 열어 주세요!"), "일일 창을 봤는데 주간까지 개수 표시");
                todo("daily_quest").windowSeenAt = kr.ddingmate.todo.TodoList.lastReset(DdingMateConfig.Reset.DAILY, now).minusMinutes(1).toInstant().toEpochMilli();
                check(kr.ddingmate.todo.TodoList.needsWindow(todo("daily_quest"), now), "지난 초기화 전에 본 창으로 개수를 믿음");
            });

            // 15) 본 적 없는 재료도 그림: 모드에 넣은 표가 실제 게임에서 기억한 모양(아이템·모델 번호)과 같은지
            ctx.runOnClient(c -> {
                check(kr.ddingmate.counter.IconCache.bundledSize() >= 280, "아이콘 표가 비었거나 잘못 읽힘: " + kr.ddingmate.counter.IconCache.bundledSize());
                // 2026-10-09 실제 서버에서 기억한 값(config/ddingmate-icons.json)
                look("코룸 주괴", Items.PAPER, 2006004f);
                look("코룸", Items.PAPER, 2006003f);
                look("조약돌 뭉치", Items.PAPER, 2006023f);
                look("하급 라이프스톤", Items.PAPER, 2006024f);
                look("어빌리티 스톤", Items.PAPER, 2006040f);
                look("세이지 곡괭이", Items.GOLDEN_PICKAXE, 2016003f);
                look("강화 제작 시설 청사진", Items.PAPER, 2010017f);
                // 한 번도 안 본 재료
                look("요리용 소금", Items.PAPER, 2013011f);
                look("요리용 우유", Items.PAPER, 2013007f);
                look("치즈 조각", Items.PAPER, 2013003f);
                look("아메리카노", Items.ENCHANTED_BOOK, 2032001f);
                look("황금 어니언 링", Items.COOKED_BEEF, 2004114f);
                look("굴 ★★★", Items.PAPER, 2027079f);
                // 레시피 대표 그림은 첫 재료가 아니라 만드는 아이템(하급 라이프스톤 → 라이프스톤 그림, 첫 재료 조약돌 뭉치 아님)
                kr.ddingmate.counter.IconCache.debugForgetSeen(); // 앞 시험에서 만든 가짜 아이템 모양은 잊고 표만으로
                kr.ddingmate.counter.RecipeBook.Recipe life = kr.ddingmate.counter.RecipeBook.get("강화 제작 시설/하급 라이프스톤");
                ItemStack lifeIcon = kr.ddingmate.counter.IconCache.forRecipe(life);
                check(ItemStack.areEqual(lifeIcon, kr.ddingmate.counter.IconCache.forMaterial("하급 라이프스톤")),
                        "하급 라이프스톤 대표 그림이 하급 라이프스톤 모양이 아님");
                check(!ItemStack.areEqual(lifeIcon, kr.ddingmate.counter.IconCache.forMaterial(life.materials.get(0).name)),
                        "대표 그림이 첫 재료(" + life.materials.get(0).name + ") 모양임");
                kr.ddingmate.counter.RecipeBook.Recipe cheese24 = kr.ddingmate.counter.RecipeBook.get("농작물 가공 시설/치즈 조각 x24");
                check(ItemStack.areEqual(kr.ddingmate.counter.IconCache.forRecipe(cheese24), kr.ddingmate.counter.IconCache.forMaterial("치즈 조각")),
                        "'치즈 조각 x24' 대표 그림이 치즈 조각이 아님");
                // 2차(텍스처를 직접 보고 대조): 귀중품·원석·항해 뱃지·연금품·인피니티 타워 재료·모듈·도구
                look("(Mythic) 탁월한 키론 오르골", Items.PAPER, 2046004f);
                look("(Common)넬트 천체관측기", Items.PAPER, 2046025f);
                look("피로시아 (Rare)", Items.PAPER, 2045036f);
                look("홍옥의 팔찌 획득권", Items.PAPER, 2025057f);
                look("천상의 성배 획득권", Items.PAPER, 2025109f);
                look("봉인된 소중한 귀걸이", Items.PAPER, 2025103f);
                look("부식의 엘릭서 ★★★", Items.PAPER, 2027057f);
                look("수호의 정수 ★", Items.PAPER, 2027054f);
                look("맹독 파동의 영약 ★★★", Items.PAPER, 2027041f);
                look("정제된 고요한 포자", Items.PAPER, 2035030f);
                look("커피 그라인더 모듈", Items.PAPER, 2032047f);
                look("어항 모듈", Items.LEATHER_HORSE_ARMOR, 1210001f);
                look("세이지 낚싯대", Items.FISHING_ROD, 2016017f); // 실제 게임 값
                look("소금", Items.PAPER, 2013006f);
                look("고요한 결정", Items.PAPER, 2020005f);
                look("하급 광채 생성기", Items.LEATHER_HORSE_ARMOR, 1203001f);
                look("영웅의 체력 포션", Items.GLISTERING_MELON_SLICE, 2035020f);
                // 16) 바닐라 재료(당근·감자·비트)는 작물 블록이 아니라 실제 아이템으로 그리고 센다
                for (String[] v : new String[][]{{"당근", "minecraft:carrot"}, {"감자", "minecraft:potato"}, {"비트", "minecraft:beetroot"}}) {
                    ItemStack icon = kr.ddingmate.counter.IconCache.forMaterial(v[0]);
                    check(icon != null && net.minecraft.registry.Registries.ITEM.getId(icon.getItem()).toString().equals(v[1]),
                            v[0] + " 그림이 " + v[1] + " 가 아님: " + icon);
                    check(kr.ddingmate.counter.RecipeBook.keysFor(v[0]).contains("v:" + v[1]), v[0] + " 를 인벤토리 " + v[1] + " 로 세지 않음");
                }
                // 17) 서버의 별 그림 글자(産 ★ · 山 ★★ · 算 ★★★)를 레시피 이름의 별과 같게 본다
                check(kr.ddingmate.counter.ItemKeys.normalize("굴山").equals(kr.ddingmate.counter.ItemKeys.normalize("굴 ★★")), "山 을 ★★ 로 보지 않음");
                check(kr.ddingmate.counter.ItemKeys.normalize("영생의 아쿠티스産").equals(kr.ddingmate.counter.ItemKeys.normalize("영생의 아쿠티스 ★")), "産 을 ★ 로 보지 않음");
                check(kr.ddingmate.counter.ItemKeys.normalize("무저의 척추算").equals(kr.ddingmate.counter.ItemKeys.normalize("무저의 척추 ★★★")), "算 을 ★★★ 로 보지 않음");
                check(!kr.ddingmate.counter.ItemKeys.normalize("굴山").equals(kr.ddingmate.counter.ItemKeys.normalize("굴 ★")), "★ 와 ★★ 를 같게 봄");
                // 18) 모드에 넣어 둔 제작 시간: 시설 창 이름("키론 오르골")·등급 붙은 레시피 이름 모두로 찾는다
                check(kr.ddingmate.counter.RecipeBook.timeFor("키론 오르골") == 45, "키론 오르골 시간을 못 찾음");
                check(kr.ddingmate.counter.RecipeBook.timeFor("(Rare) 키론 오르골") == 45, "등급 붙은 이름으로 시간을 못 찾음");
                check(kr.ddingmate.counter.RecipeBook.timeFor("탁월한 키론 오르골") == 1800, "탁월한 키론 오르골 시간이 다름");
                check(kr.ddingmate.counter.RecipeBook.timeFor("굴山") == 5, "별 그림 글자 이름으로 시간을 못 찾음");
                check(kr.ddingmate.counter.RecipeBook.timeFor("하급 라이프스톤") == 60, "하급 라이프스톤 60초가 아님");
                // 19) 저장소 보관 개수 읽기: 낱말이 있으면 그 수, 없으면 조작 안내가 아닌 줄의 "N개" 중 큰 수, 없으면 -1
                check(stored("보관 중: 1,234개") == 1234, "보관 중 줄을 못 읽음");
                check(stored(" 현재 4,096개", "좌클릭: 64개 꺼내기", "쉬프트 클릭: 전부 꺼내기") == 4096, "N개 줄을 못 읽거나 꺼내기 안내를 읽음");
                check(stored("우클릭: 꺼내기") == -1, "숫자 없는 설명을 수로 읽음");
                // 실제 아이템 저장소 설명(2026-10-09): 재고 앞 수만, 최대치(5,000)·세트·꺼내기 안내는 무시
                check(stored("아이템을 저장하는 저장소입니다.", "다양한 아이템을 보관해 보세요.", "- 재고: 2,868 / 5,000개", "- 세트: 44세트 52개",
                        "우클릭 시 1개를 꺼냅니다.", "쉬프트 우클릭 시 64개를 꺼냅니다.", "Q키 입력 시 모두 꺼냅니다.") == 2868, "재고 2,868 을 못 읽음");
                check(stored("아이템을 저장하는 저장소입니다.", "- 재고: 0 / 5,000개", "- 세트: 0개", "물품을 들고 클릭 시 변경합니다.") == 0,
                        "재고 0 을 최대치 5,000 으로 읽음");
                // 20) 정밀 검사: 레시피 378개의 결과물·재료 이름 전부에 그림이 있다(서버 표 또는 바닐라)
                java.util.List<String> noIcon = new java.util.ArrayList<>();
                java.util.Set<String> every = new java.util.TreeSet<>();
                for (kr.ddingmate.counter.RecipeBook.Recipe r : kr.ddingmate.counter.RecipeBook.all()) {
                    every.add(r.name);
                    for (kr.ddingmate.counter.RecipeBook.Material m : r.materials) every.add(m.name);
                }
                for (String n : every) if (kr.ddingmate.counter.IconCache.forMaterial(n) == null) noIcon.add(n);
                DdingMate.LOG.info("그림 없는 이름 {}/{}: {}", noIcon.size(), every.size(), noIcon);
                check(noIcon.isEmpty(), "그림이 없는 이름: " + noIcon);
                check(every.stream().allMatch(n -> kr.ddingmate.counter.IconCache.forRecipe(kr.ddingmate.counter.RecipeBook.recipeFor(n)) != null
                        || kr.ddingmate.counter.RecipeBook.recipeFor(n) == null), "대표 그림이 없는 레시피가 있음");
                ItemStack lantern = kr.ddingmate.counter.IconCache.forMaterial("영혼 램프");
                check(lantern != null && lantern.isOf(Items.SOUL_LANTERN), "영혼 램프가 영혼 랜턴 그림이 아님: " + lantern);
                look("활기찬 가죽 장갑", Items.PAPER, 2059103f);
                // 21) 자동 수납 알림 "[아이템 저장소] 조약돌 1개 수납! (2/5,000)" → 저장소 재고 2
                kr.ddingmate.counter.BagTracker.onOverlay("[아이템 저장소] 조약돌 1개 수납! (2/5,000)");
                check(kr.ddingmate.counter.BagTracker.storageTotals().getOrDefault("v:minecraft:cobblestone", -1) == 2,
                        "수납 알림으로 저장소 재고를 맞추지 않음: " + kr.ddingmate.counter.BagTracker.storageTotals());
                kr.ddingmate.counter.BagTracker.onOverlay("[아이템 저장소] 조약돌 64개 수납! (1,066/5,000)");
                check(kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:cobblestone") == 1066, "쉼표가 든 재고를 못 읽음");
                kr.ddingmate.counter.BagTracker.onOverlay("[아이템 저장소] 코룸 3개 수납! (40/5,000)");
                check(kr.ddingmate.counter.BagTracker.storageTotals().getOrDefault(kr.ddingmate.counter.ItemKeys.nameKey("코룸"), -1) == 40,
                        "서버 아이템 수납 알림을 이름으로 기록하지 않음");
                // 21-1) 액션바로 온 수납 알림(채팅 이벤트를 안 거침)도 저장소 재고에 반영
                c.inGameHud.setOverlayMessage(Text.literal("[아이템 저장소] 조약돌 1개 수납! (77/5,000)"), false);
                check(kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:cobblestone") == 77,
                        "액션바 수납 알림을 반영하지 않음: " + kr.ddingmate.counter.BagTracker.storageTotals());
                c.inGameHud.setSubtitle(Text.literal("[아이템 저장소] 조약돌 1개 수납! (78/5,000)"));
                check(kr.ddingmate.counter.BagTracker.storageTotals().get("v:minecraft:cobblestone") == 78, "부제목 수납 알림을 반영하지 않음");
                // 21-2) 전문가 스킬 창(2026-10-09 실제 칸)에서 제작 시간 감소 스킬 레벨을 자동으로 읽는다
                java.util.List<String> furnace = java.util.List.of("전문가 스킬 정보", ". 채광물 가공과 강화 제작 시설의", ". 제작 시간이 10% 감소합니다.",
                        "──────────────────", ". [강화 시 스킬 정보]", ". 채광물 가공과 강화 제작 시설의", ". 제작 시간이 30% 감소합니다.",
                        "──────────────────", ". - 보유 스킬 포인트 : 118포인트");
                check(kr.ddingmate.craft.ExpertSkills.debugLevel("[세레니티] 초고속 용광로 (+1)", furnace) == 1, "1레벨(10%)을 못 읽음(미리보기 30%를 읽었나?)");
                check(kr.ddingmate.craft.ExpertSkills.debugLevel("[세레니티] 초고속 용광로 (MAX)",
                        java.util.List.of("전문가 스킬 정보", ". 채광물 가공과 강화 제작 시설의", ". 제작 시간이 80% 감소합니다.", ". 최대 스킬 레벨에 도달하였습니다.")) == 5, "MAX(80%)를 못 읽음");
                check(kr.ddingmate.craft.ExpertSkills.debugLevel("[세레니티] 물 흐르듯 술술 (+3)",
                        java.util.List.of(". 제작 시간이 30% 감소합니다.", "────", ". 제작 시간이 50% 감소합니다.")) == 3, "해양 표(30%=3레벨)로 안 읽음");
                check(kr.ddingmate.craft.ExpertSkills.debugLevel("[세레니티] 초고속 용광로 (+0)", furnace) == 0, "안 배운 스킬을 배운 것으로 읽음");
                // 실제 사냥 칸은 숫자가 다음 줄로 넘어간다: "영혼 가공 시설의 제작 시간이 / 10% 감소합니다."
                check(kr.ddingmate.craft.ExpertSkills.debugLevel("[세레니티] 고속 영혼 가공기 (+1)",
                        java.util.List.of("전문가 스킬 정보", ". 영혼 가공 시설의 제작 시간이", ". 10% 감소합니다.", "──────────────────",
                                ". [강화 시 스킬 정보]", ". 영혼 가공 시설의 제작 시간이", ". 30% 감소합니다.")) == 1, "줄이 나뉜 사냥 스킬(10%)을 못 읽음");
                DdingMate.config().craft.mining = 0;
                ItemStack skill = item("[세레니티] 초고속 용광로 (+2)", 1, "전문가 스킬 정보", ". 채광물 가공과 강화 제작 시설의",
                        ". 제작 시간이 30% 감소합니다.", "──────────────────", ". [강화 시 스킬 정보]", ". 제작 시간이 40% 감소합니다.");
                check(kr.ddingmate.craft.ExpertSkills.read(skill) && DdingMate.config().craft.mining == 2, "스킬 칸으로 채광 레벨을 설정하지 않음: " + DdingMate.config().craft.mining);
                check(kr.ddingmate.craft.CraftTimers.reductionPercent("채광물 가공 시설") == 30, "자동으로 읽은 레벨이 제작 시간에 안 들어감");
                check(!kr.ddingmate.craft.ExpertSkills.read(item("[세레니티] 코비 타임 (+1)", 1, ". 제작 시간이 10% 감소합니다.")), "다른 스킬을 제작 시간 스킬로 읽음");
                DdingMate.config().craft.mining = 0;
                // 21-25) 스킬 창에서 읽은 스킬은 창을 닫을 때 채팅 한 번으로(제작 시간·판매가를 종류별로 묶어서)
                kr.ddingmate.skill.SkillReport.flush();
                DdingMate.config().expert.priceSkills.clear();
                SimpleInventory skw = new SimpleInventory(54);
                skw.setStack(10, item("[세레니티] 초고속 용광로 (+2)", 1, "전문가 스킬 정보", ". 채광물 가공과 강화 제작 시설의",
                        ". 제작 시간이 30% 감소합니다.", "──────────────────", ". [강화 시 스킬 정보]", ". 제작 시간이 40% 감소합니다."));
                skw.setStack(13, item("[세레니티] 값어치 증명 (+0)", 1,"전문가 스킬 정보", ". 영혼의 판매가가 5% 증가합니다."));
                skw.setStack(11, item("[세레니티] 주괴 좀 사주괴 (+1)", 1, "전문가 스킬 정보", ". 주괴의 판매가가 5% 증가합니다.", "──────────────────", ". 주괴의 판매가가 7% 증가합니다."));
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(95, c.player.getInventory(), skw),
                        c.player.getInventory(), Text.literal("전문가")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                // 서버는 전문가마다 창을 새로 연다: 창이 바뀌어도 채팅을 나눠 보내지 않고 계속 모은다
                check(kr.ddingmate.skill.SkillReport.pending() == 3, "첫 창 스킬을 못 모음: " + kr.ddingmate.skill.SkillReport.preview());
                SimpleInventory skw2 = new SimpleInventory(54);
                skw2.setStack(12, item("[세레니티] 반짝반짝 눈이 부셔 (+4)", 1, "전문가 스킬 정보", ". 정동석의 판매가가 20% 증가합니다."));
                skw2.setStack(13, item("[세레니티] 치솟는 생선값 (+2)", 1, "전문가 스킬 정보", ". 물고기의 판매가가 5% 증가합니다."));
                c.setScreen(new GenericContainerScreen(GenericContainerScreenHandler.createGeneric9x6(96, c.player.getInventory(), skw2),
                        c.player.getInventory(), Text.literal("전문가")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                String pv = kr.ddingmate.skill.SkillReport.preview();
                check(kr.ddingmate.skill.SkillReport.pending() >= 3 && pv.contains("제작 시간 · ") && pv.contains("판매가 · ")
                        && pv.contains("초고속 용광로 30%") && pv.contains("주괴 좀 사주괴(주괴 +5%)") && pv.contains("정동석 +20%"),
                        "스킬 창에서 읽은 스킬을 한 메시지로 모으지 않음: " + pv);
                check(pv.split("\n").length == 4 && pv.endsWith("\n  안 배움 · 값어치 증명") && !pv.contains("판매가 · 값어치"),
                        "종류별 한 줄씩 + 안 배운 스킬은 맨 아래 한 줄이 아님: " + pv);
                // 전문가마다 글자색: 채광 하늘색, 해양 파랑. 머리줄 범례는 배운 스킬의 전문가만
                check(kr.ddingmate.skill.SkillReport.debugColor("초고속 용광로") == net.minecraft.util.Formatting.AQUA
                        && kr.ddingmate.skill.SkillReport.debugColor("주괴 좀 사주괴") == net.minecraft.util.Formatting.AQUA
                        && kr.ddingmate.skill.SkillReport.debugColor("치솟는 생선값") == net.minecraft.util.Formatting.BLUE,
                        "전문가별 글자색이 다름: " + kr.ddingmate.skill.SkillReport.debugColor("치솟는 생선값"));
                check(pv.split("\n")[0].endsWith(" ■채광 ■해양"), "머리줄 전문가 색 범례가 다름: " + pv.split("\n")[0]);
                check(pv.contains("판매가 · 주괴 좀 사주괴(주괴 +5%), 반짝반짝 눈이 부셔(정동석 +20%), 치솟는 생선값(물고기 +5%)"),
                        "같은 전문가끼리 모아 보여 주지 않음: " + pv);
                c.setScreen(null);
            });
            ctx.waitTicks(5);
            ctx.runOnClient(c -> check(kr.ddingmate.skill.SkillReport.pending() == 5,"창을 닫자마자 보냄(다음 창이 열리는 틈을 안 기다림)"));
            ctx.waitTicks(kr.ddingmate.skill.SkillReport.IDLE_TICKS + 5);
            ctx.runOnClient(c -> {
                check(kr.ddingmate.skill.SkillReport.pending() == 0, "창을 모두 닫았는데 스킬 확인 채팅을 안 보냄");
                DdingMate.config().craft.mining = 0;
                DdingMate.config().expert.priceSkills.clear();
                // 21-3) 쉬프트 우클릭 스킬(실제 2026-10-09 창 칸, 2026-10 실제 채팅 순서)
                kr.ddingmate.skill.SkillTimers.debugClear();
                check(kr.ddingmate.skill.SkillTimers.read(item("[야생] 곡괭이 나가신다! (+5)", 1, "전문가 스킬 정보",
                        ". 15초 간 3x3를 부실 수 있습니다.", ". 곡괭이를 들고 쉬프트 우클릭합니다.", "..(재사용 대기 시간 : 180초)",
                        "──────────────────", ". [강화 시 스킬 정보]", ". 17초 간 3x3를 부실 수 있습니다.",
                        ". 곡괭이를 들고 쉬프트 우클릭합니다.", "..(재사용 대기 시간 : 240초)", "──────────────────",
                        ". - 보유 스킬 포인트 : 118포인트")), "쉬프트 우클릭 스킬 칸을 못 읽음");
                kr.ddingmate.skill.SkillTimers.Skill pick = kr.ddingmate.skill.SkillTimers.debugSkill("곡괭이 나가신다!");
                check(pick != null && pick.level == 5 && pick.durationSec == 15 && pick.cooldownSec == 180,
                        "지금 레벨 값(15초·180초)이 아님(미리보기 17초·240초를 읽었나?): " + (pick == null ? null : pick.durationSec + "/" + pick.cooldownSec));
                check(!kr.ddingmate.skill.SkillTimers.read(item("[세레니티] 초고속 용광로 (+2)", 1, "전문가 스킬 정보", ". 채광물 가공과 강화 제작 시설의",
                        ". 제작 시간이 30% 감소합니다.")),"제작 시간 스킬을 쉬프트 우클릭 스킬로 읽음");
                String g = G; // 실제 메시지처럼 앞에 서버 그림 글자
                long t0 = now();
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬을 활성화했습니다.", t0);
                List<kr.ddingmate.skill.SkillTimers.Row> sr = kr.ddingmate.skill.SkillTimers.rows(t0 + 1000);
                check(sr.size() == 1 && kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 1000).equals("사용 중 14초"),
                        "활성화 1초 뒤 남은 지속 시간이 14초가 아님: " + (sr.isEmpty() ? "없음" : kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 1000)));
                // 활성화 직후 서버가 "2분 59초"라고 하면 HUD 도 2분 59초(재사용 대기는 활성화 순간부터)
                kr.ddingmate.skill.SkillTimers.Row cool = new kr.ddingmate.skill.SkillTimers.Row(pick, kr.ddingmate.skill.SkillTimers.Phase.COOLDOWN, pick.readyAt, 180_000);
                check(pick.readyAt == t0 + 180_000, "재사용 대기를 활성화 순간부터 세지 않음");
                check(kr.ddingmate.skill.SkillTimers.text(cool, t0 + 50).equals("2분 59초"), "서버(2분 59초)와 다르게 보임: " + kr.ddingmate.skill.SkillTimers.text(cool, t0 + 50));
                // 창 지속 시간(15초)이 지나면 종료 메시지를 기다리지 않고 재사용 대기로 넘어간다
                sr = kr.ddingmate.skill.SkillTimers.rows(t0 + 15_200);
                check(sr.size() == 1 && sr.get(0).phase() == kr.ddingmate.skill.SkillTimers.Phase.COOLDOWN, "15초가 지났는데 아직 사용 중");
                // 종료 메시지는 다음 블록을 캘 때 와서 늦을 수 있다(실측 15~16초, 창을 열어 둔 사이 39·49초) → 지속 시간은 창 값 그대로
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬 지속시간이 종료되었습니다.", t0 + 16_000);
                check(pick.activeUntil == 0 && kr.ddingmate.skill.SkillTimers.durationMs(pick) == 15_000, "종료 메시지로 지속 시간을 바꿈: " + kr.ddingmate.skill.SkillTimers.durationMs(pick));
                sr = kr.ddingmate.skill.SkillTimers.rows(t0 + 16_500);
                check(sr.size() == 1 && sr.get(0).phase() == kr.ddingmate.skill.SkillTimers.Phase.COOLDOWN
                        && kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 16_500).equals("2분 43초"), "끝난 뒤 재사용 대기로 넘어가지 않음");
                // 서버 남은 시간과 맞으면 그대로, 어긋나면 서버 값으로 맞춘다
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬의 쿨타임이 1분 36초 남았습니다.", t0 + 83_500);
                check(pick.readyAt == t0 + 180_000, "맞는 시간을 서버 메시지로 흔듦");
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬의 쿨타임이 1분 0초 남았습니다.", t0 + 83_500);
                sr = kr.ddingmate.skill.SkillTimers.rows(t0 + 83_600);
                check(kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 83_600).equals("1분 0초"), "서버가 알려 준 남은 시간으로 맞추지 않음: " + kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 83_600));
                // 시간이 되면 서버 메시지를 기다리지 않고 바로 "사용 가능"("곧 사용 가능" 같은 짐작 표기 없음)
                sr = kr.ddingmate.skill.SkillTimers.rows(t0 + 144_000);
                check(sr.size() == 1 && kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 144_000).equals("사용 가능"),
                        "재사용 시간이 됐는데 바로 사용 가능으로 안 바뀜: " + (sr.isEmpty() ? "없음" : kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 144_000)));
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬을 사용할 수 있습니다.", t0 + 144_500);
                sr = kr.ddingmate.skill.SkillTimers.rows(t0 + 145_000);
                check(sr.size() == 1 && kr.ddingmate.skill.SkillTimers.text(sr.get(0), t0 + 145_000).equals("사용 가능"), "다시 쓸 수 있다고 보여 주지 않음");
                check(kr.ddingmate.skill.SkillTimers.rows(t0 + 150_000).isEmpty(), "사용 가능 표시가 사라지지 않음");
                // 종료 메시지가 39초 뒤에 오면(실측, 늦은 메시지) 버리고, 15초 뒤에 오면 더 짧은 15초로 바꾼다
                long t1 = t0 + 200_000;
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬을 활성화했습니다.", t1);
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬 지속시간이 종료되었습니다.", t1 + 39_000);
                check(kr.ddingmate.skill.SkillTimers.durationMs(pick) == 15_000, "늦은 종료 메시지(39초)로 지속 시간을 바꿈: " + kr.ddingmate.skill.SkillTimers.durationMs(pick));
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬을 활성화했습니다.", t1 + 200_000);
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬 지속시간이 종료되었습니다.", t1 + 215_000);
                kr.ddingmate.skill.SkillTimers.debugChat(g + "곡괭이 나가신다! 스킬을 활성화했습니다.", t1 + 400_000);
                sr = kr.ddingmate.skill.SkillTimers.rows(t1 + 400_000);
                check(kr.ddingmate.skill.SkillTimers.text(sr.get(0), t1 + 400_000).equals("사용 중 15초"), "창 지속 시간(15초)을 쓰지 않음: " + kr.ddingmate.skill.SkillTimers.text(sr.get(0), t1 + 400_000));
                // 쓸어담는 광질 +1: 창 3초. 종료 메시지는 다음 광석을 캘 때 와서 5~7초로 잡히지만 실제는 창 값 3초
                check(kr.ddingmate.skill.SkillTimers.read(item("[세레니티] 쓸어담는 광질 (+1)", 1, "전문가 스킬 정보", ". 3초 간 광석을 한 번에 캡니다.",
                        ". 곡괭이를 들고 쉬프트 우클릭합니다.", "..(재사용 대기 시간 : 300초)")), "광질 칸을 못 읽음");
                kr.ddingmate.skill.SkillTimers.Skill sweep = kr.ddingmate.skill.SkillTimers.debugSkill("쓸어담는 광질");
                for (int k : new int[]{6, 5, 7}) {
                    long ta = t1 + 1_000_000L * k;
                    kr.ddingmate.skill.SkillTimers.debugChat(g + "쓸어담는 광질 스킬을 활성화했습니다.", ta);
                    kr.ddingmate.skill.SkillTimers.debugChat(g + "쓸어담는 광질 스킬 지속시간이 종료되었습니다.", ta + k * 1000L);
                }
                check(kr.ddingmate.skill.SkillTimers.durationMs(sweep) == 3_000, "광질을 창 값 3초가 아닌 늦은 종료 메시지로 셈: " + kr.ddingmate.skill.SkillTimers.durationMs(sweep));
                t1 += 400_000;
                // 창을 아직 못 본 스킬: 숫자를 지어내지 않고, 서버가 남은 시간을 알려 주면 그 값으로
                kr.ddingmate.skill.SkillTimers.debugChat(g + "떡밥을 뿌려라! 스킬을 활성화했습니다.", t1);
                kr.ddingmate.skill.SkillTimers.Skill bait = kr.ddingmate.skill.SkillTimers.debugSkill("떡밥을 뿌려라!");
                kr.ddingmate.skill.SkillTimers.Row baitRow = kr.ddingmate.skill.SkillTimers.rows(t1 + 100).stream().filter(r -> r.skill() == bait).findFirst().orElseThrow();
                check(kr.ddingmate.skill.SkillTimers.text(baitRow, t1 + 100).equals("스킬 창 열면 표시"), "모르는 재사용 시간을 지어냄");
                kr.ddingmate.skill.SkillTimers.debugChat(g + "떡밥을 뿌려라! 스킬의 쿨타임이 4분 56초 남았습니다.", t1 + 4_000);
                baitRow = kr.ddingmate.skill.SkillTimers.rows(t1 + 4_100).stream().filter(r -> r.skill() == bait).findFirst().orElseThrow();
                check(kr.ddingmate.skill.SkillTimers.text(baitRow, t1 + 4_100).equals("4분 56초"), "서버가 알려 준 남은 시간을 쓰지 않음");
                kr.ddingmate.skill.SkillTimers.debugChat(g + "떡밥을 뿌려라! 스킬을 사용할 수 있습니다.", t1 + 300_000);
                check(bait.cooldownSec == 300, "활성화~사용 가능으로 재사용 시간을 배우지 못함: " + bait.cooldownSec);
                // 남이 쓴 채팅·귓속말은 무시
                kr.ddingmate.skill.SkillTimers.onChat("[칭호] 누군가 : 쓸어담는 맛 스킬을 활성화했습니다.");
                kr.ddingmate.skill.SkillTimers.onChat("(단무지 → 멜트<받음>) 쓸어담는 맛 스킬을 활성화했습니다.");
                check(kr.ddingmate.skill.SkillTimers.debugSkill("쓸어담는 맛") == null, "남이 쓴 채팅·귓속말로 스킬을 등록함");
                long sweepAt = sweep.activatedAt;
                kr.ddingmate.skill.SkillTimers.onChat("(단무지 → 멜트<받음>) 쓸어담는 광질 스킬을 활성화했습니다.");
                check(sweep.activatedAt == sweepAt, "귓속말로 이미 아는 스킬을 활성화함");
                // 22) 마인리스트 추천 정보 "└ 오늘 추천 여부 : 추천 완료" → 체크, 남이 흉내 낸 채팅은 무시
                DdingMate.config().todo.items = new java.util.ArrayList<>(DdingMateConfig.Todo.defaults());
                java.time.ZonedDateTime now2 = java.time.ZonedDateTime.now(kr.ddingmate.schedule.ScheduleEngine.KST);
                kr.ddingmate.todo.TodoList.onChat("[칭호] 누군가 : └ 오늘 추천 여부 : 추천 완료");
                check(!kr.ddingmate.todo.TodoList.isDone(todo("recommend"), now2), "남이 쓴 채팅으로 추천이 체크됨");
                kr.ddingmate.todo.TodoList.onChat("ꀂ └ 오늘 추천 여부 : 추천 완료");
                check(kr.ddingmate.todo.TodoList.isDone(todo("recommend"), now2), "추천 정보로 체크되지 않음");
                ItemStack quartz = kr.ddingmate.counter.IconCache.forMaterial("석영");
                check(quartz != null && quartz.isOf(Items.QUARTZ), "바닐라 재료(석영) 그림이 아님");
                // 레시피의 모든 재료 중 표나 바닐라로 그림이 나오는 비율
                java.util.Set<String> all = new java.util.HashSet<>();
                for (kr.ddingmate.counter.RecipeBook.Recipe r : kr.ddingmate.counter.RecipeBook.all()) {
                    for (kr.ddingmate.counter.RecipeBook.Material m : r.materials) all.add(m.name);
                }
                long shown = all.stream().filter(n -> kr.ddingmate.counter.IconCache.forMaterial(n) != null).count();
                DdingMate.LOG.info("재료 그림 {}/{}", shown, all.size());
                check(shown * 100 / all.size() >= 95, "재료 그림이 95% 미만: " + shown + "/" + all.size());
            });
            // 21-35) 서버가 스킬과 함께 상태 효과·아이템 쿨타임을 보내면 그 정확한 값으로 맞춘다.
            //        길이가 스킬 창 값과 동떨어진 효과(예: 10분짜리 속도)는 짝짓지 않고, 효과가 사라지면 그 순간 끝
            ctx.runOnClient(c -> {
                c.setScreen(null);
                kr.ddingmate.skill.SkillTimers.debugClear();
                kr.ddingmate.skill.SkillSignals.debugReset();
                kr.ddingmate.skill.SkillTimers.read(item("[야생] 곡괭이 나가신다! (+5)", 1, ". 15초 간 3x3를 부실 수 있습니다.",
                        ". 곡괭이를 들고 쉬프트 우클릭합니다.", "..(재사용 대기 시간 : 180초)"));
                kr.ddingmate.skill.SkillTimers.debugChat(G + "곡괭이 나가신다! 스킬을 활성화했습니다.", now());
                c.player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SPEED, 12_000, 0));
                c.player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.HASTE, 17 * 20, 2));
                c.player.getItemCooldownManager().set(net.minecraft.util.Identifier.ofVanilla("netherite_pickaxe"), 181 * 20);
            });
            ctx.waitTicks(4);
            ctx.runOnClient(c -> {
                kr.ddingmate.skill.SkillTimers.Skill pick = kr.ddingmate.skill.SkillTimers.debugSkill("곡괭이 나가신다!");
                check(pick.effect.equals("minecraft:haste"), "스킬 효과를 짝짓지 못하거나 엉뚱한 효과와 짝지음: '" + pick.effect + "'");
                check(pick.cooldownGroup.equals("minecraft:netherite_pickaxe"), "아이템 쿨타임을 짝짓지 못함: '" + pick.cooldownGroup + "'");
                long left = pick.activeUntil - now();
                check(left > 15_500 && left <= 17_000, "효과 남은 시간(약 17초)으로 맞추지 않음: " + left);
                long ready = pick.readyAt - now();
                check(ready > 179_000 && ready <= 181_000, "아이템 쿨타임(181초)으로 맞추지 않음: " + ready);
                // 늦게 온 종료 메시지는 효과가 남아 있으면 무시
                kr.ddingmate.skill.SkillTimers.onChat(G + "곡괭이 나가신다! 스킬 지속시간이 종료되었습니다.");
                check(pick.activeUntil > now(), "효과가 남았는데 종료 메시지로 끝냄");
                c.player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.HASTE);
                c.player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED);
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                kr.ddingmate.skill.SkillTimers.Skill pick = kr.ddingmate.skill.SkillTimers.debugSkill("곡괭이 나가신다!");
                check(pick.activeUntil == 0, "효과가 사라졌는데 사용 중으로 남음");
                c.player.getItemCooldownManager().remove(net.minecraft.util.Identifier.ofVanilla("netherite_pickaxe"));
                kr.ddingmate.skill.SkillTimers.debugClear();
                kr.ddingmate.skill.SkillSignals.debugReset();
            });

            // 21-4) 지금 쓰고 있는 스킬은 HUD 에 그려진다(그리는 중 오류 없음, 자리 기록)
            ctx.runOnClient(c -> {
                c.setScreen(null);
                kr.ddingmate.skill.SkillTimers.debugClear();
                kr.ddingmate.skill.SkillTimers.read(item("[야생] 곡괭이 나가신다! (+5)", 1, ". 15초 간 3x3를 부실 수 있습니다.",
                        ". 곡괭이를 들고 쉬프트 우클릭합니다.", "..(재사용 대기 시간 : 180초)"));
                kr.ddingmate.skill.SkillTimers.read(item("[마을/야생] 떡밥을 뿌려라! (+5)", 1, ". 30초 간 입질 시간이 25% 감소하고,",
                        ". 접근 시간이 1.2초로 감소합니다.", ". 낚싯대를 들고 쉬프트 우클릭합니다.", "..(재사용 대기 시간 : 300초)"));
                kr.ddingmate.skill.SkillTimers.debugChat(G + "곡괭이 나가신다! 스킬을 활성화했습니다.", now() - 4_000);
                kr.ddingmate.skill.SkillTimers.debugChat(G + "떡밥을 뿌려라! 스킬을 활성화했습니다.", now() - 95_000);
                c.getToastManager().clear();
                c.inGameHud.getChatHud().clear(false);
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("skill-1-hud");
            ctx.runOnClient(c -> {
                check(kr.ddingmate.hud.HudLayout.bounds().containsKey(kr.ddingmate.hud.HudLayout.SKILL), "쓰고 있는 스킬이 HUD 에 안 그려짐");
                DdingMate.LOG.info("스킬 HUD 자리 {} / 전체 {}", kr.ddingmate.hud.HudLayout.bounds().get(kr.ddingmate.hud.HudLayout.SKILL),
                        kr.ddingmate.hud.HudLayout.bounds());
                kr.ddingmate.gui.DdingMateScreen s = new kr.ddingmate.gui.DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("장비");
            });
            ctx.waitTicks(5);
            ctx.runOnClient(c -> c.currentScreen.children().stream()
                    .filter(e -> e instanceof net.minecraft.client.gui.widget.ElementListWidget<?>)
                    .forEach(e -> ((net.minecraft.client.gui.widget.ElementListWidget<?>) e).setScrollY(10000)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skill-2-settings");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                kr.ddingmate.skill.SkillTimers.debugClear();
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> check(!kr.ddingmate.hud.HudLayout.bounds().containsKey(kr.ddingmate.hud.HudLayout.SKILL),
                    "쓰는 스킬이 없는데 스킬 HUD 가 보임"));
        }
    }

    private static void look(String name, net.minecraft.item.Item item, float cmd) {
        ItemStack s = kr.ddingmate.counter.IconCache.fromTable(name);
        check(s != null && s.isOf(item), name + " 의 아이템이 다름: " + s);
        var data = s.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        check(data != null && !data.floats().isEmpty() && data.floats().get(0) == cmd, name + " 의 모델 번호가 다름: " + data);
    }

    /** 실제 의뢰 창 칸과 같은 모양: "[ 감자 심기 일일 의뢰 ]" + 설명 */
    private static ItemStack quest(String title, String... lore) {
        return item("[ " + title + " ]", 1, lore);
    }

    private static DdingMateConfig.TodoItem todo(String preset) {
        return DdingMate.config().todo.items.stream().filter(t -> preset.equals(t.preset)).findFirst().orElseThrow();
    }

    private static int stored(String... lore) {
        ItemStack s = new ItemStack(Items.COBBLESTONE);
        s.set(DataComponentTypes.LORE, new LoreComponent(lines(lore)));
        return kr.ddingmate.counter.ItemKeys.storedAmount(s);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void remain(String line, long expect) {
        CraftTimers.Reading r = CraftTimers.read(lines(line));
        check(r != null && r.remainingSec() == expect, "'" + line + "' 을 " + expect + "초로 읽지 못함: " + r);
    }

    private static List<Text> lines(String... s) {
        return Arrays.stream(s).map(x -> (Text) Text.literal(x)).toList();
    }

    private static List<String> labels(List<CraftTimers.Group> g) {
        return g.stream().map(CraftTimers.Group::label).toList();
    }

    /** 실제 시설 창 레시피 칸과 같은 설명 */
    private static ItemStack recipeItem(String name, String time) {
        return item(name, 1, "설명 문장입니다.", "", "  제작 시간 : " + time, "", " (좌) 해당 레시피 선택", " (쉬프트+우) 레시피 제거");
    }

    private static ItemStack queueItem(String name, int count, String... lore) {
        return item(name, count, lore);
    }

    private static ItemStack item(String name, int count, String... lore) {
        ItemStack s = new ItemStack(Items.PAPER, Math.min(count, 64));
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        s.set(DataComponentTypes.LORE, new LoreComponent(lines(lore)));
        return s;
    }

    /** 바리스타 손님 창의 별 아이콘(이름 + 설명) */
    private static ItemStack star(String name, String... lore) {
        ItemStack s = new ItemStack(Items.NETHER_STAR);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        s.set(DataComponentTypes.LORE, new LoreComponent(lines(lore)));
        return s;
    }

    /** 실제 시설 창 모양: listView 면 0 뒤로·8 ↑·45~50 탭·53 ↓(모두 이름 없는 종이), 첫 화면이면 0 레시피 목록 + 9번 고정 칸 */
    private static GenericContainerScreen ruleScreen(MinecraftClient c, boolean listView, String recipeName) {
        SimpleInventory inv = new SimpleInventory(54);
        java.util.function.IntConsumer blank = i -> {
            ItemStack s = new ItemStack(Items.PAPER);
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
            inv.setStack(i, s);
        };
        blank.accept(0);
        // 목록 줄은 "레시피 고정", 첫 화면 위 줄 고정 칸은 "레시피 제거"(실제 서버)
        ItemStack recipe = item(recipeName, 1, "재료입니다.", "", "  제작 시간 : 10초", "", " (좌) 해당 레시피 선택",
                listView ? " (쉬프트+우) 레시피 고정" : " (쉬프트+우) 레시피 제거");
        if (!listView) {
            // 첫 화면 맨 아래 줄 = 빈 제작 대기열(이름 없는 칸): 목록 화면의 탭과 헷갈리면 안 된다
            for (int i = 45; i <= 49; i++) blank.accept(i);
            blank.accept(44); // ✓ 제작 단추
        }
        if (listView) {
            for (int i = 45; i <= 50; i++) blank.accept(i); // 탭: 이름 없는 종이. ↑(8)·↓(53)는 실제처럼 아이템 없는 빈 칸
            inv.setStack(17, recipe);
        } else {
            inv.setStack(9, recipe);
        }
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(91, c.player.getInventory(), inv);
        return new GenericContainerScreen(h, c.player.getInventory(), Text.literal("가공 시설"));
    }

    /** 그 시설·분류의 n번째(0부터) 레시피 이름 */
    private static String nthOf(String facility, String category, int n) {
        return kr.ddingmate.counter.RecipeBook.all().stream().filter(r -> r.facility.equals(facility) && category.equals(r.category))
                .skip(n).findFirst().orElseThrow().name;
    }

    private static String firstOf(String facility, String category) {
        return nthOf(facility, category, 0);
    }

    private static GenericContainerScreen facility(MinecraftClient c, ItemStack... items) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < items.length; i++) inv.setStack(10 + i, items[i]);
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(90, c.player.getInventory(), inv);
        return new GenericContainerScreen(h, c.player.getInventory(), Text.literal("가공 시설"));
    }

    private static int countDone() {
        return (int) AlertLog.entries().stream().filter(e -> e.title != null && e.title.startsWith("제작 완료")).count();
    }

    private static void scrollToBottom(MinecraftClient c) {
        c.currentScreen.children().stream()
                .filter(e -> e instanceof net.minecraft.client.gui.widget.ElementListWidget<?>)
                .map(e -> (net.minecraft.client.gui.widget.ElementListWidget<?>) e)
                .findFirst().ifPresent(l -> l.setScrollY(l.getMaxScrollY()));
    }

    private static void write(Path p, String s) {
        try {
            Files.writeString(p, s, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
