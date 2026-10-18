package kr.ddingmate.ui;

import kr.ddingmate.mixin.TextFieldWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 여러 줄 입력칸. 마인크래프트의 여러 줄 칸(EditBoxWidget)에는 caramelChat 같은 한글 입력 모드가 붙지 않아 한글이 안 써져서
 * (2026-10-17 문의 탭), 한 줄 입력칸(TextFieldWidget)을 그대로 쓰고 그리기만 여러 줄로 한다.
 * 글은 칸 너비에 맞춰 띄어쓰기에서(없으면 글자에서) 줄을 바꿔 보여 준다. 줄 바꿈 글자(엔터)는 넣지 않는다.
 */
public class TextArea extends TextFieldWidget {
    private static final int PAD = 5;
    private static final int LINE = 11;

    private Text hint = Text.empty();
    /** 맨 위에 보이는 줄 */
    private int scroll;
    /** 글자 칸(커서)이 움직였을 때만 커서가 보이게 따라 내린다(휠로 올려 본 것을 되돌리지 않게) */
    private int followedCursor = -1;
    private String followedText = "";

    private String cachedText;
    private int cachedWidth = -1;
    private List<Integer> cachedStarts = List.of(0);

    public TextArea(int x, int y, int w, int h, Text name) {
        super(tr(), x, y, w, h, name);
        setDrawsBackground(false);
        setEditableColor(Theme.TEXT);
        setUneditableColor(Theme.TEXT_DISABLED);
    }

    private static TextRenderer tr() {
        return MinecraftClient.getInstance().textRenderer;
    }

    /** 비어 있을 때 보여 줄 안내 */
    public void setHint(Text hint) {
        this.hint = hint;
    }

    private int innerWidth() {
        // 오른쪽 끝은 위아래 화살표(▲▼) 자리
        return Math.max(10, getWidth() - PAD * 2 - 8);
    }

    private int rows() {
        return Math.max(1, (getHeight() - PAD * 2 + 2) / LINE);
    }

    /** 줄마다 시작하는 글자 위치 */
    public List<Integer> lineStarts() {
        String s = getText();
        int w = innerWidth();
        if (s.equals(cachedText) && w == cachedWidth) return cachedStarts;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int start = 0;
        while (start < s.length()) {
            int len = Math.max(1, tr().trimToWidth(s.substring(start), w).length());
            if (start + len >= s.length()) break;
            int cut = start + len;
            if (s.charAt(cut) == ' ') cut++; // 줄 끝 띄어쓰기는 이 줄에 둔다
            else {
                int space = s.lastIndexOf(' ', cut - 1);
                if (space > start) cut = space + 1;
            }
            if (cut >= s.length()) break;
            starts.add(cut);
            start = cut;
        }
        cachedText = s;
        cachedWidth = w;
        cachedStarts = List.copyOf(starts);
        return cachedStarts;
    }

    private static int lineOf(List<Integer> starts, int index) {
        int line = 0;
        for (int i = 0; i < starts.size(); i++) if (starts.get(i) <= index) line = i;
        return line;
    }

    private int lineEnd(List<Integer> starts, int line) {
        return line + 1 < starts.size() ? starts.get(line + 1) : getText().length();
    }

    /** 줄 안에서 x 픽셀 위치에 가장 가까운 글자 위치(줄이 바뀌는 끝은 다음 줄 맨 앞이라 한 칸 앞에 둔다) */
    private int indexAt(List<Integer> starts, int line, int px) {
        int a = starts.get(line), b = lineEnd(starts, line);
        int i = a + tr().trimToWidth(getText().substring(a, b), Math.max(0, px)).length();
        if (line + 1 < starts.size() && i >= b) i = Math.max(a, b - 1);
        return i;
    }

    @Override
    public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Theme.field(ctx, getX(), getY(), getWidth(), getHeight(), isFocused());
        String s = getText();
        List<Integer> starts = lineStarts();
        int cursor = Math.min(getCursor(), s.length());
        int cursorLine = lineOf(starts, cursor);
        int rows = rows();
        if (cursor != followedCursor || !s.equals(followedText)) {
            if (cursorLine < scroll) scroll = cursorLine;
            else if (cursorLine >= scroll + rows) scroll = cursorLine - rows + 1;
            followedCursor = cursor;
            followedText = s;
        }
        scroll = Math.max(0, Math.min(scroll, starts.size() - rows));
        int x = getX() + PAD;
        int y = getY() + PAD;
        int color = isEditable() ? Theme.TEXT : Theme.TEXT_DISABLED;

