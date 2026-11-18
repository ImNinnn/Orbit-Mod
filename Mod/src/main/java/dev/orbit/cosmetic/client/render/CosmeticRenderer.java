package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.client.gfx.PlayerBody;
import net.minecraft.resources.Identifier;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.common.anim.PoseBuffer;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.ParticleEmitters;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws cosmetics as custom geometry. Bone matrices are computed when submitting (they are captured by the
 * geometry callback, which the renderer runs later in the frame), mirroring {@code ModelPart.translateAndRotate}:
 * translate to the pivot, rotate Z then Y then X, then scale.
 */
public final class CosmeticRenderer {
	private CosmeticRenderer() {
	}

	/**
	 * @param onlyAnchor when not null, only bones of this anchor are drawn and the pose stack must already be
	 *                   positioned on that body part (used for first-person arms)
	 */
	public static void submit(List<RenderPiece> pieces, PlayerBody model, PoseStack poseStack, OrbitCanvas canvas, int light, Anchor onlyAnchor) {
		for (RenderPiece piece : pieces) {
			BakedCosmetic baked = piece.baked();
			if (baked.isReleased()) {
				continue;
			}
			for (Anchor anchor : baked.drawnAnchors) {
				if (!piece.draws(anchor)) {
					continue;
				}
				if (onlyAnchor != null) {
					if (anchor == onlyAnchor) {
						submitAnchor(baked, piece, anchor, poseStack, canvas, light);
					}
					continue;
				}
				poseStack.pushPose();
				model.moveTo(anchor, poseStack);
				submitAnchor(baked, piece, anchor, poseStack, canvas, light);
				poseStack.popPose();
			}
		}
	}

	private static void submitAnchor(BakedCosmetic baked, RenderPiece piece, Anchor anchor, PoseStack poseStack, OrbitCanvas canvas, int light) {
		PoseBuffer pose = piece.pose();
		int style = piece.style();
		int[] bones = baked.bonesByAnchor.get(anchor);
		Matrix4f[] matrices = boneMatrices(baked.model().bones(), bones, pose);
		for (int texture = 0; texture < baked.textureCount(); texture++) {
			if (!usesTexture(baked, bones, texture)) {
				continue;
			}
			int t = texture;
			// A PLAYER/SKIN texture shows the wearer's skin.
			boolean skin = baked.isSkinSlot(texture) && piece.skin() != null;
			Identifier image = skin ? piece.skin() : baked.texture(texture, style);
			OrbitCanvas.Look look = skin ? OrbitCanvas.Look.TRANSLUCENT : baked.look(texture, style);
			float[] frame = piece.frames() == null || texture >= piece.frames().length ? null : piece.frames()[texture];
			canvas.draw(poseStack, image, look, (posed, normal, out) -> emit(baked, bones, matrices, t, posed, normal, out, light, piece.overlay(), piece.color(), frame));
		}
	}

