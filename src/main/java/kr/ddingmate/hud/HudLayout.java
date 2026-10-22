package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    public static final String SKILL = "skill";

    /** 편집 화면에서 보여 줄 이름 */
    public static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(SCHEDULE, "다음 일정");
        NAMES.put(TODO, "오늘 할 일");
        NAMES.put(COUNTER, "재료 카운터");
        NAMES.put(DURABILITY, "장비 내구도");
        NAMES.put(SKILL, "전문가 스킬");
    }

    public record Rect(int x, int y, int w, int h) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** 서버 HUD 를 피하는 여백(화면 GUI 픽셀). 위: 재화 바·날짜/채널 줄, 왼쪽 아래: 채팅창, 오른쪽 아래: 핫바 높이 */
    static final int SERVER_TOP = 16;
    /**
     * 아일랜드(세레니티·파라다이스)에서는 서버가 왼쪽 위에 프로필(얼굴·이름·체력·레벨)을 그린다.
     * 1920×1080·GUI 자동(4배) 실측: 위 17~52, 왼쪽 5~136 → 왼쪽 위 HUD 는 그 아래부터
     */
    static final int ISLAND_PROFILE_TOP = 55;
    static final int ISLAND_PROFILE_RIGHT = 138;

    /** 서버가 그리는 HUD 중 피해야 할 자리(화면 GUI 좌표). 아일랜드 왼쪽 위 프로필 */
    static List<Rect> serverAreas(int sw, int sh) {
        if (!DdingMate.config().avoidServerHud) return List.of();
        return serverAreasFor(sw, sh, kr.ddingmate.game.ServerHud.onDdingtycoon(net.minecraft.client.MinecraftClient.getInstance()),
                kr.ddingmate.game.ServerHud.area() == kr.ddingmate.game.ServerHud.Area.ISLAND);
    }

    /**
     * 띵타이쿤이 늘 그리는 HUD 자리(화면 GUI 좌표, 1920×1080·GUI 4배 실측 2026-10-10).
     * 직접 옮긴 HUD 도 이 자리에 놓이면 비킨다: 왼쪽 위 재화 바(골드·루비·크리스탈), 오른쪽 위 날짜·지역·채널 줄,
     * 아래 가운데 핫바·체력·배고픔 줄, 아일랜드의 왼쪽 위 프로필
     */
    public static List<Rect> serverAreasFor(int sw, int sh, boolean ddingtycoon, boolean island) {
        List<Rect> out = new ArrayList<>();
        out.add(new Rect(sw / 2 - HOTBAR_HALF, sh - HOTBAR_ZONE, HOTBAR_HALF * 2, HOTBAR_ZONE));
        if (ddingtycoon) {
            out.add(new Rect(0, 0, CURRENCY_RIGHT, SERVER_BAR_H));
            out.add(new Rect(sw - DATE_BAR_W, 0, DATE_BAR_W, SERVER_BAR_H));
        }
        if (island) out.add(new Rect(0, 0, ISLAND_PROFILE_RIGHT, ISLAND_PROFILE_TOP));
        return out;
    }

    /** 재화 바 오른쪽 끝, 날짜·채널 줄 너비, 두 줄의 높이 */
    static final int CURRENCY_RIGHT = 207;
    static final int DATE_BAR_W = 140;
    static final int SERVER_BAR_H = 14;
    /** 핫바(182)와 그 위 체력·방어·배고픔 줄 */
    static final int HOTBAR_HALF = 92;
    static final int HOTBAR_ZONE = 50;
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

    /** 가로로 늘린 너비를 적용한 상자 너비(내용보다 좁게는 안 줄어든다) */
    public static int widen(String id, int contentW) {
        Integer w = DdingMate.config().hudWidths.get(id);
        return w == null ? contentW : Math.max(contentW, Math.min(MAX_WIDTH, w));
    }

    /** 가로로 늘린 너비(HUD 좌표 px). 0 이하면 원래대로 */
    public static void setWidth(String id, int w) {
        if (w <= 0) DdingMate.config().hudWidths.remove(id);
        else DdingMate.config().hudWidths.put(id, Math.min(MAX_WIDTH, w));
    }

    public static int width(String id) {
        Integer w = DdingMate.config().hudWidths.get(id);
        return w == null ? 0 : w;
    }

    /** 세로 높이 제한(HUD 좌표 px). 0 이면 제한 없음(화면에 맞춰 알아서) */
    public static int heightLimit(String id) {
        Integer h = DdingMate.config().hudHeights.get(id);
        return h == null ? 0 : Math.max(MIN_HEIGHT, h);
    }

    /** 세로 높이 제한. 0 이하면 원래대로 */
    public static void setHeight(String id, int h) {
        if (h <= 0) DdingMate.config().hudHeights.remove(id);
        else DdingMate.config().hudHeights.put(id, Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, h)));
    }

    public static final int MIN_HEIGHT = 40;
    public static final int MAX_HEIGHT = 1000;
    public static final int MAX_WIDTH = 600;
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

    /** 구석별 위쪽 여백. 아일랜드의 왼쪽 위는 서버 프로필을 피한다 */
    private static int topReal(DdingMateConfig.Corner corner) {
        if (corner == DdingMateConfig.Corner.TOP_LEFT && DdingMate.config().avoidServerHud
                && kr.ddingmate.game.ServerHud.area() == kr.ddingmate.game.ServerHud.Area.ISLAND) {
            return ISLAND_PROFILE_TOP;
        }
        return topReal();
    }

    private static int bottomReal(DdingMateConfig.Corner corner) {
        if (!DdingMate.config().avoidServerHud) return PLAIN_BOTTOM;
        return corner == DdingMateConfig.Corner.BOTTOM_LEFT ? CHAT_ZONE : HOTBAR_GAP;
    }

    /** 시험용: 그 구석의 위쪽 여백(화면 GUI 픽셀) */
    public static int debugTop(DdingMateConfig.Corner corner) {
        return topReal(corner);
    }

    static int topGap() {
        return gap(topReal());
    }

    static int topGap(DdingMateConfig.Corner corner) {
        return gap(topReal(corner));
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
            // 직접 옮긴 자리라도 먼저 그린 HUD 와 겹치면(일정에 제작 줄이 늘어 길어졌을 때 등) 겹치지 않는 가장 가까운 자리
            // (아래·위·옆)로 비킨다. 매 프레임 다시 계산하므로 줄어들면 원래 자리로 돌아온다
            List<Rect> avoid = new ArrayList<>(serverAreas(sw, sh));
            avoid.addAll(BOUNDS.values());
            int[] free = nearestFree(x, y, rw, rh, sw, sh, avoid);
            x = free[0];
            y = free[1];
        } else {
            Spot s = bestSpot(corner, sw, sh, rw, rh);
            if (s.newColumn()) HudStack.newColumn(s.corner());
            HudStack.push(s.corner(), rw, rh + s.lift());
            x = s.x();
            y = s.y();
        }
        x = Math.max(0, Math.min(sw - rw, x));
        y = Math.max(0, Math.min(sh - rh, y));
        BOUNDS.put(id, new Rect(x, y, rw, rh));
        return new int[]{Math.round(x / current), Math.round(y / current)};
    }

    /**
     * (x, y)에 rw×rh 를 놓을 때 avoid 와 겹치면, 화면 안에서 아무것과도 겹치지 않는 가장 가까운 자리.
     * 후보는 원래 자리와 각 장애물의 바로 아래·위·오른쪽·왼쪽을 섞은 것. 위아래를 먼저 고르고, 화면에 안 들어가면 옆.
     * 그런 자리가 없으면 원래 자리
     */
    /** 옆으로 비키는 거리를 위아래보다 몇 배로 칠지 */
    private static final int SIDEWAYS_COST = 8;

    public static int[] nearestFree(int x, int y, int rw, int rh, int sw, int sh, List<Rect> avoid) {
        if (!hits(x, y, rw, rh, avoid)) return new int[]{x, y};
        List<Integer> xs = new ArrayList<>(List.of(x));
        List<Integer> ys = new ArrayList<>(List.of(y));
        for (Rect r : avoid) {
            xs.add(r.x() + r.w() + 2);
            xs.add(r.x() - rw - 2);
            ys.add(r.y() + r.h() + 2);
            ys.add(r.y() - rh - 2);
        }
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int cx : xs) {
            if (cx < 0 || cx + rw > sw) continue;
            for (int cy : ys) {
                if (cy < 0 || cy + rh > sh || hits(cx, cy, rw, rh, avoid)) continue;
                // 붙여 둔 HUD 는 위아래로 밀리는 게 자연스럽다: 옆으로 가는 거리는 크게 쳐서, 위아래 자리가 없을 때만 옆으로
                long dx = (long) (cx - x) * SIDEWAYS_COST, dy = cy - y;
                long d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{cx, cy};
                }
            }
        }
        return best != null ? best : new int[]{x, y};
    }

    private static boolean hits(int x, int y, int w, int h, List<Rect> avoid) {
        for (Rect r : avoid) {
            if (x < r.x() + r.w() && r.x() < x + w && y < r.y() + r.h() && r.y() < y + h) return true;
        }
        return false;
    }

    /** 놓을 수 있는 자리 하나와 그 자리의 나쁨 점수(낮을수록 좋음) */
    private record Spot(DdingMateConfig.Corner corner, boolean newColumn, int x, int y, int cost, int lift) {
        Spot(DdingMateConfig.Corner corner, boolean newColumn, int x, int y, int cost) {
            this(corner, newColumn, x, y, cost, 0);
        }
    }

    /**
     * 아래 구석 자리가 핫바·체력·배고픔 줄과 같은 가로 범위에 걸치면(작은 화면이나 옆 줄로 비켰을 때) 그 줄 위로 올린 자리.
     * 올린 만큼은 같은 줄 쌓기 높이에 더해 다음 HUD 가 그 위에 쌓이게 한다
     */
    private static void addWithLift(List<Spot> spots, Spot s, int sw, int sh, int rw, int rh) {
        spots.add(s);
        Spot up = lifted(s, sw, sh, rw, rh);
        if (up != s) spots.add(up);
    }

    private static Spot lifted(Spot s, int sw, int sh, int rw, int rh) {
        boolean bottom = s.corner() == DdingMateConfig.Corner.BOTTOM_LEFT || s.corner() == DdingMateConfig.Corner.BOTTOM_RIGHT;
        if (!bottom || !DdingMate.config().avoidServerHud) return s;
        int left = sw / 2 - HOTBAR_HALF, right = sw / 2 + HOTBAR_HALF;
        if (s.x() >= right || s.x() + rw <= left) return s;
        int lift = s.y() + rh - (sh - HOTBAR_ZONE - 2);
        if (lift <= 0) return s;
        return new Spot(s.corner(), s.newColumn(), s.x(), s.y() - lift, s.cost(), lift);
    }

    /**
     * 정해 둔 구석에 쌓을 자리를 고르되, 먼저 그린 HUD 와 겹치거나 화면 밖이면 다른 자리를 찾는다.
     * 좋은 순서: 그 구석 아래 → 그 구석 옆 줄 → 다른 구석 → (마지막 수단) 채팅창·핫바 자리까지 내려가기.
     * 다른 HUD 와 겹치는 자리는 다른 방법이 전혀 없을 때만 쓴다.
     */
    private static Spot bestSpot(DdingMateConfig.Corner corner, int sw, int sh, int rw, int rh) {
        List<Spot> spots = new ArrayList<>();
        List<DdingMateConfig.Corner> order = new ArrayList<>();
        order.add(corner);
        for (DdingMateConfig.Corner k : DdingMateConfig.Corner.values()) if (k != corner) order.add(k);
        for (DdingMateConfig.Corner k : order) {
            boolean own = k == corner;
            boolean top = k == DdingMateConfig.Corner.TOP_LEFT || k == DdingMateConfig.Corner.TOP_RIGHT;
            int stack = HudStack.offset(k);
            int y = cornerY(sh, k, rh, stack);
            int x = cornerX(sw, k, rw, HudStack.shift(k));
            boolean over = stack > 0 && overflows(sh, k, top, y, rh);
            addWithLift(spots, new Spot(k, false, x, y, (own ? 0 : 40) + (over ? 70 : 0)), sw, sh, rw, rh);
            if (stack > 0) {
                int shift = HudStack.shift(k) + HudStack.width(k) + ScheduleHud.MARGIN;
                addWithLift(spots, new Spot(k, true, cornerX(sw, k, rw, shift), cornerY(sh, k, rh, 0), own ? 10 : 50), sw, sh, rw, rh);
            }
        }
        Spot best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Spot s : spots) {
            int cost = s.cost();
            if (s.x() < 0 || s.y() < 0 || s.x() + rw > sw || s.y() + rh > sh) cost += 500;
            // 자동 자리도 서버 HUD(재화 바·날짜 줄·핫바·아일랜드 프로필)와 먼저 그린 HUD 를 피한다.
            // 화면이 너무 좁아 둘 다 피할 수 없으면 HUD 끼리 겹치는 것(못 읽음)보다 서버 HUD 위에 걸치는 쪽을 고른다
            if (hits(s.x(), s.y(), rw, rh, serverAreas(sw, sh))) cost += 300;
            if (hits(s.x(), s.y(), rw, rh, new ArrayList<>(BOUNDS.values()))) cost += 1000;
            if (cost < bestCost) {
                best = s;
                bestCost = cost;
            }
        }
        return best;
    }

    private static int cornerX(int sw, DdingMateConfig.Corner corner, int rw, int shift) {
        boolean left = corner == DdingMateConfig.Corner.TOP_LEFT || corner == DdingMateConfig.Corner.BOTTOM_LEFT;
        return left ? ScheduleHud.MARGIN + shift : sw - rw - ScheduleHud.MARGIN - shift;
    }

    private static int cornerY(int sh, DdingMateConfig.Corner corner, int rh, int stack) {
        boolean top = corner == DdingMateConfig.Corner.TOP_LEFT || corner == DdingMateConfig.Corner.TOP_RIGHT;
        return top ? topReal(corner) + stack : sh - rh - bottomReal(corner) - stack;
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
        Theme.textShadow(ctx, tr, message, o[0] + 6, o[1] + ScheduleHud.HEAD_H + 3, Theme.soft(dark), dark);
    }
}
