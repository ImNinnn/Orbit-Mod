package dev.orbit.cosmetic.client.gui.ui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** A screen in Orbit's style: the grid background, and Escape returns to the screen it was opened from. */
public abstract class OrbitScreen extends Screen {
	protected final @Nullable Screen parent;

	protected OrbitScreen(Component title, @Nullable Screen parent) {
		super(title);
		this.parent = parent;
	}

	protected static OrbitClient orbit() {
		return OrbitClient.get();
	}

	/**
	 * The panel of Orbit's big screens (the wardrobe, Social, Screenshots), as x, y, width and height: at most
	 * 720 by 400, centred, with room on its left for the back button.
	 */
	protected int[] panel() {
		int w = Math.min(width - 40, 720);
		int h = Math.min(height - 16, 400);
		int x = Math.max(30, (width - w) / 2);
		return new int[] {x, (height - h) / 2, Math.min(w, width - x - 8), h};
	}

	/**
	 * Small windows (the host window, statistics, dialogs) sit on Minecraft's own menu background (the blurred
	 * world or panorama) instead of Orbit's grid.
	 */
	protected boolean isSmallWindow() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (isSmallWindow() || !orbit().config().gridBackground) {
			super.extractBackground(graphics, mouseX, mouseY, partialTick);
		} else {
			Theme.background(graphics, width, height);
		}
	}

	@Override
	public void onClose() {
		Compat.setScreen(parent);
	}

	/** A titled panel header: the title, and a back button when there is somewhere to go back to. */
	protected FlatButton backButton(int x, int y) {
		return addRenderableWidget(FlatButton.icon(x, y, 16, "back", Component.translatable("gui.back"), FlatButton.Style.SECONDARY, b -> onClose()));
	}
}
