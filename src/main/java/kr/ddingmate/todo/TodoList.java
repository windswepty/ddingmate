package kr.ddingmate.todo;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.ScheduleEngine;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 오늘 할 일. 서버 메시지를 보고 알아서 세고(직접 체크하지 않음), 서버 초기화 시각(매일 03시·목요일 03시·자정)이 지나면 0 으로 돌아간다.
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
                ZonedDateTime thu = n.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY))
                        .with(RESET_TIME).withSecond(0).withNano(0);
                yield n.isBefore(thu) ? thu.minusWeeks(1) : thu;
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
    public static String label(DdingMateConfig.TodoItem item, ZonedDateTime now) {
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
                if (target > before) Notifier.todoProgress(item.name, target, item.target);
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
            case WEEKLY -> "매주 목 03시";
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
