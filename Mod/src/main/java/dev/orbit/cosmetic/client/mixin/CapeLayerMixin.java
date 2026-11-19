package dev.orbit.cosmetic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.render.VanillaPlayerModels;
import net.minecraft.client.model.HumanoidModel;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.entity.layers.CapeLayer;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import dev.orbit.cosmetic.client.gfx.PlayerState;
import dev.orbit.cosmetic.client.render.PlayerFrame;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
*///?}
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
//? if >=1.21.2 {
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
	@Shadow
	@Final
	@Mutable
	private HumanoidModel<AvatarRenderState> model;

	@Unique
	private HumanoidModel<AvatarRenderState> orbit$replaced;

	//? if >=1.21.9 {
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
	//?} else {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("HEAD"))
	private void orbit$vanillaCape(PoseStack poseStack, MultiBufferSource collector, int lightCoords, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (VanillaPlayerModels.isUsedFor(state)) {
			orbit$replaced = model;
			model = VanillaPlayerModels.cape();
		}
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("RETURN"))
	private void orbit$restoreCape(PoseStack poseStack, MultiBufferSource collector, int lightCoords, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (orbit$replaced != null) {
			model = orbit$replaced;
			orbit$replaced = null;
		}
	}
	*///?}
}
//?} else {
/*@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private CapeLayerMixin(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	/^*
	 * Before 1.21.2 the cape is part of the player model (the vanilla one while it is used), but hangs from the
	 * shoulders whatever the body does: during an emote it follows the body, as the cape model does since 1.21.2.
	 ^/
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
	private void orbit$capeFollowsEmote(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, float walkPos, float walkSpeed, float partialTick,
		float ageInTicks, float headYaw, float headPitch, CallbackInfo ci) {
		PlayerState state = PlayerState.of(player);
		PlayerFrame frame = state == null ? null : state.frame();
		if (frame != null && frame.emote != null) {
			getParentModel().body.translateAndRotate(poseStack);
		}
	}
}
*///?}
