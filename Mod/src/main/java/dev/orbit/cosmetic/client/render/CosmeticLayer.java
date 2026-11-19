package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.client.gfx.PlayerState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
*///?}

/**
 * The player render layer that draws Orbit's cosmetics (see {@link CosmeticDrawing}). Layers run right after the
 * player model's {@code setupAnim}, so the model's parts hold this frame's pose. This class is the part each
 * Minecraft version provides: how a layer is made and called, and how the vanilla body is posed.
 */
//? if >=1.21.2 {
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private HumanoidModel<AvatarRenderState> vanillaModel;

	public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	//? if >=1.21.9 {
	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
	//?} else {
	/*@Override
	public void render(PoseStack poseStack, MultiBufferSource collector, int light, AvatarRenderState state, float yRot, float xRot) {
	*///?}
		if (state.isInvisible) {
			return;
		}
		PlayerState player = PlayerState.of(state);
		PlayerFrame frame = player.frame();
		if (frame == null) {
			return;
		}
		PlayerBody body = PlayerBody.of(getParentModel());
		if (!frame.pieces.isEmpty() && CosmeticDrawing.onVanillaBody()) {
			HumanoidModel<AvatarRenderState> vanilla = vanillaModel();
			vanilla.setupAnim(state);
			body = PlayerBody.of(vanilla);
			if (frame.emote != null) {
				EmotePoser.apply(body, frame.emote);
			}
		}
		CosmeticDrawing.draw(player, frame, body, poseStack, OrbitCanvas.of(collector), light);
	}

	/** A private vanilla model, unaffected by model replacement mods, for the VANILLA attach mode. */
	private HumanoidModel<AvatarRenderState> vanillaModel() {
		if (vanillaModel == null) {
			vanillaModel = new HumanoidModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot());
		}
		return vanillaModel;
	}
}
//?} else {
/*public final class CosmeticLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private HumanoidModel<AbstractClientPlayer> vanillaModel;

	public CosmeticLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	// Before 1.21.2 the layer reads the player entity; Orbit's state for it is on the entity while it is drawn.
	@Override
	public void render(PoseStack poseStack, MultiBufferSource collector, int light, AbstractClientPlayer entity, float walkPos, float walkSpeed, float partialTick,
		float ageInTicks, float headYaw, float headPitch) {
		PlayerState player = PlayerState.of(entity);
		if (player == null || player.isInvisible()) {
			return;
		}
		PlayerFrame frame = player.frame();
		if (frame == null) {
			return;
		}
		PlayerBody body = PlayerBody.of(getParentModel());
		if (!frame.pieces.isEmpty() && CosmeticDrawing.onVanillaBody()) {
			HumanoidModel<AbstractClientPlayer> vanilla = vanillaModel();
			getParentModel().copyPropertiesTo(vanilla);
			vanilla.prepareMobModel(entity, walkPos, walkSpeed, partialTick);
			vanilla.setupAnim(entity, walkPos, walkSpeed, ageInTicks, headYaw, headPitch);
			body = PlayerBody.of(vanilla);
			if (frame.emote != null) {
				EmotePoser.apply(body, frame.emote);
			}
		}
		CosmeticDrawing.draw(player, frame, body, poseStack, OrbitCanvas.of(collector), light);
	}

	/^* A private vanilla model, unaffected by model replacement mods, for the VANILLA attach mode. ^/
	private HumanoidModel<AbstractClientPlayer> vanillaModel() {
		if (vanillaModel == null) {
			vanillaModel = new HumanoidModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot());
		}
		return vanillaModel;
	}
}
*///?}
