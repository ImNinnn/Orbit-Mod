package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every way of joining a server goes here: in a party, the player first chooses alone or with the party. */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
	private static void orbit$askParty(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data, boolean quickPlay, @Nullable TransferState transfer,
		CallbackInfo ci) {
		if (transfer != null) {
			return; // a server sending the player on: not a choice
		}
		String shown = address.getPort() == 25565 ? address.getHost() : address.getHost() + ":" + address.getPort();
		if (dev.orbit.cosmetic.client.social.PartyVote.interceptConnect(parent, shown, data,
			() -> ConnectScreen.startConnecting(parent, minecraft, address, data, quickPlay, null))) {
			ci.cancel();
		}
	}
}
