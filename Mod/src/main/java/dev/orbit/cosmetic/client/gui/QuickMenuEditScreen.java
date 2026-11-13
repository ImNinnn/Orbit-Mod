package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.hud.HudComponent;
import dev.orbit.cosmetic.client.hud.HudLayout;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.skin.SkinLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sets up the quick menu: pick one of its nine squares on the left, then what it does in the list on the right (a
 * menu, an action, an outfit, a skin, an emote, showing or hiding HUD components, or nothing).
 */
public final class QuickMenuEditScreen extends OrbitScreen {
	private static final int ROW = 16;

	/** A line of the list: a heading, or something a square can do. */
	private record Choice(@Nullable Component heading, QuickMenuLayout.@Nullable Action action) {
	}

	private final List<Choice> choices = new ArrayList<>();
	private final List<Hotspot> squares = new ArrayList<>();
	private final List<Hotspot> rows = new ArrayList<>();
	private int selected = QuickMenuLayout.CENTER;
	private int scroll;

	public QuickMenuEditScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.quick.edit.title"), parent);
	}

	@Override
	protected int[] panel() {
		int w = Math.min(width - 20, 460);
		int h = Math.min(height - 20, 280);
		return new int[] {(width - w) / 2, (height - h) / 2, w, h};
	}

	private int square() {
		int[] panel = panel();
		return Math.clamp((panel[3] - 64) / 3, 30, 56);
	}

	private int listX() {
		return panel()[0] + 8 + square() * 3 + 12;
	}

	private int visibleRows() {
		int[] panel = panel();
		return Math.max(3, (panel[3] - 30 - 34) / ROW);
	}

	private void buildChoices() {
		choices.clear();
		choices.add(new Choice(null, QuickMenuLayout.Action.NONE));
		heading("orbit_cosmetic.quick.group.menus");
		for (QuickMenuLayout.Kind kind : new QuickMenuLayout.Kind[] {QuickMenuLayout.Kind.WARDROBE, QuickMenuLayout.Kind.SOCIAL, QuickMenuLayout.Kind.HOST,
			QuickMenuLayout.Kind.SETTINGS, QuickMenuLayout.Kind.SCREENSHOTS, QuickMenuLayout.Kind.HUD_EDITOR, QuickMenuLayout.Kind.PLAYERS, QuickMenuLayout.Kind.STATS}) {
			choices.add(new Choice(null, QuickMenuLayout.Action.of(kind)));
		}
		heading("orbit_cosmetic.quick.group.actions");
		for (QuickMenuLayout.Kind kind : new QuickMenuLayout.Kind[] {QuickMenuLayout.Kind.TOGGLE_COSMETICS, QuickMenuLayout.Kind.TOGGLE_HUD, QuickMenuLayout.Kind.PERSPECTIVE,
			QuickMenuLayout.Kind.HIDE_GUI}) {
			choices.add(new Choice(null, QuickMenuLayout.Action.of(kind)));
		}
		OrbitClient orbit = OrbitClient.get();
		List<OrbitState.Outfit> outfits = orbit.state().outfits();
		if (!outfits.isEmpty()) {
			heading("orbit_cosmetic.quick.group.outfits");
			outfits.forEach(outfit -> choices.add(new Choice(null, new QuickMenuLayout.Action(QuickMenuLayout.Kind.OUTFIT, outfit.name))));
		}
		List<SkinLibrary.LocalSkin> skins = orbit.skins().library().skins();
		if (!skins.isEmpty()) {
			heading("orbit_cosmetic.quick.group.skins");
			skins.forEach(skin -> choices.add(new Choice(null, new QuickMenuLayout.Action(QuickMenuLayout.Kind.SKIN, skin.key()))));
		}
		List<EmoteLibrary.LocalEmote> emotes = orbit.emotes().emotes();
		if (!emotes.isEmpty()) {
			heading("orbit_cosmetic.quick.group.emotes");
			emotes.forEach(emote -> choices.add(new Choice(null, new QuickMenuLayout.Action(QuickMenuLayout.Kind.EMOTE, emote.key()))));
		}
		heading("orbit_cosmetic.quick.group.profiles");
		HudLayout.profiles().forEach(profile -> choices.add(new Choice(null, new QuickMenuLayout.Action(QuickMenuLayout.Kind.HUD_PROFILE, profile.name))));
		List<HudComponent> components = HudLayout.components();
		if (!components.isEmpty()) {
			heading("orbit_cosmetic.quick.group.components");
			components.forEach(component -> choices.add(new Choice(null, new QuickMenuLayout.Action(QuickMenuLayout.Kind.TOGGLE_COMPONENT, component.id.toString()))));
		}
	}

	private void heading(String key) {
		choices.add(new Choice(Component.translatable(key), null));
	}

	@Override
	protected void init() {
		buildChoices();
		squares.clear();
		rows.clear();
		int[] panel = panel();
		int size = square();
		int gx = panel[0] + 8;
		int gy = panel[1] + 26;
		for (int i = 0; i < QuickMenuLayout.CELLS; i++) {
			int index = i;
			Hotspot spot = addRenderableWidget(new Hotspot(QuickMenuLayout.get(i).label(), h -> {
				selected = index;
				rebuildWidgets();
			}));
			spot.place(gx + i % 3 * size, gy + i / 3 * size, size, size);
			squares.add(spot);
		}
		addRenderableWidget(new FlatButton(gx, gy + size * 3 + 8, size * 3, 18, Component.translatable("orbit_cosmetic.quick.reset"), null, FlatButton.Style.SECONDARY,
			b -> {
				QuickMenuLayout.reset();
				rebuildWidgets();
			}));
		int lx = listX();
		int lw = panel[0] + panel[2] - 8 - lx;
		int ly = panel[1] + 26;
		scroll = Math.clamp(scroll, 0, Math.max(0, choices.size() - visibleRows()));
		for (int i = 0; i < visibleRows() && scroll + i < choices.size(); i++) {
			Choice choice = choices.get(scroll + i);
			if (choice.action() == null) {
				rows.add(null);
				continue;
			}
			Hotspot row = addRenderableWidget(new Hotspot(choice.action().label(), h -> {
				QuickMenuLayout.set(selected, choice.action());
				rebuildWidgets();
			}));
			row.place(lx, ly + i * ROW, lw, ROW - 1);
			rows.add(row);
		}
		addRenderableWidget(new FlatButton(panel[0] + panel[2] - 88, panel[1] + panel[3] - 26, 80, 18, CommonComponents.GUI_DONE, null, FlatButton.Style.PRIMARY,
			b -> onClose()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * 2, 0, Math.max(0, choices.size() - visibleRows()));
		rebuildWidgets();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int[] panel = panel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
		graphics.text(font, title, panel[0] + 8, panel[1] + 9, Theme.TEXT, false);
		Component hint = Component.translatable("orbit_cosmetic.quick.edit.hint");
		graphics.text(font, Theme.clip(font, hint, panel[2] - 24 - font.width(title)), panel[0] + 16 + font.width(title), panel[1] + 9, Theme.FAINT, false);
		int size = square();
		for (int i = 0; i < squares.size(); i++) {
			Hotspot spot = squares.get(i);
			QuickMenuLayout.Action action = QuickMenuLayout.get(i);
			if (action.kind() == QuickMenuLayout.Kind.EMPTY) {
				graphics.fill(spot.getX() + 2, spot.getY() + 2, spot.getRight() - 2, spot.getBottom() - 2, 0x40000000);
				Theme.dashed(graphics, spot.getX() + 2, spot.getY() + 2, size - 4, size - 4, Theme.BORDER);
			} else {
				QuickMenuScreen.drawSquare(graphics, font, action, spot.getX(), spot.getY(), size, spot.isHoveredOrFocused(), true);
			}
			if (i == selected) {
				graphics.outline(spot.getX() + 1, spot.getY() + 1, size - 2, size - 2, Theme.RED);
				graphics.outline(spot.getX() + 2, spot.getY() + 2, size - 4, size - 4, Theme.RED);
			}
			spot.drawFocus(graphics);
		}
		// The list: headings, and what the picked square can do (its current one ticked).
		int lx = listX();
		int lw = panel[0] + panel[2] - 8 - lx;
		int ly = panel[1] + 26;
		graphics.fill(lx - 6, panel[1] + 22, lx - 5, panel[1] + panel[3] - 32, Theme.BORDER);
		QuickMenuLayout.Action current = QuickMenuLayout.get(selected);
		for (int i = 0; i < rows.size(); i++) {
			Choice choice = choices.get(scroll + i);
			int y = ly + i * ROW;
			if (choice.heading() != null) {
				graphics.text(font, choice.heading(), lx + 2, y + 5, Theme.RED, false);
				continue;
			}
			Hotspot row = rows.get(i);
			QuickMenuLayout.Action action = choice.action();
			boolean isCurrent = action.equals(current);
			if (row.isHoveredOrFocused() || isCurrent) {
				graphics.fill(lx, y, lx + lw, y + ROW - 1, isCurrent ? Theme.PANEL_HOVER : Theme.PANEL_2);
			}
			if (action.kind() != QuickMenuLayout.Kind.EMPTY) {
				Theme.icon(graphics, action.kind().icon, lx + 4, y + 3, action.kind().icon.equals("orbit") ? Theme.RED : Theme.MUTED);
			}
			Component label = action.kind() == QuickMenuLayout.Kind.EMPTY ? Component.translatable("orbit_cosmetic.quick.action.empty") : action.label();
			graphics.text(font, Theme.clip(font, label, lw - 40), lx + 18, y + 4, Theme.TEXT, false);
			if (isCurrent) {
				Theme.icon(graphics, "check_small", lx + lw - 13, y + 3, Theme.GREEN);
			}
			row.drawFocus(graphics);
		}
		if (choices.size() > visibleRows()) {
			// Where the list is scrolled to.
			int track = visibleRows() * ROW;
			int thumb = Math.max(10, track * visibleRows() / choices.size());
			int ty = ly + (track - thumb) * scroll / Math.max(1, choices.size() - visibleRows());
			graphics.fill(lx + lw + 2, ty, lx + lw + 4, ty + thumb, Theme.BORDER_LIGHT);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
