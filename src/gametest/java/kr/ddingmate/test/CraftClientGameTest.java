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
                check(t0.startsWith("약 9분 1") || t0.startsWith("약 9분 2"), "넣어 둔 시간 × 개수로 세지 않음: " + t0);
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
                check(t.startsWith("약 9분 1") || t.startsWith("약 9분 2"), "4번 합친 시간(약 560초)이 아님: " + t);
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
            ctx.runOnClient(c -> {
                long left = Math.max(0, (before[0] - now()) / 1000);
                c.setScreen(facility(c,
                        queueItem("강화 횃불", 1, "  제작 시간 : " + left + "초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초"),
                        queueItem("강화 횃불", 1, "  제작 시간 : 7초")));
            });
            ctx.waitTicks(12);
            ctx.runOnClient(c -> {
                List<CraftTimers.Job> jobs = CraftTimers.jobs();
                for (int i = 0; i < 3; i++) {
                    check(Math.abs(jobs.get(i).endsAt - before[i]) <= 1_500,
                            (i + 1) + "번째 완료 시각이 다시 읽을 때 밀림: " + (jobs.get(i).endsAt - before[i]) + "ms");
                }
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
                // 대기열 칸 순서가 섞여 있어도(기다리는 칸이 앞) 남은 시간이 짧은 칸이 맨 앞 제작
                c.setScreen(facility(c,
                        queueItem("어빌리티 스톤", 50, "신비로운 힘이 깃들어 있는 스톤입니다.", "  제작 시간 : 1시간 30분 0초"),
                        queueItem("어빌리티 스톤", 28, "  제작 시간 : 50분 24초"),
                        queueItem("어빌리티 스톤", 50, "  제작 시간 : 1시간 29분 33초")));
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
                CraftTimers.debugVoyage(item("[ 유실라 보상 수령 ]", 1), lines("  - 항해 소요 시간 : 4시간 5분 0초", "클릭 시 항해 보상을 수령합니다."));
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
                ((DdingMateScreen) c.currentScreen).openTab(5);
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
                // 20) 정밀 검사: 레시피 373개의 결과물·재료 이름 전부에 그림이 있다(서버 표 또는 바닐라)
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
                look("활기찬 가죽 지갑", Items.PAPER, 2059103f);
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