	private static boolean usesTexture(BakedCosmetic baked, int[] bones, int texture) {
		for (int bone : bones) {
			if (baked.meshes[bone][texture] != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Keeps where the pieces and the player's body are drawn, for clicking on them (see {@link PickBuffer}). The pose
	 * stack is where the player model is drawn from, as for {@link #submit}.
	 */
	public static void record(List<RenderPiece> pieces, PlayerBody model, PoseStack poseStack, PickBuffer pick) {
		pick.reset();
		model.boxes(poseStack, (pose, x0, y0, z0, x1, y1, z1) -> pick.addBox(null, pose, x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16));
		Matrix4f position = new Matrix4f();
		for (RenderPiece piece : pieces) {
			BakedCosmetic baked = piece.baked();
			if (baked.isReleased()) {
				continue;
			}
			for (Anchor anchor : baked.drawnAnchors) {
				if (!piece.draws(anchor)) {
					continue;
				}
				poseStack.pushPose();
				model.moveTo(anchor, poseStack);
				int[] bones = baked.bonesByAnchor.get(anchor);
				Matrix4f[] matrices = boneMatrices(baked.model().bones(), bones, piece.pose());
				for (int bone : bones) {
					position.set(poseStack.last().pose()).mul(matrices[bone]);
					for (float[] mesh : baked.meshes[bone]) {
						if (mesh == null) {
							continue;
						}
						for (int i = 0; i < mesh.length; i += 4 * BakedCosmetic.STRIDE) {
							pick.add(baked.id(), position, mesh, i, BakedCosmetic.STRIDE);
						}
					}
				}
				poseStack.popPose();
			}
		}
	}

	/** How often an emitter group emits this frame, from its animated size (see {@link ParticleEmitters#rate}). */
	public static float emitterRate(BakedCosmetic baked, PoseBuffer pose, int bone) {
		List<Bone> bones = baked.model().bones();
		int[] anchorBones = bone < bones.size() ? baked.bonesByAnchor.get(bones.get(bone).anchor()) : null;
		Matrix4f matrix = anchorBones == null ? null : boneMatrices(bones, anchorBones, pose)[bone];
		if (matrix == null) {
			return 1;
		}
		Vector3f size = matrix.getScale(new Vector3f());
		return ParticleEmitters.rate(size.x, size.y, size.z);
	}

	static Matrix4f[] boneMatrices(List<Bone> allBones, int[] bones, PoseBuffer pose) {
		Matrix4f[] matrices = new Matrix4f[allBones.size()];
		boolean animated = pose != null && !pose.isEmpty();
		Quaternionf rotation = new Quaternionf();
		for (int index : bones) {
			Bone bone = allBones.get(index);
			Matrix4f matrix = bone.parent() >= 0 && matrices[bone.parent()] != null ? new Matrix4f(matrices[bone.parent()]) : new Matrix4f();
			float px = bone.pivotX();
			float py = bone.pivotY();
			float pz = bone.pivotZ();
			float rx = bone.rotX();
			float ry = bone.rotY();
			float rz = bone.rotZ();
			float sx = 1;
			float sy = 1;
			float sz = 1;
			if (animated) {
				int o = index * 3;
				px += pose.position[o];
				py += pose.position[o + 1];
				pz += pose.position[o + 2];
				rx += pose.rotation[o];
				ry += pose.rotation[o + 1];
				rz += pose.rotation[o + 2];
				sx = pose.scale[o];
				sy = pose.scale[o + 1];
				sz = pose.scale[o + 2];
			}
			matrix.translate(px / 16f, py / 16f, pz / 16f);
			if (rx != 0 || ry != 0 || rz != 0) {
				matrix.rotate(rotation.rotationZYX(rz, ry, rx));
			}
			if (sx != 1 || sy != 1 || sz != 1) {
				matrix.scale(sx, sy, sz);
			}
			matrices[index] = matrix;
		}
		return matrices;
	}

	private static void emit(BakedCosmetic baked, int[] bones, Matrix4f[] matrices, int texture, Matrix4f pose, Matrix3f poseNormal, OrbitCanvas.Vertices out, int light, int overlay, int color,
		float @org.jspecify.annotations.Nullable [] frame) {
		Matrix4f position = new Matrix4f();
		Matrix3f normal = new Matrix3f();
		Matrix3f boneNormal = new Matrix3f();
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (int bone : bones) {
			float[] mesh = baked.meshes[bone][texture];
			if (mesh == null) {
				continue;
			}
			position.set(pose).mul(matrices[bone]);
			normal.set(poseNormal).mul(boneNormal.set(matrices[bone]));
			for (int i = 0; i < mesh.length; i += BakedCosmetic.STRIDE) {
				position.transformPosition(mesh[i], mesh[i + 1], mesh[i + 2], p);
				normal.transform(mesh[i + 5], mesh[i + 6], mesh[i + 7], n);
				float length = n.lengthSquared();
				if (length > 1.0e-12f) {
					n.mul((float) (1 / Math.sqrt(length)));
				} else {
					n.set(0, 1, 0);
				}
				// An animated texture: into the frame showing now.
				float u = frame == null ? mesh[i + 3] : mesh[i + 3] * frame[0];
				float v = frame == null ? mesh[i + 4] : frame[2] + mesh[i + 4] * frame[1];
				out.vertex(p.x, p.y, p.z, color, u, v, overlay, light, n.x, n.y, n.z);
			}
		}
	}
}
