package kr.ddingmate.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/** 띵타이쿤 창 모양으로 그리는 단추·스위치·선택 단추·슬라이더·입력 칸 */
public final class Widgets {

    private Widgets() {}

    private static TextRenderer tr() {
        return MinecraftClient.getInstance().textRenderer;
    }

    /** 왼쪽/오른쪽을 가리키는 작은 픽셀 화살표(5×7) */
    static void arrow(DrawContext ctx, int x, int y, boolean right, int color) {
        for (int i = 0; i < 4; i++) {
            int len = 7 - i * 2;
            int col = right ? x + i : x + 3 - i;
            ctx.fill(col, y + i, col + 1, y + i + len, color);
        }
    }

    /** 일반 단추. primary=true 면 파란 단추(주요 동작), 아니면 회색 단추. 아이콘을 앞에 붙일 수 있다 */
    public static class Button extends PressableWidget {
        private final Consumer<Button> action;
        private final boolean primary;
        private ItemStack icon;

        public Button(int w, int h, Text message, boolean primary, Consumer<Button> action) {
            super(0, 0, w, h, message);
            this.primary = primary;
            this.action = action;
        }

        public Button icon(ItemStack icon) {
            this.icon = icon;
            return this;
        }

        @Override
        public void onPress() {
            action.accept(this);
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.Tile style = !active ? Theme.Tile.SLATE : primary ? Theme.Tile.BLUE : Theme.Tile.GRAY;
            Theme.tile(ctx, getX(), getY(), width, height, style, isSelected() && active);
            int iconW = icon != null ? 14 : 0;
            String s = Theme.ellipsize(tr(), getMessage().getString(), width - 10 - iconW, true);
            int tw = Theme.boldWidth(tr(), s) + iconW;
            int tx = getX() + (width - tw) / 2;
            if (icon != null) Theme.item(ctx, icon, tx, getY() + (height - 12) / 2, 12);
            int color = !active ? 0xFFAEB4BF : primary ? Theme.TEXT_LIGHT : Theme.TEXT;
            Theme.boldText(ctx, tr(), s, tx + iconW, getY() + (height - 8) / 2, color, primary && active);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /** 왼쪽에 이름, 오른쪽에 ON/OFF 스위치가 있는 한 줄짜리 칸 */
    public static class Toggle extends PressableWidget {
        private final String label;
        private final Consumer<Boolean> setter;
        private boolean value;
        private ItemStack icon;
        private String detail;

        public Toggle(int w, String label, boolean value, Consumer<Boolean> setter) {
            super(0, 0, w, 20, Text.literal(label));
            this.label = label;
            this.value = value;
            this.setter = setter;
        }

        /** 이름 앞에 아이템 아이콘 */
        public Toggle icon(ItemStack icon) {
            this.icon = icon;
            return this;
        }

        /** 스위치 왼쪽에 붙는 작은 회색 글자(예: 내구도 236/1938) */
        public Toggle detail(String detail) {
            this.detail = detail;
            return this;
        }

        @Override
        public void onPress() {
            value = !value;
            setter.accept(value);
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.row(ctx, getX(), getY(), width, height, isSelected());
            int x = getX() + 7;
            if (icon != null) {
                Theme.item(ctx, icon, getX() + 4, getY() + 2, 16);
                x = getX() + 23;
            }
            int right = getX() + width - 44;
            if (detail != null) {
                int dw = Theme.width(tr(), detail);
                Theme.text(ctx, tr(), detail, right - dw, getY() + 6, Theme.TEXT_SOFT);
                right -= dw + 6;
            }
            Theme.text(ctx, tr(), Theme.ellipsize(tr(), label, right - x), x, getY() + 6, Theme.TEXT);
            Theme.onOff(ctx, tr(), getX() + width - 39, getY() + 3, value, isSelected());
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }

        @Override
        protected MutableText getNarrationMessage() {
            return Text.literal(label + ": " + (value ? "켬" : "끔"));
        }
    }

    /** 이름 + 값. 왼쪽 클릭은 다음 값, 오른쪽 클릭·Shift+클릭은 이전 값 */
    public static class Cycle<T> extends PressableWidget {
        private final String label;
        private final List<T> values;
        private final Function<T, String> names;
        private final Consumer<T> setter;
        private int index;
        private int lastButton;

        public Cycle(int w, String label, List<T> values, T initial, Function<T, String> names, Consumer<T> setter) {
            super(0, 0, w, 20, Text.literal(label));
            this.label = label;
            this.values = values;
            this.names = names;
            this.setter = setter;
            this.index = Math.max(0, values.indexOf(initial));
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            lastButton = button;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        protected boolean isValidClickButton(int button) {
            return button == 0 || button == 1;
        }

        @Override
        public void onPress() {
            boolean back = lastButton == 1 || Screen.hasShiftDown();
            lastButton = 0;
            index = Math.floorMod(index + (back ? -1 : 1), values.size());
            setter.accept(values.get(index));
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.row(ctx, getX(), getY(), width, height, isSelected());
            String v = names.apply(values.get(index));
            int vw = Theme.boldWidth(tr(), v);
            int chipW = vw + 26;
            int chipX = getX() + width - chipW - 3;
            Theme.tile(ctx, chipX, getY() + 3, chipW, 14, Theme.Tile.GRAY, isSelected());
            arrow(ctx, chipX + 4, getY() + 6, false, Theme.GRAY_SHADE);
            arrow(ctx, chipX + chipW - 8, getY() + 6, true, Theme.GRAY_SHADE);
            Theme.boldText(ctx, tr(), v, chipX + 13, getY() + 6, Theme.TEXT, false);
            if (!label.isEmpty()) {
                String s = Theme.ellipsize(tr(), label, chipX - getX() - 12);
                Theme.text(ctx, tr(), s, getX() + 7, getY() + 6, Theme.TEXT);
            }
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /** 0~1 값 슬라이더. 파란 막대 + 파란 손잡이 */
    public static class Slider extends SliderWidget {
        private final Function<Double, String> format;
        private final DoubleConsumer setter;

        public Slider(int w, double value, Function<Double, String> format, DoubleConsumer setter) {
            super(0, 0, w, 20, Text.empty(), value);
            this.format = format;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(format.apply(value)));
        }

        @Override
        protected void applyValue() {
            setter.accept(value);
        }

        @Override
        public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.row(ctx, getX(), getY(), width, height, isSelected());
            Theme.text(ctx, tr(), getMessage().getString(), getX() + 7, getY() + 6, Theme.TEXT);
            int trackX = getX() + width / 2;
            int trackW = width / 2 - 10;
            Theme.outlineSmall(ctx, trackX - 1, getY() + 8, trackW + 2, 6, 0xFF4A4E58);
            ctx.fill(trackX, getY() + 9, trackX + trackW, getY() + 13, Theme.TRACK);
            ctx.fill(trackX, getY() + 9, trackX + (int) Math.round(trackW * value), getY() + 13, Theme.BLUE);
            int kx = trackX + (int) Math.round((trackW - 8) * value);
            Theme.tile(ctx, kx, getY() + 4, 8, 14, Theme.Tile.BLUE, isSelected());
        }
    }

    /**
     * 입력 칸. 글자 영역은 테두리 안쪽에 두고, 테두리는 직접 그린다.
     * 줄 배치에서는 {@link #place(int, int, int)} 로 바깥 칸 크기를 준다.
     */
    public static class Field extends TextFieldWidget {
        public static final int H = 20;
        private final int outer;

        public Field(int w, Text name) {
            super(tr(), 0, 0, w, 10, name);
            this.outer = w;
            setDrawsBackground(false);
            setEditableColor(Theme.TEXT);
            setUneditableColor(Theme.TEXT_DISABLED);
        }

        /** 만들 때 정한 바깥 칸 너비 */
        public int outer() {
            return outer;
        }

        /** 바깥 칸(x, y, w, 20)에 맞춰 글자 영역을 놓는다 */
        public void place(int x, int y, int w) {
            setX(x + 5);
            setY(y + 6);
            setWidth(w - 10);
        }

        @Override
        public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            Theme.field(ctx, getX() - 5, getY() - 6, getWidth() + 10, H, isFocused());
            super.renderWidget(ctx, mouseX, mouseY, delta);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return visible && mouseX >= getX() - 5 && mouseX < getX() + getWidth() + 5
                    && mouseY >= getY() - 6 && mouseY < getY() - 6 + H;
        }
    }
}
