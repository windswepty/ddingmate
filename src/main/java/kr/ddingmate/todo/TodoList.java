package kr.ddingmate.todo;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.ScheduleEngine;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 오늘 할 일. 서버 메시지를 보고 알아서 세고(직접 체크하지 않음), 서버 초기화 시각(매일 03시·수요일 03시·자정)이 지나면 0 으로 돌아간다.
 * 체크 시각만 저장하고 "마지막 초기화 이후에 체크했는가"로 판단하므로 게임을 꺼 둔 사이에 지나간 초기화도 반영된다.
 */
public final class TodoList {

    private static final LocalTime RESET_TIME = LocalTime.of(3, 0);
    private static String remindedFor = "";

    private TodoList() {}

    /** now 이전의 가장 최근 초기화 시각 */
    public static ZonedDateTime lastReset(DdingMateConfig.Reset reset, ZonedDateTime now) {
        ZonedDateTime n = now.withZoneSameInstant(ScheduleEngine.KST);
        return switch (reset) {
            case MIDNIGHT -> n.toLocalDate().atStartOfDay(ScheduleEngine.KST);
            case DAILY -> {
                ZonedDateTime today = n.with(RESET_TIME).withSecond(0).withNano(0);
                yield n.isBefore(today) ? today.minusDays(1) : today;
            }
            case WEEKLY -> {
                // 2026-10-01 이후 주간 초기화는 수요일 03시
                ZonedDateTime wed = n.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
                        .with(RESET_TIME).withSecond(0).withNano(0);
                yield n.isBefore(wed) ? wed.minusWeeks(1) : wed;
            }
        };
    }

    /** 다음 초기화 시각 */
    public static ZonedDateTime nextReset(DdingMateConfig.Reset reset, ZonedDateTime now) {
        ZonedDateTime last = lastReset(reset, now);
        return reset == DdingMateConfig.Reset.WEEKLY ? last.plusWeeks(1) : last.plusDays(1);
    }

    /** 지금까지 한 횟수. 마지막 초기화 이전에 센 것이면 0 */
    public static int progress(DdingMateConfig.TodoItem item, ZonedDateTime now) {
        if (item.doneAt <= 0 || item.doneAt < lastReset(item.reset, now).toInstant().toEpochMilli()) return 0;
        return Math.max(0, Math.min(item.target, item.progress));
    }

    public static boolean isDone(DdingMateConfig.TodoItem item, ZonedDateTime now) {
        return progress(item, now) >= Math.max(1, item.target);
    }

    /** 체크(목표만큼 채우기) / 체크 해제(0으로) */
    public static void setDone(DdingMateConfig.TodoItem item, boolean done) {
        item.progress = done ? Math.max(1, item.target) : 0;
        item.doneAt = System.currentTimeMillis();
    }

    /** delta 만큼 세기(+1·−1). 0 ~ 목표 사이로 맞춘다 */
    public static void add(DdingMateConfig.TodoItem item, int delta) {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        item.progress = Math.max(0, Math.min(Math.max(1, item.target), progress(item, now) + delta));
        item.doneAt = System.currentTimeMillis();
    }

