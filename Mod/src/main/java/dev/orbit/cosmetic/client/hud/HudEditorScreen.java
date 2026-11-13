package dev.orbit.cosmetic.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.ColorRow;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.social.FloatingChats;
import dev.orbit.cosmetic.client.social.Social;
import dev.orbit.cosmetic.client.social.SocialPopups;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Places the HUD components and the chat windows.
 * <ul>
 *   <li>Click one to pick it (a linked group is picked whole; click a member again for its own settings), Shift-click
 *   to add to what is picked, or drag over empty space to pick everything in a rectangle.</li>
 *   <li>Drag what is picked to move it (it snaps to the screen's edges and middle and to the others), or its bottom
 *   right corner to resize it.</li>
 *   <li>Between two components next to each other, the link button links them: they move, resize and go together,
 *   and stay edge to edge when one grows.</li>
 *   <li>"+" adds a component (cards with a preview of each) or a chat window; Profiles switches between sets of
 *   components.</li>
 * </ul>
 */
public final class HudEditorScreen extends OrbitScreen {
	private static final int SNAP = 4;
	private static final int GRIP = 6;
	private static final int LINK = 9;
	private static final int SETTINGS_W = ColorRow.WIDTH + 16;
	private static final int[] CARD_WIDTHS = {78, 70, 62};
	private static final int CARD_MAX_H = 58;
	private static final int LIST_W = 260;
	private static final int ROW = 16;
	private static final int LIST_ROWS = 8;
	/** The text colours offered: white and Orbit's red first. */
	private static final int[] TEXT_COLORS = {0xFFFFFF, 0xFF2626, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xA3A3AD};
	/** The accent colours offered: Orbit's red first. */
	private static final int[] ACCENT_COLORS = {0xFF2626, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xD63B8A, 0xFFFFFF};
	private static final int[] CHAT_ACCENTS = {FloatingChats.DEFAULT_ACCENT, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xD63B8A, 0x5A5A66};
	private static final Map<HudComponent.Type, HudComponent> SAMPLES = new EnumMap<>(HudComponent.Type.class);

	private enum Mode {
		EDIT, ADD, ADD_CHAT, PROFILES, LAYERS
	}

	private enum Drag {
		NONE, MOVE, RESIZE, RECTANGLE
	}

	/** A label drawn above a colour row of the settings. */
	private record Label(Component text, int x, int y) {
	}

	/** A card of the add menu: a component type, or a chat window when null. */
	private record Card(HudComponent.@Nullable Type type, Hotspot spot) {
	}

	/** A link button between two components next to each other (linked or not). */
	private record LinkSpot(HudComponent a, HudComponent b, int x, int y, boolean linked) {
	}

	private Mode mode = Mode.EDIT;
	/** What is picked: components (whole linked groups) and chat windows. */
	private final Set<Object> selection = new LinkedHashSet<>();
	/** A member of a linked group clicked again: its own settings. */
	private @Nullable Object focused;
	private Drag drag = Drag.NONE;
	private int startX;
	private int startY;
	private int mouseX;
	private int mouseY;
	/** At the start of a drag: where each moved thing was, its scale, and the picked things' outline. */
	private final Map<Object, int[]> startPositions = new IdentityHashMap<>();
	private final Map<HudComponent, Float> startScales = new IdentityHashMap<>();
	private int[] startBounds = new int[4];
	private int pendingDock = HudComponent.DOCK_NONE;
	private final List<ColorRow> colorRows = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	private final List<Hotspot> listRows = new ArrayList<>();
	private final List<Card> cards = new ArrayList<>();
	private List<Social.Person> conversations = List.of();
	private int listScroll;
	private @Nullable EditBox profileName;
	/** The paperdoll's angle field, when its settings show. */
	private @Nullable EditBox angleBox;
	/** The snapping lines shown while dragging: x of a vertical one, y of a horizontal one (-1 for none). */
	private int guideX = -1;
	private int guideY = -1;

	public HudEditorScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.hud.title"), parent);
	}

	public static void open(@Nullable Screen parent) {
		Compat.setScreen(new HudEditorScreen(parent));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private boolean inWorld() {
		return minecraft != null && minecraft.level != null;
	}

	// ---------------------------------------------------------------- items

	/** Everything placed, bottom first: the components, then the chat windows (in a world). */
	private List<Object> items() {
		List<Object> items = new ArrayList<>(HudLayout.components());
		if (FloatingChats.available()) {
			items.addAll(FloatingChats.windows());
		}
		return items;
	}

	private int[] bounds(Object item) {
		if (item instanceof HudComponent component) {
			int[] at = HudLayout.position(component, width, height);
			return new int[] {at[0], at[1], component.scaledWidth(), component.scaledHeight()};
		}
		FloatingChats.Window window = (FloatingChats.Window) item;
		return new int[] {window.x, window.y, window.w, window.h};
	}

	/** The outline around several things. */
	private int[] bounds(Iterable<Object> items) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Object item : items) {
			int[] b = bounds(item);
			minX = Math.min(minX, b[0]);
			minY = Math.min(minY, b[1]);
			maxX = Math.max(maxX, b[0] + b[2]);
			maxY = Math.max(maxY, b[1] + b[3]);
		}
		return minX == Integer.MAX_VALUE ? new int[4] : new int[] {minX, minY, maxX - minX, maxY - minY};
	}

	private boolean visible(Object item) {
		return !(item instanceof HudComponent component) || component.available() || !inWorld();
	}

	/** What an item brings with it when picked: its whole linked group. */
	private List<Object> withGroup(Object item) {
		if (item instanceof HudComponent component) {
			return new ArrayList<>(HudLayout.group(component));
		}
		return List.of(item);
	}

	/** The things a move moves: each picked group's root, and the chat windows. */
	private List<Object> movers() {
		List<Object> movers = new ArrayList<>();
		for (Object item : selection) {
			Object mover = item instanceof HudComponent component ? HudLayout.root(component) : item;
			if (!movers.contains(mover)) {
				movers.add(mover);
			}
		}
		return movers;
	}

	private List<HudComponent> selectedComponents() {
		List<HudComponent> components = new ArrayList<>();
		for (Object item : selection) {
			if (item instanceof HudComponent component) {
				components.add(component);
			}
		}
		return components;
	}

	/** The one thing the settings are for, or null when they are for several. */
	private @Nullable Object single() {
		if (focused != null && selection.contains(focused)) {
			return focused;
		}
		return selection.size() == 1 ? selection.iterator().next() : null;
	}

	private void select(Iterable<Object> items) {
		selection.clear();
		items.forEach(selection::add);
		focused = null;
	}

	private void removeSelection() {
		for (Object item : List.copyOf(selection)) {
			if (item instanceof HudComponent component) {
				HudLayout.remove(component);
			} else {
				FloatingChats.close((FloatingChats.Window) item);
			}
		}
		selection.clear();
		focused = null;
		rebuildWidgets();
	}

	private void moveTo(Object item, int x, int y) {
		if (item instanceof HudComponent component) {
			component.place(x, y, width, height);
		} else {
			FloatingChats.Window window = (FloatingChats.Window) item;
			window.x = Math.clamp(x, 0, Math.max(0, width - window.w));
			window.y = Math.clamp(y, 0, Math.max(0, height - window.h));
		}
	}

	/** A chat window clicked comes to the front (components keep their layer: see the layers list). */
	private void raise(Object item) {
		if (item instanceof FloatingChats.Window window) {
			FloatingChats.raise(window);
		}
	}

	/** Roots the moved groups again (on the side of the screen they are on now) and saves. */
	private void settle() {
		for (HudComponent component : selectedComponents()) {
			if (HudLayout.linked(component) && HudLayout.root(component) == component) {
				HudLayout.reroot(component, width, height);
			}
		}
		HudLayout.save();
	}

	// ---------------------------------------------------------------- links

	/** The link buttons: between members linked together, and between components of different groups next to each other. */
	private List<LinkSpot> linkSpots() {
		List<LinkSpot> spots = new ArrayList<>();
		List<HudComponent> components = new ArrayList<>();
		for (HudComponent component : HudLayout.components()) {
			if (visible(component) && component.dock == HudComponent.DOCK_NONE) {
				components.add(component);
			}
		}
		for (int i = 0; i < components.size(); i++) {
			for (int j = i + 1; j < components.size(); j++) {
				HudComponent a = components.get(i);
				HudComponent b = components.get(j);
				boolean linked = HudLayout.linkedTogether(a, b);
				if (!linked && HudLayout.root(a) == HudLayout.root(b)) {
					continue;
				}
				int[] spot = between(bounds(a), bounds(b));
				if (spot != null) {
					spots.add(new LinkSpot(a, b, spot[0], spot[1], linked));
				}
			}
		}
		return spots;
	}

	/** The middle of the edge two boxes share (side by side or one on the other, at most 4 pixels apart), or null. */
	private static int @Nullable [] between(int[] a, int[] b) {
		int overlapY = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
		int overlapX = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
		if (overlapY >= 4) {
			int middleY = Math.max(a[1], b[1]) + overlapY / 2;
			int gapRight = b[0] - (a[0] + a[2]);
			if (gapRight >= -1 && gapRight <= SNAP) {
				return new int[] {a[0] + a[2] + gapRight / 2, middleY};
			}
			int gapLeft = a[0] - (b[0] + b[2]);
			if (gapLeft >= -1 && gapLeft <= SNAP) {
				return new int[] {b[0] + b[2] + gapLeft / 2, middleY};
			}
		}
		if (overlapX >= 4) {
			int middleX = Math.max(a[0], b[0]) + overlapX / 2;
			int gapBelow = b[1] - (a[1] + a[3]);
			if (gapBelow >= -1 && gapBelow <= SNAP) {
				return new int[] {middleX, a[1] + a[3] + gapBelow / 2};
			}
			int gapAbove = a[1] - (b[1] + b[3]);
			if (gapAbove >= -1 && gapAbove <= SNAP) {
				return new int[] {middleX, b[1] + b[3] + gapAbove / 2};
			}
		}
		return null;
	}

	/** Link buttons show near the mouse, and for what is picked. */
	private boolean showsLink(LinkSpot spot, double mouseX, double mouseY) {
		return Math.abs(mouseX - spot.x()) < 28 && Math.abs(mouseY - spot.y()) < 28 || selection.contains(spot.a()) || selection.contains(spot.b());
	}

	private @Nullable LinkSpot linkAt(double mouseX, double mouseY) {
		if (mode != Mode.EDIT || drag != Drag.NONE) {
			return null;
		}
		for (LinkSpot spot : linkSpots()) {
			if (showsLink(spot, mouseX, mouseY) && Math.abs(mouseX - spot.x()) <= LINK / 2 + 1 && Math.abs(mouseY - spot.y()) <= LINK / 2 + 1) {
				return spot;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- widgets

	/** The buttons and the hint go at the top, or at the bottom when something is there under them. */
	private int toolbarY() {
		int x0 = width / 2 - 160;
		int x1 = width / 2 + 160;
		for (Object item : items()) {
			int[] b = bounds(item);
			if (visible(item) && b[0] < x1 && b[0] + b[2] > x0 && b[1] < 40) {
				return height - 24;
			}
		}
		return 6;
	}

	private int[] clearArea() {
		boolean top = toolbarY() == 6;
		return new int[] {top ? 28 : 4, top ? height - 4 : height - 28};
	}

	@Override
	protected void init() {
		colorRows.clear();
		labels.clear();
		listRows.clear();
		cards.clear();
		profileName = null;
		angleBox = null;
		int ty = toolbarY();
		int bw = 104;
		int x = width / 2 - (bw * 2 + 80 + 8 + 22) / 2;
		addRenderableWidget(new FlatButton(x, ty, bw, 18, Component.translatable("orbit_cosmetic.hud.add"), "plus", FlatButton.Style.PRIMARY,
			b -> setMode(mode == Mode.ADD ? Mode.EDIT : Mode.ADD)));
		Component profile = Component.literal(HudLayout.activeProfile().name);
		FlatButton profiles = addRenderableWidget(new FlatButton(x + bw + 4, ty, bw, 18, profile, "folder", FlatButton.Style.SECONDARY,
			b -> setMode(mode == Mode.PROFILES ? Mode.EDIT : Mode.PROFILES)));
		profiles.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.profiles.tooltip")));
		addRenderableWidget(FlatButton.icon(x + bw * 2 + 8, ty, 18, "grid", Component.translatable("orbit_cosmetic.hud.layers"), FlatButton.Style.SECONDARY,
			b -> setMode(mode == Mode.LAYERS ? Mode.EDIT : Mode.LAYERS)));
		addRenderableWidget(new FlatButton(x + bw * 2 + 30, ty, 80, 18, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
		selection.removeIf(item -> !items().contains(item));
		switch (mode) {
			case ADD -> initAdd();
			case ADD_CHAT -> initChats();
			case PROFILES -> initProfiles();
			case LAYERS -> initLayers();
			case EDIT -> {
				if (!selection.isEmpty() && drag == Drag.NONE) {
					initSettings();
				}
			}
		}
	}

	private void setMode(Mode mode) {
		this.mode = mode;
		listScroll = 0;
		if (mode != Mode.EDIT) {
			selection.clear();
			focused = null;
		}
		rebuildWidgets();
	}

	// ---------------------------------------------------------------- the add menu

	/** How the cards are laid out: their width and height, and how many a row (the biggest that fit the window). */
	private int[] cardLayout() {
		int count = HudComponent.Type.values().length + 1;
		int room = height - 8 - 28 - 24 - 32;
		int[] layout = null;
		for (int cardW : CARD_WIDTHS) {
			int columns = Math.clamp((width - 24 + 4) / (cardW + 4), 3, 7);
			int rows = (count + columns - 1) / columns;
			int cardH = Math.min(CARD_MAX_H, (room - (rows - 1) * 4) / rows);
			layout = new int[] {cardW, Math.max(34, cardH), columns};
			if (cardH >= 40) {
				break;
			}
		}
		return layout;
	}

	/** The panel of the add menu, the conversations or the profiles. */
	private int[] menuPanel() {
		int[] area = clearArea();
		if (mode == Mode.ADD) {
			int[] layout = cardLayout();
			int count = HudComponent.Type.values().length + 1;
			int rows = (count + layout[2] - 1) / layout[2];
			int w = layout[2] * (layout[0] + 4) - 4 + 16;
			int h = 24 + rows * (layout[1] + 4) - 4 + 32;
			return new int[] {(width - w) / 2, Math.max(area[0], Math.min((height - h) / 2, area[1] - h)), w, h};
		}
		int count = mode == Mode.ADD_CHAT ? conversations.size() : mode == Mode.LAYERS ? HudLayout.components().size() : HudLayout.profiles().size();
		int rows = Math.max(1, Math.min(LIST_ROWS, count));
		int h = 30 + rows * ROW + (mode == Mode.PROFILES ? 56 : mode == Mode.LAYERS ? 6 : 28);
		return new int[] {(width - LIST_W) / 2, Math.max(area[0], Math.min((height - h) / 2, area[1] - h)), LIST_W, h};
	}

	private void initAdd() {
		int[] panel = menuPanel();
		int[] layout = cardLayout();
		List<HudComponent.Type> types = new ArrayList<>(List.of(HudComponent.Type.values()));
		types.add(null);
		for (int i = 0; i < types.size(); i++) {
			HudComponent.Type type = types.get(i);
			int x = panel[0] + 8 + i % layout[2] * (layout[0] + 4);
			int y = panel[1] + 22 + i / layout[2] * (layout[1] + 4);
			Component label = type != null ? type.label() : Component.translatable("orbit_cosmetic.hud.type.chat");
			Hotspot spot = addRenderableWidget(new Hotspot(label, h -> {
				if (type != null) {
					HudComponent added = HudLayout.add(type, width, height);
					mode = Mode.EDIT;
					select(List.of(added));
					rebuildWidgets();
				} else if (FloatingChats.available()) {
					conversations = OrbitClient.get().social().conversations();
					setMode(Mode.ADD_CHAT);
				}
			}));
			spot.place(x, y, layout[0], layout[1]);
			if (type == null && !FloatingChats.available()) {
				spot.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.chat.world")));
			}
			cards.add(new Card(type, spot));
		}
		addRenderableWidget(new FlatButton(panel[0] + 8, panel[1] + panel[3] - 26, panel[2] - 16, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.GHOST,
			b -> setMode(Mode.EDIT)));
	}

	private void initChats() {
		int[] panel = menuPanel();
		listScroll = Math.clamp(listScroll, 0, Math.max(0, conversations.size() - LIST_ROWS));
		for (int i = 0; i < LIST_ROWS && listScroll + i < conversations.size(); i++) {
			Social.Person person = conversations.get(listScroll + i);
			Hotspot row = addRenderableWidget(new Hotspot(Component.literal(person.name()), h -> {
				FloatingChats.open(person.uuid(), person.name());
				mode = Mode.EDIT;
				select(List.of(FloatingChats.windows().getLast()));
				rebuildWidgets();
			}));
			row.place(panel[0] + 8, panel[1] + 24 + i * ROW, LIST_W - 16, ROW - 1);
			listRows.add(row);
		}
		addRenderableWidget(new FlatButton(panel[0] + 8, panel[1] + panel[3] - 26, LIST_W - 16, 18, CommonComponents.GUI_BACK, null, FlatButton.Style.GHOST,
			b -> setMode(Mode.ADD)));
	}

	// ---------------------------------------------------------------- profiles

	private void initProfiles() {
		int[] panel = menuPanel();
		List<HudLayout.Profile> profiles = HudLayout.profiles();
		listScroll = Math.clamp(listScroll, 0, Math.max(0, profiles.size() - LIST_ROWS));
		for (int i = 0; i < LIST_ROWS && listScroll + i < profiles.size(); i++) {
			HudLayout.Profile profile = profiles.get(listScroll + i);
			Hotspot row = addRenderableWidget(new Hotspot(Component.literal(profile.name), h -> {
				HudLayout.switchTo(profile);
				rebuildWidgets();
			}));
			row.place(panel[0] + 8, panel[1] + 24 + i * ROW, LIST_W - 16, ROW - 1);
			listRows.add(row);
		}
		int y = panel[1] + panel[3] - 50;
		int x = panel[0] + 8;
		int w = LIST_W - 16;
		profileName = new EditBox(font, x + 4, y + 4, w - 8 - 64, 10, Component.translatable("orbit_cosmetic.hud.profiles.name"));
		profileName.setBordered(false);
		profileName.setMaxLength(32);
		profileName.setTextColor(Theme.TEXT);
		profileName.setValue(HudLayout.activeProfile().name);
		addRenderableWidget(profileName);
		addRenderableWidget(new FlatButton(x + w - 60, y, 60, 18, Component.translatable("orbit_cosmetic.hud.profiles.rename"), null, FlatButton.Style.SECONDARY,
			b -> {
				HudLayout.rename(HudLayout.activeProfile(), profileName.getValue());
				rebuildWidgets();
			}));
		int third = (w - 8) / 3;
		int by = panel[1] + panel[3] - 26;
		addRenderableWidget(new FlatButton(x, by, third, 18, Component.translatable("orbit_cosmetic.hud.profiles.new"), "plus", FlatButton.Style.SECONDARY, b -> {
			HudLayout.create(Component.translatable("orbit_cosmetic.hud.profiles.default_name").getString(), false);
			rebuildWidgets();
		}));
		addRenderableWidget(new FlatButton(x + third + 4, by, third, 18, Component.translatable("orbit_cosmetic.hud.profiles.copy"), "copy", FlatButton.Style.SECONDARY,
			b -> {
				HudLayout.create(HudLayout.activeProfile().name, true);
				rebuildWidgets();
			}));
		FlatButton delete = addRenderableWidget(new FlatButton(x + (third + 4) * 2, by, w - (third + 4) * 2, 18, Component.translatable("orbit_cosmetic.hud.profiles.delete"),
			"trash", FlatButton.Style.DANGER, b -> {
				HudLayout.delete(HudLayout.activeProfile());
				rebuildWidgets();
			}));
		delete.active = HudLayout.profiles().size() > 1;
	}

	// ---------------------------------------------------------------- layers

	/** The components, the one drawn on top first. */
	private static List<HudComponent> layers() {
		List<HudComponent> layers = new ArrayList<>(HudLayout.components());
		java.util.Collections.reverse(layers);
		return layers;
	}

	/** The list of components, top first: click one to pick it, the arrows move it up or down a layer. */
	private void initLayers() {
		int[] panel = menuPanel();
		List<HudComponent> layers = layers();
		listScroll = Math.clamp(listScroll, 0, Math.max(0, layers.size() - LIST_ROWS));
		for (int i = 0; i < LIST_ROWS && listScroll + i < layers.size(); i++) {
			HudComponent component = layers.get(listScroll + i);
			int y = panel[1] + 24 + i * ROW;
			Hotspot row = addRenderableWidget(new Hotspot(component.type.label(), h -> {
				mode = Mode.EDIT;
				select(withGroup(component));
				rebuildWidgets();
			}));
			row.place(panel[0] + 8, y, LIST_W - 16 - 34, ROW - 1);
			listRows.add(row);
			FlatButton up = addRenderableWidget(FlatButton.icon(panel[0] + LIST_W - 8 - 32, y, 15, "chevron_up", Component.translatable("orbit_cosmetic.hud.layers.up"),
				FlatButton.Style.GHOST, b -> moveLayer(component, 1)));
			up.active = listScroll + i > 0;
			FlatButton down = addRenderableWidget(FlatButton.icon(panel[0] + LIST_W - 8 - 15, y, 15, "chevron_down", Component.translatable("orbit_cosmetic.hud.layers.down"),
				FlatButton.Style.GHOST, b -> moveLayer(component, -1)));
			down.active = listScroll + i < layers.size() - 1;
		}
	}

	/** Moves a component a layer up (over the one above it) or down. */
	private void moveLayer(HudComponent component, int by) {
		List<HudComponent> components = HudLayout.components();
		int index = components.indexOf(component);
		int to = index + by;
		if (index < 0 || to < 0 || to >= components.size()) {
			return;
		}
		components.remove(index);
		components.add(to, component);
		HudLayout.save();
		rebuildWidgets();
	}

	// ---------------------------------------------------------------- settings

	/** A row of the settings: one button, or several side by side (they share a half). */
	private record Cell(List<AbstractWidget> widgets) {
	}

	private int settingsHeight(int cells, int colors) {
		return 22 + (cells + 1) / 2 * 22 + colors * 30 + 30;
	}

	/** The settings panel sits beside what is picked (right of it if there is room, else left), on screen. */
	private int[] settingsPanel(int h) {
		int[] b = bounds(selection);
		int x = b[0] + b[2] + 6;
		if (x + SETTINGS_W > width - 4) {
			x = b[0] - SETTINGS_W - 6;
		}
		int y;
		if (x < 4) {
			// No room on either side: under it, or over it.
			x = Math.clamp(b[0], 4, Math.max(4, width - SETTINGS_W - 4));
			int below = b[1] + b[3] + 6;
			y = below + h <= height - 4 ? below : b[1] - h - 6;
		} else {
			y = b[1];
		}
		int[] area = clearArea();
		return new int[] {x, Math.clamp(y, area[0], Math.max(area[0], area[1] - h)), SETTINGS_W, h};
	}

	private int[] currentSettingsPanel() {
		return settingsPanel(settingsHeightNow);
	}

	private int settingsHeightNow;

	private void initSettings() {
		int half = (SETTINGS_W - 16 - 4) / 2;
		List<Cell> cells = new ArrayList<>();
		List<Object[]> colors = new ArrayList<>();
		Object single = single();
		if (single instanceof HudComponent component) {
			componentCells(component, half, cells, colors);
		} else if (single instanceof FloatingChats.Window window) {
			cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.auto_hide", () -> window.autoHide, on -> window.autoHide = on,
				"orbit_cosmetic.hud.auto_hide.tooltip")));
			cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.pinned", () -> window.pinned, on -> window.pinned = on, "orbit_cosmetic.hud.pinned.tooltip")));
			colors.add(new Object[] {"orbit_cosmetic.hud.accent", window.accent, CHAT_ACCENTS, (Consumer<Integer>) color -> window.accent = color});
		} else {
			// Several: what they share.
			List<HudComponent> components = selectedComponents();
			if (!components.isEmpty()) {
				cells.add(cell(groupToggle(half, components, "orbit_cosmetic.hud.menus", c -> c.inMenus, (c, on) -> c.inMenus = on)));
				cells.add(cell(groupToggle(half, components, "orbit_cosmetic.hud.background", c -> c.background, (c, on) -> c.background = on)));
				cells.add(cell(groupToggle(half, components, "orbit_cosmetic.hud.shown", c -> !c.hidden, (c, on) -> c.hidden = !on)));
				cells.add(cell(groupToggle(half, components, "orbit_cosmetic.hud.pinned", c -> c.pinned, (c, on) -> c.pinned = on)));
				cells.add(cell(makeRoomButton(half, components)));
				HudComponent first = components.getFirst();
				colors.add(new Object[] {"orbit_cosmetic.hud.color", first.color, TEXT_COLORS, (Consumer<Integer>) color -> components.forEach(c -> c.color = color)});
				colors.add(new Object[] {"orbit_cosmetic.hud.accent", first.accent, ACCENT_COLORS, (Consumer<Integer>) color -> components.forEach(c -> c.accent = color)});
			}
		}
		settingsHeightNow = settingsHeight(cells.size(), colors.size());
		int[] panel = currentSettingsPanel();
		int x = panel[0] + 8;
		int y = panel[1] + 22;
		for (int i = 0; i < cells.size(); i++) {
			int cx = x + i % 2 * (half + 4);
			for (AbstractWidget widget : cells.get(i).widgets()) {
				widget.setPosition(cx, y + i / 2 * 22 + (widget instanceof EditBox ? 5 : 0));
				addRenderableWidget(widget);
				cx += widget.getWidth() + 4;
			}
		}
		y += (cells.size() + 1) / 2 * 22;
		for (Object[] color : colors) {
			@SuppressWarnings("unchecked")
			Consumer<Integer> set = (Consumer<Integer>) color[3];
			labels.add(new Label(Component.translatable((String) color[0]), x, y + 2));
			colorRows.add(new ColorRow(font, x, y + 12, (Integer) color[1], (int[]) color[2], chosen -> {
				set.accept(chosen);
				HudLayout.save();
			}, this::addRenderableWidget));
			y += 30;
		}
		int by = panel[1] + panel[3] - 26;
		addRenderableWidget(new FlatButton(x, by, half, 18, Component.translatable("orbit_cosmetic.hud.reset_scale"), null, FlatButton.Style.SECONDARY, b -> {
			for (Object item : single != null ? List.of(single) : List.copyOf(selection)) {
				if (item instanceof HudComponent component) {
					component.scale = 1;
				} else {
					FloatingChats.Window window = (FloatingChats.Window) item;
					window.w = 190;
					window.h = 150;
				}
			}
			HudLayout.save();
			rebuildWidgets();
		}));
		boolean group = single instanceof HudComponent component && HudLayout.linked(component) || single == null;
		Component removeLabel = Component.translatable(single instanceof FloatingChats.Window ? "orbit_cosmetic.hud.close_chat"
			: group ? "orbit_cosmetic.hud.remove_all" : "orbit_cosmetic.hud.remove");
		addRenderableWidget(new FlatButton(x + half + 4, by, half, 18, removeLabel, "trash", FlatButton.Style.DANGER, b -> removeSelection()));
	}

	private void componentCells(HudComponent component, int half, List<Cell> cells, List<Object[]> colors) {
		if (component.type.hasVariants()) {
			cells.add(cell(new FlatButton(0, 0, half, 18, setting("orbit_cosmetic.hud.style", component.type.variantLabel(component.variant)), null,
				FlatButton.Style.SECONDARY, b -> {
					int[] before = bounds(component);
					component.variant = (component.variant + 1) % component.type.variants.length;
					if (component.parent == null && component.dock == HudComponent.DOCK_NONE) {
						component.place(before[0], before[1], width, height);
					}
					if (!component.isArmorHotbar()) {
						component.dock = HudComponent.DOCK_NONE;
					}
					HudLayout.save();
					rebuildWidgets();
				})));
		}
		cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.menus", () -> component.inMenus, on -> component.inMenus = on, "orbit_cosmetic.hud.menus.tooltip")));
		cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.background", () -> component.background, on -> component.background = on, null)));
		cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.shown", () -> !component.hidden, on -> component.hidden = !on, null)));
		// Pinning a linked member pins its whole group.
		List<HudComponent> group = HudLayout.group(component);
		cells.add(cell(groupToggle(half, group, "orbit_cosmetic.hud.pinned", c -> c.pinned, (c, on) -> c.pinned = on)));
		cells.add(cell(makeRoomButton(half, List.of(component))));
		if (component.hasDetail()) {
			cells.add(cell(new FlatButton(0, 0, half, 18, setting("orbit_cosmetic.hud.detail", component.detail.label()), null, FlatButton.Style.SECONDARY, b -> {
				HudComponent.Detail[] details = HudComponent.Detail.values();
				component.detail = details[(component.detail.ordinal() + 1) % details.length];
				HudLayout.save();
				rebuildWidgets();
			})));
		}
		if (component.type == HudComponent.Type.ARMOR || component.type == HudComponent.Type.ITEM) {
			cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.hide_empty", () -> component.hideEmpty, on -> component.hideEmpty = on,
				"orbit_cosmetic.hud.hide_empty.tooltip")));
		}
		if (component.type == HudComponent.Type.ARMOR) {
			cells.add(cell(toggleButton(half, "orbit_cosmetic.hud.hide_empty_slots", () -> component.hideEmptySlots, on -> component.hideEmptySlots = on,
				"orbit_cosmetic.hud.hide_empty_slots.tooltip")));
		}
		if (component.type == HudComponent.Type.PAPERDOLL) {
			// Its turn, typed in degrees, or 15 at a time with the arrows.
			EditBox angle = new EditBox(font, 0, 0, half - 44 - 8, 10, Component.translatable("orbit_cosmetic.hud.turn.label"));
			angle.setBordered(false);
			angle.setMaxLength(4);
			angle.setTextColor(Theme.TEXT);
			angle.setValue(String.valueOf(component.rotation));
			angle.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.turn.tooltip")));
			angleBox = angle;
			angle.setResponder(text -> {
				try {
					component.rotation = Math.floorMod(Integer.parseInt(text) + 180, 360) - 180;
					HudLayout.save();
				} catch (NumberFormatException ignored) {
					// Still typing ("-" or nothing).
				}
			});
			FlatButton left = FlatButton.icon(0, 0, 18, "chevron_left", Component.translatable("orbit_cosmetic.hud.turn.left"), FlatButton.Style.SECONDARY, b -> {
				component.rotation = Math.floorMod(component.rotation - 15 + 180, 360) - 180;
				angle.setValue(String.valueOf(component.rotation));
			});
			FlatButton right = FlatButton.icon(0, 0, 18, "chevron_right", Component.translatable("orbit_cosmetic.hud.turn.right"), FlatButton.Style.SECONDARY, b -> {
				component.rotation = Math.floorMod(component.rotation + 15 + 180, 360) - 180;
				angle.setValue(String.valueOf(component.rotation));
			});
			cells.add(new Cell(List.of(left, angle, right)));
		}
		colors.add(new Object[] {"orbit_cosmetic.hud.color", component.color, TEXT_COLORS, (Consumer<Integer>) color -> component.color = color});
		colors.add(new Object[] {"orbit_cosmetic.hud.accent", component.accent, ACCENT_COLORS, (Consumer<Integer>) color -> component.accent = color});
	}

	/** Off, the status effects, the boss bars, both: what the components move down out of the way of. */
	private FlatButton makeRoomButton(int width, List<HudComponent> components) {
		java.util.function.Supplier<Component> label = () -> setting("orbit_cosmetic.hud.make_room",
			Component.translatable("orbit_cosmetic.hud.make_room." + components.getFirst().avoid));
		FlatButton button = new FlatButton(0, 0, width, 18, label.get(), null, FlatButton.Style.SECONDARY, b -> {
			int next = (components.getFirst().avoid + 1) % 4;
			components.forEach(component -> component.avoid = next);
			HudLayout.save();
			b.setMessage(label.get());
		});
		button.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.make_room.tooltip")));
		return button;
	}

	private static Cell cell(AbstractWidget widget) {
		return new Cell(List.of(widget));
	}

	private FlatButton toggleButton(int width, String key, java.util.function.BooleanSupplier get, Consumer<Boolean> set, @Nullable String tooltip) {
		FlatButton button = new FlatButton(0, 0, width, 18, toggle(key, get.getAsBoolean()), null, FlatButton.Style.SECONDARY, b -> {
			set.accept(!get.getAsBoolean());
			HudLayout.save();
			b.setMessage(toggle(key, get.getAsBoolean()));
		});
		if (tooltip != null) {
			button.setTooltip(Tooltip.create(Component.translatable(tooltip)));
		}
		return button;
	}

	/** A switch for several components: on when all are, a click turns all on (or all off when all were on). */
	private FlatButton groupToggle(int width, List<HudComponent> components, String key, Function<HudComponent, Boolean> get,
		java.util.function.BiConsumer<HudComponent, Boolean> set) {
		java.util.function.BooleanSupplier all = () -> components.stream().allMatch(get::apply);
		return toggleButton(width, key, all, on -> components.forEach(component -> set.accept(component, on)),
			key.equals("orbit_cosmetic.hud.pinned") ? "orbit_cosmetic.hud.pinned.tooltip" : null);
	}

	private static Component toggle(String key, boolean on) {
		return setting(key, on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private static Component setting(String key, Component value) {
		return Component.translatable(key).append(": ").append(value);
	}

	// ---------------------------------------------------------------- mouse

	private @Nullable Object itemAt(double mouseX, double mouseY) {
		List<Object> items = items();
		for (int i = items.size() - 1; i >= 0; i--) {
			Object item = items.get(i);
			int[] b = bounds(item);
			if (visible(item) && Theme.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
				return item;
			}
		}
		return null;
	}

	/** The corner of what is picked that resizes it. */
	private boolean onGrip(double mouseX, double mouseY) {
		if (selection.isEmpty()) {
			return false;
		}
		int[] b = bounds(selection);
		int right = b[0] + b[2];
		int bottom = b[1] + b[3];
		return mouseX >= right - GRIP - 1 && mouseX < right + 2 && mouseY >= bottom - GRIP - 1 && mouseY < bottom + 2;
	}

	private boolean overPanel(double mouseX, double mouseY) {
		if (mode != Mode.EDIT) {
			int[] panel = menuPanel();
			return Theme.inside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3]);
		}
		if (!selection.isEmpty() && drag == Drag.NONE) {
			int[] panel = currentSettingsPanel();
			return Theme.inside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3]);
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (overPanel(event.x(), event.y())) {
			return true;
		}
		if (mode != Mode.EDIT) {
			// A click outside the menu closes it.
			setMode(Mode.EDIT);
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		LinkSpot link = linkAt(event.x(), event.y());
		if (link != null) {
			if (link.linked()) {
				HudLayout.unlink(link.a(), link.b(), width, height);
			} else {
				HudLayout.link(link.a(), link.b(), width, height);
				select(withGroup(link.a()));
			}
			rebuildWidgets();
			return true;
		}
		startX = (int) event.x();
		startY = (int) event.y();
		if (onGrip(event.x(), event.y())) {
			beginDrag(Drag.RESIZE);
			return true;
		}
		Object item = itemAt(event.x(), event.y());
		if (item == null) {
			// Empty space: a rectangle to pick with (Shift adds to what is picked).
			if (!event.hasShiftDown()) {
				selection.clear();
				focused = null;
			}
			drag = Drag.RECTANGLE;
			rebuildWidgets();
			return true;
		}
		if (event.hasShiftDown()) {
			List<Object> group = withGroup(item);
			if (selection.contains(item)) {
				group.forEach(selection::remove);
			} else {
				selection.addAll(group);
			}
			focused = null;
		} else if (selection.contains(item)) {
			// Clicked again: that member's own settings (a linked group stays picked to move together).
			focused = selection.size() > 1 && item instanceof HudComponent component && HudLayout.linked(component) ? item : null;
		} else {
			select(withGroup(item));
		}
		raise(item);
		setFocused(null);
		if (!selection.isEmpty()) {
			beginDrag(Drag.MOVE);
		}
		rebuildWidgets();
		return true;
	}

	private void beginDrag(Drag kind) {
		drag = kind;
		startPositions.clear();
		startScales.clear();
		startBounds = bounds(selection);
		for (Object mover : movers()) {
			int[] b = bounds(mover);
			startPositions.put(mover, new int[] {b[0], b[1], b[2], b[3]});
		}
		for (HudComponent component : selectedComponents()) {
			startScales.put(component, component.scale);
		}
		pendingDock = HudComponent.DOCK_NONE;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		mouseX = (int) event.x();
		mouseY = (int) event.y();
		switch (drag) {
			case MOVE -> {
				dragMove(mouseX - startX, mouseY - startY);
				return true;
			}
			case RESIZE -> {
				dragResize();
				return true;
			}
			case RECTANGLE -> {
				return true;
			}
			default -> {
				return super.mouseDragged(event, dx, dy);
			}
		}
	}

	private void dragMove(int dx, int dy) {
		int[] b = startBounds;
		int x = Math.clamp(b[0] + dx, 0, Math.max(0, width - b[2]));
		int y = Math.clamp(b[1] + dy, 0, Math.max(0, height - b[3]));
		guideX = -1;
		guideY = -1;
		pendingDock = HudComponent.DOCK_NONE;
		// Hotbar armour picked alone: it docks beside the hotbar, either side of the off hand slot.
		HudComponent dockable = selection.size() == 1 && selection.iterator().next() instanceof HudComponent component && component.isArmorHotbar()
			&& !HudLayout.linked(component) ? component : null;
		if (dockable != null) {
			for (int dock : new int[] {HudComponent.DOCK_OPPOSITE, HudComponent.DOCK_BESIDE}) {
				int[] at = HudLayout.dockPosition(dockable, dock, width, height);
				if (Math.abs(at[0] - x) <= SNAP * 2 && Math.abs(at[1] - y) <= SNAP * 2) {
					pendingDock = dock;
					x = at[0];
					y = at[1];
				}
			}
		}
		if (pendingDock == HudComponent.DOCK_NONE) {
			int[] snappedX = snap(x, b[2], width, true, false);
			int[] snappedY = snap(y, b[3], height, false, false);
			x = snappedX[0];
			y = snappedY[0];
			guideX = snappedX[1];
			guideY = snappedY[1];
		}
		int moveX = x - b[0];
		int moveY = y - b[1];
		for (Map.Entry<Object, int[]> entry : startPositions.entrySet()) {
			int[] at = entry.getValue();
			moveTo(entry.getKey(), at[0] + moveX, at[1] + moveY);
		}
		if (dockable != null && pendingDock != HudComponent.DOCK_NONE) {
			dockable.dock = pendingDock;
		}
	}

	private void dragResize() {
		int[] b = startBounds;
		Object single = selection.size() == 1 ? selection.iterator().next() : null;
		if (single instanceof FloatingChats.Window window) {
			int[] w = snap(mouseX, 0, width, true, true);
			int[] h = snap(mouseY, 0, height, false, true);
			window.w = Math.clamp(w[0] - b[0], FloatingChats.MIN_W, Math.max(FloatingChats.MIN_W, width - b[0]));
			window.h = Math.clamp(h[0] - b[1], FloatingChats.MIN_H, Math.max(FloatingChats.MIN_H, height - b[1]));
			guideX = w[1];
			guideY = h[1];
			return;
		}
		float factor = Math.max((mouseX - b[0]) / (float) Math.max(1, b[2]), (mouseY - b[1]) / (float) Math.max(1, b[3]));
		// Steps of a twentieth, and a whole size snaps (for a single component).
		if (startScales.size() == 1) {
			float start = startScales.values().iterator().next();
			float scale = Math.round(start * factor * 20) / 20F;
			if (Math.abs(scale - Math.round(scale)) < 0.08F) {
				scale = Math.round(scale);
			}
			factor = scale / start;
		}
		for (Map.Entry<HudComponent, Float> entry : startScales.entrySet()) {
			entry.getKey().scale = Math.clamp(entry.getValue() * factor, HudComponent.MIN_SCALE, HudComponent.MAX_SCALE);
		}
		// Everything picked grows from the outline's top left corner.
		for (Map.Entry<Object, int[]> entry : startPositions.entrySet()) {
			if (entry.getKey() instanceof HudComponent component) {
				int[] at = entry.getValue();
				int dock = component.dock;
				component.place(Math.round(b[0] + (at[0] - b[0]) * factor), Math.round(b[1] + (at[1] - b[1]) * factor), width, height);
				component.dock = dock;
			}
		}
	}

	/**
	 * The position snapped (or as it is) and where its guide line goes (-1 for none). With {@code edge}, the
	 * position is a right or bottom edge being dragged (a chat window's corner) and {@code size} is 0.
	 */
	private int[] snap(int position, int size, int screen, boolean horizontal, boolean edge) {
		List<int[]> targets = new ArrayList<>();
		// {where its start would go, where the guide is}
		targets.add(new int[] {0, -1});
		targets.add(new int[] {screen - size, -1});
		if (!edge) {
			targets.add(new int[] {screen / 2 - size / 2, screen / 2});
		}
		for (Object other : items()) {
			if (selection.contains(other) || !visible(other)) {
				continue;
			}
			int[] b = bounds(other);
			int start = horizontal ? b[0] : b[1];
			int end = start + (horizontal ? b[2] : b[3]);
			targets.add(new int[] {start, start});
			targets.add(new int[] {end - size, end});
			if (edge) {
				targets.add(new int[] {start - 2, start - 1});
			} else {
				targets.add(new int[] {end + 2, end + 1});
				targets.add(new int[] {start - size - 2, start - 1});
			}
		}
		int best = SNAP + 1;
		int[] result = {position, -1};
		for (int[] target : targets) {
			int distance = Math.abs(target[0] - position);
			if (distance < best && target[0] >= 0 && target[0] + size <= screen) {
				best = distance;
				result = target;
			}
		}
		return result;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (drag == Drag.RECTANGLE) {
			int[] r = rectangle();
			if (r[2] > 3 || r[3] > 3) {
				for (Object item : items()) {
					int[] b = bounds(item);
					boolean touches = b[0] < r[0] + r[2] && b[0] + b[2] > r[0] && b[1] < r[1] + r[3] && b[1] + b[3] > r[1];
					if (visible(item) && touches) {
						selection.addAll(withGroup(item));
					}
				}
			}
			drag = Drag.NONE;
			rebuildWidgets();
			return true;
		}
		if (drag != Drag.NONE) {
			drag = Drag.NONE;
			guideX = -1;
			guideY = -1;
			settle();
			rebuildWidgets();
			return true;
		}
		return super.mouseReleased(event);
	}

	private int[] rectangle() {
		int x0 = Math.min(startX, mouseX);
		int y0 = Math.min(startY, mouseY);
		return new int[] {x0, y0, Math.abs(mouseX - startX), Math.abs(mouseY - startY)};
	}

	@Override
	public void mouseMoved(double x, double y) {
		mouseX = (int) x;
		mouseY = (int) y;
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mode == Mode.ADD_CHAT || mode == Mode.PROFILES || mode == Mode.LAYERS) {
			listScroll -= (int) Math.signum(scrollY);
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = getFocused() instanceof EditBox;
		if (!typing && !selection.isEmpty() && mode == Mode.EDIT) {
			if (event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) {
				removeSelection();
				return true;
			}
			int step = event.hasShiftDown() ? 10 : 1;
			int dx = event.key() == InputConstants.KEY_LEFT ? -step : event.key() == InputConstants.KEY_RIGHT ? step : 0;
			int dy = event.key() == InputConstants.KEY_UP ? -step : event.key() == InputConstants.KEY_DOWN ? step : 0;
			if (dx != 0 || dy != 0) {
				for (Object mover : movers()) {
					int[] b = bounds(mover);
					moveTo(mover, b[0] + dx, b[1] + dy);
				}
				settle();
				rebuildWidgets();
				return true;
			}
		}
		if (event.key() == InputConstants.KEY_ESCAPE && mode != Mode.EDIT) {
			setMode(Mode.EDIT);
			return true;
		}
		if (profileName != null && profileName.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			HudLayout.rename(HudLayout.activeProfile(), profileName.getValue());
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		HudLayout.save();
		super.onClose();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (inWorld()) {
			// The game shows through, a little darker.
			graphics.fill(0, 0, width, height, 0x40000000);
		} else {
			Theme.background(graphics, width, height);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		HudInput.frame();
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		Object hovered = mode == Mode.EDIT && drag == Drag.NONE && !overPanel(mouseX, mouseY) ? itemAt(mouseX, mouseY) : null;
		for (HudComponent component : HudLayout.components()) {
			if (visible(component)) {
				component.draw(graphics, width, height, true, partialTick);
			}
		}
		if (FloatingChats.available()) {
			FloatingChats.drawPlaced(graphics);
		}
		graphics.nextStratum();
		List<Object> hoveredGroup = hovered != null ? withGroup(hovered) : List.of();
		for (Object item : items()) {
			if (!visible(item)) {
				continue;
			}
			int[] b = bounds(item);
			if (item instanceof HudComponent component && (component.hidden || HudLayout.allHidden())) {
				// Hidden: dimmed, still there to move and set up.
				graphics.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xA0101012);
				graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.hidden"), b[0] + b[2] / 2, b[1] + (b[3] - 8) / 2, Theme.MUTED);
			}
			if (selection.contains(item)) {
				graphics.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, item == focused ? Theme.TEXT : Theme.RED);
			} else {
				Theme.dashed(graphics, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, hoveredGroup.contains(item) ? Theme.TEXT : 0x80FFFFFF);
			}
			if (item instanceof HudComponent component) {
				if (component.inMenus) {
					graphics.fill(b[0] + b[2] - 3, b[1] - 1, b[0] + b[2] + 1, b[1] + 3, Theme.BLUE);
				}
				if (component.pinned) {
					graphics.fill(b[0] - 1, b[1] - 1, b[0] + 3, b[1] + 3, Theme.GOLD);
				}
			} else if (item instanceof FloatingChats.Window window && window.pinned) {
				graphics.fill(b[0] - 1, b[1] - 1, b[0] + 3, b[1] + 3, Theme.GOLD);
			}
		}
		if (!selection.isEmpty()) {
			int[] b = bounds(selection);
			if (selection.size() > 1) {
				Theme.dashed(graphics, b[0] - 3, b[1] - 3, b[2] + 6, b[3] + 6, 0xC0FF2626);
			}
			graphics.fill(b[0] + b[2] - GRIP + 1, b[1] + b[3] - GRIP + 1, b[0] + b[2] + 1, b[1] + b[3] + 1, Theme.RED);
		}
		if (mode == Mode.EDIT && drag == Drag.NONE) {
			for (LinkSpot spot : linkSpots()) {
				if (showsLink(spot, mouseX, mouseY)) {
					drawLink(graphics, spot, Math.abs(mouseX - spot.x()) <= LINK / 2 + 1 && Math.abs(mouseY - spot.y()) <= LINK / 2 + 1);
				}
			}
		}
		if (drag == Drag.RECTANGLE) {
			int[] r = rectangle();
			graphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x20FF2626);
			graphics.outline(r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3]), 0xA0FF2626);
		}
		if (guideX >= 0) {
			graphics.fill(guideX, 0, guideX + 1, height, 0x80FF2626);
		}
		if (guideY >= 0) {
			graphics.fill(0, guideY, width, guideY + 1, 0x80FF2626);
		}
		if (items().isEmpty() && mode == Mode.EDIT) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.empty").withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 4, Theme.TEXT);
		}
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.hint").withStyle(ChatFormatting.GRAY), width / 2, toolbarY() == 6 ? 28 : height - 36,
			Theme.TEXT);
		graphics.nextStratum();
		if (mode != Mode.EDIT) {
			drawMenu(graphics);
		} else if (!selection.isEmpty() && drag == Drag.NONE) {
			drawSettings(graphics);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** A link button: two chain links, filled in the accent red when linked, faint when not (a click links). */
	private static void drawLink(GuiGraphicsExtractor graphics, LinkSpot spot, boolean hot) {
		int x = spot.x() - LINK / 2;
		int y = spot.y() - LINK / 2;
		graphics.fill(x, y, x + LINK, y + LINK, spot.linked() ? 0xFFB81B1B : hot ? Theme.PANEL_HOVER : 0xE01C1C20);
		graphics.outline(x, y, LINK, LINK, spot.linked() || hot ? Theme.TEXT : Theme.BORDER_LIGHT);
		int color = spot.linked() || hot ? Theme.TEXT : Theme.MUTED;
		// Two small rings, crossing in the middle.
		graphics.outline(x + 1, y + 2, 4, 3, color);
		graphics.outline(x + 4, y + 4, 4, 3, color);
	}

	private void drawSettings(GuiGraphicsExtractor graphics) {
		int[] panel = currentSettingsPanel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
		Object single = single();
		Component title;
		String size;
		if (single instanceof HudComponent component) {
			title = component.type.label();
			size = Math.round(component.scale * 100) + "%";
		} else if (single instanceof FloatingChats.Window window) {
			title = Component.translatable("orbit_cosmetic.hud.chat.title", window.name());
			size = window.w + "×" + window.h;
		} else {
			boolean group = !selection.isEmpty() && selection.iterator().next() instanceof HudComponent first && selection.containsAll(HudLayout.group(first))
				&& HudLayout.group(first).size() == selection.size();
			title = Component.translatable(group ? "orbit_cosmetic.hud.group" : "orbit_cosmetic.hud.several", selection.size());
			size = "";
		}
		graphics.text(font, Theme.clip(font, title, panel[2] - 24 - font.width(size)), panel[0] + 8, panel[1] + 8, Theme.TEXT, false);
		graphics.text(font, size, panel[0] + panel[2] - 8 - font.width(size), panel[1] + 8, Theme.MUTED, false);
		if (angleBox != null) {
			// The paperdoll's angle field, framed like a button.
			EditBox box = angleBox;
			graphics.fill(box.getX() - 4, box.getY() - 5, box.getX() + box.getWidth() + 4, box.getY() + 13, Theme.PANEL_2);
			graphics.outline(box.getX() - 4, box.getY() - 5, box.getWidth() + 8, 18, box.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
			graphics.text(font, "°", box.getX() + box.getWidth() - 2, box.getY(), Theme.MUTED, false);
		}
		for (Label label : labels) {
			graphics.text(font, label.text(), label.x(), label.y(), Theme.MUTED, false);
		}
		for (ColorRow row : colorRows) {
			row.draw(graphics);
		}
	}

	private void drawMenu(GuiGraphicsExtractor graphics) {
		int[] panel = menuPanel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
		String key = switch (mode) {
			case ADD -> "orbit_cosmetic.hud.add";
			case ADD_CHAT -> "orbit_cosmetic.hud.chat.pick";
			case LAYERS -> "orbit_cosmetic.hud.layers";
			default -> "orbit_cosmetic.hud.profiles";
		};
		graphics.text(font, Component.translatable(key), panel[0] + 8, panel[1] + 8, Theme.TEXT, false);
		if (mode == Mode.ADD) {
			for (Card card : cards) {
				drawCard(graphics, card);
			}
			return;
		}
		if (mode == Mode.LAYERS) {
			List<HudComponent> layers = layers();
			if (layers.isEmpty()) {
				graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.empty.short").withStyle(ChatFormatting.GRAY), panel[0] + panel[2] / 2, panel[1] + 28,
					Theme.TEXT);
			}
			for (int i = 0; i < listRows.size(); i++) {
				Hotspot row = listRows.get(i);
				HudComponent component = layers.get(listScroll + i);
				if (row.isHoveredOrFocused()) {
					graphics.fill(row.getX(), row.getY(), row.getRight(), row.getBottom(), Theme.PANEL_2);
				}
				// Its accent, its name and style, and what it is linked to or pinned in.
				graphics.fill(row.getX() + 3, row.getY() + 3, row.getX() + 5, row.getBottom() - 3, 0xFF000000 | component.accent);
				Component label = component.type.hasVariants() ? component.type.label().copy().append(" · ").append(component.type.variantLabel(component.variant))
					: component.type.label();
				boolean dim = component.hidden || !component.available() && inWorld();
				graphics.text(font, Theme.clip(font, label, row.getWidth() - 40), row.getX() + 10, row.getY() + 4, dim ? Theme.FAINT : Theme.TEXT, false);
				int mark = row.getRight() - 4;
				if (component.pinned) {
					graphics.fill(mark - 4, row.getY() + 5, mark, row.getY() + 9, Theme.GOLD);
					mark -= 7;
				}
				if (HudLayout.linked(component)) {
					graphics.outline(mark - 6, row.getY() + 4, 4, 3, Theme.MUTED);
					graphics.outline(mark - 3, row.getY() + 6, 4, 3, Theme.MUTED);
				}
				row.drawFocus(graphics);
			}
			return;
		}
		if (mode == Mode.PROFILES) {
			List<HudLayout.Profile> profiles = HudLayout.profiles();
			for (int i = 0; i < listRows.size(); i++) {
				Hotspot row = listRows.get(i);
				HudLayout.Profile profile = profiles.get(listScroll + i);
				boolean current = profile == HudLayout.activeProfile();
				if (row.isHoveredOrFocused() || current) {
					graphics.fill(row.getX(), row.getY(), row.getRight(), row.getBottom(), current ? Theme.PANEL_HOVER : Theme.PANEL_2);
				}
				graphics.text(font, Theme.clip(font, Component.literal(profile.name), LIST_W - 40), row.getX() + 6, row.getY() + 4, Theme.TEXT, false);
				if (current) {
					Theme.icon(graphics, "check_small", row.getRight() - 12, row.getY() + 3, Theme.GREEN);
				}
				row.drawFocus(graphics);
			}
			if (profileName != null) {
				graphics.fill(profileName.getX() - 4, profileName.getY() - 4, profileName.getX() + profileName.getWidth() + 4, profileName.getY() + 14, Theme.PANEL_2);
				graphics.outline(profileName.getX() - 4, profileName.getY() - 4, profileName.getWidth() + 8, 18, profileName.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
			}
			return;
		}
		if (conversations.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.chat.none").withStyle(ChatFormatting.GRAY), panel[0] + panel[2] / 2, panel[1] + 28,
				Theme.TEXT);
		}
		for (int i = 0; i < listRows.size(); i++) {
			Hotspot row = listRows.get(i);
			Social.Person person = conversations.get(listScroll + i);
			if (row.isHoveredOrFocused()) {
				graphics.fill(row.getX(), row.getY(), row.getRight(), row.getBottom(), Theme.PANEL_2);
			}
			SocialPopups.face(graphics, person.uuid(), person.name(), row.getX() + 3, row.getY() + 2, 11);
			graphics.text(font, Theme.clip(font, Component.literal(person.name()), LIST_W - 50), row.getX() + 19, row.getY() + 4, Theme.TEXT, false);
			if (FloatingChats.has(person.uuid())) {
				Theme.icon(graphics, "check_small", row.getRight() - 12, row.getY() + 3, Theme.GREEN);
			}
			row.drawFocus(graphics);
		}
	}

	/** A card of the add menu: a small live preview of the component, its name under it. */
	private void drawCard(GuiGraphicsExtractor graphics, Card card) {
		Hotspot spot = card.spot();
		int x = spot.getX();
		int y = spot.getY();
		int cardW = spot.getWidth();
		int cardH = spot.getHeight();
		boolean on = card.type() != null || FloatingChats.available();
		boolean hot = on && spot.isHoveredOrFocused();
		graphics.fill(x, y, x + cardW, y + cardH, hot ? Theme.PANEL_HOVER : Theme.PANEL_2);
		graphics.outline(x, y, cardW, cardH, hot ? Theme.BORDER_LIGHT : Theme.BORDER);
		int areaW = cardW - 8;
		int areaH = cardH - 18;
		graphics.enableScissor(x + 1, y + 1, x + cardW - 1, y + cardH - 13);
		if (card.type() != null) {
			HudComponent sample = SAMPLES.computeIfAbsent(card.type(), HudComponent::sample);
			float scale = Math.min(1.25F, Math.min(areaW / (float) sample.width(), areaH / (float) sample.height()));
			int sw = Math.round(sample.width() * scale);
			int sh = Math.round(sample.height() * scale);
			sample.drawAt(graphics, x + (cardW - sw) / 2, y + 4 + (areaH - sh) / 2, scale, true);
		} else {
			drawChatSample(graphics, x + (cardW - 50) / 2, y + 5, on);
		}
		graphics.disableScissor();
		graphics.centeredText(font, Theme.clip(font, spot.getMessage(), cardW - 6), x + cardW / 2, y + cardH - 11, on ? Theme.TEXT : Theme.FAINT);
		spot.drawFocus(graphics);
	}

	/** A tiny chat window, for the chat window card. */
	private static void drawChatSample(GuiGraphicsExtractor graphics, int x, int y, boolean on) {
		int w = 50;
		int h = 36;
		int alpha = on ? 0xFF000000 : 0x80000000;
		graphics.fill(x, y, x + w, y + h, Theme.PANEL & 0xFFFFFF | alpha);
		graphics.outline(x, y, w, h, Theme.BORDER);
		graphics.fill(x, y, x + 2, y + h, 0xFF000000 | FloatingChats.DEFAULT_ACCENT);
		graphics.fill(x + 1, y + 8, x + w - 1, y + 9, Theme.BORDER);
		graphics.fill(x + 5, y + 2, x + 10, y + 7, Theme.MUTED);
		graphics.fill(x + 12, y + 4, x + 30, y + 5, Theme.MUTED);
		graphics.fill(x + 5, y + 12, x + 28, y + 18, 0xFF34343C);
		graphics.fill(x + 22, y + 21, x + w - 5, y + 27, 0xFF000000 | FloatingChats.DEFAULT_ACCENT);
		graphics.fill(x + 4, y + h - 6, x + w - 4, y + h - 3, Theme.PANEL_2);
	}
}