        if (s.isEmpty() && !isFocused()) {
            List<String> lines = Theme.wrap(tr(), hint.getString(), innerWidth());
            for (int i = 0; i < Math.min(rows, lines.size()); i++) Theme.text(ctx, tr(), lines.get(i), x, y + i * LINE, Theme.TEXT_DISABLED);
        }

        int other = Math.min(((TextFieldWidgetAccessor) this).ddingmate$selectionEnd(), s.length());
        int selA = Math.min(cursor, other), selB = Math.max(cursor, other);
        for (int line = scroll; line < Math.min(starts.size(), scroll + rows); line++) {
            int a = starts.get(line), b = lineEnd(starts, line);
            int ly = y + (line - scroll) * LINE;
            int sa = Math.max(a, selA), sb = Math.min(b, selB);
            if (sa < sb) {
                int x1 = x + tr().getWidth(s.substring(a, sa)), x2 = x + tr().getWidth(s.substring(a, sb));
                ctx.fill(x1, ly - 1, x2, ly + 9, Theme.SKY_EDGE);
            }
            ctx.drawText(tr(), s.substring(a, b), x, ly, color, false);
        }
        if (isFocused() && cursorLine >= scroll && cursorLine < scroll + rows && (Util.getMeasuringTimeMs() / 300) % 2 == 0) {
            int cx = x + tr().getWidth(s.substring(starts.get(cursorLine), cursor));
            int cy = y + (cursorLine - scroll) * LINE;
            ctx.fill(cx, cy - 1, cx + 1, cy + 9, Theme.TEXT);
        }
        // 위아래로 더 있으면 화살표, 글자 수(0/1500)는 칸 밖 오른쪽 아래(단추 줄)에
        if (scroll > 0) ctx.drawText(tr(), "▲", getX() + getWidth() - PAD - 6, getY() + PAD, Theme.TEXT_DISABLED, false);
        if (scroll + rows < starts.size())
            ctx.drawText(tr(), "▼", getX() + getWidth() - PAD - 6, getY() + PAD + (rows - 1) * LINE, Theme.TEXT_DISABLED, false);
        String count = s.length() + "/" + maxLength();
        Theme.text(ctx, tr(), count, getX() + getWidth() - Theme.width(tr(), count), getY() + getHeight() + 10, Theme.TEXT_DISABLED);
    }

    private int maxLength = 32;

    @Override
    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
        super.setMaxLength(maxLength);
    }

    private int maxLength() {
        return maxLength;
    }

    private boolean editable = true;

    @Override
    public void setEditable(boolean editable) {
        this.editable = editable;
        super.setEditable(editable);
    }

    private boolean isEditable() {
        return editable;
    }

    @Override
    public void write(String text) {
        super.write(text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        List<Integer> starts = lineStarts();
        int line = Math.max(0, Math.min(starts.size() - 1, scroll + (int) Math.floor((mouseY - getY() - PAD + 1) / LINE)));
        setCursor(indexAt(starts, line, (int) Math.floor(mouseX) - getX() - PAD), Screen.hasShiftDown());
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        // 끌어서 고르기
        if (!isFocused() || button != 0) return false;
        List<Integer> starts = lineStarts();
        int line = Math.max(0, Math.min(starts.size() - 1, scroll + (int) Math.floor((mouseY - getY() - PAD + 1) / LINE)));
        setCursor(indexAt(starts, line, (int) Math.floor(mouseX) - getX() - PAD), true);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        int max = Math.max(0, lineStarts().size() - rows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isActive() && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            moveLine(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1, Screen.hasShiftDown());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 위·아래 줄로 커서를 옮긴다(같은 가로 위치 쪽으로) */
    public void moveLine(int by, boolean select) {
        String s = getText();
        List<Integer> starts = lineStarts();
        int cursor = Math.min(getCursor(), s.length());
        int line = lineOf(starts, cursor);
        int target = line + by;
        if (target < 0) setCursor(0, select);
        else if (target >= starts.size()) setCursor(s.length(), select);
        else setCursor(indexAt(starts, target, tr().getWidth(s.substring(starts.get(line), cursor))), select);
    }
}
