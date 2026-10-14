package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
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
 * 바리스타 주문 도우미. 세레니티 마을 분수 광장의 손님(샐리·빈센트·웬디·아이비)에게 말을 걸어 열린 창에서
 * 손님이 원하는 커피를 읽고, 내 인벤토리에 몇 잔 있는지와 판매 합계를 보여 준다. 판매가는 위키 바리스타 문서(2026-10) 표.
 * 서버에 아무것도 보내지 않고, 창에 보이는 것만 읽는다.
 */
public final class Barista {

    private Barista() {}

    /** 커피 → 판매가(G). 위키 [바리스타] 손님별 표 */
    public static final Map<String, Integer> PRICE = new LinkedHashMap<>();
    /** 커피 → 손님(등급) */
    public static final Map<String, String> CUSTOMER = new LinkedHashMap<>();

    static {
        add("샐리 (C · 레어)", "블랙 커피", 40_888, "카페 모카", 30_666, "화이트 모카", 25_555,
                "드립 커피", 35_777, "콜드 브루", 51_110, "아메리카노", 45_999);
        add("빈센트 (B · 에픽)", "플랫 마끼아또", 73_471, "머쉬룸 마끼아또", 81_457, "코코아 마끼아또", 49_513,
                "돌체 라떼", 60_694, "그린티 라떼", 39_930, "우드 라떼", 91_040);
        add("웬디 (A · 전설)", "플라워 카푸치노", 59_096, "썬더 카푸치노", 113_401, "가든 카푸치노", 71_874,
                "스톤 블렌디드", 102_221, "쿠키 블렌디드", 84_652, "다크 블렌디드", 124_582);
        add("아이비 (S · 신화)", "그린트리 프라페", 89_443, "체리 블라썸 프라페", 71_874, "프로즌 스노우 프라페", 145_665,
                "실버문 아인슈페너", 107_012, "오로라 아인슈페너", 160_998, "골든 아인슈페너", 130_332);
        PRICE.put("인스턴트 커피", 20_000);
        CUSTOMER.put("인스턴트 커피", "아무 손님");
    }

    private static void add(String customer, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            PRICE.put((String) pairs[i], (Integer) pairs[i + 1]);
            CUSTOMER.put((String) pairs[i], customer);
        }
    }

    /** 주문 한 줄: 커피, 원하는 잔 수, 내 인벤토리에 있는 잔 수 */
    public record Line(String coffee, int want, int have, int price) {}

    /** 지금 열린 창이 손님 주문 창이면 주문 줄, 아니면 빈 목록 */
    private static List<Line> order = List.of();
    private static final Set<String> LOGGED = new HashSet<>();

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
            order = List.of();
            return;
        }
        readSkill(screen);
        // 창 안(내 인벤토리 칸 말고)의 커피 칸
        Map<String, Integer> want = new LinkedHashMap<>();
        boolean hint = ORDER_HINT.matcher(ItemKeys.stripGlyphs(screen.getTitle().getString())).find();
        boolean facility = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            String name = coffeeName(s);
            List<String> lore = lore(s);
            for (String l : lore) {
                if (l.contains("레시피") || l.contains("제작 시작") || l.contains("제작 시간")) facility = true;
                if (ORDER_HINT.matcher(l).find()) hint = true;
            }
            if (name != null) want.merge(name, s.getCount(), Integer::sum);
        }
        // 시설·가방·상자처럼 커피가 그냥 들어 있는 창은 주문이 아니다: 주문을 알리는 문구가 있는 창만
        if (want.isEmpty() || facility || !hint) {
            order = List.of();
            return;
        }
        String sig = screen.getTitle().getString() + want.keySet();
        if (LOGGED.size() < 30 && LOGGED.add(sig)) DdingMate.LOG.info("바리스타 주문 창 읽음: {} | {}", ItemKeys.stripGlyphs(screen.getTitle().getString()), want);
        Map<String, Integer> have = inventoryCoffee(client);
        List<Line> out = new ArrayList<>();
        int bonus = DdingMate.config().expert.coffeeBonus;
        want.forEach((c, n) -> {
            int base = PRICE.getOrDefault(c, 0);
            int price = c.equals("인스턴트 커피") ? base : (int) Math.round(base * (100 + bonus) / 100.0);
            out.add(new Line(c, n, have.getOrDefault(c, 0), price));
        });
        order = out;
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

    private static Map<String, Integer> inventoryCoffee(MinecraftClient client) {
        Map<String, Integer> out = new LinkedHashMap<>();
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

    /** 시험용 */
    public static void debugOrder(List<Line> lines) {
        order = lines;
    }
}
