package dev.orbit.cosmetic.client.gui.preview;

import dev.orbit.cosmetic.client.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Several players drawn into one picture of the GUI. The game draws one picture per kind of state per frame, so a
 * menu showing many players (the catalog grid) draws them all in one batch. Each kind ({@link Grid}, {@link Panel},
 * {@link Extra}) has its own renderer, so a screen can show up to three batches at once.
 */
public sealed interface PreviewBatch extends PictureInPictureRenderState permits PreviewBatch.Grid, PreviewBatch.Panel, PreviewBatch.Extra {
	/**
	 * One player in the batch.
	 *
	 * @param x where its feet are, in GUI pixels from the batch's left edge
	 * @param y where its feet are, in GUI pixels from the batch's top edge
	 * @param size GUI pixels per block
	 * @param rotation how the model is turned (vanilla inventory style: upside-down flip and tilt)
	 * @param camera the camera angle used for billboards, or null
	 */
	record Entry(EntityRenderState state, float x, float y, float size, Quaternionf rotation, @Nullable Quaternionf camera) {
	}

	List<Entry> entries();

	@Override
	default float scale() {
		return 1.0F;
	}

	record Grid(int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds, List<Entry> entries) implements PreviewBatch {
	}

	record Panel(int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds, List<Entry> entries) implements PreviewBatch {
	}

	record Extra(int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds, List<Entry> entries) implements PreviewBatch {
	}

	/** Adds a third batch (for a screen that already shows a grid and a panel). */
	static void extra(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, List<Entry> entries) {
		if (!entries.isEmpty() && x1 > x0 && y1 > y0) {
			ScreenRectangle area = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
			submit(graphics, new Extra(x0, y0, x1, y1, area, area, List.copyOf(entries)));
		}
	}

	/** Adds a grid batch covering the rectangle, clipped to it. */
	static void grid(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, List<Entry> entries) {
		if (!entries.isEmpty() && x1 > x0 && y1 > y0) {
			ScreenRectangle area = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
			submit(graphics, new Grid(x0, y0, x1, y1, area, area, List.copyOf(entries)));
		}
	}

	/** Adds a panel batch covering the rectangle, clipped to it. */
	static void panel(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, List<Entry> entries) {
		if (!entries.isEmpty() && x1 > x0 && y1 > y0) {
			ScreenRectangle area = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
			submit(graphics, new Panel(x0, y0, x1, y1, area, area, List.copyOf(entries)));
		}
	}

	private static void submit(GuiGraphicsExtractor graphics, PreviewBatch batch) {
		((GuiGraphicsExtractorAccessor) graphics).orbit$guiRenderState().addPicturesInPictureState(batch);
	}
}
