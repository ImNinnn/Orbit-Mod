package dev.orbit.cosmetic.client.gui.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.input.OrbitClick;
import dev.orbit.cosmetic.client.input.OrbitKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
//?}
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

	//? if >=1.21.9 {
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
	//?} else {
	/*// Before 1.21.9 a widget hears a click without its button: the button is noted here first.
	private int button = InputConstants.MOUSE_BUTTON_LEFT;

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		this.button = button;
		return super.mouseClicked(x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		this.button = button;
		return super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
		this.button = button;
		return super.mouseDragged(x, y, button, dragX, dragY);
	}

	@Override
	public final void onClick(double x, double y) {
		onClick(new OrbitClick(x, y, button), OrbitClick.isDoubleClick());
	}

	/^* A click on the widget, with a button it accepts (see {@link #acceptsButton}). ^/
	public void onClick(OrbitClick click, boolean doubleClick) {
		super.onClick(click.x(), click.y());
	}

	@Override
	public final void onRelease(double x, double y) {
		onRelease(new OrbitClick(x, y, button));
	}

	public void onRelease(OrbitClick click) {
		super.onRelease(click.x(), click.y());
	}

	@Override
	protected final void onDrag(double x, double y, double dragX, double dragY) {
		onDrag(new OrbitClick(x, y, button), dragX, dragY);
	}

	protected void onDrag(OrbitClick click, double dragX, double dragY) {
		super.onDrag(click.x(), click.y(), dragX, dragY);
	}

	@Override
	public final boolean keyPressed(int key, int scanCode, int modifiers) {
		return keyPressed(new OrbitKey(key, scanCode, modifiers));
	}

	public boolean keyPressed(OrbitKey key) {
		return super.keyPressed(key.key(), key.scanCode(), key.modifiers());
	}

	@Override
	protected final boolean isValidClickButton(int button) {
		return acceptsButton(button);
	}
	*///?}

	/** Which mouse buttons click the widget: the left one, as Minecraft's widgets (a button of InputConstants). */
	protected boolean acceptsButton(int button) {
		return button == InputConstants.MOUSE_BUTTON_LEFT;
	}
}
