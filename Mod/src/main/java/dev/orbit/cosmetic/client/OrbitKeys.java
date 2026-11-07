package dev.orbit.cosmetic.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.QuickMenuScreen;
import dev.orbit.cosmetic.client.gui.ScreenshotsScreen;
import dev.orbit.cosmetic.client.host.HostScreen;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.social.SocialScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Orbit's other keys (Controls, under Orbit): Host (H), Social (J), Screenshots (I), chat peek (Z: the whole chat
 * while held), and, not bound until the player binds them, the quick menu, showing or hiding every cosmetic, and
 * one key per emote wheel slot. The wardrobe (B), emote wheel (R) and zoom (C) keys are registered with the rest
 * of Orbit.
 */
public final class OrbitKeys {
	private static KeyMapping host;
	private static KeyMapping social;
	private static KeyMapping screenshots;
	private static KeyMapping chatPeek;
	private static KeyMapping quickMenu;
	private static KeyMapping toggleCosmetics;
	private static final KeyMapping[] EMOTE_SLOTS = new KeyMapping[OrbitState.WHEEL_SLOTS];

	private OrbitKeys() {
	}

	static void register(KeyMapping.Category category) {
		host = key("host", InputConstants.KEY_H, category);
		social = key("social", InputConstants.KEY_J, category);
		screenshots = key("screenshots", InputConstants.KEY_I, category);
		chatPeek = key("chat_peek", InputConstants.KEY_Z, category);
		quickMenu = key("quick_menu", InputConstants.UNKNOWN.getValue(), category);
		toggleCosmetics = key("toggle_cosmetics", InputConstants.UNKNOWN.getValue(), category);
		for (int slot = 0; slot < EMOTE_SLOTS.length; slot++) {
			EMOTE_SLOTS[slot] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic.emote_slot_" + (slot + 1), InputConstants.UNKNOWN.getValue(), category));
		}
	}

	private static KeyMapping key(String name, int key, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic." + name, key, category));
	}

	public static KeyMapping quickMenuKey() {
		return quickMenu;
	}

	/** The chat peek key is held in the game (not while a screen is open). */
	public static boolean peekingChat() {
		return chatPeek != null && chatPeek.isDown() && Compat.screen() == null;
	}

	static void tick(Minecraft minecraft) {
		OrbitClient orbit = OrbitClient.get();
		boolean free = Compat.screen() == null;
		while (host.consumeClick()) {
			if (free) {
				Compat.setScreen(new HostScreen(null));
			}
		}
		while (social.consumeClick()) {
			if (free) {
				Compat.setScreen(new SocialScreen(null));
			}
		}
		while (screenshots.consumeClick()) {
			if (free) {
				Compat.setScreen(new ScreenshotsScreen(null));
			}
		}
		while (quickMenu.consumeClick()) {
			if (free && minecraft.player != null) {
				// Like the emote wheel: open while the key is held.
				Compat.setScreen(new QuickMenuScreen());
			}
		}
		while (toggleCosmetics.consumeClick()) {
			orbit.config().renderCosmetics = !orbit.config().renderCosmetics;
			orbit.config().save();
			Notifications.info(Component.translatable(orbit.config().renderCosmetics ? "orbit_cosmetic.notify.cosmetics_shown" : "orbit_cosmetic.notify.cosmetics_hidden"),
				Component.empty());
		}
		for (int slot = 0; slot < EMOTE_SLOTS.length; slot++) {
			while (EMOTE_SLOTS[slot].consumeClick()) {
				EmoteLibrary.LocalEmote emote = orbit.emotes().slot(orbit.emotes().selectedWheel(), slot);
				if (free && emote != null && minecraft.player != null) {
					orbit.emoteController().play(emote);
				}
			}
		}
	}
}
