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

    /**
     * 핀 하나의 재료 줄. 같은 아이템이 여러 줄에 나오면(예: 해구 파동의 코어 ★★ 의 세 결정이 모두 켈프 더미를 씀)
     * 가진 개수를 위 줄부터 필요한 만큼 나눠 준다: 첫 줄이 다 차면 남은 것이 다음 줄로 간다.
     * 다 나눠 주고 남은 것은 그 아이템을 쓰는 마지막 줄에 얹어 여유분이 보이게 한다.
     * (예전: 줄마다 가진 개수 전체를 세어, 4세트 57개로 세 줄이 모두 "다 모음"으로 보였다 — 2026-10-16 사용자 요청)
     */
    static PinView view(int index, DdingMateConfig.Pin pin, RecipeBook.Recipe r, Map<String, Integer> inv,
                        Map<String, Integer> bags, Map<String, Integer> storage, boolean expand) {
        Pool pool = new Pool(inv, bags, storage);
        List<Line> lines = new ArrayList<>();
        for (RecipeBook.Material m : r.materials) {
            Line line = pool.line(m.name, m.count * craftsOf(pin, r));
            if (expand && !line.done()) line = withSubs(line, r, pool);
            lines.add(line);
        }
        return new PinView(index, pin, r, pool.giveRest(lines));
    }

    /** 시험용: 레시피 id 를 crafts 번 만들 때의 재료 줄(하위 재료 펼침). have 는 재료 이름 → 인벤토리 개수 */
    public static List<Line> debugLines(String recipeId, int crafts, Map<String, Integer> have) {
        RecipeBook.Recipe r = RecipeBook.get(recipeId);
        DdingMateConfig.Pin pin = new DdingMateConfig.Pin();
        pin.recipe = recipeId;
        pin.crafts = crafts;
        Map<String, Integer> inv = new HashMap<>();
        have.forEach((name, n) -> inv.merge(RecipeBook.keysFor(name).iterator().next(), n, Integer::sum));
        return view(0, pin, r, inv, Map.of(), Map.of(), true).lines();
    }

    /** 핀 하나 안에서 가진 개수를 줄마다 나눠 주는 통(인벤토리 → 가방 → 저장소 순서로 꺼낸다) */
    static final class Pool {
        private final Map<String, Integer> inv, bags, storage;

        Pool(Map<String, Integer> inv, Map<String, Integer> bags, Map<String, Integer> storage) {
            this.inv = new HashMap<>(inv);
            this.bags = new HashMap<>(bags);
            this.storage = new HashMap<>(storage);
        }

        /** 필요한 만큼(남은 것 안에서) 꺼내 줄을 만든다 */
        Line line(String name, int need) {
            Set<String> keys = RecipeBook.keysFor(name);
            int a = take(inv, keys, need);
            int b = take(bags, keys, need - a);
            int c = take(storage, keys, need - a - b);
            return new Line(name, a, b, c, need, List.of());
        }

        /** 다 나눠 주고 남은 것을 그 아이템을 쓰는 마지막 줄에 얹는다(하위 재료 줄 포함, 아래쪽이 마지막) */
        List<Line> giveRest(List<Line> lines) {
            Map<String, int[]> last = new HashMap<>(); // 이름 → {윗줄 번호, 하위 줄 번호(-1 = 윗줄 자신)}
            for (int i = 0; i < lines.size(); i++) {
                last.put(lines.get(i).name(), new int[]{i, -1});
                List<Line> subs = lines.get(i).subs();
                for (int j = 0; j < subs.size(); j++) last.put(subs.get(j).name(), new int[]{i, j});
            }
            List<Line> out = new ArrayList<>(lines);
            for (Map.Entry<String, int[]> e : last.entrySet()) {
                Set<String> keys = RecipeBook.keysFor(e.getKey());
                int a = take(inv, keys, Integer.MAX_VALUE);
                int b = take(bags, keys, Integer.MAX_VALUE);
                int c = take(storage, keys, Integer.MAX_VALUE);
                if (a + b + c == 0) continue;
                int i = e.getValue()[0], j = e.getValue()[1];
                Line top = out.get(i);
                if (j < 0) {
                    out.set(i, new Line(top.name(), top.inventory() + a, top.bags() + b, top.storage() + c, top.need(), top.subs()));
                } else {
                    List<Line> subs = new ArrayList<>(top.subs());
                    Line sub = subs.get(j);
                    subs.set(j, new Line(sub.name(), sub.inventory() + a, sub.bags() + b, sub.storage() + c, sub.need(), sub.subs()));
                    out.set(i, new Line(top.name(), top.inventory(), top.bags(), top.storage(), top.need(), List.copyOf(subs)));
                }
            }
            return out;
        }

        private static int take(Map<String, Integer> from, Set<String> keys, int max) {
            int got = 0;
            for (String k : keys) {
                if (got >= max) break;
                int have = from.getOrDefault(k, 0);
                int n = Math.min(have, max - got);
                if (n <= 0) continue;
                from.put(k, have - n);
                got += n;
            }
            return got;
        }
    }

    /**
     * 모자란 재료가 레시피로 만들 수 있는 것이면(예: 조약돌 뭉치 = 조약돌 64개), 모자란 만큼 만들 때 필요한 원재료를 붙인다.
     * 한 단계만 펼치고, 자기 자신을 다시 만드는 레시피는 건너뛴다.
     */
    private static Line withSubs(Line line, RecipeBook.Recipe parent, Pool pool) {
        RecipeBook.Recipe sub = RecipeBook.recipeFor(line.name());
        if (sub == null || sub.id.equals(parent.id) || sub.materials.isEmpty()) return line;
        int shortage = line.need() - line.have();
        int crafts = (shortage + sub.output - 1) / sub.output;
        List<Line> subs = new ArrayList<>();
        for (RecipeBook.Material m : sub.materials) {
            if (m.name.equals(line.name())) return line;
            subs.add(pool.line(m.name, m.count * crafts));
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
    public static Map<String, Integer> inventoryCounts(MinecraftClient client) {
        Map<String, Integer> counts = new HashMap<>();
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) ItemKeys.add(counts, inv.getStack(i));
        ItemKeys.add(counts, inv.getStack(PlayerInventory.OFF_HAND_SLOT));
        ItemStack cursor = client.player.currentScreenHandler.getCursorStack();
        ItemKeys.add(counts, cursor);
        return counts;
    }

    /** inventoryCounts 가 세는 칸과 같은 칸들(인벤토리 36칸 + 왼손 + 마우스로 집은 것) */
    public static java.util.List<ItemStack> inventoryStacks(MinecraftClient client) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) out.add(inv.getStack(i));
        out.add(inv.getStack(PlayerInventory.OFF_HAND_SLOT));
        out.add(client.player.currentScreenHandler.getCursorStack());
        return out;
    }

    private static int sum(Map<String, Integer> counts, Set<String> keys) {
        int n = 0;
        for (String k : keys) n += counts.getOrDefault(k, 0);
        return n;
    }
}
