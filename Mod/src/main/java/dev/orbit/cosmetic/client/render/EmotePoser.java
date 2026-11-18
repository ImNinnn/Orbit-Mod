package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.common.emote.BodyPose;
import dev.orbit.cosmetic.common.emote.EmoteAnimation;
import dev.orbit.cosmetic.common.model.Anchor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Puts an emote's pose on a player model after the game posed it. A body part the emote animates starts from its
 * rest pose (so walking and arm swinging do not mix in) plus the emote's offsets; other parts keep the game's pose.
 * The upper half then turns the head, body and arms together around the hips. The hat, jacket, sleeve and trouser
 * layers are children of these parts and follow them. The whole player's movement (ROOT) is applied by the renderer
 * ({@link #applyRoot}).
 */
public final class EmotePoser {
	private static final Anchor[] UPPER_PARTS = {Anchor.HEAD, Anchor.BODY, Anchor.RIGHT_ARM, Anchor.LEFT_ARM};

	private EmotePoser() {
	}

	public static void apply(PlayerBody model, BodyPose body) {
		float[] rest = new float[9];
		float[] pose = new float[9];
		for (int part = 0; part < EmoteAnimation.PARTS.length; part++) {
			if (!body.animated[part]) {
				continue;
			}
			Anchor anchor = EmoteAnimation.PARTS[part];
			model.rest(anchor, rest);
			int o = part * 3;
			for (int axis = 0; axis < 3; axis++) {
				pose[axis] = rest[axis] + body.position[o + axis];
				pose[3 + axis] = rest[3 + axis] + body.rotation[o + axis];
				pose[6 + axis] = rest[6 + axis] * body.scale[o + axis];
			}
			model.setTransform(anchor, pose);
		}
		if (body.animated[BodyPose.UPPER]) {
			applyUpper(model, body);
		}
	}

	/** Turns, moves and scales the head, body and arms together around the upper half's pivot (model space). */
	private static void applyUpper(PlayerBody model, BodyPose body) {
		int o = BodyPose.UPPER * 3;
		Quaternionf turn = new Quaternionf().rotationZYX(body.rotation[o + 2], body.rotation[o + 1], body.rotation[o]);
		float sx = body.scale[o];
		float sy = body.scale[o + 1];
		float sz = body.scale[o + 2];
		float[] pivot = body.upperPivot;
		Vector3f offset = new Vector3f();
		Vector3f angles = new Vector3f();
		Quaternionf own = new Quaternionf();
		float[] part = new float[9];
		for (Anchor anchor : UPPER_PARTS) {
			model.transform(anchor, part);
			offset.set((part[0] - pivot[0]) * sx, (part[1] - pivot[1]) * sy, (part[2] - pivot[2]) * sz);
			turn.transform(offset);
			part[0] = pivot[0] + body.position[o] + offset.x;
			part[1] = pivot[1] + body.position[o + 1] + offset.y;
			part[2] = pivot[2] + body.position[o + 2] + offset.z;
			// The part's own rotation, inside the upper half's.
			own.rotationZYX(part[5], part[4], part[3]);
			new Quaternionf(turn).mul(own).getEulerAnglesZYX(angles);
			part[3] = angles.x;
			part[4] = angles.y;
			part[5] = angles.z;
			part[6] *= sx;
			part[7] *= sy;
			part[8] *= sz;
			model.setTransform(anchor, part);
		}
	}

	/** Hides the body parts a body part cosmetic replaces (bits of {@link Anchor#ordinal()}); shown ones stay as they are. */
	public static void hide(PlayerBody model, int parts) {
		for (Anchor anchor : Anchor.values()) {
			if (anchor != Anchor.ROOT && (parts & 1 << anchor.ordinal()) != 0) {
				model.setVisible(anchor, false);
			}
		}
	}

	/**
	 * Moves the whole player: called with the pose stack right after the renderer turned the player to face their
	 * way, where the axes are Blockbench's (X to the side, Y up, the feet at 0) in blocks.
	 */
	public static void applyRoot(BodyPose body, PoseStack poseStack) {
		if (!body.animated[BodyPose.ROOT]) {
			return;
		}
		int o = BodyPose.ROOT * 3;
		// Model space (Y down, the neck at 0) back to Blockbench's: X and Y change sign.
		float px = -body.rootPivot[0] / 16;
		float py = (24 - body.rootPivot[1]) / 16;
		float pz = body.rootPivot[2] / 16;
		poseStack.translate(px - body.position[o] / 16, py - body.position[o + 1] / 16, pz + body.position[o + 2] / 16);
		// A matrix rather than a quaternion: PoseStack's quaternion method is named differently in each version.
		poseStack.mulPose(new Matrix4f().rotationZYX(body.rotation[o + 2], -body.rotation[o + 1], -body.rotation[o]).scale(body.scale[o], body.scale[o + 1], body.scale[o + 2]));
		poseStack.translate(-px, -py, -pz);
	}
}
