package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The player of the Velocity screens: it wears the cosmetic and moves at the speed set with the sliders under it,
 * so its movement physics react; the grid behind it scrolls the other way, a block per square, to show how fast
 * it goes. Drag it to turn it. The speeds are kept while the game runs, from one screen to the next.
 */
final class MotionPreview {
	private static final int SLIDER_HEIGHT = 22;
	private static final int SLIDERS = 3;
	private static final int LINE = 0x24FFFFFF;

	/** Blocks per second: forward (negative is backward), to the right, up. */
	private static final double[] speeds = new double[SLIDERS];
	private static final String[] KEYS = {"forward", "sideways", "up"};
	private static final double[] RANGES = {12, 8, 12};

	private final LocalCosmetic cosmetic;
	private final PreviewModel model = new PreviewModel();
	private float yaw = 60;
	private boolean dragging;
	/** How far the grid has scrolled (GUI pixels), and when it last did (menu time, ticks). */
	private double scrollX;
	private double scrollY;
	private double lastTime = Double.NaN;
	private int x;
	private int y;
	private int width;
	private int height;

	MotionPreview(LocalCosmetic cosmetic) {
		this.cosmetic = cosmetic;
	}

	/** Width of the column for a screen this wide. */
	static int widthFor(int screenWidth) {
		return Math.clamp(screenWidth / 4, 90, 150);
	}

	/** Places the column (the player, then the sliders under it) and returns the sliders. */
	List<ValueSlider> layout(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height - SLIDERS * SLIDER_HEIGHT;
		ValueSlider[] sliders = new ValueSlider[SLIDERS];
		for (int i = 0; i < SLIDERS; i++) {
			int axis = i;
			sliders[i] = new ValueSlider(x, y + this.height + 2 + i * SLIDER_HEIGHT, width, -RANGES[i], RANGES[i], 0.1, speeds[i],
				value -> Component.translatable("orbit_cosmetic.physics.preview." + KEYS[axis], String.format(Locale.ROOT, "%.1f", value)),
				value -> speeds[axis] = value);
			sliders[i].setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.physics.preview.tooltip")));
		}
		return List.of(sliders);
	}

	void extract(GuiGraphicsExtractor graphics, float partialTick) {
		Theme.panel(graphics, x, y, width, height);
		model.setWorn(cosmetic.isUsable() ? List.of(cosmetic.toEquipped()) : List.of());
		model.setMoving(speeds[0], speeds[1], speeds[2]);
		PreviewBatch.Entry entry = model.entryIn(6, 6, width - 12, height - 12, yaw, 0, partialTick, true);

		// The ground goes by the other way: the player's movement as seen on screen, in blocks of entry.size() pixels.
		double time = OrbitClient.get().menuTime();
		double seconds = Double.isNaN(lastTime) ? 0 : Math.clamp((time - lastTime) / 20, 0, 0.1);
		lastTime = time;
		double turn = Math.toRadians(yaw);
		double screenX = -speeds[0] * Math.sin(turn) - speeds[1] * Math.cos(turn);
		scrollX = (scrollX - screenX * entry.size() * seconds) % entry.size();
		scrollY = (scrollY + speeds[2] * entry.size() * seconds) % entry.size();
		grid(graphics, entry.size());

		PreviewBatch.panel(graphics, x + 1, y + 1, x + width - 1, y + height - 1, List.of(entry));
	}

	/** Lines a block apart, shifted by the scroll, inside the box. */
	private void grid(GuiGraphicsExtractor graphics, float block) {
		if (block < 4) {
			return;
		}
		int x0 = x + 1;
		int y0 = y + 1;
		int x1 = x + width - 1;
		int y1 = y + height - 1;
		for (double lineX = x0 + Math.floorMod((long) Math.floor(scrollX), (long) Math.max(1, Math.round(block))); lineX < x1; lineX += block) {
			graphics.fill((int) lineX, y0, (int) lineX + 1, y1, LINE);
		}
		for (double lineY = y0 + Math.floorMod((long) Math.floor(scrollY), (long) Math.max(1, Math.round(block))); lineY < y1; lineY += block) {
			graphics.fill(x0, (int) lineY, x1, (int) lineY + 1, LINE);
		}
	}

	boolean mouseClicked(double mouseX, double mouseY) {
		dragging = Theme.inside(mouseX, mouseY, x, y, width, height);
		return dragging;
	}

	boolean mouseDragged(double dx) {
		if (dragging) {
			yaw = (yaw - (float) dx * 2.5F) % 360;
		}
		return dragging;
	}

	void mouseReleased() {
		dragging = false;
	}
}
