package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.hud.HudLayout;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conversations popped out of the Social screen into small windows over the game (only in a world). In gameplay
 * they are drawn on the HUD and hide after 30 seconds without news; with the chat or the pause menu open they can
 * be moved (title bar), resized (bottom right corner), scrolled and typed in (the message box). "–" hides a window
 * until the next message or until it is clicked (the pause menu shows hidden windows see-through; a click on the title bar shows one again), "×" closes it.
 */
public final class FloatingChats {
	static final int TITLE = 16;
	static final int INPUT = 14;
	public static final int MIN_W = 130;
	public static final int MIN_H = 90;
	private static final int GRIP = 7;
	private static final long AUTO_HIDE_MS = 30_000;
	/** The default accent: the player's own bubbles and the bar on the window's left. */
	public static final int DEFAULT_ACCENT = 0xB81B1B;
	private static final int THEIRS = 0xFF34343C;

	/** One floating conversation (placed in the HUD components editor like the components). */
	public static final class Window {
		final UUID friend;
		final String name;
		public int x;
		public int y;
		public int w = 190;
		public int h = 150;
		/** Hides 30 seconds after the last news while playing (else it stays). */
		public boolean autoHide = true;
		public int accent = DEFAULT_ACCENT;
		/** In every HUD profile (else only in the profile it was opened in). */
		public boolean pinned;
		/** Hidden with "–" until a new message. */
		boolean hidden;
		long active = System.currentTimeMillis();
		int scroll;
		int maxScroll;
		@Nullable String lastId;

		Window(UUID friend, String name) {
			this.friend = friend;
			this.name = name;
		}

		public UUID friend() {
			return friend;
		}

		public String name() {
			return name;
		}

		boolean canWrite() {
			return !Social.isOrbit(friend);
		}

		int inputX() {
			return x + 4;
		}

		int inputY() {
			return y + h - INPUT - 4;
		}

		int inputW() {
			return w - 8 - GRIP;
		}
	}

	private static final List<Window> WINDOWS = new ArrayList<>();
	private static @Nullable Window dragging;
	private static boolean resizing;
	private static int dragX;
	private static int dragY;

	private FloatingChats() {
	}

	/** Whether a conversation can be popped out now: in a world. */
	public static boolean available() {
		return Minecraft.getInstance().level != null;
	}

	/** Pops a conversation out (or shows its window again). */
	public static void open(UUID friend, String name) {
		Window window = find(friend);
		if (window == null) {
			window = new Window(friend, name);
			Minecraft minecraft = Minecraft.getInstance();
			int offset = WINDOWS.size() % 5 * 14;
			window.x = Math.max(0, minecraft.getWindow().getGuiScaledWidth() - window.w - 8 - offset);
			window.y = 30 + offset;
			List<Social.Entry> chat = OrbitClient.get().social().chat(friend);
			window.lastId = chat.isEmpty() ? null : chat.getLast().id();
		} else {
			WINDOWS.remove(window);
		}
		window.hidden = false;
		window.active = System.currentTimeMillis();
		WINDOWS.add(window);
		HudLayout.save();
	}

	/** The windows, last drawn on top (the HUD components editor moves and resizes them). */
	public static List<Window> windows() {
		HudLayout.components();
		return WINDOWS;
	}

	public static void close(Window window) {
		WINDOWS.remove(window);
		HudLayout.save();
	}

	/** Brings a window to the front. */
	public static void raise(Window window) {
		if (WINDOWS.remove(window)) {
			WINDOWS.add(window);
		}
	}

	/** The open windows, pinned or not, for the HUD components file. */
	public static JsonArray toJson(boolean pinned) {
		JsonArray array = new JsonArray();
		for (Window window : WINDOWS) {
			if (window.pinned != pinned) {
				continue;
			}
			JsonObject json = new JsonObject();
			json.addProperty("friend", window.friend.toString());
			json.addProperty("name", window.name);
			json.addProperty("x", window.x);
			json.addProperty("y", window.y);
			json.addProperty("w", window.w);
			json.addProperty("h", window.h);
			json.addProperty("autoHide", window.autoHide);
			json.addProperty("accent", window.accent);
			if (window.lastId != null) {
				json.addProperty("last", window.lastId);
			}
			array.add(json);
		}
		return array;
	}

	/** Another HUD profile's windows in place of the ones not pinned. */
	public static void replaceUnpinned(JsonArray array) {
		WINDOWS.removeIf(window -> !window.pinned);
		restore(array, false);
	}