    /** 목록에 보일 글: "접속 시간 보상 받기 [2/6]", 목표가 1이면 이름만 */
    /** 스태미나처럼 큰 숫자는 "스태미나 소모하기 (1,200/3,000)" */
    private static String big(int n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    public static String label(DdingMateConfig.TodoItem item, ZonedDateTime now) {
        if (TodoPreset.STAMINA.id.equals(item.preset)) return item.name + " (" + big(progress(item, now)) + "/" + big(item.target) + ")";
        if (needsWindow(item, now)) return item.name + " · " + OPEN_WINDOW;
        return item.target > 1 ? item.name + " [" + progress(item, now) + "/" + item.target + "]" : item.name;
    }

    public static final String OPEN_WINDOW = "의뢰 창을 열어 주세요!";

    /**
     * 의뢰는 창에 보이는 것이 정답이라, 이번 초기화 뒤로 의뢰 창을 한 번도 안 열었으면 개수를 모른다고 본다
     * (서버 안내: "의뢰는 창을 열어 확인 시 활성화됩니다").
     */
    public static boolean needsWindow(DdingMateConfig.TodoItem item, ZonedDateTime now) {
        boolean quest = TodoPreset.DAILY_QUEST.id.equals(item.preset) || TodoPreset.WEEKLY_QUEST.id.equals(item.preset);
        return quest && item.windowSeenAt < lastReset(item.reset, now).toInstant().toEpochMilli();
    }

    /** "의뢰를 완료하였습니다." (실제 서버 메시지). "의뢰를 완료하지 않았습니다." 는 아님 */
    private static final java.util.regex.Pattern RECOMMEND_DONE = java.util.regex.Pattern.compile("^[├└]?\\s*오늘\\s*추천\\s*여부\\s*[:：]\\s*추천\\s*완료");
    private static final java.util.regex.Pattern QUEST_DONE =java.util.regex.Pattern.compile("^의뢰를\\s*완료하였습니다");

    /**
     * 서버 메시지를 보고 할 일을 센다. 다 채운 할 일은 더 세지 않는다. 메시지를 읽기만 하고 아무것도 보내지 않는다.
     * 의뢰 완료는 메시지만으로 일일·주간을 알 수 없어서, 그때 열려 있던(또는 방금 연) 의뢰 창이 주간 의뢰였는지로 나눈다.
     */
    public static void onChat(String message) {
        if (message == null || message.isBlank()) return;
        String text = net.minecraft.util.Formatting.strip(message);
        if (text == null) return;
        text = kr.ddingmate.counter.ItemKeys.stripGlyphs(text).trim();
        // 마인리스트 추천 정보(실제 채팅: "└ 오늘 추천 여부 : 추천 완료")는 이미 한 것이므로 그대로 체크(줄 맨 앞이 "오늘 추천 여부"여야 해서 남이 흉내 낸 채팅은 아님)
        if (RECOMMEND_DONE.matcher(text).find()) {
            sync(TodoPreset.RECOMMEND, 1);
            return;
        }
        if (!kr.ddingmate.game.ServerText.fromPlayer(text)) {
            // 스태미나(2026-10 실제 채팅): "오늘 스태미나 소모량이 부족합니다. (1,200 / 3,000)", "오늘 스태미나 3,000 소모 보상으로 빙고 티켓 …",
            // "오늘 스태미나 소모 보상은 이미 수령했습니다."
            java.util.regex.Matcher st = STAMINA_NEED.matcher(text);
            if (st.find()) {
                sync(TodoPreset.STAMINA, parse(st.group(1)));
                return;
            }
            if (STAMINA_DONE.matcher(text).find()) {
                sync(TodoPreset.STAMINA, TodoPreset.STAMINA.target);
                return;
            }
        }
        // 사람이 쓴 채팅 줄("[칭호] 닉네임 : ...")은 세지 않는다. 서버 공지만 센다
        if (kr.ddingmate.game.ServerText.fromPlayer(text)) return; // 귓속말 포함
        if (QUEST_DONE.matcher(text).find()) {
            // 의뢰 창이 열려 있으면 창에 보이는 "이미 완료한 의뢰" 수로 맞추므로 메시지로는 세지 않는다(두 번 세지 않게)
            if (!QuestContext.windowOpen()) count(QuestContext.weekly() ? TodoPreset.WEEKLY_QUEST : TodoPreset.DAILY_QUEST);
            return;
        }
        for (TodoPreset p : TodoPreset.values()) {
            if (!p.phrase.isEmpty() && text.contains(p.phrase)) count(p);
        }
    }

    private static final java.util.regex.Pattern STAMINA_NEED = java.util.regex.Pattern.compile("오늘\\s*스태미나\\s*소모량이\\s*부족합니다\\.?\\s*\\(\\s*([\\d,]+)\\s*/\\s*[\\d,]+\\s*\\)");
    private static final java.util.regex.Pattern STAMINA_DONE = java.util.regex.Pattern.compile("오늘\\s*스태미나\\s*[\\d,]+\\s*소모\\s*보상으로|오늘\\s*스태미나\\s*소모\\s*보상은\\s*이미\\s*수령");
    /** 의뢰 트래커(오른쪽 사이드바) 줄: "스태미나 소모하기 (2,940/100)" — 앞 숫자가 오늘 소모한 스태미나(2026-10 실제) */
    private static final java.util.regex.Pattern STAMINA_SIDEBAR = java.util.regex.Pattern.compile("스태미나\\s*소모하기\\s*\\(\\s*([\\d,]+)\\s*/");

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 10틱마다: 오른쪽 의뢰 트래커에 "스태미나 소모하기 (N/…)"가 보이면 오늘 소모량으로 맞춘다(읽기만) */
    public static void scanSidebar(net.minecraft.client.MinecraftClient client) {
        if (client.world == null) return;
        net.minecraft.scoreboard.Scoreboard sb = client.world.getScoreboard();
        net.minecraft.scoreboard.ScoreboardObjective obj = sb.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) {
            dailyQuests = List.of();
            weeklyQuests = List.of();
            return;
        }
        // 사이드바는 점수 높은 줄이 위
        List<net.minecraft.scoreboard.ScoreboardEntry> entries = new ArrayList<>(sb.getScoreboardEntries(obj));
        entries.sort((p, q) -> Integer.compare(q.value(), p.value()));
        List<String> lines = new ArrayList<>();
        for (net.minecraft.scoreboard.ScoreboardEntry e : entries) {
            net.minecraft.scoreboard.Team team = sb.getScoreHolderTeam(e.owner());
            lines.add(sidebarLine(net.minecraft.scoreboard.Team.decorateName(team, e.name()).getString()));
        }
        readTracker(lines);
        for (String line : lines) {
            java.util.regex.Matcher m = STAMINA_SIDEBAR.matcher(line);
            if (m.find()) {
                sync(TodoPreset.STAMINA, parse(m.group(1)));
                return;
            }
        }
    }

