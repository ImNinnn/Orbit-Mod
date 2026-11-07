package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.ParticleSystem;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.ParticleEmitters;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Player render layer that draws Orbit cosmetics and starts their particles. Layers run right after the player
 * model's {@code setupAnim}, so the parent model's parts hold this frame's (possibly mod-animated) pose, and the
 * particle emitters are where the player is drawn. Particles of players in menus are drawn here too.
 */
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private HumanoidModel<AvatarRenderState> vanillaModel;

	public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible) {
			return;
		}
		CosmeticRenderData data = state.getData(CosmeticRenderData.KEY);
		ParticleRenderData particles = state.getData(ParticleRenderData.KEY);
		Matrix4f base = state.getData(ParticleRenderData.BASE);
		if (data != null && !data.pieces().isEmpty()) {
			HumanoidModel<?> model = getParentModel();
			if (OrbitClient.get().config().attachMode == ClientConfig.AttachMode.VANILLA) {
				HumanoidModel<AvatarRenderState> vanilla = vanillaModel();
				vanilla.setupAnim(state);
				EmoteRenderData emote = state.getData(EmoteRenderData.KEY);
				if (emote != null) {
					EmotePoser.apply(vanilla, emote.body());
				}
				model = vanilla;
			}
			CosmeticRenderer.submit(data.pieces(), model, poseStack, collector, light, null);
			PickBuffer pick = state.getData(PickBuffer.KEY);
			if (pick != null) {
				CosmeticRenderer.record(data.pieces(), model, poseStack, pick);
			}
			if (particles != null && base != null) {
				emit(particles.system(), data.pieces(), model, poseStack, base, state, light);
			}
		} else if (state.getData(PickBuffer.KEY) != null) {
			state.getData(PickBuffer.KEY).reset();
		}
		if (particles != null && particles.preview() && base != null) {
			// In a menu: the particles live in the preview's own space, which the base pose draws where the player is.
			particles.system().update();
			Matrix4f back = new Matrix4f(base).invert();
			Vector3f right = back.transformDirection(new Vector3f(1, 0, 0)).normalize();
			Vector3f up = back.transformDirection(new Vector3f(0, -1, 0)).normalize();
			// Lit from the front in menus: facing the viewer.
			Vector3f normal = base.transformDirection(new Vector3f(right).cross(up)).normalize();
			ParticleRenderer.submit(particles.system(), 0, 0, 0, base, right, up, normal, new PoseStack(), collector);
		}
	}

	/** Gives every enabled emitter of the worn cosmetics (and the emote) the chance to spawn a particle where it is. */
	private static void emit(ParticleSystem system, List<RenderPiece> pieces, HumanoidModel<?> model, PoseStack poseStack, Matrix4f base, AvatarRenderState state, int light) {
		Matrix4f toPlayer = null;
		Quaternionf unturn = new Quaternionf().rotationY((float) Math.toRadians(180 - state.bodyRot)).conjugate();
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
				AnchorResolver.apply(model, bone.anchor(), poseStack);
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
				system.emit(baked.id().hex() + ":" + bone.index(), settings, state.x + at.x, state.y + at.y, state.z + at.z, state.bodyRot, baked, texture, light,
					ParticleEmitters.rate(size.x, size.y, size.z), unturn.transform(new Vector3f(at)));
			}
		}
	}

	/** A private vanilla model, unaffected by model replacement mods, for the VANILLA attach mode. */
	private HumanoidModel<AvatarRenderState> vanillaModel() {
		if (vanillaModel == null) {
			vanillaModel = new HumanoidModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot());
		}
		return vanillaModel;
	}
}
