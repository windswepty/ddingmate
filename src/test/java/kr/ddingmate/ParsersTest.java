package kr.ddingmate;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마인크래프트를 띄우지 않는 빠른 시험(./gradlew test, 몇 초). 서버 문구 해석·시간 계산처럼 게임 없이 되는 것만.
 * 문구는 모두 실제 기록에서 가져왔다. 게임 화면이 필요한 시험은 src/gametest(./gradlew runClientGameTest)
 */
class ParsersTest {

    @Test
    void itemNames() {
        assertEquals("코룸주괴", kr.ddingmate.counter.ItemKeys.normalize("§6[강화석] 코룸 주괴"));
        assertEquals("세이지곡괭이", kr.ddingmate.counter.ItemKeys.normalize("ꀆ세이지 곡괭이 [+11]"));
    }

    @Test
    void questTrackerSidebar() {
        // 2026-10-22 원본 기록: 줄 끝에 점수 주인 이름 색 코드
        List<List<String>> q = kr.ddingmate.todo.TodoList.debugTrackerRaw(List.of(
                "§d", " 일일 의뢰§c", "  판매로 골드 획득하기 (27,550/10,000)§b", "  미역 채집하기 (0/30)§7", "§6",
                " 주간 의뢰§5", "  레드스톤 채광하기 (11/96)§4", " 메뉴를 통해 직접 확인할 수 있습니다. §0"));
        assertEquals(List.of("판매로 골드 획득하기 (27,550/10,000)", "미역 채집하기 (0/30)"), q.get(0));
        assertEquals(List.of("레드스톤 채광하기 (11/96)"), q.get(1));
        assertTrue(kr.ddingmate.todo.TodoList.debugTrackerRaw(List.of(" 일일 의뢰§0", " 일일 의뢰 활성화 필요§1")).get(0).isEmpty());
    }

    @Test
    void chatLines() {
        var village = kr.ddingmate.game.Nickname.parse("[퍼피마트마을](102호) 멜트 : ㅇ");
        assertNotNull(village);
        assertEquals("멜트", village.sender());
        var whisper = kr.ddingmate.game.Nickname.parse("(멜트 → 단무지<보냄>) 모지모지");
        assertNotNull(whisper);
        assertTrue(whisper.whisper() && whisper.sent());
        assertEquals("모지모지", whisper.body());
        assertNull(kr.ddingmate.game.Nickname.parse("멜트님이 업적을 달성했습니다"));
    }

    @Test
    void keywordExcludes() {
        assertTrue(!kr.ddingmate.notify.KeywordWatcher.debugWithoutExcludes("이찬 : 안녕", List.of("이찬")).contains("찬"));
        assertTrue(kr.ddingmate.notify.KeywordWatcher.debugWithoutExcludes("이찬 : 찬 어디", List.of("이찬")).contains("찬"));
    }

    @Test
    void serverTimes() {
        assertEquals(125_000, kr.ddingmate.expert.OceanOrder.remainingIn("남은 시간 : 2분 5초"));
        String[] ship = kr.ddingmate.expert.Exploration.parse("[ C급 탐사선 출항 중 ]");
        assertNotNull(ship);
        assertEquals("C", ship[0]);
        assertEquals("출항 중", ship[1]);
    }

    @Test
    void cookingChangeDays() {
        // 실제 "[TIP] N일 오전 3시 요리 상점 가격이 변동됩니다." (05-31→1일, 06-03→6일, 08-02→3일, 08-23→24일, 10-18→21일)
        int[][] tips = {{5, 31, 1}, {6, 3, 6}, {8, 2, 3}, {8, 23, 24}, {10, 18, 21}};
        for (int[] t : tips) {
            ZonedDateTime at = ZonedDateTime.of(2026, t[0], t[1], 12, 0, 0, 0, kr.ddingmate.schedule.ScheduleEngine.KST);
            assertEquals(t[2], kr.ddingmate.expert.Cooking.nextChange(at).getDayOfMonth(), "TIP " + at);
        }
        ZonedDateTime oct30 = ZonedDateTime.of(2026, 10, 30, 3, 0, 0, 0, kr.ddingmate.schedule.ScheduleEngine.KST);
        assertEquals(1, kr.ddingmate.expert.Cooking.nextChange(oct30).getDayOfMonth());
        assertEquals(27, kr.ddingmate.expert.Cooking.lastChange(oct30.minusSeconds(1)).getDayOfMonth());
    }
}
