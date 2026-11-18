package dev.orbit.cosmetic.common.util;

/**
 * Whether this is a development run (from the source tree), where test switches (system properties pointing Orbit
 * at a local test backend or a fake of Mojang's services) are allowed. The loader's entrypoint sets it first thing;
 * it stays off otherwise, so released builds never follow those switches.
 */
public final class Development {
	private static volatile boolean enabled;

	private Development() {
	}

	public static void set(boolean value) {
		enabled = value;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/** A system property's value in a development run, null otherwise (or when it is not set). */
	public static String property(String name) {
		return enabled ? System.getProperty(name) : null;
	}
}
