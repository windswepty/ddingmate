package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.counter.MaterialCounter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전문가별 판매품 시세. 위키 [상점가]·[형광 광산]·[영혼 명품] 문서(2026-10-15)의 매입가 표를 넣어 두고,
 * 상점 창에서 그 물건의 실제 가격을 보면 그 값을 먼저 쓴다(공예품처럼 품질에 따라 값이 다른 것은 표에 범위만 있다).
 * 보유 개수는 인벤토리 + 기록된 가방·아이템 저장소(재료 카운터와 같은 셈)로 센다. 화면에 보이는 것만 읽는다.
 */
public final class ShopPrices {

    private ShopPrices() {}

    /** 판매품 하나: 이름(레시피·아이템 이름 표기), 최저·최고 매입가(같으면 고정), 파는 NPC, 전문가 */
    public record Ware(String name, int min, int max, String shop, String expert) {
        public boolean fixed() {
            return min == max;
        }
    }

    public static final List<Ware> WARES = new ArrayList<>();
    /** 상점 NPC 이름 → 무엇을 사는 곳인지 */
    public static final Map<String, String> SHOPS = new LinkedHashMap<>();

    static {
        SHOPS.put("로니", "채광물·귀중품·형광 제련품");
        SHOPS.put("샤키", "연금품");
        SHOPS.put("포스", "공예품");
        SHOPS.put("픽스", "영혼·원혼·가죽 명품");
        SHOPS.put("카이", "꽃·향장품");
        // 채광 · 로니
        fixed("로니", "채광", "코룸 주괴", 3_500, "리프톤 주괴", 3_750, "세렌트 주괴", 4_000,
                "코룸 정동석", 7_200, "리프톤 정동석", 7_650, "세렌트 정동석", 8_100);
        fixed("로니", "채광",
                "(Common) 키론 오르골", 8_100, "(Normal) 키론 오르골", 8_502, "(Rare) 키론 오르골", 10_071, "(Mythic) 탁월한 키론 오르골", 159_840,
                "(Normal) 테라온 축음기", 8_910, "(Rare) 테라온 축음기", 9_335, "(Epic) 테라온 축음기", 10_951, "(Mythic)탁월한 테라온 축음기", 186_278,
                "(Rare) 실바니움 만년필", 9_720, "(Epic) 실바니움 만년필", 10_154, "(Legendary) 실바니움 만년필", 11_777, "(Mythic) 탁월한 실바니움 만년필", 209_412,
                "(Common) 라온 만화경", 8_100, "(Normal) 라온 만화경", 8_505, "(Rare) 라온 만화경", 9_901, "(Mythic) 탁월한 라온 만화경", 160_688,
                "(Normal) 제피르 라이터", 8_910, "(Rare) 제피르 라이터", 9_308, "(Epic) 제피르 라이터", 10_989, "(Mythic) 탁월한 제피르 라이터", 187_920,
                "(Rare) 아스트랄 이어커프", 9_720, "(Epic) 아스트랄 이어커프", 10_412, "(Legendary) 아스트랄 이어커프", 12_405, "(Mythic) 탁월한 아스트랄 이어커프", 209_824,
                "(Common)넬트 천체관측기", 8_100, "(Normal)넬트 천체관측기", 8_486, "(Rare) 넬트 천체관측기", 9_956, "(Mythic) 탁월한 넬트 천체관측기", 155_232,
                "(Normal) 세피아 회중시계", 8_910, "(Rare) 세피아 회중시계", 9_545, "(Epic) 세피아 회중시계", 11_269, "(Mythic) 탁월한 세피아 회중시계", 186_300,
                "(Rare) 피로시아 단안경", 9_720, "(Epic) 피로시아 단안경", 11_055, "(Legendary) 피로시아 단안경", 12_507, "(Mythic) 탁월한 피로시아 단안경", 212_706);
        // 채광 · 형광 제련품(로니, 위키 형광 광산)
        fixed("로니", "채광", "오르딘 미니 망치", 55_000, "루미트 강철 방패", 85_000, "크레온 장인 석궁", 150_000,
                "벨릭 사냥 스피어", 200_000, "세르칸 초승달 단검", 400_000);
        // 해양 · 샤키(연금품)
        fixed("샤키", "해양", "추출된 희석액", 18_444, "영생의 아쿠티스 ★", 5_159, "크라켄의 광란체 ★", 5_234, "리바이던의 깃털 ★", 5_393,
                "해구의 파동 코어 ★★", 11_131, "침묵의 심해 비약 ★★", 11_242, "청해룡의 날개 ★★", 11_399,
                "아쿠아 펄스 파편 ★★★", 21_833, "나우틸러스의 손 ★★★", 22_088, "무저의 척추 ★★★", 22_227);
        // 해양 · 포스(공예품: 품질에 따라 최저~최고)
        range("포스", "해양", "조개껍데기 브로치", 15_000, 30_000, "푸른 향수병", 35_000, 70_000, "자개 손거울", 75_000, 150_000,
                "분홍 헤어핀", 125_000, 250_000, "자개 부채", 175_000, 350_000, "흑진주 시계", 0, 1_000_000);
        // 사냥 · 픽스
        for (String a : List.of("사슴", "미어캣", "기린", "코끼리", "하마", "플라밍고", "칠면조", "곰")) fixed("픽스", "사냥", a + "의 영혼", 2_500);
        for (String d : List.of("우티", "라프", "크록", "헤탄", "듀크")) {
            fixed("픽스", "사냥", "일반종 " + d + "의 원혼", 150_000, "희귀종 " + d + "의 원혼", 200_000, "환상종 " + d + "의 원혼", 300_000);
        }
        fixed("픽스", "사냥", "고독한 가죽 키링", 35_000, "신비한 가죽 팔찌", 38_500, "활기찬 가죽 장갑", 70_000,
                "특이한 가죽 파우치", 105_000, "화끈한 가죽 핸드백", 210_000);
        // 재배 · 카이(꽃·향장품)
        for (String f : List.of("아쿠아네타", "루밀리아", "솔라리스티", "벨라로제", "크리스텔라")) {
            fixed("카이", "재배", f + " ★", 50_000, f + " ★★", 125_000, f + " ★★★", 500_000);
        }
        for (String f : List.of("아쿠아네타 앰플", "루밀리아 디퓨저", "솔라리스티 캔들", "벨라로제 퍼퓸", "크리스텔라 오일")) {
            fixed("카이", "재배", f + " ★", 135_000, f + " ★★", 337_500, f + " ★★★", 1_350_000);
        }
    }

