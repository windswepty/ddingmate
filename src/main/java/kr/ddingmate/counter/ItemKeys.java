package kr.ddingmate.counter;

import net.minecraft.component.ComponentChanges;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;

import java.util.Map;
import java.util.Optional;

/**
 * 아이템을 셀 때 쓰는 키. 서버가 이름을 붙인 아이템(코룸 주괴 등)은 이름으로, 이름을 바꾸지 않은
 * 바닐라 아이템은 아이템 id 로 구분한다. 그래야 게임 언어와 상관없이 같은 결과가 나온다.
 */
public final class ItemKeys {

    private ItemKeys() {}

    /**
     * 색 코드·서버 전용 그림 글자(등급 아이콘 등)·앞쪽 [분류]·뒤쪽 강화 수치·공백을 지워 이름 비교를 너그럽게 한다.
     * 예: "§6[강화석] 코룸 주괴" → "코룸주괴", "ꀆ세이지 곡괭이 [+11]" → "세이지곡괭이"
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = Formatting.strip(s);
        if (t == null) return "";
        t = stripGlyphs(t);
        t = t.replaceFirst("^(\\s*\\[[^\\]]*\\])+", "");
        t = t.replaceFirst("(\\s*\\[\\+\\d+\\])+\\s*$", "");
        return t.replaceAll("\\s+", "");
    }

    /** 서버 전용 그림 글자만 지운다 */
    public static String stripGlyphs(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        t.codePoints().forEach(cp -> {
            String star = starGlyph(cp);
            if (star != null) sb.append(' ').append(star);
            else if (!serverGlyph(cp)) sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    /**
     * 서버는 별 개수를 한자 모양 글자로 보내고 리소스팩이 별 그림으로 바꿔 그린다(2026-10 실제 시설 창에서 확인):
     * 産 = ★, 山 = ★★, 算 = ★★★. 레시피 이름("굴 ★★")과 맞추려고 별로 바꾼다.
     */
    private static String starGlyph(int cp) {
        return switch (cp) {
            case 0x7523 -> "★";   // 産
            case 0x5C71 -> "★★";  // 山
            case 0x7B97 -> "★★★"; // 算
            default -> null;
        };
    }

    /**
     * 서버 리소스팩이 그림(등급 아이콘·말머리 등)으로 바꿔 쓰는 글자인지.
     * 서버는 이 섬 문자(U+A000~), 사용자 정의 영역, 비어 있는 보조 평면(U+CFFFB 등), 몽골 문자 보충(U+11661 등)
     * 같은 여러 곳의 글자를 그림으로 쓰므로, 이름에 실제로 쓰이는 글자(한글·영문·숫자·일반 기호·한자)만 남긴다.
     */
    private static boolean serverGlyph(int cp) {
        boolean name = (cp >= 0x20 && cp <= 0x24F)          // 영문·숫자·라틴 문자
                || (cp >= 0x2000 && cp <= 0x2BFF)           // 문장 부호·로마 숫자·화살표·도형·상자 그림(├ └)
                || (cp >= 0x3000 && cp <= 0x303F)           // 한중일 문장 부호
                || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)           // 한글
                || (cp >= 0x4E00 && cp <= 0x9FFF)           // 한자
                || (cp >= 0xFF00 && cp <= 0xFFEF)           // 전각 문자
                || cp == '\t' || cp == '\n';
        return !name;
    }

    /** 예전 규칙으로 저장된 키를 지금 규칙으로 다시 만든다 */
    public static String rekey(String key) {
        return key != null && key.startsWith("n:") ? nameKey(key.substring(2)) : key;
    }

    public static String nameKey(String name) {
        return "n:" + normalize(name);
    }

    public static String vanillaKey(String id) {
        return "v:" + id;
    }

    public static String keyOf(ItemStack stack) {
        if (stack.isEmpty()) return "";
        if (renamed(stack)) return nameKey(stack.getName().getString());
        return vanillaKey(Registries.ITEM.getId(stack.getItem()).toString());
    }

    public static void add(Map<String, Integer> counts, ItemStack stack) {
        if (stack.isEmpty()) return;
        String key = keyOf(stack);
        if (key.equals("n:")) return; // 이름이 빈 칸(창 꾸미기 유리판 등)은 세지 않는다
        counts.merge(key, stack.getCount(), Integer::sum);
        IconCache.remember(stack);
    }

    /**
     * 보관 개수 줄. 띵타이쿤 아이템 저장소는 "- 재고: 2,868 / 5,000개"(앞 = 재고, 뒤 = 칸의 최대치) 이다(2026-10 실제 창).
     * 낱말 뒤 첫 숫자만 읽어서 최대치(5,000)를 재고로 착각하지 않는다.
     */
    private static final java.util.regex.Pattern STORED = java.util.regex.Pattern.compile(
            "(재고|보관|저장량|적재|수량|보유|잔여)[^\\d]*([\\d,]+)");
    /** 낱말이 없을 때: "1,234 / 5,000개" 면 앞 수, 아니면 "1,234개" */
    private static final java.util.regex.Pattern RATIO = java.util.regex.Pattern.compile("([\\d,]+)\\s*/\\s*[\\d,]+");
    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("([\\d,]+)\\s*개");
    /** 조작 안내 줄(꺼낼 개수 등)·세트 수·구매 비용 줄은 보관 개수가 아니다 */
    private static final java.util.regex.Pattern NOT_STOCK = java.util.regex.Pattern.compile(
            "꺼내|넣기|넣어|클릭|판매|구매|비용|이동|쉬프트|Shift|\\(좌\\)|\\(우\\)|세트");

    /**
     * 설명 문구에 적힌 보관 개수. "재고: 2,868 / 5,000개" → 2868. 낱말이 없으면 "A / B" 의 A, 그다음 "N개".
     * 조작 안내·세트·비용 줄은 건너뛴다. 못 찾으면 -1.
     */
    public static int storedAmount(ItemStack stack) {
        net.minecraft.component.type.LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        long fallback = -1;
        for (net.minecraft.text.Text line : lore.lines()) {
            String t = Formatting.strip(line.getString());
            if (t == null) continue;
            t = stripGlyphs(t);
            if (NOT_STOCK.matcher(t).find()) continue;
            java.util.regex.Matcher m = STORED.matcher(t);
            if (m.find()) {
                long v = parse(m.group(2));
                if (v >= 0) return (int) Math.min(Integer.MAX_VALUE, v);
            }
            if (fallback < 0) {
                java.util.regex.Matcher r = RATIO.matcher(t);
                java.util.regex.Matcher c = COUNT.matcher(t);
                if (r.find()) fallback = parse(r.group(1));
                else if (c.find()) fallback = parse(c.group(1));
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, fallback);
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean renamed(ItemStack stack) {
        ComponentChanges ch = stack.getComponentChanges();
        return present(ch.get(DataComponentTypes.CUSTOM_NAME)) || present(ch.get(DataComponentTypes.ITEM_NAME));
    }

    private static boolean present(Optional<?> o) {
        return o != null && o.isPresent();
    }
}
