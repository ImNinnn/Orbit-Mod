package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.anim.MiningTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes who breaks blocks, for the MINE animation trigger (see {@link MiningTracker}). */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "destroyBlockProgress", at = @At("HEAD"))
	private void orbit$destroyBlockProgress(int breakerId, BlockPos pos, int progress, CallbackInfo info) {
		ClientLevel self = (ClientLevel) (Object) this;
		MiningTracker.progress(self, breakerId, progress, self.getGameTime());
	}
}
