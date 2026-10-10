package kr.ddingmate.counter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import kr.ddingmate.DdingMate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 재료 아이콘. 띵타이쿤 전용 아이템(치즈 조각 등)은 서버가 아이템에 붙여 보내는 모델 정보로 그려지므로,
 * 인벤토리·가방·내가 연 창(제작 시설 창 포함)에서 본 실제 아이템의 모양 정보를 기억해 두었다가 그대로 그린다.
 * 기억은 config/ddingmate-icons.json 에 남아 게임을 다시 켜도 유지된다.
 * 서버 리소스팩 그림을 모드에 담지 않고, 게임이 이미 불러온 리소스팩으로 그린다.
 * 아직 한 번도 본 적 없는 서버 아이템은 null(물음표 칸).
 */
public final class IconCache {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX = 4000;
    /** 그림에 필요한 정보만 남긴다(이름·설명은 버림) */
    private static final List<ComponentType<?>> LOOK = List.of(
            DataComponentTypes.ITEM_MODEL, DataComponentTypes.CUSTOM_MODEL_DATA, DataComponentTypes.DYED_COLOR,
            DataComponentTypes.PROFILE, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, DataComponentTypes.POTION_CONTENTS);

    private static final Map<String, ItemStack> SEEN = new LinkedHashMap<>();
    /** 내 인벤토리·가방이 아니라 다른 창에서만 본 것(인벤토리에서 보면 그 모양으로 바꾼다) */
    private static final Set<String> WEAK = new HashSet<>();
    private static RegistryWrapper.WrapperLookup lookup;
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    private IconCache() {}

    /** 내 인벤토리·가방·저장소에서 본 아이템 */
    public static void remember(ItemStack stack) {
        put(stack, false);
    }

    /** 이 이름(예: "토르엔 항해")의 그림으로 기억한다. 늘 마지막에 본 모양으로 바꾼다 */
    public static void rememberAs(String name, ItemStack stack) {
        if (stack.isEmpty()) return;
        ensureLoaded();
        String key = ItemKeys.nameKey(name);
        ItemStack before = SEEN.get(key);
        ItemStack now = slim(stack);
        if (before != null && ItemStack.areItemsAndComponentsEqual(before, now)) return;
        if (before == null && SEEN.size() >= MAX) return;
        SEEN.put(key, now);
        WEAK.remove(key);
        dirty = true;
    }

    /** 그 밖의 창(제작 시설 등)에서 본 아이템: 아직 모르는 것만 기억 */
    public static void rememberSeen(ItemStack stack) {
        put(stack, true);
    }

    private static void put(ItemStack stack, boolean weak) {
        if (stack.isEmpty()) return;
        ensureLoaded();
        String key = ItemKeys.keyOf(stack);
        if (key.isEmpty() || key.equals("n:") || key.startsWith("v:")) return; // 바닐라 아이템은 이름으로 바로 그릴 수 있다
        boolean known = SEEN.containsKey(key);
        if (known && (weak || !WEAK.contains(key))) return;
        if (!known && SEEN.size() >= MAX) return;
        SEEN.put(key, slim(stack));
        if (weak) WEAK.add(key);
        else WEAK.remove(key);
        dirty = true;
    }

    private static int worldScan;

