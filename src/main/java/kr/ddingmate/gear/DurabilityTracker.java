package kr.ddingmate.gear;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * HUD 한 줄. stack 이 null 이면 지금 인벤토리에 없음.
     * hidden: 치장 아래에 있어 durability 는 마지막으로 본 값. live: 치장 아이템에 서버가 내구도를 실어 보내 지금 값
     */
    public record Row(DdingMateConfig.TrackedItem item, ItemStack stack, Durability durability, boolean hidden, boolean live) {
        public Row(DdingMateConfig.TrackedItem item, ItemStack stack, Durability durability, boolean hidden) {
            this(item, stack, durability, hidden, false);
        }

        /** 지금 값이 정확한가: 보이는 장비, 치장에 실려 온 값, 마지막으로 본 뒤로 안 맞은 치장 아래 투구 */
        public boolean exact() {
            return durability != null && (!hidden || live || !item.hitSince);
        }
    }

    /** 지난 틱의 피격 흔들림·체력: 흔들림이 새로 시작되거나 체력이 줄면 한 대 맞은 것 */
    private static int lastHurtTime;
    private static float lastHealth = -1;

    /**
     * 매 틱: 맞았으면 치장 아래 투구마다 맞은 횟수를 하나 올린다(투구는 맞아야만 닳는다). 0.5초마다 보면 회복이 빨라
     * 맞은 흔적이 사라져서(시험: 2 피해 뒤 15틱 만에 체력 20) 매 틱 본다. 낙하처럼 갑옷이 안 닳는 피해도 센다(넉넉하게)
     */
    public static void watchHits(MinecraftClient client) {
        if (client.player == null) {
            lastHurtTime = 0;
            lastHealth = -1;
            return;
        }
        int hurt = client.player.hurtTime;
        float health = client.player.getHealth();
        boolean hit = (hurt > 0 && hurt > lastHurtTime) || (lastHealth >= 0 && health < lastHealth - 0.01f && hurt == 0);
        lastHurtTime = hurt;
        lastHealth = health;
        if (!hit) return;
        DdingMateConfig.Durability cfg = DdingMate.config().durability;
        PlayerInventory inv = client.player.getInventory();
        boolean changed = false;
        for (DdingMateConfig.TrackedItem t : cfg.tracked) {
            if (t.lastMax <= 0 || cosmeticOver(t, inv) == null) continue;
            t.hitsSince++;
            t.hitSince = true;
            changed = true;
        }
        if (changed) DdingMate.config().save();
    }

    /** 배운 한 대당 닳는 양(맞은 횟수 5번 이상 배웠을 때). 모르면 -1 */
    public static double lossPerHit() {
        DdingMateConfig.Durability cfg = DdingMate.config().durability;
        return cfg.learnedHits >= 5 ? (double) cfg.learnedLoss / cfg.learnedHits : -1;
    }

    private static List<Row> rows = List.of();
    private static final Set<String> WARNED = new HashSet<>();

    private DurabilityTracker() {}

    public static List<Row> rows() {
        return rows;
    }

    /** 내구도가 있는 아이템이면 남은/최대 내구도, 아니면 null */
    public static Durability read(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        // 1) 서버가 설명에 직접 적은 숫자가 먼저: 드릴은 바닐라 곡괭이(2031)지만 툴팁은 "◇ 현재 내구도 : 97 / 100"(2026-10-24 원본 기록)
        if (lore != null) {
            for (Text line : lore.lines()) {
                String t = Formatting.strip(line.getString());
                Matcher m = t == null ? null : LORE.matcher(t);
                if (m == null || !m.find()) continue;
                try {
                    int left = Integer.parseInt(m.group(1).replace(",", ""));
                    int max = Integer.parseInt(m.group(2).replace(",", ""));
                    if (max > 0) return new Durability(left, max);
                } catch (NumberFormatException ignored) {
                    // 숫자가 너무 크면 무시
                }
            }
        }
        if (s.isDamageable() && s.getMaxDamage() > 0) {
            return new Durability(s.getMaxDamage() - s.getDamage(), s.getMaxDamage());
        }
        // 2) 갑옷 가리기 버튼은 진짜 장비의 닳은 양(minecraft:damage 2)을 그대로 갖고 온다(2026-10-29 기록) → 479/481 처럼 정확히
        Item real = disguisedAs(s);
        int realMax = real == null ? 0 : new ItemStack(real).getMaxDamage();
        if (realMax > 0 && s.contains(DataComponentTypes.DAMAGE)) {
            return new Durability(Math.max(0, realMax - s.getOrDefault(DataComponentTypes.DAMAGE, 0)), realMax);
        }
        // 3) 퍼센트만 있으면 퍼센트(진짜 장비를 알고 100% 면 닳지 않은 것)
        if (lore != null) {
            for (Text line : lore.lines()) {
                String t = Formatting.strip(line.getString());
                Matcher p = t == null ? null : LORE_PCT.matcher(t);
                if (p == null || !p.find()) continue;
                double pct = Math.min(100, Double.parseDouble(p.group(1)));
                if (realMax > 0 && pct >= 100) return new Durability(realMax, realMax);
                return new Durability((int) Math.round(pct * 10), 1000, true);
            }
        }
        return null;
    }

    /**
     * 프레스티지 '갑옷 가리기'(2026-10-29 F3+H 화면): 입은 신발이 polished_blackstone_button 으로 오고 이름은 영어
     * "Netherite Boots", 툴팁에 "Durability: 100%". 영어 이름을 아이템 id(netherite_boots)로 바꿔 진짜 장비를 찾는다
     */
    static Item disguisedAs(ItemStack s) {
        if (s.isEmpty() || s.isDamageable()) return null;
        String name = Formatting.strip(ItemKeys.stripGlyphs(s.getName().getString()));
        if (name == null) return null;
        String path = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!path.matches("[a-z0-9_]+")) return null;
        Item real = Registries.ITEM.get(Identifier.ofVanilla(path));
        if (real == Items.AIR || real == s.getItem() || !new ItemStack(real).isDamageable()) return null;
        return real;
    }

    /** 장비 키. 가린 갑옷은 진짜 장비 이름("네더라이트 부츠")으로 만들어 가리기 전과 같은 장비로 본다 */
    public static String keyOf(ItemStack s) {
        Item real = disguisedAs(s);
        return real == null ? ItemKeys.keyOf(s) : ItemKeys.nameKey(new ItemStack(real).getName().getString());
    }

    /** 목록에 보일 이름("Netherite Boots" → "네더라이트 부츠") */
    public static String nameOf(ItemStack s) {
        Item real = disguisedAs(s);
        return ItemKeys.stripGlyphs((real == null ? s : new ItemStack(real)).getName().getString()).trim();
    }

    /** 목록·HUD 그림. 가린 갑옷은 버튼 대신 진짜 장비 그림 */
    public static ItemStack iconOf(ItemStack s) {
        Item real = disguisedAs(s);
        return real == null ? s.copyWithCount(1) : new ItemStack(real);
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
            out.putIfAbsent(keyOf(s), s);
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
                if (s.isEmpty() || !t.key.equals(keyOf(s))) continue;
                d = read(s);
                if (d != null) {
                    found = s;
                    break;
                }
            }
            boolean hidden = false, live = false;
            if (found != null) {
                remember(t, inv, found, d);
            } else {
                // 이름으로 못 찾음: 프레스티지 머리 치장·갑옷 크기 조절은 입은 갑옷의 이름·그림만 바꾼다
                // (2026-10-29 사용자 화면: 투구를 썼는데 머리 칸이 "렛서판다 모자"). 입은 칸에서 같은 종류 → 같은 칸 순서로 찾는다
                found = worn(t, inv);
                d = found == null ? null : read(found);
                ItemStack cos = found == null ? cosmeticOver(t, inv) : null;
                if (cos != null) {
                    // 치장 아래 투구: 서버는 치장 쓴 동안 진짜 투구를 한 번도 안 보낸다(2026-10-30 패킷 기록, 접속·채널 이동 5번).
                    // 1) 치장 아이템에 내구도가 실려 오면(갑옷 가리기 버튼처럼) 그 값이 지금 값
                    // 2) 아니면 투구를 벗었다 낄 때 본 마지막 값. 그 뒤로 안 맞았으면 지금도 정확하다(투구는 맞아야만 닳음)
                    hidden = true;
                    Durability carried = carried(cos, t);
                    if (carried != null) {
                        d = carried;
                        live = true;
                        rememberHidden(t, d);
                    } else {
                        d = t.lastMax > 0 ? new Durability(t.lastLeft, t.lastMax, t.lastPercent) : null;
                    }
                }
            }
            Row row = new Row(t, found, d, hidden, live);
            out.add(row);
            if (row.exact()) checkWarn(cfg, t, found, d);
        }
        rows = out;
    }

    /** 찾은 장비의 종류·입은 칸, 갑옷이면 내구도도 기억한다(바뀐 때만 저장. 도구는 블록마다 바뀌어서 내구도는 안 남김) */
    private static void remember(DdingMateConfig.TrackedItem t, PlayerInventory inv, ItemStack s, Durability d) {
        Item real = disguisedAs(s);
        Item item = real != null ? real : s.getItem();
        String id = Registries.ITEM.getId(item).toString();
        int slot = -1;
        for (int i = 36; i <= 39; i++) if (inv.getStack(i) == s) slot = i;
        boolean armor = armorSlot(item) >= 0;
        if (armor) t.lastAt = kr.ddingmate.util.Clock.now(); // 저장은 값이 바뀔 때만(시각만 바뀐 것은 다음 저장 때 함께)
        boolean same = id.equals(t.itemId) && slot == t.wornSlot && !(armor && t.hitSince);
        if (armor && t.hitsSince > 0 && t.lastMax > 0 && !t.lastPercent && !d.percent() && d.max() == t.lastMax) {
            int loss = t.lastLeft - d.left();
            // 줄어든 만큼만 배운다(늘었으면 그사이 수리·수선이라 맞은 횟수와 상관없다)
            if (loss >= 0) {
                DdingMateConfig.Durability cfg = DdingMate.config().durability;
                cfg.learnedHits += t.hitsSince;
                cfg.learnedLoss += loss;
                DdingMate.LOG.info("치장 투구 추정 배움: 맞은 {}번에 {} 줄어듦 (누적 {}번 {})", t.hitsSince, loss, cfg.learnedHits, cfg.learnedLoss);
            }
        }
        if (armor) {
            t.hitSince = false; // 진짜 값을 봤다
            t.hitsSince = 0;
        }
        if (armor) same &= d.left() == t.lastLeft && d.max() == t.lastMax && d.percent() == t.lastPercent;
        if (same) return;
        t.itemId = id;
        t.wornSlot = slot;
        if (armor) {
            t.lastLeft = d.left();
            t.lastMax = d.max();
            t.lastPercent = d.percent();
        }
        DdingMate.config().save();
    }

    /** 치장 아이템에 실려 온 진짜 투구 내구도: 닳은 양(minecraft:damage)이나 설명의 "Durability: N%"(갑옷 가리기 버튼과 같은 방식). 없으면 null */
    static Durability carried(ItemStack cos, DdingMateConfig.TrackedItem t) {
        Identifier id = Identifier.tryParse(t.itemId);
        int max = id == null || !Registries.ITEM.containsId(id) ? 0 : new ItemStack(Registries.ITEM.get(id)).getMaxDamage();
        if (max > 0 && cos.contains(DataComponentTypes.DAMAGE)) {
            return new Durability(Math.max(0, max - cos.getOrDefault(DataComponentTypes.DAMAGE, 0)), max);
        }
        Durability lore = read(cos);
        if (lore != null && lore.percent() && max > 0 && lore.ratio() >= 1) return new Durability(max, max);
        return lore;
    }

    /** 치장에 실려 온 값을 마지막 값으로 기억(바뀐 때만 저장) */
    private static void rememberHidden(DdingMateConfig.TrackedItem t, Durability d) {
        t.lastAt = kr.ddingmate.util.Clock.now();
        if (d.left() == t.lastLeft && d.max() == t.lastMax && d.percent() == t.lastPercent && !t.hitSince) return;
        t.lastLeft = d.left();
        t.lastMax = d.max();
        t.lastPercent = d.percent();
        t.hitSince = false;
        t.hitsSince = 0;
        DdingMate.config().save();
    }

    /** "방금", "12분 전", "3시간 전", "2일 전" */
    public static String ago(long at, long now) {
        if (at <= 0) return "";
        long min = Math.max(0, now - at) / 60_000;
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        if (min < 24 * 60) return (min / 60) + "시간 전";
        return (min / (24 * 60)) + "일 전";
    }

    /**
     * HUD·장비 탭 값. 치장 아래: 지금 값이거나 그 뒤로 안 맞았으면 "치장 · 405/407"(정확),
     * 맞은 적이 있으면 "치장 · 405/407 이하 · 3시간 전"(그 뒤로 줄었을 수 있음 → 투구를 벗었다 끼면 맞춰짐)
     */
    public static String valueText(Row r) {
        if (r.durability() == null) return r.hidden() ? "치장에 가려짐" : "인벤토리에 없음";
        if (!r.hidden()) return r.durability().text();
        if (r.exact()) return "치장 · " + r.durability().text();
        Durability est = estimate(r);
        if (est != null) return "치장 · 약 " + est.text();
        String when = ago(r.item().lastAt, kr.ddingmate.util.Clock.now());
        return "치장 · " + r.durability().text() + " 이하" + (when.isEmpty() ? "" : " · " + when);
    }

    /** 맞은 뒤의 치장 아래 투구 어림값: 마지막 값 − 맞은 횟수 × 배운 한 대당 닳는 양. 아직 덜 배웠으면 null */
    public static Durability estimate(Row r) {
        Durability d = r.durability();
        double per = lossPerHit();
        if (d == null || d.percent() || per < 0 || r.item().hitsSince <= 0) return null;
        return new Durability(Math.max(0, d.left() - (int) Math.round(per * r.item().hitsSince)), d.max());
    }

    /** 갑옷이 들어가는 인벤토리 칸(신발 36 ~ 투구 39), 갑옷이 아니면 -1 */
    public static int armorSlot(Item item) {
        var eq = new ItemStack(item).get(DataComponentTypes.EQUIPPABLE);
        if (eq == null || eq.slot().getType() != net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR) return -1;
        return 36 + eq.slot().getEntitySlotId();
    }

    /**
     * 머리 치장(2026-10-29 실제 기록): 투구 칸이 HMCCosmetics 아이템(leather_horse_armor "렛서판다 모자",
     * custom_data PublicBukkitValues "hmccosmetics:cosmetic") 으로 바뀌고 진짜 투구의 내구도는 오지 않는다
     */
    public static boolean cosmetic(ItemStack s) {
        net.minecraft.component.type.NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().getCompound("PublicBukkitValues").contains("hmccosmetics:cosmetic");
    }

    /** 이 장비가 들어갈 갑옷 칸이 치장으로 가려져 있나 */
    static boolean underCosmetic(DdingMateConfig.TrackedItem t, PlayerInventory inv) {
        return cosmeticOver(t, inv) != null;
    }

    /** 이 장비가 들어갈 갑옷 칸의 치장 아이템(치장이 아니면 null) */
    static ItemStack cosmeticOver(DdingMateConfig.TrackedItem t, PlayerInventory inv) {
        Identifier id = Identifier.tryParse(t.itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        int slot = armorSlot(Registries.ITEM.get(id));
        ItemStack s = slot >= 0 ? inv.getStack(slot) : ItemStack.EMPTY;
        return cosmetic(s) ? s : null;
    }

    /** 이름이 바뀐 채 입고 있는 장비: 같은 종류를 입은 칸, 없으면 예전에 입던 칸(내구도가 있을 때만) */
    static ItemStack worn(DdingMateConfig.TrackedItem t, PlayerInventory inv) {
        String id = !t.itemId.isEmpty() ? t.itemId : t.key.startsWith("v:") ? t.key.substring(2) : "";
        for (int i = 36; i <= 39; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && !id.isEmpty() && id.equals(net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString()) && read(s) != null) return s;
        }
        if (t.wornSlot >= 36 && t.wornSlot <= 39 && !id.isEmpty()) {
            // 예전에 입던 칸: 그 칸의 진짜 장비 종류가 같을 때만(다른 장비의 내구도를 보여 주지 않게)
            ItemStack s = inv.getStack(t.wornSlot);
            Item real = s.isEmpty() ? null : disguisedAs(s);
            if (real != null && id.equals(Registries.ITEM.getId(real).toString()) && read(s) != null) return s;
        }
        return null;
    }

    /** 서버가 인벤토리 칸을 보낸 순간(치장으로 바뀌기 전 진짜 장비가 잠깐 온다) */
    public static void onInventoryPacket(MinecraftClient client, String kind) {
        if (client.player != null && kr.ddingmate.debug.DevRecord.enabled()) logHead(client, kind);
        if (client.player != null && !DdingMate.config().durability.tracked.isEmpty()) tick(client);
    }

    private static String lastHeadLog = "";

    /**
     * 개발용 기록이 켜져 있으면 머리 칸이 바뀔 때마다 무슨 패킷으로 무엇이 왔는지 남긴다(2026-10-30: 첫 접속 땐 진짜 투구가 잠깐 오는데
     * 채널 이동 땐 못 잡았다 → 이동 때 서버가 보내는 순서를 보려고)
     */
    private static void logHead(MinecraftClient client, String kind) {
        ItemStack h = client.player.getInventory().getStack(39);
        String sig = h.isEmpty() ? "빈칸" : Registries.ITEM.getId(h.getItem()) + " " + h.getName().getString() + (cosmetic(h) ? " (치장)" : "")
                + (h.isDamageable() ? " " + (h.getMaxDamage() - h.getDamage()) + "/" + h.getMaxDamage() : "");
        if (sig.equals(lastHeadLog)) return;
        lastHeadLog = sig;
        DdingMate.LOG.info("[원본 기록] 머리 칸({}): {}", kind, sig);
    }

    /** 경고선 아래로 처음 내려갈 때 한 번 알리고, 수리해서 5%p 이상 회복하면 다시 알릴 수 있게 한다. */
    private static void checkWarn(DdingMateConfig.Durability cfg, DdingMateConfig.TrackedItem t, ItemStack s, Durability d) {
        if (cfg.warnPercent <= 0) return;
        double pct = d.ratio() * 100;
        if (pct <= cfg.warnPercent) {
            if (WARNED.add(t.key)) Notifier.durabilityLow(t.name, d.text(), s != null ? iconOf(s) : kr.ddingmate.counter.IconCache.forKey(t.key));
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
