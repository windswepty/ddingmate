package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HUD 자리 정하기. 사용자가 편집 화면에서 직접 옮긴 HUD 는 그 자리에, 아니면 정해 둔 구석에 차례로 쌓는다.
 * 옮긴 자리는 화면 크기에 대한 비율로 저장해서 창 크기나 GUI 배율이 바뀌어도 비슷한 자리에 남는다.
 */
public final class HudLayout {

    public static final String SCHEDULE = "schedule";
    public static final String TODO = "todo";
    public static final String COUNTER = "counter";
    public static final String DURABILITY = "durability";

    /** 편집 화면에서 보여 줄 이름 */
    public static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(SCHEDULE, "다음 일정");
        NAMES.put(TODO, "오늘 할 일");
        NAMES.put(COUNTER, "재료 카운터");
        NAMES.put(DURABILITY, "장비 내구도");
    }

    public record Rect(int x, int y, int w, int h) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** 서버 HUD 를 피하는 여백(화면 GUI 픽셀). 위: 재화 바·날짜/채널 줄, 왼쪽 아래: 채팅창, 오른쪽 아래: 핫바 높이 */
    static final int SERVER_TOP = 16;
    static final int CHAT_ZONE = 135;
    static final int HOTBAR_GAP = 34;
    /** 서버 HUD 피하기를 끈 경우의 예전 여백 */
    static final int PLAIN_TOP = 4;
    static final int PLAIN_BOTTOM = 60;

    private static final Map<String, Rect> BOUNDS = new LinkedHashMap<>();
    /** HUD 위치 편집 화면이 열려 있으면 꺼진 HUD 나 내용이 없는 HUD 도 자리 표시를 그린다 */
    public static boolean editing;

    private HudLayout() {}

    /** 프레임마다 가장 먼저 그리는 일정 HUD 가 부른다 */
    static void beginFrame() {
        HudStack.reset();
        BOUNDS.clear();
    }

    /** 이번 프레임에 그린 HUD 들의 자리 (편집 화면용) */
    public static Map<String, Rect> bounds() {
        return BOUNDS;
    }

    /** 지금 그리고 있는 HUD 의 크기(전체 크기 × HUD 별 크기). push 에서 정한다 */
    private static float current = 1f;

    /** HUD 별 크기(%) 50~150, 기본 100 */
    public static int size(String id) {
        Integer v = DdingMate.config().hudSizes.get(id);
        return v == null ? 100 : v;
    }

    public static void setSize(String id, int percent) {
        int v = Math.max(MIN_SIZE, Math.min(MAX_SIZE, percent));
        if (v == 100) DdingMate.config().hudSizes.remove(id);
        else DdingMate.config().hudSizes.put(id, v);
    }

    public static final int MIN_SIZE = 50;
    public static final int MAX_SIZE = 150;

    /** 이 HUD 의 실제 배율 = 알림 방식 탭의 전체 크기 × 편집 화면의 HUD 별 크기 */
    public static float scale(String id) {
        return DdingMate.config().hudScale / 100f * size(id) / 100f;
    }

    /** HUD 크기를 적용한 가상 화면 너비·높이 (HUD 는 이 좌표로 그린다) */
    static int vw(DrawContext ctx) {
        return Math.round(ctx.getScaledWindowWidth() / current);
    }

    static int vh(DrawContext ctx) {
        return Math.round(ctx.getScaledWindowHeight() / current);
    }

    /** 화면 픽셀 여백을 지금 HUD 의 가상 좌표로 */
    static int gap(int screenPx) {
        return (int) Math.ceil(screenPx / current);
    }

    private static int topReal() {
        return DdingMate.config().avoidServerHud ? SERVER_TOP : PLAIN_TOP;
    }

    private static int bottomReal(DdingMateConfig.Corner corner) {
        if (!DdingMate.config().avoidServerHud) return PLAIN_BOTTOM;
        return corner == DdingMateConfig.Corner.BOTTOM_LEFT ? CHAT_ZONE : HOTBAR_GAP;
    }

    static int topGap() {
        return gap(topReal());
    }

    static int bottomGap(DdingMateConfig.Corner corner) {
        return gap(bottomReal(corner));
    }

    /** 같은 구석에 이미 쌓인 높이(지금 HUD 의 가상 좌표) */
    static int stacked(DdingMateConfig.Corner corner) {
        return Math.round(HudStack.offset(corner) / current);
    }

    /** 이 HUD 의 크기만큼 줄이거나 키워서 그리기 시작 / 끝 */
    static void push(DrawContext ctx, String id) {
        current = scale(id);
        ctx.getMatrices().push();
        ctx.getMatrices().scale(current, current, 1);
    }

    static void pop(DrawContext ctx) {
        ctx.getMatrices().pop();
        current = 1f;
    }

    public static boolean custom(String id) {
        return DdingMate.config().hudPos.containsKey(id);
    }

    /** 옮긴 자리가 있으면 그 위쪽 y(가상 좌표), 없으면 -1 (재료 카운터가 세로 공간을 계산할 때 씀) */
    static int customTop(DrawContext ctx, String id, int h) {
        DdingMateConfig.HudPos p = DdingMate.config().hudPos.get(id);
        if (p == null) return -1;
        return Math.round(p.y * Math.max(0, vh(ctx) - h));
    }

    /**
     * 크기가 w×h(가상 좌표)인 HUD 를 둘 자리 {x, y}(가상 좌표).
     * HUD 마다 크기가 달라도 같은 구석에 쌓이도록 자리 계산은 실제 화면 좌표로 한다.
     */
    static int[] place(DrawContext ctx, String id, DdingMateConfig.Corner corner, int w, int h) {
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        int rw = Math.round(w * current);
        int rh = Math.round(h * current);
        DdingMateConfig.HudPos p = DdingMate.config().hudPos.get(id);
        int x, y;
        if (p != null) {
            x = Math.round(p.x * Math.max(0, sw - rw));
            y = Math.round(p.y * Math.max(0, sh - rh));
        } else {
            boolean top = corner == DdingMateConfig.Corner.TOP_LEFT || corner == DdingMateConfig.Corner.TOP_RIGHT;
            boolean left = corner == DdingMateConfig.Corner.TOP_LEFT || corner == DdingMateConfig.Corner.BOTTOM_LEFT;
            y = cornerY(sh, corner, rh, HudStack.offset(corner));
            // 같은 구석에 쌓다가 채팅창·핫바나 반대쪽 끝까지 닿으면 옆에 새 줄을 시작한다
            if (HudStack.offset(corner) > 0 && overflows(sh, corner, top, y, rh)) {
                HudStack.newColumn(corner);
                y = cornerY(sh, corner, rh, 0);
            }
            x = left ? ScheduleHud.MARGIN + HudStack.shift(corner)
                    : sw - rw - ScheduleHud.MARGIN - HudStack.shift(corner);
            HudStack.push(corner, rw, rh);
        }
        x = Math.max(0, Math.min(sw - rw, x));
        y = Math.max(0, Math.min(sh - rh, y));
        BOUNDS.put(id, new Rect(x, y, rw, rh));
        return new int[]{Math.round(x / current), Math.round(y / current)};
    }

    private static int cornerY(int sh, DdingMateConfig.Corner corner, int rh, int stack) {
        boolean top = corner == DdingMateConfig.Corner.TOP_LEFT || corner == DdingMateConfig.Corner.TOP_RIGHT;
        return top ? topReal() + stack : sh - rh - bottomReal(corner) - stack;
    }

    /** 위쪽 구석이면 아래쪽 안전선(채팅창·핫바)을, 아래쪽 구석이면 위쪽 안전선(서버 재화 바)을 넘는가 */
    private static boolean overflows(int sh, DdingMateConfig.Corner corner, boolean top, int y, int rh) {
        if (top) {
            DdingMateConfig.Corner below = corner == DdingMateConfig.Corner.TOP_LEFT
                    ? DdingMateConfig.Corner.BOTTOM_LEFT : DdingMateConfig.Corner.BOTTOM_RIGHT;
            return y + rh > sh - bottomReal(below);
        }
        return y < topReal();
    }

    /** 편집 화면에서 옮긴 자리를 화면 비율로 저장 */
    public static void setPos(String id, int x, int y, int w, int h, int sw, int sh) {
        DdingMateConfig.HudPos p = new DdingMateConfig.HudPos();
        p.x = sw > w ? Math.max(0f, Math.min(1f, (float) x / (sw - w))) : 0f;
        p.y = sh > h ? Math.max(0f, Math.min(1f, (float) y / (sh - h))) : 0f;
        DdingMate.config().hudPos.put(id, p);
    }

    /** 원래 구석 자리로 */
    public static void reset(String id) {
        DdingMate.config().hudPos.remove(id);
    }

    /** 편집 중에만: 꺼졌거나 보여 줄 내용이 없는 HUD 의 자리 표시 */
    static void placeholder(DrawContext ctx, String id, DdingMateConfig.Corner corner, ItemStack icon, String message) {
        if (!editing) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        DdingMateConfig cfg = DdingMate.config();
        String title = NAMES.get(id);
        int w = Math.max(Theme.boldWidth(tr, title) + 22, Theme.width(tr, message) + 12);
        int h = ScheduleHud.HEAD_H + 14;
        int[] o = place(ctx, id, corner, w, h);
        boolean dark = cfg.hudStyle != DdingMateConfig.HudStyle.LIGHT;
        ScheduleHud.panel(ctx, o[0], o[1], w, h, 200, dark);
        ScheduleHud.header(ctx, tr, o[0], o[1], w, title, icon, dark, 200);
        Theme.text(ctx, tr, message, o[0] + 6, o[1] + ScheduleHud.HEAD_H + 3, dark ? 0xFF9AA3B2 : Theme.TEXT_SOFT);
    }
}
