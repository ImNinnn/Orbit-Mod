package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The tabs of a cosmetic's settings: Animations, Velocity (movement physics) and Particles; a body part's: Parts
 * (which parts it replaces) and Particles.
 */
final class SettingsTabs {
	/** Space the tabs take at the top of the screen. */
	static final int HEIGHT = 26;
	/** Tab numbers screens pass to {@link #buttons}: a body part's Parts tab is {@link #ANIMATIONS}. */
	static final int ANIMATIONS = 0;
	static final int VELOCITY = 1;
	static final int PARTICLES = 2;
	private static final String[] KEYS = {"animations", "velocity", "particles"};
	private static final String[] BODY_PART_KEYS = {"parts", "particles"};

	private SettingsTabs() {
	}

	/**
	 * The tab buttons, centred at the top; {@code selected} is the open one. Switching tabs keeps {@code parent} as
	 * where Done and Escape lead.
	 */
	static List<FlatButton> buttons(int screenWidth, int selected, Screen parent, LocalCosmetic cosmetic) {
		boolean bodyPart = cosmetic.isBodyPart();
		String[] keys = bodyPart ? BODY_PART_KEYS : KEYS;
		List<BiFunction<Screen, LocalCosmetic, Screen>> screens = bodyPart
			? List.of(BodyPartScreen::new, (p, c) -> new ParticlesScreen(p, ParticleTarget.of(c), c, null))
			: List.of(AnimationSettingsScreen::new, PhysicsScreen::new, (p, c) -> new ParticlesScreen(p, ParticleTarget.of(c), c, null));
		int open = bodyPart ? (selected == PARTICLES ? 1 : 0) : selected;
		int width = 86;
		int left = (screenWidth - (width * keys.length + 4 * (keys.length - 1))) / 2;
		List<FlatButton> buttons = new ArrayList<>();
		for (int i = 0; i < keys.length; i++) {
			int tab = i;
			FlatButton button = new FlatButton(left + i * (width + 4), 4, width, 18, Component.translatable("orbit_cosmetic.tabs." + keys[i]), null,
				FlatButton.Style.GHOST, b -> {
					if (tab != open) {
						Compat.setScreen(screens.get(tab).apply(parent, cosmetic));
					}
				});
			button.setSelected(i == open);
			buttons.add(button);
		}
		return buttons;
	}
}
