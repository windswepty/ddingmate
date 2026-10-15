package kr.ddingmate.counter;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 핀으로 고른 레시피의 재료를 인벤토리(+ 기록된 가방·아이템 저장소)에서 세어 HUD 용으로 정리한다. */
public final class MaterialCounter {

    /**
     * 재료 한 줄. subs 는 이 재료가 다른 레시피로 만드는 중간 재료일 때, 모자란 만큼 만들려면 필요한 원재료(한 단계만).
     */
    public record Line(String name, int inventory, int bags, int storage, int need, List<Line> subs) {
        public int have() {
            return inventory + bags + storage;
        }

        public boolean done() {
            return have() >= need;
        }
    }

    public record PinView(int index, DdingMateConfig.Pin pin, RecipeBook.Recipe recipe, List<Line> lines) {
        public boolean done() {
            return lines.stream().allMatch(Line::done);
        }

        public long doneCount() {
            return lines.stream().filter(Line::done).count();
        }
    }

    /**
     * 실제로 몇 번 만들어야 하는지. 결과물 기준이면 원하는 개수를 한 번에 나오는 개수로 나눠 올림한다.
     * 예: 깐 새우 x2 레시피에서 결과물 33개 → 17번.
     */
    public static int craftsOf(DdingMateConfig.Pin pin, RecipeBook.Recipe r) {
        if (pin.byOutput && r.output > 1) return (pin.crafts + r.output - 1) / r.output;
        return pin.crafts;
    }

    /** HUD·홈에 쓰는 핀 제목. 한 번에 여러 개 나오면 결과물 개수와 제작 횟수를 함께 보여 준다 */
    public static String title(DdingMateConfig.Pin pin, RecipeBook.Recipe r) {
        int n = craftsOf(pin, r);
        if (r.output > 1) return r.name + " " + (n * r.output) + "개 · " + n + "번";
        return r.name + " ×" + n;
    }

    private static List<PinView> views = List.of();
    /** 핀별 "다 모였음" 상태. null 이면 아직 한 번도 계산 안 함(접속 직후 알림이 울리지 않게) */
    private static final Boolean[] wasDone = new Boolean[DdingMateConfig.PIN_COUNT];
    private static final String[] lastKey = new String[DdingMateConfig.PIN_COUNT];

    private MaterialCounter() {}

    public static List<PinView> views() {
        return views;
    }

    public static void tick(MinecraftClient client) {
        DdingMateConfig.Counter cfg = DdingMate.config().counter;
        if (client.player == null || !cfg.enabled) {
            views = List.of();
            return;
        }
        Map<String, Integer> inv = inventoryCounts(client);
        Map<String, Integer> bags = cfg.includeBags ? BagTracker.totals() : Map.of();
        Map<String, Integer> storage = cfg.includeStorage ? BagTracker.storageTotals() : Map.of();
        List<PinView> out = new ArrayList<>();
        for (int i = 0; i < cfg.pins.size() && i < DdingMateConfig.PIN_COUNT; i++) {
            DdingMateConfig.Pin pin = cfg.pins.get(i);
            RecipeBook.Recipe r = RecipeBook.get(pin.recipe);
            String key = r == null ? null : r.id + "*" + craftsOf(pin, r);
            if (key == null || !key.equals(lastKey[i])) {
                lastKey[i] = key;
                wasDone[i] = null;
            }
            if (r == null) continue;
            PinView v = view(i, pin, r, inv, bags, storage, cfg.expandSubMaterials);
            out.add(v);
            boolean done = v.done();
            if (Boolean.FALSE.equals(wasDone[i]) && done && cfg.notifyComplete) {
                ItemStack icon = IconCache.forRecipe(r); // 만드는 아이템 모양
                Notifier.materialsReady(title(pin, r), icon);
            }
            wasDone[i] = done;
        }
        views = out;
    }

    static PinView view(int index, DdingMateConfig.Pin pin, RecipeBook.Recipe r, Map<String, Integer> inv,
                        Map<String, Integer> bags, Map<String, Integer> storage, boolean expand) {
        List<Line> lines = new ArrayList<>();
        for (RecipeBook.Material m : r.materials) {
            Line line = line(m.name, m.count * craftsOf(pin, r), inv, bags, storage, List.of());
            if (expand && !line.done()) line = withSubs(line, r, inv, bags, storage);
            lines.add(line);
        }
        return new PinView(index, pin, r, lines);
    }

