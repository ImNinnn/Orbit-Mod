package dev.orbit.cosmetic.common.emote;

import java.util.Arrays;

/**
 * What an emote does to the player this frame, in Minecraft model space (pixels, Y down, the neck at 0 and the feet
 * at 24): rotations in radians, positions in pixels, scales multiplied. The six body parts ({@link EmoteAnimation#PARTS})
 * get offsets from their rest pose; parts the emote does not animate keep the game's own pose (head look, walking
 * legs). {@link #UPPER} turns everything but the legs around {@link #upperPivot}, and {@link #ROOT} moves the whole
 * player around {@link #rootPivot}.
 */
public final class BodyPose {
	/** Index of the upper half (head, body and arms together) in the arrays. */
	public static final int UPPER = EmoteAnimation.PARTS.length;
	/** Index of the whole player in the arrays. */
	public static final int ROOT = UPPER + 1;
	private static final int SLOTS = ROOT + 1;

	public final float[] rotation = new float[SLOTS * 3];
	public final float[] position = new float[SLOTS * 3];
	public final float[] scale = new float[SLOTS * 3];
	public final boolean[] animated = new boolean[SLOTS];
	/** Where the upper half bends (the bottom of the body), and what the whole player turns around (the feet). */
	public final float[] upperPivot = {0, 12, 0};
	public final float[] rootPivot = {0, 24, 0};

	public BodyPose() {
		reset();
	}

	public void reset() {
		Arrays.fill(rotation, 0);
		Arrays.fill(position, 0);
		Arrays.fill(scale, 1);
		Arrays.fill(animated, false);
	}

	public boolean isEmpty() {
		for (boolean part : animated) {
			if (part) {
				return false;
			}
		}
		return true;
	}

	public void copyFrom(BodyPose other) {
		System.arraycopy(other.rotation, 0, rotation, 0, rotation.length);
		System.arraycopy(other.position, 0, position, 0, position.length);
		System.arraycopy(other.scale, 0, scale, 0, scale.length);
		System.arraycopy(other.animated, 0, animated, 0, animated.length);
		System.arraycopy(other.upperPivot, 0, upperPivot, 0, 3);
		System.arraycopy(other.rootPivot, 0, rootPivot, 0, 3);
	}
}
