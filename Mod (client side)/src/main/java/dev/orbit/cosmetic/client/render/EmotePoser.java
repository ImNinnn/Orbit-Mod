package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.common.emote.BodyPose;
import dev.orbit.cosmetic.common.emote.EmoteAnimation;
import dev.orbit.cosmetic.common.model.Anchor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
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

	public static void apply(HumanoidModel<?> model, BodyPose body) {
		for (int part = 0; part < EmoteAnimation.PARTS.length; part++) {
			if (!body.animated[part]) {
				continue;
			}
			ModelPart target = AnchorResolver.part(model, EmoteAnimation.PARTS[part]);
			PartPose rest = target.getInitialPose();
			int o = part * 3;
			target.x = rest.x() + body.position[o];
			target.y = rest.y() + body.position[o + 1];
			target.z = rest.z() + body.position[o + 2];
			target.xRot = rest.xRot() + body.rotation[o];
			target.yRot = rest.yRot() + body.rotation[o + 1];
			target.zRot = rest.zRot() + body.rotation[o + 2];
			target.xScale = rest.xScale() * body.scale[o];
			target.yScale = rest.yScale() * body.scale[o + 1];
			target.zScale = rest.zScale() * body.scale[o + 2];
		}
		if (body.animated[BodyPose.UPPER]) {
			applyUpper(model, body);
		}
	}

	/** Turns, moves and scales the head, body and arms together around the upper half's pivot (model space). */
	private static void applyUpper(HumanoidModel<?> model, BodyPose body) {
		int o = BodyPose.UPPER * 3;
		Quaternionf turn = new Quaternionf().rotationZYX(body.rotation[o + 2], body.rotation[o + 1], body.rotation[o]);
		float sx = body.scale[o];
		float sy = body.scale[o + 1];
		float sz = body.scale[o + 2];
		float[] pivot = body.upperPivot;
		Vector3f offset = new Vector3f();
		Vector3f angles = new Vector3f();
		Quaternionf own = new Quaternionf();
		for (Anchor anchor : UPPER_PARTS) {
			ModelPart part = AnchorResolver.part(model, anchor);
			offset.set((part.x - pivot[0]) * sx, (part.y - pivot[1]) * sy, (part.z - pivot[2]) * sz);
			turn.transform(offset);
			part.x = pivot[0] + body.position[o] + offset.x;
			part.y = pivot[1] + body.position[o + 1] + offset.y;
			part.z = pivot[2] + body.position[o + 2] + offset.z;
			// The part's own rotation, inside the upper half's.
			own.rotationZYX(part.zRot, part.yRot, part.xRot);
			new Quaternionf(turn).mul(own).getEulerAnglesZYX(angles);
			part.xRot = angles.x;
			part.yRot = angles.y;
			part.zRot = angles.z;
			part.xScale *= sx;
			part.yScale *= sy;
			part.zScale *= sz;
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
