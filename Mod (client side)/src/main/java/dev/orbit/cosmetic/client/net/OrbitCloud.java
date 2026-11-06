package dev.orbit.cosmetic.client.net;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Where Orbit Cloud is. It is fixed: the mod only talks to the official, moderated Orbit Cloud. Development runs
 * may point at a local test backend with {@code -Dorbit.backendUrl=...}, which released games ignore.
 * Uses no Minecraft classes, so the update check can use it before the game loads.
 */
public final class OrbitCloud {
	private static final String URL = "https://api.orbitcosmetics.dev";
	/**
	 * The date of the current Terms of Service and Privacy Policy. Orbit Cloud is only used once the player accepted
	 * this version (see {@code ClientConfig.termsAccepted}); a new date asks again.
	 */
	public static final String TERMS_VERSION = "2026-11-02";
	public static final String TERMS_URL = "https://orbitcosmetics.dev/terms.html";
	public static final String PRIVACY_URL = "https://orbitcosmetics.dev/privacy.html";

	private OrbitCloud() {
	}

	/** Orbit Cloud's address, without a trailing slash. */
	public static String url() {
		String override = System.getProperty("orbit.backendUrl");
		if (override != null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return override.trim().replaceAll("/+$", "");
		}
		return URL;
	}
}
