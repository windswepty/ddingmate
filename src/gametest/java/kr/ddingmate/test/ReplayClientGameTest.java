package kr.ddingmate.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.ddingmate.DdingMate;
import kr.ddingmate.notify.AlertLog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 실제 플레이 기록 재생 시험(build/testdata 가 있을 때만, 또는 DDINGMATE_TESTDATA=폴더). 저장소에는 넣지 않는 내 컴퓨터 자료:
 * <ul>
 * <li>chat-corpus*.tsv: 지금까지 모든 플레이 로그의 채팅 줄(서버 안내·사람 채팅, 순서 그대로) → 실제 채팅 이벤트로 흘려 보낸다</li>
 * <li>ddingmate-screens.txt: 예전에 기록한 서버 창(칸 이름·설명·모양 번호) → 상자 창으로 다시 만들어 연다</li>
 * <li>record/*.jsonl: 개발용 원본 기록(DevRecord: 글꼴·모양 정보까지) → 채팅·보스바·창을 똑같이 다시 만든다</li>
 * </ul>
 * 오류 없이 다 지나가야 하고, 무엇이 어떤 줄에서 알림을 냈는지 build/testdata/replay-report.txt 에 적는다(잘못 울린 알림 찾기).
 * 재생 동안 바뀐 설정·기록 파일은 끝나면 원래대로 돌려놓는다.
 */
