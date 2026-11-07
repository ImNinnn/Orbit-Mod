package dev.orbit.cosmetic.client.gui.ui;

import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * The look of Orbit's menus, taken from the Orbit website: a dark background with a faint grid, panels with thin
 * borders, and Orbit red for what matters most. Panels, tiles and buttons are shaded like vanilla's: lit along the
 * top, darker along the bottom, so they sit well next to Minecraft's own screens.
 */
public final class Theme {
	public static final int BACKGROUND = 0xFF141416;
	public static final int GRID = 0x0DFFFFFF;
	public static final int PANEL = 0xFF1C1C20;
	public static final int PANEL_2 = 0xFF232328;
	public static final int PANEL_HOVER = 0xFF2B2B31;
	public static final int BORDER = 0xFF2E2E35;
	public static final int BORDER_LIGHT = 0xFF45454F;
	public static final int TEXT = 0xFFECECEF;
	public static final int MUTED = 0xFFA3A3AD;
	public static final int FAINT = 0xFF6F6F7A;
	public static final int RED = 0xFFFF2626;
	public static final int RED_DARK = 0xFFB81B1B;
	public static final int GREEN = 0xFF7CE38B;
	public static final int YELLOW = 0xFFFFCC4D;
	public static final int BLUE = 0xFF4DA6FF;
	public static final int GOLD = 0xFFFFC23D;
	/** The dark edge around buttons, as vanilla draws them. */
	public static final int EDGE = 0xFF0A0A0C;
	/** The light edge of a hovered or focused button. */
	public static final int EDGE_HOT = 0xFFE6E6EA;

	/** Spacing of the background grid, in GUI pixels. */
	public static final int GRID_SIZE = 20;

	private Theme() {
	}

