package kr.ddingmate.expert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.todo.TodoList;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 바리스타 주문 도우미. 세레니티 마을 분수 광장의 손님(샐리·빈센트·웬디·아이비)에게 말을 걸면 열리는 창에서
 * 별 아이콘 설명(실제 서버 2026-10-15: "[ (B등급) 빈센트의 주문 ]", "- 우드 라떼 [판매 0/2개] : 183,900골드",
 * "남은 판매 가격 : 183,900골드")을 읽어 손님별 주문을 기억한다. 어느 바리스타 창을 열어도 기억해 둔 모든 손님의 주문,
 * 만들어야 할 커피, 판매 합계를 한 번에 보여 준다. 판매가는 서버가 적어 준 값을 쓰고, 표(위키 바리스타 문서)는 참고용.
 * 서버에 아무것도 보내지 않고, 창에 보이는 것만 읽는다.
 */
public final class Barista {

    private Barista() {}

    /** 커피 → 판매가(G). 위키 [바리스타] 손님별 표 */
    public static final Map<String, Integer> PRICE = new LinkedHashMap<>();
    /** 커피 → 손님(등급) */
    public static final Map<String, String> CUSTOMER = new LinkedHashMap<>();
    /** 손님 순서(등급 낮은 순) */
    public static final List<String> CUSTOMERS = List.of("샐리", "빈센트", "웬디", "아이비");
    public static final String INSTANT = "인스턴트 커피";

    static {
        add("샐리 (C · 레어)", "블랙 커피", 40_888, "카페 모카", 30_666, "화이트 모카", 25_555,
                "드립 커피", 35_777, "콜드 브루", 51_110, "아메리카노", 45_999);
        add("빈센트 (B · 에픽)", "플랫 마끼아또", 73_471, "머쉬룸 마끼아또", 81_457, "코코아 마끼아또", 49_513,
                "돌체 라떼", 60_694, "그린티 라떼", 39_930, "우드 라떼", 91_040);
        add("웬디 (A · 전설)", "플라워 카푸치노", 59_096, "썬더 카푸치노", 113_401, "가든 카푸치노", 71_874,
                "스톤 블렌디드", 102_221, "쿠키 블렌디드", 84_652, "다크 블렌디드", 124_582);
        add("아이비 (S · 신화)", "그린트리 프라페", 89_443, "체리 블라썸 프라페", 71_874, "프로즌 스노우 프라페", 145_665,
                "실버문 아인슈페너", 107_012, "오로라 아인슈페너", 160_998, "골든 아인슈페너", 130_332);
        PRICE.put(INSTANT, 20_000);
        CUSTOMER.put(INSTANT, "아무 손님");
    }