	/** The windows open when the game was last closed: back where they were, hidden until news (if they auto-hide). */
	public static void restore(JsonArray array, boolean pinned) {
		for (var element : array) {
			try {
				JsonObject json = element.getAsJsonObject();
				UUID friend = UUID.fromString(json.get("friend").getAsString());
				if (find(friend) != null || WINDOWS.size() >= 16) {
					continue;
				}
				Window window = new Window(friend, json.get("name").getAsString());
				window.x = json.get("x").getAsInt();
				window.y = json.get("y").getAsInt();
				window.w = Math.max(MIN_W, json.get("w").getAsInt());
				window.h = Math.max(MIN_H, json.get("h").getAsInt());
				window.autoHide = !json.has("autoHide") || json.get("autoHide").getAsBoolean();
				window.accent = json.has("accent") ? json.get("accent").getAsInt() & 0xFFFFFF : DEFAULT_ACCENT;
				window.lastId = json.has("last") ? json.get("last").getAsString() : null;
				window.active = 0;
				window.pinned = pinned;
				WINDOWS.add(window);
			} catch (RuntimeException e) {
				OrbitClient.LOGGER.warn("Skipped a saved chat window: {}", e.toString());
			}
		}
	}

	/** Draws every window as it is placed (hidden ones too), for the HUD components editor. */
	public static void drawPlaced(GuiGraphicsExtractor graphics) {
		Social social = OrbitClient.get().social();
		Minecraft minecraft = Minecraft.getInstance();
		int screenW = minecraft.getWindow().getGuiScaledWidth();
		int screenH = minecraft.getWindow().getGuiScaledHeight();
		for (Window window : WINDOWS) {
			clampTo(window, screenW, screenH);
			graphics.nextStratum();
			drawWindow(graphics, window, social.chat(window.friend), -1, -1, true);
		}
	}

	private static void clampTo(Window window, int screenW, int screenH) {
		window.w = Math.min(window.w, screenW);
		window.h = Math.min(window.h, screenH);
		window.x = Math.clamp(window.x, 0, Math.max(0, screenW - window.w));
		window.y = Math.clamp(window.y, 0, Math.max(0, screenH - window.h));
	}

	/** True when this conversation has a floating window (it shows new messages, so no popup is needed). */
	public static boolean has(UUID friend) {
		return available() && find(friend) != null;
	}

	static @Nullable Window find(UUID friend) {
		return WINDOWS.stream().filter(window -> window.friend.equals(friend)).findFirst().orElse(null);
	}

	static boolean exists(Window window) {
		return WINDOWS.contains(window);
	}

	/** The screens the windows can be used over: the chat, the pause menu and the message box. */
	static boolean interactive(@Nullable Screen screen) {
		return available() && (screen instanceof ChatScreen || screen instanceof PauseScreen || screen instanceof FloatingChatInput);
	}

	public static void register() {
		// With the HUD components, under the chat (screens and notifications go over them).
		HudElementRegistry.attachElementAfter(dev.orbit.cosmetic.client.hud.HudRenderer.ID, Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "floating_chats"), (graphics, tick) -> {
			if (Compat.screen() == null && !Compat.hudHidden() && available()) {
				draw(graphics, -1, -1, false);
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// A new screen ends any drag (its button release goes to the new screen).
			dragging = null;
			if (!interactive(screen)) {
				return;
			}
			if (screen instanceof PauseScreen) {
				long now = System.currentTimeMillis();
				for (Window window : WINDOWS) {
					window.active = now;
				}
			}
			if (!(screen instanceof FloatingChatInput)) {
				// The message box draws the windows itself, under its text field.
				ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> draw(graphics, mouseX, mouseY, true));
			}
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !click(current, event.x(), event.y()));
			ScreenMouseEvents.allowMouseRelease(screen).register((current, event) -> {
				boolean was = dragging != null;
				dragging = null;
				if (was) {
					HudLayout.save();
				}
				return !was;
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((current, mouseX, mouseY, scrollX, scrollY) -> !scroll(mouseX, mouseY, scrollY));
		});
	}

	private static boolean shown(Window window, boolean interactive, long now) {
		return !window.hidden && (interactive || !window.autoHide || now - window.active < AUTO_HIDE_MS);
	}

	/** Hidden windows show see-through over the pause menu, where a click brings them back. */
	private static boolean ghosts() {
		return Compat.screen() instanceof PauseScreen;
	}

