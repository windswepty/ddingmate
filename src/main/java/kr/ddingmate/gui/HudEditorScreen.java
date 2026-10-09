package kr.ddingmate.gui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.hud.HudLayout;
import kr.ddingmate.ui.Theme;
import kr.ddingmate.ui.Widgets;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD 위치·크기 편집. 게임 화면 위에 HUD 를 그대로 띄운 채 마우스로 다룬다.
 * <ul>
 *   <li>끌기: 옮기기 · 오른쪽 아래 모서리 손잡이 끌기 또는 휠: 크기 · 오른쪽 클릭: 그 HUD 를 원래 자리·크기로</li>
 *   <li>아래 가운데: 고른 HUD 의 [−] 크기 [+], 그리고 [저장] [초기화]. Esc 로 나가면 편집 전으로 돌아간다.</li>
 * </ul>
 */
public class HudEditorScreen extends Screen {

    private static final int SNAP = 6;
    private static final int BUTTON_W = 80;
    private static final int HANDLE = 6;
    private static final int STEP = 10;

    private final Screen parent;
    /** 열었을 때 자리·크기. 저장하지 않고 나가면 되돌린다 */
    private final Map<String, DdingMateConfig.HudPos> beforePos = new LinkedHashMap<>();
    private final Map<String, Integer> beforeSize = new LinkedHashMap<>();
    private boolean saved;