    private static Line line(String name, int need, Map<String, Integer> inv, Map<String, Integer> bags,
                             Map<String, Integer> storage, List<Line> subs) {
        Set<String> keys = RecipeBook.keysFor(name);
        return new Line(name, sum(inv, keys), sum(bags, keys), sum(storage, keys), need, subs);
    }

    /**
     * 모자란 재료가 레시피로 만들 수 있는 것이면(예: 조약돌 뭉치 = 조약돌 64개), 모자란 만큼 만들 때 필요한 원재료를 붙인다.
     * 한 단계만 펼치고, 자기 자신을 다시 만드는 레시피는 건너뛴다.
     */
    private static Line withSubs(Line line, RecipeBook.Recipe parent, Map<String, Integer> inv,
                                 Map<String, Integer> bags, Map<String, Integer> storage) {
        RecipeBook.Recipe sub = RecipeBook.recipeFor(line.name());
        if (sub == null || sub.id.equals(parent.id) || sub.materials.isEmpty()) return line;
        int shortage = line.need() - line.have();
        int crafts = (shortage + sub.output - 1) / sub.output;
        List<Line> subs = new ArrayList<>();
        for (RecipeBook.Material m : sub.materials) {
            if (m.name.equals(line.name())) return line;
            subs.add(line(m.name, m.count * crafts, inv, bags, storage, List.of()));
        }
        return new Line(line.name(), line.inventory(), line.bags(), line.storage(), line.need(), List.copyOf(subs));
    }

    /** 인벤토리 + (설정에 따라) 기록된 가방 + 아이템 저장소를 합친 개수: 아이템 키 → 개수 */
    public static Map<String, Integer> availableCounts(MinecraftClient client) {
        DdingMateConfig.Counter cfg = DdingMate.config().counter;
        Map<String, Integer> all = new HashMap<>(inventoryCounts(client));
        if (cfg.includeBags) BagTracker.totals().forEach((k, v) -> all.merge(k, v, Integer::sum));
        if (cfg.includeStorage) BagTracker.storageTotals().forEach((k, v) -> all.merge(k, v, Integer::sum));
        return all;
    }

    /** 재료 이름으로 센 개수(다른 이름·바닐라 아이템 포함) */
    public static int have(Map<String, Integer> counts, String materialName) {
        return sum(counts, RecipeBook.keysFor(materialName));
    }

    /** 이 레시피를 지금 몇 번 만들 수 있나(재료마다 가진 수 ÷ 필요 수의 최솟값) */
    public static int craftable(Map<String, Integer> counts, RecipeBook.Recipe r) {
        int best = Integer.MAX_VALUE;
        for (RecipeBook.Material m : r.materials) best = Math.min(best, have(counts, m.name) / Math.max(1, m.count));
        return r.materials.isEmpty() ? 0 : best;
    }

    /** 가진 재료 종류 수(필요 수를 다 채운 재료만) */
    public static int materialsReady(Map<String, Integer> counts, RecipeBook.Recipe r) {
        int n = 0;
        for (RecipeBook.Material m : r.materials) if (have(counts, m.name) >= m.count) n++;
        return n;
    }

    /** 인벤토리 36칸 + 왼손 + 지금 마우스로 집고 있는 아이템 (옮기는 도중에 숫자가 튀지 않게) */
    static Map<String, Integer> inventoryCounts(MinecraftClient client) {
        Map<String, Integer> counts = new HashMap<>();
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) ItemKeys.add(counts, inv.getStack(i));
        ItemKeys.add(counts, inv.getStack(PlayerInventory.OFF_HAND_SLOT));
        ItemStack cursor = client.player.currentScreenHandler.getCursorStack();
        ItemKeys.add(counts, cursor);
        return counts;
    }

    private static int sum(Map<String, Integer> counts, Set<String> keys) {
        int n = 0;
        for (String k : keys) n += counts.getOrDefault(k, 0);
        return n;
    }
}
