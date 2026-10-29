package kr.ddingmate.mixin;

import kr.ddingmate.gear.DurabilityTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 서버가 내 인벤토리 칸을 보낸 바로 그 순간 장비 내구도를 읽는다. 머리 치장(HMCCosmetics)·갑옷 가리기는 접속·이동 때
 * 진짜 장비를 먼저 보내고 곧바로 치장으로 바꿔 보내서(2026-10-29 기록: 진짜 부츠 → 버튼이 같은 초), 0.5초마다 읽으면 놓친다.
 * 읽기만 하고 아무것도 바꾸거나 보내지 않는다
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class InventoryPacketMixin {

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void ddingmate$onInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        DurabilityTracker.onInventoryPacket(MinecraftClient.getInstance());
    }

    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void ddingmate$onSlot(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        DurabilityTracker.onInventoryPacket(MinecraftClient.getInstance());
    }

    @Inject(method = "onSetPlayerInventory", at = @At("TAIL"))
    private void ddingmate$onPlayerSlot(SetPlayerInventoryS2CPacket packet, CallbackInfo ci) {
        DurabilityTracker.onInventoryPacket(MinecraftClient.getInstance());
    }
}
