package dev.orbit.cosmetic.client.hud;

import dev.orbit.cosmetic.client.platform.OrbitPlatform;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Draws the HUD components: in game on the HUD (hidden with F1), and the ones set to show in menus over every other
 * screen too. The editor draws them itself.
 */
public final class HudRenderer {
	/** The components' HUD layer (the chat windows go just above it). */
	public static final Identifier ID = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "hud_components");

	private HudRenderer() {
	}

	public static void register() {
		HudLayout.components();
		HudTargets.register();
		// Under the chat: screens (the inventory too), toasts and Orbit's popups go over the components.
		OrbitPlatform.hudUnderChat(ID, (graphics, partialTick) -> {
			if (Compat.hudHidden() || Compat.screen() instanceof HudEditorScreen) {
				return;
			}
			// With a screen open, the ones shown in menus are drawn over it instead.
			draw(graphics, Compat.screen() == null ? Filter.ALL : Filter.NOT_IN_MENUS, partialTick);
		});
		OrbitPlatform.onScreenOpened((screen, hooks) -> {
			if (!(screen instanceof HudEditorScreen)) {
				hooks.afterDraw((graphics, mouseX, mouseY, partialTick) -> {
					graphics.layer();
					draw(graphics, Filter.IN_MENUS, partialTick);
				});
			}
		});
	}

	private enum Filter {
		ALL, IN_MENUS, NOT_IN_MENUS
	}

	private static void draw(OrbitGraphics graphics, Filter filter, float partialTick) {
		if (HudLayout.components().isEmpty() || HudLayout.allHidden()) {
			return;
		}
		HudInput.frame();
		Minecraft minecraft = Minecraft.getInstance();
		int w = minecraft.getWindow().getGuiScaledWidth();
		int h = minecraft.getWindow().getGuiScaledHeight();
		for (HudComponent component : HudLayout.components()) {
			boolean pass = switch (filter) {
				case ALL -> true;
				case IN_MENUS -> component.inMenus;
				case NOT_IN_MENUS -> !component.inMenus;
			};
			if (pass && component.available() && !component.hidden) {
				component.draw(graphics, w, h, false, partialTick);
			}
		}
	}
}
