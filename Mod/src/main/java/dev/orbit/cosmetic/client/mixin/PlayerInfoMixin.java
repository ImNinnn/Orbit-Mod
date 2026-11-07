package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Players' skins and capes as Orbit has them: the local player's chosen skin and cape, and what other Orbit players
 * wear (through Orbit Cloud), without waiting for Mojang or a reconnect. Everything that shows a player's skin
 * (the player, first-person hands, the tab list) asks here.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void orbit$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		OrbitClient orbit = OrbitClient.get();
		if (orbit != null && orbit.skins() != null) {
			cir.setReturnValue(orbit.skins().apply(((PlayerInfo) (Object) this).getProfile().id(), cir.getReturnValue()));
		}
	}
}
