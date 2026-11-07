package dev.orbit.cosmetic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	/** Attaches this frame's cosmetics (and the nameplate icon) to the player's render state. */
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL")
	)
	private void orbit$extractCosmetics(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (entity instanceof AbstractClientPlayer player) {
			OrbitClient.get().extract(player, state, partialTicks);
		}
	}

	/** Vanilla never shows the camera entity's name; the "show own nameplate" setting does in third person. */
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true)
	private void orbit$showOwnNameplate(Avatar entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && OrbitClient.get().shouldShowOwnNameplate(entity)) {
			cir.setReturnValue(true);
		}
	}

	/** First-person arms: the sleeve pixels that arm cosmetics cover are hidden, as in third person. */
	@ModifyVariable(method = "renderHand", at = @At("HEAD"), argsOnly = true)
	private Identifier orbit$maskedArmSkin(Identifier skin) {
		return OrbitClient.get().firstPersonSkin(skin);
	}

	/** A first-person arm a body part replaces: only the body part is drawn (with the arm's cosmetics). */
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void orbit$replaceArm(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		if (OrbitClient.get().replacesFirstPersonArm(model, arm)) {
			// The game resets the arm's pose before drawing it; the body part follows the same pose.
			arm.resetPose();
			model.leftArm.zRot = -0.1F;
			model.rightArm.zRot = 0.1F;
			OrbitClient.get().renderFirstPersonArm(model, poseStack, collector, light, arm);
			ci.cancel();
		}
	}

	/** First-person arms are rendered without render layers, so arm cosmetics are drawn here. */
	@Inject(method = "renderHand", at = @At("TAIL"))
	private void orbit$renderArmCosmetics(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		OrbitClient.get().renderFirstPersonArm(((AvatarRenderer<?>) (Object) this).getModel(), poseStack, collector, light, arm);
	}
}
