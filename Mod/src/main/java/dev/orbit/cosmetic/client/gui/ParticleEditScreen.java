package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits one particle emitter, with a player on the left wearing the cosmetic (or playing the emote) so the
 * particles show as they change; drag it to turn it. The Look tab has which texture it draws (any texture of the
 * model; a {@code _t1} texture follows the cosmetic's alternate textures), whether it glows, and how its particles
 * look, live and spawn; the Motion tab where they appear and how they move: speed and turning on X, Y and Z, and how
 * those change every second.
 */
final class ParticleEditScreen extends OrbitScreen {
	private static final int TOP = 42;
	private static final int ROW_HEIGHT = 23;
	private static final int LABEL_WIDTH = 74;
	private static final int MAX_SETTINGS_WIDTH = 380;

	/** Indexes into {@link ParticleSettings#values()}. */
	private static final int SPIN_MIN = 0;
	private static final int SPIN_MAX = 1;
	private static final int SPEED = 2;
	private static final int TURN = 5;
	private static final int SPEED_CHANGE = 8;
	private static final int TURN_CHANGE = 11;
	private static final int SPAWN_MIN = 14;
	private static final int SPAWN_MAX = 15;
	private static final int SIZE_START = 16;
	private static final int SIZE_END = 17;
	private static final int LIFETIME = 18;
	private static final int FADE_OUT = 19;
	private static final int OPACITY = 20;
	private static final int OFFSET = 21;
	private static final int ROTATION = 24;

	/** The Motion tab's rows: first value, the slider's range, its step, and its name. */
	private record AxisRow(int value, double range, double step, String key) {
	}

	private static final AxisRow[] MOTION_ROWS = {
		new AxisRow(OFFSET, 32, 0.25, "offset"),
		new AxisRow(SPEED, 10, 0.05, "speed"),
		new AxisRow(SPEED_CHANGE, 10, 0.05, "speed_change"),
		new AxisRow(TURN, 720, 5, "turn"),
		new AxisRow(TURN_CHANGE, 720, 5, "turn_change"),
	};

	private final ParticleTarget target;
	private final int bone;
	private final PreviewModel preview = new PreviewModel();
	private boolean motionTab;
	private float previewYaw = 25;
	private boolean draggingPreview;

	ParticleEditScreen(Screen parent, ParticleTarget target, int bone) {
		super(Component.translatable("orbit_cosmetic.particles.part_title", target.model().bones().get(bone).name()), parent);
		this.target = target;
		this.bone = bone;
	}

	private ParticleSettings current() {
		ParticleSettings current = target.get(bone);
		return current != null ? current : ParticleSettings.defaults(bone, -1);
	}

	private void set(int value, double to) {
		target.set(current().with(value, (float) to));
	}

	// Layout: the preview on the left, the settings on the right.

	private int previewWidth() {
		return Math.clamp(width / 4, 80, 150);
	}

	private int previewBottom() {
		return height - 34;
	}

	private int settingsLeft() {
		int free = width - 10 - previewWidth() - 8 - 10;
		return 10 + previewWidth() + 8 + Math.max(0, (free - settingsWidth()) / 2);
	}

	private int settingsWidth() {
		return Math.min(MAX_SETTINGS_WIDTH, width - 10 - previewWidth() - 8 - 10);
	}

	@Override
	protected void init() {
		int left = settingsLeft();
		int rowWidth = settingsWidth();

		int tabWidth = Math.min(90, (rowWidth - 4) / 2);
		int tabsLeft = left + (rowWidth - tabWidth * 2 - 4) / 2;
		String[] tabs = {"look", "motion"};
		for (int i = 0; i < tabs.length; i++) {
			boolean motion = i == 1;
			FlatButton tab = addRenderableWidget(new FlatButton(tabsLeft + i * (tabWidth + 4), 20, tabWidth, 16,
				Component.translatable("orbit_cosmetic.particles.tab." + tabs[i]), null, FlatButton.Style.GHOST, b -> {
					motionTab = motion;
					rebuildWidgets();
				}));
			tab.setSelected(motionTab == motion);
		}

		if (motionTab) {
			initMotion(left, rowWidth);
		} else {
			initLook(left, rowWidth);
		}

		int footerY = height - 28;
		addRenderableWidget(new FlatButton(width / 2 - 154, footerY, 150, 20, Component.translatable("orbit_cosmetic.animations.reset"), null, FlatButton.Style.SECONDARY, b -> {
			target.set(ParticleSettings.defaults(bone, Math.max(-1, current().texture())).withEnabled(current().enabled()));
			rebuildWidgets();
		}));
		addRenderableWidget(new FlatButton(width / 2 + 4, footerY, 150, 20, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	/** Texture, on, glow; opacity and life; sizes and rotation; spin; how often they appear. */
	private void initLook(int left, int rowWidth) {
		ParticleSettings settings = current();
		float[] values = settings.values();
		int column = (rowWidth - 8) / 3;
		int y = TOP;
		List<Integer> textures = new ArrayList<>();
		for (int i = 0; i < target.model().textures().size(); i++) {
			textures.add(i);
		}
		if (!textures.isEmpty()) {
			addRenderableWidget(FlatCycle.of(left, y, column, 20, Component.translatable("orbit_cosmetic.particles.texture"), textures,
				Math.max(0, settings.texture()), i -> Component.literal(ParticlesScreen.textureName(target, i)),
				i -> Component.translatable("orbit_cosmetic.particles.texture.tooltip"), i -> target.set(current().withTexture(i))));
		}
		addRenderableWidget(FlatCycle.onOff(left + (column + 4), y, column, 20, Component.translatable("orbit_cosmetic.particles.enabled"), settings.enabled(),
			Component.translatable("orbit_cosmetic.particles.enabled.tooltip"), value -> target.set(current().withEnabled(value))));
		addRenderableWidget(FlatCycle.onOff(left + (column + 4) * 2, y, column, 20, Component.translatable("orbit_cosmetic.particles.glow"), settings.glow(),
			Component.translatable("orbit_cosmetic.particles.glow.tooltip"), value -> target.set(current().withGlow(value))));

		y += ROW_HEIGHT;
		addRenderableWidget(new ValueSlider(left, y, column, 0, 1, 0.05, values[OPACITY],
			value -> Component.translatable("orbit_cosmetic.particles.opacity", Math.round(value * 100)), value -> set(OPACITY, value)));
		addRenderableWidget(slider(left + (column + 4), y, column, LIFETIME, 0.1, 10, 0.1, values, "lifetime"));
		addRenderableWidget(slider(left + (column + 4) * 2, y, column, FADE_OUT, 0, 10, 0.1, values, "fade_out"));

		y += ROW_HEIGHT;
		addRenderableWidget(slider(left, y, column, SIZE_START, 0, 16, 0.25, values, "size_start"));
		addRenderableWidget(slider(left + (column + 4), y, column, SIZE_END, 0, 16, 0.25, values, "size_end"));
		addRenderableWidget(slider(left + (column + 4) * 2, y, column, ROTATION, 0, ParticleSettings.MAX_ROTATION, 5, values, "rotation"));

		y += ROW_HEIGHT;
		addRenderableWidget(slider(left, y, column, SPIN_MIN, -720, 720, 5, values, "spin_min"));
		addRenderableWidget(slider(left + (column + 4), y, column, SPIN_MAX, -720, 720, 5, values, "spin_max"));

		y += ROW_HEIGHT;
		addRenderableWidget(slider(left, y, column, SPAWN_MIN, 1, 200, 1, values, "spawn_min"));
		addRenderableWidget(slider(left + (column + 4), y, column, SPAWN_MAX, 1, 200, 1, values, "spawn_max"));
	}

	/** One row per kind, X, Y and Z side by side after the row's name. */
	private void initMotion(int left, int rowWidth) {
		float[] values = current().values();
		int axisColumn = (rowWidth - LABEL_WIDTH - 8) / 3;
		int y = TOP;
		for (AxisRow row : MOTION_ROWS) {
			boolean offset = row.value == OFFSET;
			for (int axis = 0; axis < 3; axis++) {
				int value = row.value + axis;
				String axisName = "XYZ".substring(axis, axis + 1);
				ValueSlider slider = new ValueSlider(left + LABEL_WIDTH + axis * (axisColumn + 4), y, axisColumn, offset ? 0 : -row.range, row.range, row.step,
					values[value], v -> Component.literal(axisName + ": " + (offset ? "±" + number(v) + "px" : number(v))), v -> set(value, v));
				if (offset) {
					slider.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.particles.offset.tooltip")));
				}
				addRenderableWidget(slider);
			}
			y += ROW_HEIGHT;
		}
	}

	private ValueSlider slider(int x, int y, int width, int value, double min, double max, double step, float[] values, String key) {
		ValueSlider slider = new ValueSlider(x, y, width, min, max, step, values[value],
			v -> Component.translatable("orbit_cosmetic.particles." + key, number(v)), v -> set(value, v));
		if (key.equals("rotation")) {
			slider.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.particles.rotation.tooltip")));
		} else if (key.startsWith("spin")) {
			slider.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.particles.spin.tooltip")));
		} else if (key.startsWith("size")) {
			slider.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.particles.size.tooltip")));
		}
		return slider;
	}

	/** A slider value without trailing zeros: 2, 0.5, 0.25. */
	private static String number(double value) {
		String text = String.format(Locale.ROOT, "%.2f", value);
		text = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
		return text.equals("-0") ? "0" : text;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (Theme.inside(event.x(), event.y(), 10, TOP, previewWidth(), previewBottom() - TOP)) {
			draggingPreview = true;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingPreview = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingPreview) {
			previewYaw = (previewYaw - (float) dx * 2.5F) % 360;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 6, Theme.TEXT);

		int previewX = 10;
		int previewW = previewWidth();
		int previewH = previewBottom() - TOP;
		Theme.panel(graphics, previewX, TOP, previewW, previewH);
		target.preview(preview);
		// Room above the player for particles rising from it.
		int headroom = previewH / 4;
		PreviewBatch.panel(graphics, previewX + 1, TOP + 1, previewX + previewW - 1, TOP + previewH - 1,
			List.of(preview.entryIn(4, headroom, previewW - 10, previewH - headroom - 8, previewYaw, 0, partialTick)));

		int left = settingsLeft();
		int rowWidth = settingsWidth();
		int y = TOP;
		if (motionTab) {
			for (AxisRow row : MOTION_ROWS) {
				graphics.text(font, Theme.clip(font, Component.translatable("orbit_cosmetic.particles.row." + row.key), LABEL_WIDTH - 4), left, y + 6, Theme.TEXT);
				y += ROW_HEIGHT;
			}
		} else {
			y += 5 * ROW_HEIGHT;
		}
		y += 2;
		// Only as many help lines as fit above the footer buttons.
		Component help = Component.translatable(motionTab ? "orbit_cosmetic.particles.help" : "orbit_cosmetic.particles.look_help").withStyle(ChatFormatting.GRAY);
		for (FormattedCharSequence line : font.split(help, rowWidth)) {
			if (y + 9 > height - 32) {
				break;
			}
			graphics.text(font, line, left, y, Theme.TEXT);
			y += 10;
		}
	}
}
