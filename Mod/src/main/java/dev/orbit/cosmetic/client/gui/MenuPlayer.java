package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.host.HostScreen;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.social.Social;
import dev.orbit.cosmetic.client.social.SocialScreen;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The player on the title and pause screens: your skin wearing your cosmetics, now and then playing one of your
 * emotes, with the Wardrobe button (and Players, in a world) under its feet. Opposite it, on the other side of the
 * vanilla buttons, is Orbit's stack of buttons: Host, Social, Screenshots, Settings and Statistics. With Essential,
 * which draws its own player there, the player moves to the left edge and the stack to the right edge.
 */
public final class MenuPlayer {
	private static final PreviewModel MODEL = new PreviewModel();
	private static final Random RANDOM = new Random();
	private static final boolean ESSENTIAL = FabricLoader.getInstance().isModLoaded("essential") || FabricLoader.getInstance().isModLoaded("essential-container");
	/**
	 * The Options button of each screen, and Orbit's buttons on it. Other mods (Essential, Mod Menu) may move the
	 * vanilla buttons after the screen is set up, so Orbit's follow the Options button every frame.
	 */
	private static final Map<Screen, AbstractWidget> OPTIONS = new WeakHashMap<>();
	private static final Map<Screen, List<FlatButton>> BUTTONS = new WeakHashMap<>();
	/** The buttons under the player: Wardrobe, and Players in a world. */
	private static final Map<Screen, List<FlatButton>> UNDER = new WeakHashMap<>();
	/** The arrow next to Settings while a newer Orbit can be installed. */
	private static final Map<Screen, FlatButton> UPDATE = new WeakHashMap<>();
	/** Menu time when the next random emote starts. */
	private static double nextEmote = 60;