    /** 의뢰 트래커 한 줄: "레드스톤 채광하기 (0/8)" (2026-10 실제 화면) */
    private static final java.util.regex.Pattern TRACKER_QUEST = java.util.regex.Pattern.compile("^(.+?)\\s*\\(\\s*([\\d,]+)\\s*/\\s*([\\d,]+)\\s*\\)$");
    /** 마지막으로 읽은 의뢰 트래커의 일일·주간 의뢰 줄 */
    private static List<String> dailyQuests = List.of(), weeklyQuests = List.of();

    /** 의뢰 트래커 줄들: "일일 의뢰" 머리 밑은 일일, "주간 의뢰" 머리 밑은 주간 */
    static void readTracker(List<String> lines) {
        List<String> daily = new ArrayList<>(), weekly = new ArrayList<>();
        List<String> into = null;
        boolean tracker = false;
        for (String l : lines) {
            if (l.matches("^(일일|일간)\\s*의뢰.*")) {
                into = daily;
                tracker = true;
            } else if (l.matches("^주간\\s*의뢰.*")) {
                into = weekly;
                tracker = true;
            }
            else if (into != null && TRACKER_QUEST.matcher(l).matches()) into.add(l);
            else if (!l.isBlank() && into != null && !TRACKER_QUEST.matcher(l).matches()) into = null;
        }
        // 트래커가 보이면 그대로("의뢰 활성화 필요"면 빈 목록), 다른 사이드바거나 트래커를 끄면 지난 의뢰를 지운다
        dailyQuests = tracker ? List.copyOf(daily) : List.of();
        weeklyQuests = tracker ? List.copyOf(weekly) : List.of();
    }

    /** 할 일 HUD 에 펼칠 의뢰 내용(일일·주간 의뢰 줄만, 설정이 켜져 있을 때) */
    public static List<String> questLines(DdingMateConfig.TodoItem item) {
        if (!DdingMate.config().todo.questDetails || item == null) return List.of();
        if (TodoPreset.DAILY_QUEST.id.equals(item.preset)) return dailyQuests;
        if (TodoPreset.WEEKLY_QUEST.id.equals(item.preset)) return weeklyQuests;
        return List.of();
    }

