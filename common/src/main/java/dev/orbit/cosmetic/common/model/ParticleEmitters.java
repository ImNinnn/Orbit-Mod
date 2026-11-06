package dev.orbit.cosmetic.common.model;

import java.util.List;
import java.util.Locale;

/**
 * Particle emitters: any group of a model can be one, once it is given particle settings. Particles appear where the
 * group is, so animating the group moves where they appear, and its size sets how many: at its normal size it emits
 * normally, smaller it emits less and less often, and at size 0 it stops.
 */
public final class ParticleEmitters {
	private ParticleEmitters() {
	}

	/** The texture a new emitter draws: the first whose name has "particle" in it, else the first; -1 without textures. */
	public static int defaultTexture(CosmeticModel model) {
		List<TextureData> textures = model.textures();
		for (int i = 0; i < textures.size(); i++) {
			String name = textures.get(i).name();
			if (name != null && name.toLowerCase(Locale.ROOT).contains("particle")) {
				return i;
			}
		}
		return textures.isEmpty() ? -1 : 0;
	}

	/**
	 * How often an emitter emits, from its size (the average of its scale on the three axes): 1 at its normal size,
	 * less when smaller, never at size 0.
	 */
	public static float rate(float scaleX, float scaleY, float scaleZ) {
		float size = (Math.abs(scaleX) + Math.abs(scaleY) + Math.abs(scaleZ)) / 3;
		return Float.isFinite(size) ? Math.clamp(size, 0, 1) : 1;
	}
}
