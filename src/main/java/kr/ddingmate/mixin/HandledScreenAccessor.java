package kr.ddingmate.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 상자형 창의 왼쪽 위 자리(칸 좌표는 여기서부터). 시설 창에서 눌러야 할 칸을 강조할 때 읽기만 한다 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int ddingmate$x();

    @Accessor("y")
    int ddingmate$y();
}
