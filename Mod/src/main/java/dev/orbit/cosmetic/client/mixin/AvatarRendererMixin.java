package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.gfx.PlayerState;
import org.spongepowered.asm.mixin.Unique;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.orbit.cosmetic.client.render.PlayerFrameHolder;
import net.minecraft.network.chat.Component;
*///?}
import net.minecraft.resources.Identifier;
//? if >=1.21.9
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	//? if >=1.21.9 {
	/** Attaches this frame's cosmetics (and the nameplate icon) to the player's render state. */
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL")
	)
	private void orbit$extractCosmetics(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (entity instanceof AbstractClientPlayer player) {
			OrbitClient.get().extract(player, PlayerState.of(state), partialTicks);
		}
	}

	/** Vanilla never shows the camera entity's name; the "show own nameplate" setting does in third person. */
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true)
	private void orbit$showOwnNameplate(Avatar entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && OrbitClient.get().shouldShowOwnNameplate(entity)) {
			cir.setReturnValue(true);
		}
	}
	//?} else if >=1.21.2 {
	/*/^* Attaches this frame's cosmetics (and the nameplate icon) to the player's render state. ^/
	@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL"))
	private void orbit$extractCosmetics(AbstractClientPlayer player, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		OrbitClient.get().extract(player, PlayerState.of(state), partialTicks);
	}
	// The own nameplate: LivingEntityRendererMixin (players have no shouldShowName of their own before 1.21.9).
	*///?}

	//? if >=1.21.2 {
	/** First-person arms: the sleeve pixels that arm cosmetics cover are hidden, as in third person. */
	@ModifyVariable(method = "renderHand", at = @At("HEAD"), argsOnly = true)
	private Identifier orbit$maskedArmSkin(Identifier skin) {
		return OrbitClient.get().firstPersonSkin(skin);
	}
	//?} else {
	/*/^*
	 * Before 1.21.2 the game keeps no render state: Orbit works out the player's state when the renderer starts on
	 * them and keeps it on the player until it is done (the layers, the model, the cape and the name read it).
	 ^/
	@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"))
	private void orbit$extractCosmetics(AbstractClientPlayer player, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
		PlayerState state = PlayerState.capture(player, partialTicks);
		((PlayerFrameHolder) player).orbit$setState(state);
		OrbitClient.get().extract(player, state, partialTicks);
	}

	@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("RETURN"))
	private void orbit$forgetState(AbstractClientPlayer player, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
		((PlayerFrameHolder) player).orbit$setState(null);
	}

	/^* The skin Orbit gives the player (its skin without the pixels cosmetics cover). ^/
	@Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/Identifier;", at = @At("RETURN"), cancellable = true)
	private void orbit$skin(AbstractClientPlayer player, CallbackInfoReturnable<Identifier> cir) {
		PlayerState state = PlayerState.of(player);
		if (state != null && state.skin() != null) {
			cir.setReturnValue(state.skin().texture());
		}
	}

	/^* The name Orbit gives the player (with the nameplate icon). ^/
	//? if >=1.20.5 {
	@ModifyVariable(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
		at = @At("HEAD"), argsOnly = true)
	//?} else {
	/^@ModifyVariable(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"), argsOnly = true)
	^///?}
	private Component orbit$nameTag(Component name, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) AbstractClientPlayer player) {
		PlayerState state = PlayerState.of(player);
		return state != null && state.nameTag() != null ? state.nameTag() : name;
	}

	/^* First-person arms: the sleeve pixels that arm cosmetics cover are hidden, as in third person. ^/
	@ModifyExpressionValue(method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
		//? if >=1.20.2 {
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/PlayerSkin;texture()Lnet/minecraft/resources/Identifier;"))
		//?} else {
		/^at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getSkinTextureLocation()Lnet/minecraft/resources/Identifier;"))
		^///?}
	private Identifier orbit$maskedArmSkin(Identifier skin) {
		return OrbitClient.get().firstPersonSkin(skin);
	}
	*///?}

	//? if >=1.21.9 {
	/** A first-person arm a body part replaces: only the body part is drawn (with the arm's cosmetics). */
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void orbit$replaceArm(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		Anchor anchor = orbit$anchor(model, arm);
		if (OrbitClient.get().replacesFirstPersonArm(anchor)) {
			// The game resets the arm's pose before drawing it; the body part follows the same pose.
			arm.resetPose();
			model.leftArm.zRot = -0.1F;
			model.rightArm.zRot = 0.1F;
			OrbitClient.get().renderFirstPersonArm(PlayerBody.of(model), poseStack, OrbitCanvas.of(collector), light, anchor);
			ci.cancel();
		}
	}

	/** First-person arms are rendered without render layers, so arm cosmetics are drawn here. */
	@Inject(method = "renderHand", at = @At("TAIL"))
	private void orbit$renderArmCosmetics(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		OrbitClient.get().renderFirstPersonArm(PlayerBody.of(model), poseStack, OrbitCanvas.of(collector), light, orbit$anchor(model, arm));
	}
	//?} else if >=1.21.2 {
	/*/^* A first-person arm a body part replaces: only the body part is drawn (with the arm's cosmetics). ^/
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void orbit$replaceArm(PoseStack poseStack, MultiBufferSource collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		Anchor anchor = orbit$anchor(model, arm);
		if (OrbitClient.get().replacesFirstPersonArm(anchor)) {
			// The game resets the arm's pose before drawing it; the body part follows the same pose.
			arm.resetPose();
			model.leftArm.zRot = -0.1F;
			model.rightArm.zRot = 0.1F;
			OrbitClient.get().renderFirstPersonArm(PlayerBody.of(model), poseStack, OrbitCanvas.of(collector), light, anchor);
			ci.cancel();
		}
	}

	/^* First-person arms are rendered without render layers, so arm cosmetics are drawn here. ^/
	@Inject(method = "renderHand", at = @At("TAIL"))
	private void orbit$renderArmCosmetics(PoseStack poseStack, MultiBufferSource collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		OrbitClient.get().renderFirstPersonArm(PlayerBody.of(model), poseStack, OrbitCanvas.of(collector), light, orbit$anchor(model, arm));
	}
	*///?} else {
	/*/^* A first-person arm a body part replaces: only the body part is drawn (with the arm's cosmetics). ^/
	@Inject(method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
		at = @At("HEAD"), cancellable = true)
	private void orbit$replaceArm(PoseStack poseStack, MultiBufferSource collector, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		Anchor anchor = orbit$anchor(model, arm);
		if (OrbitClient.get().replacesFirstPersonArm(anchor)) {
			// The arm as the game poses it for drawing: at rest, hanging slightly out.
			arm.resetPose();
			model.leftArm.zRot = -0.1F;
			model.rightArm.zRot = 0.1F;
			OrbitClient.get().renderFirstPersonArm(PlayerBody.of(model), poseStack, OrbitCanvas.of(collector), light, anchor);
			ci.cancel();
		}
	}

	/^* First-person arms are rendered without render layers, so arm cosmetics are drawn here. ^/
	@Inject(method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
		at = @At("TAIL"))
	private void orbit$renderArmCosmetics(PoseStack poseStack, MultiBufferSource collector, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, CallbackInfo ci) {
		PlayerModel model = ((AvatarRenderer<?>) (Object) this).getModel();
		OrbitClient.get().renderFirstPersonArm(PlayerBody.of(model), poseStack, OrbitCanvas.of(collector), light, orbit$anchor(model, arm));
	}
	*///?}

	/** Which arm the game draws; ROOT for any other part (nothing is drawn on it then). */
	@Unique
	private static Anchor orbit$anchor(PlayerModel model, ModelPart arm) {
		return arm == model.rightArm ? Anchor.RIGHT_ARM : arm == model.leftArm ? Anchor.LEFT_ARM : Anchor.ROOT;
	}
}
