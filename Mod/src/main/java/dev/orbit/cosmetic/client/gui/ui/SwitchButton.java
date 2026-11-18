package dev.orbit.cosmetic.client.gui.ui;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A setting that is on or off: its icon, its name, and the settings menu's switch on the right (with a tooltip). */
public final class SwitchButton extends OrbitWidget {
	private final String icon;
	private final BooleanSupplier get;
	private final Consumer<Boolean> set;

	public SwitchButton(int width, int height, String icon, Component label, @Nullable Component tooltip, BooleanSupplier get, Consumer<Boolean> set) {
		super(0, 0, width, height, label);
		this.icon = icon;
		this.get = get;
		this.set = set;
		setTooltip(Tooltip.create(tooltip != null ? label.copy().append("\n").append(tooltip.copy().withStyle(net.minecraft.ChatFormatting.GRAY)) : label));
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		set.accept(!get.getAsBoolean());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (isFocused() && (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_SPACE)) {
			set.accept(!get.getAsBoolean());
			return true;
		}
		return false;
	}

	@Override
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		boolean on = get.getAsBoolean();
		boolean hot = isHoveredOrFocused();
		int x = getX();
		int y = getY();
		graphics.fill(x, y, getRight(), getBottom(), hot ? Theme.PANEL_HOVER : Theme.PANEL_2);
		graphics.outline(x, y, getWidth(), getHeight(), hot ? Theme.BORDER_LIGHT : Theme.BORDER);
		if (icon.contains(":")) {
			// One of the game's own sprites (a heart, a shank...): kept in its colours, darker when off.
			graphics.sprite(net.minecraft.resources.Identifier.parse(icon), x + 4, y + (getHeight() - 9) / 2, 9, 9, on ? 0xFFFFFFFF : 0xFF707070);
		} else {
			Theme.icon(graphics, icon, x + 4, y + (getHeight() - 9) / 2, on ? Theme.TEXT : Theme.MUTED);
		}
		Font font = Minecraft.getInstance().font;
		graphics.text(font, Theme.clip(font, getMessage(), getWidth() - 15 - 27), x + 15, y + (getHeight() - 8) / 2, on ? Theme.TEXT : Theme.MUTED, false);
		Theme.toggleSwitch(graphics, getRight() - 25, y + (getHeight() - 10) / 2, on);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage().copy().append(": ").append(get.getAsBoolean() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
	}
}
