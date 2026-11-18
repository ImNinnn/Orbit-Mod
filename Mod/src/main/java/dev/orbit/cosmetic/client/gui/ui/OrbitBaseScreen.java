package dev.orbit.cosmetic.client.gui.ui;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Every screen of Orbit's: it draws with {@link OrbitGraphics} in {@link #draw} and {@link #drawBackground}, and this
 * class is the one place that knows how each Minecraft version asks a screen to draw itself. {@link OrbitScreen}
 * adds Orbit's look (the grid, going back to the screen it was opened from); overlays like the emote wheel extend
 * this one directly.
 */
public abstract class OrbitBaseScreen extends Screen {
	protected OrbitBaseScreen(Component title) {
		super(title);
	}

	@Override
	public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		draw(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick);
	}

	/** Draws the screen over its background: by default only its widgets (see {@link #drawWidgets}). */
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawWidgets(graphics, mouseX, mouseY, partialTick);
	}

	/** Draws the screen's widgets (buttons, text boxes...), as Minecraft does. */
	protected final void drawWidgets(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics.raw(), mouseX, mouseY, partialTick);
	}

	@Override
	public final void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawBackground(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick);
	}

	/** The screen's background: by default Minecraft's own (see {@link #drawMinecraftBackground}). */
	protected void drawBackground(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawMinecraftBackground(graphics, mouseX, mouseY, partialTick);
	}

	/** Minecraft's own menu background (the blurred world, or the panorama). */
	protected final void drawMinecraftBackground(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics.raw(), mouseX, mouseY, partialTick);
	}
}
