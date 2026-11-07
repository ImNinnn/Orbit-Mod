package dev.orbit.cosmetic.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's Configure button for Orbit: opens Orbit's settings. Only loaded when Mod Menu is installed. */
public final class OrbitModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return OrbitSettingsScreen::new;
	}
}