    /**
     * 10틱마다: 지금 열려 있는 창의 아이템을 기억하고, 바뀐 게 있으면 가끔 저장.
     * 2초마다 주변에 보이는 아이템(땅에 떨어진 것, 상점 진열·아이템 액자, 다른 사람·갑옷 거치대가 든 것)도 기억한다.
     * 화면에 이미 보이는 것의 모양만 기억하며, 아무것도 보내지 않는다.
     */
    public static void tick(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> screen) {
            for (Slot slot : screen.getScreenHandler().slots) rememberSeen(slot.getStack());
        }
        if (client.world != null && ++worldScan % 4 == 0) {
            int n = 0;
            for (net.minecraft.entity.Entity e : client.world.getEntities()) {
                if (++n > 600) break; // 사람이 많은 곳에서도 가볍게
                if (e instanceof net.minecraft.entity.ItemEntity item) rememberSeen(item.getStack());
                else if (e instanceof net.minecraft.entity.decoration.ItemFrameEntity frame) rememberSeen(frame.getHeldItemStack());
                else if (e instanceof net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity display) rememberSeen(display.getItemStack());
                else if (e instanceof net.minecraft.entity.LivingEntity living && e != client.player) {
                    for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
                        rememberSeen(living.getEquippedStack(slot));
                    }
                }
            }
        }
        if (dirty && System.currentTimeMillis() - lastSave > 30_000) save();
    }

    /** 아이템 키(n:이름 / v:아이템 id)로 아이콘 찾기. 모르면 null */
    public static ItemStack forKey(String key) {
        if (key == null) return null;
        ensureLoaded();
        ItemStack s = SEEN.get(ItemKeys.rekey(key));
        if (s != null) return s;
        s = bundled().get(ItemKeys.rekey(key));
        if (s != null) return s;
        if (key.startsWith("v:")) {
            Identifier id = Identifier.tryParse(key.substring(2));
            Item item = id == null ? Items.AIR : Registries.ITEM.get(id);
            if (item != Items.AIR) return new ItemStack(item);
        }
        return null;
    }

    /**
     * 모드에 넣어 둔 이름 → 서버 아이템 모양 표(assets/ddingmate/icons.json).
     * 서버 리소스팩의 모델 이름(corum_ingot = 코룸 주괴 …)과 레시피 이름을 짝지어 만든 것이라,
     * 한 번도 본 적 없는 재료도 처음부터 그림이 나온다. 그림 자체는 서버 리소스팩이 그린다.
     */
    private static Map<String, ItemStack> bundled;

    private static Map<String, ItemStack> bundled() {
        if (bundled != null) return bundled;
        Map<String, ItemStack> out = new HashMap<>();
        try (var in = IconCache.class.getResourceAsStream("/assets/ddingmate/icons.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    ItemStack s = parseLook(e.getValue().getAsString());
                    if (s != null) out.put(ItemKeys.nameKey(e.getKey()), s);
                }
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("아이콘 표를 읽지 못했어요: {}", e.toString());
        }
        bundled = out;
        return out;
    }

    /** "paper:2006004" → 종이 + 모델 번호 2006004, "quartz" → 바닐라 석영 */
    static ItemStack parseLook(String look) {
        String[] parts = look.split(":");
        Identifier id = Identifier.tryParse("minecraft:" + parts[0]);
        Item item = id == null ? Items.AIR : Registries.ITEM.get(id);
        if (item == Items.AIR) return null;
        ItemStack s = new ItemStack(item);
        if (parts.length > 1) {
            float cmd = Float.parseFloat(parts[1]);
            s.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new net.minecraft.component.type.CustomModelDataComponent(List.of(cmd), List.of(), List.of(), List.of()));
        }
        return s;
    }

    /**
     * 레시피 대표 그림: 만드는 아이템 모양(예: 하급 라이프스톤). 이름 뒤 " x24"·앞 "(Rare)" 같은 표시는 떼고 찾고,
     * 그래도 모르면 첫 재료 모양. 아무것도 모르면 null
     */
    public static ItemStack forRecipe(RecipeBook.Recipe r) {
        if (r == null) return null;
        ItemStack s = forMaterial(r.name);
        if (s == null) {
            String plain = r.name.replaceFirst("\\s*[xX×]\\s*\\d+\\s*$", "").replaceFirst("^\\s*\\([A-Za-z]+\\)\\s*", "").trim();
            if (!plain.equals(r.name)) s = forMaterial(plain);
        }
        if (s == null && !r.materials.isEmpty()) s = forMaterial(r.materials.get(0).name);
        return s;
    }

    /** 모드에 넣어 둔 표에서만 찾기(본 적 있는 모양은 보지 않음). 없으면 null */
    public static ItemStack fromTable(String name) {
        return bundled().get(ItemKeys.nameKey(name));
    }

    /** 시험용: 본 적 있는 모양을 잊는다(파일은 그대로) */
    public static void debugForgetSeen() {
        SEEN.clear();
        WEAK.clear();
        loaded = true;
    }

    /** 시험용 */
    public static int bundledSize() {
        return bundled().size();
    }

    /** 재료 이름으로 아이콘 찾기. 본 적 있는 아이템 → 모드에 넣어 둔 표 → 바닐라 아이템 → null */
    public static ItemStack forMaterial(String materialName) {
        ensureLoaded();
        for (String key : RecipeBook.keysFor(materialName)) {
            ItemStack s = SEEN.get(key);
            if (s != null) return s;
        }
        ItemStack fromTable = bundled().get(ItemKeys.nameKey(materialName));
        if (fromTable != null) return fromTable;
        for (String key : RecipeBook.keysFor(materialName)) {
            if (key.startsWith("v:")) {
                Identifier id = Identifier.tryParse(key.substring(2));
                if (id == null) continue;
                Item item = Registries.ITEM.get(id);
                if (item != Items.AIR) return new ItemStack(item);
            }
        }
        return null;
    }

    /** 시험용: 메모리를 비우고 파일에서 다시 읽게 한다 */
    public static void reloadForTest() {
        save();
        SEEN.clear();
        WEAK.clear();
        loaded = false;
    }

    public static int size() {
        return SEEN.size();
    }

    private static ItemStack slim(ItemStack s) {
        ItemStack out = new ItemStack(s.getItem());
        for (ComponentType<?> type : LOOK) copy(s, out, type);
        return out;
    }

    private static <T> void copy(ItemStack from, ItemStack to, ComponentType<T> type) {
        T v = from.get(type);
        if (v != null) to.set(type, v);
    }

    // ---------------------------------------------------------------- 저장·불러오기

    private static RegistryWrapper.WrapperLookup registries() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.world != null) lookup = c.world.getRegistryManager();
        return lookup;
    }

    /** 처음 쓸 때 한 번 불러온다. 아이템 정보를 읽으려면 접속 중이어야 해서 접속 전이면 다음에 다시 시도 */
    private static void ensureLoaded() {
        if (loaded) return;
        RegistryWrapper.WrapperLookup reg = registries();
        if (reg == null) return;
        loaded = true;
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            var ops = reg.getOps(JsonOps.INSTANCE);
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String key = ItemKeys.rekey(e.getKey()); // 이름 규칙이 바뀌었을 수 있다
                if (key.equals("n:") || SEEN.containsKey(key)) continue;
                ItemStack.CODEC.parse(ops, e.getValue()).result().ifPresent(s -> {
                    SEEN.put(key, s);
                    WEAK.add(key); // 이번에 인벤토리에서 다시 보면 최신 모양으로 바꾼다
                });
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("아이콘 기억 파일을 읽지 못했어요: {}", e.toString());
        }
    }

    public static void save() {
        if (!dirty) return;
        ensureLoaded(); // 파일에만 있던 기억을 덮어쓰지 않게 먼저 합친다
        RegistryWrapper.WrapperLookup reg = registries();
        if (reg == null) return;
        lastSave = System.currentTimeMillis();
        dirty = false;
        var ops = reg.getOps(JsonOps.INSTANCE);
        JsonObject root = new JsonObject();
        for (Map.Entry<String, ItemStack> e : new HashMap<>(SEEN).entrySet()) {
            ItemStack.CODEC.encodeStart(ops, e.getValue()).result().ifPresent(j -> root.add(e.getKey(), j));
        }
        try {
            Path p = path();
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            DdingMate.LOG.warn("아이콘 기억 파일을 저장하지 못했어요: {}", e.toString());
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-icons.json");
    }
}
