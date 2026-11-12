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
import java.util.List;
import java.util.Map;

/**
 * Places the HUD components and the chat windows: drag one to move it (it snaps to the screen's edges and centre and
 * to the others), drag its bottom right corner to resize it, click it for its settings. "+" adds a component (cards
 * with a preview of each), or pops a conversation out as a chat window.
 */
public final class HudEditorScreen extends OrbitScreen {
	private static final int SNAP = 4;
	private static final int GRIP = 6;
	private static final int SETTINGS_W = ColorRow.WIDTH + 16;
	private static final int CARD_W = 78;
	private static final int CARD_MAX_H = 58;
	private static final int CHAT_W = 260;
	private static final int ROW = 16;
	private static final int CHAT_ROWS = 8;
	/** The text colours offered: white and Orbit's red first. */
	private static final int[] TEXT_COLORS = {0xFFFFFF, 0xFF2626, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xA3A3AD};
	/** The accent colours offered: Orbit's red first. */
	private static final int[] ACCENT_COLORS = {0xFF2626, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xD63B8A, 0xFFFFFF};
	private static final int[] CHAT_ACCENTS = {FloatingChats.DEFAULT_ACCENT, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xD63B8A, 0x5A5A66};
	private static final Map<HudComponent.Type, HudComponent> SAMPLES = new EnumMap<>(HudComponent.Type.class);

	private enum Mode {
		EDIT, ADD, ADD_CHAT
	}

	/** A label drawn above a colour row of the settings. */
	private record Label(Component text, int x, int y) {
	}

	/** A card of the add menu: a component type, or a chat window when null. */
	private record Card(HudComponent.@Nullable Type type, Hotspot spot) {
	}