    /** 의뢰 줄을 다 채웠는지("(8/8)") */
    public static boolean questDone(String line) {
        java.util.regex.Matcher m = TRACKER_QUEST.matcher(line);
        return m.matches() && parse(m.group(2)) >= parse(m.group(3)) && parse(m.group(3)) > 0;
    }

    /** 사이드바 한 줄: 줄 끝에 점수 주인 이름인 색 코드("§b")가 붙어 온다(2026-10-22 원본 기록 "  레드스톤 채광하기 (11/96)§3") */
    static String sidebarLine(String decorated) {
        String raw = net.minecraft.util.Formatting.strip(decorated);
        return kr.ddingmate.counter.ItemKeys.stripGlyphs(raw == null ? "" : raw).trim();
    }

    /** 시험용: 지금 기억한 [일일, 주간] 의뢰 줄 */
    public static List<List<String>> debugQuests() {
        return List.of(dailyQuests, weeklyQuests);
    }

    /** 시험용: 사이드바 원문 줄(색 코드 포함, 위에서 아래 순)로 [일일, 주간] */
    public static List<List<String>> debugTrackerRaw(List<String> decorated) {
        return debugTracker(decorated.stream().map(TodoList::sidebarLine).toList());
    }

    /** 시험용: [일일, 주간] */
    public static List<List<String>> debugTracker(List<String> lines) {
        readTracker(lines);
        return List.of(dailyQuests, weeklyQuests);
    }

    /**
     * 아일랜드 프로필 HUD(보스바, 서버 리소스팩 hud_island_info) 의 지금 스태미나 "2,720"(2026-10-24 원본 기록).
     * 오늘 일일 의뢰에 '스태미나 소모하기'가 없으면 의뢰 트래커로는 셀 수 없어서(사용자 "스태미나 카운트 안 됨"),
     * 이 숫자가 줄어든 만큼을 오늘 소모량에 더한다. 늘어나는 것(회복·물약)은 세지 않는다. 읽기만 한다
     */
    private static final String STAMINA_FONT = "hud_island_info_hud_text_1_5_1";
    private static int lastStamina = -1;

