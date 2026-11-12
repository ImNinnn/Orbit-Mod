package dev.orbit.cosmetic.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the HUD components: drag one to move it (it snaps to the screen's edges and centre and to the others),
 * drag its bottom right corner to scale it, click it for its settings. "+" adds a component, or pops a conversation
 * out as a chat window (moved and resized here too, like over the chat).
 */
public final class HudEditorScreen extends OrbitScreen {
	private static final int SNAP = 4;
	private static final int GRIP = 6;
	private static final int SETTINGS_W = ColorRow.WIDTH + 16;
	private static final int SETTINGS_H = 108;
	private static final int ADD_W = 260;
	private static final int ROW = 16;
	private static final int CHAT_ROWS = 8;
	/** The text colours offered: white and Orbit's red first. */
	private static final int[] TEXT_COLORS = {0xFFFFFF, 0xFF2626, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xA3A3AD};

	private enum Mode {
		EDIT, ADD, ADD_CHAT
	}

	private Mode mode = Mode.EDIT;
	private @Nullable HudComponent selected;
	private @Nullable HudComponent dragging;
	private boolean scaling;
	private int grabX;
	private int grabY;
	private @Nullable ColorRow colorRow;
	private final List<Hotspot> chatRows = new ArrayList<>();
	private List<Social.Person> conversations = List.of();
	/** The snapping lines shown while dragging: x of a vertical one, y of a horizontal one (-1 for none). */
	private int guideX = -1;
	private int guideY = -1;

	public HudEditorScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.hud.title"), parent);
	}

	public static void open(@Nullable Screen parent) {
		dev.orbit.cosmetic.client.compat.Compat.setScreen(new HudEditorScreen(parent));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private boolean inWorld() {
		return minecraft != null && minecraft.level != null;
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		colorRow = null;
		chatRows.clear();
		int bw = 110;
		int ty = toolbarY();
		addRenderableWidget(new FlatButton(width / 2 - bw - 2, ty, bw, 18, Component.translatable("orbit_cosmetic.hud.add"), "plus", FlatButton.Style.PRIMARY,
			b -> setMode(mode == Mode.EDIT ? Mode.ADD : Mode.EDIT)));
		addRenderableWidget(new FlatButton(width / 2 + 2, ty, bw, 18, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
		switch (mode) {
			case ADD -> initAdd();
			case ADD_CHAT -> initChats();
			case EDIT -> {
				if (selected != null && dragging == null) {
					initSettings(selected);
				}
			}
		}
	}

	/** The buttons and the hint go at the top, or at the bottom when a component is there under them. */
	private int toolbarY() {
		int x0 = width / 2 - 112;
		int x1 = width / 2 + 112;
		for (HudComponent component : HudLayout.components()) {
			int x = component.x(width);
			int y = component.y(height);
			if (x < x1 && x + component.scaledWidth() > x0 && y < 40 && (component.available() || !inWorld())) {
				return height - 24;
			}
		}
		return 6;
	}

	private void setMode(Mode mode) {
		this.mode = mode;
		if (mode != Mode.EDIT) {
			selected = null;
		}
		rebuildWidgets();
	}

	private int[] addPanel() {
		int types = HudComponent.Type.values().length + 1;
		int rows = mode == Mode.ADD_CHAT ? Math.max(1, Math.min(CHAT_ROWS, conversations.size())) : (types + 1) / 2;
		int h = 30 + rows * (mode == Mode.ADD_CHAT ? ROW : 22) + 28;
		return new int[] {(width - ADD_W) / 2, Math.max(30, (height - h) / 2), ADD_W, h};
	}

	private void initAdd() {
		int[] panel = addPanel();
		int x = panel[0] + 8;
		int y = panel[1] + 24;
		int half = (ADD_W - 16 - 4) / 2;
		int i = 0;
		for (HudComponent.Type type : HudComponent.Type.values()) {
			addRenderableWidget(new FlatButton(x + i % 2 * (half + 4), y + i / 2 * 22, half, 18, type.label(), null, FlatButton.Style.SECONDARY, b -> {
				selected = HudLayout.add(type, width, height);
				setMode(Mode.EDIT);
			}));
			i++;
		}
		FlatButton chat = addRenderableWidget(new FlatButton(x + i % 2 * (half + 4), y + i / 2 * 22, half, 18, Component.translatable("orbit_cosmetic.hud.type.chat"),
			"popout", FlatButton.Style.SECONDARY, b -> {
				conversations = OrbitClient.get().social().conversations();
				setMode(Mode.ADD_CHAT);
			}));
		chat.active = FloatingChats.available();
		if (!chat.active) {
			chat.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("orbit_cosmetic.hud.chat.world")));
		}
		addRenderableWidget(new FlatButton(panel[0] + 8, panel[1] + panel[3] - 26, ADD_W - 16, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.GHOST,
			b -> setMode(Mode.EDIT)));
	}

	private int chatScroll;

	private void initChats() {
		int[] panel = addPanel();
		int x = panel[0] + 8;
		int y = panel[1] + 24;
		chatScroll = Math.clamp(chatScroll, 0, Math.max(0, conversations.size() - CHAT_ROWS));
		for (int i = 0; i < CHAT_ROWS && chatScroll + i < conversations.size(); i++) {
			Social.Person person = conversations.get(chatScroll + i);
			Hotspot row = addRenderableWidget(new Hotspot(Component.literal(person.name()), h -> {
				FloatingChats.open(person.uuid(), person.name());
				setMode(Mode.EDIT);
			}));
			row.place(x, y + i * ROW, ADD_W - 16, ROW - 1);
			chatRows.add(row);
		}
		addRenderableWidget(new FlatButton(panel[0] + 8, panel[1] + panel[3] - 26, ADD_W - 16, 18, CommonComponents.GUI_BACK, null, FlatButton.Style.GHOST,
			b -> setMode(Mode.ADD)));
	}

	/** The settings panel sits beside the component (right of it if there is room, else left), on screen. */
	private int[] settingsPanel(HudComponent component) {
		int cx = component.x(width);
		int cy = component.y(height);
		int x = cx + component.scaledWidth() + 6;
		if (x + SETTINGS_W > width - 4) {
			x = cx - SETTINGS_W - 6;
		}
		if (x < 4) {
			// No room on either side: under it, or over it.
			x = Math.clamp(cx, 4, Math.max(4, width - SETTINGS_W - 4));
			int below = cy + component.scaledHeight() + 6;
			int y = below + SETTINGS_H <= height - 4 ? below : cy - SETTINGS_H - 6;
			return new int[] {x, Math.clamp(y, 28, Math.max(28, height - SETTINGS_H - 4)), SETTINGS_W, SETTINGS_H};
		}
		return new int[] {x, Math.clamp(cy, 28, Math.max(28, height - SETTINGS_H - 4)), SETTINGS_W, SETTINGS_H};
	}

	private void initSettings(HudComponent component) {
		int[] panel = settingsPanel(component);
		int x = panel[0] + 8;
		int y = panel[1] + 22;
		int half = (SETTINGS_W - 16 - 4) / 2;
		addRenderableWidget(new FlatButton(x, y, half, 18, toggle("orbit_cosmetic.hud.menus", component.inMenus), null, FlatButton.Style.SECONDARY, b -> {
			component.inMenus = !component.inMenus;
			HudLayout.save();
			b.setMessage(toggle("orbit_cosmetic.hud.menus", component.inMenus));
		})).setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("orbit_cosmetic.hud.menus.tooltip")));
		addRenderableWidget(new FlatButton(x + half + 4, y, half, 18, toggle("orbit_cosmetic.hud.background", component.background), null, FlatButton.Style.SECONDARY, b -> {
			component.background = !component.background;
			HudLayout.save();
			b.setMessage(toggle("orbit_cosmetic.hud.background", component.background));
		}));
		colorRow = new ColorRow(font, x, y + 36, component.color, TEXT_COLORS, color -> {
			component.color = color;
			HudLayout.save();
		}, this::addRenderableWidget);
		addRenderableWidget(new FlatButton(x, panel[1] + panel[3] - 26, half, 18, Component.translatable("orbit_cosmetic.hud.reset_scale"), null, FlatButton.Style.SECONDARY,
			b -> {
				component.scale = 1;
				HudLayout.save();
				rebuildWidgets();
			}));
		addRenderableWidget(new FlatButton(x + half + 4, panel[1] + panel[3] - 26, half, 18, Component.translatable("orbit_cosmetic.hud.remove"), "trash",
			FlatButton.Style.DANGER, b -> remove(component)));
	}

	private static Component toggle(String key, boolean on) {
		return Component.translatable(key).append(": ").append(on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private void remove(HudComponent component) {
		HudLayout.remove(component);
		selected = null;
		rebuildWidgets();
	}

	// ---------------------------------------------------------------- mouse

	private @Nullable HudComponent componentAt(double mouseX, double mouseY) {
		List<HudComponent> components = HudLayout.components();
		for (int i = components.size() - 1; i >= 0; i--) {
			HudComponent component = components.get(i);
			if (Theme.inside(mouseX, mouseY, component.x(width), component.y(height), component.scaledWidth(), component.scaledHeight())) {
				return component;
			}
		}
		return null;
	}

	private boolean onGrip(HudComponent component, double mouseX, double mouseY) {
		int right = component.x(width) + component.scaledWidth();
		int bottom = component.y(height) + component.scaledHeight();
		return mouseX >= right - GRIP && mouseX < right + 2 && mouseY >= bottom - GRIP && mouseY < bottom + 2;
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
		HudComponent grip = selected != null && onGrip(selected, event.x(), event.y()) ? selected : null;
		HudComponent component = grip != null ? grip : componentAt(event.x(), event.y());
		if (component == null) {
			if (selected != null) {
				selected = null;
				rebuildWidgets();
			}
			return false;
		}
		// The one clicked comes to the front.
		HudLayout.components().remove(component);
		HudLayout.components().add(component);
		selected = component;
		dragging = component;
		scaling = grip != null || onGrip(component, event.x(), event.y());
		grabX = (int) event.x() - component.x(width);
		grabY = (int) event.y() - component.y(height);
		setFocused(null);
		rebuildWidgets();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging == null) {
			return super.mouseDragged(event, dx, dy);
		}
		HudComponent component = dragging;
		if (scaling) {
			int left = component.x(width);
			int top = component.y(height);
			float scale = Math.max((float) (event.x() - left) / component.width(), (float) (event.y() - top) / component.height());
			// Steps of a twentieth, and a whole size snaps.
			scale = Math.round(scale * 20) / 20F;
			if (Math.abs(scale - Math.round(scale)) < 0.08F) {
				scale = Math.round(scale);
			}
			component.scale = Math.clamp(scale, HudComponent.MIN_SCALE, HudComponent.MAX_SCALE);
			component.place(left, top, width, height);
			return true;
		}
		int w = component.scaledWidth();
		int h = component.scaledHeight();
		int x = Math.clamp((int) event.x() - grabX, 0, Math.max(0, width - w));
		int y = Math.clamp((int) event.y() - grabY, 0, Math.max(0, height - h));
		guideX = -1;
		guideY = -1;
		// Snap: the edges and middle of the screen, and the others' edges (side by side or lined up).
		int[] snappedX = snap(x, w, width, true, component);
		int[] snappedY = snap(y, h, height, false, component);
		component.place(snappedX[0], snappedY[0], width, height);
		guideX = snappedX[1];
		guideY = snappedY[1];
		return true;
	}

	/** The position snapped (or as it is) and where its guide line goes (-1 for none). */
	private int[] snap(int position, int size, int screen, boolean horizontal, HudComponent moving) {
		List<int[]> targets = new ArrayList<>();
		// {where its start would go, where the guide is}
		targets.add(new int[] {0, -1});
		targets.add(new int[] {screen - size, -1});
		targets.add(new int[] {screen / 2 - size / 2, screen / 2});
		for (HudComponent other : HudLayout.components()) {
			if (other == moving || !other.available() && inWorld()) {
				continue;
			}
			int start = horizontal ? other.x(width) : other.y(height);
			int end = start + (horizontal ? other.scaledWidth() : other.scaledHeight());
			targets.add(new int[] {start, start});
			targets.add(new int[] {end - size, end});
			targets.add(new int[] {end + 2, end + 1});
			targets.add(new int[] {start - size - 2, start - 1});
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
			scaling = false;
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
				selected.place(selected.x(width) + dx, selected.y(height) + dy, width, height);
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
		if (guideX >= 0) {
			graphics.fill(guideX, 0, guideX + 1, height, 0x80FF2626);
		}
		if (guideY >= 0) {
			graphics.fill(0, guideY, width, guideY + 1, 0x80FF2626);
		}
		HudComponent hovered = mode == Mode.EDIT && !overPanel(mouseX, mouseY) ? componentAt(mouseX, mouseY) : null;
		for (HudComponent component : HudLayout.components()) {
			component.draw(graphics, width, height, true, partialTick);
			int x = component.x(width);
			int y = component.y(height);
			int w = component.scaledWidth();
			int h = component.scaledHeight();
			boolean isSelected = component == selected;
			if (isSelected) {
				graphics.outline(x - 1, y - 1, w + 2, h + 2, Theme.RED);
				graphics.fill(x + w - GRIP + 1, y + h - GRIP + 1, x + w + 1, y + h + 1, Theme.RED);
			} else {
				Theme.dashed(graphics, x - 1, y - 1, w + 2, h + 2, component == hovered ? Theme.TEXT : 0x80FFFFFF);
			}
			if (component.inMenus) {
				graphics.fill(x + w - 3, y - 1, x + w + 1, y + 3, Theme.BLUE);
			}
		}
		if (HudLayout.components().isEmpty() && mode == Mode.EDIT) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.empty").withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 4, Theme.TEXT);
		}
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.hint").withStyle(ChatFormatting.GRAY), width / 2, toolbarY() == 6 ? 28 : height - 36, Theme.TEXT);
		graphics.nextStratum();
		if (mode != Mode.EDIT) {
			drawAdd(graphics);
		} else if (selected != null && dragging == null) {
			int[] panel = settingsPanel(selected);
			Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
			graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
			graphics.text(font, selected.type.label(), panel[0] + 8, panel[1] + 8, Theme.TEXT, false);
			String scale = Math.round(selected.scale * 100) + "%";
			graphics.text(font, scale, panel[0] + panel[2] - 8 - font.width(scale), panel[1] + 8, Theme.MUTED, false);
			graphics.text(font, Component.translatable("orbit_cosmetic.hud.color"), panel[0] + 8, panel[1] + 46, Theme.MUTED, false);
			if (colorRow != null) {
				colorRow.draw(graphics);
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawAdd(GuiGraphicsExtractor graphics) {
		int[] panel = addPanel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.RED);
		graphics.text(font, Component.translatable(mode == Mode.ADD ? "orbit_cosmetic.hud.add" : "orbit_cosmetic.hud.chat.pick"), panel[0] + 8, panel[1] + 8, Theme.TEXT,
			false);
		if (mode != Mode.ADD_CHAT) {
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
			graphics.text(font, Theme.clip(font, Component.literal(person.name()), ADD_W - 50), row.getX() + 19, row.getY() + 4, Theme.TEXT, false);
			if (FloatingChats.has(person.uuid())) {
				Theme.icon(graphics, "check_small", row.getRight() - 12, row.getY() + 3, Theme.GREEN);
			}
			row.drawFocus(graphics);
		}
	}
}
