package kr.ddingmate.hud;

import kr.ddingmate.config.DdingMateConfig;

/**
 * 같은 구석에 HUD 여러 개(일정 → 할 일 → 재료 → 내구도)를 차례로 쌓기 위한 프레임별 자리표.
 * 구석마다 지금 쌓고 있는 줄의 높이·너비와, 옆으로 넘어간 거리를 기억한다.
 * 프레임마다 맨 먼저 그리는 일정 HUD 가 {@link #reset()} 한다.
 */
public final class HudStack {

    private static final int N = DdingMateConfig.Corner.values().length;
    /** 지금 줄에 쌓인 높이 */
    private static final int[] USED = new int[N];
    /** 지금 줄의 가장 넓은 HUD 너비 */
    private static final int[] WIDTH = new int[N];
    /** 앞 줄들을 피해 옆으로 비킨 거리 */
    private static final int[] SHIFT = new int[N];

    private HudStack() {}

    public static void reset() {
        java.util.Arrays.fill(USED, 0);
        java.util.Arrays.fill(WIDTH, 0);
        java.util.Arrays.fill(SHIFT, 0);
    }

    /** 이 구석의 지금 줄에 이미 쌓인 높이 */
    public static int offset(DdingMateConfig.Corner corner) {
        return USED[corner.ordinal()];
    }

    /** 이 구석에서 옆으로 비킨 거리 */
    static int shift(DdingMateConfig.Corner corner) {
        return SHIFT[corner.ordinal()];
    }

    /** 이 구석의 지금 줄에서 가장 넓은 HUD 너비 */
    static int width(DdingMateConfig.Corner corner) {
        return WIDTH[corner.ordinal()];
    }

    /** 지금 줄을 닫고 옆에 새 줄을 시작한다 */
    static void newColumn(DdingMateConfig.Corner corner) {
        int i = corner.ordinal();
        SHIFT[i] += WIDTH[i] + ScheduleHud.MARGIN;
        USED[i] = 0;
        WIDTH[i] = 0;
    }

    /** 너비 w, 높이 h 만큼 그렸다고 기록한다(사이 여백 포함) */
    public static void push(DdingMateConfig.Corner corner, int w, int h) {
        int i = corner.ordinal();
        USED[i] += h + ScheduleHud.MARGIN;
        WIDTH[i] = Math.max(WIDTH[i], w);
    }
}
