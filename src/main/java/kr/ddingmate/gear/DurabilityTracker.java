package kr.ddingmate.gear;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 지정한 장비의 남은 내구도를 내 인벤토리에서 찾아 보여 주고, 정해 둔 % 아래로 떨어지면 한 번 알린다.
 * 같은 장비가 여러 개면 손에 든 것 → 왼손 → 입은 것 → 단축바 → 가방 칸 순서로 먼저 찾은 것을 쓴다.
 */
public final class DurabilityTracker {

    /** 서버가 내구도를 설명 문구로 적는 경우를 대비: "내구도 236/1938", "내구도: 236 / 1,938" */
    private static final Pattern LORE = Pattern.compile("내구도\\s*[:：]?\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");

    /** 프레스티지 버튼 모양 장비(2026-10-29 사용자 화면): 마우스를 올리면 "Durability: 98%" 처럼 퍼센트만 보인다 */
    private static final Pattern LORE_PCT = Pattern.compile("(?:Durability|내구도)\\s*:?\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*%");

    /** percent: 서버가 퍼센트로만 알려 준 내구도(left/max 는 천분율). 이때는 숫자 대신 "98%"로 보인다 */
    public record Durability(int left, int max, boolean percent) {
        public Durability(int left, int max) {
            this(left, max, false);
        }

        public double ratio() {
            return max <= 0 ? 0 : (double) left / max;
        }

        /** "1461/1561" 또는 "98%" */
        public String text() {
            if (!percent) return left + "/" + max;
            double p = ratio() * 100;
            return (p == Math.rint(p) ? String.valueOf((long) p) : String.format(java.util.Locale.ROOT, "%.1f", p)) + "%";
        }
    }

    /** HUD 한 줄. stack 이 null 이면 지금 인벤토리에 없음 */
    public record Row(DdingMateConfig.TrackedItem item, ItemStack stack, Durability durability) {}

    private static List<Row> rows = List.of();
    private static final Set<String> WARNED = new HashSet<>();

    private DurabilityTracker() {}

    public static List<Row> rows() {
        return rows;
    }

