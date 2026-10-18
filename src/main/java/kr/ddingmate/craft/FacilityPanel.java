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

    /** 판 한 줄: 레시피, 만들 수 있는 횟수, 다 모인 재료 종류 수, 하나라도 가진 재료 종류 수 */
    public record Entry(RecipeBook.Recipe recipe, int craftable, int ready, int owned) {}

    private static List<Entry> entries = List.of();
    private static int shown;
    /** 마지막으로 그린 자리(누르기 판정) */
    private static int px, py, pw, ph, rowsTop;
    /** "그 밖에 N개"를 눌러 연 전체 목록 판 */
    private static boolean expanded;
    private static int scroll;
    private static int lx, ly, lw, lh, listTop, listRows, moreY;
    /**
     * 눌러야 할 칸 강조(0.3.2): 판 줄을 쉬프트(앉기 키)+좌클릭해서 연 레시피(재료 판과 함께 켜지고 꺼진다, 2026-10-18 사용자 요청).
     * 창에 그 레시피 칸이 보이면 그 칸, 아니면 그 레시피 분류의 탭 칸을 테두리로 표시한다. 누르는 건 사용자가 직접
     */
    private static String lastFacility;
    /** 지금 창: 레시피 id → 칸 번호, 분류 이름 → 탭 칸 번호 */
    private static Map<String, Integer> recipeSlots = Map.of();
    private static Map<String, Integer> tabSlots = Map.of();
    private static final java.util.Set<String> TAB_LOGGED = new java.util.HashSet<>();

    /** 쉬프트+클릭으로 연 레시피(아래 판에 재료를 보여 줌). 쉬프트+오른쪽 클릭으로 닫는다 */
    private static RecipeBook.Recipe detail;
    /** 마지막으로 센 보유 개수(레시피 판에 씀) */
    private static Map<String, Integer> lastCounts = Map.of();

    public static List<Entry> entries() {
        return entries;
    }

    /** 10틱마다: 열린 창의 레시피 칸을 읽고 만들 수 있는 횟수를 센다 */
    public static void scan(MinecraftClient client) {
        if (!DdingMate.config().counter.facilityPanel || client.player == null
                || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            entries = List.of();
            recipeSlots = Map.of();
            tabSlots = Map.of();
            visible = Map.of();
            if (client.currentScreen == null) {
                detail = null; // 창을 닫으면 강조·재료 판도 닫는다
                lastFacility = null;
            }
            return;
        }
        // 레시피 칸으로 어느 시설 창인지 알아본다(고정 칸도 시설을 알아보는 데만 쓴다)
        Map<String, Integer> facilities = new java.util.HashMap<>();
        Map<String, Integer> slotsNow = new java.util.HashMap<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            CraftTimers.Reading r = CraftTimers.read(lore.lines());
            if (r == null || !r.recipe()) continue;
            String name = CraftTimers.cleanName(ItemKeys.stripGlyphs(s.getName().getString()));
            RecipeBook.Recipe recipe = RecipeBook.forWindowSlot(name, s.getCount());
            if (recipe != null) {
                facilities.merge(recipe.facility, 1, Integer::sum);
                slotsNow.putIfAbsent(recipe.id, slot.id);
            }
        }
        String facility = facilities.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (facility == null) {
            entries = List.of();
            detail = null;
            visible = Map.of();
            return;
        }
        if (!facility.equals(lastFacility)) {
            lastFacility = facility;
            detail = null; // 다른 시설 창이면 지난 강조는 닫는다
        }
        learn(screen, facility, slotsNow);
        noteLayout(facility);
        recipeSlots = slotsNow;
        tabSlots = Map.of();
        Map<String, Integer> counts = MaterialCounter.availableCounts(client);
        lastCounts = counts;
        List<Entry> out = new ArrayList<>();
        for (RecipeBook.Recipe r : RecipeBook.all()) {
            if (!r.facility.equals(facility) || r.materials.isEmpty()) continue;
            int owned = 0;
            for (RecipeBook.Material m : r.materials) if (MaterialCounter.have(counts, m.name) > 0) owned++;
            // 재료를 하나도 안 가진 레시피는 추천하지 않는다
            if (owned == 0) continue;
            out.add(new Entry(r, MaterialCounter.craftable(counts, r), MaterialCounter.materialsReady(counts, r), owned));
        }
        out.sort((a, b) -> {
            if (a.craftable() != b.craftable()) return Integer.compare(b.craftable(), a.craftable());
            int c = Double.compare((double) b.ready() / b.recipe().materials.size(), (double) a.ready() / a.recipe().materials.size());
            return c != 0 ? c : Double.compare((double) b.owned() / b.recipe().materials.size(), (double) a.owned() / a.recipe().materials.size());
        });
        entries = out;
        if (out.size() <= MAX_ROWS) expanded = false;
    }

    public static void render(DrawContext ctx, Screen screen, int mouseX, int mouseY) {
        pw = 0;
        tip = null;
        if (detail == null) detailRect = new int[4];
        if (entries.isEmpty() || !DdingMate.config().counter.facilityPanel) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        SidePanel.Palette p = SidePanel.WORKSHOP;
        shown = Math.min(MAX_ROWS, entries.size());
        String title = "보유 재료로 만들기";
        int w = Theme.boldWidth(tr, title) + 26;
        for (int i = 0; i < shown; i++) {
            Entry e = entries.get(i);
            int pinW = pinnedIndex(e.recipe()) >= 0 ? Theme.width(tr, "[핀] ") : 0;
            w = Math.max(w, 13 + pinW + Theme.itemNameWidth(tr, e.recipe().displayName()) + 8 + Theme.boldWidth(tr, right(e)) + 14);
        }
        w = Math.max(132, Math.min(SidePanel.maxWidth(screen.width), w));
        String foot = "클릭: 핀 넣기 / 한 번 더: 빼기";
        String foot2 = DdingMate.config().counter.facilityHighlight ? "쉬프트+클릭: 재료·칸 보기 / 닫기" : "쉬프트+클릭: 레시피 보기 / 닫기";
        int h = SidePanel.HEAD_H + 5 + shown * ROW + 4 + (entries.size() > shown ? 11 : 0) + 12 + 10;
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
            Theme.Drawn d = Theme.itemName(ctx, tr, pinned ? "[핀] " : "", e.recipe().displayName(), x + 19, cy + 2,
                    w - 6 - 13 - rw - 12, pinned ? p.accent() : e.craftable() > 0 ? p.ink() : p.soft());
            if (hover && d.cut()) tip = e.recipe().displayName();
            Theme.boldText(ctx, tr, r, x + w - 7 - rw, cy + 2, e.craftable() > 0 ? p.good() : p.soft(), false);
            cy += ROW;
        }
        moreY = -1;
        if (entries.size() > shown) {
            moreY = cy;
            boolean hover = mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= cy && mouseY < cy + 11;
            String more = expanded ? "전체 목록 닫기" : "그 밖에 " + (entries.size() - shown) + "개 · 눌러서 모두 보기";
            Theme.text(ctx, tr, Theme.ellipsize(tr, more, w - 12), x + 6, cy + 2, hover ? p.ink() : p.accent());
            if (hover) ctx.fill(x + 6, cy + 10, x + 6 + Math.min(w - 12, Theme.width(tr, more)), cy + 11, p.accent());
            cy += 11;
        }
        SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
        Theme.text(ctx, tr, Theme.ellipsize(tr, foot, w - 12), x + 6, cy + 5, p.soft());
        Theme.text(ctx, tr, Theme.ellipsize(tr, foot2, w - 12), x + 6, cy + 15, p.soft());
        ctx.getMatrices().pop();
        lw = 0;
        if (expanded) renderList(ctx, screen, tr, p, mouseX, mouseY);
        if (detail != null) {
            int dh = detailHeight(detail);
            int dx = x, dy = y + h + 3, dw = w;
            if (dy + dh > screen.height - 2) {
                if (y - dh - 3 >= 2) {
                    dy = y - dh - 3;
                } else {
                    // 위아래 어디에도 안 들어가면 상자 창 왼쪽 빈자리에(작은 판은 옮기지 않는다: 줄이 커서 밑에서 움직이면
                    // 같은 자리를 다시 쉬프트+클릭해도 닫히지 않고 다른 레시피가 열린다). 전체 목록 판과 겹치면 레시피 판이 위,
                    // 그 자리의 클릭·툴팁은 레시피 판 몫이다
                    int room = screen.width / 2 - 92 - 6;
                    dw = Math.max(110, Math.min(w, room));
                    dx = Math.max(2, screen.width / 2 - 92 - 4 - dw);
                    dy = Math.max(2, Math.min(y, screen.height - 2 - dh));
                }
            }
            renderDetail(ctx, screen, tr, p, dx, dy, dw);
        }
        highlight(ctx, screen);
        // 이름이 … 로 잘린 줄에 마우스를 올리면 서버 이름 그대로 보여 준다
        if (tip != null) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 310);
            ctx.drawTooltip(tr, net.minecraft.text.Text.literal(tip), mouseX, mouseY);
            ctx.getMatrices().pop();
        }
    }

    /** 이번 프레임에 마우스를 올린 잘린 이름(전체) */
    private static String tip;

    // ---------------------------------------------------------------- 눌러야 할 칸 강조

    /** 이 시설 레시피의 분류 이름이 칸 이름에 들어간 칸 = 아래 탭. 처음 보는 탭 이름은 로그에 남긴다(서버 표기 확인용) */
    private static Map<String, Integer> findTabs(HandledScreen<?> screen, String facility) {
        java.util.Set<String> cats = new java.util.HashSet<>();
        for (RecipeBook.Recipe r : RecipeBook.all()) if (r.facility.equals(facility) && r.category != null) cats.add(r.category);
        Map<String, Integer> out = new java.util.HashMap<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            LoreComponent tabLore = slot.getStack().get(DataComponentTypes.LORE);
            if (tabLore != null && CraftTimers.read(tabLore.lines()) != null) continue;
            String n = ItemKeys.normalize(ItemKeys.stripGlyphs(slot.getStack().getName().getString()));
            if (n.isEmpty()) continue;
            for (String c : cats) {
                String nc = ItemKeys.normalize(c);
                if (!nc.isEmpty() && n.contains(nc) && !out.containsKey(c)) {
                    out.put(c, slot.id);
                    if (TAB_LOGGED.add(facility + "/" + c)) DdingMate.LOG.info("시설 탭 읽음: {} · {} → 칸 {}", facility, c, slot.id);
                }
            }
        }
        return out;
    }

    /** 강조할 레시피 id(쉬프트+클릭으로 연 레시피). 강조를 꺼 두었으면 null */
    private static String focus() {
        return DdingMate.config().counter.facilityHighlight && detail != null ? detail.id : null;
    }

    /** 쉬프트 또는 앉기 키(다른 키로 바꿔 쓰는 사람도 있어서) */
    static boolean sneakDown() {
        if (net.minecraft.client.gui.screen.Screen.hasShiftDown()) return true;
        MinecraftClient c = MinecraftClient.getInstance();
        net.minecraft.client.util.InputUtil.Key k = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(c.options.sneakKey);
        return k.getCategory() == net.minecraft.client.util.InputUtil.Type.KEYSYM
                && net.minecraft.client.util.InputUtil.isKeyPressed(c.getWindow().getHandle(), k.getCode());
    }

    // ---- 경로 배우기: 사용자가 칸을 누른 뒤 새로 나타난 레시피·칸은 "그 칸을 누르면 나온다"로 기억한다
    //      (서버 창의 칸 이름을 몰라도, 한 번 직접 찾아간 길은 다음부터 반짝여 알려 줄 수 있다. 서버로는 아무것도 보내지 않음)

    /** 지금 창에 보이는 것: 노드(레시피 "r:id" 또는 칸 "s:번호:이름:모양") → 칸 번호 */
    private static Map<String, Integer> visible = Map.of();
    /** 시설 → (노드 → 그 노드를 나타나게 한 칸 노드) */
    private static Map<String, Map<String, String>> parents;
    private static String clickedNode;
    private static long clickedAt;

    private static String slotNode(Slot slot) {
        ItemStack s = slot.getStack();
        String name = ItemKeys.normalize(ItemKeys.stripGlyphs(s.getName().getString()));
        String model = "";
        net.minecraft.component.type.CustomModelDataComponent cmd = s.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd != null && !cmd.floats().isEmpty()) model = String.valueOf(cmd.floats().get(0).intValue());
        var im = s.getComponentChanges().get(DataComponentTypes.ITEM_MODEL);
        if (im != null && im.isPresent()) model += im.get().toString();
        return "s:" + slot.id + ":" + name + ":" + model;
    }

    private static void learn(HandledScreen<?> screen, String facility, Map<String, Integer> recipesNow) {
        Map<String, Integer> now = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> e : recipesNow.entrySet()) now.put("r:" + e.getKey(), e.getValue());
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            if (recipesNow.containsValue(slot.id)) continue;
            now.put(slotNode(slot), slot.id);
        }
        // 누른 지 3초 안에 새로 나타난 것들은 그 칸을 눌러서 나온 것
        if (clickedNode != null && System.currentTimeMillis() - clickedAt < 3000 && !visible.isEmpty()) {
            Map<String, String> map = parents().computeIfAbsent(facility, k -> new java.util.HashMap<>());
            boolean changed = false;
            for (String n : now.keySet()) {
                if (visible.containsKey(n) || n.equals(clickedNode)) continue;
                if (n.startsWith("r:") && clickedNode.startsWith("s:" + LIST_BUTTON + ":")) continue; // 목록 첫 탭은 규칙으로 안다
                if (!clickedNode.equals(map.put(n, clickedNode))) {
                    changed = true;
                    if (n.startsWith("r:")) DdingMate.LOG.info("시설 경로 기억: {} ← {}", n, clickedNode);
                }
            }
            if (changed) saveParents();
        }
        if (!now.keySet().equals(visible.keySet()) && clickedNode != null && System.currentTimeMillis() - clickedAt < 3000) {
            clickedNode = null; // 한 번 누른 것은 한 번만 배운다
        }
        visible = now;
    }

    /** 창 칸을 눌렀을 때(띵메이트 판 바깥): 어떤 칸을 눌렀는지만 기억한다 */
    private static void noteSlotClick(double mouseX, double mouseY) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (lastFacility == null || !(c.currentScreen instanceof HandledScreen<?> hs)) return;
        int ox = ((kr.ddingmate.mixin.HandledScreenAccessor) hs).ddingmate$x();
        int oy = ((kr.ddingmate.mixin.HandledScreenAccessor) hs).ddingmate$y();
        for (Slot slot : hs.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            int sx = ox + slot.x, sy = oy + slot.y;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                clickedNode = recipeSlots.containsValue(slot.id) ? null : slotNode(slot);
                clickedAt = System.currentTimeMillis();
                if (clickedNode != null) DdingMate.LOG.info("시설 창 누름: {} · {}", lastFacility, clickedNode);
                return;
            }
        }
    }

    // ---- 시설 창 구조(2026-10-18 실제, 9개 시설에서 누른 기록으로 확인):
    //  첫 화면: 왼쪽 위 0번 칸 = 레시피 목록(이름 없는 투명 종이), 위 줄은 '레시피 고정' 칸
    //  목록 화면: 0번 = 뒤로, 8번 = ↑, 줄마다 9·18·27·36번 재료 → 17·26·35·44번 결과(한 번에 4줄),
    //            맨 아래 45~50번 = 탭(이름 없는 투명 종이, 그림은 창 배경), 53번 = ↓
    //  탭 순서 = 그 시설 레시피 목록(recipes.json, 위키 표 순서)의 분류 순서: 첫 분류가 45번, 다음이 46번 …
    //  (연금: 정수·핵·에센스·결정·엘릭서·영약 → 45~50, 채광물: 연료·주괴·코룸·리프톤·세렌트·형광 큐브 → 45~50)
    static final int LIST_BUTTON = 0, UP_ARROW = 8, DOWN_ARROW = 53, FIRST_TAB = 45, MAX_TABS = 6;

    /** 레시피 목록·탭 창 모양을 실제로 확인한 시설(2026-10-18 사용자가 열어 본 9곳). 나머지는 그 창에서 목록을 열면 확인한다 */
    static final java.util.Set<String> VERIFIED_LAYOUT = java.util.Set.of("강화 제작 시설", "도구 제작 시설", "연금 제작 시설", "영혼 가공 시설",
            "요리 제작 시설", "정밀 연금 제작 시설", "채광물 가공 시설", "해양 제작 시설", "황금 요리 제작 시설");

    /** 이 시설의 창이 위 모양(0번 목록 단추, 45~50번 탭)인 걸 알고 있나 */
    static boolean layoutKnown(String facility) {
        if (VERIFIED_LAYOUT.contains(facility)) return true;
        Map<String, String> seen = parents().get(LAYOUT_KEY);
        return seen != null && seen.containsKey(facility);
    }

    private static final String LAYOUT_KEY = "_목록 창 모양 확인";

    /** 목록 화면(45번 탭이 이름 없는 단추)을 본 시설은 모양을 확인한 것으로 기억 */
    private static void noteLayout(String facility) {
        if (layoutKnown(facility) || !blankButton(FIRST_TAB) || !blankButton(LIST_BUTTON)) return;
        parents().computeIfAbsent(LAYOUT_KEY, k -> new java.util.HashMap<>()).put(facility, "list");
        saveParents();
        DdingMate.LOG.info("시설 목록 창 모양 확인: {}", facility);
    }

    /**
     * 처음부터 끝까지 길: "레시피 목록 → 3번째 탭(영혼 계약서) → 세르칸 큐브". 탭 안의 줄 순서는 레시피 목록 순서와 달라서
     * (2026-10-18 실제: 세르칸 큐브가 목록상 5번째인데 첫 쪽 4번째 줄) 몇 번째 줄인지는 적지 않는다. 모양을 모르는 시설이면 null
     */
    static String fullPath(RecipeBook.Recipe r) {
        int tab = tabOf(r);
        if (tab < 0 || !layoutKnown(r.facility)) return null;
        return "레시피 목록 → " + (tab - FIRST_TAB + 1) + "번째 탭(" + r.category + ") → " + r.name;
    }

    /** 이 레시피가 있는 탭 칸 번호(시설의 분류 순서로). 모르면 -1 */
    static int tabOf(RecipeBook.Recipe r) {
        if (r == null || r.category == null) return -1;
        List<String> cats = new ArrayList<>();
        for (RecipeBook.Recipe x : RecipeBook.all()) if (x.facility.equals(r.facility) && x.category != null && !cats.contains(x.category)) cats.add(x.category);
        int i = cats.indexOf(r.category);
        return i < 0 || i >= MAX_TABS ? -1 : FIRST_TAB + i;
    }

    /** 지금 창의 그 칸이 이름 없는 단추(투명 종이)인가 — 레시피 목록 단추·탭·화살표 */
    private static boolean blankButton(int slotId) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (!(c.currentScreen instanceof HandledScreen<?> hs) || slotId < 0 || slotId >= hs.getScreenHandler().slots.size()) return false;
        Slot slot = hs.getScreenHandler().slots.get(slotId);
        if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) return false;
        return ItemKeys.normalize(ItemKeys.stripGlyphs(slot.getStack().getName().getString())).isEmpty();
    }

    /** 규칙으로 찾은 다음 칸: 첫 화면이면 레시피 목록, 목록 화면이면 그 탭, 맞는 탭이면 ↓·↑. 모르면 -1 */
    private static int ruleSlot(RecipeBook.Recipe r) {
        int tab = tabOf(r);
        if (tab < 0 || !layoutKnown(r.facility)) return -1;
        boolean listView = blankButton(FIRST_TAB);
        if (!listView) return blankButton(LIST_BUTTON) ? LIST_BUTTON : -1;
        // 지금 보이는 레시피가 같은 분류면 맞는 탭에 있다 → 위아래로 넘긴다
        boolean sameTab = false;
        for (String id : recipeSlots.keySet()) {
            RecipeBook.Recipe v = RecipeBook.get(id);
            if (v != null && v.facility.equals(r.facility) && r.category.equals(v.category)) sameTab = true;
        }
        if (!sameTab) return blankButton(tab) ? tab : -1;
        // 맞는 탭인데 안 보이면 다른 쪽: 줄 순서를 몰라 방향은 ↓(끝까지 내려가면 ↑)
        if (blankButton(DOWN_ARROW)) return DOWN_ARROW;
        return blankButton(UP_ARROW) ? UP_ARROW : -1;
    }

    /** 강조할 칸: 레시피가 보이면 그 칸, 아니면 시설 창 구조 규칙, 아니면 기억한 길, 아니면 분류 이름 탭. 없으면 -1 */
    private static int targetSlot(String recipeId) {
        Integer direct = recipeSlots.get(recipeId);
        if (direct != null) return direct;
        int rule = ruleSlot(RecipeBook.get(recipeId));
        if (rule >= 0) return rule;
        Map<String, String> map = lastFacility == null ? null : parents().get(lastFacility);
        String node = map == null ? null : map.get("r:" + recipeId);
        for (int depth = 0; node != null && depth < 6; depth++) {
            Integer at = visible.get(node);
            if (at != null) return at;
            node = map.get(node);
        }
        return -1;
    }

    private static Map<String, Map<String, String>> parents() {
        if (parents == null) {
            parents = new java.util.HashMap<>();
            java.nio.file.Path f = pathsFile();
            if (java.nio.file.Files.exists(f)) {
                try (java.io.Reader r = java.nio.file.Files.newBufferedReader(f, java.nio.charset.StandardCharsets.UTF_8)) {
                    Map<String, Map<String, String>> read = new com.google.gson.Gson().fromJson(r,
                            new com.google.gson.reflect.TypeToken<Map<String, Map<String, String>>>() {}.getType());
                    if (read != null) read.forEach((k, v) -> parents.put(k, new java.util.HashMap<>(v)));
                } catch (Exception e) {
                    DdingMate.LOG.warn("시설 경로 기록을 읽지 못했어요", e);
                }
            }
        }
        return parents;
    }

    private static java.nio.file.Path pathsFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ddingmate-facility-paths.json");
    }

    private static void saveParents() {
        try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(pathsFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            new com.google.gson.Gson().toJson(parents(), w);
        } catch (Exception e) {
            DdingMate.LOG.warn("시설 경로 기록을 저장하지 못했어요", e);
        }
    }

    /** 강조할 레시피가 창에 안 보이면 어디로 가야 하는지 한 줄 */
    private static String hint() {
        String focusId = focus();
        if (focusId == null || recipeSlots.containsKey(focusId)) return null;
        int at = targetSlot(focusId);
        if (at == LIST_BUTTON && blankButton(LIST_BUTTON) && !blankButton(FIRST_TAB)) return "지금: 왼쪽 위 레시피 목록";
        if (at >= FIRST_TAB && at < FIRST_TAB + MAX_TABS) return "지금: 아래 " + (at - FIRST_TAB + 1) + "번째 탭";
        if (at == DOWN_ARROW || at == UP_ARROW) return "지금: 다른 쪽(↓↑로 넘겨요)";
        if (at >= 0) return "지금: 반짝이는 칸";
        return "처음엔 직접 찾아 주세요(길은 기억해요)";
    }

    /** 시설 창의 그 칸(없으면 탭)에 반짝이는 테두리 */
    private static void highlight(DrawContext ctx, Screen screen) {
        String focusId = focus();
        if (focusId == null || !(screen instanceof HandledScreen<?> hs)) return;
        int slotId = targetSlot(focusId);
        if (slotId < 0 || slotId >= hs.getScreenHandler().slots.size()) return;
        Slot slot = hs.getScreenHandler().slots.get(slotId);
        int sx = ((kr.ddingmate.mixin.HandledScreenAccessor) hs).ddingmate$x() + slot.x;
        int sy = ((kr.ddingmate.mixin.HandledScreenAccessor) hs).ddingmate$y() + slot.y;
        // 0.8초마다 밝아졌다 어두워진다
        double phase = (net.minecraft.util.Util.getMeasuringTimeMs() % 800) / 800.0;
        int a = (int) (140 + 115 * Math.abs(Math.sin(phase * Math.PI)));
        int c = (a << 24) | 0xF2B531;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 350);
        ctx.fill(sx - 2, sy - 2, sx + 18, sy, c);
        ctx.fill(sx - 2, sy + 16, sx + 18, sy + 18, c);
        ctx.fill(sx - 2, sy, sx, sy + 16, c);
        ctx.fill(sx + 16, sy, sx + 18, sy + 16, c);
        ctx.fill(sx, sy, sx + 16, sy + 16, (a / 4 << 24) | 0xF2B531);
        ctx.getMatrices().pop();
    }

    /** 시험용 */
    public static String debugFocus() {
        return focus();
    }

    public static Map<String, Integer> debugRecipeSlots() {
        return recipeSlots;
    }

    /** 시험용: 이 칸을 누른 것처럼(경로 배우기) */
    public static void debugClickSlot(Slot slot) {
        clickedNode = slotNode(slot);
        clickedAt = System.currentTimeMillis();
    }

    public static String debugFullPath(String recipeId) {
        return fullPath(RecipeBook.get(recipeId));
    }

    public static int debugTabOf(String recipeId) {
        return tabOf(RecipeBook.get(recipeId));
    }

    public static int debugTargetSlot() {
        String f = focus();
        return f == null ? -1 : targetSlot(f);
    }

    public static void debugForgetPaths() {
        parents = new java.util.HashMap<>();
        saveParents();
        visible = Map.of();
    }

    public static String debugHint() {
        return hint();
    }

    /** 쉬프트+클릭한 레시피의 재료: 판 바로 아래(자리가 없으면 위)에 재료마다 보유/필요 */
    /** 마지막으로 그린 레시피 판 안인가 */
    private static boolean inDetail(double mx, double my) {
        int[] d = detailRect;
        return detail != null && d[2] > 0 && mx >= d[0] && mx < d[0] + d[2] && my >= d[1] && my < d[1] + d[3];
    }

    private static int detailHeight(RecipeBook.Recipe r) {
        boolean path = DdingMate.config().counter.facilityHighlight && fullPath(r) != null;
        return SidePanel.HEAD_H + 5 + r.materials.size() * ROW + 4 + 12 + 4 + (hint() != null ? 10 : 0) + (path ? 30 : 0);
    }

    /** 시험용: 마지막으로 그린 레시피 판 자리 {x, y, w, h} */
    public static int[] debugDetailRect() {
        return detailRect.clone();
    }

    private static int[] detailRect = new int[4];

    /** 시험용: 마지막으로 그린 작은 판 자리 {x, y, w, h} */
    public static int[] debugPanelRect() {
        return new int[]{px, py, pw, ph};
    }

    private static void renderDetail(DrawContext ctx, Screen screen, TextRenderer tr, SidePanel.Palette p, int x, int y, int w) {
        RecipeBook.Recipe r = detail;
        int h = detailHeight(r);
        detailRect = new int[]{x, y, w, h};
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        ItemStack out = IconCache.forRecipe(r);
        SidePanel.frame(ctx, tr, x, y, w, h, p, out == null ? new ItemStack(Items.CRAFTING_TABLE) : out,
                Theme.ellipsize(tr, Theme.compactGrade(r.displayName()), w - 26, true));
        int cy = y + SidePanel.HEAD_H + 5;
        int i = 0;
        for (RecipeBook.Material m : r.materials) {
            int have = MaterialCounter.have(lastCounts, m.name);
            SidePanel.rowBg(ctx, x + 3, cy - 1, w - 6, ROW, p, i++, false);
            ItemStack icon = IconCache.forMaterial(m.name);
            if (icon != null) Theme.item(ctx, icon, x + 6, cy, 10);
            String right = have + "/" + m.count;
            int rw = Theme.boldWidth(tr, right);
            Theme.text(ctx, tr, Theme.ellipsize(tr, m.name, w - 6 - 13 - rw - 12), x + 19, cy + 2, have > 0 ? p.ink() : p.soft());
            Theme.boldText(ctx, tr, right, x + w - 7 - rw, cy + 2, have >= m.count ? p.good() : have > 0 ? p.accent() : p.bad(), false);
            cy += ROW;
        }
        SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
        int can = MaterialCounter.craftable(lastCounts, r);
        String sum = can > 0 ? "지금 " + can + "번 만들 수 있어요" : "재료가 모자라요";
        Theme.text(ctx, tr, Theme.ellipsize(tr, sum, w - 12), x + 6, cy + 6, can > 0 ? p.good() : p.soft());
        int ly = cy + 16;
        String path = DdingMate.config().counter.facilityHighlight ? fullPath(r) : null;
        if (path != null) {
            // 좁은 판에 다 보이게 한 단계씩 한 줄: "길: 레시피 목록" / "→ 3번째 탭(영혼 계약서)" / "→ 정복의 영혼 계약서"
            String[] steps = path.split(" → ");
            for (int k = 0; k < steps.length; k++) {
                Theme.text(ctx, tr, Theme.ellipsize(tr, (k == 0 ? "길: " : "→ ") + steps[k], w - 12), x + 6, ly, p.ink());
                ly += 10;
            }
        }
        String hint = hint();
        if (hint != null) Theme.text(ctx, tr, Theme.ellipsize(tr, hint, w - 12), x + 6, ly, Theme.GOLD_ON_LIGHT);
        ctx.getMatrices().pop();
    }

    /** 쉬프트를 누른 채 누르면 레시피 판 열기(왼쪽)/닫기(오른쪽), 아니면 핀 넣기/빼기 */
    private static void act(RecipeBook.Recipe r, int button) {
        if (sneakDown()) {
            // 쉬프트(앉기)+클릭: 레시피 판 열기 + 시설 창에서 눌러야 할 칸 강조, 같은 레시피를 한 번 더 누르면 닫기(쉬프트+우클릭도 닫기)
            detail = button == 0 && detail != r ? r : null;
            return;
        }
        // 클릭: 핀 넣기, 이미 핀이면 빼기(우클릭은 늘 빼기)
        if (button == 0 && pinnedIndex(r) < 0) pin(r);
        else unpin(r);
    }

    /** 시험용: 쉬프트 없이 / 쉬프트 누르고 누른 것처럼 */
    public static void debugAct(RecipeBook.Recipe r, int button, boolean shift) {
        if (shift) detail = button == 0 && detail != r ? r : null;
        else if (button == 0 && pinnedIndex(r) < 0) pin(r);
        else unpin(r);
    }

    /** 전체 목록 판: 창 왼쪽에 크게. 휠로 넘기고, 줄은 작은 판과 똑같이 왼쪽 클릭 핀 넣기 · 오른쪽 클릭 핀 빼기 */
    private static void renderList(DrawContext ctx, Screen screen, TextRenderer tr, SidePanel.Palette p, int mouseX, int mouseY) {
        int mid = screen.width / 2;
        int w = Math.max(132, Math.min(200, mid - 92 - 6));
        int x = Math.max(2, mid - 92 - 4 - w);
        int y = 6;
        int h = screen.height - 12;
        String title = "만들 수 있는 레시피 " + entries.size() + "개";
        listRows = Math.max(1, (h - SidePanel.HEAD_H - 5 - 16) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - listRows)));
        lx = x;
        ly = y;
        lw = w;
        lh = h;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        SidePanel.frame(ctx, tr, x, y, w, h, p, new ItemStack(Items.CRAFTING_TABLE), title);
        // 닫기(×)
        boolean hoverX = mouseX >= x + w - 14 && mouseX < x + w - 2 && mouseY >= y + 2 && mouseY < y + SidePanel.HEAD_H;
        Theme.boldText(ctx, tr, "×", x + w - 11, y + 4, hoverX ? Theme.GOLD : p.headerText(), false);
        int cy = y + SidePanel.HEAD_H + 5;
        listTop = cy;
        for (int i = scroll; i < Math.min(entries.size(), scroll + listRows); i++) {
            Entry e = entries.get(i);
            boolean hover = mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= cy - 1 && mouseY < cy - 1 + ROW
                    && !inDetail(mouseX, mouseY);
            SidePanel.rowBg(ctx, x + 3, cy - 1, w - 6, ROW, p, i, hover);
            ItemStack icon = IconCache.forRecipe(e.recipe());
            if (icon != null) Theme.item(ctx, icon, x + 6, cy, 10);
            String r = right(e);
            int rw = Theme.boldWidth(tr, r);
            boolean pinned = pinnedIndex(e.recipe()) >= 0;
            Theme.Drawn d = Theme.itemName(ctx, tr, pinned ? "[핀] " : "", e.recipe().displayName(), x + 19, cy + 2,
                    w - 6 - 13 - rw - 12, pinned ? p.accent() : e.craftable() > 0 ? p.ink() : p.soft());
            if (hover && d.cut()) tip = e.recipe().displayName();
            Theme.boldText(ctx, tr, r, x + w - 7 - rw, cy + 2, e.craftable() > 0 ? p.good() : p.soft(), false);
            cy += ROW;
        }
        // 넘길 수 있으면 오른쪽에 위치 막대
        if (entries.size() > listRows) {
            int trackTop = listTop - 1, trackH = listRows * ROW;
            int th = Math.max(8, trackH * listRows / entries.size());
            int ty = trackTop + (trackH - th) * scroll / Math.max(1, entries.size() - listRows);
            ctx.fill(x + w - 3, trackTop, x + w - 2, trackTop + trackH, Theme.withAlpha(p.soft(), 80));
            ctx.fill(x + w - 3, ty, x + w - 2, ty + th, p.accent());
        }
        Theme.text(ctx, tr, entries.size() > listRows ? "휠: 넘기기 · ×: 닫기" : "×: 닫기", x + 6, y + h - 12, p.soft());
        ctx.getMatrices().pop();
    }

    /** 전체 목록 판 위에서 휠을 굴렸으면 넘기고 true */
    public static boolean scrolled(double mouseX, double mouseY, double vertical) {
        if (!expanded || lw <= 0) return false;
        if (mouseX < lx || mouseX >= lx + lw || mouseY < ly || mouseY >= ly + lh) return false;
        scroll = Math.max(0, Math.min(Math.max(0, entries.size() - listRows), scroll + (vertical > 0 ? -3 : 3)));
        return true;
    }

    private static String right(Entry e) {
        return e.craftable() > 0 ? e.craftable() + "번" : "재료 " + e.ready() + "/" + e.recipe().materials.size();
    }

    /** 판 안을 눌렀으면 처리하고 true(창으로는 넘기지 않음). 왼쪽: 핀 넣기, 오른쪽: 핀 빼기 */
    public static boolean click(double mouseX, double mouseY, int button) {
        boolean ours = clickPanel(mouseX, mouseY, button);
        if (!ours && button == 0) noteSlotClick(mouseX, mouseY);
        return ours;
    }

    private static boolean clickPanel(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) return false;
        if (inDetail(mouseX, mouseY)) {
            if (sneakDown()) detail = null;
            return true;
        }
        // 전체 목록 판
        if (expanded && lw > 0 && mouseX >= lx && mouseX < lx + lw && mouseY >= ly && mouseY < ly + lh) {
            if (mouseY < ly + SidePanel.HEAD_H && mouseX >= lx + lw - 14) {
                expanded = false;
                return true;
            }
            int i = (int) Math.floor((mouseY - (listTop - 1)) / ROW);
            if (mouseY >= listTop - 1 && i >= 0 && i < listRows && scroll + i < entries.size()) {
                act(entries.get(scroll + i).recipe(), button);
            }
            return true;
        }
        if (pw <= 0) return false;
        if (mouseX < px || mouseX >= px + pw || mouseY < py || mouseY >= py + ph) return false;
        if (moreY >= 0 && mouseY >= moreY && mouseY < moreY + 11 && button == 0) {
            expanded = !expanded;
            scroll = 0;
            return true;
        }
        int i = (int) Math.floor((mouseY - (rowsTop - 1)) / ROW);
        if (mouseY >= rowsTop - 1 && i >= 0 && i < shown) act(entries.get(i).recipe(), button);
        else if (button == 1 && sneakDown()) detail = null;
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
        String n = kr.ddingmate.ui.Theme.compactGrade(r.displayName());
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

    /** 시험용: "그 밖에 N개" 줄 가운데(없으면 null) */
    public static int[] debugMore() {
        return pw <= 0 || moreY < 0 ? null : new int[]{px + pw / 2, moreY + 5};
    }

    /** 시험용: 지금 연 레시피 판 */
    public static RecipeBook.Recipe detail() {
        return detail;
    }

    /** 시험용: 쉬프트를 누른 것처럼 레시피 판 열기/닫기 */
    public static void debugDetail(RecipeBook.Recipe r) {
        detail = r;
    }

    /** 시험용 */
    public static boolean expanded() {
        return expanded;
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
