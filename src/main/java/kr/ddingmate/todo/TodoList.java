package kr.ddingmate.todo;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.ScheduleEngine;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 오늘 할 일. 직접 체크하고, 서버 초기화 시각(매일 03시·목요일 03시·자정)이 지나면 체크가 알아서 풀린다.
 * 체크 시각만 저장하고 "마지막 초기화 이후에 체크했는가"로 판단하므로 게임을 꺼 둔 사이에 지나간 초기화도 반영된다.
 */
public final class TodoList {

    private static final LocalTime RESET_TIME = LocalTime.of(3, 0);
    private static String remindedFor = "";

    private TodoList() {}

    /** now 이전의 가장 최근 초기화 시각 */
    public static ZonedDateTime lastReset(AlarmConfig.Reset reset, ZonedDateTime now) {
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
    public static ZonedDateTime nextReset(AlarmConfig.Reset reset, ZonedDateTime now) {
        ZonedDateTime last = lastReset(reset, now);
        return reset == AlarmConfig.Reset.WEEKLY ? last.plusWeeks(1) : last.plusDays(1);
    }

    public static boolean isDone(AlarmConfig.TodoItem item, ZonedDateTime now) {
        return item.doneAt > 0 && item.doneAt >= lastReset(item.reset, now).toInstant().toEpochMilli();
    }

    public static void setDone(AlarmConfig.TodoItem item, boolean done) {
        item.doneAt = done ? System.currentTimeMillis() : 0;
    }

    public static List<AlarmConfig.TodoItem> remaining(ZonedDateTime now) {
        return DdingMate.config().todo.items.stream().filter(t -> !isDone(t, now)).toList();
    }

    public static String resetName(AlarmConfig.Reset r) {
        return switch (r) {
            case DAILY -> "매일 03시";
            case WEEKLY -> "매주 목 03시";
            case MIDNIGHT -> "매일 자정";
        };
    }

    /** 1초에 한 번. 일일 초기화 remindMinutes 분 전에 남은 '매일 03시' 할 일이 있으면 하루에 한 번 알린다. */
    public static void tick(ZonedDateTime now) {
        AlarmConfig.Todo cfg = DdingMate.config().todo;
        if (cfg.remindMinutes <= 0) return;
        ZonedDateTime next = nextReset(AlarmConfig.Reset.DAILY, now);
        ZonedDateTime at = next.minusMinutes(cfg.remindMinutes);
        if (now.isBefore(at) || !now.isBefore(next)) return;
        String key = next.toLocalDate().toString();
        if (key.equals(remindedFor)) return;
        remindedFor = key;
        List<AlarmConfig.TodoItem> left = remaining(now).stream()
                .filter(t -> t.reset == AlarmConfig.Reset.DAILY).toList();
        if (left.isEmpty()) return;
        String names = left.stream().map(t -> t.name).limit(4).collect(Collectors.joining(", "))
                + (left.size() > 4 ? " 외 " + (left.size() - 4) + "개" : "");
        Notifier.todoReminder(left.size(), names, cfg.remindMinutes);
    }
}