    private static void fixed(String shop, String expert, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            int p = (Integer) pairs[i + 1];
            WARES.add(new Ware((String) pairs[i], p, p, shop, expert));
        }
    }

    private static void range(String shop, String expert, Object... triples) {
        for (int i = 0; i < triples.length; i += 3) {
            WARES.add(new Ware((String) triples[i], (Integer) triples[i + 1], (Integer) triples[i + 2], shop, expert));
        }
    }

    // ---------------------------------------------------------------- 상점 창 읽기

    private static final Pattern PRICE = Pattern.compile("([\\d,]+)\\s*(?:G\\b|골드)");
    private static final Pattern PRICE_HINT = Pattern.compile("판매|매입|가격|가치");
    /**
     * 상점 창 칸의 실제 문구(2026-10-15 로니): "판매가격 : 3,500 골드" / "나의 판매가 : 3,675 골드" — 나의 판매가는
     * 서버가 전문가 스킬을 반영해 계산한 값(코룸 정동석 7,200 → 8,640, +20%). 귀중품은 아이템마다 "가격 : 16,227골드"
     * (귀중품 가치·전문가 배율 반영, 보석함 일괄 판매 안내)가 따로 적혀 있다.
     */
    /** 로니 "판매가격 : 3,500 골드", 포스 "판매가 : 824,662 골드 (↑796,645)" */
    private static final Pattern BASE = Pattern.compile("판매\\s*가격?\\s*:?\\s*([\\d,]+)");
    /** 귀중품 설명 "귀중품 가치 : 4,414" — 같은 귀중품의 가격("가격 : 16,227골드")을 다른 창에서도 쓰려고 함께 기억 */
    private static final Pattern WORTH = Pattern.compile("귀중품\\s*가치\\s*:?\\s*([\\d,]+)");
    private static final Pattern MINE = Pattern.compile("나의\\s*판매가\\s*:?\\s*([\\d,]+)");
    private static final Pattern ITEM_PRICE = Pattern.compile("^\\s*가격\\s*:?\\s*([\\d,]+)\\s*(?:골드|G)");

    /** 상점 창에서 본 가격: 이름 → {판매가격(기본), 나의 판매가(스킬 반영)}. 계정별로 저장 */
    private static final class Store {
        Map<String, Map<String, int[]>> accounts = new LinkedHashMap<>();
        /** 귀중품 "이름|가치" → 그 귀중품의 가격(보석함 창에서 본 값). 계정별 */
        Map<String, Map<String, Integer>> valuables = new LinkedHashMap<>();
    }

    private static Store store;
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> LOGGED = new HashSet<>();
    /** 지금 열린 창이 판매품 상점이면 그 NPC, 아니면 null */
    private static String openShop;

    public static String openShop() {
        return openShop;
    }

    /** 10틱마다: 창에 가격이 적힌 판매품이 3개 이상 보이면 그 상점 창으로 보고, 칸에 적힌 가격을 기억한다 */
    public static void scan(MinecraftClient client) {
        openShop = null;
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) return;
        Map<String, Integer> hits = new LinkedHashMap<>();
        boolean changed = false;
        // 보석함처럼 귀중품마다 가격이 적힌 창: 내 인벤토리 칸까지 "이름|가치 → 가격"을 기억한다
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            Ware w = wareOf(s);
            if (w == null || !group(w).equals("귀중품")) continue;
            int own = ownPrice(s), worth = worth(s);
            if (own <= 0 || worth <= 0) continue;
            Map<String, Integer> vm = valuables(true);
            Integer old = vm.put(w.name() + "|" + worth, own);
            if (old == null || old != own) changed = true;
        }
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            Ware w = wareOf(s);
            if (w == null) continue;
            int[] p = prices(s);
            // 가방·상자에 판매품이 들어 있는 것만으로는 상점이 아니다: 가격이 적힌 칸만 센다
            if (p == null || !plausible(w, p)) continue;
            hits.merge(w.shop(), 1, Integer::sum);
            if (group(w).equals("귀중품")) continue; // 귀중품은 아이템마다 값이 달라(가치에 따라) 하나로 기억하지 않는다
            Map<String, int[]> mine = seen(true);
            int[] old = mine.get(w.name());
            if (old == null || old[0] != p[0] || old[1] != p[1]) {
                mine.put(w.name(), p);
                changed = true;
                if (LOGGED.size() < 80 && LOGGED.add(w.name() + p[0] + "/" + p[1])) {
                    DdingMate.LOG.info("상점 가격 읽음: {} {} 판매가격 {}G · 나의 판매가 {}G", w.shop(), w.name(), p[0], p[1]);
                }
            }
        }
        if (changed) save();
        openShop = hits.entrySet().stream().filter(e -> e.getValue() >= 3).max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
    }

    /** 아이템이 표의 판매품이면 그 판매품(등급 귀중품은 모델 번호로 등급을 알아본 이름으로 맞춘다) */
    static Ware wareOf(ItemStack s) {
        if (s.isEmpty()) return null;
        return byKey().get(ItemKeys.keyOf(s));
    }

    private static Map<String, Ware> byKey;

    /** 아이템 키 → 판매품(처음 쓸 때 한 번 만든다) */
    private static Map<String, Ware> byKey() {
        if (byKey == null) {
            Map<String, Ware> m = new LinkedHashMap<>();
            for (Ware w : WARES) for (String k : kr.ddingmate.counter.RecipeBook.keysFor(w.name())) m.putIfAbsent(k, w);
            byKey = m;
        }
        return byKey;
    }

    /** 칸 설명의 {판매가격, 나의 판매가}. 나의 판매가가 없으면 판매가격과 같게, 판매가격이 없으면 "판매·가격" 줄의 첫 "…골드". 가격이 없으면 null */
    static int[] prices(ItemStack s) {
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return null;
        int base = -1, mine = -1;
        for (Text t : lore.lines()) {
            String l = ItemKeys.stripGlyphs(t.getString());
            Matcher m;
            if ((m = MINE.matcher(l)).find()) mine = num(m.group(1));
            else if ((m = BASE.matcher(l)).find()) base = num(m.group(1));
        }
        if (base <= 0 && mine <= 0) return null;
        if (trade(lore.lines())) return null;
        if (base <= 0) base = mine;
        if (mine <= 0) mine = base;
        return new int[]{base, mine};
    }

    /** 무역 창 칸(실제 포스 무역: "- 판매 수량 : 24개", "무역 물품을 판매합니다")인가 */
    private static boolean trade(List<Text> lines) {
        for (Text t : lines) {
            String l = ItemKeys.stripGlyphs(t.getString());
            if (l.contains("판매 수량") || l.contains("무역 물품") || l.matches(".*\\d+%\\s*가격.*")) return true;
        }
        return false;
    }

    /** 표의 값과 너무 다르면(다른 창의 묶음 값 등) 상점 가격으로 쓰지 않는다. 범위 판매품은 표 범위의 0.5~3배 */
    static boolean plausible(Ware w, int[] p) {
        if (w.max() <= 0) return true;
        double lo = Math.max(1, w.min()) * 0.5, hi = w.max() * 3.0;
        return p[0] >= lo && p[0] <= hi && p[1] >= lo && p[1] <= hi * 1.6;
    }

    /** 내 인벤토리 귀중품처럼 아이템 설명에 그 아이템만의 가격("가격 : 16,227골드")이 있으면 그 값, 없으면 -1 */
    static int ownPrice(ItemStack s) {
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text t : lore.lines()) {
            Matcher m = ITEM_PRICE.matcher(ItemKeys.stripGlyphs(t.getString()));
            if (m.find()) return num(m.group(1));
        }
        return -1;
    }

    /** 귀중품 설명의 "귀중품 가치 : N" (없으면 -1) */
    static int worth(ItemStack s) {
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text t : lore.lines()) {
            Matcher m = WORTH.matcher(ItemKeys.stripGlyphs(t.getString()));
            if (m.find()) return num(m.group(1));
        }
        return -1;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 판매품 묶음(같은 묶음은 전문가 스킬 배율이 같다고 본다): 주괴·정동석·귀중품·제련품·영혼·원혼·가죽 명품·꽃·향장품·연금품·공예품 */
    public static String group(Ware w) {
        String n = w.name();
        if (n.endsWith("주괴")) return "주괴";
        if (n.endsWith("정동석")) return "정동석";
        if (n.startsWith("(")) return "귀중품";
        if (w.shop().equals("로니")) return "제련품";
        if (n.endsWith("의 영혼")) return "영혼";
        if (n.endsWith("의 원혼")) return "원혼";
        if (n.contains("가죽")) return "가죽 명품";
        if (w.shop().equals("카이")) return n.matches(".*(앰플|디퓨저|캔들|퍼퓸|오일).*") ? "향장품" : "꽃";
        return w.shop().equals("샤키") ? "연금품" : "공예품";
    }

    /** 이 판매품의 {기본가, 나의 판매가}: 상점에서 본 값 → 같은 묶음에서 본 배율 × 표 → 표. 공예품처럼 범위면 최고가 기준 */
    public static int[] priceFor(Ware w) {
        Map<String, int[]> mine = seen(false);
        if (mine != null && mine.containsKey(w.name())) return mine.get(w.name());
        return new int[]{w.max(), (int) Math.round(w.max() * groupRatio(w))};
    }

    /** 같은 상점·같은 묶음에서 본 (나의 판매가 ÷ 판매가격) 평균. 본 게 없으면 1 */
    public static double groupRatio(Ware w) {
        Map<String, int[]> mine = seen(false);
        String g = group(w);
        double sum = 0;
        int n = 0;
        for (Ware o : mine == null ? List.<Ware>of() : WARES) {
            int[] p = mine.get(o.name());
            if (p == null || p[0] <= 0 || !o.shop().equals(w.shop()) || !group(o).equals(g)) continue;
            sum += (double) p[1] / p[0];
            n++;
        }
        if (n > 0) return sum / n;
        // 상점에서 같은 종류를 못 봤으면 /전문가 스킬 창에서 읽은 판매가 스킬 비율
        PriceSkills.Skill k = PriceSkills.forGroup(g);
        double pct = k == null ? -1 : PriceSkills.pct(k.name());
        return pct > 0 ? 1 + pct / 100.0 : 1;
    }

    /** 예상 배율이 어디서 왔는지: "상점" / "스킬 창" / "" */
    public static String ratioSource(Ware w) {
        Map<String, int[]> mine = seen(false);
        String g = group(w);
        if (mine != null) {
            for (Ware o : WARES) if (mine.containsKey(o.name()) && o.shop().equals(w.shop()) && group(o).equals(g)) return "상점";
        }
        PriceSkills.Skill k = PriceSkills.forGroup(g);
        return k != null && PriceSkills.pct(k.name()) > 0 ? "스킬 창" : "";
    }

    private static final Set<String> DIAG = new HashSet<>();

    /** 진단: 상점 판에 보유 판매품이 하나도 안 잡히면 인벤토리 아이템 키를 한 번 남긴다(로그에만) */
    static void diagnose(MinecraftClient client, String shop) {
        if (client.player == null || !DIAG.add(shop)) return;
        List<String> keys = new ArrayList<>();
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size() && keys.size() < 20; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) keys.add(ItemKeys.keyOf(s) + "×" + s.getCount());
        }
        DdingMate.LOG.info("상점 판 보유 판매품 없음({}): 인벤토리 {}칸 {}", shop, keys.size(), keys);
    }

    // ---------------------------------------------------------------- 보유 판매품

    /**
     * 보유한 판매품 한 줄: 개수, 한 개 기본가·나의 판매가, 상점에서 본 값인지, 합계 범위(나의 판매가 기준).
     * 인벤토리 귀중품처럼 아이템마다 가격이 적힌 것은 그 값을 더한다(exact)
     */
    public record Owned(Ware ware, int count, int base, int each, boolean fromShop, long low, long high, boolean exact) {
        /** 전문가 스킬로 늘어난 비율(%) */
        public int bonus() {
            return base <= 0 || exact ? 0 : (int) Math.round((each - base) * 100.0 / base);
        }
    }

    /** 이 전문가(또는 상점)의 판매품 중 보유한 것, 값이 큰 순 */
    public static List<Owned> owned(MinecraftClient client, String expert, String shop) {
        List<Owned> out = new ArrayList<>();
        if (client.player == null) return out;
        Map<String, Integer> counts = MaterialCounter.availableCounts(client);
        // 인벤토리에서 아이템마다 가격이 적힌 것(귀중품): 이름 → {개수, 가격 합}
        Map<String, long[]> exact = new LinkedHashMap<>();
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            Ware w = wareOf(s);
            if (w == null) continue;
            int own = ownPrice(s);
            if (own <= 0 && group(w).equals("귀중품")) {
                // 로니 창처럼 가격 줄이 없는 창: 보석함 창에서 본 같은 귀중품(이름·가치가 같은 것)의 가격
                Map<String, Integer> vm = valuables(false);
                Integer remembered = vm == null ? null : vm.get(w.name() + "|" + worth(s));
                if (remembered != null) own = remembered;
            }
            if (own <= 0) continue;
            long[] e = exact.computeIfAbsent(w.name(), k -> new long[2]);
            e[0] += s.getCount();
            e[1] += (long) own * s.getCount();
        }
        Map<String, int[]> mine = seen(false);
        for (Ware w : WARES) {
            if (expert != null && !w.expert().equals(expert)) continue;
            if (shop != null && !w.shop().equals(shop)) continue;
            int n = MaterialCounter.have(counts, w.name());
            if (n <= 0) continue;
            int[] p = priceFor(w);
            boolean fromShop = mine != null && mine.containsKey(w.name());
            long[] e = exact.get(w.name());
            int rest = n - (e == null ? 0 : (int) Math.min(n, e[0]));
            long known = e == null ? 0 : e[1];
            long low = known + (fromShop || w.fixed() ? (long) p[1] * rest : Math.round(w.min() * groupRatio(w)) * rest);
            long high = known + (long) p[1] * rest;
            int each = e != null && rest == 0 ? (int) (e[1] / Math.max(1, e[0])) : p[1];
            out.add(new Owned(w, n, p[0], each, fromShop, low, high, e != null));
        }
        out.sort((a, b) -> Long.compare(b.high(), a.high()));
        return out;
    }

    /** 합계(최저, 최고) */
    public static long[] total(List<Owned> list) {
        long lo = 0, hi = 0;
        for (Owned o : list) {
            lo += o.low();
            hi += o.high();
        }
        return new long[]{lo, hi};
    }

    /** "12,000G" 또는 범위 "15,000~30,000G" */
    public static String money(long lo, long hi) {
        return lo == hi ? String.format("%,dG", hi) : String.format("%,d~%,dG", lo, hi);
    }

    // ---------------------------------------------------------------- 저장

    private static Map<String, int[]> seen(boolean create) {
        if (store == null) load();
        String key = kr.ddingmate.skill.SkillTimers.accountKey();
        if (key == null) key = "test";
        Map<String, int[]> m = store.accounts.get(key);
        if (m == null && create) {
            m = new LinkedHashMap<>();
            store.accounts.put(key, m);
        }
        return m;
    }

    private static Map<String, Integer> valuables(boolean create) {
        if (store == null) load();
        if (store.valuables == null) store.valuables = new LinkedHashMap<>();
        String key = kr.ddingmate.skill.SkillTimers.accountKey();
        if (key == null) key = "test";
        Map<String, Integer> m = store.valuables.get(key);
        if (m == null && create) {
            m = new LinkedHashMap<>();
            store.valuables.put(key, m);
        }
        return m;
    }

    private static java.nio.file.Path path() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ddingmate-shop.json");
    }

    private static void load() {
        store = new Store();
        java.nio.file.Path p = path();
        if (!java.nio.file.Files.exists(p)) return;
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(p, java.nio.charset.StandardCharsets.UTF_8)) {
            Store s = GSON.fromJson(r, Store.class);
            if (s != null && s.accounts != null) {
                s.accounts.values().removeIf(java.util.Objects::isNull);
                for (Map<String, int[]> m : s.accounts.values()) {
                    m.values().removeIf(v -> v == null || v.length != 2 || v[0] <= 0 || v[1] <= 0);
                    m.keySet().removeIf(k -> k.startsWith("(")); // 예전 판(5bbce7f)이 귀중품 한 개 값을 상점 가격으로 저장했던 것
                    // 예전 판이 무역 창 묶음 값(코룸 주괴 24개 89,082골드)을 한 개 값으로 저장했던 것
                    m.entrySet().removeIf(e -> WARES.stream().anyMatch(w -> w.name().equals(e.getKey()) && !plausible(w, e.getValue())));
                }
                store = s;
            }
        } catch (java.io.IOException | RuntimeException e) {
            DdingMate.LOG.warn("상점 가격 기록을 읽지 못했어요: {}", p, e);
        }
    }

    private static void save() {
        try {
            java.nio.file.Files.createDirectories(path().getParent());
            java.nio.file.Files.writeString(path(), GSON.toJson(store), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            DdingMate.LOG.error("상점 가격 기록 저장 실패", e);
        }
    }

    /** 시험용 */
    public static void debugShop(String shop) {
        openShop = shop;
    }

    /** 시험용 */
    public static void debugClear() {
        store = new Store();
        openShop = null;
    }
}