    /** 내구도가 있는 아이템이면 남은/최대 내구도, 아니면 null */
    public static Durability read(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        if (s.isDamageable() && s.getMaxDamage() > 0) {
            return new Durability(s.getMaxDamage() - s.getDamage(), s.getMaxDamage());
        }
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                String t = Formatting.strip(line.getString());
                if (t == null) continue;
                Matcher m = LORE.matcher(t);
                if (m.find()) {
                    try {
                        int left = Integer.parseInt(m.group(1).replace(",", ""));
                        int max = Integer.parseInt(m.group(2).replace(",", ""));
                        if (max > 0) return new Durability(left, max);
                    } catch (NumberFormatException ignored) {
                        // 숫자가 너무 크면 무시
                    }
                }
                Matcher p = LORE_PCT.matcher(t);
                if (p.find()) {
                    double pct = Math.min(100, Double.parseDouble(p.group(1)));
                    return new Durability((int) Math.round(pct * 10), 1000, true);
                }
            }
        }
        return null;
    }

    /** 찾는 순서대로 인벤토리 칸 번호: 손 → 왼손 → 갑옷 → 단축바 → 나머지 */
    static List<ItemStack> searchOrder(PlayerInventory inv) {
        List<ItemStack> out = new ArrayList<>();
        out.add(inv.getMainHandStack());
        out.add(inv.getStack(PlayerInventory.OFF_HAND_SLOT));
        for (int i = 39; i >= 36; i--) out.add(inv.getStack(i));
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) out.add(inv.getStack(i));
        return out;
    }

    /** 지금 인벤토리에 있는 내구도 아이템을 종류별로 하나씩 (설정 화면 목록용) */
    public static Map<String, ItemStack> candidates(MinecraftClient client) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        if (client.player == null) return out;
        for (ItemStack s : searchOrder(client.player.getInventory())) {
            if (read(s) == null) continue;
            out.putIfAbsent(ItemKeys.keyOf(s), s);
        }
        return out;
    }

    public static void tick(MinecraftClient client) {
        DdingMateConfig.Durability cfg = DdingMate.config().durability;
        if (client.player == null || cfg.tracked.isEmpty()) {
            rows = List.of();
            return;
        }
        PlayerInventory inv = client.player.getInventory();
        List<ItemStack> order = searchOrder(inv);
        List<Row> out = new ArrayList<>();
        for (DdingMateConfig.TrackedItem t : cfg.tracked) {
            ItemStack found = null;
            Durability d = null;
            for (ItemStack s : order) {
                if (s.isEmpty() || !t.key.equals(ItemKeys.keyOf(s))) continue;
                d = read(s);
                if (d != null) {
                    found = s;
                    break;
                }
            }
            if (found != null) {
                remember(t, inv, found);
            } else {
                // 이름으로 못 찾음: 프레스티지 머리 치장·갑옷 크기 조절은 입은 갑옷의 이름·그림만 바꾼다
                // (2026-10-29 사용자 화면: 투구를 썼는데 머리 칸이 "렛서판다 모자"). 입은 칸에서 같은 종류 → 같은 칸 순서로 찾는다
                found = worn(t, inv);
                d = found == null ? null : read(found);
            }
            out.add(new Row(t, found, d));
            if (d != null) checkWarn(cfg, t, found, d);
        }
        rows = out;
    }

    /** 찾은 장비의 종류와 입은 칸을 기억한다(바뀐 때만 저장) */
    private static void remember(DdingMateConfig.TrackedItem t, PlayerInventory inv, ItemStack s) {
        String id = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString();
        int slot = -1;
        for (int i = 36; i <= 39; i++) if (inv.getStack(i) == s) slot = i;
        if (id.equals(t.itemId) && slot == t.wornSlot) return;
        t.itemId = id;
        t.wornSlot = slot;
        DdingMate.config().save();
    }

    /** 이름이 바뀐 채 입고 있는 장비: 같은 종류를 입은 칸, 없으면 예전에 입던 칸(내구도가 있을 때만) */
    static ItemStack worn(DdingMateConfig.TrackedItem t, PlayerInventory inv) {
        String id = !t.itemId.isEmpty() ? t.itemId : t.key.startsWith("v:") ? t.key.substring(2) : "";
        for (int i = 36; i <= 39; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && !id.isEmpty() && id.equals(net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString()) && read(s) != null) return s;
        }
        if (t.wornSlot >= 36 && t.wornSlot <= 39) {
            ItemStack s = inv.getStack(t.wornSlot);
            if (!s.isEmpty() && read(s) != null) return s;
        }
        return null;
    }

    /** 경고선 아래로 처음 내려갈 때 한 번 알리고, 수리해서 5%p 이상 회복하면 다시 알릴 수 있게 한다. */
    private static void checkWarn(DdingMateConfig.Durability cfg, DdingMateConfig.TrackedItem t, ItemStack s, Durability d) {
        if (cfg.warnPercent <= 0) return;
        double pct = d.ratio() * 100;
        if (pct <= cfg.warnPercent) {
            if (WARNED.add(t.key)) Notifier.durabilityLow(t.name, d.text(), s.copyWithCount(1));
        } else if (pct > cfg.warnPercent + 5) {
            WARNED.remove(t.key);
        }
    }

    public static boolean isTracked(String key) {
        return DdingMate.config().durability.tracked.stream().anyMatch(t -> t.key.equals(key));
    }

    public static void setTracked(String key, String name, boolean on) {
        List<DdingMateConfig.TrackedItem> list = DdingMate.config().durability.tracked;
        list.removeIf(t -> t.key.equals(key));
        if (on) list.add(new DdingMateConfig.TrackedItem(key, ItemKeys.stripGlyphs(name).trim()));
        WARNED.remove(key);
    }
}
