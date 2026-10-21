package kr.ddingmate.skill;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import kr.ddingmate.DdingMate;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 서버 리소스팩 그림으로 그리는 전문가 아이콘. 그림은 서버 리소스팩이 그리고, 리소스팩이 없으면 바닐라 도구 모양으로 보인다.
 * <ul>
 * <li>스킬: 전문가 창의 파란 보석 아이콘(종이 + 모양 번호). assets/ddingmate/skill-icons.json 은 2026-10 실제 전문가 창에서
 * 모은 표로, 스킬 이름(띄어쓰기 없이) → on(배운 스킬)·off(잠긴 스킬) 모양 번호</li>
 * <li>도구: 세이지 도구 4종(실제 아이템 모양 번호)</li>
 * </ul>
 */
public final class SkillIcons {

    private SkillIcons() {}

    private record Pair(int on, int off) {}

    private static Map<String, Pair> table;

    /** 스킬 아이콘. 배운 스킬은 밝은 보석, 안 배운 스킬은 잠긴 보석(그 모양을 모르면 반대쪽). 표에 없으면 세이지 도구 */
    public static ItemStack forSkill(String name, boolean learned, String tool) {
        Pair p = table().get(key(name));
        if (p != null) {
            int cmd = learned ? (p.on > 0 ? p.on : p.off) : (p.off > 0 ? p.off : p.on);
            if (cmd > 0) return model(Items.PAPER, cmd);
        }
        return sageTool(tool);
    }

    /** 표에 그림이 있는 스킬인가(시험용) */
    public static boolean known(String name) {
        return table().containsKey(key(name));
    }

    /**
     * 세이지 도구(2026-10 실제 아이템): 곡괭이 golden_pickaxe#2016003, 낚싯대 fishing_rod#2016017, 대검 golden_sword#2016033,
     * 괭이 golden_hoe#2016027. tool 은 "pickaxe"·"rod"·"sword"·"hoe" 또는 전문가 이름(채광·해양·사냥·재배)
     */
    public static ItemStack sageTool(String tool) {
        if (tool == null) return new ItemStack(Items.EXPERIENCE_BOTTLE);
        return switch (tool) {
            case "pickaxe", "채광" -> model(Items.GOLDEN_PICKAXE, 2016003);
            case "rod", "해양" -> model(Items.FISHING_ROD, 2016017);
            case "sword", "axe", "사냥" -> model(Items.GOLDEN_SWORD, 2016033);
            case "hoe", "재배" -> model(Items.GOLDEN_HOE, 2016027);
            default -> new ItemStack(Items.EXPERIENCE_BOTTLE);
        };
    }

    private static ItemStack model(Item item, int cmd) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(List.of((float) cmd), List.of(), List.of(), List.of()));
        return s;
    }

    /** "곡괭이 나가신다! (+5)" → "곡괭이나가신다!" */
    static String key(String name) {
        if (name == null) return "";
        return name.replaceAll("\\s+", "").replaceFirst("^\\[[^\\]]*\\]", "").replaceFirst("\\((?:\\+\\d+|MAX|잠김)\\)$", "");
    }

    private static Map<String, Pair> table() {
        if (table != null) return table;
        Map<String, Pair> map = new HashMap<>();
        try (InputStream in = SkillIcons.class.getResourceAsStream("/assets/ddingmate/skill-icons.json")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = new Gson().fromJson(r, JsonObject.class);
                    for (String k : root.keySet()) {
                        JsonObject e = root.getAsJsonObject(k);
                        map.put(k, new Pair(e.has("on") ? e.get("on").getAsInt() : 0, e.has("off") ? e.get("off").getAsInt() : 0));
                    }
                }
            }
        } catch (Exception e) {
            DdingMate.LOG.warn("스킬 아이콘 표를 읽지 못했어요", e);
        }
        table = map;
        return table;
    }
}
