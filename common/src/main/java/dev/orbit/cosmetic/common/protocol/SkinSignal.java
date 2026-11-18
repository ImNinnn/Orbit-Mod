package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.util.Development;

import java.util.regex.Pattern;

/**
 * The skin and cape a player wears, as Orbit Cloud shares them: Mojang texture hashes
 * ({@code textures.minecraft.net/texture/<hash>}). Only textures Mojang hosts are shared, so every skin passed Mojang's
 * skin moderation. A null texture or cape is left as Minecraft shows it; {@code noCape} hides the cape.
 */
public record SkinSignal(String texture, boolean slim, String cape, boolean noCape) {
	/** Tests serve textures from a local fake of Mojang's texture host; only in a development environment. */
	private static String textures() {
		String override = Development.property("orbit.mojangTextures");
		return override != null ? override.replaceAll("/+$", "") : "https://textures.minecraft.net";
	}

	private static final Pattern HASH = Pattern.compile("[0-9a-f]{32,64}");

	public static boolean isHash(String value) {
		return value != null && HASH.matcher(value).matches();
	}

	/** The hash at the end of a textures.minecraft.net address, or null for anything else. */
	public static String hashOf(String url) {
		if (url == null) {
			return null;
		}
		int slash = url.lastIndexOf('/');
		String hash = slash >= 0 ? url.substring(slash + 1) : url;
		return url.matches("https?://textures\\.minecraft\\.net/texture/[0-9a-f]+") && isHash(hash) ? hash : null;
	}

	/** Where Mojang hosts a texture (as Mojang names it: also what the account API is given). */
	public static String url(String hash) {
		return "http://textures.minecraft.net/texture/" + hash;
	}

	/** Where to download a texture from. */
	public static String downloadUrl(String hash) {
		return textures() + "/texture/" + hash;
	}
}
