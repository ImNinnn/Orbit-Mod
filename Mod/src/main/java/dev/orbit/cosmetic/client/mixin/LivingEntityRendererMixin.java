package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.render.PlayerFrame;
import dev.orbit.cosmetic.client.gfx.PlayerState;
import dev.orbit.cosmetic.client.render.EmotePoser;
import dev.orbit.cosmetic.client.render.VanillaPlayerModels;
import net.minecraft.client.model.EntityModel;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//? if >=1.21.9
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a player plays an emote, and always for the players in Orbit's menus, draws them with a plain vanilla player
 * model. Model replacement mods (Entity Model Features packs such as Fresh Animations) animate their own model and
 * would override the emote; the vanilla model shows it as made, and the layers drawn on top (armor, cosmetics)
 * follow that same body. An emote's ROOT group moves the whole player, layers and all. The pose the player is
 * started with is kept on the render state, for placing particles.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Shadow
	protected EntityModel<?> model;

	@Unique
	private EntityModel<?> orbit$replaced;

	//? if >=1.21.9 {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
	private void orbit$vanillaWhileEmoting(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if ((Object) this instanceof AvatarRenderer<?> && state instanceof AvatarRenderState avatar) {
			PlayerFrame frame = PlayerState.of(avatar).frame();
			if (frame != null) {
				frame.base = new Matrix4f(poseStack.last().pose());
			}
			if (VanillaPlayerModels.isUsedFor(avatar)) {
				orbit$replaced = model;
				model = VanillaPlayerModels.forSkin(avatar.skin);
			}
		}
	}

	/** The emote's ROOT: right after the player is turned to face their way, in Blockbench's axes (blocks, feet at 0). */
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", shift = At.Shift.AFTER))
	private void orbit$moveWholePlayer(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		PlayerFrame frame = state instanceof AvatarRenderState avatar ? PlayerState.of(avatar).frame() : null;
		if (frame != null && frame.emote != null) {
			EmotePoser.applyRoot(frame.emote, poseStack);
		}
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
	private void orbit$restoreModel(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (orbit$replaced != null) {
			model = orbit$replaced;
			orbit$replaced = null;
		}
	}
	//?} else {
	/*@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"))
	private void orbit$vanillaWhileEmoting(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource collector, int light, CallbackInfo ci) {
		if ((Object) this instanceof AvatarRenderer<?> && state instanceof AvatarRenderState avatar) {
			PlayerFrame frame = PlayerState.of(avatar).frame();
			if (frame != null) {
				frame.base = new Matrix4f(poseStack.last().pose());
			}
			if (VanillaPlayerModels.isUsedFor(avatar)) {
				orbit$replaced = model;
				model = VanillaPlayerModels.forSkin(avatar.skin);
			}
		}
	}

	/^* The emote's ROOT: right after the player is turned to face their way, in Blockbench's axes (blocks, feet at 0). ^/
	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", shift = At.Shift.AFTER))
	private void orbit$moveWholePlayer(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource collector, int light, CallbackInfo ci) {
		PlayerFrame frame = state instanceof AvatarRenderState avatar ? PlayerState.of(avatar).frame() : null;
		if (frame != null && frame.emote != null) {
			EmotePoser.applyRoot(frame.emote, poseStack);
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("RETURN"))
	private void orbit$restoreModel(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource collector, int light, CallbackInfo ci) {
		if (orbit$replaced != null) {
			model = orbit$replaced;
			orbit$replaced = null;
		}
	}

	/^*
	 * Vanilla never shows the camera entity's name; the "show own nameplate" setting does in third person. Before
	 * 1.21.9 players have no shouldShowName of their own: it is this renderer's.
	 ^/
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void orbit$showOwnNameplate(net.minecraft.world.entity.LivingEntity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && dev.orbit.cosmetic.client.OrbitClient.get().shouldShowOwnNameplate(entity)) {
			cir.setReturnValue(true);
		}
	}
	*///?}
}
