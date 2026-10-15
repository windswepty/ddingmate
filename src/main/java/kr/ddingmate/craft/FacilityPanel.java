package kr.ddingmate.craft;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.ui.SidePanel;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 제작 시설 창 옆 판: 이 창에 보이는 레시피 가운데 지금 가진 재료(인벤토리 + 기록된 가방·저장소)로 만들 수 있는 것과
 * 몇 번 만들 수 있는지. 줄을 누르면 비어 있는 핀에 그 레시피를 넣는다(레시피 고르기에서 직접 찾지 않아도 되게).
 * 창 칸은 읽기만 하고, 누르는 것은 띵메이트 판 안에서만 처리한다(서버로 아무것도 보내지 않음).
 */
public final class FacilityPanel {

    private FacilityPanel() {}

    private static final int ROW = 12;
    private static final int MAX_ROWS = 8;

    /** 판 한 줄: 레시피, 만들 수 있는 횟수, 다 모인 재료 종류 수 */
    public record Entry(RecipeBook.Recipe recipe, int craftable, int ready) {}

    private static List<Entry> entries = List.of();
    private static int shown;
    /** 마지막으로 그린 자리(누르기 판정) */
    private static int px, py, pw, ph, rowsTop;

    public static List<Entry> entries() {
        return entries;
    }

