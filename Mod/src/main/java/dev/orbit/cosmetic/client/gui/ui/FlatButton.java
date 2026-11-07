package dev.orbit.cosmetic.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.function.Consumer;

/**
 * A button in Orbit's colours, shaded like vanilla's: a dark edge, a lit top and a darker bottom, a light edge when
 * hovered, and text with a shadow. An optional 9x9 icon and a label; square when it has only an icon. Ghost buttons
 * (lists such as the sidebar) stay flat until hovered.
 */
public class FlatButton extends AbstractWidget {
	public enum Style {
		/** Orbit red: the main action of a screen. */
		PRIMARY(0xFFC21E1E, 0xFFE02626, Theme.TEXT),
		/** A raised panel. */
		SECONDARY(0xFF34343C, 0xFF42424B, Theme.TEXT),
		/** Text only, until hovered. */
		GHOST(0, Theme.PANEL_2, Theme.MUTED),
		/** Destructive actions. */
		DANGER(0xFF5A2328, 0xFF73292F, 0xFFFFB0B0);

		final int fill;
		final int hover;
		final int text;

		Style(int fill, int hover, int text) {
			this.fill = fill;
			this.hover = hover;
			this.text = text;
		}
	}

	private final Consumer<FlatButton> onPress;
	private Style style;
	private @Nullable String icon;
	private int iconColor = -1;
	private boolean selected;
	private boolean alignLeft;

	public FlatButton(int x, int y, int width, int height, Component label, @Nullable String icon, Style style, Consumer<FlatButton> onPress) {
		super(x, y, width, height, label);
		this.icon = icon;
		this.style = style;
		this.onPress = onPress;
	}

	/** A square button showing only an icon; the label is its tooltip and narration. */
	public static FlatButton icon(int x, int y, int size, String icon, Component label, Style style, Consumer<FlatButton> onPress) {
		FlatButton button = new FlatButton(x, y, size, size, label, icon, style, onPress);
		button.setTooltip(Tooltip.create(label));
		return button;
	}

	public FlatButton style(Style style) {
		this.style = style;
		return this;
	}

	public Style getStyle() {
		return style;
	}

	public FlatButton setIcon(@Nullable String icon) {
		this.icon = icon;
		return this;
	}

	public FlatButton iconColor(int color) {
		this.iconColor = color;
		return this;
	}

	/** Puts the icon and label at the left edge instead of the middle (for lists such as the sidebar). */
	public FlatButton alignLeft() {
		this.alignLeft = true;
		return this;
	}

	/** Shows the button as chosen (for tabs and toggles). */
	public FlatButton setSelected(boolean selected) {
		this.selected = selected;
		return this;
	}

	public boolean isSelected() {
		return selected;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		boolean hot = active && (isHoveredOrFocused() || selected);
		boolean raised = style != Style.GHOST;
		// Raised buttons sink their content by one pixel above the bottom shadow, like vanilla's.
		int sink = raised ? 1 : 0;
		if (raised) {
			int fill = !active ? Theme.PANEL_2 : hot ? style.hover : style.fill;
			// With Minecraft's texture, the main action is tinted red and dangerous ones pink.
			int tint = style == Style.PRIMARY ? 0xFFFF6A6A : style == Style.DANGER ? 0xFFFFA0A0 : -1;
			boolean vanilla = Theme.buttonFace(graphics, getX(), getY(), getWidth(), getHeight(), active, isHoveredOrFocused(), fill, tint);
			if (active && !vanilla) {
				graphics.fill(getX() + 1, getY() + 1, getRight() - 1, getY() + 2, Theme.shade(fill, 1.35F));
				graphics.fill(getX() + 1, getY() + 2, getX() + 2, getBottom() - 3, Theme.shade(fill, 1.15F));
				graphics.fill(getX() + 1, getBottom() - 3, getRight() - 1, getBottom() - 1, Theme.shade(fill, 0.62F));
			}
		} else if (hot) {
			graphics.fill(getX(), getY(), getRight(), getBottom(), style.hover);
			Theme.bevel(graphics, getX(), getY(), getWidth(), getHeight(), 1);
		}
		if (selected && !raised) {
			graphics.fill(getX(), getY(), getX() + 2, getBottom(), Theme.RED);
		}
		int textColor = !active ? Theme.FAINT : hot && !raised ? Theme.TEXT : style.text;
		Font font = Minecraft.getInstance().font;
		Component label = getMessage();
		boolean iconOnly = icon != null && getWidth() <= getHeight() + 2;
		if (iconOnly) {
			Theme.icon(graphics, icon, getX() + (getWidth() - 9) / 2, getY() + (getHeight() - 9) / 2 - sink, iconColor != -1 && active ? iconColor : textColor);
			return;
		}
		int labelWidth = font.width(label);
		int contentWidth = labelWidth + (icon != null ? 12 : 0);
		int x = alignLeft ? getX() + 6 : getX() + Math.max(4, (getWidth() - contentWidth) / 2);
		int y = getY() + (getHeight() - 8) / 2 - sink;
		if (icon != null) {
			Theme.icon(graphics, icon, x, getY() + (getHeight() - 9) / 2 - sink, iconColor != -1 && active ? iconColor : textColor);
			x += 12;
		}
		graphics.text(font, Theme.clip(font, label, getRight() - x - 4), x, y, textColor, raised && active);
	}

	/** Does what the button does, as if clicked. */
	public void press() {
		onPress.accept(this);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onPress.accept(this);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && visible && isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_NUMPADENTER)) {
			playDownSound(Minecraft.getInstance().getSoundManager());
			onPress.accept(this);
			return true;
		}
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
