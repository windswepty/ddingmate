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
 * HUD 위치 편집. 게임 화면 위에 HUD 를 그대로 띄운 채 마우스로 끌어 옮긴다.
 * 오른쪽 클릭은 그 HUD 를 원래 구석 자리로. 아래 가운데 [저장]으로 남기고, Esc 로 나가면 옮기기 전으로 돌아간다.
 */
public class HudEditorScreen extends Screen {

    private static final int SNAP = 6;
    private static final int BUTTON_W = 80;

    private final Screen parent;
    /** 열었을 때 자리. 저장하지 않고 나가면 되돌린다 */
    private final Map<String, DdingMateConfig.HudPos> before = new LinkedHashMap<>();
    private boolean saved;

    private String dragging;
    private double offX, offY;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD 위치 편집"));
        this.parent = parent;
        for (Map.Entry<String, DdingMateConfig.HudPos> e : DdingMate.config().hudPos.entrySet()) {
            DdingMateConfig.HudPos copy = new DdingMateConfig.HudPos();
            copy.x = e.getValue().x;
            copy.y = e.getValue().y;
            before.put(e.getKey(), copy);
        }
    }

    @Override
    protected void init() {
        HudLayout.editing = true;
        int y = height - 30;
        Widgets.Button save = new Widgets.Button(BUTTON_W, 20, Text.literal("저장"), true, b -> {
            saved = true;
            DdingMate.config().save();
            close();
        });
        save.setX(width / 2 - BUTTON_W - 3);
        save.setY(y);
        addDrawableChild(save);
        Widgets.Button reset = new Widgets.Button(BUTTON_W, 20, Text.literal("초기화"), false,
                b -> DdingMate.config().hudPos.clear());
        reset.setTooltip(Tooltip.of(Text.literal("모든 HUD 를 원래 구석 자리로 돌려요. [저장]을 눌러야 남아요.")));
        reset.setX(width / 2 + 3);
        reset.setY(y);
        addDrawableChild(reset);
    }

    /** 흐림·어둡게 하기 없이 게임 화면과 HUD 를 그대로 보여 준다 */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // HUD 테두리와 이름표
        Map<String, HudLayout.Rect> bounds = HudLayout.bounds();
        String hover = dragging != null ? dragging : hit(mouseX, mouseY);
        for (Map.Entry<String, HudLayout.Rect> e : bounds.entrySet()) {
            HudLayout.Rect r = e.getValue();
            boolean on = e.getKey().equals(hover);
            int c = on ? Theme.BLUE : 0xAAFFFFFF;
            int t = on ? 2 : 1;
            ctx.fill(r.x() - t, r.y() - t, r.x() + r.w() + t, r.y(), c);
            ctx.fill(r.x() - t, r.y() + r.h(), r.x() + r.w() + t, r.y() + r.h() + t, c);
            ctx.fill(r.x() - t, r.y(), r.x(), r.y() + r.h(), c);
            ctx.fill(r.x() + r.w(), r.y(), r.x() + r.w() + t, r.y() + r.h(), c);
            if (on) {
                String name = HudLayout.NAMES.get(e.getKey()) + (HudLayout.custom(e.getKey()) ? " · 옮김" : "");
                int nw = Theme.width(textRenderer, name) + 8;
                int ly = r.y() >= 13 ? r.y() - 13 : r.y() + r.h() + 2;
                ctx.fill(r.x(), ly, r.x() + nw, ly + 11, Theme.BLUE_MID);
                Theme.text(ctx, textRenderer, name, r.x() + 4, ly + 2, Theme.WHITE);
            }
        }
        // 아래 가운데 안내 판
        String help = "HUD 를 끌어서 옮기세요 · 오른쪽 클릭: 원래 자리 · Esc: 취소";
        int hw = Math.max(Theme.width(textRenderer, help) + 16, BUTTON_W * 2 + 22);
        int hx = width / 2 - hw / 2;
        int hy = height - 52;
        Theme.window(ctx, hx, hy, hw, 48);
        Theme.text(ctx, textRenderer, help, width / 2 - Theme.width(textRenderer, help) / 2, hy + 7, Theme.TEXT);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private String hit(double mx, double my) {
        List<String> ids = new ArrayList<>(HudLayout.bounds().keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (HudLayout.bounds().get(ids.get(i)).contains(mx, my)) return ids.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        String id = hit(mx, my);
        if (id == null) return false;
        if (button == 1) {
            HudLayout.reset(id);
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
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
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
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void close() {
        if (!saved) {
            // 저장하지 않았으면 옮기기 전 자리로
            Map<String, DdingMateConfig.HudPos> pos = DdingMate.config().hudPos;
            pos.clear();
            pos.putAll(before);
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
}
