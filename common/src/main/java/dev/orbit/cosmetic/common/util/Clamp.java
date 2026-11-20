package dev.orbit.cosmetic.common.util;

/**
 * Java 21's {@code Math.clamp}, for Java 17 (the oldest Minecraft versions Orbit runs on): the same overloads and the
 * same behaviour, an exception when {@code min > max} included, so the shared code behaves alike on every version.
 */
public final class Clamp {
	private Clamp() {
	}

	public static int clamp(long value, int min, int max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return (int) Math.min(max, Math.max(value, min));
	}

	public static long clamp(long value, long min, long max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}

	public static float clamp(float value, float min, float max) {
		if (!(min <= max)) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}

	public static double clamp(double value, double min, double max) {
		if (!(min <= max)) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}
}
