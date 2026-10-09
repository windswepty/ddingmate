package kr.ddingmate.hud;

import kr.ddingmate.config.AlarmConfig;

/**
 * 같은 구석에 HUD 여러 개(일정 → 할 일 → 재료 → 내구도)를 차례로 쌓기 위한 프레임별 자리표.
 * 프레임마다 맨 먼저 그리는 일정 HUD 가 {@link #reset()} 한다.
 */
public final class HudStack {

    private static final int[] USED = new int[AlarmConfig.Corner.values().length];

    private HudStack() {}

    public static void reset() {
        java.util.Arrays.fill(USED, 0);
    }

    /** 이 구석에 이미 쌓인 높이 */
    public static int offset(AlarmConfig.Corner corner) {
        return USED[corner.ordinal()];
    }

    /** 높이 h 만큼 그렸다고 기록한다(사이 여백 포함) */
    public static void push(AlarmConfig.Corner corner, int h) {
        USED[corner.ordinal()] += h + AlarmHud.MARGIN;
    }
}
