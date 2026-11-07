package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.OrbitSplashes;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.resources.SplashManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One splash in five is one of Orbit's (see OrbitSplashes), wherever Minecraft picks one, also for mods that cycle
 * the splash text. Christmas, New Year and Halloween keep Minecraft's.
 */
@Mixin(SplashManager.class)
public abstract class SplashManagerMixin {
	@Inject(method = "getSplash", at = @At("RETURN"), cancellable = true)
	private void orbit$ownSplash(CallbackInfoReturnable<SplashRenderer> info) {
		SplashRenderer vanilla = info.getReturnValue();
		if (vanilla == null || vanilla == SplashRenderer.CHRISTMAS || vanilla == SplashRenderer.NEW_YEAR || vanilla == SplashRenderer.HALLOWEEN) {
			return;
		}
		SplashRenderer own = OrbitSplashes.pick();
		if (own != null) {
			info.setReturnValue(own);
		}
	}
}
