package kr.ddingmate.craft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 시설 창 시간 글(2026-10 실제 창) */
class CraftTimeTest {

    @Test
    void seconds() {
        assertEquals(20, CraftTimers.seconds("제작 시간 : 20초"));
        assertEquals(336, CraftTimers.seconds("제작 시간 : 5분 36초"));
        assertEquals(5400, CraftTimers.seconds("제작 시간 : 1시간 30분 0초"));
        assertEquals(-1, CraftTimers.seconds("제작 대기중입니다."));
    }
}
