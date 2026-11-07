package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The screen the Multiplayer screen goes back to (Orbit's server tabs go back there too), and its layout and server
 * list, so its top bar can hold the tabs.
 */
@Mixin(JoinMultiplayerScreen.class)
public interface JoinMultiplayerScreenAccessor {
	@Accessor("lastScreen")
	Screen orbit$lastScreen();

	@Accessor("layout")
	HeaderAndFooterLayout orbit$layout();

	@Accessor("serverSelectionList")
	ServerSelectionList orbit$serverList();
}
