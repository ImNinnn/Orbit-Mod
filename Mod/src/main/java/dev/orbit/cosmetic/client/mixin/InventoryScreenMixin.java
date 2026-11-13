package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the "show own nameplate" option out of GUI previews of the local player (inventory, Cosmetic Manager), and
 * lets HUD components pose the entity they draw (a paperdoll turned to an angle, its head as the player's).
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private static void orbit$hideOwnNameplate(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		if (entity == Minecraft.getInstance().player) {
			cir.getReturnValue().nameTag = null;
		}
	}

	@ModifyArg(method = "extractEntityInInventoryFollowsMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;entity"), index = 0)
	private static EntityRenderState orbit$hudPose(EntityRenderState state) {
		dev.orbit.cosmetic.client.hud.HudDoll.pose(state);
		return state;
	}
}
