package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Around a party command Orbit sends by itself (the risky setting), the game's actions (moving, attacking, using,
 * dropping...) are not taken for a moment: held keys read as up, and presses are dropped.
 */
@Mixin(KeyMapping.class)
abstract class KeyMappingMixin {
	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void orbit$frozenDown(CallbackInfoReturnable<Boolean> cir) {
		if (dev.orbit.cosmetic.client.social.ServerParty.blocks((KeyMapping) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "consumeClick", at = @At("RETURN"), cancellable = true)
	private void orbit$frozenClick(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && dev.orbit.cosmetic.client.social.ServerParty.blocks((KeyMapping) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
