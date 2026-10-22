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

    // ---------------------------------------------------------------- 전문가 보석(스킬 트리 창 위쪽 전문가 단추)

    /**
     * 띵타이쿤 스킬 트리 창의 전문가 단추 그림(2026-10-21 리소스팩 textures/font/skill_tree/ui/button/work_skilltree_*.png,
     * 18×18 파란 보석: mining 곡괭이 · ocean 낚싯대 · cultivation 괭이 · hunting 대검). 아이템이 아니라 창 그림이라
     * 지금 켜진 리소스팩에서 그 파일을 찾아 직접 그린다(이름공간이 바뀌어도 경로 끝으로 찾음). 안 배운 것은 흐린 보석(_none)
     */
    private static Map<String, net.minecraft.util.Identifier> gems;

    public static void onResourceReload() {
        gems = null;
    }

    private static Map<String, net.minecraft.util.Identifier> gems() {
        if (gems != null) return gems;
        Map<String, net.minecraft.util.Identifier> out = new HashMap<>();
        try {
            var found = net.minecraft.client.MinecraftClient.getInstance().getResourceManager()
                    .findResources("textures/font/skill_tree/ui/button", id -> id.getPath().contains("/work_skilltree_") && id.getPath().endsWith(".png"));
            for (net.minecraft.util.Identifier id : found.keySet()) {
                String file = id.getPath().substring(id.getPath().lastIndexOf('/') + 1, id.getPath().length() - 4);
                out.put(file.substring("work_skilltree_".length()), id);
            }
        } catch (Exception e) {
            DdingMate.LOG.warn("전문가 보석 그림을 찾지 못했어요", e);
        }
        gems = out;
        return gems;
    }

    /** "pickaxe"·"채광" → "mining" … 모르면 null */
    static String gemKey(String expertOrTool) {
        if (expertOrTool == null) return null;
        return switch (expertOrTool) {
            case "pickaxe", "채광" -> "mining";
            case "rod", "해양" -> "ocean";
            case "hoe", "재배" -> "cultivation";
            case "sword", "axe", "사냥" -> "hunting";
            default -> null;
        };
    }

    /** 전문가 보석을 (x, y)에 size 크기로. 리소스팩에 그림이 없으면 false(그때는 세이지 도구를 그리면 된다) */
    public static boolean drawGem(net.minecraft.client.gui.DrawContext ctx, String expertOrTool, boolean learned, int x, int y, int size) {
        String key = gemKey(expertOrTool);
        if (key == null) return false;
        net.minecraft.util.Identifier tex = gems().get(learned ? key : key + "_none");
        if (tex == null) tex = gems().get(key);
        if (tex == null) return false;
        ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, tex, x, y, 0, 0, size, size, 18, 18, 18, 18);
        return true;
    }

    /** 시험용: 리소스팩에서 찾은 보석 그림 수 */
    public static int debugGemCount() {
        gems = null;
        return gems().size();
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
