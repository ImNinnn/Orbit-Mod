package dev.orbit.cosmetic.client.gui.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Something to click that a screen draws itself (a tile, a list row, a card's button), which the keyboard reaches
 * too: Tab and the arrow keys move to it, Enter or Space presses it. The screen places it where it draws the thing
 * and draws its focus with {@link #drawFocus}. Outside its clip (a scrolled list's edges) the mouse cannot press it,
 * but the keyboard can still move to it, so a list can scroll to what is focused.
 */
public final class Hotspot extends AbstractWidget {
	private final Consumer<Hotspot> onPress;
	private int clipTop = Integer.MIN_VALUE;
	private int clipBottom = Integer.MAX_VALUE;
	/** Pressed with the right mouse button (or Delete on the keyboard), if anything. */
	private Consumer<Hotspot> onSecondary;
	/** False for things the screen handles the mouse for itself: only the keyboard uses it then. */
	private boolean mouse = true;

	public Hotspot(Component narration, Consumer<Hotspot> onPress) {
		super(0, 0, 0, 0, narration);
		this.onPress = onPress;
	}

	/** Moves it; the mouse only reaches it between {@code clipTop} and {@code clipBottom}. */
	public Hotspot place(int x, int y, int width, int height, int clipTop, int clipBottom) {
		setRectangle(width, height, x, y);
		this.clipTop = clipTop;
		this.clipBottom = clipBottom;
		return this;
	}

	public Hotspot place(int x, int y, int width, int height) {
		return place(x, y, width, height, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/** Leaves the mouse to the screen: it only takes the keyboard. */
	public Hotspot keyboardOnly() {
		this.mouse = false;
		return this;
	}

	public Hotspot onSecondary(Consumer<Hotspot> action) {
		this.onSecondary = action;
		return this;
	}

	/** Whether the keyboard is on it (the screen then draws {@link #drawFocus}). */
	public boolean keyboardFocused() {
		return isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
	}

	/** The keyboard's mark: a light frame around it. */
	public void drawFocus(GuiGraphicsExtractor graphics) {
		if (keyboardFocused()) {
			graphics.outline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2, Theme.TEXT);
		}
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return mouse && super.isMouseOver(mouseX, mouseY) && mouseY >= clipTop && mouseY < clipBottom;
	}

	@Override
	protected boolean isValidClickButton(MouseButtonInfo button) {
		return button.button() == InputConstants.MOUSE_BUTTON_LEFT || button.button() == InputConstants.MOUSE_BUTTON_RIGHT && onSecondary != null;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			onSecondary.accept(this);
		} else {
			onPress.accept(this);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active || !visible || !isFocused()) {
			return false;
		}
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_NUMPADENTER) {
			playDownSound(Minecraft.getInstance().getSoundManager());
			onPress.accept(this);
			return true;
		}
		if (onSecondary != null && event.key() == InputConstants.KEY_DELETE) {
			onSecondary.accept(this);
			return true;
		}
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Drawn by its screen.
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
