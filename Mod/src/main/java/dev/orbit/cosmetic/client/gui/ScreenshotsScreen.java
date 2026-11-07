package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The screenshot manager: every screenshot, newest first, grouped by day (Today, Yesterday) then by month, with
 * the favourites on their own tab. Clicking one opens it.
 */
public final class ScreenshotsScreen extends OrbitScreen {
	private static final int HEADER = 24;
	private static final int TABS = 20;
	private static final int PAD = 8;
	private static final int GAP = 6;

	/** Kept while the game runs: the tab, and whether tiles are small. */
	private static boolean favoritesOnly;
	private static boolean small;

	private List<ScreenshotLibrary.Shot> shots = List.of();
	private String search = "";
	private @org.jspecify.annotations.Nullable EditBox searchBox;
	private double scroll;
	private int contentHeight;
	/** Tiles drawn last frame, for the mouse. */
	private final List<Placed> placed = new ArrayList<>();

	private record Placed(ScreenshotLibrary.Shot shot, int x, int y, int w, int h) {
	}

	public ScreenshotsScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.screenshots.title"), parent);
	}

	private int panelX() {
		return panel()[0];
	}

	private int panelWidth() {
		return panel()[2];
	}

	private int panelY() {
		return panel()[1];
	}

	private int panelHeight() {
		return panel()[3];
	}

	@Override
	protected void init() {
		shots = orbit().screenshots().list();
		int px = panelX();
		int pw = panelWidth();
		backButton(px - 22, panelY());
		int right = px + pw - PAD;
		addRenderableWidget(FlatButton.icon(right - 18, panelY() + 3, 18, "folder", Component.translatable("orbit_cosmetic.screenshots.open_folder"),
			FlatButton.Style.SECONDARY, b -> Compat.openPath(orbit().screenshots().folder())));
		addRenderableWidget(FlatButton.icon(right - 40, panelY() + 3, 18, small ? "image" : "grid", Component.translatable(small ? "orbit_cosmetic.screenshots.large" : "orbit_cosmetic.screenshots.small"),
			FlatButton.Style.SECONDARY, b -> {
				small = !small;
				rebuildWidgets();
			}));
		// Like the Orbit menu's: a dark field with the search icon, then the text.
		searchBox = new EditBox(font, right - 146, panelY() + 8, 98, 10, Component.translatable("orbit_cosmetic.menu.search"));
		searchBox.setBordered(false);
		searchBox.setHint(Component.translatable("orbit_cosmetic.menu.search").withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setTextColor(Theme.TEXT);
		searchBox.setValue(search);
		searchBox.setResponder(text -> {
			search = text.strip().toLowerCase(Locale.ROOT);
			scroll = 0;
		});
		addRenderableWidget(searchBox);
		int tabY = panelY() + HEADER + 2;
		FlatButton all = addRenderableWidget(new FlatButton(px + PAD, tabY, 50, 16, Component.translatable("orbit_cosmetic.screenshots.all"), null, FlatButton.Style.GHOST, b -> {
			favoritesOnly = false;
			scroll = 0;
			rebuildWidgets();
		}));
		all.setSelected(!favoritesOnly);
		FlatButton favorites = addRenderableWidget(new FlatButton(px + PAD + 54, tabY, 70, 16, Component.translatable("orbit_cosmetic.screenshots.favorites"), null,
			FlatButton.Style.GHOST, b -> {
				favoritesOnly = true;
				scroll = 0;
				rebuildWidgets();
			}));
		favorites.setSelected(favoritesOnly);
	}

	private List<ScreenshotLibrary.Shot> visible() {
		return shots.stream().filter(shot -> (!favoritesOnly || shot.favorite()) && (search.isEmpty() || shot.name().toLowerCase(Locale.ROOT).contains(search))).toList();
	}

	/** "Today", "Yesterday", or the month ("July 2026"). */
	private static Component group(long modified) {
		LocalDate day = Instant.ofEpochMilli(modified).atZone(ZoneId.systemDefault()).toLocalDate();
		LocalDate today = LocalDate.now();
		if (day.equals(today)) {
			return Component.translatable("orbit_cosmetic.screenshots.today");
		}
		if (day.equals(today.minusDays(1))) {
			return Component.translatable("orbit_cosmetic.screenshots.yesterday");
		}
		String month = day.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault());
		return Component.literal(month.substring(0, 1).toUpperCase(Locale.ROOT) + month.substring(1) + " " + day.getYear());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int px = panelX();
		int pw = panelWidth();
		int py = panelY();
		int ph = panelHeight();
		Theme.panel(graphics, px, py, pw, ph);
		graphics.fill(px + 1, py + 1, px + pw - 1, py + HEADER, Theme.PANEL_2);
		graphics.text(font, title, px + PAD, py + 8, Theme.TEXT, false);
		if (searchBox != null) {
			int boxX = searchBox.getX() - 12;
			Theme.panel(graphics, boxX, searchBox.getY() - 4, searchBox.getWidth() + 14, 16, Theme.BACKGROUND);
			Theme.icon(graphics, "search", boxX + 3, searchBox.getY(), Theme.FAINT);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int gridTop = py + HEADER + TABS + 4;
		int gridBottom = py + ph - PAD;
		int gridX = px + PAD;
		int gridW = pw - PAD * 2 - 4;
		int columns = Math.max(1, (gridW + GAP) / ((small ? 90 : 150) + GAP));
		int tileW = (gridW - GAP * (columns - 1)) / columns;
		int tileH = tileW * 9 / 16;
		List<ScreenshotLibrary.Shot> list = visible();
		placed.clear();
		graphics.enableScissor(px + 1, gridTop, px + pw - 1, gridBottom);
		int y = gridTop - (int) scroll;
		String currentGroup = null;
		int column = 0;
		ScreenshotLibrary.Shot hovered = null;
		for (ScreenshotLibrary.Shot shot : list) {
			Component group = group(shot.modified());
			if (!group.getString().equals(currentGroup)) {
				if (currentGroup != null) {
					y += tileH + GAP;
				}
				currentGroup = group.getString();
				Theme.separator(graphics, font, gridX, y + 6, gridW, group);
				y += 16;
				column = 0;
			} else if (column == columns) {
				column = 0;
				y += tileH + GAP;
			}
			int x = gridX + column * (tileW + GAP);
			column++;
			if (y + tileH < gridTop || y > gridBottom) {
				continue;
			}
			placed.add(new Placed(shot, x, y, tileW, tileH));
			graphics.fill(x, y, x + tileW, y + tileH, Theme.PANEL_2);
			ScreenshotLibrary.Thumbnail thumbnail = orbit().screenshots().thumbnail(shot.path(), small ? 160 : 320);
			if (thumbnail != null) {
				// Covers the tile, cut to its shape.
				float imageRatio = thumbnail.width() / (float) thumbnail.height();
				float tileRatio = tileW / (float) tileH;
				float u0 = 0;
				float u1 = 1;
				float v0 = 0;
				float v1 = 1;
				if (imageRatio > tileRatio) {
					float cut = (1 - tileRatio / imageRatio) / 2;
					u0 = cut;
					u1 = 1 - cut;
				} else {
					float cut = (1 - imageRatio / tileRatio) / 2;
					v0 = cut;
					v1 = 1 - cut;
				}
				graphics.blit(thumbnail.id(), x, y, x + tileW, y + tileH, u0, u1, v0, v1);
			}
			boolean hot = mouseX >= x && mouseY >= y && mouseX < x + tileW && mouseY < y + tileH && mouseY >= gridTop && mouseY < gridBottom;
			if (hot) {
				hovered = shot;
				graphics.outline(x - 1, y - 1, tileW + 2, tileH + 2, Theme.EDGE_HOT);
			}
			if (shot.favorite()) {
				Theme.icon(graphics, "heart", x + tileW - 12, y + 3, Theme.RED);
			}
		}
		contentHeight = list.isEmpty() ? 0 : y + tileH + (int) scroll - gridTop;
		graphics.disableScissor();
		if (list.isEmpty()) {
			graphics.centeredText(font, Component.translatable(favoritesOnly ? "orbit_cosmetic.screenshots.no_favorites" : "orbit_cosmetic.screenshots.empty")
				.withStyle(ChatFormatting.GRAY), px + pw / 2, gridTop + 30, Theme.TEXT);
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(font, Component.literal(hovered.name()), mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		for (Placed p : placed) {
			if (event.x() >= p.x && event.y() >= p.y && event.x() < p.x + p.w && event.y() < p.y + p.h) {
				Compat.setScreen(new ScreenshotViewScreen(this, p.shot));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int gridHeight = panelHeight() - HEADER - TABS - 4 - PAD;
		scroll = Math.clamp(scroll - scrollY * 30, 0, Math.max(0, contentHeight - gridHeight));
		return true;
	}

	@Override
	public void onClose() {
		orbit().screenshots().releaseAll();
		super.onClose();
	}
}
