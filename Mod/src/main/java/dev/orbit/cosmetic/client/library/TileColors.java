package dev.orbit.cosmetic.client.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The colours of an item's tile in the Orbit menu, from {@code "gradient"} in its settings file (or in the featured
 * catalog): one colour, which the tile fades into, or two, for the top and the bottom. Colours are {@code "#RRGGBB"}
 * or {@code "#AARRGGBB"}.
 */
public final class TileColors {
	private static final Pattern COLOR = Pattern.compile("#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");
	/** How strongly a single colour shows at the bottom of the tile, unless the colour gives its own alpha. */
	private static final int FADE_ALPHA = 0x70;

	private TileColors() {
	}

	/** Top and bottom colours (ARGB) from a string or an array of one or two strings, or null if there are none. */
	public static int[] of(JsonElement value) {
		List<String> colors = new ArrayList<>();
		if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			colors.add(value.getAsString().trim());
		} else if (value != null && value.isJsonArray()) {
			JsonArray array = value.getAsJsonArray();
			for (JsonElement element : array) {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
					return null;
				}
				colors.add(element.getAsString().trim());
			}
		}
		if (colors.isEmpty() || colors.size() > 2 || !colors.stream().allMatch(color -> COLOR.matcher(color).matches())) {
			return null;
		}
		if (colors.size() == 1) {
			String color = colors.getFirst();
			int argb = parse(color, FADE_ALPHA);
			return new int[] {argb & 0x00FFFFFF, argb};
		}
		return new int[] {parse(colors.get(0), 0xFF), parse(colors.get(1), 0xFF)};
	}

	private static int parse(String color, int defaultAlpha) {
		long value = Long.parseLong(color.substring(1), 16);
		return color.length() == 7 ? defaultAlpha << 24 | (int) value : (int) value;
	}
}
