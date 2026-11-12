package dev.orbit.cosmetic.client.hud;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Draws the HUD components: in game on the HUD (hidden with F1), and the ones set to show in menus over every other
 * screen too. The editor draws them itself.
 */
public final class HudRenderer {
	private HudRenderer() {
	}

	public static void register() {
		HudLayout.components();
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "hud_components"), (graphics, tick) -> {
			if (Compat.hudHidden() || Compat.screen() instanceof HudEditorScreen) {
				return;
			}
			// With a screen open, the ones shown in menus are drawn over it instead.
			draw(graphics, Compat.screen() == null ? Filter.ALL : Filter.NOT_IN_MENUS, tick.getGameTimeDeltaPartialTick(false));
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof HudEditorScreen)) {
				ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> {
					graphics.nextStratum();
					draw(graphics, Filter.IN_MENUS, partialTick);
				});
			}
		});
	}

	private enum Filter {
		ALL, IN_MENUS, NOT_IN_MENUS
	}

	private static void draw(GuiGraphicsExtractor graphics, Filter filter, float partialTick) {
		if (HudLayout.components().isEmpty()) {
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
			if (pass && component.available()) {
				component.draw(graphics, w, h, false, partialTick);
			}
		}
	}
}
