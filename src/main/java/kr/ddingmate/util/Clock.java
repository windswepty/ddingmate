package kr.ddingmate.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.LongSupplier;

/**
 * 띵메이트가 게임 상태(제작·쿨타임·할 일·초기화…)를 계산할 때 쓰는 지금 시각. 게임에서는 늘 진짜 시각이다.
 * 실제 기록 재생 시험이 기록된 시각으로 맞춰, 시간이 지나야 일어나는 일(완료 알림·초기화)까지 기록 순서대로 확인한다
 */
public final class Clock {

    private Clock() {}

    private static volatile LongSupplier source = System::currentTimeMillis;

    /** 지금 시각(ms) */
    public static long now() {
        return source.getAsLong();
    }

    /** 지금 시각(시간대) */
    public static ZonedDateTime zoned(ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(now()), zone);
    }

    /** 시험용: 시각을 정한다(null 이면 진짜 시각으로) */
    public static void debugSet(LongSupplier s) {
        source = s == null ? System::currentTimeMillis : s;
    }
}
