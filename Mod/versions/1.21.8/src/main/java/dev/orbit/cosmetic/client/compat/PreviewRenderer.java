package dev.orbit.cosmetic.client.compat;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.joml.Quaternionf;

/** Draws a {@link PreviewBatch}: every player of the batch into one picture. */
public final class PreviewRenderer<T extends PreviewBatch> extends PictureInPictureRenderer<T> {
	/** Full brightness, so previews look the same everywhere. */
	private static final int FULL_BRIGHT = 0xF000F0;
	private final Class<T> type;

	private PreviewRenderer(MultiBufferSource.BufferSource bufferSource, Class<T> type) {
		super(bufferSource);
		this.type = type;
	}

	public static void register() {
		SpecialGuiElementRegistry.register(context -> new PreviewRenderer<>(context.vertexConsumers(), PreviewBatch.Grid.class));
		SpecialGuiElementRegistry.register(context -> new PreviewRenderer<>(context.vertexConsumers(), PreviewBatch.Panel.class));
		SpecialGuiElementRegistry.register(context -> new PreviewRenderer<>(context.vertexConsumers(), PreviewBatch.Extra.class));
	}

	@Override
	public Class<T> getRenderStateClass() {
		return type;
	}

	@Override
	protected void renderToTexture(T batch, PoseStack poseStack) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
		EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
		float halfWidth = (batch.x1() - batch.x0()) / 2.0F;
		float halfHeight = (batch.y1() - batch.y0()) / 2.0F;
		// As the game draws an entity into a picture in 1.21.6 to 1.21.8: at full brightness, without its shadow, the
		// camera turned for billboards.
		dispatcher.setRenderShadow(false);
		for (PreviewBatch.Entry entry : batch.entries()) {
			poseStack.pushPose();
			poseStack.translate(entry.x() - halfWidth, entry.y() - halfHeight, 0.0F);
			poseStack.scale(entry.size(), entry.size(), entry.size());
			poseStack.mulPose(entry.rotation());
			if (entry.camera() != null) {
				dispatcher.overrideCameraOrientation(entry.camera().conjugate(new Quaternionf()).rotateY((float) Math.PI));
			}
			dispatcher.render(entry.player().raw(), 0.0, 0.0, 0.0, poseStack, bufferSource, FULL_BRIGHT);
			bufferSource.endBatch();
			poseStack.popPose();
		}
		dispatcher.setRenderShadow(true);
	}

	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2.0F;
	}

	@Override
	protected String getTextureLabel() {
		return "orbit_preview";
	}
}
