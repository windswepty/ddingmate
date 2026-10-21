package kr.ddingmate.mixin;

import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 글꼴 이름으로 글꼴 묶음 꺼내기("게임 전체에 적용"에서 고른 글꼴 묶음을 찾을 때) */
@Mixin(TextRenderer.class)
public interface TextRendererAccessor {

    @Invoker("getFontStorage")
    FontStorage ddingmate$storage(Identifier id);
}
