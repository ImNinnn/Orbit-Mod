package dev.orbit.cosmetic.client.gui.ui;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * A widget of Orbit's (buttons, switches, sliders): it draws with {@link OrbitGraphics} in {@link #draw}, and this
 * class is the one place that knows how each Minecraft version asks a widget to draw itself.
 */
public abstract class OrbitWidget extends AbstractWidget {
	protected OrbitWidget(int x, int y, int width, int height, Component message) {
		super(x, y, width, height, message);
	}

	@Override
	protected final void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		draw(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick);
	}

	/** Draws the widget (at getX(), getY(), getWidth() by getHeight()). */
	protected abstract void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick);
}
