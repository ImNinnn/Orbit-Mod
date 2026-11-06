package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.model.TextureData;

import java.util.ArrayList;
import java.util.List;

/**
 * An animated texture: like a Minecraft .mcmeta texture (and Blockbench's animated textures), the frames are
 * stacked one under the other in the image, each {@code frameWidth} by {@code frameHeight} pixels, and each shows for
 * {@code frameTicks} game ticks (20 a second). The model's UVs point at one frame, or at the top of the strip.
 *
 * @param texture the texture of the model it animates (index)
 */
public record TextureAnimation(int texture, int frameWidth, int frameHeight, int frameTicks) {
	public static final int MAX_TICKS = 200;
	/** At most this many animated textures per cosmetic (the model's texture limit is lower). */
	public static final int MAX_PER_COSMETIC = 16;

	public TextureAnimation {
		frameWidth = Math.clamp(frameWidth, 1, 4096);
		frameHeight = Math.clamp(frameHeight, 1, 4096);
		frameTicks = Math.clamp(frameTicks, 1, MAX_TICKS);
	}

	/** How many frames the image holds (1: not animated). */
	public int frames(TextureData image) {
		if (frameHeight >= image.height() || image.height() % frameHeight != 0) {
			return 1;
		}
		return image.height() / frameHeight;
	}

	/**
	 * Where the model's UVs land in the frame showing at {@code ticks}: {u scale, v scale, v offset}, or null when the
	 * texture shows as it is.
	 */
	public float[] uv(TextureData image, double ticks) {
		int frames = frames(image);
		if (frames <= 1) {
			return null;
		}
		int frame = (int) (Math.floorMod((long) Math.floor(ticks / frameTicks), frames));
		float frameV = frameHeight / (float) image.height();
		// UVs drawn on the whole strip (their space has the image's shape) already point into the first frame; UVs
		// drawn on one frame (Blockbench's animated textures) are scaled into it.
		float uvAspect = image.uvHeight() / image.uvWidth();
		float imageAspect = image.height() / (float) image.width();
		if (Math.abs(uvAspect - imageAspect) < 1.0e-3f) {
			return new float[] {1, 1, frame * frameV};
		}
		return new float[] {Math.min(1, frameWidth / (float) image.width()), frameV, frame * frameV};
	}

	/**
	 * The animations a model's file sets: its textures Blockbench saved as animated (a frame time, and frames the
	 * shape of the texture's UV size stacked in the image).
	 */
	public static List<TextureAnimation> defaults(List<TextureData> textures) {
		List<TextureAnimation> result = new ArrayList<>();
		for (int i = 0; i < textures.size() && result.size() < MAX_PER_COSMETIC; i++) {
			TextureData texture = textures.get(i);
			if (texture.frameTime() <= 0 || texture.uvWidth() <= 0) {
				continue;
			}
			int frameHeight = Math.round(texture.width() * texture.uvHeight() / texture.uvWidth());
			if (frameHeight > 0 && frameHeight < texture.height() && texture.height() % frameHeight == 0) {
				result.add(new TextureAnimation(i, texture.width(), frameHeight, texture.frameTime()));
			}
		}
		return List.copyOf(result);
	}

	/** The UV changes for each texture of a model at {@code ticks} (null entries, or null, when nothing changes). */
	public static float[][] transforms(List<TextureData> textures, List<TextureAnimation> animations, double ticks) {
		float[][] result = null;
		for (TextureAnimation animation : animations) {
			if (animation.texture() < 0 || animation.texture() >= textures.size()) {
				continue;
			}
			float[] uv = animation.uv(textures.get(animation.texture()), ticks);
			if (uv != null) {
				if (result == null) {
					result = new float[textures.size()][];
				}
				result[animation.texture()] = uv;
			}
		}
		return result;
	}
}
