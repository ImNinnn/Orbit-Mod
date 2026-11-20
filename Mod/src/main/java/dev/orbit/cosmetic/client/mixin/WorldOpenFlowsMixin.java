package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opening a singleplayer world: in a party, the player first chooses alone or with the party. */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
	//? if >=1.20.2 {
	@Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
	private void orbit$askParty(String levelId, Runnable onCancel, CallbackInfo ci) {
		WorldOpenFlows flows = (WorldOpenFlows) (Object) this;
		if (dev.orbit.cosmetic.client.social.PartyVote.interceptWorld(levelId, () -> flows.openWorld(levelId, onCancel))) {
			ci.cancel();
		}
	}
	//?} else {
	/*// Before 1.20.2 a world is opened with loadLevel.
	@Inject(method = "loadLevel(Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
	private void orbit$askParty(net.minecraft.client.gui.screens.Screen parent, String levelId, CallbackInfo ci) {
		WorldOpenFlows flows = (WorldOpenFlows) (Object) this;
		if (dev.orbit.cosmetic.client.social.PartyVote.interceptWorld(levelId, () -> flows.loadLevel(parent, levelId))) {
			ci.cancel();
		}
	}
	*///?}
}
