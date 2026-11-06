package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.OrbitState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The emote wheel: open while the emote key (R) is held. Point at an emote and let go of the key to play it. The
 * last emote pointed at stays chosen, so the key can be released anywhere. Clicking an emote plays it too; the
 * arrows (or the mouse wheel) switch between wheels, and Edit opens the Orbit menu's emote section.
 */
public final class EmoteWheelScreen extends Screen {
	/** Slot positions on the 3x3 grid, clockwise from the top; the middle cell is not a slot. */
	private static final int[][] CELLS = {{1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}, {0, 0}};

	private final PreviewModel[] previews = new PreviewModel[OrbitState.WHEEL_SLOTS];
	private int chosen = -1;
	private int cell;
	private int left;
	private int top;

	public EmoteWheelScreen() {
		super(Component.translatable("orbit_cosmetic.wheel.title"));
		for (int i = 0; i < previews.length; i++) {
			previews[i] = new PreviewModel();
		}
	}

	public static int[] cellOfSlot(int slot) {
		return CELLS[slot];
	}

	public static int slotOfCell(int column, int row) {
		for (int slot = 0; slot < CELLS.length; slot++) {
			if (CELLS[slot][0] == column && CELLS[slot][1] == row) {
				return slot;
			}
		}
		return -1;
	}

	private static EmoteLibrary emotes() {
		return OrbitClient.get().emotes();
	}

	@Override
	protected void init() {
		cell = Math.min(60, Math.min(width, height - 80) / 4);
		left = (width - cell * 3) / 2;
		top = (height - cell * 3) / 2;
		addRenderableWidget(FlatButton.icon(left - 24, top + cell + (cell - 18) / 2, 18, "back", Component.translatable("orbit_cosmetic.wheel.previous"), FlatButton.Style.SECONDARY, b -> switchWheel(-1)));
		addRenderableWidget(FlatButton.icon(left + cell * 3 + 6, top + cell + (cell - 18) / 2, 18, "forward", Component.translatable("orbit_cosmetic.wheel.next"), FlatButton.Style.SECONDARY, b -> switchWheel(1)));
		addRenderableWidget(new FlatButton(width / 2 - 25, top + cell * 3 + 8, 50, 18, Component.translatable("orbit_cosmetic.wheel.edit"), "edit", FlatButton.Style.SECONDARY,
			b -> Compat.setScreen(OrbitMenuScreen.emotes(null))));
	}

	private void switchWheel(int delta) {
		emotes().selectWheel(emotes().selectedWheel() + delta);
		chosen = -1;
	}

	private int slotAt(double mouseX, double mouseY) {
		int column = (int) Math.floor((mouseX - left) / cell);
		int row = (int) Math.floor((mouseY - top) / cell);
		if (column < 0 || column > 2 || row < 0 || row > 2) {
			return -1;
		}
		return slotOfCell(column, row);
	}

	private void play(int slot) {
		EmoteLibrary.LocalEmote emote = slot < 0 ? null : emotes().slot(emotes().selectedWheel(), slot);
		onClose();
		if (emote != null) {
			OrbitClient.get().emoteController().play(emote);
		}
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (OrbitClient.get().emoteWheelKey().matches(event)) {
			play(chosen);
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (OrbitClient.get().emoteWheelKey().matches(event)) {
			// Opened with a quick tap: pressing the key again closes it (or plays the chosen emote on release).
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		int slot = slotAt(event.x(), event.y());
		if (slot >= 0 && emotes().slot(emotes().selectedWheel(), slot) != null) {
			play(slot);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		switchWheel(scrollY > 0 ? -1 : 1);
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0x60000000, 0x90000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int wheel = emotes().selectedWheel();
		Component name = Component.literal(emotes().wheels().get(wheel).name);
		int nameWidth = font.width(name) + 12;
		graphics.fill(width / 2 - nameWidth / 2, top - 22, width / 2 + nameWidth / 2, top - 8, 0xC0141416);
		graphics.centeredText(font, name, width / 2, top - 19, Theme.TEXT);
		int hovered = slotAt(mouseX, mouseY);
		if (hovered >= 0 && emotes().slot(wheel, hovered) != null) {
			chosen = hovered;
		}
		List<PreviewBatch.Entry> entries = new ArrayList<>();
		for (int slot = 0; slot < OrbitState.WHEEL_SLOTS; slot++) {
			int x = left + CELLS[slot][0] * cell;
			int y = top + CELLS[slot][1] * cell;
			EmoteLibrary.LocalEmote emote = emotes().slot(wheel, slot);
			boolean isChosen = slot == chosen;
			graphics.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, isChosen ? 0xD0262A33 : 0xB01C1C20);
			graphics.outline(x + 2, y + 2, cell - 4, cell - 4, isChosen ? Theme.BLUE : Theme.BORDER);
			Theme.bevel(graphics, x + 3, y + 3, cell - 6, cell - 6, 1);
			if (emote == null) {
				continue;
			}
			PreviewModel preview = previews[slot];
			Wearer.Emote playing = preview.emote();
			if (playing == null || !playing.asset.equals(emote.asset()) || playing.isFinished(OrbitClient.get().menuTime())) {
				preview.playEmote(emote.asset(), emote.file().particles());
			}
			entries.add(preview.entryIn(x - left + 4, y - top + 4, cell - 8, cell - 18, 15, 0, partialTick));
		}
		PreviewBatch.grid(graphics, left, top, left + cell * 3, top + cell * 3, entries);
		for (int slot = 0; slot < OrbitState.WHEEL_SLOTS; slot++) {
			EmoteLibrary.LocalEmote emote = emotes().slot(wheel, slot);
			if (emote != null) {
				int x = left + CELLS[slot][0] * cell;
				int y = top + CELLS[slot][1] * cell;
				graphics.text(font, Theme.clip(font, Component.literal(emote.displayName()), cell - 8), x + 4, y + cell - 11, Theme.TEXT, true);
			}
		}
		// The middle cell is opaque: it covers the crosshair, which the game draws under every screen.
		graphics.fill(left + cell + 2, top + cell + 2, left + cell * 2 - 2, top + cell * 2 - 2, Theme.PANEL);
		graphics.outline(left + cell + 2, top + cell + 2, cell - 4, cell - 4, Theme.BORDER);
		Theme.bevel(graphics, left + cell + 3, top + cell + 3, cell - 6, cell - 6, 1);
		Theme.icon(graphics, "wheel", left + cell + (cell - 9) / 2, top + cell + (cell - 9) / 2, Theme.FAINT);
		boolean empty = true;
		for (int slot = 0; slot < OrbitState.WHEEL_SLOTS && empty; slot++) {
			empty = emotes().slot(wheel, slot) == null;
		}
		if (empty) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.wheel.empty").withStyle(ChatFormatting.GRAY), width / 2, top + cell * 3 + 32, Theme.MUTED);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
