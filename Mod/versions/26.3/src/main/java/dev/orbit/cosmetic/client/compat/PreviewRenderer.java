package dev.orbit.cosmetic.client.compat;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Quaternionf;

/** Draws a {@link PreviewBatch}: every player of the batch into one picture. */
public final class PreviewRenderer<T extends PreviewBatch> extends PictureInPictureRenderer<T> {
	private final Class<T> type;

	private PreviewRenderer(Class<T> type) {
		this.type = type;
	}

	public static void register() {
		PictureInPictureRendererRegistry.register(context -> new PreviewRenderer<>(PreviewBatch.Grid.class));
		PictureInPictureRendererRegistry.register(context -> new PreviewRenderer<>(PreviewBatch.Panel.class));
		PictureInPictureRendererRegistry.register(context -> new PreviewRenderer<>(PreviewBatch.Extra.class));
	}

	@Override
	public Class<T> getRenderStateClass() {
		return type;
	}

	@Override
	protected void renderToTexture(T batch, PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
		float halfWidth = (batch.x1() - batch.x0()) / 2.0F;
		float halfHeight = (batch.y1() - batch.y0()) / 2.0F;
		for (PreviewBatch.Entry entry : batch.entries()) {
			poseStack.pushPose();
			poseStack.translate(entry.x() - halfWidth, entry.y() - halfHeight, 0.0F);
			poseStack.scale(entry.size(), entry.size(), entry.size());
			poseStack.rotate(entry.rotation());
			CameraRenderState camera = new CameraRenderState();
			if (entry.camera() != null) {
				camera.orientation = entry.camera().conjugate(new Quaternionf()).rotateY((float) Math.PI);
			}
			minecraft.getEntityRenderDispatcher().submit(entry.state(), camera, 0.0, 0.0, 0.0, poseStack, collector);
			poseStack.popPose();
		}
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
