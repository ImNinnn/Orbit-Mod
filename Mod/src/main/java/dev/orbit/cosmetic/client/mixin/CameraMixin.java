package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.zoom.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom: the field of view divided by the zoom level. */
//? if >=26.1 {
@Mixin(Camera.class)
abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void orbit$zoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
//?} else {
/*// Before 26.1 the game renderer works the field of view out.
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
abstract class CameraMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void orbit$zoom(Camera camera, float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
*///?}
		Zoom.update();
		float fov = Zoom.apply(cir.getReturnValueF());
		Zoom.lastFov = fov;
		cir.setReturnValue(fov);
	}
}
