package kr.ddingmate.counter;

import com.google.gson.Gson;
import kr.ddingmate.DdingMate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 위키 제작 시설·가공 시설 문서에서 옮겨 온 레시피 목록(assets/ddingmate/recipes.json).
 * 재료 이름은 위키 표기 그대로이고, 바닐라 아이템과 이름이 같은 재료는 아이템 id 도 함께 둔다.
 */
public final class RecipeBook {

    public static final class Material {
        public String name;
        public int count;
    }

    public static final class Recipe {
        public String id;
        public String facility;
        /** 제작 1번에 걸리는 초(시설 창 "제작 시간", 스킬 적용 전). 모르면 0. 실제 서버에서 모은 값 */
        public int time;
        public String category;
        public String name;
        public int output = 1;
        public List<Material> materials = new ArrayList<>();

        /** 목록·HUD 에 보일 이름. 여러 개 묶음 레시피는 결과 개수를 붙인다. */
        public String displayName() {
            return output > 1 ? name + " x" + output : name;
        }
    }

    private static final class Data {
        Map<String, String> vanilla = new LinkedHashMap<>();
        /** 실제 이름을 아직 확인하지 못했거나 서버·위키 표기가 다른 아이템: 대표 이름 → 다른 이름들(모두 같은 아이템으로 센다) */
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        List<Recipe> recipes = new ArrayList<>();
    }

    private static Map<String, Recipe> byId = Map.of();
    /** 결과물 이름 → 그 결과물을 만드는 첫 레시피 (조합이 여럿인 것은 빼고, 묶음 크기가 가장 작은 것) */
    private static Map<String, Recipe> byOutput = Map.of();
    private static List<Recipe> all = List.of();
    private static Map<String, String> vanilla = Map.of();
    private static Map<String, List<String>> aliases = Map.of();
    /** 정규화한 다른 이름 → 대표 이름 */
    private static Map<String, String> canonical = Map.of();

    private RecipeBook() {}

