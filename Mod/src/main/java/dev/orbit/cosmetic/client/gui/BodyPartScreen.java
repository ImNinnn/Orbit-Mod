package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.BodyParts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;

/**
 * The Parts tab of a body part's settings: which of its parts replace the player's (each part in the file can be
 * turned off; the last one stays on), next to a player wearing it. Parts that are too big, or not in the file,
 * cannot be used: the player's own part shows there.
 */
public final class BodyPartScreen extends OrbitScreen {
	private static final int ROW_HEIGHT = 24;
	private static final int TOP = SettingsTabs.HEIGHT + 44;

	private final LocalCosmetic cosmetic;
	private final PreviewModel preview = new PreviewModel();
	private float previewYaw = 25;
	private boolean dragging;

	public BodyPartScreen(Screen parent, LocalCosmetic cosmetic) {
		super(Component.translatable("orbit_cosmetic.body_part.title", cosmetic.displayName()), parent);
		this.cosmetic = cosmetic;
	}

	private int previewWidth() {
		return MotionPreview.widthFor(width);
	}

	private int rowWidth() {
		return Math.min(320, width - 10 - previewWidth() - 8 - 10);
	}

	private int left() {
		int free = width - 10 - previewWidth() - 8 - 10;
		return 10 + previewWidth() + 8 + Math.max(0, (free - rowWidth()) / 2);
	}

	@Override
	protected void init() {
		SettingsTabs.buttons(width, SettingsTabs.ANIMATIONS, parent, cosmetic).forEach(this::addRenderableWidget);
		BodyParts.Check[] checks = cosmetic.model() == null ? null : BodyParts.check(cosmetic.model());
		int rowWidth = rowWidth();
		int left = left();
		int y = TOP;
		for (Anchor part : BodyParts.PARTS) {
			boolean valid = (cosmetic.validParts() & BodyParts.bit(part)) != 0;
			boolean on = (cosmetic.replacedParts() & BodyParts.bit(part)) != 0;
			boolean last = on && Integer.bitCount(cosmetic.replacedParts()) == 1;
			FlatButton toggle = addRenderableWidget(FlatCycle.onOff(left + rowWidth - 110, y, 110, 20, Component.translatable("orbit_cosmetic.body_part.replace"), on,
				Component.translatable("orbit_cosmetic.body_part.replace.tooltip"), value -> {
					OrbitClient.get().library().setPartEnabled(cosmetic, part, value);
					rebuildWidgets();
				}));
			toggle.active = valid && !cosmetic.isFeatured() && !last;
			if (!valid && checks != null) {
				toggle.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.body_part.check." + checks[part.ordinal()].name().toLowerCase(Locale.ROOT))));
			} else if (last) {
				toggle.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.body_part.last")));
			} else if (cosmetic.isFeatured()) {
				toggle.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.particles.featured")));
			}
			y += ROW_HEIGHT;
		}
		addRenderableWidget(new FlatButton(width / 2 - 75, height - 28, 150, 20, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		dragging = Theme.inside(event.x(), event.y(), 10, SettingsTabs.HEIGHT + 4, previewWidth(), height - 34 - SettingsTabs.HEIGHT - 4);
		return dragging;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			previewYaw = (previewYaw - (float) dx * 2.5F) % 360;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int previewX = 10;
		int previewY = SettingsTabs.HEIGHT + 4;
		int previewW = previewWidth();
		int previewH = height - 34 - previewY;
		Theme.panel(graphics, previewX, previewY, previewW, previewH);
		preview.setWorn(cosmetic.isUsable() ? List.of(cosmetic.toEquipped()) : List.of());
		PreviewBatch.panel(graphics, previewX + 1, previewY + 1, previewX + previewW - 1, previewY + previewH - 1,
			List.of(preview.entryIn(4, 4, previewW - 10, previewH - 10, previewYaw, 0, partialTick)));

		int rowWidth = rowWidth();
		int left = left();
		int center = left + rowWidth / 2;
		graphics.centeredText(font, Theme.clip(font, title, rowWidth), center, SettingsTabs.HEIGHT + 8, Theme.TEXT);
		graphics.centeredText(font, Theme.clip(font, Component.translatable("orbit_cosmetic.body_part.subtitle").withStyle(ChatFormatting.GRAY), rowWidth),
			center, SettingsTabs.HEIGHT + 22, Theme.TEXT);
		BodyParts.Check[] checks = cosmetic.model() == null ? null : BodyParts.check(cosmetic.model());
		int y = TOP;
		for (Anchor part : BodyParts.PARTS) {
			graphics.text(font, Component.translatable("orbit_cosmetic.body_part.part." + part.name().toLowerCase(Locale.ROOT)), left, y + 1, Theme.TEXT, false);
			String state;
			if ((cosmetic.replacedParts() & BodyParts.bit(part)) != 0) {
				state = "replaced";
			} else if ((cosmetic.validParts() & BodyParts.bit(part)) != 0) {
				state = "off";
			} else {
				state = checks == null ? "missing" : checks[part.ordinal()].name().toLowerCase(Locale.ROOT);
			}
			graphics.text(font, Theme.clip(font, Component.translatable("orbit_cosmetic.body_part.state." + state), rowWidth - 120), left, y + 11,
				state.equals("too_big") ? Theme.YELLOW : Theme.MUTED, false);
			y += ROW_HEIGHT;
		}
		y += 4;
		for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.body_part.help").withStyle(ChatFormatting.GRAY), rowWidth)) {
			if (y + 9 > height - 32) {
				break;
			}
			graphics.text(font, line, left, y, Theme.TEXT, false);
			y += 10;
		}
	}
}
