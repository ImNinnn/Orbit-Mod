package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitKeys;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.host.HostScreen;
import dev.orbit.cosmetic.client.social.SocialScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Supplier;

/**
 * The quick menu: open while its key is held, like the emote wheel. Squares in a cross (Host, Social,
 * Wardrobe in the middle, Settings, Players) and HUD components in a corner; point at one and let go of the key, or click it.
 */
public final class QuickMenuScreen extends Screen {
	private record Entry(int column, int row, String icon, String key, Supplier<Screen> open) {
	}

	private static final Entry[] ENTRIES = {
		new Entry(1, 0, "host", "orbit_cosmetic.menu.host", () -> new HostScreen(null)),
		new Entry(0, 1, "social", "orbit_cosmetic.quick.message", () -> new SocialScreen(null)),
		new Entry(1, 1, "orbit", "orbit_cosmetic.menu.wardrobe", () -> new OrbitMenuScreen(null)),
		new Entry(2, 1, "settings", "orbit_cosmetic.menu.settings", () -> new OrbitSettingsScreen(null)),
		new Entry(2, 0, "hud", "orbit_cosmetic.menu.hud", () -> new dev.orbit.cosmetic.client.hud.HudEditorScreen(null)),
		new Entry(1, 2, "player", "orbit_cosmetic.players.button", () -> new PlayersScreen(null)),
	};

	private int chosen = -1;
	private int cell;
	private int left;
	private int top;

	public QuickMenuScreen() {
		super(Component.translatable("orbit_cosmetic.quick.title"));
	}

	@Override
	protected void init() {
		cell = Math.min(64, Math.min(width, height - 60) / 4);
		left = (width - cell * 3) / 2;
		top = (height - cell * 3) / 2;
	}

	private static boolean available(Entry entry) {
		return !entry.icon().equals("player") || PlayersScreen.available();
	}

	private int entryAt(double mouseX, double mouseY) {
		int column = (int) Math.floor((mouseX - left) / cell);
		int row = (int) Math.floor((mouseY - top) / cell);
		for (int i = 0; i < ENTRIES.length; i++) {
			if (ENTRIES[i].column() == column && ENTRIES[i].row() == row && available(ENTRIES[i])) {
				return i;
			}
		}
		return -1;
	}

	private void open(int index) {
		if (index < 0) {
			onClose();
			return;
		}
		Compat.setScreen(ENTRIES[index].open().get());
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
		int index = entryAt(event.x(), event.y());
		if (index >= 0) {
			open(index);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
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
		for (int i = 0; i < ENTRIES.length; i++) {
			Entry entry = ENTRIES[i];
			int x = left + entry.column() * cell;
			int y = top + entry.row() * cell;
			boolean on = available(entry);
			boolean isChosen = i == chosen;
			graphics.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, isChosen ? 0xD0262A33 : 0xB01C1C20);
			graphics.outline(x + 2, y + 2, cell - 4, cell - 4, isChosen ? Theme.BLUE : Theme.BORDER);
			Theme.bevel(graphics, x + 3, y + 3, cell - 6, cell - 6, 1);
			Theme.icon(graphics, entry.icon(), x + (cell - 9) / 2, y + cell / 2 - 12, !on ? Theme.FAINT : entry.icon().equals("orbit") ? Theme.RED : Theme.TEXT);
			// The name under the icon, on two lines if it needs them.
			List<FormattedCharSequence> lines = font.split(Component.translatable(entry.key()), cell - 8);
			int ly = y + cell / 2 + 4;
			for (int line = 0; line < Math.min(2, lines.size()); line++) {
				graphics.centeredText(font, lines.get(line), x + cell / 2, ly, on ? Theme.TEXT : Theme.FAINT);
				ly += 10;
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