    /** 10틱마다: 열린 창의 레시피 칸을 읽고 만들 수 있는 횟수를 센다 */
    public static void scan(MinecraftClient client) {
        if (!DdingMate.config().counter.facilityPanel || client.player == null
                || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            entries = List.of();
            return;
        }
        Set<RecipeBook.Recipe> seen = new LinkedHashSet<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            CraftTimers.Reading r = CraftTimers.read(lore.lines());
            if (r == null || !r.recipe()) continue;
            String name = CraftTimers.cleanName(ItemKeys.stripGlyphs(s.getName().getString()));
            RecipeBook.Recipe recipe = RecipeBook.forWindowSlot(name, s.getCount());
            if (recipe != null) seen.add(recipe);
        }
        if (seen.isEmpty()) {
            entries = List.of();
            return;
        }
        Map<String, Integer> counts = MaterialCounter.availableCounts(client);
        List<Entry> out = new ArrayList<>();
        for (RecipeBook.Recipe r : seen) out.add(new Entry(r, MaterialCounter.craftable(counts, r), MaterialCounter.materialsReady(counts, r)));
        out.sort((a, b) -> a.craftable() != b.craftable() ? Integer.compare(b.craftable(), a.craftable())
                : Double.compare((double) b.ready() / b.recipe().materials.size(), (double) a.ready() / a.recipe().materials.size()));
        entries = out;
    }

    public static void render(DrawContext ctx, Screen screen, int mouseX, int mouseY) {
        pw = 0;
        if (entries.isEmpty() || !DdingMate.config().counter.facilityPanel) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        SidePanel.Palette p = SidePanel.WORKSHOP;
        shown = Math.min(MAX_ROWS, entries.size());
        String title = "가진 재료로 만들기";
        int w = Theme.boldWidth(tr, title) + 26;
        for (int i = 0; i < shown; i++) {
            Entry e = entries.get(i);
            w = Math.max(w, 13 + Theme.width(tr, e.recipe().displayName()) + 8 + Theme.boldWidth(tr, right(e)) + 14);
        }
        w = Math.max(132, Math.min(150, w));
        String foot = "누르면 빈 핀에 넣어요";
        int h = SidePanel.HEAD_H + 5 + shown * ROW + 4 + (entries.size() > shown ? 11 : 0) + 12;
        int[] at = SidePanel.place(screen.width, screen.height, w, h);
        int x = at[0], y = at[1];
        px = x;
        py = y;
        pw = w;
        ph = h;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        SidePanel.frame(ctx, tr, x, y, w, h, p, new ItemStack(Items.CRAFTING_TABLE), title);
        int cy = y + SidePanel.HEAD_H + 5;
        rowsTop = cy;
        for (int i = 0; i < shown; i++) {
            Entry e = entries.get(i);
            boolean hover = mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= cy - 1 && mouseY < cy - 1 + ROW;
            SidePanel.rowBg(ctx, x + 3, cy - 1, w - 6, ROW, p, i, hover);
            ItemStack icon = IconCache.forRecipe(e.recipe());
            if (icon != null) Theme.item(ctx, icon, x + 6, cy, 10);
            String r = right(e);
            int rw = Theme.boldWidth(tr, r);
            boolean pinned = pinnedIndex(e.recipe()) >= 0;
            String name = Theme.ellipsize(tr, (pinned ? "[핀] " : "") + e.recipe().displayName(), w - 6 - 13 - rw - 12);
            Theme.text(ctx, tr, name, x + 19, cy + 2, pinned ? p.accent() : e.craftable() > 0 ? p.ink() : p.soft());
            Theme.boldText(ctx, tr, r, x + w - 7 - rw, cy + 2, e.craftable() > 0 ? p.good() : p.soft(), false);
            cy += ROW;
        }
        if (entries.size() > shown) {
            Theme.text(ctx, tr, "그 밖에 " + (entries.size() - shown) + "개", x + 6, cy + 2, p.soft());
            cy += 11;
        }
        SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
        Theme.text(ctx, tr, foot, x + 6, cy + 5, p.soft());
        ctx.getMatrices().pop();
    }

    private static String right(Entry e) {
        return e.craftable() > 0 ? e.craftable() + "번" : "재료 " + e.ready() + "/" + e.recipe().materials.size();
    }

    /** 판 안을 눌렀으면 처리하고 true(창으로는 넘기지 않음) */
    public static boolean click(double mouseX, double mouseY, int button) {
        if (pw <= 0 || button != 0) return false;
        if (mouseX < px || mouseX >= px + pw || mouseY < py || mouseY >= py + ph) return false;
        int i = (int) Math.floor((mouseY - (rowsTop - 1)) / ROW);
        if (mouseY >= rowsTop - 1 && i >= 0 && i < shown) pin(entries.get(i).recipe());
        return true;
    }

    /** 비어 있는 핀에 넣는다. 이미 핀했거나 핀이 다 찼으면 알려만 준다 */
    public static int pin(RecipeBook.Recipe r) {
        DdingMateConfig.Counter cfg = DdingMate.config().counter;
        int already = pinnedIndex(r);
        if (already >= 0) {
            Notifier.chat("'" + r.displayName() + "' 은(는) 이미 핀 " + (already + 1) + "에 있어요.");
            return already;
        }
        for (int i = 0; i < cfg.pins.size(); i++) {
            DdingMateConfig.Pin pin = cfg.pins.get(i);
            if (pin.recipe == null || pin.recipe.isEmpty() || RecipeBook.get(pin.recipe) == null) {
                pin.recipe = r.id;
                pin.crafts = 1;
                pin.byOutput = false;
                DdingMate.config().save();
                Notifier.chat("핀 " + (i + 1) + "에 '" + r.displayName() + "' 을(를) 넣었어요. 재료 HUD 에서 모이는 걸 볼 수 있어요.");
                return i;
            }
        }
        Notifier.chat("핀 " + cfg.pins.size() + "개가 다 찼어요. /띵메이트 재료 탭에서 하나를 비우고 다시 눌러 주세요.");
        return -1;
    }

    private static int pinnedIndex(RecipeBook.Recipe r) {
        List<DdingMateConfig.Pin> pins = DdingMate.config().counter.pins;
        for (int i = 0; i < pins.size(); i++) if (r.id.equals(pins.get(i).recipe)) return i;
        return -1;
    }

    /** 시험용: i번째 줄 가운데 화면 좌표(아직 안 그렸으면 null) */
    public static int[] debugRow(int i) {
        return pw <= 0 ? null : new int[]{px + pw / 2, rowsTop - 1 + i * ROW + ROW / 2};
    }

    /** 시험용 */
    public static void debugEntries(List<Entry> list) {
        entries = list;
    }
}
