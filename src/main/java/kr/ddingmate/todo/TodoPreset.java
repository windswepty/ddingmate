package kr.ddingmate.todo;

import kr.ddingmate.config.DdingMateConfig;

/**
 * 오늘 할 일로 넣을 수 있는 것. 서버가 끝났다고 알려 주는 것만 넣어서 모드가 알아서 센다(직접 체크하지 않는다).
 * 문구는 실제 서버 메시지(2026-08~10 게임 로그)에서 확인했다.
 */
public enum TodoPreset {
    DAILY_QUEST("daily_quest", "일일 의뢰", DdingMateConfig.Reset.DAILY, 5, "",
            "의뢰를 완료하면(\"의뢰를 완료하였습니다\") 세요. 주간 의뢰 창에서 완료한 것은 주간 의뢰로 세요"),
    WEEKLY_QUEST("weekly_quest", "주간 의뢰", DdingMateConfig.Reset.WEEKLY, 3, "",
            "주간 의뢰 창에서 의뢰를 완료하면 세요"),
    PLAYTIME("playtime", "접속 시간 보상 받기", DdingMateConfig.Reset.MIDNIGHT, 6, "접속 보상을 수령하였습니다",
            "\"N시간 접속 보상을 수령하였습니다\"가 뜨면 세고, /접속시간 창을 열면 받은 보상 수로 맞춰요"),
    RECOMMEND("recommend", "마인리스트 추천", DdingMateConfig.Reset.MIDNIGHT, 1, "추천 보상 우편이 도착",
            "\"마인리스트 추천 보상 우편이 도착했습니다\"가 뜨면 체크돼요"),
    TRADE("trade", "무역", DdingMateConfig.Reset.DAILY, 1, "무역 아이템을",
            "\"무역 아이템을 …골드에 판매하였습니다\"가 뜨면 체크돼요"),
    /** 위키 스태미나: 매일 03시 초기화. 3,000 소모 보상(빙고 티켓 10개) — 2026-10 실제 채팅 "오늘 스태미나 3,000 소모 보상으로 …" */
    STAMINA("stamina", "스태미나 소모하기", DdingMateConfig.Reset.DAILY, 3000, "",
            "아일랜드 왼쪽 위 프로필의 스태미나 숫자가 줄어든 만큼 세요. 오른쪽 의뢰 트래커의 \"스태미나 소모하기 (N/…)\"와 보상을 받을 때 뜨는 "
                    + "\"(N / 3,000)\"으로도 맞추고, 3,000 소모 보상을 받으면 다 채워요");

    public final String id;
    public final String label;
    public final DdingMateConfig.Reset reset;
    public final int target;
    /** 서버 메시지에 이 문구가 있으면 +1 (의뢰는 따로 판단) */
    public final String phrase;
    /** 어떻게 세는지(설정 화면 설명) */
    public final String how;

    TodoPreset(String id, String label, DdingMateConfig.Reset reset, int target, String phrase, String how) {
        this.id = id;
        this.label = label;
        this.reset = reset;
        this.target = target;
        this.phrase = phrase;
        this.how = how;
    }

    public DdingMateConfig.TodoItem create() {
        DdingMateConfig.TodoItem t = new DdingMateConfig.TodoItem(label, reset, target);
        t.preset = id;
        t.autoPhrase = phrase;
        return t;
    }

    public static TodoPreset of(DdingMateConfig.TodoItem item) {
        if (item == null) return null;
        for (TodoPreset p : values()) if (p.id.equals(item.preset)) return p;
        return null;
    }

    /** 예전 설정의 할 일 이름 → 해당하는 것(없으면 null) */
    public static TodoPreset byOldName(String name) {
        if (name == null) return null;
        return switch (name.trim()) {
            case "일일 의뢰", "의뢰 완료" -> DAILY_QUEST;
            case "주간 의뢰" -> WEEKLY_QUEST;
            case "접속 시간 보상 받기", "접속 시간 보상" -> PLAYTIME;
            case "마인리스트 추천" -> RECOMMEND;
            case "무역" -> TRADE;
            case "스태미나 소모하기" -> STAMINA;
            default -> null;
        };
    }
}
