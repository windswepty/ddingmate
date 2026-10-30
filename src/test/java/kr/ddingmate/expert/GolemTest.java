package kr.ddingmate.expert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** /광석골렘 답의 남은 시간(2026-10-20~29 실제) */
class GolemTest {

    @Test
    void replyTimes() {
        assertEquals(282_000, OreGolem.duration("다음 등장 대기 : 4분 42초"));
        assertEquals(55_000, OreGolem.duration("다음 등장 대기 : 55초"));
        assertEquals(5_000, OreGolem.duration("다음 등장 대기 : 5초"));
    }
}
