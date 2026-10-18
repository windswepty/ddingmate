package kr.ddingmate.mixin;

import kr.ddingmate.counter.BagTracker;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 서버가 화면 아래(액션바)·가운데(제목)에 띄우는 글은 채팅 이벤트를 거치지 않는다.
 * 자동 수납 알림 "[아이템 저장소] 조약돌 1개 수납! (2/5,000)" 을 여기서 받아 저장소 재고를 맞춘다.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudOverlayMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void ddingmate$overlay(Text message, boolean tinted, CallbackInfo ci) {
        if (message == null) return;
        BagTracker.onOverlay(message.getString());
        kr.ddingmate.game.ServerHud.onOverlay(message.getString()); // 잠수 채널 알아보기
        kr.ddingmate.skill.SkillSignals.onOverlay(message.getString()); // 스킬을 켠 직후 액션바(진단 기록)
        kr.ddingmate.expert.OceanOrder.onMessage(message.getString());
    }

    @Inject(method = "setTitle", at = @At("HEAD"))
    private void ddingmate$title(Text title, CallbackInfo ci) {
        if (title == null) return;
        BagTracker.onOverlay(title.getString());
        kr.ddingmate.skill.SkillSignals.onOverlay(title.getString());
        kr.ddingmate.expert.OceanOrder.onMessage(title.getString());
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void ddingmate$subtitle(Text subtitle, CallbackInfo ci) {
        if (subtitle == null) return;
        BagTracker.onOverlay(subtitle.getString());
        kr.ddingmate.skill.SkillSignals.onOverlay(subtitle.getString());
        kr.ddingmate.expert.OceanOrder.onMessage(subtitle.getString());
    }
}
