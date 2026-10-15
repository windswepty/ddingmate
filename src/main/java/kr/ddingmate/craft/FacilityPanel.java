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
import java.util.List;
import java.util.Map;

/**
 * 제작 시설 창 옆 판: 이 시설의 레시피 가운데 지금 보유한 재료(인벤토리 + 기록된 가방·저장소)로 만들 수 있는 것과
 * 몇 번 만들 수 있는지. 왼쪽 클릭은 비어 있는 핀에 넣기, 오른쪽 클릭은 그 핀 빼기.
 * 시설은 창의 레시피 칸으로 알아보고, 판에는 그 시설의 레시피 전체(위키 레시피 목록)를 보유 재료 순으로 보여 준다.
 * 창 위쪽 "레시피 고정" 칸(설명 "(쉬프트+우) 레시피 제거", 2026-10-15 실제)에 둔 아이템은 판에 따로 끌어오지 않는다.
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
        // 레시피 칸으로 어느 시설 창인지 알아본다(고정 칸도 시설을 알아보는 데만 쓴다)
        Map<String, Integer> facilities = new java.util.HashMap<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            CraftTimers.Reading r = CraftTimers.read(lore.lines());
            if (r == null || !r.recipe()) continue;
            String name = CraftTimers.cleanName(ItemKeys.stripGlyphs(s.getName().getString()));
            RecipeBook.Recipe recipe = RecipeBook.forWindowSlot(name, s.getCount());
            if (recipe != null) facilities.merge(recipe.facility, 1, Integer::sum);
        }
        String facility = facilities.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (facility == null) {
            entries = List.of();
            return;
        }
        Map<String, Integer> counts = MaterialCounter.availableCounts(client);
        List<Entry> out = new ArrayList<>();
        for (RecipeBook.Recipe r : RecipeBook.all()) {
            if (!r.facility.equals(facility) || r.materials.isEmpty()) continue;
            out.add(new Entry(r, MaterialCounter.craftable(counts, r), MaterialCounter.materialsReady(counts, r)));
        }
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
        String title = "보유 재료로 만들기";
        int w = Theme.boldWidth(tr, title) + 26;
        for (int i = 0; i < shown; i++) {
            Entry e = entries.get(i);
            w = Math.max(w, 13 + Theme.width(tr, e.recipe().displayName()) + 8 + Theme.boldWidth(tr, right(e)) + 14);
        }
        w = Math.max(132, Math.min(150, w));
        String foot = "왼쪽: 핀 넣기 · 오른쪽: 핀 빼기";
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

    /** 판 안을 눌렀으면 처리하고 true(창으로는 넘기지 않음). 왼쪽: 핀 넣기, 오른쪽: 핀 빼기 */
    public static boolean click(double mouseX, double mouseY, int button) {
        if (pw <= 0 || (button != 0 && button != 1)) return false;
        if (mouseX < px || mouseX >= px + pw || mouseY < py || mouseY >= py + ph) return false;
        int i = (int) Math.floor((mouseY - (rowsTop - 1)) / ROW);
        if (mouseY >= rowsTop - 1 && i >= 0 && i < shown) {
            if (button == 0) pin(entries.get(i).recipe());
            else unpin(entries.get(i).recipe());
        }
        return true;
    }

    /** 이 레시피가 들어 있는 핀을 비운다 */
    public static int unpin(RecipeBook.Recipe r) {
        int at = pinnedIndex(r);
        if (at < 0) {
            say(quoted(r, "은", "는") + " 핀에 없어요.");
            return -1;
        }
        DdingMateConfig.Pin pin = DdingMate.config().counter.pins.get(at);
        pin.recipe = "";
        pin.crafts = 1;
        pin.byOutput = false;
        DdingMate.config().save();
        say("핀 " + (at + 1) + "에서 " + quoted(r, "을", "를") + " 뺐어요.");
        return at;
    }

    /** "'리프톤 주괴'는" 처럼 받침에 맞는 조사를 붙인다(한글로 안 끝나면 "은(는)") */
    static String quoted(RecipeBook.Recipe r, String withFinal, String withoutFinal) {
        String n = r.displayName();
        char last = n.isEmpty() ? ' ' : n.charAt(n.length() - 1);
        String josa;
        if (last >= 0xAC00 && last <= 0xD7A3) josa = (last - 0xAC00) % 28 != 0 ? withFinal : withoutFinal;
        else josa = withFinal + "(" + withoutFinal + ")";
        return "'" + n + "'" + josa;
    }

    private static String lastSaid;
    private static long lastSaidAt;

    /** 같은 안내를 연달아 누르면 채팅이 쌓이지 않게 2초에 한 번만 */
    private static void say(String msg) {
        long now = System.currentTimeMillis();
        if (msg.equals(lastSaid) && now - lastSaidAt < 2000) return;
        lastSaid = msg;
        lastSaidAt = now;
        Notifier.chat(msg);
    }

    /** 비어 있는 핀에 넣는다. 이미 핀했거나 핀이 다 찼으면 알려만 준다 */
    public static int pin(RecipeBook.Recipe r) {
        DdingMateConfig.Counter cfg = DdingMate.config().counter;
        int already = pinnedIndex(r);
        if (already >= 0) {
            say(quoted(r, "은", "는") + " 이미 핀 " + (already + 1) + "에 있어요. 빼려면 오른쪽 클릭.");
            return already;
        }
        for (int i = 0; i < cfg.pins.size(); i++) {
            DdingMateConfig.Pin pin = cfg.pins.get(i);
            if (pin.recipe == null || pin.recipe.isEmpty() || RecipeBook.get(pin.recipe) == null) {
                pin.recipe = r.id;
                pin.crafts = 1;
                pin.byOutput = false;
                DdingMate.config().save();
                say("핀 " + (i + 1) + "에 " + quoted(r, "을", "를") + " 넣었어요. 재료 HUD 에서 모이는 걸 볼 수 있어요.");
                return i;
            }
        }
        say("핀 " + cfg.pins.size() + "개가 다 찼어요. 판에서 다른 레시피를 오른쪽 클릭하면 빠져요.");
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
