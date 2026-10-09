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

    public static boolean custom(String id) {
        return DdingMate.config().hudPos.containsKey(id);
    }

    /** 옮긴 자리가 있으면 그 위쪽 y, 없으면 -1 (재료 카운터가 세로 공간을 계산할 때 씀) */
    static int customTop(DrawContext ctx, String id, int h) {
        DdingMateConfig.HudPos p = DdingMate.config().hudPos.get(id);
        if (p == null) return -1;
        return Math.round(p.y * Math.max(0, ctx.getScaledWindowHeight() - h));
    }

    /** 크기가 w×h 인 HUD 를 둘 자리 {x, y}. 구석에 쌓을 때는 쌓인 높이도 기록한다 */
    static int[] place(DrawContext ctx, String id, DdingMateConfig.Corner corner, int w, int h) {
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        DdingMateConfig.HudPos p = DdingMate.config().hudPos.get(id);
        int x, y;
        if (p != null) {
            x = Math.round(p.x * Math.max(0, sw - w));
            y = Math.round(p.y * Math.max(0, sh - h));
        } else {
            int stack = HudStack.offset(corner);
            x = ScheduleHud.cornerX(ctx, corner, w);
            y = ScheduleHud.cornerY(ctx, corner, h, stack);
            HudStack.push(corner, h);
        }
        x = Math.max(0, Math.min(sw - w, x));
        y = Math.max(0, Math.min(sh - h, y));
        BOUNDS.put(id, new Rect(x, y, w, h));
        return new int[]{x, y};
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
