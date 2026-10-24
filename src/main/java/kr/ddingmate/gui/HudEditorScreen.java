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
    /** 오른쪽 가장자리 손잡이 높이 */
    private static final int EDGE = 12;

    private final Screen parent;
    /** 열었을 때 자리·크기. 저장하지 않고 나가면 되돌린다 */
    private final Map<String, DdingMateConfig.HudPos> beforePos = new LinkedHashMap<>();
    private final Map<String, Integer> beforeSize = new LinkedHashMap<>();
    private final Map<String, Integer> beforeWidth = new LinkedHashMap<>();
    private final Map<String, Integer> beforeHeight = new LinkedHashMap<>();
    /** 아래 가장자리 손잡이로 높이만 바꾸는 중인 HUD(넘치는 줄은 옆 칸으로) */
    private String heightening;
    private int tallStartH;
    private double tallStartY;
    /** 오른쪽 가장자리 손잡이로 너비만 늘리는 중인 HUD */
    private String widening;
    private int wideStartW;
    private double wideStartX;
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
        beforeWidth.putAll(cfg.hudWidths);
        beforeHeight.putAll(cfg.hudHeights);
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
            DdingMate.config().hudWidths.clear();
            DdingMate.config().hudHeights.clear();
        });
        reset.setTooltip(Tooltip.of(Text.literal("모든 HUD 를 원래 자리·크기로 돌려요. [저장]을 눌러야 남아요.")));
        reset.setX(cx + 3);
        reset.setY(y);
        addDrawableChild(reset);
        // 바 진하기: 바 아래(가운데 아래)에 둔 HUD 가 비쳐 보이게. 바 위에 마우스를 올리면 잠깐 진해진다
        opacity = new Widgets.Button(OPACITY_W, 20, opacityLabel(), false, b -> stepOpacity(1));
        opacity.setTooltip(Tooltip.of(Text.literal("이 조작 바를 흐리게 해서, 바 아래(화면 가운데 아래)에 둔 HUD 를 보면서 옮겨요.\n"
                + "누를 때마다 100 → 70 → 40 → 20%. 바 위에 마우스를 올리면 잠깐 진해져요. 바 아래 HUD 도 그대로 끌 수 있어요.")));
        opacity.setX(cx + BUTTON_W + 9);
        opacity.setY(y);
        addDrawableChild(opacity);
    }

    private static final int OPACITY_W = 74;
    private static final int[] OPACITIES = {100, 70, 40, 20};
    private Widgets.Button opacity;

    private Text opacityLabel() {
        return Text.literal("바 " + DdingMate.config().editorBarOpacity + "%");
    }

    private void stepOpacity(int dir) {
        DdingMateConfig cfg = DdingMate.config();
        int i = 0;
        for (int k = 0; k < OPACITIES.length; k++) if (OPACITIES[k] == cfg.editorBarOpacity) i = k;
        cfg.editorBarOpacity = OPACITIES[Math.floorMod(i + dir, OPACITIES.length)];
        cfg.save(); // 화면을 취소(Esc)해도 바 진하기는 남긴다
        if (opacity != null) opacity.setMessage(opacityLabel());
    }

    /** 시험용 */
    public void debugStepOpacity() {
        stepOpacity(1);
    }

    /** 아래 조작 바 자리(그린 뒤 정해짐) */
    private int barX, barY, barW, barH;

    /** 지금 바 진하기(0~1): 바 위에 마우스가 있으면 진하게 */
    private float barAlpha(int mouseX, int mouseY) {
        boolean over = mouseX >= barX && mouseX < barX + barW && mouseY >= barY && mouseY < barY + barH;
        return over ? 1f : DdingMate.config().editorBarOpacity / 100f;
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
        String hover = dragging != null ? dragging : resizing != null ? resizing : widening != null ? widening
                : heightening != null ? heightening : hit(mouseX, mouseY);
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
                // 오른쪽 가장자리 가운데: 너비만 늘리는 손잡이
                int ex = r.x() + r.w() - 2, ey = r.y() + r.h() / 2 - EDGE / 2;
                ctx.fill(ex - 1, ey - 1, ex + 4, ey + EDGE + 1, Theme.BLUE_DEEP);
                ctx.fill(ex, ey, ex + 3, ey + EDGE, Theme.WHITE);
                if (tallable(id)) {
                    // 아래 가장자리 가운데: 높이만 바꾸는 손잡이(넘치는 줄은 옆 칸으로)
                    int bx = r.x() + r.w() / 2 - EDGE / 2, by = r.y() + r.h() - 2;
                    ctx.fill(bx - 1, by - 1, bx + EDGE + 1, by + 4, Theme.BLUE_DEEP);
                    ctx.fill(bx, by, bx + EDGE, by + 3, Theme.WHITE);
                }
                String name = HudLayout.NAMES.get(id) + " · " + HudLayout.size(id) + "%"
                        + (HudLayout.custom(id) ? " · 옮김" : "") + (HudLayout.heightLimit(id) > 0 ? " · 높이 고정" : "");
                int nw = Theme.width(textRenderer, name) + 8;
                int ly = r.y() >= 13 ? r.y() - 13 : r.y() + r.h() + 2;
                ctx.fill(r.x(), ly, r.x() + nw, ly + 11, sel ? Theme.BLUE_MID : Theme.SLATE);
                Theme.text(ctx, textRenderer, name, r.x() + 4, ly + 2, Theme.WHITE);
            }
        }
        // 아래 가운데 조작 판
        String help = "끌기: 옮기기 · 휠·모서리: 크기 · 오른쪽 가장자리: 가로 · 아래 가장자리: 세로(넘치면 옆으로) · 오른쪽 클릭: 원래대로 · Esc: 취소";
        // 화면보다 길면 여러 줄로 나누고 판을 위로 늘린다(예전: 854×480 에서 양쪽 끝이 잘렸다)
        List<String> helpLines = new ArrayList<>();
        int room = Math.max(BUTTON_W * 2, width - 28);
        for (String part : help.split(" · ")) {
            int last = helpLines.size() - 1;
            if (last >= 0 && Theme.width(textRenderer, helpLines.get(last) + " · " + part) <= room) {
                helpLines.set(last, helpLines.get(last) + " · " + part);
            } else {
                helpLines.add(part);
            }
        }
        int lineW = 0;
        for (String l : helpLines) lineW = Math.max(lineW, Theme.width(textRenderer, l));
        int extra = (helpLines.size() - 1) * 10;
        int hw = Math.min(width - 4, Math.max(lineW + 16, BUTTON_W * 2 + 22));
        int hx = width / 2 - hw / 2;
        int hy = height - 76 - extra;
        // [바 n%] 단추까지 들어가게 판을 넓힌다
        int need = (BUTTON_W + 9 + OPACITY_W + 8) * 2;
        if (hw < need && need <= width - 4) {
            hw = need;
            hx = width / 2 - hw / 2;
        }
        barX = hx;
        barY = hy;
        barW = hw;
        barH = 72 + extra;
        float alpha = barAlpha(mouseX, mouseY);
        // 바와 그 위 글자·단추를 이 진하기로(그리기를 한 번 비워야 색 곱하기가 바에만 걸린다)
        ctx.draw();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        Theme.window(ctx, hx, hy, hw, 72 + extra);
        for (int i = 0; i < helpLines.size(); i++) {
            String l = helpLines.get(i);
            Theme.text(ctx, textRenderer, l, width / 2 - Theme.width(textRenderer, l) / 2, hy + 6 + i * 10, Theme.TEXT_SOFT);
        }
        String sel = selected == null ? "HUD 를 눌러 고르세요" : HudLayout.NAMES.get(selected);
        Theme.boldText(ctx, textRenderer, sel, width / 2 - 6 - Theme.boldWidth(textRenderer, sel), height - 48, Theme.INK, false);
        if (selected != null) {
            String pct = HudLayout.size(selected) + "%";
            Theme.boldText(ctx, textRenderer, pct, width / 2 + 73 - Theme.boldWidth(textRenderer, pct) / 2, height - 48, Theme.BLUE_MID, false);
        }
        super.render(ctx, mouseX, mouseY, delta);
        ctx.draw();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private String hit(double mx, double my) {
        List<String> ids = new ArrayList<>(HudLayout.bounds().keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (HudLayout.bounds().get(ids.get(i)).contains(mx, my)) return ids.get(i);
        }
        return null;
    }

    /** 오른쪽 가장자리 가운데 손잡이(너비만 바꾸기) 위인가 */
    private String edgeAt(double mx, double my) {
        for (Map.Entry<String, HudLayout.Rect> e : HudLayout.bounds().entrySet()) {
            HudLayout.Rect r = e.getValue();
            int ex = r.x() + r.w(), ey = r.y() + r.h() / 2 - EDGE / 2;
            if (mx >= ex - 4 && mx <= ex + 4 && my >= ey - 2 && my <= ey + EDGE + 2) return e.getKey();
        }
        return null;
    }

    /** 높이를 정할 수 있는 HUD(줄이 넘치면 옆 칸으로 넘기는 HUD) */
    private static boolean tallable(String id) {
        return HudLayout.COUNTER.equals(id);
    }

    /** 아래 가장자리 가운데 손잡이(높이만 바꾸기) 위인가 */
    private String bottomAt(double mx, double my) {
        for (Map.Entry<String, HudLayout.Rect> e : HudLayout.bounds().entrySet()) {
            if (!tallable(e.getKey())) continue;
            HudLayout.Rect r = e.getValue();
            int bx = r.x() + r.w() / 2 - EDGE / 2, by = r.y() + r.h();
            if (mx >= bx - 2 && mx <= bx + EDGE + 2 && my >= by - 4 && my <= by + 4) return e.getKey();
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
            String t = bottomAt(mx, my);
            if (t != null && handleAt(mx, my) == null) {
                selected = t;
                heightening = t;
                tallStartH = HudLayout.bounds().get(t).h();
                tallStartY = my;
                return true;
            }
            String w = edgeAt(mx, my);
            if (w != null && handleAt(mx, my) == null) {
                selected = w;
                widening = w;
                wideStartW = HudLayout.bounds().get(w).w();
                wideStartX = mx;
                return true;
            }
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
            HudLayout.setWidth(id, 0);
            HudLayout.setHeight(id, 0);
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
        if (heightening != null) {
            // 끈 만큼 높이 제한(HUD 좌표 px). 내용이 이보다 길면 남는 줄은 옆 칸으로 넘어간다
            int newH = (int) Math.round(tallStartH + (my - tallStartY));
            HudLayout.setHeight(heightening, Math.max(HudLayout.MIN_HEIGHT, Math.round(newH / HudLayout.scale(heightening))));
            return true;
        }
        if (widening != null) {
            // 끈 만큼 상자 너비만 바꾼다(글자 크기는 그대로). 화면 px → HUD 좌표 px
            int newW = (int) Math.round(wideStartW + (mx - wideStartX));
            HudLayout.setWidth(widening, Math.max(1, Math.round(newW / HudLayout.scale(widening))));
            return true;
        }
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
        widening = null;
        heightening = null;
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
            cfg.hudWidths.clear();
            cfg.hudWidths.putAll(beforeWidth);
            cfg.hudHeights.clear();
            cfg.hudHeights.putAll(beforeHeight);
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
