package dev.orbit.cosmetic.common.anim;

/**
 * How one particle emitter of a cosmetic or emote behaves. Any group of the model can be an emitter; its particles
 * appear where that group is, then live in the world. These settings are shared with other players (every client
 * simulates the particles itself), so every value is clamped to a sane range when built.
 *
 * <p>Directions are Blockbench's, relative to the player when the particle appears: X to the side, Y up, Z to the
 * back (the player faces -Z). A particle moves like a car: {@code speed} is its velocity in its own frame, which
 * starts as the player's, and {@code turn} turns that frame, so moving forward while turning around Y goes in a
 * circle.
 *
 * @param bone the emitter group's bone index in the model
 * @param enabled false to keep the emitter quiet
 * @param texture index of the texture drawn (with the cosmetic's alternate textures, its {@code _tN} sibling)
 * @param spinMin how fast a particle turns on itself, in degrees per second: each picks a speed between this and
 *                {@code spinMax} (either may be the larger)
 * @param spinMax see {@code spinMin}
 * @param speedX speed in blocks per second (likewise Y and Z)
 * @param turnX how fast the particle's direction turns around X, in degrees per second (likewise Y and Z)
 * @param speedChangeX change of {@code speedX} per second (likewise the others)
 * @param turnChangeX change of {@code turnX} per second (likewise the others)
 * @param spawnMin shortest wait before the next particle, in ticks
 * @param spawnMax longest wait before the next particle, in ticks
 * @param sizeStart size when a particle appears, in pixels (sixteenths of a block)
 * @param sizeEnd size at the end of its life, in pixels; it grows or shrinks evenly in between
 * @param lifetime seconds a particle lives
 * @param fadeOut seconds at the end of its life during which it fades out
 * @param opacity 0 (invisible) to 1 (opaque)
 * @param offsetX how far from the group a particle may appear, in pixels either way, picked at random (likewise Y and Z)
 * @param rotation how far a particle may be turned on itself when it appears, in degrees either way, picked at random
 * @param glow drawn at full brightness, ignoring the light around it
 */
