package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits how one part reacts to movement: for each of position X/Y/Z and rotation X/Y/Z (Blockbench axes),
 * what drives it, how strongly, between which values, and how much it resists (smooths) changes. A player on the
 * left wears the cosmetic and moves at the speed set under it, so the part reacts as it is edited.
 */
public final class PhysicsPartScreen extends OrbitScreen {
	private static final int TOP = 48;
	private static final int ROW_HEIGHT = 24;
	private static final int MAX_ROW_WIDTH = 480;
	private static final int COLUMNS = 5;

	private final LocalCosmetic cosmetic;
	private final int bone;
	private final MotionPreview preview;

	public PhysicsPartScreen(Screen parent, LocalCosmetic cosmetic, int bone) {
		super(Component.translatable("orbit_cosmetic.physics.part_title", PartTreeScreen.path(cosmetic.model(), bone)), parent);
		this.cosmetic = cosmetic;
		this.bone = bone;
		this.preview = new MotionPreview(cosmetic);
	}

	private List<AxisDrive> axes() {
		// PhysicsLink fills missing axes with OFF and clamps values.
		return new PhysicsLink(bone, cosmetic.physicsFor(bone)).axes();
	}

	// Layout: the player on the left, the axes on the right.

	private int left() {
		int free = width - 10 - MotionPreview.widthFor(width) - 8 - 10;
		return 10 + MotionPreview.widthFor(width) + 8 + Math.max(0, (free - rowWidth()) / 2);
	}

	private int rowWidth() {
		return Math.min(MAX_ROW_WIDTH, width - 10 - MotionPreview.widthFor(width) - 8 - 10);
	}

	private int labelWidth() {
		return Math.min(66, rowWidth() / 6);
	}

	private int column() {
		return (rowWidth() - labelWidth() - 4 * (COLUMNS - 1)) / COLUMNS;
	}

	@Override
	protected void init() {
		preview.layout(10, 24, MotionPreview.widthFor(width), height - 34 - 24).forEach(this::addRenderableWidget);
		List<AxisDrive> axes = axes();
		if (cosmetic.physicsFor(bone) == null) {
			// Opening a part creates its entry so it shows up in the list even before any axis is set.
			OrbitClient.get().library().setPhysics(cosmetic, bone, axes);
		}
		int column = column();
		for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
			int a = axis;
			boolean rotation = axis >= 3;
			String unit = rotation ? "°" : "px";
			AxisDrive drive = axes.get(axis);
			int y = TOP + axis * ROW_HEIGHT;
			int x = left() + labelWidth();
			addRenderableWidget(FlatCycle.of(x, y, column, 20, Component.empty(), List.of(PhysicsSource.values()), drive.source(),
				source -> Component.translatable("orbit_cosmetic.physics.source." + source.name().toLowerCase(Locale.ROOT)),
				source -> Component.translatable("orbit_cosmetic.physics.source." + source.name().toLowerCase(Locale.ROOT) + ".tooltip"),
				value -> update(a, current(a).withSource(value))));
			float strengthLimit = rotation ? AxisDrive.MAX_ROTATION_STRENGTH : AxisDrive.MAX_POSITION_STRENGTH;
			addRenderableWidget(new ValueSlider(x + column + 4, y, column, -strengthLimit, strengthLimit, rotation ? 0.5 : 0.1, drive.strength(),
				value -> Component.literal(format(value)),
				value -> update(a, current(a).withStrength((float) value))));
			float limit = rotation ? AxisDrive.MAX_ROTATION : AxisDrive.MAX_POSITION;
			double step = rotation ? 1 : 0.25;
			addRenderableWidget(new ValueSlider(x + (column + 4) * 2, y, column, -limit, limit, step, drive.min(),
				value -> Component.literal(format(value) + unit),
				value -> update(a, current(a).withMin((float) value))));
			addRenderableWidget(new ValueSlider(x + (column + 4) * 3, y, column, -limit, limit, step, drive.max(),
				value -> Component.literal(format(value) + unit),
				value -> update(a, current(a).withMax((float) value))));
			addRenderableWidget(new ValueSlider(x + (column + 4) * 4, y, column, 0, AxisDrive.MAX_RESISTANCE, 0.01, drive.resistance(),
				value -> Component.literal(String.format(Locale.ROOT, "%.2f", value)),
				value -> update(a, current(a).withResistance((float) value))));
		}
		int footerY = height - 28;
		addRenderableWidget(new FlatButton(width / 2 - 154, footerY, 150, 20, Component.translatable("orbit_cosmetic.physics.remove_part"), null, FlatButton.Style.SECONDARY, b -> {
			OrbitClient.get().library().removePhysics(cosmetic, bone);
			onClose();
		}));
		addRenderableWidget(new FlatButton(width / 2 + 4, footerY, 150, 20, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	private AxisDrive current(int axis) {
		List<AxisDrive> saved = cosmetic.physicsFor(bone);
		return saved != null && axis < saved.size() ? saved.get(axis) : AxisDrive.OFF;
	}

	private void update(int axis, AxisDrive drive) {
		List<AxisDrive> axes = new ArrayList<>(PhysicsLink.AXES);
		for (int i = 0; i < PhysicsLink.AXES; i++) {
			axes.add(i == axis ? drive.clamped(i >= 3) : current(i));
		}
		OrbitClient.get().library().setPhysics(cosmetic, bone, axes);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, Math.abs(value % 1) < 0.001 ? "%.0f" : "%.2f", value);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(event, doubleClick) || preview.mouseClicked(event.x(), event.y());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		preview.mouseReleased();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return preview.mouseDragged(dx) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		preview.extract(graphics, partialTick);
		graphics.centeredText(font, title, width / 2, 10, Theme.TEXT);
		int rowWidth = rowWidth();
		int left = left();
		int column = column();
		int x = left + labelWidth();
		String[] headers = {"orbit_cosmetic.physics.source", "orbit_cosmetic.physics.header.strength", "orbit_cosmetic.physics.header.min",
			"orbit_cosmetic.physics.header.max", "orbit_cosmetic.physics.header.resistance"};
		for (int i = 0; i < headers.length; i++) {
			FormattedCharSequence header = Theme.clip(font, Component.translatable(headers[i]).withStyle(ChatFormatting.GRAY), column + 2);
			graphics.centeredText(font, header, x + (column + 4) * i + column / 2, TOP - 12, Theme.TEXT);
		}
		for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
			graphics.text(font, Theme.clip(font, Component.translatable("orbit_cosmetic.physics.axis." + PhysicsLink.AXIS_NAMES[axis]), labelWidth() - 3),
				left, TOP + axis * ROW_HEIGHT + 6, Theme.TEXT);
		}
		int y = TOP + PhysicsLink.AXES * ROW_HEIGHT + 6;
		// Only as many help lines as fit above the footer buttons.
		for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.physics.help").withStyle(ChatFormatting.GRAY), rowWidth)) {
			if (y + 9 > height - 32) {
				break;
			}
			graphics.text(font, line, left, y, Theme.TEXT);
			y += 10;
		}
	}
}
