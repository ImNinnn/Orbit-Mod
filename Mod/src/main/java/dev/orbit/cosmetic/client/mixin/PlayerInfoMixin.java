package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.gfx.OrbitSkin;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.multiplayer.PlayerInfo;
//? if >=1.20.2 {
import net.minecraft.world.entity.player.PlayerSkin;
//?} else {
/*import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
*///?}
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
	//? if >=1.20.2 {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void orbit$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		OrbitClient orbit = OrbitClient.get();
		if (orbit != null && orbit.skins() != null) {
			cir.setReturnValue(orbit.skins().apply(((PlayerInfo) (Object) this).getProfile().id(), OrbitSkin.of(cir.getReturnValue())).raw());
		}
	}
	//?} else {
	/*// Before 1.20.2 the skin, model, cape and elytra are asked for one by one: each is taken from the whole skin as
	// Orbit has it (worked out from the game's own values, which these hooks then let through).
	@Unique
	private boolean orbit$asking;

	@Shadow
	public abstract Identifier getSkinLocation();

	@Shadow
	public abstract String getModelName();

	@Shadow
	public abstract Identifier getCapeLocation();

	@Shadow
	public abstract Identifier getElytraLocation();

	@Unique
	private OrbitSkin orbit$skin() {
		orbit$asking = true;
		OrbitSkin vanilla;
		try {
			vanilla = OrbitSkin.of(getSkinLocation(), "slim".equals(getModelName()), getCapeLocation(), getElytraLocation());
		} finally {
			orbit$asking = false;
		}
		return OrbitClient.get().skins().apply(((PlayerInfo) (Object) this).getProfile().id(), vanilla);
	}

	@Unique
	private boolean orbit$applies() {
		return !orbit$asking && OrbitClient.get() != null && OrbitClient.get().skins() != null;
	}

	@Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
	private void orbit$texture(CallbackInfoReturnable<Identifier> cir) {
		if (orbit$applies()) {
			cir.setReturnValue(orbit$skin().texture());
		}
	}

	@Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
	private void orbit$model(CallbackInfoReturnable<String> cir) {
		if (orbit$applies()) {
			cir.setReturnValue(orbit$skin().slim() ? "slim" : "default");
		}
	}

	@Inject(method = "getCapeLocation", at = @At("HEAD"), cancellable = true)
	private void orbit$cape(CallbackInfoReturnable<Identifier> cir) {
		if (orbit$applies()) {
			cir.setReturnValue(orbit$skin().cape());
		}
	}

	@Inject(method = "getElytraLocation", at = @At("HEAD"), cancellable = true)
	private void orbit$elytra(CallbackInfoReturnable<Identifier> cir) {
		if (orbit$applies()) {
			cir.setReturnValue(orbit$skin().elytra());
		}
	}
	*///?}
}
