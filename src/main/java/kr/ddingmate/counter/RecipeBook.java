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
        List<Recipe> recipes = new ArrayList<>();
    }

    private static Map<String, Recipe> byId = Map.of();
    /** 결과물 이름 → 그 결과물을 만드는 첫 레시피 (조합이 여럿인 것은 빼고, 묶음 크기가 가장 작은 것) */
    private static Map<String, Recipe> byOutput = Map.of();
    private static List<Recipe> all = List.of();
    private static Map<String, String> vanilla = Map.of();

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
                vanilla = Map.copyOf(d.vanilla);
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
        return byOutput.get(outputName);
    }

    /**
     * 제작 1번 시간(초). 시설 창·채팅에 보이는 이름("키론 오르골")으로도 찾을 수 있게
     * 레시피 이름 앞의 "(Common)" 같은 등급 표시와 뒤의 "(조합 1)" 은 떼고도 비교한다. 모르면 -1
     */
    public static long timeFor(String name) {
        String key = ItemKeys.normalize(name);
        for (Recipe r : all) {
            if (r.time > 0 && (ItemKeys.normalize(r.name).equals(key) || plainKey(r.name).equals(key))) return r.time;
        }
        return -1;
    }

    private static String plainKey(String name) {
        return ItemKeys.normalize(name.replaceFirst("^\\s*\\((Common|Normal|Rare|Epic|Legendary|Mythic)\\)\\s*", "")
                .replaceFirst("\\s*\\(조합\\s*\\d+\\)\\s*$", ""));
    }

    /** 이 이름의 아이템을 만드는 시설 이름(이름 비교는 너그럽게). 모르면 null */
    public static String facilityOf(String outputName) {
        Recipe r = recipeFor(outputName);
        if (r != null) return r.facility;
        String key = ItemKeys.normalize(outputName);
        for (Recipe rec : all) if (ItemKeys.normalize(rec.name).equals(key)) return rec.facility;
        for (Recipe rec : all) if (plainKey(rec.name).equals(key)) return rec.facility;
        return null;
    }

    /** 재료 이름에 해당하는 아이템 키들. 이름이 같은 서버 아이템과, 이름 없는 바닐라 아이템을 모두 센다. */
    public static Set<String> keysFor(String materialName) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(ItemKeys.nameKey(materialName));
        String id = vanilla.get(materialName);
        if (id != null) keys.add(ItemKeys.vanillaKey(id));
        return keys;
    }
}