    /** 보스바 글 하나에서 스태미나 칸을 찾아 읽는다(없으면 아무것도 안 함) */
    public static void scanStamina(net.minecraft.text.Text bar) {
        if (bar == null) return;
        StringBuilder sb = new StringBuilder();
        boolean[] found = {false};
        bar.visit((style, s) -> {
            net.minecraft.util.Identifier f = style.getFont();
            if (f != null && f.getPath().equals(STAMINA_FONT)) {
                found[0] = true;
                sb.append(s);
            }
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        if (!found[0]) return;
        String digits = kr.ddingmate.counter.ItemKeys.stripGlyphs(sb.toString()).replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 7) return;
        staminaReading(Integer.parseInt(digits));
    }

    /** 지금 스태미나 값: 직전보다 줄었으면 그만큼 오늘 소모량에 더한다 */
    static void staminaReading(int value) {
        int before = lastStamina;
        lastStamina = value;
        if (before < 0 || value >= before) return;
        int used = before - value;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        for (DdingMateConfig.TodoItem item : DdingMate.config().todo.items) {
            if (!TodoPreset.STAMINA.id.equals(item.preset)) continue;
            sync(TodoPreset.STAMINA, progress(item, now) + used);
            return;
        }
    }

    /** 접속이 바뀌면 다시 기준을 잡는다(그 사이 회복·소모는 모름) */
    public static void resetStaminaBaseline() {
        lastStamina = -1;
    }

    /** 시험용: 스태미나 HUD 값을 읽은 것처럼 */
    public static void debugStamina(int value) {
        staminaReading(value);
    }

    /** 시험용 */
    public static void debugSidebarLine(String line) {
        java.util.regex.Matcher m = STAMINA_SIDEBAR.matcher(line);
        if (m.find()) sync(TodoPreset.STAMINA, parse(m.group(1)));
    }

    /** 목록에 있는 그 할 일을 +1 */
    public static void count(TodoPreset preset) {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        for (DdingMateConfig.TodoItem item : DdingMate.config().todo.items) {
            if (!preset.id.equals(item.preset) || isDone(item, now)) continue;
            add(item, 1);
            Notifier.todoProgress(item.name, progress(item, now), item.target);
            DdingMate.config().save();
            return;
        }
    }

    /** 서버 기록에서 알게 된 횟수로 맞춘다(예: /접속시간 창의 받은 보상 수). 줄이지는 않는다 */
    public static void sync(TodoPreset preset, int done) {
        set(preset, done, false);
    }

    /** 서버 창에 보이는 그대로 맞춘다(의뢰 창처럼 창이 정답일 때). 많게 센 것도 바로잡는다 */
    public static void setExact(TodoPreset preset, int done) {
        set(preset, done, true);
    }

    private static void set(TodoPreset preset, int done, boolean exact) {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        for (DdingMateConfig.TodoItem item : DdingMate.config().todo.items) {
            if (!preset.id.equals(item.preset)) continue;
            int target = Math.min(item.target, Math.max(0, done));
            int before = progress(item, now);
            if (exact) item.windowSeenAt = System.currentTimeMillis();
            if (target > before || (exact && target != before)) {
                item.progress = target;
                item.doneAt = System.currentTimeMillis();
                DdingMate.config().save();
                boolean quiet = TodoPreset.STAMINA.id.equals(item.preset) && target < item.target; // 스태미나는 자주 바뀌어 다 채웠을 때만
                if (target > before && !quiet) Notifier.todoProgress(item.name, target, item.target);
            }
        }
    }

    /**
     * 지금(또는 방금) 열린 창이 주간 의뢰 창인지. 창 제목과 칸 이름·설명에 "주간 의뢰"가 보이면 주간으로 본다.
     * 창을 닫은 직후 메시지가 와도 맞게 30초 동안 기억한다.
     */
    public static final class QuestContext {
        private static boolean weekly;
        private static long seenAt;

        private QuestContext() {}

        /** 지금 의뢰 창(일일·주간 의뢰 칸이 보이는 창)이 열려 있는지 */
        private static boolean open;
        /**
         * 끝내고 보상까지 받은 의뢰(실제 창: "이미 완료한 의뢰입니다.").
         * 진행도를 다 채웠어도 아직 보상을 안 받은 칸은 세지 않는다 — 보상을 받으며 오는 완료 메시지와 두 번 세지 않게.
         */
        private static final java.util.regex.Pattern CLAIMED = java.util.regex.Pattern.compile("이미\\s*완료한\\s*의뢰|완료한\\s*의뢰입니다");

        /**
         * 10틱마다. 의뢰 창(실제 창: "[ 감자 심기 일일 의뢰 ]" 칸들)을 읽어 일일·주간 중 어느 창인지 기억하고,
         * "이미 완료한 의뢰입니다." 칸 수로 할 일을 그대로 맞춘다(창이 정답이라 많게 센 것도 바로잡음).
         */
        public static void scan(net.minecraft.client.MinecraftClient client) {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)) {
                open = false;
                return;
            }
            // 의뢰 이름으로 센다: 같은 의뢰가 다른 칸에 한 번 더 보여도(장식·숨은 칸) 하나로 친다
            java.util.Set<String> daily = new java.util.LinkedHashSet<>(), weeklySet = new java.util.LinkedHashSet<>();
            java.util.Set<String> dailyDone = new java.util.LinkedHashSet<>(), weeklyDone = new java.util.LinkedHashSet<>();
            for (net.minecraft.screen.slot.Slot slot : screen.getScreenHandler().slots) {
                if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory || !slot.hasStack()) continue;
                net.minecraft.item.ItemStack st = slot.getStack();
                java.util.regex.Matcher m = QUEST_NAME.matcher(kr.ddingmate.counter.ItemKeys.stripGlyphs(st.getName().getString()).trim()
                        .replaceAll("^\\[\\s*|\\s*]$", ""));
                if (!m.matches()) continue;
                String quest = m.group(1).replaceAll("\\s+", " ");
                boolean isWeekly = m.group(2).equals("주간");
                boolean done = false;
                var lore = st.get(net.minecraft.component.DataComponentTypes.LORE);
                if (lore != null) {
                    for (net.minecraft.text.Text l : lore.lines()) {
                        if (CLAIMED.matcher(kr.ddingmate.counter.ItemKeys.stripGlyphs(l.getString())).find()) done = true;
                    }
                }
                (isWeekly ? weeklySet : daily).add(quest);
                if (done) (isWeekly ? weeklyDone : dailyDone).add(quest);
            }
            open = !daily.isEmpty() || !weeklySet.isEmpty();
            if (!open) return;
            weekly = !weeklySet.isEmpty() && daily.isEmpty();
            seenAt = System.currentTimeMillis();
            if (!daily.isEmpty()) report(TodoPreset.DAILY_QUEST, daily, dailyDone);
            if (!weeklySet.isEmpty()) report(TodoPreset.WEEKLY_QUEST, weeklySet, weeklyDone);
        }

        /**
         * 괄호를 뗀 의뢰 칸 이름 "감자 심기 일일 의뢰". 앞에 할 일 내용이 있어야 한다 —
         * 탭 단추 같은 다른 칸("[ 일일 의뢰 ]")은 내용이 없어 걸러진다
         */
        private static final java.util.regex.Pattern QUEST_NAME =
                java.util.regex.Pattern.compile("^([^\\[\\]\\s].*?)\\s+(일일|일간|주간)\\s*의뢰$");

        private static void report(TodoPreset preset, java.util.Set<String> all, java.util.Set<String> done) {
            int count = Math.min(done.size(), preset.target);
            String sig = preset.id + done;
            if (!sig.equals(lastLogged)) {
                lastLogged = sig;
                DdingMate.LOG.info("의뢰 창 {}: 받은 의뢰 {}/{} {}", preset.id, count, all.size(), done);
            }
            setExact(preset, count);
        }

        private static String lastLogged = "";

        static boolean windowOpen() {
            return open;
        }

        static boolean weekly() {
            return weekly && System.currentTimeMillis() - seenAt < 30_000;
        }

        /** 시험용 */
        public static void debugSet(boolean isWeekly) {
            weekly = isWeekly;
            seenAt = isWeekly ? System.currentTimeMillis() : 0;
        }
    }

    public static List<DdingMateConfig.TodoItem> remaining(ZonedDateTime now) {
        return DdingMate.config().todo.items.stream().filter(t -> !isDone(t, now)).toList();
    }

    public static String resetName(DdingMateConfig.Reset r) {
        return switch (r) {
            case DAILY -> "매일 03시";
            case WEEKLY -> "매주 수 03시";
            case MIDNIGHT -> "매일 자정";
        };
    }

    /** 1초에 한 번. 일일 초기화 remindMinutes 분 전에 남은 '매일 03시' 할 일이 있으면 하루에 한 번 알린다. */
    public static void tick(ZonedDateTime now) {
        DdingMateConfig.Todo cfg = DdingMate.config().todo;
        if (cfg.remindMinutes <= 0) return;
        ZonedDateTime next = nextReset(DdingMateConfig.Reset.DAILY, now);
        ZonedDateTime at = next.minusMinutes(cfg.remindMinutes);
        if (now.isBefore(at) || !now.isBefore(next)) return;
        String key = next.toLocalDate().toString();
        if (key.equals(remindedFor)) return;
        remindedFor = key;
        List<DdingMateConfig.TodoItem> left = remaining(now).stream()
                .filter(t -> t.reset == DdingMateConfig.Reset.DAILY).toList();
        if (left.isEmpty()) return;
        String names = left.stream().map(t -> t.name).limit(4).collect(Collectors.joining(", "))
                + (left.size() > 4 ? " 외 " + (left.size() - 4) + "개" : "");
        Notifier.todoReminder(left.size(), names, cfg.remindMinutes);
    }
}