    public static void load() {
        try (InputStream in = RecipeBook.class.getResourceAsStream("/assets/ddingmate/recipes.json")) {
            if (in == null) throw new IllegalStateException("recipes.json 없음");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Data d = new Gson().fromJson(r, Data.class);
                Map<String, Recipe> map = new LinkedHashMap<>();
                for (Recipe rec : d.recipes) map.put(rec.id, rec);
                byId = map;
                Map<String, Recipe> out = new LinkedHashMap<>();
                for (Recipe rec : d.recipes) {
                    if (rec.name.contains("(조합 ")) continue;
                    Recipe prev = out.get(rec.name);
                    if (prev == null || rec.output < prev.output) out.put(rec.name, rec);
                }
                byOutput = out;
                all = List.copyOf(d.recipes);
                INSTANT.clear();
                vanilla = Map.copyOf(d.vanilla);
                aliases = d.aliases == null ? Map.of() : Map.copyOf(d.aliases);
                Map<String, String> canon = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : aliases.entrySet()) {
                    for (String other : e.getValue()) canon.put(ItemKeys.normalize(other), e.getKey());
                }
                canonical = canon;
            }
        } catch (Exception e) {
            DdingMate.LOG.error("레시피 목록을 읽지 못했어요", e);
        }
    }

    public static List<Recipe> all() {
        return all;
    }

    public static Recipe get(String id) {
        return id == null ? null : byId.get(id);
    }

    /** 이 이름의 아이템을 만드는 레시피. 없으면 null */
    public static Recipe recipeFor(String outputName) {
        if (outputName == null) return null;
        Recipe r = byOutput.get(outputName);
        return r != null ? r : byOutput.get(canonicalName(outputName));
    }

    /**
     * 광물 블록 1개 = 9개(바닐라 제작대 압축). 시설 레시피가 아니라서 레시피 목록·제작 시간·경로 강조에는 넣지 않고,
     * 하위 재료 펼치기에만 쓴다(2026-10-22 사용자: "다이아몬드 블록 1개 → 다이아몬드 9개")
     */
    private static final Map<String, Recipe> BLOCKS = blocks(
            "석탄 블록", "석탄", "구리 블록", "구리 주괴", "철 블록", "철 주괴", "금 블록", "금 주괴",
            "레드스톤 블록", "레드스톤 가루", "청금석 블록", "청금석", "다이아몬드 블록", "다이아몬드",
            "에메랄드 블록", "에메랄드", "네더라이트 블록", "네더라이트 주괴");

    private static Map<String, Recipe> blocks(String... pairs) {
        Map<String, Recipe> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Recipe r = new Recipe();
            r.id = "block:" + pairs[i];
            r.facility = "제작대";
            r.category = "광물 블록";
            r.name = pairs[i];
            Material m = new Material();
            m.name = pairs[i + 1];
            m.count = 9;
            r.materials.add(m);
            map.put(pairs[i], r);
        }
        return Map.copyOf(map);
    }

    /** 하위 재료로 펼칠 레시피: 시설 레시피, 없으면 광물 블록 압축. 없으면 null */
    public static Recipe subRecipeFor(String name) {
        Recipe r = recipeFor(name);
        if (r != null || name == null) return r;
        r = BLOCKS.get(name);
        return r != null ? r : BLOCKS.get(canonicalName(name));
    }

    /** 다른 이름(예: 위키 옛 표기 "활기찬 가죽 지갑")이면 대표 이름, 아니면 그대로 */
    public static String canonicalName(String name) {
        return name == null ? null : canonical.getOrDefault(ItemKeys.normalize(name), name);
    }

    /**
     * 제작 1번 시간(초). 시설 창·채팅에 보이는 이름("키론 오르골")으로도 찾을 수 있게
     * 레시피 이름 앞의 "(Common)" 같은 등급 표시와 뒤의 "(조합 1)" 은 떼고도 비교한다. 모르면 -1
     */
    public static long timeFor(String name) {
        String key = ItemKeys.normalize(canonicalName(name));
        for (Recipe r : all) {
            if (r.time > 0 && (ItemKeys.normalize(r.name).equals(key) || plainKey(r.name).equals(key))) return r.time;
        }
        return -1;
    }

    /** 채팅·창에 보인 이름이 이 레시피의 결과물인가(등급 표시·"(조합 N)"·다른 이름은 너그럽게) */
    public static boolean sameItem(Recipe r, String shownName) {
        if (r == null || shownName == null) return false;
        String key = ItemKeys.normalize(canonicalName(shownName));
        String plainShown = plainKey(shownName);
        return ItemKeys.normalize(r.name).equals(key) || plainKey(r.name).equals(key) || plainKey(r.name).equals(plainShown);
    }

    private static String plainKey(String name) {
        return ItemKeys.normalize(name.replaceFirst("^\\s*\\((Common|Normal|Rare|Epic|Legendary|Mythic)\\)\\s*", "")
                .replaceFirst("\\s*\\(조합\\s*\\d+\\)\\s*$", ""));
    }

    /**
     * 시설 창 레시피 칸(이름 + 아이템 개수)에 해당하는 레시피. 등급 표시·"(조합 N)" 은 떼고 비교하고,
     * 한 번에 여러 개 나오는 레시피는 칸의 아이템 개수(= 결과 개수)가 같은 것을 고른다. 모르면 null
     */
    public static Recipe forWindowSlot(String name, int count) {
        String key = ItemKeys.normalize(canonicalName(name));
        Recipe any = null;
        for (Recipe r : all) {
            if (!ItemKeys.normalize(r.name).equals(key) && !plainKey(r.name).equals(key)) continue;
            if (r.output == Math.max(1, count)) return r;
            if (any == null) any = r;
        }
        return any;
    }

    /** 넣자마자 만들어지는(기다리는 시간이 없는) 시설: 커피·대형 제작대·형광 모루(미니게임) */
    static final Set<String> INSTANT_FACILITIES = Set.of("바리스타 · 믹서기", "바리스타 · 커피 머신", "대형 제작대", "형광 모루");

    /**
     * 바로 만들어지는 아이템인가: 이 이름을 만드는 레시피가 모두 바로 만드는 시설의 것이면 true.
     * 기다리는 시설에서도 만드는 이름(예: 반짝 광택제)이나 레시피에 없는 이름은 false
     */
    public static boolean isInstant(String name) {
        if (name == null) return false;
        String key = ItemKeys.normalize(canonicalName(name));
        if (key.isEmpty()) return false;
        return INSTANT.computeIfAbsent(key, k -> {
            boolean any = false;
            for (Recipe r : all) {
                if (!ItemKeys.normalize(r.name).equals(k) && !plainKey(r.name).equals(k)) continue;
                if (!INSTANT_FACILITIES.contains(r.facility)) return false;
                any = true;
            }
            return any;
        });
    }

    /** isInstant 결과(매 틱 부르므로 이름마다 한 번만 계산) */
    private static final java.util.Map<String, Boolean> INSTANT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 이 이름의 아이템을 만드는 시설 이름(이름 비교는 너그럽게). 모르면 null */
    public static String facilityOf(String outputName) {
        Recipe r = recipeFor(outputName);
        if (r != null) return r.facility;
        String key = ItemKeys.normalize(canonicalName(outputName));
        for (Recipe rec : all) if (ItemKeys.normalize(rec.name).equals(key)) return rec.facility;
        for (Recipe rec : all) if (plainKey(rec.name).equals(key)) return rec.facility;
        return null;
    }

    /** 재료 이름에 해당하는 아이템 키들. 이름이 같은 서버 아이템과, 이름 없는 바닐라 아이템을 모두 센다. */
    public static Set<String> keysFor(String materialName) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(ItemKeys.nameKey(materialName));
        for (String other : aliases.getOrDefault(materialName, List.of())) keys.add(ItemKeys.nameKey(other));
        String id = vanilla.get(materialName);
        if (id != null) keys.add(ItemKeys.vanillaKey(id));
        return keys;
    }
}
