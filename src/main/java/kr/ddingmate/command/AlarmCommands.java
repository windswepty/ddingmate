package kr.ddingmate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.notify.AlertLog;
import kr.ddingmate.playtime.PlaytimeTracker;
import kr.ddingmate.todo.TodoList;
import kr.ddingmate.gui.AlarmConfigScreen;
import net.minecraft.client.MinecraftClient;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.EventRule;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** 내 클라이언트에서만 처리되는 명령어. 서버로 전송되지 않는다. */
public final class AlarmCommands {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M/d(E) HH:mm");

    private AlarmCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(root("띵메이트"));
        d.register(root("알리미")); // 짧게 쓰는 이름
        d.register(root("ddmate"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> root(String name) {
        SuggestionProvider<FabricClientCommandSource> ids = (c, b) ->
                CommandSource.suggestMatching(allIds(), b);
        SuggestionProvider<FabricClientCommandSource> words = (c, b) ->
                CommandSource.suggestMatching(DdingMate.config().keywords.words, b);

        return literal(name)
                .executes(AlarmCommands::help)
                .then(literal("설정").executes(c -> {
                    // 채팅창이 닫힌 다음 프레임에 연다.
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.send(() -> client.setScreen(new AlarmConfigScreen(null)));
                    return 1;
                }))
                .then(literal("목록").executes(AlarmCommands::list))
                .then(literal("hud").executes(c -> toggle(c, "HUD", (cfg, v) -> cfg.hudEnabled = v, cfg -> cfg.hudEnabled)))
                .then(literal("소리").executes(c -> toggle(c, "소리", (cfg, v) -> cfg.soundEnabled = v, cfg -> cfg.soundEnabled)))
                .then(literal("토스트").executes(c -> toggle(c, "토스트", (cfg, v) -> cfg.toastEnabled = v, cfg -> cfg.toastEnabled)))
                .then(literal("채팅").executes(c -> toggle(c, "채팅 알림", (cfg, v) -> cfg.chatEnabled = v, cfg -> cfg.chatEnabled)))
                .then(literal("켜기").then(argument("id", StringArgumentType.word()).suggests(ids)
                        .executes(c -> setEvent(c, true))))
                .then(literal("끄기").then(argument("id", StringArgumentType.word()).suggests(ids)
                        .executes(c -> setEvent(c, false))))
                .then(literal("시점").then(argument("id", StringArgumentType.word()).suggests(ids)
                        .then(argument("분목록", StringArgumentType.greedyString()).executes(AlarmCommands::setLeads))))
                .then(literal("채널")
                        .then(channel("마을", (cfg, v) -> cfg.villageChannel = v, 35))
                        .then(channel("노크틸라", (cfg, v) -> cfg.huntChannel = v, 24))
                        .then(channel("야생", (cfg, v) -> cfg.wildChannel = v, 16))
                        .then(channel("아일랜드", (cfg, v) -> cfg.islandChannel = v, 15))
                        .then(channel("스폰", (cfg, v) -> cfg.spawnChannel = v, 4)))
                .then(literal("위치")
                        .then(corner("좌상", AlarmConfig.Corner.TOP_LEFT))
                        .then(corner("우상", AlarmConfig.Corner.TOP_RIGHT))
                        .then(corner("좌하", AlarmConfig.Corner.BOTTOM_LEFT))
                        .then(corner("우하", AlarmConfig.Corner.BOTTOM_RIGHT)))
                .then(literal("줄수").then(argument("n", IntegerArgumentType.integer(1, 10)).executes(c -> {
                    DdingMate.config().hudLines = IntegerArgumentType.getInteger(c, "n");
                    return saved(c, "HUD 에 일정 " + DdingMate.config().hudLines + "개를 표시해요.");
                })))
                .then(literal("키워드")
                        .then(literal("목록").executes(AlarmCommands::keywordList))
                        .then(literal("추가").then(argument("단어", StringArgumentType.greedyString()).executes(c -> {
                            String w = StringArgumentType.getString(c, "단어").trim();
                            List<String> list = DdingMate.config().keywords.words;
                            if (w.isEmpty() || list.contains(w)) return feedback(c, "이미 있거나 빈 단어예요.");
                            list.add(w);
                            return saved(c, "키워드 '" + w + "' 를 추가했어요.");
                        })))
                        .then(literal("삭제").then(argument("단어", StringArgumentType.greedyString()).suggests(words).executes(c -> {
                            String w = StringArgumentType.getString(c, "단어").trim();
                            boolean removed = DdingMate.config().keywords.words.remove(w);
                            return removed ? saved(c, "키워드 '" + w + "' 를 지웠어요.") : feedback(c, "그런 키워드가 없어요.");
                        })))
                        .then(literal("켜기").executes(c -> { DdingMate.config().keywords.enabled = true; return saved(c, "키워드 알림을 켰어요."); }))
                        .then(literal("끄기").executes(c -> { DdingMate.config().keywords.enabled = false; return saved(c, "키워드 알림을 껐어요."); })))
                .then(literal("가방").executes(AlarmCommands::bags))
                .then(literal("할일").executes(AlarmCommands::todo))
                .then(literal("기록").executes(AlarmCommands::log))
                .then(literal("접속").executes(AlarmCommands::playtime))
                .then(literal("테스트").executes(c -> { Notifier.test(); return 1; }))
                .then(literal("다시읽기").executes(c -> { DdingMate.reloadConfig(); return feedback(c, "설정 파일을 다시 읽었어요."); }));
    }

    // ---------------------------------------------------------------- 하위 명령

    private static int help(CommandContext<FabricClientCommandSource> c) {
        String[] lines = {
                "/띵메이트 설정 — 설정 화면 열기 (Mod Menu 의 설정 버튼과 같아요)",
                "/띵메이트 목록 — 모든 일정과 다음 시각",
                "/띵메이트 hud | 소리 | 토스트 | 채팅 — 켜고 끄기",
                "/띵메이트 켜기|끄기 <id> — 일정별 알림",
                "/띵메이트 시점 <id> <분,분> — 몇 분 전에 알릴지 (예: 10,3 / 0은 '지금')",
                "/띵메이트 채널 <마을|노크틸라|야생|아일랜드|스폰> <번호> — 내 채널 재시작 알림(0=끔)",
                "/띵메이트 위치 <좌상|우상|좌하|우하>, /띵메이트 줄수 <1~10>",
                "/띵메이트 키워드 목록|추가|삭제|켜기|끄기",
                "/띵메이트 가방 — 재료 카운터에 기록된 가방 목록",
                "/띵메이트 할일 · 기록 · 접속 — 오늘 할 일, 최근 알림, 오늘 접속 시간",
                "/띵메이트 테스트, /띵메이트 다시읽기",
        };
        feedback(c, "띵메이트 명령어 (/알리미 로 줄여 써도 돼요)");
        for (String l : lines) c.getSource().sendFeedback(Text.literal("  " + l).formatted(Formatting.GRAY));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> c) {
        AlarmConfig cfg = DdingMate.config();
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        feedback(c, "일정 목록 (한국 시간)");
        for (EventRule r : DdingMate.rules()) {
            ZonedDateTime n = ScheduleEngine.next(r, now);
            String when = n == null ? "-" : WHEN.format(n) + " (" + ScheduleEngine.formatRemaining(Duration.between(now, n)) + " 후)";
            boolean on = cfg.isEnabled(r);
            c.getSource().sendFeedback(Text.literal((on ? "● " : "○ ") + r.label() + " [" + r.id() + "] ")
                    .formatted(on ? Formatting.WHITE : Formatting.DARK_GRAY)
                    .append(Text.literal(when + " · 알림 " + cfg.leadsOf(r) + "분 전").formatted(Formatting.GRAY)));
        }
        return 1;
    }

    private static int bags(CommandContext<FabricClientCommandSource> c) {
        Map<Integer, Long> bags = BagTracker.recordedBags();
        if (bags.isEmpty()) return feedback(c, "기록된 가방이 없어요. 단축어(예: ㄱ1)로 가방을 한 번씩 열면 기록돼요.");
        feedback(c, "기록된 가방 " + bags.size() + "개 (재료 카운터에 합산)");
        bags.forEach((n, at) -> c.getSource().sendFeedback(Text.literal("  " + n + "번 가방 · "
                + WHEN.format(Instant.ofEpochMilli(at).atZone(ScheduleEngine.KST)) + " 확인").formatted(Formatting.GRAY)));
        return 1;
    }

    private static int todo(CommandContext<FabricClientCommandSource> c) {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        List<AlarmConfig.TodoItem> items = DdingMate.config().todo.items;
        long done = items.stream().filter(t -> TodoList.isDone(t, now)).count();
        feedback(c, "오늘 할 일 " + done + "/" + items.size() + " (체크는 설정 → 할 일 탭에서)");
        for (AlarmConfig.TodoItem t : items) {
            boolean ok = TodoList.isDone(t, now);
            c.getSource().sendFeedback(Text.literal((ok ? "  ✔ " : "  ☐ ") + t.name + " · " + TodoList.resetName(t.reset))
                    .formatted(ok ? Formatting.DARK_GRAY : Formatting.WHITE));
        }
        return 1;
    }

    private static int log(CommandContext<FabricClientCommandSource> c) {
        List<AlertLog.Entry> entries = AlertLog.entries();
        if (entries.isEmpty()) return feedback(c, "아직 알림 기록이 없어요.");
        feedback(c, "최근 알림 (전체는 설정 → 기록 탭)");
        for (AlertLog.Entry e : entries.subList(0, Math.min(10, entries.size()))) {
            c.getSource().sendFeedback(Text.literal("  " + WHEN.format(Instant.ofEpochMilli(e.at).atZone(ScheduleEngine.KST))
                    + " [" + e.kind + "] ").formatted(Formatting.GRAY).append(Text.literal(e.title).formatted(Formatting.WHITE)));
        }
        AlertLog.markRead();
        return 1;
    }

    private static int playtime(CommandContext<FabricClientCommandSource> c) {
        long s = PlaytimeTracker.seconds();
        if (s < 0) return feedback(c, "월드에 들어가 있을 때 확인할 수 있어요.");
        int next = PlaytimeTracker.nextRewardHour(s);
        return feedback(c, "오늘 접속 " + (s / 3600) + "시간 " + (s % 3600 / 60) + "분 (추정) · "
                + (next == 0 ? "보상 구간을 다 채웠어요" : "다음 보상 " + next + "시간: " + PlaytimeTracker.REWARDS.get(next))
                + " · 서버 기준은 /접속시간");
    }

    private static int setEvent(CommandContext<FabricClientCommandSource> c, boolean on) {
        String id = StringArgumentType.getString(c, "id");
        AlarmConfig.EventSetting s = DdingMate.config().events.get(id);
        if (s == null) return feedback(c, "알 수 없는 id 예요. /띵메이트 목록 에서 확인하세요.");
        s.enabled = on;
        return saved(c, id + " 알림을 " + (on ? "켰어요." : "껐어요."));
    }

    private static int setLeads(CommandContext<FabricClientCommandSource> c) {
        String id = StringArgumentType.getString(c, "id");
        AlarmConfig.EventSetting s = DdingMate.config().events.get(id);
        if (s == null) return feedback(c, "알 수 없는 id 예요.");
        List<Integer> leads = new ArrayList<>();
        for (String part : StringArgumentType.getString(c, "분목록").split("[,\\s]+")) {
            if (part.isBlank()) continue;
            try {
                int v = Integer.parseInt(part.trim());
                if (v < 0 || v > 1440) return feedback(c, "0~1440 사이 숫자만 쓸 수 있어요.");
                if (!leads.contains(v)) leads.add(v);
            } catch (NumberFormatException e) {
                return feedback(c, "숫자를 쉼표로 구분해 주세요. 예: 10,3");
            }
        }
        s.leads = leads;
        return saved(c, id + " 알림 시점: " + leads + "분 전");
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> channel(
            String name, BiConsumer<AlarmConfig, Integer> setter, int max) {
        return literal(name).then(argument("번호", IntegerArgumentType.integer(0, max)).executes(c -> {
            int v = IntegerArgumentType.getInteger(c, "번호");
            setter.accept(DdingMate.config(), v);
            DdingMate.rebuildRules();
            return saved(c, v == 0 ? name + " 채널 재시작 알림을 껐어요." : name + " " + v + "채널 재시작을 알려 드릴게요.");
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> corner(String name, AlarmConfig.Corner corner) {
        return literal(name).executes(c -> {
            DdingMate.config().hudCorner = corner;
            return saved(c, "HUD 위치: " + name);
        });
    }

    private static int keywordList(CommandContext<FabricClientCommandSource> c) {
        AlarmConfig.Keywords kw = DdingMate.config().keywords;
        return feedback(c, "키워드 알림 " + (kw.enabled ? "켜짐" : "꺼짐") + " · 내 닉네임 감지 "
                + (kw.includeOwnName ? "켜짐" : "꺼짐") + " · 단어: " + kw.words);
    }

    private static int toggle(CommandContext<FabricClientCommandSource> c, String name,
                              BiConsumer<AlarmConfig, Boolean> setter,
                              java.util.function.Function<AlarmConfig, Boolean> getter) {
        AlarmConfig cfg = DdingMate.config();
        boolean v = !getter.apply(cfg);
        setter.accept(cfg, v);
        return saved(c, name + " 을(를) " + (v ? "켰어요." : "껐어요."));
    }

    private static int saved(CommandContext<FabricClientCommandSource> c, String msg) {
        DdingMate.config().save();
        return feedback(c, msg);
    }

    private static int feedback(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal("[띵메이트] ").formatted(Formatting.GOLD)
                .append(Text.literal(msg).formatted(Formatting.WHITE)));
        return 1;
    }

    private static List<String> allIds() {
        return new ArrayList<>(DdingMate.config().events.keySet());
    }
}
