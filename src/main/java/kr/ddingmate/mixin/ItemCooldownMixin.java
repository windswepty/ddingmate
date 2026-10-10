package kr.ddingmate.mixin;

import kr.ddingmate.skill.SkillSignals;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 서버가 내 도구에 건 아이템 쿨타임(길이 틱)을 스킬 재사용 대기 시각으로 쓴다. 읽기만 하고 바꾸지 않는다 */
@Mixin(ItemCooldownManager.class)
public abstract class ItemCooldownMixin {

    @Inject(method = "set(Lnet/minecraft/util/Identifier;I)V", at = @At("HEAD"))
    private void ddingmate$onSet(Identifier group, int duration, CallbackInfo ci) {
        MinecraftClient c = MinecraftClient.getInstance();
        // 내 클라이언트 플레이어의 쿨타임만(싱글플레이 내장 서버 쪽은 제외)
        if (c.player == null || (Object) this != c.player.getItemCooldownManager()) return;
        SkillSignals.onCooldownSet(group, duration);
    }
}
