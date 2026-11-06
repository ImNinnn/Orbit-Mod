package dev.orbit.cosmetic.common.model;

import java.util.List;

/**
 * Body parts: cosmetics that replace parts of the player instead of adding to them. A body part file uses the same
 * anchor groups as other cosmetics ({@code HEAD}, {@code BODY}, the arms and the legs, with the vanilla pivots);
 * every group with geometry replaces that vanilla part, so a file with all six replaces the whole player.
 *
 * <p>A replaced part must stay close to the vanilla part's size: everything it draws must fit in the vanilla part's
 * box made 50% bigger around its centre. It must also draw something, so a body part cannot make a player
 * invisible. Every client checks this itself, so a part that breaks a rule simply keeps the vanilla part.
 *
 * <p>Masks are bits of {@link Anchor#ordinal()}.
 */
public final class BodyParts {
	/** The six parts a body part can replace, in menu order. */
	public static final List<Anchor> PARTS = List.of(Anchor.HEAD, Anchor.BODY, Anchor.RIGHT_ARM, Anchor.LEFT_ARM, Anchor.RIGHT_LEG, Anchor.LEFT_LEG);
	/** How much bigger than the vanilla part a replacement may be, on every axis. */
	public static final float MAX_GROWTH = 1.5F;

	/** Whether a part is fine to replace, and if not, why. */
	public enum Check {
		/** Draws something and fits. */
		OK,
		/** The file has nothing for this part. */
		MISSING,
		/** Bigger than {@link #MAX_GROWTH} times the vanilla part. */
		TOO_BIG
	}

	/** The vanilla parts' boxes in Blockbench pixels (Y up, feet at 0, facing north): from x, y, z, then to x, y, z. */
	private static final float[][] VANILLA = new float[Anchor.values().length][];

	static {
		VANILLA[Anchor.HEAD.ordinal()] = new float[] {-4, 24, -4, 4, 32, 4};
		VANILLA[Anchor.BODY.ordinal()] = new float[] {-4, 12, -2, 4, 24, 2};
		VANILLA[Anchor.RIGHT_ARM.ordinal()] = new float[] {4, 12, -2, 8, 24, 2};
		VANILLA[Anchor.LEFT_ARM.ordinal()] = new float[] {-8, 12, -2, -4, 24, 2};
		VANILLA[Anchor.RIGHT_LEG.ordinal()] = new float[] {-0.1F, 0, -2, 3.9F, 12, 2};
		VANILLA[Anchor.LEFT_LEG.ordinal()] = new float[] {-3.9F, 0, -2, 0.1F, 12, 2};
	}

	private BodyParts() {
	}

	public static int bit(Anchor anchor) {
		return 1 << anchor.ordinal();
	}

	/** Every part. */
	public static int all() {
		int mask = 0;
		for (Anchor part : PARTS) {
			mask |= bit(part);
		}
		return mask;
	}

	/** The vanilla box of a part (see {@link #VANILLA}), or null for {@link Anchor#ROOT}. */
	public static float[] vanillaBox(Anchor part) {
		float[] box = VANILLA[part.ordinal()];
		return box == null ? null : box.clone();
	}

	/** Checks every part of a model; indexed by {@link Anchor#ordinal()} ({@link Anchor#ROOT} is always MISSING). */
	public static Check[] check(CosmeticModel model) {
		float[][] extents = extents(model);
		Check[] result = new Check[Anchor.values().length];
		for (Anchor anchor : Anchor.values()) {
			float[] extent = extents[anchor.ordinal()];
			float[] box = VANILLA[anchor.ordinal()];
			if (box == null || extent == null) {
				result[anchor.ordinal()] = Check.MISSING;
				continue;
			}
			boolean fits = true;
			for (int axis = 0; axis < 3; axis++) {
				float center = (box[axis] + box[axis + 3]) / 2;
				float half = (box[axis + 3] - box[axis]) / 2 * MAX_GROWTH + 0.001F;
				fits &= extent[axis] >= center - half && extent[axis + 3] <= center + half;
			}
			result[anchor.ordinal()] = fits ? Check.OK : Check.TOO_BIG;
		}
		return result;
	}

