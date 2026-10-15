package kr.ddingmate.gui;

import kr.ddingmate.ui.Widgets;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 만들 개수 조절 묶음: [−64] [−10] [−] 숫자 [+] [+10] [+64] (+ 한 번에 여러 개 나오는 레시피면 "번 만들기 / 개 얻기" 선택).
 * 재료 탭의 핀 줄과 레시피 고르기 화면에서 함께 쓴다.
 */
final class CountBar {

    static final int MIN = 1;
    static final int MAX = 9999;
    static final int FIELD_W = 52;
    static final int SMALL_W = 20;
    static final int QUICK_W = 30;
    static final int MODE_W = 92;

    private final IntConsumer onValue;
    private final Widgets.Field field;
    private final List<ClickableWidget> widgets = new ArrayList<>();
    private int value;
    private boolean updating;

    /**
     * @param showMode 한 번에 여러 개 나오는 레시피일 때만 true
     */
    CountBar(int value, boolean byOutput, boolean showMode, IntConsumer onValue, Consumer<Boolean> onMode) {
        this.value = clamp(value);
        this.onValue = onValue;

        Widgets.Button minusStack = new Widgets.Button(QUICK_W, 20, Text.literal("−64"), false, b -> set(this.value - 64));
        minusStack.setTooltip(Tooltip.of(Text.literal("1세트(64개) 줄이기")));
        Widgets.Button minusTen = new Widgets.Button(QUICK_W, 20, Text.literal("−10"), false, b -> set(this.value - 10));
        minusTen.setTooltip(Tooltip.of(Text.literal("10개 줄이기")));
        Widgets.Button minus = new Widgets.Button(SMALL_W, 20, Text.literal("−"), false, b -> set(this.value - 1));
        minus.setTooltip(Tooltip.of(Text.literal("1개 줄이기")));
        field = new Widgets.Field(FIELD_W, Text.literal("만들 개수"));
        field.setMaxLength(4);
        field.setText(String.valueOf(this.value));
        field.setTooltip(Tooltip.of(Text.literal("1~9999 까지 직접 적을 수 있어요")));
        field.setChangedListener(text -> {
            if (updating) return;
            try {
                int v = Integer.parseInt(text.trim());
                if (v >= MIN && v <= MAX) {
                    this.value = v;
                    onValue.accept(v);
                }
            } catch (NumberFormatException ignored) {
                // 입력 중인 값은 무시하고 마지막으로 올바른 값을 유지한다
            }
        });
        Widgets.Button plus = new Widgets.Button(SMALL_W, 20, Text.literal("+"), false, b -> set(this.value + 1));
        plus.setTooltip(Tooltip.of(Text.literal("1개 늘리기")));
        Widgets.Button ten = new Widgets.Button(QUICK_W, 20, Text.literal("+10"), false, b -> set(this.value + 10));
        ten.setTooltip(Tooltip.of(Text.literal("10개 늘리기")));
        Widgets.Button stack = new Widgets.Button(QUICK_W, 20, Text.literal("+64"), false, b -> set(this.value + 64));
        stack.setTooltip(Tooltip.of(Text.literal("1세트(64개) 늘리기")));
        widgets.add(minusStack);
        widgets.add(minusTen);
        widgets.add(minus);
        widgets.add(field);
        widgets.add(plus);
        widgets.add(ten);
        widgets.add(stack);
        if (showMode) {
            Widgets.Cycle<Boolean> mode = new Widgets.Cycle<>(MODE_W, "", List.of(false, true), byOutput,
                    v -> v ? "개 얻기" : "번 만들기", onMode::accept);
            mode.setTooltip(Tooltip.of(Text.literal("번 만들기: 숫자만큼 제작해요\n개 얻기: 결과물이 숫자만큼 나오도록 제작 횟수를 알아서 계산해요")));
            widgets.add(mode);
        }
    }

    List<ClickableWidget> widgets() {
        return widgets;
    }

    int value() {
        return value;
    }

    private void set(int v) {
        value = clamp(v);
        updating = true;
        field.setText(String.valueOf(value));
        updating = false;
        onValue.accept(value);
    }

    private static int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }
}
