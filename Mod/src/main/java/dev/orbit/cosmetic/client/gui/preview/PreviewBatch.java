package dev.orbit.cosmetic.client.gui.preview;

import dev.orbit.cosmetic.client.gfx.PlayerState;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
//? if >=1.21.6 {
import dev.orbit.cosmetic.client.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
//?} else {
/*import dev.orbit.cosmetic.client.compat.PreviewRenderer;
*///?}
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Several players drawn into one picture of the GUI. The game draws one picture per kind of state per frame, so a
 * menu showing many players (the catalog grid) draws them all in one batch. Each kind ({@link Grid}, {@link Panel},
 * {@link Extra}) has its own renderer, so a screen can show up to three batches at once. Before 1.21.6 the GUI has
 * no pictures: a batch is drawn straight away (see compat/PreviewRenderer).
 */
//? if >=1.21.6 {
public sealed interface PreviewBatch extends PictureInPictureRenderState permits PreviewBatch.Grid, PreviewBatch.Panel, PreviewBatch.Extra {
//?} else {
/*public sealed interface PreviewBatch permits PreviewBatch.Grid, PreviewBatch.Panel, PreviewBatch.Extra {
	int x0();

	int y0();

	int x1();

	int y1();
*///?}
	/**
	 * One player in the batch.
	 *
	 * @param player the player drawn, set up for this frame
	 * @param x where its feet are, in GUI pixels from the batch's left edge
	 * @param y where its feet are, in GUI pixels from the batch's top edge
	 * @param size GUI pixels per block
	 * @param rotation how the model is turned (vanilla inventory style: upside-down flip and tilt)
	 * @param camera the camera angle used for billboards, or null
	 */
	record Entry(PlayerState player, float x, float y, float size, Quaternionf rotation, @Nullable Quaternionf camera) {
	}

	List<Entry> entries();

	//? if >=1.21.6 {
	@Override
	default float scale() {
		return 1.0F;
	}
	//?}

	record Grid(int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds, List<Entry> entries) implements PreviewBatch {
	}

	record Panel(int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds, List<Entry> entries) implements PreviewBatch {
	}

	record Extra(int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds, List<Entry> entries) implements PreviewBatch {
	}

	/** Adds a third batch (for a screen that already shows a grid and a panel). */
	static void extra(OrbitGraphics graphics, int x0, int y0, int x1, int y1, List<Entry> entries) {
		if (!entries.isEmpty() && x1 > x0 && y1 > y0) {
			ScreenRectangle area = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
			submit(graphics, new Extra(x0, y0, x1, y1, area, area, List.copyOf(entries)));
		}
	}

	/** Adds a grid batch covering the rectangle, clipped to it. */
	static void grid(OrbitGraphics graphics, int x0, int y0, int x1, int y1, List<Entry> entries) {
		if (!entries.isEmpty() && x1 > x0 && y1 > y0) {
			ScreenRectangle area = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
			submit(graphics, new Grid(x0, y0, x1, y1, area, area, List.copyOf(entries)));
		}
	}

	/** Adds a panel batch covering the rectangle, clipped to it. */
	static void panel(OrbitGraphics graphics, int x0, int y0, int x1, int y1, List<Entry> entries) {
		if (!entries.isEmpty() && x1 > x0 && y1 > y0) {
			ScreenRectangle area = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
			submit(graphics, new Panel(x0, y0, x1, y1, area, area, List.copyOf(entries)));
		}
	}

	private static void submit(OrbitGraphics graphics, PreviewBatch batch) {
		//? if >=1.21.6 {
		((GuiGraphicsExtractorAccessor) graphics.raw()).orbit$guiRenderState().addPicturesInPictureState(batch);
		//?} else {
		/*PreviewRenderer.draw(graphics.raw(), batch);
		*///?}
	}
}