    private static void add(String customer, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            PRICE.put((String) pairs[i], (Integer) pairs[i + 1]);
            CUSTOMER.put((String) pairs[i], customer);
        }
    }

    // ---------------------------------------------------------------- 손님 주문(기억)

    /** 손님 한 명의 주문. 별 아이콘 설명에서 읽는다 */
    public static final class Order {
        public String customer = "";
        public String grade = "";
        public List<OrderLine> lines = new ArrayList<>();
        /** 서버가 적어 준 "남은 판매 가격" */
        public long remaining;
        /** 마지막으로 읽은 시각 */
        public long at;

        public int cupsLeft() {
            int n = 0;
            for (OrderLine l : lines) n += l.left();
            return n;
        }
    }

    public static final class OrderLine {
        public String coffee = "";
        public int sold;
        public int want;
        /** 한 잔 판매가(서버 표기에서 계산) */
        public long perCup;

        public int left() {
            return Math.max(0, want - sold);
        }
    }

    private static final class Store {
        Map<String, Map<String, Order>> accounts = new LinkedHashMap<>();
    }

    private static Store store;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** "[ (B등급) 빈센트의 주문 ]" */
    static final Pattern ORDER_TITLE = Pattern.compile("\\(\\s*([A-Za-z])\\s*등급\\s*\\)\\s*(.+?)\\s*의\\s*주문");
    /** "- 우드 라떼 [판매 0/2개] : 183,900골드" */
    static final Pattern ORDER_LINE = Pattern.compile("^\\s*-?\\s*(.+?)\\s*\\[\\s*판매\\s*(\\d+)\\s*/\\s*(\\d+)\\s*개?\\s*]\\s*:?\\s*([\\d,]+)\\s*(?:골드|G)");
    /** "남은 판매 가격 : 183,900골드" */
    static final Pattern REMAIN = Pattern.compile("남은\\s*판매\\s*가격\\s*:?\\s*([\\d,]+)");

    // ---------------------------------------------------------------- 지금 열린 창

    public enum Kind { NONE, CUSTOMER, INSTANT, ORDER_ITEMS }

    /** 주문 한 줄(창에 커피 아이템이 그대로 놓인 주문 창용): 커피, 원하는 잔 수, 내 인벤토리에 있는 잔 수 */
    public record Line(String coffee, int want, int have, int price) {}

    private static Kind kind = Kind.NONE;
    private static String current;
    private static List<Line> order = List.of();
    private static final Set<String> LOGGED = new HashSet<>();

    public static Kind kind() {
        return kind;
    }

    /** 지금 창의 손님 이름(손님 창이 아니면 null) */
    public static String currentCustomer() {
        return current;
    }

    public static List<Line> order() {
        return order;
    }

    /** 커피값이 밥값 스킬로 늘어나는 판매가(%). 전문가 창에서 읽는다(인스턴트 커피에는 안 붙음) */
    private static final Pattern BONUS = Pattern.compile("(\\d+)\\s*%");
    private static final Pattern PREVIEW = Pattern.compile("─{3,}|강화\\s*시|습득\\s*시|배울\\s*시");
    private static final Pattern ORDER_HINT = Pattern.compile("주문|손님|원하|판매|납품|건네");

    /** 10틱마다 */
    public static void scan(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen) || client.player == null) {
            kind = Kind.NONE;
            current = null;
            order = List.of();
            return;
        }
        readSkill(screen);
        // 1) 손님 창: 별 아이콘 설명에 주문이 적혀 있다
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            Order o = parseOrder(slot.getStack());
            if (o == null) continue;
            remember(o);
            kind = Kind.CUSTOMER;
            current = o.customer;
            order = List.of();
            return;
        }
        // 2) 창에 커피가 그대로 놓인 주문 창(실제: 인스턴트 커피 손님 창)
        Map<String, Integer> want = new LinkedHashMap<>();
        boolean hint = ORDER_HINT.matcher(ItemKeys.stripGlyphs(screen.getTitle().getString())).find();
        boolean facility = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            String name = coffeeName(s);
            for (String l : lore(s)) {
                if (l.contains("레시피") || l.contains("제작 시작") || l.contains("제작 시간")) facility = true;
                if (ORDER_HINT.matcher(l).find()) hint = true;
            }
            if (name != null) want.merge(name, s.getCount(), Integer::sum);
        }
        // 시설·가방·상자처럼 커피가 그냥 들어 있는 창은 주문이 아니다: 주문을 알리는 문구가 있는 창만
        if (want.isEmpty() || facility || !hint) {
            kind = Kind.NONE;
            current = null;
            order = List.of();
            return;
        }
        String sig = screen.getTitle().getString() + want.keySet();
        if (LOGGED.size() < 30 && LOGGED.add(sig)) DdingMate.LOG.info("바리스타 주문 창 읽음: {} | {}", ItemKeys.stripGlyphs(screen.getTitle().getString()), want);
        Map<String, Integer> have = inventoryCoffee(client);
        List<Line> out = new ArrayList<>();
        want.forEach((c, n) -> out.add(new Line(c, n, have.getOrDefault(c, 0), priceOf(c))));
        order = out;
        kind = want.size() == 1 && want.containsKey(INSTANT) ? Kind.INSTANT : Kind.ORDER_ITEMS;
        current = null;
    }

    /** 표 판매가에 커피값이 밥값 스킬을 더한 값(인스턴트 커피는 그대로) */
    public static int priceOf(String coffee) {
        int base = PRICE.getOrDefault(coffee, 0);
        if (coffee.equals(INSTANT)) return base;
        return (int) Math.round(base * (100 + DdingMate.config().expert.coffeeBonus) / 100.0);
    }

    /** 별 아이콘이면 주문, 아니면 null */
    static Order parseOrder(ItemStack s) {
        String name = ItemKeys.stripGlyphs(s.getName().getString());
        Matcher t = ORDER_TITLE.matcher(name);
        if (!t.find()) return null;
        Order o = new Order();
        o.grade = t.group(1).toUpperCase(java.util.Locale.ROOT);
        o.customer = t.group(2).trim();
        o.at = System.currentTimeMillis();
        List<String> lore = lore(s);
        List<long[]> raw = new ArrayList<>(); // sold, want, linePrice
        boolean remainSeen = false;
        for (String l : lore) {
            Matcher m = ORDER_LINE.matcher(l);
            if (m.find()) {
                OrderLine line = new OrderLine();
                line.coffee = canonicalCoffee(m.group(1).trim());
                line.sold = Integer.parseInt(m.group(2));
                line.want = Integer.parseInt(m.group(3));
                o.lines.add(line);
                raw.add(new long[]{line.sold, line.want, Long.parseLong(m.group(4).replace(",", ""))});
                continue;
            }
            Matcher r = REMAIN.matcher(l);
            if (r.find()) {
                o.remaining = Long.parseLong(r.group(1).replace(",", ""));
                remainSeen = true;
            }
        }
        // 줄 가격이 "주문 전체"인지 "남은 잔"인지: 판매한 잔이 있고 줄 가격 합이 남은 판매 가격과 같으면 남은 잔 기준
        long sum = 0;
        boolean anySold = false;
        for (long[] v : raw) {
            sum += v[2];
            if (v[0] > 0) anySold = true;
        }
        boolean remainingBased = anySold && remainSeen && sum == o.remaining;
        for (int i = 0; i < o.lines.size(); i++) {
            long[] v = raw.get(i);
            long cups = remainingBased ? Math.max(1, v[1] - v[0]) : Math.max(1, v[1]);
            o.lines.get(i).perCup = Math.round((double) v[2] / cups);
        }
        if (!remainSeen) {
            for (OrderLine l : o.lines) o.remaining += l.perCup * l.left();
        }
        String sig = o.customer + lore;
        if (LOGGED.size() < 30 && LOGGED.add(sig)) DdingMate.LOG.info("바리스타 손님 주문 읽음: {} | {}", name, lore);
        return o;
    }

    /** 표에 있는 커피 이름으로(띄어쓰기·등급 배지 차이 무시). 모르면 그대로 */
    static String canonicalCoffee(String name) {
        String key = ItemKeys.normalize(name);
        for (String c : PRICE.keySet()) if (ItemKeys.normalize(c).equals(key)) return c;
        return name;
    }

    private static void remember(Order o) {
        Map<String, Order> mine = mine(true);
        if (mine == null) return;
        Order old = mine.get(o.customer);
        boolean same = old != null && old.remaining == o.remaining && old.lines.size() == o.lines.size() && fresh(old);
        if (same) {
            for (int i = 0; i < o.lines.size(); i++) {
                OrderLine a = old.lines.get(i), b = o.lines.get(i);
                if (!a.coffee.equals(b.coffee) || a.sold != b.sold || a.want != b.want) same = false;
            }
        }
        mine.put(o.customer, o);
        if (!same) save();
    }

    /** 이번 일일 초기화(03시) 뒤에 읽은 주문인가 */
    public static boolean fresh(Order o) {
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        return o.at >= TodoList.lastReset(DdingMateConfig.Reset.DAILY, now).toInstant().toEpochMilli();
    }

    /** 기억하는 손님 주문(샐리·빈센트·웬디·아이비 순, 모르는 손님은 뒤) */
    public static List<Order> orders() {
        Map<String, Order> mine = mine(false);
        if (mine == null || mine.isEmpty()) return List.of();
        List<Order> out = new ArrayList<>();
        for (String c : CUSTOMERS) if (mine.containsKey(c)) out.add(mine.get(c));
        for (Order o : mine.values()) if (!CUSTOMERS.contains(o.customer)) out.add(o);
        return out;
    }

    /** 만들어야 할 커피 한 줄: 남은 잔 수(오늘 읽은 주문만), 가진 잔 수, 한 잔 값 */
    public record Need(String coffee, int left, int have, long perCup) {
        public int toMake() {
            return Math.max(0, left - have);
        }
    }

    /** 오늘 읽은 주문을 커피별로 모은다. 같은 커피를 여러 손님이 원하면 잔 수를 더한다 */
    public static List<Need> needs(Map<String, Integer> have) {
        Map<String, int[]> left = new LinkedHashMap<>();
        Map<String, Long> cup = new LinkedHashMap<>();
        for (Order o : orders()) {
            if (!fresh(o)) continue;
            for (OrderLine l : o.lines) {
                if (l.left() <= 0) continue;
                left.computeIfAbsent(l.coffee, k -> new int[1])[0] += l.left();
                cup.merge(l.coffee, l.perCup, Math::max);
            }
        }
        List<Need> out = new ArrayList<>();
        left.forEach((c, n) -> out.add(new Need(c, n[0], have.getOrDefault(c, 0), cup.get(c))));
        return out;
    }

    /** 지금 가진 커피로 팔 수 있는 금액(나눠 팔 수 있음, 위키) */
    public static long sellableNow(List<Need> needs) {
        long t = 0;
        for (Need n : needs) t += Math.min(n.left(), n.have()) * n.perCup();
        return t;
    }

    /** 오늘 읽은 주문의 남은 판매 가격 합 */
    public static long remainingTotal() {
        long t = 0;
        for (Order o : orders()) if (fresh(o)) t += o.remaining;
        return t;
    }

    /** 판매 합계: 원하는 잔 수와 가진 잔 수 중 작은 쪽만큼(나눠 팔 수 있음, 위키) */
    public static long total(List<Line> lines) {
        long t = 0;
        for (Line l : lines) t += (long) Math.min(l.want(), l.have()) * l.price();
        return t;
    }

    /** 다 갖췄을 때 합계 */
    public static long fullTotal(List<Line> lines) {
        long t = 0;
        for (Line l : lines) t += (long) l.want() * l.price();
        return t;
    }

    public static Map<String, Integer> inventoryCoffee(MinecraftClient client) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (client.player == null) return out;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            String c = coffeeName(inv.getStack(i));
            if (c != null) out.merge(c, inv.getStack(i).getCount(), Integer::sum);
        }
        return out;
    }

    /** 아이템이 커피면 표의 이름(등급 배지·띄어쓰기 차이는 무시), 아니면 null */
    static String coffeeName(ItemStack s) {
        if (s.isEmpty()) return null;
        String key = ItemKeys.normalize(s.getName().getString());
        for (String c : PRICE.keySet()) if (ItemKeys.normalize(c).equals(key)) return c;
        return null;
    }

    private static List<String> lore(ItemStack s) {
        List<String> out = new ArrayList<>();
        var l = s.get(DataComponentTypes.LORE);
        if (l != null) for (Text t : l.lines()) out.add(ItemKeys.stripGlyphs(t.getString()));
        return out;
    }

    /** 전문가 창의 "[세레니티] 커피값이 밥값 (+N)" 칸에서 지금 판매가 증가율을 읽는다 */
    private static void readSkill(GenericContainerScreen screen) {
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            String name = ItemKeys.stripGlyphs(slot.getStack().getName().getString());
            if (!name.contains("커피값이 밥값")) continue;
            int pct = 0;
            for (String l : lore(slot.getStack())) {
                if (PREVIEW.matcher(l).find()) break;
                Matcher m = BONUS.matcher(l);
                if (m.find()) {
                    pct = Integer.parseInt(m.group(1));
                    break;
                }
            }
            if (name.matches(".*\\(\\s*\\+?\\s*0\\s*\\).*") || name.contains("잠김")) pct = 0;
            if (DdingMate.config().expert.coffeeBonus != pct) {
                DdingMate.config().expert.coffeeBonus = pct;
                DdingMate.config().save();
            }
        }
    }

    // ---------------------------------------------------------------- 저장

    private static Map<String, Order> mine(boolean create) {
        if (store == null) load();
        String key = kr.ddingmate.skill.SkillTimers.accountKey();
        if (key == null) key = "test";
        Map<String, Order> m = store.accounts.get(key);
        if (m == null && create) {
            m = new LinkedHashMap<>();
            store.accounts.put(key, m);
        }
        return m;
    }

    private static void load() {
        store = new Store();
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Store s = GSON.fromJson(r, Store.class);
            if (s != null && s.accounts != null) {
                s.accounts.values().removeIf(Objects::isNull);
                for (Map<String, Order> m : s.accounts.values()) {
                    m.values().removeIf(o -> o == null || o.customer == null || o.lines == null);
                    for (Order o : m.values()) o.lines.removeIf(l -> l == null || l.coffee == null);
                }
                store = s;
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("바리스타 주문 기록을 읽지 못했어요: {}", p, e);
        }
    }

    private static void save() {
        if (store == null) return;
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(store), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DdingMate.LOG.error("바리스타 주문 기록 저장 실패", e);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-barista.json");
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugOrder(List<Line> lines) {
        order = lines;
        kind = lines.isEmpty() ? Kind.NONE : Kind.ORDER_ITEMS;
    }

    /** 시험용: 별 아이콘 하나를 읽은 것처럼 */
    public static Order debugRead(ItemStack star) {
        Order o = parseOrder(star);
        if (o != null) {
            remember(o);
            kind = Kind.CUSTOMER;
            current = o.customer;
        }
        return o;
    }

    public static void debugAge(String customer, long at) {
        Map<String, Order> m = mine(false);
        if (m != null && m.containsKey(customer)) m.get(customer).at = at;
    }

    public static void debugClear() {
        store = new Store();
        kind = Kind.NONE;
        current = null;
        order = List.of();
    }

    public static void debugKind(Kind k, String customer) {
        kind = k;
        current = customer;
    }
}
