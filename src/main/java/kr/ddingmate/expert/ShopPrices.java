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
    /** 상점 창에서 본 실제 가격: 이름 → 가격 */
    private static final Map<String, Integer> seen = new LinkedHashMap<>();
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
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            Ware w = wareOf(s);
            if (w == null) continue;
            int price = priceOf(s);
            // 가방·상자에 판매품이 들어 있는 것만으로는 상점이 아니다: 가격이 적힌 칸만 센다
            if (price > 0) hits.merge(w.shop(), 1, Integer::sum);
            if (price > 0) {
                Integer old = seen.put(w.name(), price);
                if ((old == null || old != price) && LOGGED.size() < 60 && LOGGED.add(w.name() + price)) {
                    DdingMate.LOG.info("상점 가격 읽음: {} {} {}G", w.shop(), w.name(), price);
                }
            }
        }
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

    /** 칸 설명에서 가격: "판매·매입·가격·가치" 줄의 "1,234G"를 먼저, 없으면 첫 "…G" */
    static int priceOf(ItemStack s) {
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        int any = -1;
        for (Text t : lore.lines()) {
            String l = ItemKeys.stripGlyphs(t.getString());
            Matcher m = PRICE.matcher(l);
            if (!m.find()) continue;
            int v;
            try {
                v = Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (PRICE_HINT.matcher(l).find()) return v;
            if (any < 0) any = v;
        }
        return any;
    }

    // ---------------------------------------------------------------- 보유 판매품

    /** 보유한 판매품 한 줄: 개수, 한 개 값(상점에서 본 값 → 표), 합계 범위 */
    public record Owned(Ware ware, int count, int each, boolean fromShop, long low, long high) {}

    /** 이 전문가(또는 상점)의 판매품 중 보유한 것, 값이 큰 순 */
    public static List<Owned> owned(MinecraftClient client, String expert, String shop) {
        List<Owned> out = new ArrayList<>();
        if (client.player == null) return out;
        Map<String, Integer> counts = MaterialCounter.availableCounts(client);
        for (Ware w : WARES) {
            if (expert != null && !w.expert().equals(expert)) continue;
            if (shop != null && !w.shop().equals(shop)) continue;
            int n = MaterialCounter.have(counts, w.name());
            if (n <= 0) continue;
            Integer real = seen.get(w.name());
            int each = real != null ? real : w.max();
            long low = real != null ? (long) real * n : (long) w.min() * n;
            long high = real != null ? (long) real * n : (long) w.max() * n;
            out.add(new Owned(w, n, each, real != null, low, high));
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

    /** 시험용 */
    public static void debugShop(String shop) {
        openShop = shop;
    }

    /** 시험용 */
    public static void debugClear() {
        seen.clear();
        openShop = null;
    }
}
