package dev.orbit.cosmetic.common.anim;

/**
 * How one axis of a part reacts to movement.
 *
 * @param strength output per unit of the source (pixels or degrees per block/second); negative inverts
 * @param min lowest output (pixels or degrees); 0 keeps the part from going the negative way, like a chest lid that
 *            must not sink into the chest
 * @param max highest output (pixels or degrees); if it is below {@code min}, the two are swapped
 * @param resistance 0 follows the movement instantly; values near 1 react slowly and settle smoothly
 */
public record AxisDrive(PhysicsSource source, float strength, float min, float max, float resistance) {
	public static final float MAX_POSITION_STRENGTH = 8f;
	public static final float MAX_ROTATION_STRENGTH = 45f;
	public static final float MAX_POSITION = 16f;
	public static final float MAX_ROTATION = 180f;
	public static final float MAX_RESISTANCE = 0.98f;
	public static final AxisDrive OFF = new AxisDrive(PhysicsSource.NONE, 0, 0, 0, 0.5f);

	public AxisDrive {
		if (source == null) {
			source = PhysicsSource.NONE;
		}
		strength = finite(strength, 0);
		min = finite(min, 0);
		max = finite(max, 0);
		resistance = Math.max(0, Math.min(MAX_RESISTANCE, finite(resistance, 0.5f)));
	}

	/** Limits values to the ranges allowed for a position (axis 0-2) or rotation (axis 3-5) axis. */
	public AxisDrive clamped(boolean rotation) {
		float strengthLimit = rotation ? MAX_ROTATION_STRENGTH : MAX_POSITION_STRENGTH;
		float limit = rotation ? MAX_ROTATION : MAX_POSITION;
		return new AxisDrive(source, Math.clamp(strength, -strengthLimit, strengthLimit), Math.clamp(min, -limit, limit), Math.clamp(max, -limit, limit), resistance);
	}

	public boolean active() {
		return source != PhysicsSource.NONE && strength != 0 && (min != 0 || max != 0);
	}

	/** {@code value} kept between the minimum and the maximum. */
	public double limit(double value) {
		return Math.clamp(value, Math.min(min, max), Math.max(min, max));
	}

	public AxisDrive withSource(PhysicsSource value) {
		return new AxisDrive(value, strength, min, max, resistance);
	}

	public AxisDrive withStrength(float value) {
		return new AxisDrive(source, value, min, max, resistance);
	}

	public AxisDrive withMin(float value) {
		return new AxisDrive(source, strength, value, max, resistance);
	}

	public AxisDrive withMax(float value) {
		return new AxisDrive(source, strength, min, value, resistance);
	}

	public AxisDrive withResistance(float value) {
		return new AxisDrive(source, strength, min, max, value);
	}

	private static float finite(float value, float fallback) {
		return Float.isFinite(value) ? value : fallback;
	}
}
