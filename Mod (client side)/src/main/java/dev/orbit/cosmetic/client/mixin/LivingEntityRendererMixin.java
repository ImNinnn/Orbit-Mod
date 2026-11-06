package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.render.EmotePoser;
import dev.orbit.cosmetic.client.render.EmoteRenderData;
import dev.orbit.cosmetic.client.render.ParticleRenderData;
import dev.orbit.cosmetic.client.render.VanillaPlayerModels;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
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

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
	private void orbit$vanillaWhileEmoting(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if ((Object) this instanceof AvatarRenderer<?> && state instanceof AvatarRenderState avatar) {
			avatar.setData(ParticleRenderData.BASE, new Matrix4f(poseStack.last().pose()));
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
		if (state instanceof AvatarRenderState avatar && avatar.getData(EmoteRenderData.KEY) != null) {
			EmotePoser.applyRoot(avatar.getData(EmoteRenderData.KEY).body(), poseStack);
		}
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
	private void orbit$restoreModel(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (orbit$replaced != null) {
			model = orbit$replaced;
			orbit$replaced = null;
		}
	}
}
