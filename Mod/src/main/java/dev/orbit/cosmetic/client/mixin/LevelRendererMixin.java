package dev.orbit.cosmetic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import dev.orbit.cosmetic.client.platform.OrbitPlatform;
import net.minecraft.client.renderer.LevelRenderer;
//? if >=1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if >=1.21.9
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Orbit's own hook for drawing into the world (its particles), where Fabric API has none: Fabric API for 1.21.9
 * dropped its world render events (they came back for 1.21.10). Applied only then (see OrbitMixinPlugin); it runs
 * where Fabric's "before entities" event would, just before the entities are submitted.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	//? if >=1.21.9 {
	@Inject(method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
		at = @At("HEAD"))
	private void orbit$worldDraw(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector collector, CallbackInfo ci) {
		OrbitPlatform.drawWorld(OrbitCanvas.of(collector), poseStack, state.cameraRenderState.pos, state.cameraRenderState.orientation);
	}
	//?}
	// Before 1.21.9 every Fabric API has world render events: nothing to hook (OrbitMixinPlugin never applies this).
}