public record ParticleSettings(
	int bone,
	boolean enabled,
	int texture,
	float spinMin, float spinMax,
	float speedX, float speedY, float speedZ,
	float turnX, float turnY, float turnZ,
	float speedChangeX, float speedChangeY, float speedChangeZ,
	float turnChangeX, float turnChangeY, float turnChangeZ,
	int spawnMin, int spawnMax,
	float sizeStart, float sizeEnd,
	float lifetime,
	float fadeOut,
	float opacity,
	float offsetX, float offsetY, float offsetZ,
	float rotation,
	boolean glow
) {
	/** At most this many particles per player at once, all emitters together. */
	public static final int MAX_PER_PLAYER = 20;
	/** At most this many emitters per cosmetic or emote. */
	public static final int MAX_EMITTERS = 8;
	public static final float MAX_SPIN = 3600;
	public static final float MAX_SPEED = 20;
	public static final float MAX_TURN = 1440;
	public static final float MAX_SPEED_CHANGE = 50;
	public static final float MAX_TURN_CHANGE = 2880;
	public static final int MAX_SPAWN_TICKS = 1200;
	public static final float MAX_SIZE = 32;
	public static final float MIN_LIFETIME = 0.05F;
	public static final float MAX_LIFETIME = 20;
	public static final float MAX_OFFSET = 64;
	public static final float MAX_ROTATION = 180;

	public ParticleSettings {
		bone = Math.clamp(bone, 0, 0xFFFF);
		texture = Math.clamp(texture, -1, 255);
		spinMin = clamp(spinMin, MAX_SPIN);
		spinMax = clamp(spinMax, MAX_SPIN);
		speedX = clamp(speedX, MAX_SPEED);
		speedY = clamp(speedY, MAX_SPEED);
		speedZ = clamp(speedZ, MAX_SPEED);
		turnX = clamp(turnX, MAX_TURN);
		turnY = clamp(turnY, MAX_TURN);
		turnZ = clamp(turnZ, MAX_TURN);
		speedChangeX = clamp(speedChangeX, MAX_SPEED_CHANGE);
		speedChangeY = clamp(speedChangeY, MAX_SPEED_CHANGE);
		speedChangeZ = clamp(speedChangeZ, MAX_SPEED_CHANGE);
		turnChangeX = clamp(turnChangeX, MAX_TURN_CHANGE);
		turnChangeY = clamp(turnChangeY, MAX_TURN_CHANGE);
		turnChangeZ = clamp(turnChangeZ, MAX_TURN_CHANGE);
		spawnMin = Math.clamp(spawnMin, 1, MAX_SPAWN_TICKS);
		spawnMax = Math.clamp(spawnMax, spawnMin, MAX_SPAWN_TICKS);
		sizeStart = Float.isFinite(sizeStart) ? Math.clamp(sizeStart, 0, MAX_SIZE) : 2;
		sizeEnd = Float.isFinite(sizeEnd) ? Math.clamp(sizeEnd, 0, MAX_SIZE) : sizeStart;
		lifetime = Float.isFinite(lifetime) ? Math.clamp(lifetime, MIN_LIFETIME, MAX_LIFETIME) : 2;
		fadeOut = Float.isFinite(fadeOut) ? Math.clamp(fadeOut, 0, lifetime) : 0;
		opacity = Float.isFinite(opacity) ? Math.clamp(opacity, 0, 1) : 1;
		offsetX = spread(offsetX, MAX_OFFSET);
		offsetY = spread(offsetY, MAX_OFFSET);
		offsetZ = spread(offsetZ, MAX_OFFSET);
		rotation = spread(rotation, MAX_ROTATION);
	}

	/** What a newly added emitter does: small particles drifting up, drawn with {@code texture} (-1: none yet). */
	public static ParticleSettings defaults(int bone, int texture) {
		return new ParticleSettings(bone, texture >= 0, texture, 0, 0, 0, 0.5F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 20, 3, 1, 2, 0.5F, 1, 0, 0, 0, 0, false);
	}

	private static float clamp(float value, float limit) {
		return Float.isFinite(value) ? Math.clamp(value, -limit, limit) : 0;
	}

	/** A random range either way: 0 to {@code limit}. */
	private static float spread(float value, float limit) {
		return Float.isFinite(value) ? Math.clamp(value, 0, limit) : 0;
	}

	/** The values as an array, in the order of {@link #VALUE_NAMES} (for menus and files). */
	public float[] values() {
		return new float[] {spinMin, spinMax, speedX, speedY, speedZ, turnX, turnY, turnZ, speedChangeX, speedChangeY, speedChangeZ,
			turnChangeX, turnChangeY, turnChangeZ, spawnMin, spawnMax, sizeStart, sizeEnd, lifetime, fadeOut, opacity, offsetX, offsetY, offsetZ, rotation};
	}

	/** Names of the numeric values, as stored in settings files. */
	public static final String[] VALUE_NAMES = {"spinMin", "spinMax", "speedX", "speedY", "speedZ", "turnX", "turnY", "turnZ", "speedChangeX", "speedChangeY",
		"speedChangeZ", "turnChangeX", "turnChangeY", "turnChangeZ", "spawnMin", "spawnMax", "sizeStart", "sizeEnd", "lifetime", "fadeOut", "opacity",
		"offsetX", "offsetY", "offsetZ", "rotation"};

	/** These settings with the numeric values replaced (same order as {@link #values()}). */
	public ParticleSettings withValues(float[] v) {
		return new ParticleSettings(bone, enabled, texture, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13],
			Math.round(v[14]), Math.round(v[15]), v[16], v[17], v[18], v[19], v[20], v[21], v[22], v[23], v[24], glow);
	}

	/** These settings with one numeric value replaced (index into {@link #values()}). */
	public ParticleSettings with(int value, float to) {
		float[] v = values();
		v[value] = to;
		return withValues(v);
	}

	public ParticleSettings withBone(int value) {
		return new ParticleSettings(value, enabled, texture, spinMin, spinMax, speedX, speedY, speedZ, turnX, turnY, turnZ, speedChangeX, speedChangeY, speedChangeZ,
			turnChangeX, turnChangeY, turnChangeZ, spawnMin, spawnMax, sizeStart, sizeEnd, lifetime, fadeOut, opacity, offsetX, offsetY, offsetZ, rotation, glow);
	}

	public ParticleSettings withEnabled(boolean value) {
		return new ParticleSettings(bone, value, texture, spinMin, spinMax, speedX, speedY, speedZ, turnX, turnY, turnZ, speedChangeX, speedChangeY, speedChangeZ,
			turnChangeX, turnChangeY, turnChangeZ, spawnMin, spawnMax, sizeStart, sizeEnd, lifetime, fadeOut, opacity, offsetX, offsetY, offsetZ, rotation, glow);
	}

	public ParticleSettings withTexture(int value) {
		return new ParticleSettings(bone, enabled, value, spinMin, spinMax, speedX, speedY, speedZ, turnX, turnY, turnZ, speedChangeX, speedChangeY, speedChangeZ,
			turnChangeX, turnChangeY, turnChangeZ, spawnMin, spawnMax, sizeStart, sizeEnd, lifetime, fadeOut, opacity, offsetX, offsetY, offsetZ, rotation, glow);
	}

	public ParticleSettings withGlow(boolean value) {
		return new ParticleSettings(bone, enabled, texture, spinMin, spinMax, speedX, speedY, speedZ, turnX, turnY, turnZ, speedChangeX, speedChangeY, speedChangeZ,
			turnChangeX, turnChangeY, turnChangeZ, spawnMin, spawnMax, sizeStart, sizeEnd, lifetime, fadeOut, opacity, offsetX, offsetY, offsetZ, rotation, value);
	}
}
