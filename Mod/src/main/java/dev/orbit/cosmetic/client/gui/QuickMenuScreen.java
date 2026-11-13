package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitKeys;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The quick menu: open while its key is held, like the emote wheel. Up to nine squares the player sets up (see
 * {@link QuickMenuLayout}; Orbit's usual cross at first); point at one and let go of the key, or click it.
 */
public final class QuickMenuScreen extends Screen {
	private int chosen = -1;
	private int cell;
	private int left;
	private int top;

	public QuickMenuScreen() {
		super(Component.translatable("orbit_cosmetic.quick.title"));
	}

	@Override
	protected void init() {
		cell = Math.min(64, Math.min(width / 4, (height - 50) / 3));
		left = (width - cell * 3) / 2;
		top = (height - cell * 3) / 2;
		Component customise = Component.translatable("orbit_cosmetic.quick.customise");
		int w = font.width(customise) + 24;
		addRenderableWidget(new FlatButton(width / 2 - w / 2, top + cell * 3 + 8, w, 16, customise, "edit", FlatButton.Style.GHOST,
			b -> Compat.setScreen(new QuickMenuEditScreen(null))));
	}

	/** The square under the mouse that can be used, or -1. */
	private int entryAt(double mouseX, double mouseY) {
		int column = (int) Math.floor((mouseX - left) / cell);
		int row = (int) Math.floor((mouseY - top) / cell);
		if (column < 0 || column > 2 || row < 0 || row > 2) {
			return -1;
		}
		int index = row * 3 + column;
		return QuickMenuLayout.get(index).available() ? index : -1;
	}

	private void open(int index) {
		if (index < 0) {
			onClose();
			return;
		}
		QuickMenuLayout.get(index).run();
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (OrbitKeys.quickMenuKey().matches(event)) {
			open(chosen);
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (OrbitKeys.quickMenuKey().matches(event)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		int index = entryAt(event.x(), event.y());
		if (index >= 0) {
			open(index);
			return true;
		}
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0x60000000, 0x90000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int hovered = entryAt(mouseX, mouseY);
		if (hovered >= 0) {
			chosen = hovered;
		}
		for (int i = 0; i < QuickMenuLayout.CELLS; i++) {
			QuickMenuLayout.Action action = QuickMenuLayout.get(i);
			if (action.kind() == QuickMenuLayout.Kind.EMPTY) {
				continue;
			}
			int x = left + i % 3 * cell;
			int y = top + i / 3 * cell;
			drawSquare(graphics, font, action, x, y, cell, i == chosen, action.available());
		}
	}

	/** One square: its icon (Orbit's in red), its name under it on up to two lines. */
	static void drawSquare(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, QuickMenuLayout.Action action, int x, int y, int cell, boolean chosen,
		boolean on) {
		graphics.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, chosen ? 0xD0262A33 : 0xB01C1C20);
		graphics.outline(x + 2, y + 2, cell - 4, cell - 4, chosen ? Theme.BLUE : Theme.BORDER);
		Theme.bevel(graphics, x + 3, y + 3, cell - 6, cell - 6, 1);
		String icon = action.kind().icon;
		Theme.icon(graphics, icon, x + (cell - 9) / 2, y + cell / 2 - 12, !on ? Theme.FAINT : icon.equals("orbit") ? Theme.RED : Theme.TEXT);
		// Two lines at most, broken between words: a word too long for the square ends with "...".
		Component label = action.label();
		boolean wordsFit = java.util.Arrays.stream(label.getString().split(" ")).allMatch(word -> font.width(word) <= cell - 8);
		List<FormattedCharSequence> lines = wordsFit ? font.split(label, cell - 8) : List.of(Theme.clip(font, label, cell - 8));
		int ly = y + cell / 2 + 4;
		for (int line = 0; line < Math.min(2, lines.size()); line++) {
			graphics.centeredText(font, lines.get(line), x + cell / 2, ly, on ? Theme.TEXT : Theme.FAINT);
			ly += 10;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