	private MenuPlayer() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen || screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				OPTIONS.put(screen, optionsButton(screen));
				addButtons(screen);
				ScreenEvents.beforeExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> placeButtons(current));
				ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> draw(current, graphics, mouseX, mouseY, partialTick));
			}
		});
	}

	/** Keeps the files of the emote being shown loaded. */
	public static void assetsInUse(Set<AssetId> inUse) {
		if (MODEL.emote() != null) {
			inUse.add(MODEL.emote().asset);
		}
	}

	/** The screen's Options button, or null if it has none. */
	private static AbstractWidget optionsButton(Screen screen) {
		for (var widget : Screens.getWidgets(screen)) {
			if (widget instanceof AbstractWidget button && button.getMessage().getContents() instanceof TranslatableContents text && "menu.options".equals(text.getKey())) {
				return button;
			}
		}
		return null;
	}

	/**
	 * Where the player stands (centre, feet, GUI pixels per block, Options row): left of the vanilla buttons, on its
	 * buttons in the Options row, or nowhere on very narrow windows.
	 */
	private static int[] area(Screen screen) {
		int width = screen.width;
		int height = screen.height;
		int space = width / 2 - 110;
		if (space < 70) {
			return null;
		}
		int row = row(screen);
		int feetY = row - 6;
		int size = Math.min(56, Math.min(ESSENTIAL ? space / 4 : space / 2, height / 6));
		// The head (and raised arms) must stay on screen.
		size = Math.min(size, (feetY - 24) * 10 / 24);
		if (size < 12) {
			return null;
		}
		int centerX = ESSENTIAL ? size * 3 / 2 + 6 : space / 2 + 4;
		return new int[] {centerX, feetY, size, row};
	}

	/** The names that take a whole row of the stack (Statistics shares its row with the square Settings button). */
	private static final String[] STACK_LABELS = {"orbit_cosmetic.menu.host", "orbit_cosmetic.menu.social", "orbit_cosmetic.menu.hud", "orbit_cosmetic.menu.screenshots"};
	private static final int SETTINGS_INDEX = 4;
	private static final int STATS_INDEX = 5;

	/**
	 * How wide the stack's buttons are with their names, or 0 when a name would not fit whole: on small windows they
	 * are square, with only their icons (and their names as tooltips).
	 */
	private static int stackWidth(Screen screen) {
		int space = ESSENTIAL ? 120 : screen.width / 2 - 110;
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int needed = font.width(Component.translatable("orbit_cosmetic.menu.stats")) + 6 + 12 + 6 + 24;
		for (String key : STACK_LABELS) {
			needed = Math.max(needed, font.width(Component.translatable(key)) + 6 + 12 + 6);
		}
		int width = Math.min(Math.max(needed, 80), space - 16);
		return width >= needed && screen.height >= 200 ? width : 0;
	}

	/**
	 * The row Orbit lines up with: the Options button's on the title screen, the one under it (Save and Quit) on the
	 * pause menu.
	 */
	private static int row(Screen screen) {
		AbstractWidget options = OPTIONS.get(screen);
		if (options == null || !options.visible) {
			return screen.height / 2 + 66;
		}
		return options.getY() + (screen instanceof PauseScreen ? 24 : 0);
	}

	private static void addButtons(Screen screen) {
		List<FlatButton> buttons = new ArrayList<>();
		int width = stackWidth(screen);
		buttons.add(stackButton(width, "host", "orbit_cosmetic.menu.host", b -> Compat.setScreen(new HostScreen(screen))));
		buttons.add(stackButton(width, "social", "orbit_cosmetic.menu.social", b -> Compat.setScreen(new SocialScreen(screen))));
		buttons.add(stackButton(width, "hud", "orbit_cosmetic.menu.hud", b -> dev.orbit.cosmetic.client.hud.HudEditorScreen.open(screen)));
		buttons.add(stackButton(width, "image", "orbit_cosmetic.menu.screenshots", b -> Compat.setScreen(new ScreenshotsScreen(screen))));
		// A square button, beside Statistics.
		buttons.add(FlatButton.icon(0, 0, 20, "settings", Component.translatable("orbit_cosmetic.menu.settings"), FlatButton.Style.SECONDARY,
			b -> Compat.setScreen(new OrbitSettingsScreen(screen))));
		String update = dev.orbit.cosmetic.client.Updates.available();
		if (update != null) {
			FlatButton arrow = FlatButton.icon(0, 0, 20, "download", Component.translatable("orbit_cosmetic.update.button", update), FlatButton.Style.PRIMARY,
				b -> Compat.setScreen(new UpdatePromptScreen(screen)));
			Screens.getWidgets(screen).add(arrow);
			UPDATE.put(screen, arrow);
		}
		buttons.add(stackButton(width, "account", "orbit_cosmetic.menu.stats", b -> Compat.setScreen(new StatsScreen(screen))));
		List<FlatButton> under = new ArrayList<>();
		under.add(FlatButton.icon(0, 0, 20, "orbit", Component.translatable("orbit_cosmetic.menu.wardrobe"), FlatButton.Style.SECONDARY,
			b -> Compat.setScreen(new OrbitMenuScreen(screen))).iconColor(Theme.RED));
		if (screen instanceof PauseScreen) {
			FlatButton players = FlatButton.icon(0, 0, 20, "player", Component.translatable("orbit_cosmetic.players.button"), FlatButton.Style.SECONDARY,
				b -> Compat.setScreen(new PlayersScreen(screen)));
			players.active = PlayersScreen.available();
			under.add(players);
		}
		Screens.getWidgets(screen).addAll(under);
		Screens.getWidgets(screen).add(new Announcement(screen));
		Screens.getWidgets(screen).addAll(buttons);
		UNDER.put(screen, under);
		BUTTONS.put(screen, buttons);
		placeButtons(screen);
	}

	private static FlatButton stackButton(int width, String icon, String key, java.util.function.Consumer<FlatButton> onPress) {
		Component label = Component.translatable(key);
		if (width == 0) {
			return FlatButton.icon(0, 0, 20, icon, label, FlatButton.Style.SECONDARY, onPress);
		}
		FlatButton button = new FlatButton(0, 0, width, 20, label, icon, FlatButton.Style.SECONDARY, onPress).alignLeft();
		return button;
	}

	/**
	 * Puts Orbit's stack opposite the player, its bottom on the Options button's row (along the top on windows too
	 * small for it).
	 */
	private static void placeButtons(Screen screen) {
		List<FlatButton> under = UNDER.get(screen);
		if (under != null) {
			// Under the player, on the Options row (in the top left corner when there is no room for the player).
			int[] area = area(screen);
			int x = area != null ? area[0] - (under.size() * 24 - 4) / 2 : 4;
			int y = area != null ? area[3] : 4;
			for (FlatButton button : under) {
				button.setPosition(x, y);
				x += 24;
			}
		}
		List<FlatButton> buttons = BUTTONS.get(screen);
		if (buttons == null) {
			return;
		}
		int buttonWidth = buttons.getFirst().getWidth();
		// Statistics and the square Settings share the last row (one under the other when the buttons are square).
		boolean shared = buttonWidth > 20;
		int stackHeight = (buttons.size() - (shared ? 1 : 0)) * 24 - 4;
		int bottom = row(screen) + 20;
		int space = screen.width / 2 - 110;
		if (bottom - stackHeight < 4 || !ESSENTIAL && space < buttonWidth + 8) {
			int total = -4;
			for (FlatButton button : buttons) {
				total += button.getWidth() + 4;
			}
			int x = screen.width - 4 - total;
			for (FlatButton button : buttons) {
				button.setPosition(x, 4);
				x += button.getWidth() + 4;
			}
			placeUpdateArrow(screen, buttons);
			return;
		}
		int x = ESSENTIAL ? screen.width - buttonWidth - 6 : screen.width - space / 2 - 4 - buttonWidth / 2;
		int y = bottom - stackHeight;
		for (int i = 0; i < buttons.size(); i++) {
			FlatButton button = buttons.get(i);
			if (shared && i == SETTINGS_INDEX) {
				continue;
			}
			if (shared && i == STATS_INDEX) {
				button.setWidth(buttonWidth - 24);
				button.setPosition(x, y);
				buttons.get(SETTINGS_INDEX).setPosition(x + buttonWidth - 20, y);
				y += 24;
				continue;
			}
			if (i != SETTINGS_INDEX) {
				button.setWidth(buttonWidth);
			}
			button.setPosition(x, y);
			y += 24;
		}
		placeUpdateArrow(screen, buttons);
	}

	/** The update arrow, just left of the row of Statistics and Settings (or of the row along the top). */
	private static void placeUpdateArrow(Screen screen, List<FlatButton> buttons) {
		FlatButton arrow = UPDATE.get(screen);
		if (arrow == null || buttons.size() <= STATS_INDEX) {
			return;
		}
		FlatButton stats = buttons.get(STATS_INDEX);
		FlatButton settings = buttons.get(SETTINGS_INDEX);
		int left = Math.min(stats.getX(), settings.getX());
		if (buttons.getFirst().getY() == stats.getY()) {
			// Along the top: left of the whole row.
			left = buttons.getFirst().getX();
		}
		arrow.setPosition(left - 24, stats.getY());
	}

	/** Unread messages and friend requests, on the Social button. */
	private static void badges(Screen screen, GuiGraphicsExtractor graphics) {
		List<FlatButton> buttons = BUTTONS.get(screen);
		if (buttons == null) {
			return;
		}
		Social social = OrbitClient.get().social();
		int count = social.unreadTotal() + social.incoming().size();
		if (count == 0) {
			return;
		}
		FlatButton button = buttons.get(1);
		String text = count > 99 ? "99+" : Integer.toString(count);
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int w = Math.max(9, font.width(text) + 4);
		int x = button.getX() + button.getWidth() - w + 3;
		int y = button.getY() - 3;
		graphics.fill(x, y, x + w, y + 9, Theme.RED);
		graphics.text(font, text, x + (w - font.width(text) + 1) / 2, y + 1, Theme.TEXT, false);
	}

	private static void draw(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		badges(screen, graphics);
		OrbitClient orbit = OrbitClient.get();
		if (!orbit.config().menuPlayer) {
			return;
		}
		int[] area = area(screen);
		if (area == null) {
			return;
		}
		int size = area[2];
		// A box over the buttons, wide enough for wings and the like; the player is sized to fit it.
		int x0 = Math.max(0, area[0] - size * 3 / 2);
		int y0 = Math.max(0, area[1] - size * 2 - size * 2 / 5);
		int x1 = area[0] + (area[0] - x0);
		int y1 = area[1] + 4;
		MODEL.setWorn(orbit.equipped());
		randomEmote(orbit);
		// Turn a little towards the mouse, like the inventory player.
		float yaw = (float) Math.atan((area[0] - mouseX) / 60.0) * 25.0F;
		float pitch = (float) Math.atan((area[1] - size * 1.6 - mouseY) / 60.0) * -15.0F;
		// Steady: the player keeps its size when an emote starts.
		PreviewBatch.panel(graphics, x0, y0, x1, y1, List.of(MODEL.entryIn(0, 0, x1 - x0, y1 - y0, yaw, pitch, partialTick, true)));
	}

	/**
	 * The Orbit team's message ("Summer Cosmetics!"), a widget of the screen so tooltips (the Wardrobe button's) are
	 * drawn over it.
	 */
	private static final class Announcement extends net.minecraft.client.gui.components.AbstractWidget {
		private final Screen screen;

		Announcement(Screen screen) {
			super(0, 0, 0, 0, Component.empty());
			this.screen = screen;
			this.active = false;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			announcement(screen, graphics);
		}

		@Override
		protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
		}
	}

	/**
	 * The Orbit team's message ("Summer Cosmetics!") in a box under the Wardrobe button while there is one (in the top
	 * left corner on small windows).
	 */
	private static void announcement(Screen screen, GuiGraphicsExtractor graphics) {
		String text = OrbitClient.get().backend().announcement();
		List<FlatButton> under = UNDER.get(screen);
		if (text == null || under == null || under.isEmpty()) {
			return;
		}
		var font = net.minecraft.client.Minecraft.getInstance().font;
		FlatButton first = under.getFirst();
		FlatButton last = under.getLast();
		int center = (first.getX() + last.getX() + last.getWidth()) / 2;
		int maxWidth = Math.clamp((ESSENTIAL ? 120 : screen.width / 2 - 110) - 8, 80, 170);
		List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(text), maxWidth - 14);
		lines = lines.subList(0, Math.min(3, lines.size()));
		int width = 14;
		for (var line : lines) {
			width = Math.max(width, font.width(line) + 14);
		}
		int height = lines.size() * 10 + 8;
		int x = Math.clamp(center - width / 2, 4, Math.max(4, screen.width - width - 4));
		int y = first.getY() + first.getHeight() + 4;
		if (y + height > screen.height - 12) {
			// No room above Minecraft's version line: in the top left corner instead.
			x = 4;
			y = 4;
		}
		Theme.panel(graphics, x, y, width, height);
		graphics.fill(x, y, x + 2, y + height, Theme.RED);
		int lineY = y + 5;
		for (var line : lines) {
			graphics.centeredText(font, line, x + 1 + width / 2, lineY, Theme.TEXT);
			lineY += 10;
		}
	}

	/** Every 12 to 25 seconds, one of the player's emotes (from the wheels first). */
	private static void randomEmote(OrbitClient orbit) {
		double now = orbit.menuTime();
		if (MODEL.emote() != null || now < nextEmote) {
			return;
		}
		nextEmote = now + 240 + RANDOM.nextInt(260);
		List<EmoteLibrary.LocalEmote> choices = new ArrayList<>();
		EmoteLibrary emotes = orbit.emotes();
		for (int wheel = 0; wheel < emotes.wheels().size(); wheel++) {
			for (int slot = 0; slot < OrbitState.WHEEL_SLOTS; slot++) {
				EmoteLibrary.LocalEmote emote = emotes.slot(wheel, slot);
				if (emote != null) {
					choices.add(emote);
				}
			}
		}
		if (choices.isEmpty()) {
			choices.addAll(emotes.emotes());
		}
		if (!choices.isEmpty()) {
			EmoteLibrary.LocalEmote emote = choices.get(RANDOM.nextInt(choices.size()));
			if (emote.asset() != null) {
				MODEL.playEmote(emote.asset(), emote.file().particles());
			}
		}
	}
}
