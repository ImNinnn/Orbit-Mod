package dev.orbit.cosmetic.client.screenshot;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A new screenshot appears as a small picture in the top right corner, like a notification, instead of its name in
 * the chat. With the mouse free (any screen open), hovering it shows four buttons over it: Edit, Favorite, Copy and
 * Send to friends; clicking the picture itself opens it. It goes away after a few seconds (not while hovered).
 */
public final class ScreenshotToasts {
	private static final int WIDTH = 150;
	private static final int MARGIN = 4;
	private static final int BUTTON = 18;
	private static final long LIFETIME_MS = 6000;
	private static final long SLIDE_MS = 180;
	private static final int MAX_SHOWN = 3;
	private static final String[] ACTIONS = {"edit", "favorite", "copy", "send"};

	/** One shown screenshot. */
	private static final class Toast {
		ScreenshotLibrary.Shot shot;
		final long shownAt = System.currentTimeMillis();
		long hoveredFor;
		long lastFrame = System.currentTimeMillis();
		/** Where it was drawn last frame (GUI pixels), for the mouse. */
		int x;
		int y;
		int height;

		Toast(ScreenshotLibrary.Shot shot) {
			this.shot = shot;
		}

		long age() {
			return System.currentTimeMillis() - shownAt - hoveredFor;
		}
	}

	private static final List<Toast> TOASTS = new ArrayList<>();

	private ScreenshotToasts() {
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "screenshots"), (graphics, tick) -> {
			if (Compat.screen() == null && !Compat.hudHidden()) {
				draw(graphics, -1, -1);
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> draw(graphics, mouseX, mouseY));
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !click(current, event.x(), event.y()));
		});
	}

	/** Shows a screenshot that was just saved. */
	public static void show(Path file) {
		ScreenshotLibrary library = OrbitClient.get().screenshots();
		TOASTS.addFirst(new Toast(library.shot(file)));
		while (TOASTS.size() > MAX_SHOWN) {
			TOASTS.removeLast();
		}
	}

	private static void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (TOASTS.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		ScreenshotLibrary library = OrbitClient.get().screenshots();
		int y = MARGIN;
		long now = System.currentTimeMillis();
		for (Toast toast : List.copyOf(TOASTS)) {
			if (toast.age() > LIFETIME_MS + SLIDE_MS) {
				TOASTS.remove(toast);
				continue;
			}
			ScreenshotLibrary.Thumbnail thumbnail = library.thumbnail(toast.shot.path(), 320);
			int imageHeight = thumbnail == null ? WIDTH * 9 / 16 : Math.round(WIDTH * thumbnail.height() / (float) thumbnail.width());
			int height = imageHeight + 2;
			// Slides in from the right, and out again at the end.
			long age = toast.age();
			float shown = age < SLIDE_MS ? age / (float) SLIDE_MS : age > LIFETIME_MS ? 1 - (age - LIFETIME_MS) / (float) SLIDE_MS : 1;
			shown = 1 - (1 - shown) * (1 - shown);
			int x = screenWidth - MARGIN - Math.round((WIDTH + 2 + MARGIN) * shown) + MARGIN;
			toast.x = x;
			toast.y = y;
			toast.height = height;
			boolean hovered = mouseX >= x && mouseY >= y && mouseX < x + WIDTH + 2 && mouseY < y + height;
			if (hovered) {
				// Stays while the mouse is on it.
				toast.hoveredFor += now - toast.lastFrame;
			}
			toast.lastFrame = now;
			Theme.panel(graphics, x, y, WIDTH + 2, height);
			if (thumbnail != null) {
				graphics.blit(thumbnail.id(), x + 1, y + 1, x + 1 + WIDTH, y + 1 + imageHeight, 0, 1, 0, 1);
			}
			if (hovered) {
				graphics.fill(x + 1, y + 1, x + 1 + WIDTH, y + 1 + imageHeight, 0x80000000);
				int hoveredAction = actionAt(toast, mouseX, mouseY);
				for (int i = 0; i < ACTIONS.length; i++) {
					int bx = buttonX(toast, i);
					int by = buttonY(toast);
					boolean hot = i == hoveredAction;
					graphics.fill(bx, by, bx + BUTTON, by + BUTTON, hot ? 0xE0FFFFFF : 0xC0141416);
					graphics.outline(bx, by, BUTTON, BUTTON, hot ? 0xFFFFFFFF : Theme.BORDER_LIGHT);
					String icon = i == 1 ? (toast.shot.favorite() ? "heart" : "heart_empty") : ACTIONS[i];
					Theme.icon(graphics, icon, bx + (BUTTON - 9) / 2, by + (BUTTON - 9) / 2, hot ? 0xFF141416 : i == 1 && toast.shot.favorite() ? Theme.RED : Theme.TEXT);
				}
				if (hoveredAction >= 0) {
					Component label = Component.translatable("orbit_cosmetic.screenshots.action." + ACTIONS[hoveredAction]
						+ (hoveredAction == 1 && toast.shot.favorite() ? ".remove" : ""));
					int labelWidth = minecraft.font.width(label) + 8;
					int lx = Math.clamp(buttonX(toast, hoveredAction) + BUTTON / 2 - labelWidth / 2, x, screenWidth - labelWidth - 2);
					int ly = y + height + 2;
					graphics.fill(lx, ly, lx + labelWidth, ly + 13, 0xF0141416);
					graphics.outline(lx, ly, labelWidth, 13, Theme.BORDER_LIGHT);
					graphics.text(minecraft.font, label, lx + 4, ly + 3, Theme.TEXT, false);
				}
			}
			y += height + MARGIN;
		}
	}

	private static int buttonX(Toast toast, int index) {
		int total = ACTIONS.length * BUTTON + (ACTIONS.length - 1) * 6;
		return toast.x + 1 + (WIDTH - total) / 2 + index * (BUTTON + 6);
	}

	private static int buttonY(Toast toast) {
		return toast.y + 1 + (toast.height - 2 - BUTTON) / 2;
	}

	private static int actionAt(Toast toast, double mouseX, double mouseY) {
		for (int i = 0; i < ACTIONS.length; i++) {
			int bx = buttonX(toast, i);
			int by = buttonY(toast);
			if (mouseX >= bx && mouseY >= by && mouseX < bx + BUTTON && mouseY < by + BUTTON) {
				return i;
			}
		}
		return -1;
	}

	/** A click on a shown screenshot: true when it was used. */
	private static boolean click(Screen screen, double mouseX, double mouseY) {
		for (Toast toast : List.copyOf(TOASTS)) {
			if (mouseX < toast.x || mouseY < toast.y || mouseX >= toast.x + WIDTH + 2 || mouseY >= toast.y + toast.height) {
				continue;
			}
			ScreenshotLibrary library = OrbitClient.get().screenshots();
			switch (actionAt(toast, mouseX, mouseY)) {
				case 0 -> {
					TOASTS.remove(toast);
					Compat.setScreen(new dev.orbit.cosmetic.client.gui.ScreenshotEditScreen(screen, toast.shot));
				}
				case 1 -> toast.shot = library.toggleFavorite(toast.shot);
				case 2 -> ImageClipboard.copy(toast.shot.path());
				case 3 -> {
					TOASTS.remove(toast);
					dev.orbit.cosmetic.client.gui.ScreenshotViewScreen.send(screen, toast.shot);
				}
				default -> {
					TOASTS.remove(toast);
					Compat.setScreen(new dev.orbit.cosmetic.client.gui.ScreenshotViewScreen(screen, toast.shot));
				}
			}
			return true;
		}
		return false;
	}
}