	/** The parts that can be replaced: present and within the size limit. */
	public static int valid(CosmeticModel model) {
		Check[] checks = check(model);
		int mask = 0;
		for (Anchor part : PARTS) {
			if (checks[part.ordinal()] == Check.OK) {
				mask |= bit(part);
			}
		}
		return mask;
	}

	/**
	 * The box around each anchor's geometry in Blockbench pixels (from x, y, z, then to x, y, z), rest rotations
	 * included; null for anchors without geometry.
	 */
	static float[][] extents(CosmeticModel model) {
		List<Bone> bones = model.bones();
		// Each bone's transform to Blockbench space: a 3x3 rotation (row-major) and an offset.
		double[][] rotations = new double[bones.size()][];
		double[][] offsets = new double[bones.size()][];
		float[][] extents = new float[Anchor.values().length][];
		for (Bone bone : bones) {
			double[] parentRotation;
			double[] parentOffset;
			if (bone.parent() >= 0 && rotations[bone.parent()] != null) {
				parentRotation = rotations[bone.parent()];
				parentOffset = offsets[bone.parent()];
			} else {
				// Model space (the renderer's): X and Y flipped from Blockbench, origin at the anchor's pivot.
				Anchor anchor = bone.anchor();
				parentRotation = new double[] {-1, 0, 0, 0, -1, 0, 0, 0, 1};
				parentOffset = new double[] {anchor.pivotX(), anchor.pivotY(), anchor.pivotZ()};
			}
			double[] pivot = transform(parentRotation, parentOffset, bone.pivotX(), bone.pivotY(), bone.pivotZ());
			double[] rotation = multiply(parentRotation, rotationZyx(bone.rotZ(), bone.rotY(), bone.rotX()));
			rotations[bone.index()] = rotation;
			offsets[bone.index()] = pivot;
			if (bone.quads().isEmpty()) {
				continue;
			}
			int slot = bone.anchor().ordinal();
			for (Quad quad : bone.quads()) {
				float[] positions = quad.positions();
				for (int i = 0; i + 2 < positions.length; i += 3) {
					double[] p = transform(rotation, pivot, positions[i], positions[i + 1], positions[i + 2]);
					float[] extent = extents[slot];
					if (extent == null) {
						extent = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
						extents[slot] = extent;
					}
					for (int axis = 0; axis < 3; axis++) {
						extent[axis] = Math.min(extent[axis], (float) p[axis]);
						extent[axis + 3] = Math.max(extent[axis + 3], (float) p[axis]);
					}
				}
			}
		}
		return extents;
	}

	private static double[] transform(double[] rotation, double[] offset, double x, double y, double z) {
		return new double[] {
			offset[0] + rotation[0] * x + rotation[1] * y + rotation[2] * z,
			offset[1] + rotation[3] * x + rotation[4] * y + rotation[5] * z,
			offset[2] + rotation[6] * x + rotation[7] * y + rotation[8] * z,
		};
	}

	/** Rotation by x, then y, then z (radians), as the renderer turns bones. */
	private static double[] rotationZyx(double z, double y, double x) {
		double[] rx = {1, 0, 0, 0, Math.cos(x), -Math.sin(x), 0, Math.sin(x), Math.cos(x)};
		double[] ry = {Math.cos(y), 0, Math.sin(y), 0, 1, 0, -Math.sin(y), 0, Math.cos(y)};
		double[] rz = {Math.cos(z), -Math.sin(z), 0, Math.sin(z), Math.cos(z), 0, 0, 0, 1};
		return multiply(rz, multiply(ry, rx));
	}

	private static double[] multiply(double[] a, double[] b) {
		double[] result = new double[9];
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				result[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column];
			}
		}
		return result;
	}
}
