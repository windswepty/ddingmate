package kr.ddingmate.counter;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 재료 아이콘. 서버 아이템(코룸 주괴 등)은 인벤토리·가방에서 본 실제 아이템을 기억해 두었다가 그 모양으로 그린다.
 * 서버 리소스팩 그림을 모드에 담지 않고, 게임이 이미 불러온 아이템을 그대로 그리는 방식이다.
 * 아직 본 적 없는 서버 아이템은 null(물음표 칸).
 */
public final class IconCache {

    private static final Map<String, ItemStack> SEEN = new HashMap<>();

    private IconCache() {}

    public static void remember(ItemStack stack) {
        if (stack.isEmpty()) return;
        String key = ItemKeys.keyOf(stack);
        if (!SEEN.containsKey(key)) SEEN.put(key, stack.copyWithCount(1));
    }

    /** 재료 이름으로 아이콘 찾기. 본 적 있는 아이템 → 바닐라 아이템 → null */
    public static ItemStack forMaterial(String materialName) {
        for (String key : RecipeBook.keysFor(materialName)) {
            ItemStack s = SEEN.get(key);
            if (s != null) return s;
        }
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
}
