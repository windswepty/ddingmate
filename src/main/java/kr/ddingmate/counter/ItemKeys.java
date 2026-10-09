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

    /** 색 코드·앞쪽 [분류] 표시·공백을 지워 이름 비교를 너그럽게 한다. 예: "§6[강화석] 코룸 주괴" → "코룸주괴" */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = Formatting.strip(s);
        if (t == null) return "";
        t = t.replaceFirst("^(\\s*\\[[^\\]]*\\])+", "");
        return t.replaceAll("\\s+", "");
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
        counts.merge(keyOf(stack), stack.getCount(), Integer::sum);
        IconCache.remember(stack);
    }

    private static boolean renamed(ItemStack stack) {
        ComponentChanges ch = stack.getComponentChanges();
        return present(ch.get(DataComponentTypes.CUSTOM_NAME)) || present(ch.get(DataComponentTypes.ITEM_NAME));
    }

    private static boolean present(Optional<?> o) {
        return o != null && o.isPresent();
    }
}
