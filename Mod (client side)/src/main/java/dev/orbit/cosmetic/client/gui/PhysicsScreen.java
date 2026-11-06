package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.protocol.EquipCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Velocity tab of a cosmetic's settings: the parts that react to the wearer's movement, next to a player
 * wearing the cosmetic that moves at the speed set under it.
 */
public final class PhysicsScreen extends OrbitScreen {
	private static final int ROW_HEIGHT = 24;
	private static final int TOP = SettingsTabs.HEIGHT + 44;

	private final LocalCosmetic cosmetic;
	private final MotionPreview preview;
	private int page;

	public PhysicsScreen(Screen parent, LocalCosmetic cosmetic) {
		super(Component.translatable("orbit_cosmetic.physics.title", cosmetic.displayName()), parent);
		this.cosmetic = cosmetic;
		this.preview = new MotionPreview(cosmetic);
	}

	// Layout: the player on the left, the parts on the right.

	private int rowWidth() {
		return Math.min(420, width - 10 - MotionPreview.widthFor(width) - 8 - 10);
	}

	private int left() {
		int free = width - 10 - MotionPreview.widthFor(width) - 8 - 10;
		return 10 + MotionPreview.widthFor(width) + 8 + Math.max(0, (free - rowWidth()) / 2);
	}

	private int rowsPerPage() {
		return Math.max(1, (height - TOP - 40) / ROW_HEIGHT);
	}

	private List<PhysicsLink> links() {
		return cosmetic.physicsLinks();
	}

	@Override
	protected void init() {
		SettingsTabs.buttons(width, 1, parent, cosmetic).forEach(this::addRenderableWidget);
		preview.layout(10, SettingsTabs.HEIGHT + 4, MotionPreview.widthFor(width), height - 34 - SettingsTabs.HEIGHT - 4).forEach(this::addRenderableWidget);
		List<PhysicsLink> links = links();
		int perPage = rowsPerPage();
		int pages = Math.max(1, (links.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int rowWidth = rowWidth();
		int left = left();
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= links.size()) {
				break;
			}
			int bone = links.get(index).bone();
			int y = TOP + row * ROW_HEIGHT;
			addRenderableWidget(new FlatButton(left + rowWidth - 124, y, 60, 20, Component.translatable("orbit_cosmetic.physics.edit"), null, FlatButton.Style.SECONDARY, b -> Compat.setScreen(new PhysicsPartScreen(this, cosmetic, bone))));
			addRenderableWidget(new FlatButton(left + rowWidth - 60, y, 60, 20, Component.translatable("orbit_cosmetic.physics.remove"), null, FlatButton.Style.SECONDARY, b -> {
				OrbitClient.get().library().removePhysics(cosmetic, bone);
				rebuildWidgets();
			}));
		}

		int footerY = height - 28;
		if (pages > 1) {
			addRenderableWidget(new FlatButton(left, footerY, 20, 20, Component.literal("<"), null, FlatButton.Style.SECONDARY, b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			})).active = page > 0;
			addRenderableWidget(new FlatButton(left + 24, footerY, 20, 20, Component.literal(">"), null, FlatButton.Style.SECONDARY, b -> {
				page = Math.min(pages - 1, page + 1);
				rebuildWidgets();
			})).active = page < pages - 1;
		}
		FlatButton add = addRenderableWidget(new FlatButton(width / 2 - 154, footerY, 150, 20, Component.translatable("orbit_cosmetic.physics.add"), "plus", FlatButton.Style.PRIMARY, b ->
			Compat.setScreen(new PartTreeScreen(this, cosmetic.model(), bone -> cosmetic.physicsFor(bone) != null, bone -> Compat.setScreen(new PhysicsPartScreen(this, cosmetic, bone))))));
		add.active = links.size() < EquipCodec.MAX_PHYSICS_LINKS;
		addRenderableWidget(new FlatButton(width / 2 + 4, footerY, 150, 20, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
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
		int rowWidth = rowWidth();
		int left = left();
		int center = left + rowWidth / 2;
		graphics.centeredText(font, Theme.clip(font, title, rowWidth), center, SettingsTabs.HEIGHT + 8, Theme.TEXT);
		graphics.centeredText(font, Theme.clip(font, Component.translatable("orbit_cosmetic.physics.subtitle", EquipCodec.MAX_PHYSICS_LINKS).withStyle(ChatFormatting.GRAY), rowWidth),
			center, SettingsTabs.HEIGHT + 22, Theme.TEXT);
		List<PhysicsLink> links = links();
		if (links.isEmpty()) {
			int y = height / 2 - 10;
			for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.physics.empty").withStyle(ChatFormatting.GRAY), rowWidth)) {
				graphics.centeredText(font, line, center, y, Theme.TEXT);
				y += 10;
			}
			return;
		}
		int perPage = rowsPerPage();
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= links.size()) {
				break;
			}
			PhysicsLink link = links.get(index);
			int y = TOP + row * ROW_HEIGHT;
			graphics.text(font, Theme.clip(font, Component.literal(PartTreeScreen.path(cosmetic.model(), link.bone())), rowWidth - 128), left, y + 1, Theme.TEXT);
			graphics.text(font, Theme.clip(font, summary(link).withStyle(ChatFormatting.GRAY), rowWidth - 128), left, y + 11, Theme.TEXT);
		}
	}

	/** "Rotation X: Forward, Position Y: Vertical" or "No axes set". */
	static MutableComponent summary(PhysicsLink link) {
		List<Component> parts = new ArrayList<>();
		for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
			AxisDrive drive = link.axes().get(axis);
			if (drive.active()) {
				parts.add(Component.translatable("orbit_cosmetic.physics.axis." + PhysicsLink.AXIS_NAMES[axis])
					.append(": ")
					.append(Component.translatable("orbit_cosmetic.physics.source." + drive.source().name().toLowerCase(Locale.ROOT))));
			}
		}
		if (parts.isEmpty()) {
			return Component.translatable("orbit_cosmetic.physics.no_axes");
		}
		MutableComponent result = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			result.append(i == 0 ? Component.empty() : Component.literal(", ")).append(parts.get(i));
		}
		return result;
	}
}
