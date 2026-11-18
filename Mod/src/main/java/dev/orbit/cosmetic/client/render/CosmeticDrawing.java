package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.ParticleSystem;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.client.gfx.PlayerState;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.ParticleEmitters;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws Orbit's cosmetics on a player and starts their particles, from the player's render layer (each Minecraft
 * version has its own; see CosmeticLayer). It runs right after the player model was posed, so the body parts hold
 * this frame's (possibly mod-animated) pose, and the particle emitters are where the player is drawn. Particles of
 * players in menus are drawn here too.
 */
public final class CosmeticDrawing {
	private CosmeticDrawing() {
	}

	/**
	 * Whether cosmetics hang from a plain vanilla body posed like the player, rather than from the model the game
	 * draws (which model mods may change); the layer then poses that body and applies the emote to it.
	 */
	public static boolean onVanillaBody() {
		return OrbitClient.get().config().attachMode == ClientConfig.AttachMode.VANILLA;
	}

	/**
	 * @param body the body the cosmetics hang from, posed for this frame
	 * @param poseStack where the player model is drawn from
	 */
	public static void draw(PlayerState player, PlayerFrame frame, PlayerBody body, PoseStack poseStack, OrbitCanvas canvas, int light) {
		ParticleSystem particles = frame.particles;
		Matrix4f base = frame.base;
		if (!frame.pieces.isEmpty()) {
			CosmeticRenderer.submit(frame.pieces, body, poseStack, canvas, light, null);
			if (frame.pick != null) {
				CosmeticRenderer.record(frame.pieces, body, poseStack, frame.pick);
			}
			if (particles != null && base != null) {
				emit(particles, frame.pieces, body, poseStack, base, player, light);
			}
		} else if (frame.pick != null) {
			frame.pick.reset();
		}
		if (particles != null && frame.preview && base != null) {
			// In a menu: the particles live in the preview's own space, which the base pose draws where the player is.
			particles.update();
			Matrix4f back = new Matrix4f(base).invert();
			Vector3f right = back.transformDirection(new Vector3f(1, 0, 0)).normalize();
			Vector3f up = back.transformDirection(new Vector3f(0, -1, 0)).normalize();
			// Lit from the front in menus: facing the viewer.
			Vector3f normal = base.transformDirection(new Vector3f(right).cross(up)).normalize();
			ParticleRenderer.submit(particles, 0, 0, 0, base, right, up, normal, new PoseStack(), canvas);
		}
	}

	/** Gives every enabled emitter of the worn cosmetics (and the emote) the chance to spawn a particle where it is. */
	private static void emit(ParticleSystem system, List<RenderPiece> pieces, PlayerBody model, PoseStack poseStack, Matrix4f base, PlayerState player, int light) {
		Matrix4f toPlayer = null;
		Quaternionf unturn = new Quaternionf().rotationY((float) Math.toRadians(180 - player.bodyRot())).conjugate();
		for (RenderPiece piece : pieces) {
			BakedCosmetic baked = piece.baked();
			if (baked.isReleased() || piece.particles().isEmpty()) {
				continue;
			}
			List<Bone> bones = baked.model().bones();
			for (ParticleSettings settings : piece.particles()) {
				if (!settings.enabled() || settings.texture() < 0 || settings.bone() >= bones.size()) {
					continue;
				}
				Bone bone = bones.get(settings.bone());
				int[] anchorBones = baked.bonesByAnchor.get(bone.anchor());
				Matrix4f[] matrices = anchorBones == null ? null : CosmeticRenderer.boneMatrices(bones, anchorBones, piece.pose());
				if (matrices == null || matrices[bone.index()] == null) {
					continue;
				}
				poseStack.pushPose();
				model.moveTo(bone.anchor(), poseStack);
				Vector3f at = poseStack.last().pose().transformPosition(matrices[bone.index()].transformPosition(new Vector3f()));
				poseStack.popPose();
				if (toPlayer == null) {
					toPlayer = new Matrix4f(base).invert();
				}
				// Relative to where the player stands (blocks, world axes), then in the world (or the preview's space).
				toPlayer.transformPosition(at);
				Identifier texture = baked.texture(settings.texture(), piece.style());
				// The group's size (animated scale) sets how often it emits.
				Vector3f size = matrices[bone.index()].getScale(new Vector3f());
				system.emit(baked.id().hex() + ":" + bone.index(), settings, player.x() + at.x, player.y() + at.y, player.z() + at.z, player.bodyRot(), baked, texture, light,
					ParticleEmitters.rate(size.x, size.y, size.z), unturn.transform(new Vector3f(at)));
			}
		}
	}
}
