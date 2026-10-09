package kr.ddingmate.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * 서버 일정 하나. 시각은 모두 한국 시간(Asia/Seoul) 기준이다.
 *
 * @param id          설정·명령어에서 쓰는 영문 식별자
 * @param label       HUD·알림에 표시할 이름
 * @param detail      알림 본문에 붙는 설명
 * @param days        반복 요일. 비어 있으면 매일
 * @param dayOfMonth  매월 반복일(1~31). 0 이면 사용하지 않음
 * @param times       하루 중 발생 시각들
 * @param defaultLeads 기본 알림 시점(이벤트 몇 분 전). 0 은 "지금 시작"
 */
public record EventRule(
        String id,
        String label,
        String detail,
        Set<DayOfWeek> days,
        int dayOfMonth,
        List<LocalTime> times,
        List<Integer> defaultLeads) {

    public boolean occursOn(ZonedDateTime day) {
        if (dayOfMonth > 0 && day.getDayOfMonth() != dayOfMonth) return false;
        return days.isEmpty() || days.contains(day.getDayOfWeek());
    }
}
