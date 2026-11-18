package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * Targets carry whole descriptors, so they are remapped where Minecraft is obfuscated.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	//? if >=1.21.11 {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private static void orbit$hideOwnNameplate(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		if (entity == Minecraft.getInstance().player) {
			cir.getReturnValue().nameTag = null;
		}
	}
	//?}

	//? if >=26.2 {
	@ModifyArg(method = "extractEntityInInventoryFollowsMouse", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;entity(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;IIII)V"), index = 0)
	private static EntityRenderState orbit$hudPose(EntityRenderState state) {
		dev.orbit.cosmetic.client.gfx.OrbitGraphics.poseEntity(state);
		return state;
	}
	//?} else if >=26.1 {
	/*@ModifyArg(method = "extractEntityInInventoryFollowsMouse", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;entity(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;IIII)V"), index = 0)
	private static EntityRenderState orbit$hudPose(EntityRenderState state) {
		dev.orbit.cosmetic.client.gfx.OrbitGraphics.poseEntity(state);
		return state;
	}
	*///?} else if >=1.21.11 {
	/*@ModifyArg(method = "extractEntityInInventoryFollowsMouse", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;submitEntityRenderState(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;IIII)V"), index = 0)
	private static EntityRenderState orbit$hudPose(EntityRenderState state) {
		dev.orbit.cosmetic.client.gfx.OrbitGraphics.poseEntity(state);
		return state;
	}
	*///?} else {
	/*// Before 1.21.11 the entity is drawn by renderEntityInInventory, which makes its render state itself: one hook
	// hides the nameplate and applies the HUD's pose.
	@ModifyArg(method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIFLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V",
		at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;submitEntityRenderState(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;IIII)V"), index = 0)
	private static EntityRenderState orbit$hudPose(EntityRenderState state, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) LivingEntity entity) {
		if (entity == Minecraft.getInstance().player) {
			state.nameTag = null;
		}
		dev.orbit.cosmetic.client.gfx.OrbitGraphics.poseEntity(state);
		return state;
	}
	*///?}
}
