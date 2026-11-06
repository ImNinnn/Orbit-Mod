package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.zoom.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom: the field of view divided by the zoom level. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void orbit$zoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
		Zoom.update();
		cir.setReturnValue(Zoom.apply(cir.getReturnValueF()));
	}
}