public class ReplayClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Shots.full()) return; // 전체 시험(-Pfull)에서만: 오래 걸리거나 화면 비교용
        Path data = dataDir();
        if (data == null) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            Map<Path, byte[]> backup = new LinkedHashMap<>();
            StringBuilder report = new StringBuilder("띵메이트 실제 기록 재생 보고 " + java.time.LocalDateTime.now().withNano(0) + "\n\n");
            ctx.runOnClient(c -> {
                backup.putAll(backupConfig());
                freshInstall();
                // 실제 프로필이 배운 제작 시간표(실제 시간 39개 등): 시작 메시지만으로 완료 시각을 세서 회수 문구와 맞춰 볼 수 있게
                Path learned = data.resolve("ddingmate-crafts-learned.json");
                if (Files.exists(learned)) kr.ddingmate.craft.CraftTimers.debugLearnFrom(learned);
            });

            // 1) 채팅 전부
            ctx.runOnClient(c -> replayChat(c, data, report));
            // 2) 예전 창 기록(글만)
            List<Window> windows = readScreens(data.resolve("ddingmate-screens.txt"));
            report.append("\n== 창 기록 ").append(windows.size()).append("개 다시 열기\n");
            // 창마다 게임 틱 12번을 기다리지 않고 띵메이트 틱 처리를 그 자리에서 12번 돌린다(창 하나에 0.6초 → 몇 밀리초)
            ctx.runOnClient(c -> {
                for (Window w : windows) {
                    open(c, Text.literal(w.title), w.stacks);
                    settle(c);
                    report.append(describe(c, w.title)).append('\n');
                }
            });
            // 3) 원본 기록(DevRecord): 파일을 줄 단위로 읽어 500줄씩 한 번에(전부 메모리에 올리지 않음)
            long r0 = System.currentTimeMillis();
            int[] total = {0, 0};
            Map<String, Integer> seenShape = new java.util.HashMap<>();
            for (Path f : recordFiles(data.resolve("record"))) {
                try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    List<JsonObject> batch = new ArrayList<>();
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.isBlank() || skipSameShape(line, seenShape)) continue;
                        batch.add(JsonParser.parseString(line).getAsJsonObject());
                        if (batch.size() >= 500) {
                            List<JsonObject> run = batch;
                            ctx.runOnClient(c -> replayBatch(c, run, report, total));
                            batch = new ArrayList<>();
                        }
                    }
                    List<JsonObject> run = batch;
                    ctx.runOnClient(c -> replayBatch(c, run, report, total));
                } catch (java.io.IOException e) {
                    throw new AssertionError("원본 기록을 못 읽음: " + f, e);
                }
            }
            report.append("== 원본 기록 ").append(total[0]).append("줄(창 ").append(total[1]).append("개) 재생 (")
                    .append(System.currentTimeMillis() - r0).append("ms)\n");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                // 재생한 서버 HUD(보스바)·제목·채팅을 비운다: 남겨 두면 다음 시험 월드에서도 띵타이쿤 HUD 가 떠 있었다
                ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars().clear();
                c.inGameHud.clearTitle();
                c.inGameHud.getChatHud().clear(false);
                var replayObj = c.world.getScoreboard().getNullableObjective("ddm_replay");
                if (replayObj != null) c.world.getScoreboard().removeObjective(replayObj);
                report.append("\n== 회수할 때 아직 안 끝났다고 보던 제작 ").append(CRAFT_LATE.size()).append("건 (대조한 회수 ").append(CHECKED[0]).append("줄)\n");
                for (String f : CRAFT_LATE.subList(0, Math.min(60, CRAFT_LATE.size()))) report.append("  ").append(f).append('\n');
                for (String f : CRAFT_LATE) FAILURES.add("제작 예상이 회수보다 늦음 · " + f);
                report.append("\n== 서버 문구 대조 실패 ").append(FAILURES.size()).append("건\n");
                for (String f : FAILURES.subList(0, Math.min(40, FAILURES.size()))) report.append("  ").append(f).append('\n');
                restoreConfig(backup);
                try {
                    Files.writeString(data.resolve("replay-report.txt"), report, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                DdingMate.LOG.info("실제 기록 재생 보고: {}", data.resolve("replay-report.txt"));
                compareGolden(data, report.toString());
            });
            if (!FAILURES.isEmpty()) {
                throw new AssertionError("실제 기록 재생 대조 실패 " + FAILURES.size() + "건(처음 10건):\n  "
                        + String.join("\n  ", FAILURES.subList(0, Math.min(10, FAILURES.size()))));
            }
        }
    }

    /**
     * 승인한 재생 결과(replay-golden.txt)와 이번 결과를 줄 단위로 비교해 달라진 줄만 replay-diff.txt 에 적는다.
     * 알림·HUD·창 해석이 뜻하지 않게 바뀐 것을 바로 본다. 승인본이 없거나 -Dddingmate.approveReplay=true 면 이번 결과를 승인본으로
     */
    private static void compareGolden(Path data, String report) {
        List<String> now = new ArrayList<>();
        for (String l : report.split("\n")) {
            if (l.startsWith("띵메이트 실제 기록 재생 보고")) continue; // 머리 줄의 시각
            now.add(l.replaceAll("\\(\\d+ms\\)", "(…ms)"));
        }
        Path golden = data.resolve("replay-golden.txt");
        try {
            if (Boolean.getBoolean("ddingmate.approveReplay") || !Files.exists(golden)) {
                Files.write(golden, now, StandardCharsets.UTF_8);
                DdingMate.LOG.info("재생 결과 승인본을 저장했어요: {}", golden);
                return;
            }
            Map<String, Integer> count = new java.util.HashMap<>();
            for (String l : Files.readAllLines(golden, StandardCharsets.UTF_8)) count.merge(l, 1, Integer::sum);
            List<String> added = new ArrayList<>();
            for (String l : now) {
                Integer n = count.get(l);
                if (n == null || n == 0) added.add("+ " + l);
                else count.put(l, n - 1);
            }
            List<String> removed = new ArrayList<>();
            count.forEach((l, n) -> {
                for (int i = 0; i < n; i++) removed.add("- " + l);
            });
            List<String> diff = new ArrayList<>(added);
            diff.addAll(removed);
            Files.write(data.resolve("replay-diff.txt"), diff, StandardCharsets.UTF_8);
            DdingMate.LOG.info("재생 결과가 승인본과 다른 줄: 새로 생김 {} · 없어짐 {} ({})", added.size(), removed.size(), data.resolve("replay-diff.txt"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- 서버 문구를 정답으로 쓰는 대조

    /** 재생한 줄의 서버 문구와 띵메이트가 계산한 값이 어긋난 곳 */
    private static final List<String> FAILURES = new ArrayList<>();

    private static final java.util.regex.Pattern TIP = java.util.regex.Pattern.compile("^\\[TIP] (\\d+)일 오전 3시 요리 상점 가격이 변동됩니다");
    private static final java.util.regex.Pattern REBOOT = java.util.regex.Pattern.compile("^\\[서버 리붓] (\\d+)분 뒤 현재 채널이 재부팅됩니다");
    private static final java.util.regex.Pattern CLEAN = java.util.regex.Pattern.compile("^\\[청소] (\\d+)(분|초) 후");
    private static final java.util.regex.Pattern GOLEM = java.util.regex.Pattern.compile("^다음 등장 대기 : (?:(\\d+)분)? ?(?:(\\d+)초)?");

    /**
     * 서버가 정답을 함께 알려 주는 줄: 요리 시세 TIP 날짜, 리붓·청소 예고 시간, /광석골렘 남은 시간.
     * 줄을 흘려 보낸 직후 띵메이트 값이 서버 문구와 같은지 본다
     */
    private static void verify(String raw, String time, String file) {
        String t = kr.ddingmate.counter.ItemKeys.stripGlyphs(raw).replaceAll("\\s+", " ").trim();
        long now = kr.ddingmate.util.Clock.now();
        String where = file + " " + time + " | " + t;
        java.util.regex.Matcher m = TIP.matcher(t);
        if (m.find() && file.length() >= 10) {
            try {
                java.time.ZonedDateTime at = java.time.LocalDate.parse(file.substring(0, 10)).atTime(java.time.LocalTime.parse(time))
                        .atZone(kr.ddingmate.schedule.ScheduleEngine.KST);
                int day = kr.ddingmate.expert.Cooking.nextChange(at).getDayOfMonth();
                if (day != Integer.parseInt(m.group(1))) FAILURES.add("요리 시세 다음 변동 " + day + "일 ≠ 서버 · " + where);
            } catch (RuntimeException ignored) {
                // 날짜를 못 읽는 줄은 건너뜀
            }
            return;
        }
        m = REBOOT.matcher(t);
        if (m.find()) {
            long want = Long.parseLong(m.group(1)) * 60_000;
            long got = kr.ddingmate.game.ServerHud.rebootAt() - now;
            if (Math.abs(got - want) > 3_000) FAILURES.add("재부팅 예고 " + got / 1000 + "초 ≠ 서버 · " + where);
            return;
        }
        m = CLEAN.matcher(t);
        if (m.find()) {
            long want = Long.parseLong(m.group(1)) * (m.group(2).equals("분") ? 60_000 : 1_000);
            long got = kr.ddingmate.game.ServerHud.cleanAt() - now;
            if (Math.abs(got - want) > 3_000) FAILURES.add("바닥 청소 " + got / 1000 + "초 ≠ 서버 · " + where);
            return;
        }
        m = GOLEM.matcher(t);
        if (m.find() && (m.group(1) != null || m.group(2) != null)) {
            long want = ((m.group(1) == null ? 0 : Long.parseLong(m.group(1))) * 60 + (m.group(2) == null ? 0 : Long.parseLong(m.group(2)))) * 1000;
            long got = kr.ddingmate.expert.OreGolem.remaining(now);
            if (Math.abs(got - want) > 3_000) FAILURES.add("광석 골렘 쿨타임 " + got / 1000 + "초 ≠ 서버 · " + where);
        }
    }

    /**
     * 사이드바 기록(의뢰 트래커)을 실제 점수판으로 다시 만들어 띵메이트가 읽게 한다. 트래커에 의뢰 줄이 보이면
     * 할 일 HUD 의 의뢰 내용도 그 줄과 같아야 한다(2026-10 실제: 줄 끝 색 코드 때문에 0줄이던 결함)
     */
    private static void replaySidebar(MinecraftClient c, JsonObject r, net.minecraft.registry.RegistryWrapper.WrapperLookup regs) {
        net.minecraft.scoreboard.Scoreboard sb = c.world.getScoreboard();
        net.minecraft.scoreboard.ScoreboardObjective old = sb.getNullableObjective("ddm_replay");
        if (old != null) sb.removeObjective(old);
        net.minecraft.scoreboard.ScoreboardObjective obj = sb.addObjective("ddm_replay", net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                Text.Serialization.fromJsonTree(r.get("title"), regs), net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER, false, null);
        sb.setObjectiveSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR, obj);
        List<Map.Entry<Integer, String>> lines = new ArrayList<>();
        for (var e : r.getAsJsonArray("lines")) {
            JsonObject l = e.getAsJsonObject();
            String name = Text.Serialization.fromJsonTree(l.get("text"), regs).getString();
            int score = l.get("score").getAsInt();
            sb.getOrCreateScore(net.minecraft.scoreboard.ScoreHolder.fromName(name), obj).setScore(score);
            lines.add(Map.entry(score, name));
        }
        kr.ddingmate.todo.TodoList.scanSidebar(c);
        // 기대값: 점수 높은 순, 색 코드·그림 글자를 지운 줄에서 "일일 의뢰" 바로 아래의 "(n/m)" 줄
        lines.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));
        List<String> clean = lines.stream().map(e -> {
            String s = net.minecraft.util.Formatting.strip(e.getValue());
            return kr.ddingmate.counter.ItemKeys.stripGlyphs(s == null ? "" : s).trim();
        }).toList();
        List<String> want = new ArrayList<>();
        boolean in = false;
        for (String l : clean) {
            if (l.matches("^(일일|일간)\\s*의뢰.*")) in = true;
            else if (l.matches("^주간\\s*의뢰.*")) in = false;
            else if (in && l.matches(".*\\(\\s*[\\d,]+\\s*/\\s*[\\d,]+\\s*\\)$")) want.add(l);
            else if (in && !l.isBlank()) in = false;
        }
        // 트래커가 아닌 사이드바면 띵메이트는 마지막으로 본 의뢰를 그대로 둔다(트래커를 꺼도 보이게) → 대조하지 않는다
        if (clean.stream().noneMatch(l -> l.matches("^(일일|일간|주간)\\s*의뢰.*"))) return;
        List<String> got = kr.ddingmate.todo.TodoList.debugQuests().get(0);
        if (!got.equals(want)) FAILURES.add("의뢰 트래커 일일 의뢰 " + got + " ≠ 사이드바 " + want);
    }

    // ---------------------------------------------------------------- 채팅

    private static void replayChat(MinecraftClient c, Path data, StringBuilder report) {
        List<Path> files;
        try (Stream<Path> s = Files.list(data)) {
            files = s.filter(p -> p.getFileName().toString().startsWith("chat-corpus") && p.toString().endsWith(".tsv")).sorted().toList();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Map<String, Integer> byTitle = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        Map<String, Integer> changes = new LinkedHashMap<>();
        Map<String, List<String>> changeEx = new LinkedHashMap<>();
        Map<String, String> state = snapshot();
        int lines = 0;
        long t0 = System.currentTimeMillis();
        AlertLog.Entry top = AlertLog.entries().isEmpty() ? null : AlertLog.entries().get(0);
        // 가상 시계: 줄마다 기록 시각(파일 날짜 + 줄 시각)으로 맞추고, 그 사이 1초 처리(제작 완료·초기화·쿨타임)를 시간 순서대로 돌린다
        long[] vnow = {0};
        kr.ddingmate.util.Clock.debugSet(() -> vnow[0] > 0 ? vnow[0] : System.currentTimeMillis());
        try {
        for (Path f : files) {
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length < 3) continue;
                    String msg = parts[2].replace("\\\\", "\\");
                    lines++;
                    long at = lineTime(parts[1], parts[0]);
                    if (at > 0) {
                        advance(c, vnow, at);
                        // 시간이 흐르며 생긴 알림(제작 완료·초기화 등)은 이 줄이 아니라 '시간 흐름'으로 센다
                        top = collect(top, byTitle, examples, "(시간 흐름) ", parts[1] + " " + parts[0] + " 직전");
                    }
                    try {
                        checkCollect(msg, parts[0], parts[1]);
                        ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(Text.literal(msg), false);
                        verify(msg, parts[0], parts[1]);
                    } catch (AssertionError e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new AssertionError("채팅 재생 중 오류: " + parts[1] + " " + parts[0] + " " + msg, e);
                    }
                    // 이 줄로 바뀐 상태(할 일 진행·골렘·오션오더·제작 줄·닉네임·핀·접속 시간): 잘못 반응한 줄 찾기
                    Map<String, String> after = snapshot();
                    for (Map.Entry<String, String> e : after.entrySet()) {
                        String before = state.get(e.getKey());
                        if (java.util.Objects.equals(before, e.getValue())) continue;
                        String key = e.getKey();
                        changes.merge(key, 1, Integer::sum);
                        List<String> ex = changeEx.computeIfAbsent(key, k -> new ArrayList<>());
                        if (ex.size() < 4) ex.add(before + " → " + e.getValue() + " · " + parts[1] + " " + parts[0] + " | "
                                + kr.ddingmate.counter.ItemKeys.stripGlyphs(msg));
                    }
                    state = after;
                    // 이 줄 하나로 생긴 알림들
                    top = collect(top, byTitle, examples, "", parts[1] + " " + parts[0] + " | " + kr.ddingmate.counter.ItemKeys.stripGlyphs(msg));
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        } finally {
            kr.ddingmate.util.Clock.debugSet(null);
        }
        report.append("== 채팅 ").append(lines).append("줄 재생 (").append(System.currentTimeMillis() - t0).append("ms)\n");
        report.append("알림이 생긴 줄(종류 · 제목 = 횟수, 예시):\n");
        byTitle.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            report.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            for (String ex : examples.get(e.getKey())) report.append("      ").append(ex.length() > 220 ? ex.substring(0, 220) : ex).append('\n');
        });
        report.append("상태가 바뀐 줄(항목 = 횟수, 예시):\n");
        changes.forEach((k, v) -> {
            report.append("  ").append(k).append(" = ").append(v).append('\n');
            for (String ex : changeEx.get(k)) report.append("      ").append(ex.length() > 240 ? ex.substring(0, 240) : ex).append('\n');
        });
        report.append("끝난 뒤 상태: ").append(snapshot()).append('\n');
        if (lines == 0) throw new AssertionError("재생할 채팅이 없음: " + data);
    }

    /** 맨 앞 알림이 top 이후로 새로 생겼으면 종류·제목별로 센다. 새 맨 앞을 돌려준다 */
    private static AlertLog.Entry collect(AlertLog.Entry top, Map<String, Integer> byTitle, Map<String, List<String>> examples,
                                          String prefix, String where) {
        List<AlertLog.Entry> es = AlertLog.entries();
        AlertLog.Entry now = es.isEmpty() ? null : es.get(0);
        if (now == top) return top;
        for (AlertLog.Entry e : es) {
            if (e == top) break;
            String key = prefix + e.kind + " · " + e.title;
            byTitle.merge(key, 1, Integer::sum);
            List<String> ex = examples.computeIfAbsent(key, k -> new ArrayList<>());
            if (ex.size() < 4) ex.add(where);
        }
        return now;
    }

    /** 줄의 기록 시각: 파일 이름 "2026-10-18-1.log.gz" 의 날짜 + 줄 시각 "16:57:49". 모르면 -1(latest.log 등) */
    private static long lineTime(String file, String time) {
        if (file.length() < 10 || time.length() < 8 || !Character.isDigit(file.charAt(0))) return -1;
        try {
            return java.time.LocalDate.parse(file.substring(0, 10)).atTime(java.time.LocalTime.parse(time.substring(0, 8)))
                    .atZone(kr.ddingmate.schedule.ScheduleEngine.KST).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 가상 시계를 at 까지 흘린다: 처음 5초는 1초마다 1초 처리, 그 뒤는 at 으로 건너뛰어 한 번 더(긴 공백·다음 날 접속도
     * 초기화·완료 판정이 한 번은 돈다). 시각이 거꾸로 가면(파일 경계) 그 시각으로 맞추기만 한다
     */
    private static void advance(MinecraftClient c, long[] vnow, long at) {
        long cur = vnow[0];
        if (cur == 0 || at <= cur) {
            vnow[0] = at;
            return;
        }
        long next = cur + 1000;
        for (int i = 0; i < 5 && next <= at; i++, next += 1000) {
            vnow[0] = next;
            tickAt(c);
        }
        if (vnow[0] < at) {
            vnow[0] = at;
            tickAt(c);
        }
    }

    private static void tickAt(MinecraftClient c) {
        kr.ddingmate.craft.CraftTimers.tick();
        DdingMate.timeTick();
    }

    private static final java.util.regex.Pattern COLLECT = java.util.regex.Pattern.compile("^(.+?) 아이템 ([\\d,]+)개를 제작대에서 회수했습니다");

    /**
     * 회수 문구는 서버에서 제작이 끝났다는 뜻이다: 시작 메시지로 센 그 제작(시각을 아는 것)이 아직 30초 넘게 남았다고
     * 보고 있으면 띵메이트 예상이 실제보다 길다(완료 알림이 회수보다 늦게 울림)
     */
    private static void checkCollect(String raw, String time, String file) {
        String t = kr.ddingmate.counter.ItemKeys.stripGlyphs(raw).replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher m = COLLECT.matcher(t);
        if (!m.find()) return;
        String key = kr.ddingmate.counter.ItemKeys.normalize(m.group(1));
        int count = Integer.parseInt(m.group(2).replace(",", ""));
        long now = kr.ddingmate.util.Clock.now();
        long soonest = Long.MAX_VALUE;
        // 실제 시간(시작 버튼·대기열)을 아는 제작만: 레시피 시간 × 스킬 공식은 재생 설정의 스킬 레벨(0)로 길게 나온다.
        // 배운 시간표는 10-09 부터의 프로필 값이라 그 전 기록에는 맞지 않는다(8월엔 스킬이 달라 키론 오르골이 32초가 아니라 27초)
        if (file.compareTo(LEARNED_FROM) < 0) return;
        if (!kr.ddingmate.craft.CraftTimers.debugObserved(m.group(1), count)) return;
        CHECKED[0]++;
        for (kr.ddingmate.craft.CraftTimers.Job j : kr.ddingmate.craft.CraftTimers.jobs()) {
            if (j.separate || j.count != count || j.endsAt <= 0 || !(j.synced || j.derived)) continue;
            if (!kr.ddingmate.counter.ItemKeys.normalize(j.name).equals(key)) continue;
            soonest = Math.min(soonest, j.endsAt);
        }
        if (soonest != Long.MAX_VALUE && soonest - now > 30_000) {
            CRAFT_LATE.add(file + " " + time + " | " + t + " · 띵메이트는 " + (soonest - now) / 1000 + "초 남았다고 봄");
        }
    }

    /** 회수할 때 띵메이트가 아직 안 끝났다고 보던 제작 */
    private static final List<String> CRAFT_LATE = new ArrayList<>();
    /** 배운 제작 시간표(ddingmate-crafts-learned.json)가 맞는 기록의 첫 날(테스트 프로필 첫 로그) */
    private static final String LEARNED_FROM = "2026-10-09";
    /** 회수 대조를 한 줄 수 */
    private static final int[] CHECKED = {0};

    /** 줄마다 비교할 상태 */
    private static Map<String, String> snapshot() {
        Map<String, String> m = new java.util.TreeMap<>();
        java.time.ZonedDateTime now = kr.ddingmate.util.Clock.zoned(kr.ddingmate.schedule.ScheduleEngine.KST);
        for (var t : DdingMate.config().todo.items) m.put("할 일 " + t.name, String.valueOf(kr.ddingmate.todo.TodoList.progress(t, now)));
        long ms = kr.ddingmate.util.Clock.now();
        m.put("광석 골렘 쿨타임", kr.ddingmate.expert.OreGolem.remaining(ms) > 0 ? "켜짐" : "꺼짐");
        m.put("오션오더", kr.ddingmate.expert.OceanOrder.active(ms) + " 오늘 " + kr.ddingmate.expert.OceanOrder.todayCount());
        m.put("제작 줄", String.valueOf(kr.ddingmate.craft.CraftTimers.groups().size()));
        m.put("한글 닉네임", String.valueOf(kr.ddingmate.game.Nickname.get()));
        m.put("핀", DdingMate.config().counter.pins.stream().map(p -> p.recipe).toList().toString());
        m.put("접속 보상 받은 것", String.valueOf(kr.ddingmate.playtime.PlaytimeTracker.unclaimedRewards()));
        m.put("재부팅 예고", String.valueOf(kr.ddingmate.game.ServerHud.rebootAt() > 0));
        return m;
    }

    /** 처음 설치한 상태로: 띵메이트 설정·기록 파일을 지우고 다시 읽는다(백업은 끝나면 되돌림) */
    private static void freshInstall() {
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ddingmate") && Files.isRegularFile(p)).toList()) Files.delete(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DdingMate.reloadConfig();
        // 고정 핀 시험: 핀 1 = 강화 횃불(고정 안 함) — 제작 시작 줄에서 빠져야 한다
        var torch = kr.ddingmate.counter.RecipeBook.recipeFor("강화 횃불");
        if (torch != null) DdingMate.config().counter.pins.get(0).recipe = torch.id;
        kr.ddingmate.craft.CraftTimers.debugClear();
        kr.ddingmate.expert.OreGolem.debugReset();
        kr.ddingmate.expert.OceanOrder.debugReset();
        kr.ddingmate.expert.PetFish.debugReset();
        kr.ddingmate.game.Nickname.debugReset();
        AlertLog.clear();
    }

    // ---------------------------------------------------------------- 예전 창 기록(ddingmate-screens.txt)

    private record Window(String title, Map<Integer, ItemStack> stacks) {}

    private static final java.util.regex.Pattern SLOT = java.util.regex.Pattern.compile(
            "^\\s*\\[(\\d+)] (.*?) ×(\\d+) <([a-z0-9_]+)(?: (\\d+))?(?: [^>]*)?>(.*)$");

    private static List<Window> readScreens(Path p) {
        List<Window> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        try {
            String title = null;
            Map<Integer, ItemStack> stacks = null;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.startsWith("== 창: ")) {
                    if (title != null) out.add(new Window(title, stacks));
                    String t = line.substring(5);
                    int at = t.lastIndexOf(" (");
                    title = at > 0 ? t.substring(0, at) : t;
                    stacks = new LinkedHashMap<>();
                    continue;
                }
                java.util.regex.Matcher m = SLOT.matcher(line);
                if (!m.find() || stacks == null) continue;
                int slot = Integer.parseInt(m.group(1));
                if (slot >= 54) continue;
                Item item = Registries.ITEM.get(Identifier.ofVanilla(m.group(4)));
                ItemStack s = new ItemStack(item, Math.max(1, Math.min(99, Integer.parseInt(m.group(3)))));
                if (!m.group(2).isEmpty()) s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(m.group(2)).setStyle(Style.EMPTY.withItalic(false)));
                if (m.group(5) != null) s.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                        new CustomModelDataComponent(List.of(Float.parseFloat(m.group(5))), List.of(), List.of(), List.of()));
                List<Text> lore = new ArrayList<>();
                for (String l : m.group(6).split(" \\| ")) if (!l.isBlank()) lore.add(Text.literal(l.trim()));
                if (!lore.isEmpty()) s.set(DataComponentTypes.LORE, new LoreComponent(lore));
                stacks.put(slot, s);
            }
            if (title != null) out.add(new Window(title, stacks));
        } catch (Exception e) {
            throw new AssertionError("창 기록을 못 읽음", e);
        }
        return out;
    }

    private static void open(MinecraftClient c, Text title, Map<Integer, ItemStack> stacks) {
        SimpleInventory inv = new SimpleInventory(54);
        stacks.forEach((k, v) -> {
            if (k < 54) inv.setStack(k, v);
        });
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(1, c.player.getInventory(), inv);
        c.setScreen(new GenericContainerScreen(h, c.player.getInventory(), title));
    }

    /** 이 창에서 띵메이트가 알아본 것 한 줄 */
    private static String describe(MinecraftClient c, String title) {
        StringBuilder sb = new StringBuilder("  ");
        sb.append(kr.ddingmate.counter.ItemKeys.stripGlyphs(title).trim()).append(" → ");
        List<String> got = new ArrayList<>();
        if (!kr.ddingmate.craft.FacilityPanel.entries().isEmpty())
            got.add("시설 판 " + kr.ddingmate.craft.FacilityPanel.entries().size() + "줄(" + kr.ddingmate.craft.FacilityPanel.entries().get(0).recipe().facility + ")");
        if (kr.ddingmate.counter.BagTracker.storageOpen()) got.add("아이템 저장소");
        if (kr.ddingmate.expert.PetFish.current() != null) got.add("반려어 " + kr.ddingmate.expert.PetFish.current().name);
        if (kr.ddingmate.expert.OceanOrder.active(System.currentTimeMillis())) got.add("오션오더 진행");
        int jobs = kr.ddingmate.craft.CraftTimers.groups().size();
        if (jobs > 0) got.add("제작 줄 " + jobs);
        sb.append(got.isEmpty() ? "(알아본 것 없음)" : String.join(", ", got));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 원본 기록(DevRecord)

    /** 원본 기록에서 같은 모양(숫자만 다름) 줄을 몇 개까지 다시 보낼지 */
    private static final int SAME_SHAPE_MAX = 30;

    private static List<Path> recordFiles(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList();
        } catch (Exception e) {
            throw new AssertionError("원본 기록 폴더를 못 읽음", e);
        }
    }

    /**
     * 같은 모양(숫자만 다름) 줄은 몇 개만: 잠수 채널 "299초 뒤 잠수 포인트…" 액션바가 하루 수만 줄이다.
     * 창(screen)·사이드바는 모두 다시 연다
     */
    private static boolean skipSameShape(String line, Map<String, Integer> seen) {
        if (line.contains("\"t\":\"screen\"") || line.contains("\"t\":\"sidebar\"") || line.contains("\"t\":\"inv\"")) return false;
        String shape = line.replaceAll("\\d", "#").replaceAll("\"at\":#+", "");
        return seen.merge(shape, 1, Integer::sum) > SAME_SHAPE_MAX;
    }

    /** 기록 여러 줄을 게임 스레드에서 한 번에: 창이면 띵메이트 틱 12번으로 읽게 하고 알아본 것을 보고서에 */
    private static void replayBatch(MinecraftClient c, List<JsonObject> batch, StringBuilder report, int[] total) {
        for (JsonObject r : batch) {
            String kind = r.get("t").getAsString();
            replayRecord(c, r, kind, report);
            total[0]++;
            if (kind.equals("screen")) {
                total[1]++;
                settle(c);
                report.append(describe(c, r.get("plainTitle").getAsString())).append('\n');
            }
        }
    }

    /** 창을 연 뒤 게임 틱 12번 동안 일어날 띵메이트 처리(창 읽기 0.1초·0.5초 주기, 가방 판단 6틱)를 바로 돌린다 */
    private static void settle(MinecraftClient c) {
        for (int i = 0; i < 12; i++) DdingMate.debugTick(c);
    }

    private static void replayRecord(MinecraftClient c, JsonObject r, String kind, StringBuilder report) {
        var regs = c.world.getRegistryManager();
        try {
            switch (kind) {
                case "chat", "overlay" -> ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(
                        Text.Serialization.fromJsonTree(r.get("text"), regs), kind.equals("overlay"));
                case "title" -> c.inGameHud.setTitle(Text.Serialization.fromJsonTree(r.get("text"), regs));
                case "subtitle" -> c.inGameHud.setSubtitle(Text.Serialization.fromJsonTree(r.get("text"), regs));
                case "bossbar" -> {
                    java.util.UUID id = java.util.UUID.fromString(r.get("id").getAsString());
                    var bars = ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars();
                    bars.put(id, new net.minecraft.client.gui.hud.ClientBossBar(id, Text.Serialization.fromJsonTree(r.get("text"), regs),
                            r.has("percent") ? r.get("percent").getAsFloat() : 1f, net.minecraft.entity.boss.BossBar.Color.WHITE,
                            net.minecraft.entity.boss.BossBar.Style.PROGRESS, false, false, false));
                }
                case "bossbars" -> {
                    java.util.Set<java.util.UUID> keep = new java.util.HashSet<>();
                    for (var e : r.getAsJsonArray("ids")) keep.add(java.util.UUID.fromString(e.getAsString()));
                    ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars().keySet().retainAll(keep);
                }
                case "screen" -> {
                    Map<Integer, ItemStack> stacks = new LinkedHashMap<>();
                    for (var e : r.getAsJsonArray("slots")) {
                        JsonObject s = e.getAsJsonObject();
                        ItemStack.CODEC.parse(regs.getOps(com.mojang.serialization.JsonOps.INSTANCE), s.get("item")).result()
                                .ifPresent(st -> stacks.put(s.get("slot").getAsInt(), st));
                    }
                    open(c, Text.Serialization.fromJsonTree(r.get("title"), regs), stacks);
                }
                case "sidebar" -> replaySidebar(c, r, regs);
                case "inv" -> {
                    // 내 인벤토리 바뀐 칸(2026-10-30 부터 기록): 실제 보유량으로 재료 HUD·시설 판이 계산되게
                    for (var e : r.getAsJsonArray("slots")) {
                        JsonObject s = e.getAsJsonObject();
                        int slot = s.get("slot").getAsInt();
                        JsonObject item = s.getAsJsonObject("item");
                        ItemStack st = item.size() == 0 ? ItemStack.EMPTY : ItemStack.CODEC.parse(
                                regs.getOps(com.mojang.serialization.JsonOps.INSTANCE), item).result().orElse(ItemStack.EMPTY);
                        if (slot >= 0 && slot < c.player.getInventory().size()) c.player.getInventory().setStack(slot, st);
                    }
                }
                default -> { /* 그 밖: 보고만 */ }
            }
        } catch (Throwable e) {
            throw new AssertionError("원본 기록 재생 중 오류: " + r, e);
        }
    }

    // ---------------------------------------------------------------- 설정 되돌리기

    private static Map<Path, byte[]> backupConfig() {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ddingmate") && Files.isRegularFile(p)).toList()) {
                out.put(p, Files.readAllBytes(p));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return out;
    }

    private static void restoreConfig(Map<Path, byte[]> backup) {
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ddingmate") && Files.isRegularFile(p)).toList()) {
                if (!backup.containsKey(p)) Files.delete(p);
            }
            for (Map.Entry<Path, byte[]> e : backup.entrySet()) Files.write(e.getKey(), e.getValue());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DdingMate.reloadConfig();
    }

    /** build/testdata(시험은 build/run/clientGameTest 에서 돈다) 또는 DDINGMATE_TESTDATA */
    private static Path dataDir() {
        String env = System.getenv("DDINGMATE_TESTDATA");
        Path p = env != null ? Path.of(env) : Path.of("").toAbsolutePath().resolve("../../testdata").normalize();
        return Files.isDirectory(p) ? p : null;
    }
}
