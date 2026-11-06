package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Picks one of the player's screenshots (newest first) to send. */
final class PickScreenshotScreen extends OrbitScreen {
	private final Consumer<ScreenshotLibrary.Shot> picked;
	private List<ScreenshotLibrary.Shot> shots = List.of();
	private final List<int[]> tiles = new ArrayList<>();
	private double scroll;

	PickScreenshotScreen(@Nullable Screen parent, Consumer<ScreenshotLibrary.Shot> picked) {
		super(Component.translatable("orbit_cosmetic.social.pick_picture"), parent);
		this.picked = picked;
	}

	@Override
	protected void init() {
		shots = OrbitClient.get().screenshots().list();
		backButton(6, 6);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.centeredText(font, title, width / 2, 10, Theme.TEXT);
		int columns = Math.max(1, (width - 40) / 130);
		int tileW = (width - 40 - (columns - 1) * 6) / columns;
		int tileH = tileW * 9 / 16;
		tiles.clear();
		graphics.enableScissor(0, 28, width, height);
		for (int i = 0; i < shots.size(); i++) {
			int x = 20 + (i % columns) * (tileW + 6);
			int y = 30 + (i / columns) * (tileH + 6) - (int) scroll;
			if (y + tileH < 28 || y > height) {
				continue;
			}
			tiles.add(new int[] {i, x, y, tileW, tileH});
			graphics.fill(x, y, x + tileW, y + tileH, Theme.PANEL_2);
			ScreenshotLibrary.Thumbnail thumbnail = OrbitClient.get().screenshots().thumbnail(shots.get(i).path(), 256);
			if (thumbnail != null) {
				graphics.blit(thumbnail.id(), x, y, x + tileW, y + tileH, 0, 1, 0, 1);
			}
			if (Theme.inside(mouseX, mouseY, x, y, tileW, tileH)) {
				graphics.outline(x - 1, y - 1, tileW + 2, tileH + 2, Theme.EDGE_HOT);
			}
		}
		graphics.disableScissor();
		if (shots.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.screenshots.empty").withStyle(ChatFormatting.GRAY), width / 2, height / 2, Theme.TEXT);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		for (int[] tile : tiles) {
			if (Theme.inside(event.x(), event.y(), tile[1], tile[2], tile[3], tile[4])) {
				picked.accept(shots.get(tile[0]));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.max(0, scroll - scrollY * 30);
		return true;
	}
}
