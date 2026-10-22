package kr.ddingmate.test;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 시험용 서버 아이템을 실제와 같은 모양으로 만든다(2026-10-22 사용자: 시험 화면에서 서버 그림이 제대로 안 나옴).
 * 예전에는 이름만 붙인 바닐라 종이라 서버 리소스팩을 켜도 흰 종이로 보였다. 모드의 서버 모양 표(icons.json, 실제 리소스팩
 * 모델 번호)에 그 이름이 있으면 그 아이템 종류·모델 번호로 만든다.
 */
final class TestItems {

    private TestItems() {}

    /** 실제 서버 창의 이름 없는 단추(레시피 목록·탭·뒤로가기): 종이 2025(2026-10-09 창 기록) */
    static final float BLANK_MODEL = 2025f;

    /** 이름이 붙은 서버 아이템(표에 없으면 종이) */
    static ItemStack server(String name, int count) {
        ItemStack table = kr.ddingmate.counter.IconCache.fromTable(name);
        ItemStack s = table != null ? table.copyWithCount(Math.max(1, Math.min(count, 99))) : new ItemStack(Items.PAPER, Math.max(1, Math.min(count, 64)));
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return s;
    }

    /** 이름 없는 단추 칸 */
    static ItemStack blank() {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        s.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(List.of(BLANK_MODEL), List.of(), List.of(), List.of()));
        return s;
    }
}