	public static Identifier icon(String name) {
		return Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "icon/" + name);
	}

	/** The full-screen background: dark with a faint grid. */
	public static void background(GuiGraphicsExtractor graphics, int width, int height) {
		graphics.fill(0, 0, width, height, BACKGROUND);
		grid(graphics, 0, 0, width, height);
	}

	public static void grid(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		for (int x = x0 - Math.floorMod(x0, GRID_SIZE); x < x1; x += GRID_SIZE) {
			if (x >= x0) {
				graphics.fill(x, y0, x + 1, y1, GRID);
			}
		}
		for (int y = y0 - Math.floorMod(y0, GRID_SIZE); y < y1; y += GRID_SIZE) {
			if (y >= y0) {
				graphics.fill(x0, y, x1, y + 1, GRID);
			}
		}
	}

	/** A panel with a one-pixel border, lit along its top inner edge and shaded along its bottom one. */
	public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill) {
		graphics.fill(x, y, x + width, y + height, fill);
		graphics.outline(x, y, width, height, BORDER);
		bevel(graphics, x + 1, y + 1, width - 2, height - 2, 1);
	}

	/**
	 * Vanilla-style shading inside a rectangle: a light line along the top and the left, and a dark band of
	 * {@code shadow} pixels along the bottom.
	 */
	public static void bevel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int shadow) {
		if (width <= 2 || height <= shadow + 1) {
			return;
		}
		graphics.fill(x, y, x + width, y + 1, 0x1FFFFFFF);
		graphics.fill(x, y + 1, x + 1, y + height - shadow, 0x12FFFFFF);
		graphics.fill(x, y + height - shadow, x + width, y + height, 0x59000000);
	}

	/** A colour made lighter ({@code factor} above 1) or darker, keeping its alpha. */
	public static int shade(int color, float factor) {
		int r = Math.min(255, Math.round((color >> 16 & 0xFF) * factor));
		int g = Math.min(255, Math.round((color >> 8 & 0xFF) * factor));
		int b = Math.min(255, Math.round((color & 0xFF) * factor));
		return color & 0xFF000000 | r << 16 | g << 8 | b;
	}

	public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		panel(graphics, x, y, width, height, PANEL);
	}

	/** A 9x9 icon, tinted. */
	private static final Identifier VANILLA_BUTTON = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier VANILLA_BUTTON_HOT = Identifier.withDefaultNamespace("widget/button_highlighted");
	private static final Identifier VANILLA_BUTTON_OFF = Identifier.withDefaultNamespace("widget/button_disabled");

	/**
	 * A button's face: Minecraft's button texture when the player chose it in the settings (tinted with
	 * {@code tint}, white for none), or Orbit's flat one in {@code fill} with an edge. Returns whether it was
	 * Minecraft's.
	 */
	public static boolean buttonFace(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean active, boolean hot, int fill, int tint) {
		if (OrbitClient.get().config().vanillaButtons) {
			Identifier sprite = !active ? VANILLA_BUTTON_OFF : hot ? VANILLA_BUTTON_HOT : VANILLA_BUTTON;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, active ? tint : -1);
			return true;
		}
		graphics.fill(x, y, x + width, y + height, active && hot ? EDGE_HOT : EDGE);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
		return false;
	}

	/** A dashed frame: two pixels drawn, two left out, all around. */
	public static void dashed(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		for (int i = 0; i < width; i += 4) {
			int end = Math.min(i + 2, width);
			graphics.fill(x + i, y, x + end, y + 1, color);
			graphics.fill(x + i, y + height - 1, x + end, y + height, color);
		}
		for (int i = 0; i < height; i += 4) {
			int end = Math.min(i + 2, height);
			graphics.fill(x, y + i, x + 1, y + end, color);
			graphics.fill(x + width - 1, y + i, x + width, y + end, color);
		}
	}

	/** A box with its corners cut by one bubble pixel ({@code unit} GUI pixels): message cards. */
	public static void round(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color, int unit) {
		graphics.fill(x + unit, y, x + width - unit, y + height, color);
		graphics.fill(x, y + unit, x + unit, y + height - unit, color);
		graphics.fill(x + width - unit, y + unit, x + width, y + height - unit, color);
	}

	/**
	 * A speech bubble: a box with corners cut by one bubble pixel ({@code unit} GUI pixels), except at the bottom on
	 * the speaker's side, where one more pixel points out (right for {@code right}, else left).
	 */
	public static void bubble(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color, int unit, boolean right) {
		graphics.fill(x + unit, y, x + width - unit, y + height - unit, color);
		graphics.fill(x, y + unit, x + width, y + height - unit, color);
		if (right) {
			graphics.fill(x + unit, y + height - unit, x + width + unit, y + height, color);
		} else {
			graphics.fill(x - unit, y + height - unit, x + width - unit, y + height, color);
		}
	}

	/** A filled box with its corner pixels cut off, such as a message bubble. */
	public static void round(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		graphics.fill(x + 1, y, x + width - 1, y + height, color);
		graphics.fill(x, y + 1, x + 1, y + height - 1, color);
		graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
	}

	public static void icon(GuiGraphicsExtractor graphics, String name, int x, int y, int color) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon(name), x, y, 9, 9, color);
	}

	/** A gradient from left to right (Minecraft's own gradient goes from top to bottom), in 2-pixel strips. */
	public static void hGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int left, int right) {
		int width = x1 - x0;
		for (int x = 0; x < width; x += 2) {
			float t = width <= 2 ? 1 : x / (float) (width - 2);
			graphics.fill(x0 + x, y0, Math.min(x1, x0 + x + 2), y1, mix(left, right, t));
		}
	}

	/** A horizontal line with an optional label in its middle, like the website's section rules. */
	public static void separator(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, Component label) {
		if (label == null) {
			graphics.fill(x, y, x + width, y + 1, BORDER);
			return;
		}
		int textWidth = font.width(label);
		int center = x + width / 2;
		int gap = 6;
		graphics.fill(x, y, center - textWidth / 2 - gap, y + 1, BORDER);
		graphics.fill(center + (textWidth + 1) / 2 + gap, y, x + width, y + 1, BORDER);
		graphics.text(font, label, center - textWidth / 2, y - 4, MUTED, false);
	}

	/** Text cut to a width, with an ellipsis. */
	public static FormattedCharSequence clip(Font font, Component text, int width) {
		if (font.width(text) <= width) {
			return text.getVisualOrderText();
		}
		String ellipsis = "...";
		String plain = text.getString();
		int keep = Math.max(0, font.plainSubstrByWidth(plain, Math.max(0, width - font.width(ellipsis))).length());
		return Component.literal(plain.substring(0, keep) + ellipsis).withStyle(text.getStyle()).getVisualOrderText();
	}

	/** Mixes two ARGB colors. */
	public static int mix(int a, int b, float t) {
		int alpha = Math.round(((a >>> 24) & 0xFF) * (1 - t) + ((b >>> 24) & 0xFF) * t);
		int red = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int green = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int blue = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	public static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
	}
}
