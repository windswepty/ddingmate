package kr.ddingmate.gui;

import kr.ddingmate.ui.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 단축키 지정 창: 쓰고 싶은 키를 함께 누른 뒤 떼면 그 조합(최대 3개)으로 정한다. Esc 는 그대로 두고 닫기,
 * Backspace·Delete 는 단축키 지우기.
 */
public class KeyCaptureScreen extends Screen {

    static final int MAX_KEYS = 3;

    private final Screen parent;
    private final Consumer<List<Integer>> done;
    private final List<Integer> keys = new ArrayList<>();

    public KeyCaptureScreen(Screen parent, Consumer<List<Integer>> done) {
        super(Text.literal("단축키 지정"));
        this.parent = parent;
        this.done = done;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keys.isEmpty() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keys.isEmpty() && (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE)) {
            finish(List.of());
            return true;
        }
        if (keyCode != GLFW.GLFW_KEY_UNKNOWN && !keys.contains(keyCode) && keys.size() < MAX_KEYS) keys.add(keyCode);
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // 누른 키 하나라도 떼면 그때까지 함께 누른 키들로 정한다
        if (!keys.isEmpty()) finish(List.copyOf(keys));
        return true;
    }

    private void finish(List<Integer> result) {
        done.accept(result);
        close();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;
        String now = keys.isEmpty() ? "키를 누르세요" : kr.ddingmate.expert.CropTimer.keyName(keys);
        Theme.boldText(ctx, textRenderer, "아일랜드 작물 타이머 단축키", cx - Theme.boldWidth(textRenderer, "아일랜드 작물 타이머 단축키") / 2, cy - 30, Theme.WHITE, true);
        Theme.boldText(ctx, textRenderer, now, cx - Theme.boldWidth(textRenderer, now) / 2, cy - 10, Theme.GOLD, true);
        String help = "쓸 키를 함께 누른 뒤 떼면 정해져요(최대 " + MAX_KEYS + "개, 예: Ctrl + Shift + G)";
        String help2 = "Esc: 그대로 두기 · Backspace: 단축키 지우기 · 게임 조작 키와 겹치면 그 동작도 함께 일어나요";
        Theme.text(ctx, textRenderer, help, cx - Theme.width(textRenderer, help) / 2, cy + 12, Theme.TEXT_LIGHT);
        Theme.text(ctx, textRenderer, help2, cx - Theme.width(textRenderer, help2) / 2, cy + 26, Theme.TEXT_LIGHT);
    }
}
