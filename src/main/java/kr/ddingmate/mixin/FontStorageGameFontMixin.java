package kr.ddingmate.mixin;

import net.minecraft.client.font.BakedGlyph;
import net.minecraft.client.font.Font;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.Glyph;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * "게임 전체에 적용"(HUD 탭 → 글꼴): 기본 글꼴의 글자 글꼴(TTF·유니폰트) 글자를 고른 글꼴로 그린다. 서버 그림 글자는 그대로.
 * 판단은 {@link kr.ddingmate.ui.GameFont}. 화면에 그리는 글자 모양만 바뀌고 서버로는 아무것도 보내지 않는다.
 */
@Mixin(FontStorage.class)
public abstract class FontStorageGameFontMixin {

    @Shadow
    @Final
    private Identifier id;

    @Shadow
    private List<Font> availableFonts;

    @Inject(method = "getGlyph", at = @At("HEAD"), cancellable = true)
    private void ddingmate$glyph(int codePoint, boolean validateAdvance, CallbackInfoReturnable<Glyph> cir) {
        FontStorage other = kr.ddingmate.ui.GameFont.target(id, availableFonts, codePoint);
        if (other != null && other != (Object) this) cir.setReturnValue(other.getGlyph(codePoint, validateAdvance));
    }

    @Inject(method = "getBaked", at = @At("HEAD"), cancellable = true)
    private void ddingmate$baked(int codePoint, CallbackInfoReturnable<BakedGlyph> cir) {
        FontStorage other = kr.ddingmate.ui.GameFont.target(id, availableFonts, codePoint);
        if (other != null && other != (Object) this) cir.setReturnValue(other.getBaked(codePoint));
    }
}
