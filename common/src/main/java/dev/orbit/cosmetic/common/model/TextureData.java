package dev.orbit.cosmetic.common.model;

/**
 * A texture embedded in a model.
 *
 * @param png the raw PNG bytes (validated header only; decoding happens on the client)
 * @param translucent whether any pixel has partial alpha, which selects a translucent render type.
 *                    Determined by the client after decoding; the parser sets false.
 * @param uvWidth the size of the texture's UV space in the model (its UVs are divided by it)
 * @param uvHeight see uvWidth
 * @param frameTime ticks per frame when Blockbench saved it as an animated texture, else 0
 *                  (see {@link dev.orbit.cosmetic.common.anim.TextureAnimation})
 */
public record TextureData(String name, byte[] png, int width, int height, boolean translucent, float uvWidth, float uvHeight, int frameTime) {
	public TextureData(String name, byte[] png, int width, int height, boolean translucent) {
		this(name, png, width, height, translucent, width, height, 0);
	}

	public TextureData withTranslucent(boolean value) {
		return new TextureData(name, png, width, height, value, uvWidth, uvHeight, frameTime);
	}

	/**
	 * Named and sized for the wearer's skin: {@code PLAYER} or {@code SKIN} (any case, with or without
	 * {@code .png}), 64x64. Such a texture is drawn with the skin of whoever wears it, provided it is empty
	 * (fully transparent), which the game checks once the image is decoded.
	 */
	public boolean isSkinSlot() {
		String base = name == null ? "" : name.trim();
		if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
			base = base.substring(0, base.length() - 4);
		}
		return (base.equalsIgnoreCase("PLAYER") || base.equalsIgnoreCase("SKIN")) && width == 64 && height == 64;
	}
}
