package dev.orbit.cosmetic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.render.VanillaPlayerModels;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the cape with a plain vanilla cape model whenever the player has the vanilla body (while emoting, and in
 * Orbit's menus; see {@link LivingEntityRendererMixin}), so it hangs from that body instead of where a model pack's
 * cape expects its own body to be.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
	@Shadow
	@Final
	@Mutable
	private HumanoidModel<AvatarRenderState> model;

	@Unique
	private HumanoidModel<AvatarRenderState> orbit$replaced;

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("HEAD"))
	private void orbit$vanillaCape(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (VanillaPlayerModels.isUsedFor(state)) {
			orbit$replaced = model;
			model = VanillaPlayerModels.cape();
		}
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("RETURN"))
	private void orbit$restoreCape(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (orbit$replaced != null) {
			model = orbit$replaced;
			orbit$replaced = null;
		}
	}
}
