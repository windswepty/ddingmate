package kr.ddingmate.gui;

import kr.ddingmate.ui.Theme;
import kr.ddingmate.ui.Widgets;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 읽기 창(업데이트 소식 · 공지): 가운데 판에 제목 · 부제 · 긴 글(휠로 넘기기)과 아래 [확인] 단추.
 * 글은 머리 줄(색)과 점 목록으로 쌓고, 판 너비에 맞춰 줄을 나눈다.
 */
abstract class ReadingScreen extends Screen {

    /** 글 한 덩어리: 머리 줄이면 굵게·색, 아니면 점 목록 한 항목(들여쓰기). gap: 위 여백 */
    record Block(String text, int color, boolean head, boolean bullet, int gap) {}

    private static final int LINE_H = 11;
    private final Screen parent;
    private final String subtitle;
    /** [확인] 옆 두 번째 단추(없으면 null) */
    private String actionLabel;
    private Runnable action;
    private double scroll;
    private int px, py, pw, ph, top, bottom;

    ReadingScreen(String title, String subtitle, Screen parent) {
        super(Text.literal(title));
        this.subtitle = subtitle;
        this.parent = parent;
    }

    /** 보여 줄 글 */
    abstract List<Block> blocks();

    /** [확인] 옆에 단추 하나 더(예: 다운로드 페이지 열기) */
    void action(String label, Runnable run) {
        actionLabel = label;
        action = run;
    }

    @Override
    protected void init() {
        pw = Math.min(380, width - 20);
        ph = height - 20;
        px = (width - pw) / 2;
        py = 10;
        top = py + 40;
        bottom = py + ph - 30;
        Widgets.Button ok = new Widgets.Button(110, 20, Text.literal("확인"), action == null, b -> close());
        ok.setX(action == null ? px + (pw - 110) / 2 : px + pw / 2 + 3);
        ok.setY(py + ph - 25);
        addDrawableChild(ok);
        if (action != null) {
            Widgets.Button go = new Widgets.Button(150, 20, Text.literal(actionLabel), true, b -> action.run());
            go.setX(px + pw / 2 - 153);
            go.setY(py + ph - 25);
            addDrawableChild(go);
        }
    }

    private record Line(OrderedText text, int color, int x, int y) {}

    private List<Line> lay() {
        List<Line> out = new ArrayList<>();
        int y = 0;
        int w = pw - 24;
        for (Block b : blocks()) {
            y += b.gap();
            int indent = b.bullet() ? 10 : 0;
            Text t = b.head() ? Theme.title(b.text()) : Theme.body(b.text());
            List<OrderedText> wrapped = textRenderer.wrapLines(t, w - indent);
            for (int i = 0; i < wrapped.size(); i++) {
                if (b.bullet() && i == 0) out.add(new Line(Theme.body("•").asOrderedText(), b.color(), 0, y));
                out.add(new Line(wrapped.get(i), b.color(), indent, y));
                y += b.head() ? 14 : LINE_H;
            }
        }
        return out;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xB0000000);
        ctx.fill(px, py, px + pw, py + ph, 0xF01B1F27);
        Theme.outline(ctx, px, py, pw, ph, 0xFF3C6FB8);
        ctx.fill(px, py, px + pw, py + 2, 0xFF5A8AD0);
        ctx.drawText(textRenderer, Theme.title(getTitle().getString()), px + 12, py + 9, 0xFFF2C230, true);
        Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, subtitle, pw - 24), px + 12, py + 24, 0xFFB8C0CC, true);
        List<Line> lines = lay();
        int contentH = lines.isEmpty() ? 0 : lines.get(lines.size() - 1).y() + LINE_H;
        scroll = Math.max(0, Math.min(Math.max(0, contentH - (bottom - top)), scroll));
        ctx.enableScissor(px + 4, top, px + pw - 4, bottom);
        for (Line l : lines) {
            int y = top + l.y() - (int) scroll;
            if (y + LINE_H < top || y > bottom) continue;
            ctx.drawText(textRenderer, l.text(), px + 12 + l.x(), y, l.color(), true);
        }
        ctx.disableScissor();
        if (contentH > bottom - top) {
            // 오른쪽 스크롤 막대
            int barH = Math.max(12, (bottom - top) * (bottom - top) / contentH);
            int barY = top + (int) ((bottom - top - barH) * (scroll / Math.max(1, contentH - (bottom - top))));
            ctx.fill(px + pw - 5, barY, px + pw - 3, barY + barH, 0xFF6A7488);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // render 에서 직접 어둡게 칠한다
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= vertical * 22;
        return true;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
