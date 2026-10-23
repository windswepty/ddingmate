package kr.ddingmate.mixin;

import net.minecraft.client.resource.DefaultClientResourcePackProvider;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 게임(클라이언트) 리소스팩 목록에 '띵메이트 내 글꼴' 팩을 더한다({@link kr.ddingmate.ui.UserFonts.Provider}).
 * 기본 클라이언트 팩을 가진 목록(= 게임 리소스팩)에만, 서버 데이터팩 목록에는 넣지 않는다.
 */
@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerUserFontsMixin {

    @Shadow
    @Final
    @Mutable
    private Set<ResourcePackProvider> providers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ddingmate$userFonts(ResourcePackProvider[] list, CallbackInfo ci) {
        boolean client = false;
        for (ResourcePackProvider p : list) if (p instanceof DefaultClientResourcePackProvider) client = true;
        if (!client) return;
        Set<ResourcePackProvider> all = new LinkedHashSet<>(providers);
        all.add(new kr.ddingmate.ui.UserFonts.Provider());
        providers = all;
    }
}