    private String selected = HudLayout.SCHEDULE;
    private String dragging;
    private double offX, offY;
    private String resizing;
    private int resizeStartW;
    private int resizeStartSize;
    private double resizeStartX;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD 위치·크기 설정"));
        this.parent = parent;
        DdingMateConfig cfg = DdingMate.config();
        for (Map.Entry<String, DdingMateConfig.HudPos> e : cfg.hudPos.entrySet()) {
            DdingMateConfig.HudPos copy = new DdingMateConfig.HudPos();
            copy.x = e.getValue().x;
            copy.y = e.getValue().y;
            beforePos.put(e.getKey(), copy);
        }
        beforeSize.putAll(cfg.hudSizes);
    }

    @Override
    protected void init() {
        HudLayout.editing = true;
        int cx = width / 2;
        int sizeY = height - 54;
        Widgets.Button minus = new Widgets.Button(20, 20, Text.literal("−"), false, b -> resize(-STEP));
        minus.setTooltip(Tooltip.of(Text.literal("고른 HUD 작게")));
        minus.setX(cx + 30);
        minus.setY(sizeY);
        addDrawableChild(minus);
        Widgets.Button plus = new Widgets.Button(20, 20, Text.literal("+"), false, b -> resize(STEP));
        plus.setTooltip(Tooltip.of(Text.literal("고른 HUD 크게")));
        plus.setX(cx + 96);
        plus.setY(sizeY);
        addDrawableChild(plus);

        int y = height - 30;
        Widgets.Button save = new Widgets.Button(BUTTON_W, 20, Text.literal("저장"), true, b -> {
            saved = true;
            DdingMate.config().save();
            close();
        });
        save.setX(cx - BUTTON_W - 3);
        save.setY(y);
        addDrawableChild(save);
        Widgets.Button reset = new Widgets.Button(BUTTON_W, 20, Text.literal("초기화"), false, b -> {
            DdingMate.config().hudPos.clear();
            DdingMate.config().hudSizes.clear();
        });
        reset.setTooltip(Tooltip.of(Text.literal("모든 HUD 를 원래 자리·크기로 돌려요. [저장]을 눌러야 남아요.")));
        reset.setX(cx + 3);
        reset.setY(y);
        addDrawableChild(reset);
    }

    private void resize(int delta) {
        if (selected != null) HudLayout.setSize(selected, HudLayout.size(selected) + delta);
    }

    /** 흐림·어둡게 하기 없이 게임 화면과 HUD 를 그대로 보여 준다 */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Map<String, HudLayout.Rect> bounds = HudLayout.bounds();
        String hover = dragging != null ? dragging : resizing != null ? resizing : hit(mouseX, mouseY);
        for (Map.Entry<String, HudLayout.Rect> e : bounds.entrySet()) {
            String id = e.getKey();
            HudLayout.Rect r = e.getValue();
            boolean sel = id.equals(selected);
            boolean on = sel || id.equals(hover);
            int c = sel ? Theme.BLUE : on ? Theme.BLUE_LIGHT : 0xAAFFFFFF;
            int t = on ? 2 : 1;
            ctx.fill(r.x() - t, r.y() - t, r.x() + r.w() + t, r.y(), c);
            ctx.fill(r.x() - t, r.y() + r.h(), r.x() + r.w() + t, r.y() + r.h() + t, c);
            ctx.fill(r.x() - t, r.y(), r.x(), r.y() + r.h(), c);
            ctx.fill(r.x() + r.w(), r.y(), r.x() + r.w() + t, r.y() + r.h(), c);
            if (on) {
                // 크기 손잡이(오른쪽 아래 모서리)
                int hx = r.x() + r.w() - HANDLE + 1, hy = r.y() + r.h() - HANDLE + 1;
                ctx.fill(hx - 1, hy - 1, hx + HANDLE + 1, hy + HANDLE + 1, Theme.BLUE_DEEP);
                ctx.fill(hx, hy, hx + HANDLE, hy + HANDLE, Theme.WHITE);
                String name = HudLayout.NAMES.get(id) + " · " + HudLayout.size(id) + "%"
                        + (HudLayout.custom(id) ? " · 옮김" : "");
                int nw = Theme.width(textRenderer, name) + 8;
                int ly = r.y() >= 13 ? r.y() - 13 : r.y() + r.h() + 2;
                ctx.fill(r.x(), ly, r.x() + nw, ly + 11, sel ? Theme.BLUE_MID : Theme.SLATE);
                Theme.text(ctx, textRenderer, name, r.x() + 4, ly + 2, Theme.WHITE);
            }
        }
        // 아래 가운데 조작 판
        String help = "끌기: 옮기기 · 휠·모서리: 크기 · 오른쪽 클릭: 원래대로 · Esc: 취소";
        int hw = Math.max(Theme.width(textRenderer, help) + 16, BUTTON_W * 2 + 22);
        int hx = width / 2 - hw / 2;
        int hy = height - 76;
        Theme.window(ctx, hx, hy, hw, 72);
        Theme.text(ctx, textRenderer, help, width / 2 - Theme.width(textRenderer, help) / 2, hy + 6, Theme.TEXT_SOFT);
        String sel = selected == null ? "HUD 를 눌러 고르세요" : HudLayout.NAMES.get(selected);
        Theme.boldText(ctx, textRenderer, sel, width / 2 - 6 - Theme.boldWidth(textRenderer, sel), height - 48, Theme.INK, false);
        if (selected != null) {
            String pct = HudLayout.size(selected) + "%";
            Theme.boldText(ctx, textRenderer, pct, width / 2 + 73 - Theme.boldWidth(textRenderer, pct) / 2, height - 48, Theme.BLUE_MID, false);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    private String hit(double mx, double my) {
        List<String> ids = new ArrayList<>(HudLayout.bounds().keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (HudLayout.bounds().get(ids.get(i)).contains(mx, my)) return ids.get(i);
        }
        return null;
    }

    /** 고른(또는 마우스가 올라간) HUD 의 크기 손잡이 위인가 */
    private String handleAt(double mx, double my) {
        for (Map.Entry<String, HudLayout.Rect> e : HudLayout.bounds().entrySet()) {
            HudLayout.Rect r = e.getValue();
            int hx = r.x() + r.w() - HANDLE, hy = r.y() + r.h() - HANDLE;
            if (mx >= hx - 1 && mx <= r.x() + r.w() + 2 && my >= hy - 1 && my <= r.y() + r.h() + 2) return e.getKey();
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0) {
            String h = handleAt(mx, my);
            if (h != null) {
                selected = h;
                resizing = h;
                resizeStartW = HudLayout.bounds().get(h).w();
                resizeStartSize = HudLayout.size(h);
                resizeStartX = mx;
                return true;
            }
        }
        String id = hit(mx, my);
        if (id == null) return false;
        selected = id;
        if (button == 1) {
            HudLayout.reset(id);
            HudLayout.setSize(id, 100);
            return true;
        }
        if (button == 0) {
            HudLayout.Rect r = HudLayout.bounds().get(id);
            dragging = id;
            offX = mx - r.x();
            offY = my - r.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        String id = hit(mx, my);
        if (id == null) return super.mouseScrolled(mx, my, horizontal, vertical);
        selected = id;
        HudLayout.setSize(id, HudLayout.size(id) + (vertical > 0 ? STEP : -STEP));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (resizing != null) {
            // 손잡이를 끈 만큼 너비가 바뀌도록 크기(%)를 정한다. 5% 단위로 맞춘다
            int newW = Math.max(10, (int) Math.round(resizeStartW + (mx - resizeStartX)));
            int pct = Math.round(resizeStartSize * (float) newW / resizeStartW / 5f) * 5;
            HudLayout.setSize(resizing, pct);
            return true;
        }
        if (dragging == null) return super.mouseDragged(mx, my, button, dx, dy);
        HudLayout.Rect r = HudLayout.bounds().get(dragging);
        if (r == null) return true;
        int x = (int) Math.round(mx - offX);
        int y = (int) Math.round(my - offY);
        // 화면 가장자리에 가까우면 딱 붙인다
        if (x < SNAP) x = 0;
        if (y < SNAP) y = 0;
        if (x > width - r.w() - SNAP) x = width - r.w();
        if (y > height - r.h() - SNAP) y = height - r.h();
        x = Math.max(0, Math.min(width - r.w(), x));
        y = Math.max(0, Math.min(height - r.h(), y));
        HudLayout.setPos(dragging, x, y, r.w(), r.h(), width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        resizing = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void close() {
        if (!saved) {
            // 저장하지 않았으면 편집 전 자리·크기로
            DdingMateConfig cfg = DdingMate.config();
            cfg.hudPos.clear();
            cfg.hudPos.putAll(beforePos);
            cfg.hudSizes.clear();
            cfg.hudSizes.putAll(beforeSize);
        }
        HudLayout.editing = false;
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void removed() {
        HudLayout.editing = false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** 시험용: 지금 고른 HUD */
    public String selected() {
        return selected;
    }
}
