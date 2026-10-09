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

    /** 재료 이름에 해당하는 아이템 키들. 이름이 같은 서버 아이템과, 이름 없는 바닐라 아이템을 모두 센다. */
    public static Set<String> keysFor(String materialName) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(ItemKeys.nameKey(materialName));
        String id = vanilla.get(materialName);
        if (id != null) keys.add(ItemKeys.vanillaKey(id));
        return keys;
    }
}
