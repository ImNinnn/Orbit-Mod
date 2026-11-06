package dev.orbit.cosmetic.common.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alternate textures ("styles") of a model, found from texture names: {@code wings_t1}, {@code wings_t2} and so on,
 * up to {@code _t5}. A {@code _t1} texture marks a texture with styles; faces use it in Blockbench, and style N draws
 * them with the {@code _tN} texture instead (or {@code _t1} when there is none). A model with
 * {@code upperwings_t1/_t2} and {@code lowerwings_t1/_t2} therefore has two styles. Models without {@code _t1}
 * textures have a single style.
 *
 * @param count how many styles the model has (at least 1)
 * @param remap for each style, the texture to draw instead of each texture of the model
 * @param sample for each style, the texture that represents it (its colour is shown on the style buttons)
 */
public record TextureStyles(int count, int[][] remap, int[] sample) {
	public static final int MAX = 5;
	private static final Pattern NAME = Pattern.compile("(.+)_t([1-" + MAX + "])(?:\\.png)?", Pattern.CASE_INSENSITIVE);

	/** The name a style texture belongs to ({@code wings} for {@code Wings_t2.png}), or null for other textures. */
	public static String baseName(String textureName) {
		Matcher matcher = textureName == null ? null : NAME.matcher(textureName.trim());
		return matcher != null && matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
	}

	public static TextureStyles of(List<TextureData> textures) {
		// Texture index of each style of each name, in the order the names first appear.
		Map<String, int[]> groups = new LinkedHashMap<>();
		for (int i = 0; i < textures.size(); i++) {
			String name = textures.get(i).name();
			Matcher matcher = name == null ? null : NAME.matcher(name.trim());
			if (matcher != null && matcher.matches()) {
				int[] group = groups.computeIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), key -> new int[] {-1, -1, -1, -1, -1});
				int style = Integer.parseInt(matcher.group(2)) - 1;
				if (group[style] < 0) {
					group[style] = i;
				}
			}
		}
		groups.values().removeIf(group -> group[0] < 0);
		int count = 1;
		for (int[] group : groups.values()) {
			for (int style = MAX - 1; style > 0; style--) {
				if (group[style] >= 0) {
					count = Math.max(count, style + 1);
					break;
				}
			}
		}
		int[][] remap = new int[count][textures.size()];
		int[] sample = new int[count];
		for (int style = 0; style < count; style++) {
			for (int i = 0; i < textures.size(); i++) {
				remap[style][i] = i;
			}
			sample[style] = textures.isEmpty() ? -1 : 0;
			boolean sampled = false;
			for (int[] group : groups.values()) {
				int target = group[style] >= 0 ? group[style] : group[0];
				for (int member : group) {
					if (member >= 0) {
						remap[style][member] = target;
					}
				}
				if (!sampled) {
					sample[style] = target;
					sampled = true;
				}
			}
		}
		return new TextureStyles(count, remap, sample);
	}

	/** The texture to draw for a texture of the model in a style; out-of-range styles draw the first one. */
	public int texture(int style, int texture) {
		return remap[style >= 0 && style < count ? style : 0][texture];
	}
}
