package kr.ddingmate.counter;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 핀으로 고른 레시피의 재료를 인벤토리(+ 기록된 가방)에서 세어 HUD 용으로 정리한다. */
public final class MaterialCounter {

    public record Line(String name, int inventory, int bags, int need) {
        public int have() {
            return inventory + bags;
        }

        public boolean done() {
            return have() >= need;
        }
    }

    public record PinView(int index, AlarmConfig.Pin pin, RecipeBook.Recipe recipe, List<Line> lines) {
        public boolean done() {
            return lines.stream().allMatch(Line::done);
        }

        public long doneCount() {
            return lines.stream().filter(Line::done).count();
        }
    }

    private static List<PinView> views = List.of();
    /** 핀별 "다 모였음" 상태. null 이면 아직 한 번도 계산 안 함(접속 직후 알림이 울리지 않게) */
    private static final Boolean[] wasDone = new Boolean[AlarmConfig.PIN_COUNT];
    private static final String[] lastKey = new String[AlarmConfig.PIN_COUNT];

    private MaterialCounter() {}

    public static List<PinView> views() {
        return views;
    }

    public static void tick(MinecraftClient client) {
        AlarmConfig.Counter cfg = DdingMate.config().counter;
        if (client.player == null || !cfg.enabled) {
            views = List.of();
            return;
        }
        Map<String, Integer> inv = inventoryCounts(client);
        Map<String, Integer> bags = cfg.includeBags ? BagTracker.totals() : Map.of();
        List<PinView> out = new ArrayList<>();
        for (int i = 0; i < cfg.pins.size() && i < AlarmConfig.PIN_COUNT; i++) {
            AlarmConfig.Pin pin = cfg.pins.get(i);
            RecipeBook.Recipe r = RecipeBook.get(pin.recipe);
            String key = r == null ? null : r.id + "*" + pin.crafts;
            if (key == null || !key.equals(lastKey[i])) {
                lastKey[i] = key;
                wasDone[i] = null;
            }
            if (r == null) continue;
            PinView v = view(i, pin, r, inv, bags);
            out.add(v);
            boolean done = v.done();
            if (Boolean.FALSE.equals(wasDone[i]) && done && cfg.notifyComplete) {
                ItemStack icon = r.materials.isEmpty() ? null : IconCache.forMaterial(r.materials.get(0).name);
                Notifier.materialsReady(r.displayName(), pin.crafts, icon);
            }
            wasDone[i] = done;
        }
        views = out;
    }

    static PinView view(int index, AlarmConfig.Pin pin, RecipeBook.Recipe r,
                        Map<String, Integer> inv, Map<String, Integer> bags) {
        List<Line> lines = new ArrayList<>();
        for (RecipeBook.Material m : r.materials) {
            Set<String> keys = RecipeBook.keysFor(m.name);
            lines.add(new Line(m.name, sum(inv, keys), sum(bags, keys), m.count * pin.crafts));
        }
        return new PinView(index, pin, r, lines);
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
