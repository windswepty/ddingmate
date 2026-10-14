package kr.ddingmate.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 띵타이쿤 위키 [스케줄러]·[마을 투자]·[파라다이스 정보]·[수렵] 문서에 공개된 일정만으로
 * 다음 발생 시각과 알림 시점을 계산한다. 서버와 통신하지 않는 순수 시계 계산이다.
 */
public final class ScheduleEngine {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 알림이 늦게 확인돼도 이 시간 안이면 울린다(틱 지연·창 최소화 대비). */
    private static final long ALERT_WINDOW_SECONDS = 120;

    private ScheduleEngine() {}

    /** 채널 번호가 0 이하이면 해당 채널 재시작 일정은 만들지 않는다. */
    public record Channels(int village, int hunt, int wild, int island, int spawn) {}

    public record Occurrence(EventRule rule, ZonedDateTime time) {}

    public record Alert(EventRule rule, ZonedDateTime time, int leadMinutes) {
        public String key() {
            return rule.id() + "@" + time.toEpochSecond() + "#" + leadMinutes;
        }
    }

    // ---------------------------------------------------------------- 규칙 목록

    public static List<EventRule> buildRules(Channels ch) {
        List<EventRule> rules = new ArrayList<>();
        Set<DayOfWeek> daily = EnumSet.noneOf(DayOfWeek.class);

        rules.add(new EventRule("daily_reset", "일일 초기화",
                "일일 의뢰·스태미나·상점 일일 제한·무역·오션오더·미니보스 특별 보상·바리스타 주문 초기화",
                daily, 0, List.of(t(3, 0)), List.of(10)));
        rules.add(new EventRule("midnight_reset", "자정 초기화",
                "접속 시간 보상 초기화(자정 전에 수령!)·마을 은행 한도·광채 생성기 용량",
                daily, 0, List.of(t(0, 0)), List.of(15)));
        rules.add(new EventRule("fishing_contest", "파라다이스 낚시 대회",
                "10분 전 종목 공개 — 인어의 축복을 미리 맞춰 두세요",
                daily, 0, List.of(t(10, 0), t(22, 0)), List.of(10, 1)));
        rules.add(new EventRule("invest_deadline", "마을 투자 주간 마감",
                "02:59 까지 투자한 금액으로 주간 순위가 확정돼요",
                EnumSet.of(DayOfWeek.WEDNESDAY), 0, List.of(t(2, 59)), List.of(60, 10)));
        rules.add(new EventRule("maintenance_start", "정기 점검 시작",
                "매주 수요일 09:00~15:00 정기 점검",
                EnumSet.of(DayOfWeek.WEDNESDAY), 0, List.of(t(9, 0)), List.of(30, 10)));
        // 점검 종료 시각은 공지로 자주 바뀌어 일정으로 두지 않는다(사용자 요청 2026-10)
        // 주간 초기화는 2026-10-01 이후 목요일 → 수요일 03시로 바뀌었다(사용자 확인)
        rules.add(new EventRule("weekly_reset", "주간 초기화",
                "주간 의뢰·상점 주간 제한·인피니티 타워 주간 랭킹 초기화",
                EnumSet.of(DayOfWeek.WEDNESDAY), 0, List.of(t(3, 0)), List.of(60)));
        rules.add(new EventRule("hunter_affinity", "수렵꾼 호감도 초기화",
                "매월 1일 03:00 모든 수렵꾼 호감도가 초기화돼요",
                daily, 1, List.of(t(3, 0)), List.of(60)));

        if (ch.village() > 0) {
            int g = (ch.village() - 1) % 5;
            int[][] hours = {{0, 6, 12, 18}, {1, 7, 13, 19}, {3, 9, 15, 21}, {4, 10, 16, 22}, {5, 11, 17, 23}};
            rules.add(new EventRule("village_reboot", "마을 " + ch.village() + "채널 재시작",
                    "마을 채널이 재시작돼요",
                    daily, 0, hoursToTimes(hours[g], 0), List.of(5, 1)));
        }
        // 노크틸라·야생·아일랜드·스폰 재시작은 일정으로 만들지 않는다(사용자 요청·정해진 시각 없음).
        // 지금 채널 재부팅은 서버 예고 "[서버 리붓] N분 뒤 현재 채널이 재부팅됩니다." 로 따로 보여 준다(ServerHud).
        return rules;
    }

