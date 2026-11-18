package dev.orbit.cosmetic.client.gui.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.input.OrbitClick;
import dev.orbit.cosmetic.client.input.OrbitKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/**
 * A widget of Orbit's (buttons, switches, sliders): it draws with {@link OrbitGraphics} in {@link #draw}, and hears
 * clicks and keys as {@link OrbitClick} and {@link OrbitKey} ({@code onClick(OrbitClick, boolean)}...). This class is
 * the one place that knows how each Minecraft version hands a widget its drawing and input.
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

	// ---------------------------------------------------------------- input

	@Override
	public final void onClick(MouseButtonEvent event, boolean doubleClick) {
		onClick(new OrbitClick(event), doubleClick);
	}

	/** A click on the widget, with a button it accepts (see {@link #acceptsButton}). */
	public void onClick(OrbitClick click, boolean doubleClick) {
		super.onClick(click.raw(), doubleClick);
	}

	@Override
	public final void onRelease(MouseButtonEvent event) {
		onRelease(new OrbitClick(event));
	}

	public void onRelease(OrbitClick click) {
		super.onRelease(click.raw());
	}

	@Override
	protected final void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		onDrag(new OrbitClick(event), dragX, dragY);
	}

	protected void onDrag(OrbitClick click, double dragX, double dragY) {
		super.onDrag(click.raw(), dragX, dragY);
	}

	@Override
	public final boolean keyPressed(KeyEvent event) {
		return keyPressed(new OrbitKey(event));
	}

	public boolean keyPressed(OrbitKey key) {
		return super.keyPressed(key.raw());
	}

	@Override
	protected final boolean isValidClickButton(MouseButtonInfo button) {
		return acceptsButton(button.button());
	}

	/** Which mouse buttons click the widget: the left one, as Minecraft's widgets (a button of InputConstants). */
	protected boolean acceptsButton(int button) {
		return button == InputConstants.MOUSE_BUTTON_LEFT;
	}
}
