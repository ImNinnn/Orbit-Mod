package dev.orbit.cosmetic.client.compat;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import dev.orbit.cosmetic.client.gfx.OrbitSkin;
import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.client.gfx.PlayerState;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.render.CosmeticDrawing;
import dev.orbit.cosmetic.client.render.EmotePoser;
import dev.orbit.cosmetic.client.render.PlayerFrame;
import dev.orbit.cosmetic.client.render.VanillaPlayerModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws a {@link PreviewBatch} on Minecraft 1.21.1. Its GUI has no pictures of their own and its player renderer
 * needs a player entity, which menus (the title screen) do not have: the players are drawn straight onto the screen,
 * with the vanilla player model posed here as the game would pose a player standing in the menu.
 */
public final class PreviewRenderer {
	/** How far in front of the screen the players stand (GUI depth units, as the inventory's player). */
	private static final float DEPTH = 50.0F;

	private PreviewRenderer() {
	}

	/** Nothing to register: batches are drawn when they are added. */
	public static void register() {
	}

	/** Draws every player of the batch, clipped to it, over what the screen drew so far. */
	public static void draw(GuiGraphics graphics, PreviewBatch batch) {
		Minecraft minecraft = Minecraft.getInstance();
		float guiScale = (float) minecraft.getWindow().getGuiScale();
		graphics.flush();
		graphics.enableScissor(batch.x0(), batch.y0(), batch.x1(), batch.y1());
		// Like a picture of its own: nothing drawn before hides the players, and what is drawn after goes over them.
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
		Lighting.setupForEntityInInventory();
		// A batch's corners are on the screen itself, whatever moving and scaling the screen draws with (as for the
		// pictures of later versions).
		PoseStack poseStack = new PoseStack();
		MultiBufferSource.BufferSource buffers = graphics.bufferSource();
		for (PreviewBatch.Entry entry : batch.entries()) {
			poseStack.pushPose();
			// The picture's pixels (from its top left corner, Y down, Z towards the viewer), as the faces kept for
			// clicking are measured.
			poseStack.translate(batch.x0(), batch.y0(), DEPTH);
			poseStack.scale(1 / guiScale, 1 / guiScale, 1 / guiScale);
			Matrix4f picture = new Matrix4f(poseStack.last().pose());
			poseStack.translate(entry.x() * guiScale, entry.y() * guiScale, 0);
			float size = entry.size() * guiScale;
			poseStack.scale(size, size, -size);
			poseStack.mulPose(entry.rotation());
			drawPlayer(entry.player(), poseStack, buffers);
			graphics.flush();
			PlayerFrame frame = entry.player().frame();
			if (frame != null && frame.pick != null) {
				frame.pick.transform(picture.invert());
			}
			poseStack.popPose();
		}
		Lighting.setupFor3DItems();
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
		graphics.disableScissor();
	}

	/** A menu player as the player renderer draws one (turned, scaled, the model, the cape, the cosmetics). */
	private static void drawPlayer(PlayerState state, PoseStack poseStack, MultiBufferSource buffers) {
		OrbitSkin skin = state.skin();
		if (skin == null) {
			return;
		}
		int light = PlayerState.menuLight();
		PlayerFrame frame = state.frame();
		PlayerModel<?> model = VanillaPlayerModels.model(skin.slim());
		PlayerBody body = PlayerBody.of(model);
		if (frame != null) {
			frame.base = new Matrix4f(poseStack.last().pose());
		}
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.bodyRot()));
		if (frame != null && frame.emote != null) {
			EmotePoser.applyRoot(frame.emote, poseStack);
		}
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		poseStack.scale(0.9375F, 0.9375F, 0.9375F);
		poseStack.translate(0.0F, -1.501F, 0.0F);
		pose(model, state);
		if (frame != null) {
			if (frame.emote != null) {
				EmotePoser.apply(body, frame.emote);
			}
			EmotePoser.hide(body, frame.hiddenParts());
		}
		model.renderToBuffer(poseStack, buffers.getBuffer(model.renderType(skin.texture())), light, OverlayTexture.NO_OVERLAY, -1);
		ResourceLocation cape = skin.cape();
		if (cape != null && state.shows(PlayerModelPart.CAPE)) {
			poseStack.pushPose();
			poseStack.translate(0.0F, 0.0F, 0.125F);
			if (frame != null && frame.emote != null) {
				model.body.translateAndRotate(poseStack);
			}
			poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
			poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
			model.renderCloak(poseStack, buffers.getBuffer(RenderType.entitySolid(cape)), light, OverlayTexture.NO_OVERLAY);
			poseStack.popPose();
		}
		if (frame != null) {
			CosmeticDrawing.draw(state, frame, body, poseStack, OrbitCanvas.of(buffers), light);
		}
		poseStack.popPose();
	}

	/**
	 * Poses the model as HumanoidModel and PlayerModel pose a player standing on the ground with empty hands (they
	 * need a player entity for it): the walk cycle, the head, the arms' idle sway; the skin layers follow.
	 */
	private static void pose(PlayerModel<?> model, PlayerState state) {
		float walkPos = state.motion().distanceMoved();
		float walkSpeed = state.motion().moveSpeed();
		ModelPart[] parts = {model.head, model.hat, model.body, model.jacket, model.rightArm, model.rightSleeve, model.leftArm, model.leftSleeve, model.rightLeg,
			model.rightPants, model.leftLeg, model.leftPants};
		// Models start out as a baby's (the renderer sets it for each creature).
		model.young = false;
		model.riding = false;
		model.attackTime = 0;
		model.crouching = false;
		for (ModelPart part : parts) {
			part.resetPose();
			part.visible = true;
		}
		model.head.yRot = state.motion().headYRotation() * Mth.DEG_TO_RAD;
		model.head.xRot = state.motion().headXRotation() * Mth.DEG_TO_RAD;
		model.rightArm.xRot = Mth.cos(walkPos * 0.6662F + Mth.PI) * walkSpeed;
		model.leftArm.xRot = Mth.cos(walkPos * 0.6662F) * walkSpeed;
		model.rightLeg.xRot = Mth.cos(walkPos * 0.6662F) * 1.4F * walkSpeed;
		model.leftLeg.xRot = Mth.cos(walkPos * 0.6662F + Mth.PI) * 1.4F * walkSpeed;
		model.rightLeg.yRot = 0.005F;
		model.leftLeg.yRot = -0.005F;
		model.rightLeg.zRot = 0.005F;
		model.leftLeg.zRot = -0.005F;
		AnimationUtils.bobArms(model.rightArm, model.leftArm, state.ageInTicks());
		model.hat.copyFrom(model.head);
		model.jacket.copyFrom(model.body);
		model.rightSleeve.copyFrom(model.rightArm);
		model.leftSleeve.copyFrom(model.leftArm);
		model.rightPants.copyFrom(model.rightLeg);
		model.leftPants.copyFrom(model.leftLeg);
		model.hat.visible = state.shows(PlayerModelPart.HAT);
		model.jacket.visible = state.shows(PlayerModelPart.JACKET);
		model.rightSleeve.visible = state.shows(PlayerModelPart.RIGHT_SLEEVE);
		model.leftSleeve.visible = state.shows(PlayerModelPart.LEFT_SLEEVE);
		model.rightPants.visible = state.shows(PlayerModelPart.RIGHT_PANTS_LEG);
		model.leftPants.visible = state.shows(PlayerModelPart.LEFT_PANTS_LEG);
	}
}
