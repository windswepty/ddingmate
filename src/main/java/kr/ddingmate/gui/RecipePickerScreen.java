package kr.ddingmate.gui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.ui.Theme;
import kr.ddingmate.ui.Widgets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/** 재료 카운터 핀에 넣을 레시피를 검색해서 고르는 화면. 레시피 이름·분류·시설·재료 이름으로 찾을 수 있다. */
public class RecipePickerScreen extends Screen {

    private static final String ALL = "전체 시설";

    private final Screen parent;
    private final int pinIndex;
    private Widgets.Field search;
    private RecipeList list;
    private String query = "";
    private String facility = ALL;

    private int px, py, pw, ph;
    private int cx, cy, cw, ch;

    public RecipePickerScreen(Screen parent, int pinIndex) {
        super(Text.literal("핀 " + (pinIndex + 1) + " 레시피 고르기"));
        this.parent = parent;
        this.pinIndex = pinIndex;
    }

    @Override
    protected void init() {
        pw = Math.min(400, width - 16);
        ph = height - 12;
        px = (width - pw) / 2;
        py = 6;
        cx = px + 6;
        cy = py + DdingMateScreen.HEADER_H + 30;
        cw = pw - 12;
        ch = ph - (cy - py) - 32;

        int fieldW = cw - 132;
        search = new Widgets.Field(fieldW, Text.literal("검색"));
        search.setPlaceholder(Text.literal("검색: 라이프스톤, 구리 블록 …"));
        search.setMaxLength(50);
        search.setText(query);
        search.place(cx, py + DdingMateScreen.HEADER_H + 5, fieldW);
        search.setChangedListener(t -> {
            query = t;
            refill();
        });
        addDrawableChild(search);

        List<String> facilities = new ArrayList<>();
        facilities.add(ALL);
        facilities.addAll(RecipeBook.all().stream().map(r -> r.facility).collect(Collectors.toCollection(LinkedHashSet::new)));
        Widgets.Cycle<String> filter = new Widgets.Cycle<>(128, "", facilities, facility,
                f -> f.equals(ALL) ? ALL : f.replace(" 시설", ""), f -> {
                    facility = f;
                    refill();
                });
        filter.setX(cx + cw - 128);
        filter.setY(py + DdingMateScreen.HEADER_H + 5);
        filter.setTooltip(Tooltip.of(Text.literal("시설로 거르기 (오른쪽 클릭은 거꾸로)")));
        addDrawableChild(filter);

        list = addDrawableChild(new RecipeList(client, cw - 4, ch - 4, cy + 2, 24));
        list.setX(cx + 2);
        refill();
        setInitialFocus(search);

        Widgets.Button clear = new Widgets.Button(120, 20, Text.literal("핀 비우기"), false, b -> {
            pin().recipe = "";
            close();
        });
        clear.setX(px + 8);
        clear.setY(py + ph - 27);
        addDrawableChild(clear);
        Widgets.Button cancel = new Widgets.Button(120, 20, Text.literal("돌아가기"), true, b -> close());
        cancel.setX(px + pw - 128);
        cancel.setY(py + ph - 27);
        addDrawableChild(cancel);
    }

    private DdingMateConfig.Pin pin() {
        return DdingMate.config().counter.pins.get(pinIndex);
    }

    private void refill() {
        list.clear();
        String q = ItemKeys.normalize(query);
        for (RecipeBook.Recipe r : RecipeBook.all()) {
            if (!facility.equals(ALL) && !facility.equals(r.facility)) continue;
            if (!q.isEmpty() && !matches(r, q)) continue;
            list.add(new RecipeCard(r, list.getRowWidth()));
        }
    }

    static boolean matches(RecipeBook.Recipe r, String normalizedQuery) {
        if (ItemKeys.normalize(r.name).contains(normalizedQuery)) return true;
        if (ItemKeys.normalize(r.category).contains(normalizedQuery)) return true;
        if (ItemKeys.normalize(r.facility).contains(normalizedQuery)) return true;
        for (RecipeBook.Material m : r.materials) {
            if (ItemKeys.normalize(m.name).contains(normalizedQuery)) return true;
        }
        return false;
    }

    private void choose(RecipeBook.Recipe r) {
        DdingMateConfig.Pin p = pin();
        p.recipe = r.id;
        if (p.crafts < 1) p.crafts = 1;
        close();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        DdingMateScreen.drawFrame(ctx, textRenderer, px, py, pw, ph, "레시피 고르기");
        Theme.inset(ctx, cx, cy, cw, ch);
        String sub = "핀 " + (pinIndex + 1) + " · " + list.children().size() + "개";
        Theme.text(ctx, textRenderer, sub, px + (pw - Theme.width(textRenderer, sub)) / 2, py + ph - 20, Theme.TEXT_SOFT);
    }

    @Override
    public void close() {
        DdingMate.config().save();
        if (client != null) client.setScreen(parent);
    }

    /** 레시피 한 장: 재료 아이콘 + 이름 + 분류 꼬리표 */
    private class RecipeCard extends PressableWidget {
        private final RecipeBook.Recipe recipe;

        RecipeCard(RecipeBook.Recipe recipe, int w) {
            super(0, 0, w, 22, Text.literal(recipe.displayName()));
            this.recipe = recipe;
            String mats = recipe.materials.stream().map(m -> m.name + " " + m.count + "개")
                    .collect(Collectors.joining("\n"));
            setTooltip(Tooltip.of(Text.literal(recipe.facility + "\n" + mats)));
        }

        @Override
        public void onPress() {
            choose(recipe);
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            boolean current = recipe.id.equals(pin().recipe);
            Theme.row(ctx, getX(), getY(), width, height, isSelected());
            if (current) ctx.fill(getX() + 1, getY() + 2, getX() + 3, getY() + height - 2, Theme.BLUE);
            int x = getX() + 5;
            int shown = 0;
            for (RecipeBook.Material m : recipe.materials) {
                if (shown == 4) break;
                ItemStack icon = IconCache.forMaterial(m.name);
                if (icon != null) Theme.item(ctx, icon, x, getY() + 3, 16);
                else Theme.unknownIcon(ctx, textRenderer, x + 1, getY() + 4, 14);
                x += 15;
                shown++;
            }
            x = getX() + 5 + 4 * 15 + 4;
            String tag = recipe.category;
            int tagW = Theme.width(textRenderer, tag) + 10;
            int tagX = getX() + width - tagW - 4;
            Theme.tile(ctx, tagX, getY() + 4, tagW, 14, Theme.Tile.BLUE, false);
            Theme.text(ctx, textRenderer, tag, tagX + 5, getY() + 7, Theme.WHITE);
            String name = Theme.ellipsize(textRenderer, recipe.displayName(), tagX - 6 - x, true);
            Theme.boldText(ctx, textRenderer, name, x, getY() + 7, Theme.TEXT, false);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    private class RecipeList extends DdingMateScreen.ThemedList<Row> {
        RecipeList(MinecraftClient client, int width, int height, int y, int itemHeight) {
            super(client, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return width - 20;
        }

        void clear() {
            clearEntries();
            setScrollY(0);
        }

        void add(RecipeCard card) {
            addEntry(new Row(card));
        }
    }

    private class Row extends ElementListWidget.Entry<Row> {
        private final RecipeCard card;

        Row(RecipeCard card) {
            this.card = card;
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float tickDelta) {
            card.setX(x);
            card.setY(y);
            card.setWidth(entryWidth);
            card.render(ctx, mouseX, mouseY, tickDelta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(card);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(card);
        }
    }
}