	/** Draws the windows; with {@code interactive}, hovers, drags and resizes follow the mouse. */
	static void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean interactive) {
		if (WINDOWS.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		int screenW = minecraft.getWindow().getGuiScaledWidth();
		int screenH = minecraft.getWindow().getGuiScaledHeight();
		long now = System.currentTimeMillis();
		Social social = OrbitClient.get().social();
		if (!interactive) {
			dragging = null;
		}
		follow(mouseX, mouseY, screenW, screenH);
		for (Window window : List.copyOf(WINDOWS)) {
			// News: the window shows again.
			List<Social.Entry> chat = social.chat(window.friend);
			String last = chat.isEmpty() ? null : chat.getLast().id();
			if (last != null && !last.equals(window.lastId)) {
				window.lastId = last;
				window.hidden = false;
				window.active = now;
				window.scroll = 0;
			}
			clampTo(window, screenW, screenH);
			if (shown(window, interactive, now)) {
				graphics.nextStratum();
				drawWindow(graphics, window, chat, interactive ? mouseX : -1, interactive ? mouseY : -1, interactive);
			} else if (window.hidden && interactive && ghosts()) {
				graphics.nextStratum();
				drawGhost(graphics, window, Theme.inside(mouseX, mouseY, window.x, window.y, window.w, TITLE));
			}
		}
	}

	private static void drawWindow(GuiGraphicsExtractor graphics, Window window, List<Social.Entry> chat, int mouseX, int mouseY, boolean interactive) {
		Font font = Minecraft.getInstance().font;
		int x = window.x;
		int y = window.y;
		int w = window.w;
		int h = window.h;
		Theme.panel(graphics, x, y, w, h, interactive ? Theme.PANEL : 0xE81C1C20);
		graphics.fill(x, y, x + 2, y + h, 0xFF000000 | window.accent);
		// Title bar, like the update window's: face and name, then minimize and close.
		graphics.fill(x + 1, y + TITLE - 1, x + w - 1, y + TITLE, Theme.BORDER);
		SocialPopups.face(graphics, window.friend, window.name, x + 4, y + 3, 10);
		graphics.text(font, Theme.clip(font, Component.literal(window.name), w - 56), x + 18, y + 4, Theme.TEXT, false);
		int minimizeX = x + w - 34;
		int closeX = x + w - 17;
		boolean minimizeHot = Theme.inside(mouseX, mouseY, minimizeX, y + 1, 16, TITLE - 2);
		boolean closeHot = Theme.inside(mouseX, mouseY, closeX, y + 1, 16, TITLE - 2);
		if (minimizeHot) {
			graphics.fill(minimizeX, y + 1, minimizeX + 16, y + TITLE - 1, Theme.PANEL_HOVER);
		}
		graphics.fill(minimizeX + 5, y + 8, minimizeX + 11, y + 9, minimizeHot ? Theme.TEXT : Theme.MUTED);
		if (closeHot) {
			graphics.fill(closeX, y + 1, closeX + 16, y + TITLE - 1, Theme.RED_DARK);
		}
		Theme.icon(graphics, "close", closeX + 4, y + 3, closeHot ? Theme.TEXT : Theme.MUTED);

		// The messages, newest at the bottom.
		int top = y + TITLE + 2;
		int bottom = window.canWrite() ? window.inputY() - 3 : y + h - 4;
		int bubbleMax = Math.max(40, (w - 12) * 4 / 5);
		UUID me = OrbitClient.get().social().me();
		graphics.enableScissor(x + 1, top, x + w - 1, bottom);
		int cy = bottom - 2 + window.scroll;
		int height = 0;
		for (int i = chat.size() - 1; i >= 0; i--) {
			Social.Entry entry = chat.get(i);
			boolean mine = me != null && entry.mine(me);
			List<Part> parts = parts(entry);
			for (int p = parts.size() - 1; p >= 0; p--) {
				Part part = parts.get(p);
				Component text = part.text();
				boolean grey = part.grey();
				List<FormattedCharSequence> lines = font.split(grey ? text.copy().withStyle(ChatFormatting.ITALIC) : text, bubbleMax - 8);
				int bw = 8;
				for (FormattedCharSequence line : lines) {
					bw = Math.max(bw, font.width(line) + 8);
				}
				int bh = lines.size() * 9 + 5;
				int gap = p == parts.size() - 1 ? 4 : 2;
				cy -= bh + gap;
				height += bh + gap;
				if (cy > bottom || cy + bh < top) {
					continue;
				}
				int bx = mine ? x + w - 6 - bw : x + 6;
				if (entry.encrypted() && !entry.deleted()) {
					// End-to-end encrypted: the dashed outline of the Social screen.
					Theme.dashed(graphics, bx - 2, cy - 2, bw + 4, bh + 4, 0xFFB0B0BA);
				}
				Theme.bubble(graphics, bx, cy, bw, bh, grey ? THEIRS : mine ? 0xFF000000 | window.accent : THEIRS, 1, mine);
				int ly = cy + 3;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, bx + 4, ly, grey ? Theme.MUTED : Theme.TEXT, false);
					ly += 9;
				}
				if (entry.edited() && !entry.deleted() && p == 0) {
					Component edited = Component.translatable("orbit_cosmetic.social.edited").withStyle(ChatFormatting.ITALIC);
					int ex = mine ? bx - 4 - font.width(edited) : bx + bw + 4;
					graphics.text(font, edited, ex, cy + bh - 9, Theme.FAINT, false);
				}
			}
		}
		graphics.disableScissor();
		window.maxScroll = Math.max(0, height - (bottom - top) + 4);
		window.scroll = Math.clamp(window.scroll, 0, window.maxScroll);
		if (chat.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.social.say_hi").withStyle(ChatFormatting.GRAY), x + w / 2, (top + bottom) / 2 - 4, Theme.TEXT);
		}

		// The message box (typed in on its own small screen) and the resize corner.
		if (window.canWrite()) {
			boolean typing = Compat.screen() instanceof FloatingChatInput input && input.window() == window;
			boolean hot = Theme.inside(mouseX, mouseY, window.inputX(), window.inputY(), window.inputW(), INPUT);
			Theme.panel(graphics, window.inputX(), window.inputY(), window.inputW(), INPUT, Theme.PANEL_2);
			if (typing || hot) {
				graphics.outline(window.inputX(), window.inputY(), window.inputW(), INPUT, Theme.BORDER_LIGHT);
			}
			if (!typing) {
				Component hint = Component.translatable("orbit_cosmetic.social.message_to", window.name).withStyle(ChatFormatting.DARK_GRAY);
				graphics.text(font, Theme.clip(font, hint, window.inputW() - 8), window.inputX() + 4, window.inputY() + 3, Theme.TEXT, false);
			}
		}
		int gx = x + w - GRIP;
		int gy = y + h - GRIP;
		int grip = Theme.inside(mouseX, mouseY, gx - 1, gy - 1, GRIP + 1, GRIP + 1) || dragging == window && resizing ? Theme.TEXT : Theme.FAINT;
		for (int i = 0; i < 3; i++) {
			graphics.fill(gx + 4 - i * 2, gy + 4, gx + 5 - i * 2, gy + 5, grip);
			if (i > 0) {
				graphics.fill(gx + 4, gy + 4 - i * 2, gx + 5, gy + 5 - i * 2, grip);
			}
		}
		graphics.fill(gx + 2, gy + 2, gx + 3, gy + 3, grip);
	}

	/** Moves or resizes the window being dragged to the mouse (until the button is released). */
	static void follow(int mouseX, int mouseY, int screenW, int screenH) {
		Window window = dragging;
		if (window == null) {
			return;
		}
		if (resizing) {
			window.w = Math.clamp(mouseX - window.x + dragX, MIN_W, Math.max(MIN_W, screenW - window.x));
			window.h = Math.clamp(mouseY - window.y + dragY, MIN_H, Math.max(MIN_H, screenH - window.y));
		} else {
			window.x = mouseX - dragX;
			window.y = mouseY - dragY;
		}
		window.active = System.currentTimeMillis();
	}

	/** A hidden window over the pause menu: see-through, its outline and name only. */
	private static void drawGhost(GuiGraphicsExtractor graphics, Window window, boolean hovered) {
		Font font = Minecraft.getInstance().font;
		int x = window.x;
		int y = window.y;
		graphics.fill(x, y, x + window.w, y + window.h, hovered ? 0x501C1C20 : 0x281C1C20);
		graphics.fill(x, y, x + window.w, y + TITLE, hovered ? 0x701C1C20 : 0x401C1C20);
		Theme.dashed(graphics, x, y, window.w, window.h, hovered ? 0xA0ECECEF : 0x50ECECEF);
		Component show = Component.translatable("orbit_cosmetic.social.floating.show");
		int showW = hovered ? font.width(show) + 8 : 0;
		graphics.text(font, Theme.clip(font, Component.literal(window.name), window.w - 12 - showW), x + 6, y + 4, hovered ? 0xC0ECECEF : 0x70ECECEF, false);
		if (hovered) {
			graphics.text(font, show, x + window.w - showW, y + 4, 0xC0A3A3AD, false);
		}
	}

	/** One bubble of a message: its text, or a grey line for what cannot show here. */
	private record Part(Component text, boolean grey) {
	}

	/**
	 * A message as the window shows it: its text, then a grey "*sent a screenshot*" (or cosmetic, emote, body part,
	 * invite) in place of what only the Social screen shows.
	 */
	private static List<Part> parts(Social.Entry entry) {
		if (entry.deleted()) {
			return List.of(new Part(Component.translatable("orbit_cosmetic.social.deleted"), true));
		}
		JsonObject content = entry.content();
		if (content == null) {
			return List.of(new Part(Component.translatable("orbit_cosmetic.social.unreadable"), true));
		}
		List<Part> parts = new ArrayList<>();
		String text = content.has("text") ? content.get("text").getAsString().strip() : "";
		if (!text.isEmpty()) {
			parts.add(new Part(Component.literal(text), false));
		}
		if (content.has("image")) {
			parts.add(new Part(Component.translatable("orbit_cosmetic.social.sent.screenshot"), true));
		}
		if (content.get("cosmetic") instanceof JsonObject cosmetic) {
			String kind = cosmetic.has("kind") ? cosmetic.get("kind").getAsString() : "cosmetic";
			parts.add(new Part(Component.translatable("orbit_cosmetic.social.sent." + (kind.equals("emote") ? "emote" : kind.equals("bodypart") ? "body_part" : "cosmetic")), true));
		}
		if (content.has("invite")) {
			parts.add(new Part(Component.translatable("orbit_cosmetic.social.sent.invite"), true));
		}
		if (parts.isEmpty()) {
			parts.add(new Part(Component.literal(Social.preview(content)), false));
		}
		return parts;
	}

	private static @Nullable Window windowAt(double mouseX, double mouseY) {
		long now = System.currentTimeMillis();
		boolean ghosts = ghosts();
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			Window window = WINDOWS.get(i);
			// A see-through hidden window only takes clicks on its title bar: the menu under it stays usable.
			boolean ghost = window.hidden && ghosts && Theme.inside(mouseX, mouseY, window.x, window.y, window.w, TITLE);
			if (ghost || shown(window, true, now) && Theme.inside(mouseX, mouseY, window.x, window.y, window.w, window.h)) {
				return window;
			}
		}
		return null;
	}

	/** A click over the chat or pause menu: true when a window used it. */
	private static boolean click(Screen screen, double mouseX, double mouseY) {
		Window window = windowAt(mouseX, mouseY);
		if (window == null) {
			return false;
		}
		WINDOWS.remove(window);
		WINDOWS.add(window);
		window.active = System.currentTimeMillis();
		if (window.hidden) {
			// A see-through hidden window: a click brings it back.
			window.hidden = false;
			return true;
		}
		int x = window.x;
		int y = window.y;
		if (Theme.inside(mouseX, mouseY, x + window.w - 17, y + 1, 16, TITLE - 2)) {
			close(window);
			closeInput(screen, window);
		} else if (Theme.inside(mouseX, mouseY, x + window.w - 34, y + 1, 16, TITLE - 2)) {
			window.hidden = true;
			closeInput(screen, window);
		} else if (mouseY < y + TITLE) {
			dragging = window;
			resizing = false;
			dragX = (int) mouseX - x;
			dragY = (int) mouseY - y;
		} else if (mouseX >= x + window.w - GRIP - 1 && mouseY >= y + window.h - GRIP - 1) {
			dragging = window;
			resizing = true;
			dragX = x + window.w - (int) mouseX;
			dragY = y + window.h - (int) mouseY;
		} else if (window.canWrite() && Theme.inside(mouseX, mouseY, window.inputX(), window.inputY(), window.inputW(), INPUT)) {
			if (screen instanceof FloatingChatInput input && input.window() == window) {
				return false;
			}
			Screen back = screen instanceof FloatingChatInput input ? input.parent() : screen instanceof ChatScreen ? null : screen;
			Compat.setScreen(new FloatingChatInput(window, back));
		}
		return true;
	}

	private static void closeInput(Screen screen, Window window) {
		if (screen instanceof FloatingChatInput input && input.window() == window) {
			input.onClose();
		}
	}

	private static boolean scroll(double mouseX, double mouseY, double amount) {
		Window window = windowAt(mouseX, mouseY);
		if (window == null || window.hidden) {
			return false;
		}
		window.scroll = Math.clamp(window.scroll + (int) Math.round(amount * 12), 0, window.maxScroll);
		window.active = System.currentTimeMillis();
		return true;
	}
}
