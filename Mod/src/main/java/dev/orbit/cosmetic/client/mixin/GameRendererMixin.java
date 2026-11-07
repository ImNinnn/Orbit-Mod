package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.zoom.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zoom hides the first-person hand. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void orbit$hideHandWhileZooming(CallbackInfo ci) {
		if (Zoom.isZooming()) {
			ci.cancel();
		}
	}
}