    // ---------------------------------------------------------------- 계산

    /** now 이후(같은 시각 포함하지 않음) 가장 가까운 발생 시각. 32일 안에 없으면 null. */
    public static ZonedDateTime next(EventRule rule, ZonedDateTime now) {
        ZonedDateTime kstNow = now.withZoneSameInstant(KST);
        ZonedDateTime best = null;
        for (int d = 0; d <= 32 && best == null; d++) {
            ZonedDateTime day = kstNow.plusDays(d);
            if (!rule.occursOn(day)) continue;
            for (LocalTime time : rule.times()) {
                ZonedDateTime cand = day.with(time).withSecond(0).withNano(0);
                if (cand.isAfter(kstNow) && (best == null || cand.isBefore(best))) best = cand;
            }
        }
        return best;
    }

    /** 켜진 규칙들의 다음 발생 시각을 가까운 순으로. */
    public static List<Occurrence> upcoming(List<EventRule> rules, Function<EventRule, Boolean> enabled,
                                            ZonedDateTime now, int limit) {
        List<Occurrence> list = new ArrayList<>();
        for (EventRule r : rules) {
            if (!enabled.apply(r)) continue;
            ZonedDateTime n = next(r, now);
            if (n != null) list.add(new Occurrence(r, n));
        }
        list.sort(Comparator.comparing(Occurrence::time));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /**
     * 지금 울려야 하는 알림. fired 에 이미 있는 알림은 건너뛰고, 새로 울릴 알림은 fired 에 추가한다.
     * fired 는 (알림 키 → 이벤트 시각 epoch초) 이며 지난 이벤트는 정리한다.
     */
    public static List<Alert> dueAlerts(List<EventRule> rules, Function<EventRule, Boolean> enabled,
                                        Function<EventRule, List<Integer>> leads,
                                        ZonedDateTime now, Map<String, Long> fired) {
        long nowSec = now.toEpochSecond();
        for (Iterator<Map.Entry<String, Long>> it = fired.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < nowSec - 3600) it.remove();
        }
        List<Alert> due = new ArrayList<>();
        // 0분 알림(지금 시작)은 방금 지난 발생도 봐야 하므로 조금 이전 시점부터 다음 발생을 찾는다.
        ZonedDateTime probe = now.minusSeconds(ALERT_WINDOW_SECONDS);
        for (EventRule r : rules) {
            if (!enabled.apply(r)) continue;
            ZonedDateTime at = next(r, probe);
            if (at == null) continue;
            long until = at.toEpochSecond() - nowSec;
            for (int lead : leads.apply(r)) {
                long target = lead * 60L;
                if (until <= target && until > target - ALERT_WINDOW_SECONDS) {
                    Alert a = new Alert(r, at, lead);
                    if (fired.putIfAbsent(a.key(), at.toEpochSecond()) == null) due.add(a);
                }
            }
        }
        return due;
    }

    public static String formatRemaining(Duration d) {
        long s = Math.max(0, d.getSeconds());
        long days = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (days > 0) return days + "일 " + h + "시간";
        if (h > 0) return h + "시간 " + m + "분";
        if (m >= 10) return m + "분";
        return String.format("%d:%02d", m, sec);
    }

    public static Map<String, EventRule> byId(List<EventRule> rules) {
        Map<String, EventRule> map = new HashMap<>();
        for (EventRule r : rules) map.put(r.id(), r);
        return map;
    }

    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }

    private static List<LocalTime> hoursToTimes(int[] hours, int minute) {
        List<LocalTime> out = new ArrayList<>();
        for (int h : hours) out.add(LocalTime.of(h, minute));
        return out;
    }
}
