package kr.ddingmate.expert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요리 효율 순위. 판매가는 세레니티 마을 밀키 상점 창에서 읽은 "지금 가격"만 쓴다(가격은 매월 1·3·6·9·12·15·18·21·24·27·30일
 * 03시에 바뀜, 위키 [상점가]). 효율 = 판매가 ÷ 원재료 개수(베이스·묶음·가공 식재료를 원재료까지 풀어서 센 수),
 * 그리고 판매가 ÷ 요리 시간(재배 스킬 반영). 바뀐 뒤 상점을 다시 안 열었으면 그 가격은 "지난 가격"으로 표시한다.
 */
public final class Cooking {

    private Cooking() {}

    /** 가격이 바뀌는 날(매월) */
    static final int[] CHANGE_DAYS = {1, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30};
    private static final Pattern PRICE = Pattern.compile("([\\d,]+)\\s*(?:G\\b|골드)");
    private static final Pattern PRICE_HINT = Pattern.compile("판매|매입|가격|구매");

    /** 요리 이름 → 마지막으로 읽은 판매가(G)와 읽은 시각 */
    public static final class Seen {
        public int price;
        public long at;
    }

    private static final class Store {
        Map<String, Seen> prices = new LinkedHashMap<>();
    }

    private static Store store;
    private static final Set<String> LOGGED = new HashSet<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 순위 한 줄 */
    public record Rank(RecipeBook.Recipe recipe, int price, boolean fresh, int rawItems, long craftSec,
                       double perItem, double perMinute) {}

    /** 요리 레시피(요리 제작 시설·황금 요리 제작 시설의 "… 요리") */
    public static List<RecipeBook.Recipe> dishes() {
        List<RecipeBook.Recipe> out = new ArrayList<>();
        for (RecipeBook.Recipe r : RecipeBook.all()) {
            if ((r.facility.equals("요리 제작 시설") || r.facility.equals("황금 요리 제작 시설")) && r.category.endsWith("요리")) out.add(r);
        }
        return out;
    }

    /** 10틱마다: 열린 창이 요리를 사 주는 상점이면 요리별 가격을 읽는다 */
    public static void scan(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) return;
        Map<String, String> names = new LinkedHashMap<>();
        for (RecipeBook.Recipe r : dishes()) names.put(ItemKeys.normalize(r.name), r.name);
        boolean changed = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            String dish = names.get(ItemKeys.normalize(s.getName().getString()));
            if (dish == null) continue;
            int price = priceOf(s);
            if (price <= 0) continue;
            if (LOGGED.size() < 40 && LOGGED.add(dish + price)) DdingMate.LOG.info("요리 가격 읽음: {} {}G", dish, price);
            Seen old = store().prices.get(dish);
            if (old == null || old.price != price || !fresh(old.at)) {
                Seen n = new Seen();
                n.price = price;
                n.at = System.currentTimeMillis();
                store().prices.put(dish, n);
                changed = true;
            }
        }
        if (changed) save();
    }

    /** 칸 설명에서 판매가: "판매/매입/가격" 줄의 "1,234G"를 먼저, 없으면 첫 "…G" */
    static int priceOf(ItemStack s) {
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        int any = -1;
        for (Text t : lore.lines()) {
            String l = ItemKeys.stripGlyphs(t.getString());
            Matcher m = PRICE.matcher(l);
            if (!m.find()) continue;
            int v = Integer.parseInt(m.group(1).replace(",", ""));
            if (PRICE_HINT.matcher(l).find()) return v;
            if (any < 0) any = v;
        }
        return any;
    }

    /** 이 시각에 읽은 가격이 아직 지금 가격인가(그 뒤로 가격이 안 바뀜) */
    public static boolean fresh(long at) {
        return at > 0 && at >= lastChange(ZonedDateTime.now(ScheduleEngine.KST)).toInstant().toEpochMilli();
    }

    /** 가장 최근 가격 변동 시각 */
    public static ZonedDateTime lastChange(ZonedDateTime now) {
        ZonedDateTime best = null;
        for (int back = 0; back < 40 && best == null; back++) {
            ZonedDateTime d = now.minusDays(back).withHour(3).withMinute(0).withSecond(0).withNano(0);
            if (d.isAfter(now)) continue;
            for (int day : CHANGE_DAYS) if (d.getDayOfMonth() == day) best = d;
        }
        return best == null ? now.minusDays(3) : best;
    }

    /** 다음 가격 변동 시각 */
    public static ZonedDateTime nextChange(ZonedDateTime now) {
        for (int ahead = 0; ahead < 40; ahead++) {
            ZonedDateTime d = now.plusDays(ahead).withHour(3).withMinute(0).withSecond(0).withNano(0);
            if (!d.isAfter(now)) continue;
            for (int day : CHANGE_DAYS) if (d.getDayOfMonth() == day) return d;
        }
        return now.plusDays(3);
    }

    /** 순위: 가격을 아는 요리만, perMinute 또는 perItem 높은 순 */
    public static List<Rank> ranking(boolean byTime) {
        List<Rank> out = new ArrayList<>();
        for (RecipeBook.Recipe r : dishes()) {
            Seen s = store().prices.get(r.name);
            if (s == null) continue;
            int raw = rawItems(r, 0);
            long sec = Math.max(1, kr.ddingmate.craft.CraftTimers.durationMs(r.name, 1) / 1000);
            if (sec <= 1 && r.time > 0) sec = r.time;
            double perItem = raw > 0 ? (double) s.price * r.output / raw : 0;
            double perMinute = (double) s.price * r.output * 60.0 / sec;
            out.add(new Rank(r, s.price, fresh(s.at), raw, sec, perItem, perMinute));
        }
        out.sort(Comparator.comparingDouble((Rank k) -> byTime ? -k.perMinute() : -k.perItem()));
        return out;
    }

    /** 원재료 개수: 다른 레시피로 만드는 재료(베이스·묶음·치즈 조각 등)는 그 재료까지 풀어서 센다 */
    static int rawItems(RecipeBook.Recipe r, int depth) {
        int n = 0;
        for (RecipeBook.Material m : r.materials) {
            RecipeBook.Recipe sub = depth < 3 ? RecipeBook.recipeFor(m.name) : null;
            if (sub != null && sub != r && !sub.materials.isEmpty()) {
                int perCraft = rawItems(sub, depth + 1);
                n += (int) Math.ceil((double) perCraft * m.count / Math.max(1, sub.output));
            } else {
                n += m.count;
            }
        }
        return n;
    }

    /** 가격을 아는 요리 수 / 전체 */
    public static int knownCount() {
        int k = 0;
        for (RecipeBook.Recipe r : dishes()) if (store().prices.containsKey(r.name)) k++;
        return k;
    }

    // ---------------------------------------------------------------- 저장

    private static Store store() {
        if (store == null) load();
        return store;
    }

    private static void load() {
        store = new Store();
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Store s = GSON.fromJson(r, Store.class);
            if (s != null && s.prices != null) {
                s.prices.values().removeIf(v -> v == null || v.price <= 0);
                store = s;
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("요리 가격 기록을 읽지 못했어요: {}", p, e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(store()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DdingMate.LOG.error("요리 가격 기록 저장 실패", e);
        }
    }

    /** 시험용 */
    public static void debugPrice(String dish, int price, long at) {
        Seen s = new Seen();
        s.price = price;
        s.at = at;
        store().prices.put(dish, s);
    }

    /** 시험용 */
    public static void debugClear() {
        store = new Store();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-cooking.json");
    }
}
