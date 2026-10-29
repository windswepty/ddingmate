package kr.ddingmate.expert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 해양 탐사(포스 4번 "해양 탐사하기") 창 읽기. 위키 해양 탐사 문서와 2026-10-29 실제 화면 툴팁 기준:
 * <ul>
 * <li>납품 칸 "[ C급 탐사선 출항 준비하기 ]" · "- 요구 아이템 : 합성수지 재활용품" · "- 요구 수량 : 1 / 3"</li>
 * <li>탐사 버튼 "[ C급 탐사선 초기화하기 ]"(재고 채우기 전) · "[ C급 탐사선 출항하기 ]"(다 채움, "- 필요 고대 주화 : 4개")</li>
 * <li>출항 뒤 "[ C급 탐사선 출항 중 ]" · "- 남은 시간 : 30시간 48분 27초" · "현재 탐사 진행 중입니다." (출항 채팅은 없음)</li>
 * </ul>
 * 남은 시간은 CraftTimers 가 일정 HUD 줄로 만들고, 여기서는 출항 전 탐사선의 남은 납품 재료를 기억한다. 읽기만 한다.
 */
public final class Exploration {

    private Exploration() {}

    /** 탐사선 칸 이름: "[ C급 탐사선 출항 중 ]" → 등급 C, 상태 "출항 중" */
    private static final Pattern SLOT = Pattern.compile("^\\[?\\s*([A-Za-z])\\s*급\\s*탐사선\\s*(.*?)\\s*]?$");
    private static final Pattern ITEM = Pattern.compile("요구\\s*아이템\\s*:\\s*(.+)$");
    private static final Pattern COUNT = Pattern.compile("요구\\s*수량\\s*:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");

    /** 납품 재료 한 칸 */
    public record Need(String item, int have, int need) {
        public int left() {
            return Math.max(0, need - have);
        }
    }

    /** 출항 전 탐사선 하나(창의 줄 = 칸 y) */
    public record Prep(String grade, int row, List<Need> needs) {
        public int slotsLeft() {
            return (int) needs.stream().filter(n -> n.left() > 0).count();
        }
    }

    /** 칸 이름에서 등급("C")과 상태("출항 중"). 탐사선 칸이 아니면 null */
    public static String[] parse(String name) {
        Matcher m = SLOT.matcher(name.trim());
        return m.matches() ? new String[]{m.group(1).toUpperCase(java.util.Locale.ROOT), m.group(2).trim()} : null;
    }

    /** 이번 창 읽기에서 모은 출항 준비 줄(창을 다 읽으면 commit) */
    private static final Map<Integer, Prep> scanning = new LinkedHashMap<>();
    private static List<Prep> preps = List.of();
    private static long seenAt;

    /** 납품 칸 하나("출항 준비하기") */
    public static void readNeed(String grade, int row, List<String> lore) {
        String item = null;
        int have = -1, need = -1;
        for (String l : lore) {
            Matcher i = ITEM.matcher(l);
            if (i.find()) item = i.group(1).trim();
            Matcher c = COUNT.matcher(l);
            if (c.find()) {
                have = Integer.parseInt(c.group(1).replace(",", ""));
                need = Integer.parseInt(c.group(2).replace(",", ""));
            }
        }
        if (item == null || need <= 0) return;
        Prep p = scanning.computeIfAbsent(row, r -> new Prep(grade, r, new ArrayList<>()));
        p.needs().add(new Need(item, have, need));
    }

    /** 탐사 창을 다 읽었다: 이번에 본 출항 준비 줄로 바꾼다(출항한 배는 빠짐) */
    public static void commit(long now) {
        preps = List.copyOf(scanning.values());
        scanning.clear();
        seenAt = now;
    }

    /** 창을 다 읽지 않고 끝났을 때(탐사 창이 아님) */
    public static void discard() {
        scanning.clear();
    }

    /** 출항 전 탐사선들(마지막으로 탐사 창을 본 때 기준) */
    public static List<Prep> preps() {
        return preps;
    }

    public static long seenAt() {
        return seenAt;
    }

    /** 시험용 */
    public static void debugReset() {
        scanning.clear();
        preps = List.of();
        seenAt = 0;
    }
}
