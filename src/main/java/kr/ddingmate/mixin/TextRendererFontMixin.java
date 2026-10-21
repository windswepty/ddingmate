package kr.ddingmate.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * "게임 전체에 적용"(알림 방식 탭 → 글꼴): 마인크래프트 기본 글꼴(minecraft:default)로 그리는 글자를 띵메이트에서 고른 글꼴로.
 * 띵메이트 글꼴(ddingmate:body…)은 [고른 TTF → minecraft:default] 순서라, 그 글꼴에 없는 글자(서버 리소스팩의 아이콘·창 그림·
 * 간격 글자)는 그대로 서버 글꼴로 그려진다. 화면에 그리는 글자 모양만 바뀌고 서버로는 아무것도 보내지 않는다.
 */
@Mixin(TextRenderer.class)
public class TextRendererFontMixin {

    @ModifyVariable(method = "getFontStorage", at = @At("HEAD"), argsOnly = true)
    private Identifier ddingmate$gameFont(Identifier id) {
        return kr.ddingmate.ui.Theme.gameFont(id);
    }
}
