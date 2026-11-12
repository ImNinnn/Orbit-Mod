package dev.orbit.cosmetic.client.gui.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Picking a colour: a row of swatches, then a field for any colour as a hex code ("#3A9D4A"). Used for statistics
 * pages and group chats.
 */
public final class ColorRow {
	public static final int[] COLORS = {0xC21E1E, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xD63B8A, 0x5A5A66};
	/** How wide the row is: the swatches, then the hex field. */
	public static final int WIDTH = COLORS.length * 17 + 4 + 56;

	private final List<Hotspot> swatches = new ArrayList<>();
	private final int[] colors;
	private final EditBox hex;
	private int color;

	public ColorRow(Font font, int x, int y, int color, IntConsumer changed, Consumer<AbstractWidget> add) {
		this(font, x, y, color, COLORS, changed, add);
	}

	/** With other swatches (at most as many as {@link #COLORS}, so the row keeps its width). */
	public ColorRow(Font font, int x, int y, int color, int[] swatchColors, IntConsumer changed, Consumer<AbstractWidget> add) {
		this.color = color & 0xFFFFFF;
		this.colors = swatchColors;
		for (int i = 0; i < colors.length; i++) {
			int chosen = colors[i];
			Hotspot swatch = new Hotspot(Component.translatable("orbit_cosmetic.stats.color"), h -> {
				this.color = chosen;
				hexField().setValue(format(chosen));
				changed.accept(chosen);
			});
			swatch.place(x + i * 17, y, 14, 14);
			swatch.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.stats.color")));
			swatches.add(swatch);
			add.accept(swatch);
		}
		hex = new EditBox(font, x + colors.length * 17 + 8, y + 3, 48, 10, Component.translatable("orbit_cosmetic.color.hex"));
		hex.setBordered(false);
		hex.setMaxLength(7);
		hex.setTextColor(Theme.TEXT);
		hex.setValue(format(this.color));
		hex.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.color.hex.tooltip")));
		hex.setResponder(text -> {
			Integer parsed = parse(text);
			if (parsed != null) {
				this.color = parsed;
				changed.accept(parsed);
			}
		});
		add.accept(hex);
	}

	private EditBox hexField() {
		return hex;
	}

	public int color() {
		return color;
	}

	public static String format(int color) {
		return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
	}

	/** "#3A9D4A", "3a9d4a" or "#3AD" as a colour; null for anything else. */
	public static Integer parse(String text) {
		String value = text.strip().replace("#", "");
		if (value.length() == 3) {
			value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1) + value.charAt(1) + value.charAt(2) + value.charAt(2);
		}
		if (!value.matches("[0-9a-fA-F]{6}")) {
			return null;
		}
		return Integer.parseInt(value, 16);
	}

	/** The swatches (the chosen one outlined) and the hex field's frame, with a dot of the colour typed. */
	public void draw(GuiGraphicsExtractor graphics) {
		for (int i = 0; i < swatches.size(); i++) {
			Hotspot swatch = swatches.get(i);
			int sx = swatch.getX();
			int sy = swatch.getY();
			boolean chosen = colors[i] == color;
			graphics.fill(sx, sy, sx + 14, sy + 14, chosen || swatch.isHoveredOrFocused() ? Theme.RED : Theme.EDGE);
			graphics.fill(sx + 1, sy + 1, sx + 13, sy + 13, 0xFF000000 | colors[i]);
			swatch.drawFocus(graphics);
		}
		int fx = hex.getX() - 4;
		int fy = hex.getY() - 3;
		boolean valid = parse(hex.getValue()) != null;
		graphics.fill(fx, fy, fx + 56, fy + 14, 0x88000000);
		graphics.outline(fx, fy, 56, 14, hex.isFocused() ? Theme.BORDER_LIGHT : valid ? Theme.BORDER : Theme.RED);
		boolean custom = java.util.Arrays.stream(colors).noneMatch(c -> c == color);
		if (custom) {
			graphics.fill(fx - 6, fy + 4, fx - 2, fy + 10, 0xFF000000 | color);
		}
	}
}