	private Mode mode = Mode.EDIT;
	/** The component or chat window picked (a {@link HudComponent} or a {@link FloatingChats.Window}). */
	private @Nullable Object selected;
	private @Nullable Object dragging;
	private boolean resizing;
	private int grabX;
	private int grabY;
	private final List<ColorRow> colorRows = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	private final List<Hotspot> chatRows = new ArrayList<>();
	private final List<Card> cards = new ArrayList<>();
	private List<Social.Person> conversations = List.of();
	private int chatScroll;
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
			return new int[] {component.x(width), component.y(height), component.scaledWidth(), component.scaledHeight()};
		}
		FloatingChats.Window window = (FloatingChats.Window) item;
		return new int[] {window.x, window.y, window.w, window.h};
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

	private void raise(Object item) {
		if (item instanceof HudComponent component) {
			HudLayout.components().remove(component);
			HudLayout.components().add(component);
		} else {
			FloatingChats.raise((FloatingChats.Window) item);
		}
	}

	private void remove(Object item) {
		if (item instanceof HudComponent component) {
			HudLayout.remove(component);
		} else {
			FloatingChats.close((FloatingChats.Window) item);
		}
		selected = null;
		rebuildWidgets();
	}

	private boolean visible(Object item) {
		return !(item instanceof HudComponent component) || component.available() || !inWorld();
	}

	// ---------------------------------------------------------------- widgets

	/** The buttons and the hint go at the top, or at the bottom when something is there under them. */
	private int toolbarY() {
		int x0 = width / 2 - 112;
		int x1 = width / 2 + 112;
		for (Object item : items()) {
			int[] b = bounds(item);
			if (visible(item) && b[0] < x1 && b[0] + b[2] > x0 && b[1] < 40) {
				return height - 24;
			}
		}
		return 6;
	}

	@Override
	protected void init() {
		colorRows.clear();
		labels.clear();
		chatRows.clear();
		cards.clear();
		int bw = 110;
		int ty = toolbarY();
		addRenderableWidget(new FlatButton(width / 2 - bw - 2, ty, bw, 18, Component.translatable("orbit_cosmetic.hud.add"), "plus", FlatButton.Style.PRIMARY,
			b -> setMode(mode == Mode.EDIT ? Mode.ADD : Mode.EDIT)));
		addRenderableWidget(new FlatButton(width / 2 + 2, ty, bw, 18, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
		switch (mode) {
			case ADD -> initAdd();
			case ADD_CHAT -> initChats();
			case EDIT -> {
				if (selected != null && dragging == null && items().contains(selected)) {
					initSettings(selected);
				} else if (dragging == null) {
					selected = null;
				}
			}
		}
	}

	private void setMode(Mode mode) {
		this.mode = mode;
		if (mode != Mode.EDIT) {
			selected = null;
		}
		rebuildWidgets();
	}

	private int columns() {
		return Math.clamp((width - 40) / (CARD_W + 4), 3, 6);
	}

	private int cardRows() {
		int count = HudComponent.Type.values().length + 1;
		return (count + columns() - 1) / columns();
	}

	/** The cards shrink on short windows, so the menu stays clear of the buttons. */
	private int cardH() {
		int room = height - 8 - 28 - 24 - 32;
		return Math.clamp((room - (cardRows() - 1) * 4) / cardRows(), 40, CARD_MAX_H);
	}

	/** The add menu's panel: the cards, or the conversations. */
	private int[] addPanel() {
		if (mode == Mode.ADD_CHAT) {
			int rows = Math.max(1, Math.min(CHAT_ROWS, conversations.size()));
			int h = 30 + rows * ROW + 28;
			return new int[] {(width - CHAT_W) / 2, Math.max(30, (height - h) / 2), CHAT_W, h};
		}
		int w = columns() * (CARD_W + 4) - 4 + 16;
		int h = 24 + cardRows() * (cardH() + 4) - 4 + 32;
		// Clear of the toolbar, at the top or the bottom.
		int top = toolbarY() == 6 ? 28 : 4;
		int bottom = toolbarY() == 6 ? height - 4 : height - 28;
		return new int[] {(width - w) / 2, Math.max(top, Math.min((height - h) / 2, bottom - h)), w, h};
	}

	private void initAdd() {
		int[] panel = addPanel();
		int columns = columns();
		List<HudComponent.Type> types = new ArrayList<>(List.of(HudComponent.Type.values()));
		types.add(null);
		for (int i = 0; i < types.size(); i++) {
			HudComponent.Type type = types.get(i);
			int x = panel[0] + 8 + i % columns * (CARD_W + 4);
			int y = panel[1] + 22 + i / columns * (cardH() + 4);
			Component label = type != null ? type.label() : Component.translatable("orbit_cosmetic.hud.type.chat");
			Hotspot spot = addRenderableWidget(new Hotspot(label, h -> {
				if (type != null) {
					selected = HudLayout.add(type, width, height);
					mode = Mode.EDIT;
					rebuildWidgets();
				} else if (FloatingChats.available()) {
					conversations = OrbitClient.get().social().conversations();
					setMode(Mode.ADD_CHAT);
				}
			}));
			spot.place(x, y, CARD_W, cardH());
			if (type == null && !FloatingChats.available()) {
				spot.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.chat.world")));
			}
			cards.add(new Card(type, spot));
		}
		addRenderableWidget(new FlatButton(panel[0] + 8, panel[1] + panel[3] - 26, panel[2] - 16, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.GHOST,
			b -> setMode(Mode.EDIT)));
	}

	private void initChats() {
		int[] panel = addPanel();
		int x = panel[0] + 8;
		int y = panel[1] + 24;
		chatScroll = Math.clamp(chatScroll, 0, Math.max(0, conversations.size() - CHAT_ROWS));
		for (int i = 0; i < CHAT_ROWS && chatScroll + i < conversations.size(); i++) {
			Social.Person person = conversations.get(chatScroll + i);
			Hotspot row = addRenderableWidget(new Hotspot(Component.literal(person.name()), h -> {
				FloatingChats.open(person.uuid(), person.name());
				mode = Mode.EDIT;
				selected = FloatingChats.windows().getLast();
				rebuildWidgets();
			}));
			row.place(x, y + i * ROW, CHAT_W - 16, ROW - 1);
			chatRows.add(row);
		}
		addRenderableWidget(new FlatButton(panel[0] + 8, panel[1] + panel[3] - 26, CHAT_W - 16, 18, CommonComponents.GUI_BACK, null, FlatButton.Style.GHOST,
			b -> setMode(Mode.ADD)));
	}

	/** How tall the settings are: the title, the buttons (two a row), the colour rows and the bottom buttons. */
	private int settingsHeight(Object item) {
		int cells;
		int colors;
		if (item instanceof HudComponent component) {
			cells = 2 + (component.type.hasVariants() ? 1 : 0) + (component.hasDetail() ? 1 : 0);
			colors = 2;
		} else {
			cells = 1;
			colors = 1;
		}
		return 22 + (cells + 1) / 2 * 22 + colors * 30 + 30;
	}

	/** The settings panel sits beside what it is for (right of it if there is room, else left), on screen. */
	private int[] settingsPanel(Object item) {
		int[] b = bounds(item);
		int h = settingsHeight(item);
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
		return new int[] {x, Math.clamp(y, 4, Math.max(4, height - h - 4)), SETTINGS_W, h};
	}

	private void initSettings(Object item) {
		int[] panel = settingsPanel(item);
		int x = panel[0] + 8;
		int half = (SETTINGS_W - 16 - 4) / 2;
		List<FlatButton> cells = new ArrayList<>();
		if (item instanceof HudComponent component) {
			if (component.type.hasVariants()) {
				cells.add(new FlatButton(0, 0, half, 18, setting("orbit_cosmetic.hud.style", component.type.variantLabel(component.variant)), null,
					FlatButton.Style.SECONDARY, b -> {
						int[] before = bounds(component);
						component.variant = (component.variant + 1) % component.type.variants.length;
						component.place(before[0], before[1], width, height);
						HudLayout.save();
						rebuildWidgets();
					}));
			}
			FlatButton menus = new FlatButton(0, 0, half, 18, toggle("orbit_cosmetic.hud.menus", component.inMenus), null, FlatButton.Style.SECONDARY, b -> {
				component.inMenus = !component.inMenus;
				HudLayout.save();
				b.setMessage(toggle("orbit_cosmetic.hud.menus", component.inMenus));
			});
			menus.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.menus.tooltip")));
			cells.add(menus);
			cells.add(new FlatButton(0, 0, half, 18, toggle("orbit_cosmetic.hud.background", component.background), null, FlatButton.Style.SECONDARY, b -> {
				component.background = !component.background;
				HudLayout.save();
				b.setMessage(toggle("orbit_cosmetic.hud.background", component.background));
			}));
			if (component.hasDetail()) {
				cells.add(new FlatButton(0, 0, half, 18, setting("orbit_cosmetic.hud.detail", component.detail.label()), null, FlatButton.Style.SECONDARY, b -> {
					HudComponent.Detail[] details = HudComponent.Detail.values();
					component.detail = details[(component.detail.ordinal() + 1) % details.length];
					HudLayout.save();
					rebuildWidgets();
				}));
			}
		} else {
			FloatingChats.Window window = (FloatingChats.Window) item;
			FlatButton hide = new FlatButton(0, 0, half, 18, toggle("orbit_cosmetic.hud.auto_hide", window.autoHide), null, FlatButton.Style.SECONDARY, b -> {
				window.autoHide = !window.autoHide;
				HudLayout.save();
				b.setMessage(toggle("orbit_cosmetic.hud.auto_hide", window.autoHide));
			});
			hide.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.hud.auto_hide.tooltip")));
			cells.add(hide);
		}
		int y = panel[1] + 22;
		for (int i = 0; i < cells.size(); i++) {
			FlatButton cell = cells.get(i);
			cell.setPosition(x + i % 2 * (half + 4), y + i / 2 * 22);
			addRenderableWidget(cell);
		}
		y += (cells.size() + 1) / 2 * 22;
		if (item instanceof HudComponent component) {
			y = colorRow(x, y, "orbit_cosmetic.hud.color", component.color, TEXT_COLORS, color -> component.color = color);
			colorRow(x, y, "orbit_cosmetic.hud.accent", component.accent, ACCENT_COLORS, color -> component.accent = color);
		} else {
			FloatingChats.Window window = (FloatingChats.Window) item;
			colorRow(x, y, "orbit_cosmetic.hud.accent", window.accent, CHAT_ACCENTS, color -> window.accent = color);
		}
		int by = panel[1] + panel[3] - 26;
		addRenderableWidget(new FlatButton(x, by, half, 18, Component.translatable("orbit_cosmetic.hud.reset_scale"), null, FlatButton.Style.SECONDARY, b -> {
			if (item instanceof HudComponent component) {
				component.scale = 1;
			} else {
				FloatingChats.Window window = (FloatingChats.Window) item;
				window.w = 190;
				window.h = 150;
			}
			HudLayout.save();
			rebuildWidgets();
		}));
		Component removeLabel = Component.translatable(item instanceof HudComponent ? "orbit_cosmetic.hud.remove" : "orbit_cosmetic.hud.close_chat");
		addRenderableWidget(new FlatButton(x + half + 4, by, half, 18, removeLabel, "trash", FlatButton.Style.DANGER, b -> remove(item)));
	}

	/** A label and a colour row under it; returns where the next row goes. */
	private int colorRow(int x, int y, String key, int color, int[] swatches, java.util.function.IntConsumer set) {
		labels.add(new Label(Component.translatable(key), x, y + 2));
		colorRows.add(new ColorRow(font, x, y + 12, color, swatches, chosen -> {
			set.accept(chosen);
			HudLayout.save();
		}, this::addRenderableWidget));
		return y + 30;
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

	private boolean onGrip(Object item, double mouseX, double mouseY) {
		int[] b = bounds(item);
		int right = b[0] + b[2];
		int bottom = b[1] + b[3];
		return mouseX >= right - GRIP - 1 && mouseX < right + 2 && mouseY >= bottom - GRIP - 1 && mouseY < bottom + 2;
	}

	private boolean overPanel(double mouseX, double mouseY) {
		if (mode != Mode.EDIT) {
			int[] panel = addPanel();
			return Theme.inside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3]);
		}
		if (selected != null) {
			int[] panel = settingsPanel(selected);
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
			// A click outside the add menu closes it.
			setMode(Mode.EDIT);
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		Object grip = selected != null && onGrip(selected, event.x(), event.y()) ? selected : null;
		Object item = grip != null ? grip : itemAt(event.x(), event.y());
		if (item == null) {
			if (selected != null) {
				selected = null;
				rebuildWidgets();
			}
			return false;
		}
		raise(item);
		selected = item;
		dragging = item;
		resizing = grip != null || onGrip(item, event.x(), event.y());
		int[] b = bounds(item);
		grabX = (int) event.x() - (resizing ? b[0] + b[2] : b[0]);
		grabY = (int) event.y() - (resizing ? b[1] + b[3] : b[1]);
		setFocused(null);
		rebuildWidgets();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging == null) {
			return super.mouseDragged(event, dx, dy);
		}
		Object item = dragging;
		int[] b = bounds(item);
		if (resizing) {
			int right = (int) event.x() - grabX;
			int bottom = (int) event.y() - grabY;
			if (item instanceof HudComponent component) {
				float scale = Math.max((float) (right - b[0]) / component.width(), (float) (bottom - b[1]) / component.height());
				// Steps of a twentieth, and a whole size snaps.
				scale = Math.round(scale * 20) / 20F;
				if (Math.abs(scale - Math.round(scale)) < 0.08F) {
					scale = Math.round(scale);
				}
				component.scale = Math.clamp(scale, HudComponent.MIN_SCALE, HudComponent.MAX_SCALE);
				component.place(b[0], b[1], width, height);
			} else {
				FloatingChats.Window window = (FloatingChats.Window) item;
				int[] w = snap(right, 0, width, true, item, true);
				int[] h = snap(bottom, 0, height, false, item, true);
				window.w = Math.clamp(w[0] - b[0], FloatingChats.MIN_W, Math.max(FloatingChats.MIN_W, width - b[0]));
				window.h = Math.clamp(h[0] - b[1], FloatingChats.MIN_H, Math.max(FloatingChats.MIN_H, height - b[1]));
				guideX = w[1];
				guideY = h[1];
			}
			return true;
		}
		int x = Math.clamp((int) event.x() - grabX, 0, Math.max(0, width - b[2]));
		int y = Math.clamp((int) event.y() - grabY, 0, Math.max(0, height - b[3]));
		// Snap: the edges and middle of the screen, and the others' edges (side by side or lined up).
		int[] snappedX = snap(x, b[2], width, true, item, false);
		int[] snappedY = snap(y, b[3], height, false, item, false);
		moveTo(item, snappedX[0], snappedY[0]);
		guideX = snappedX[1];
		guideY = snappedY[1];
		return true;
	}

	/**
	 * The position snapped (or as it is) and where its guide line goes (-1 for none). With {@code edge}, the
	 * position is a right or bottom edge being dragged (a chat window's corner) and {@code size} is 0.
	 */
	private int[] snap(int position, int size, int screen, boolean horizontal, Object moving, boolean edge) {
		List<int[]> targets = new ArrayList<>();
		// {where its start would go, where the guide is}
		targets.add(new int[] {0, -1});
		targets.add(new int[] {screen - size, -1});
		if (!edge) {
			targets.add(new int[] {screen / 2 - size / 2, screen / 2});
		}
		for (Object other : items()) {
			if (other == moving || !visible(other)) {
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
		if (dragging != null) {
			dragging = null;
			resizing = false;
			guideX = -1;
			guideY = -1;
			HudLayout.save();
			rebuildWidgets();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mode == Mode.ADD_CHAT) {
			chatScroll -= (int) Math.signum(scrollY);
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = getFocused() instanceof EditBox;
		if (!typing && selected != null && mode == Mode.EDIT) {
			if (event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) {
				remove(selected);
				return true;
			}
			int step = event.hasShiftDown() ? 10 : 1;
			int dx = event.key() == InputConstants.KEY_LEFT ? -step : event.key() == InputConstants.KEY_RIGHT ? step : 0;
			int dy = event.key() == InputConstants.KEY_UP ? -step : event.key() == InputConstants.KEY_DOWN ? step : 0;
			if (dx != 0 || dy != 0) {
				int[] b = bounds(selected);
				moveTo(selected, b[0] + dx, b[1] + dy);
				HudLayout.save();
				rebuildWidgets();
				return true;
			}
		}
		if (event.key() == InputConstants.KEY_ESCAPE && mode != Mode.EDIT) {
			setMode(Mode.EDIT);
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
		Object hovered = mode == Mode.EDIT && !overPanel(mouseX, mouseY) ? itemAt(mouseX, mouseY) : null;
		for (HudComponent component : HudLayout.components()) {
			if (visible(component)) {
				component.draw(graphics, width, height, true, partialTick);
			}
		}
		if (FloatingChats.available()) {
			FloatingChats.drawPlaced(graphics);
		}
		graphics.nextStratum();
		for (Object item : items()) {
			if (!visible(item)) {
				continue;
			}
			int[] b = bounds(item);
			if (item == selected) {
				graphics.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, Theme.RED);
				graphics.fill(b[0] + b[2] - GRIP + 1, b[1] + b[3] - GRIP + 1, b[0] + b[2] + 1, b[1] + b[3] + 1, Theme.RED);
			} else {
				Theme.dashed(graphics, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, item == hovered ? Theme.TEXT : 0x80FFFFFF);
			}
			if (item instanceof HudComponent component && component.inMenus) {
				graphics.fill(b[0] + b[2] - 3, b[1] - 1, b[0] + b[2] + 1, b[1] + 3, Theme.BLUE);
			}
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
			drawAdd(graphics);
		} else if (selected != null && dragging == null) {
			drawSettings(graphics, selected);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawSettings(GuiGraphicsExtractor graphics, Object item) {
		int[] panel = settingsPanel(item);
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
		Component title;
		String size;
		if (item instanceof HudComponent component) {
			title = component.type.label();
			size = Math.round(component.scale * 100) + "%";
		} else {
			FloatingChats.Window window = (FloatingChats.Window) item;
			title = Component.translatable("orbit_cosmetic.hud.chat.title", window.name());
			size = window.w + "×" + window.h;
		}
		graphics.text(font, Theme.clip(font, title, panel[2] - 24 - font.width(size)), panel[0] + 8, panel[1] + 8, Theme.TEXT, false);
		graphics.text(font, size, panel[0] + panel[2] - 8 - font.width(size), panel[1] + 8, Theme.MUTED, false);
		for (Label label : labels) {
			graphics.text(font, label.text(), label.x(), label.y(), Theme.MUTED, false);
		}
		for (ColorRow row : colorRows) {
			row.draw(graphics);
		}
	}

	private void drawAdd(GuiGraphicsExtractor graphics) {
		int[] panel = addPanel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
		graphics.text(font, Component.translatable(mode == Mode.ADD ? "orbit_cosmetic.hud.add" : "orbit_cosmetic.hud.chat.pick"), panel[0] + 8, panel[1] + 8, Theme.TEXT,
			false);
		if (mode == Mode.ADD) {
			for (Card card : cards) {
				drawCard(graphics, card);
			}
			return;
		}
		if (conversations.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.chat.none").withStyle(ChatFormatting.GRAY), panel[0] + panel[2] / 2, panel[1] + 28,
				Theme.TEXT);
		}
		for (int i = 0; i < chatRows.size(); i++) {
			Hotspot row = chatRows.get(i);
			Social.Person person = conversations.get(chatScroll + i);
			if (row.isHoveredOrFocused()) {
				graphics.fill(row.getX(), row.getY(), row.getRight(), row.getBottom(), Theme.PANEL_2);
			}
			SocialPopups.face(graphics, person.uuid(), person.name(), row.getX() + 3, row.getY() + 2, 11);
			graphics.text(font, Theme.clip(font, Component.literal(person.name()), CHAT_W - 50), row.getX() + 19, row.getY() + 4, Theme.TEXT, false);
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
		boolean on = card.type() != null || FloatingChats.available();
		int cardH = spot.getHeight();
		boolean hot = on && spot.isHoveredOrFocused();
		graphics.fill(x, y, x + CARD_W, y + cardH, hot ? Theme.PANEL_HOVER : Theme.PANEL_2);
		graphics.outline(x, y, CARD_W, cardH, hot ? Theme.BORDER_LIGHT : Theme.BORDER);
		int areaW = CARD_W - 8;
		int areaH = cardH - 18;
		graphics.enableScissor(x + 1, y + 1, x + CARD_W - 1, y + cardH - 13);
		if (card.type() != null) {
			HudComponent sample = SAMPLES.computeIfAbsent(card.type(), HudComponent::sample);
			float scale = Math.min(1.25F, Math.min(areaW / (float) sample.width(), areaH / (float) sample.height()));
			int sw = Math.round(sample.width() * scale);
			int sh = Math.round(sample.height() * scale);
			sample.drawAt(graphics, x + (CARD_W - sw) / 2, y + 4 + (areaH - sh) / 2, scale, true);
		} else {
			drawChatSample(graphics, x + (CARD_W - 50) / 2, y + 5, on);
		}
		graphics.disableScissor();
		graphics.centeredText(font, Theme.clip(font, spot.getMessage(), CARD_W - 6), x + CARD_W / 2, y + cardH - 11, on ? Theme.TEXT : Theme.FAINT);
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
