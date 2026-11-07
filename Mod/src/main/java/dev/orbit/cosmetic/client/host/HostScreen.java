package dev.orbit.cosmetic.client.host;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import dev.orbit.cosmetic.client.social.ShareScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Hosting a world for friends. From the menus it lists the worlds to open; inside a singleplayer world it puts
 * the world online (through e4mc), shows its address and who joined, and invites friends to it.
 */
public final class HostScreen extends OrbitScreen {
	private static final int WIDTH = 320;
	private static final int ROW = 30;
	private static final URI E4MC_PAGE = URI.create("https://modrinth.com/mod/e4mc");

	private @Nullable List<LevelSummary> worlds;
	private boolean worldsFailed;
	private double scroll;
	/** The game mode for players who join: the world's own until changed here. */
	private @Nullable GameType mode;
	private boolean cheats;
	/** What the buttons were made for: rebuilt when hosting starts or stops, or the address arrives. */
	private String shown = "";

	public HostScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.host.title"), parent);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	private static @Nullable IntegratedServer server() {
		return Minecraft.getInstance().getSingleplayerServer();
	}

	private String state() {
		return (server() != null) + "/" + Hosting.isHosting() + "/" + Hosting.address();
	}

	private int left() {
		return width / 2 - WIDTH / 2;
	}

	private int top() {
		return Math.max(8, height / 2 - 120);
	}

	@Override
	protected void init() {
		shown = state();
		int x = left();
		int y = top();
		backButton(x + WIDTH - 22, y + 6);
		IntegratedServer server = server();
		if (minecraft.level == null) {
			loadWorlds();
			return;
		}
		if (server == null) {
			// On a multiplayer server: friends can be invited to it.
			net.minecraft.client.multiplayer.ServerData current = minecraft.getCurrentServer();
			if (current != null) {
				addRenderableWidget(new FlatButton(x + 8, y + 74, WIDTH - 16, 20, Component.translatable("orbit_cosmetic.host.invite_server"), "send", FlatButton.Style.PRIMARY,
					b -> ShareScreen.server(this, current)));
			}
			return;
		}
		int bx = x + 8;
		int by = y + 74;
		if (!Hosting.e4mcInstalled()) {
			addRenderableWidget(new FlatButton(bx, by + 34, WIDTH - 16, 20, Component.translatable("orbit_cosmetic.host.get_e4mc"), "folder", FlatButton.Style.PRIMARY,
				b -> Compat.openUri(E4MC_PAGE)));
			return;
		}
		int half = (WIDTH - 20) / 2;
		Difficulty difficulty = minecraft.level.getDifficulty();
		FlatButton difficultyButton = addRenderableWidget(FlatCycle.of(bx, by, half, 20, Component.translatable("options.difficulty"), List.of(Difficulty.values()), difficulty,
			Difficulty::getDisplayName, null, value -> server.execute(() -> server.setDifficulty(value, true))));
		difficultyButton.active = !server.getWorldData().isDifficultyLocked() && !server.getWorldData().isHardcore();
		if (!Hosting.isHosting()) {
			if (mode == null) {
				mode = server.getDefaultGameType();
			}
			addRenderableWidget(FlatCycle.of(bx + half + 4, by, half, 20, Component.translatable("selectWorld.gameMode"),
				List.of(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR), mode, GameType::getShortDisplayName, null, value -> mode = value));
			addRenderableWidget(FlatCycle.onOff(bx, by + 24, half, 20, Component.translatable("selectWorld.allowCommands"), cheats,
				Component.translatable("orbit_cosmetic.host.cheats.tooltip"), value -> cheats = value));
			addRenderableWidget(new FlatButton(bx + half + 4, by + 24, half, 20, Component.translatable("orbit_cosmetic.host.start"), "host", FlatButton.Style.PRIMARY, b -> {
				Hosting.start(mode, cheats);
				rebuildWidgets();
			}));
			return;
		}
		String address = Hosting.address();
		FlatButton invite = addRenderableWidget(new FlatButton(bx + half + 4, by, half, 20, Component.translatable("orbit_cosmetic.host.invite"), "person_add", FlatButton.Style.PRIMARY,
			b -> {
				String now = Hosting.address();
				if (now != null) {
					ShareScreen.invite(this, now, server.getWorldData().getLevelName());
				}
			}));
		invite.active = address != null && orbit().social().isLoaded();
		FlatButton copy = addRenderableWidget(new FlatButton(bx, by + 24, half, 20, Component.translatable("orbit_cosmetic.host.copy"), "copy", FlatButton.Style.SECONDARY, b -> {
			String now = Hosting.address();
			if (now != null) {
				minecraft.keyboardHandler.setClipboard(now);
				Notifications.success(Component.translatable("orbit_cosmetic.host.copied"), Component.literal(now));
			}
		}));
		copy.active = address != null;
		addRenderableWidget(new FlatButton(bx + half + 4, by + 24, half, 20, Component.translatable("orbit_cosmetic.host.stop"), "close", FlatButton.Style.DANGER, b -> {
			if (!Hosting.stop()) {
				Notifications.error(Component.translatable("orbit_cosmetic.host.cannot_stop"), Component.empty());
			}
			rebuildWidgets();
		}));
	}

	private void loadWorlds() {
		if (worlds != null || worldsFailed) {
			return;
		}
		try {
			var source = minecraft.getLevelSource();
			source.loadLevelSummaries(source.findLevelCandidates()).whenComplete((list, error) -> minecraft.execute(() -> {
				if (error != null) {
					worldsFailed = true;
					return;
				}
				List<LevelSummary> usable = new ArrayList<>();
				for (LevelSummary summary : list) {
					if (!summary.isLocked() && summary.primaryActionActive()) {
						usable.add(summary);
					}
				}
				usable.sort(null);
				worlds = usable;
			}));
		} catch (Exception e) {
			worldsFailed = true;
		}
	}

	private void open(LevelSummary world) {
		Hosting.openWhenLoaded(true);
		orbit().screenshots().releaseAll();
		minecraft.createWorldOpenFlows().openWorld(world.getLevelId(), () -> {
			Hosting.openWhenLoaded(false);
			Compat.setScreen(this);
		});
	}

	@Override
	public void tick() {
		if (!shown.equals(state())) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = left();
		int y = top();
		int panelHeight = Math.min(height - 16, 240);
		Theme.panel(graphics, x, y, WIDTH, panelHeight);
		graphics.text(font, title, x + 8, y + 10, Theme.TEXT, false);
		IntegratedServer server = server();
		if (minecraft.level == null) {
			worldList(graphics, mouseX, mouseY, x, y + 28, panelHeight - 36);
		} else if (server == null) {
			paragraph(graphics, Component.translatable("orbit_cosmetic.host.on_server"), x + 8, y + 32);
		} else {
			hostPanel(graphics, server, x, y, panelHeight);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void hostPanel(GuiGraphicsExtractor graphics, IntegratedServer server, int x, int y, int panelHeight) {
		graphics.text(font, Theme.clip(font, Component.literal(server.getWorldData().getLevelName()), WIDTH - 40), x + 8, y + 26, Theme.MUTED, false);
		int statusY = y + 42;
		graphics.fill(x + 8, statusY, x + WIDTH - 8, statusY + 24, Theme.PANEL_2);
		Component status;
		int color;
		if (!Hosting.e4mcInstalled()) {
			status = Component.translatable("orbit_cosmetic.host.no_e4mc");
			color = Theme.YELLOW;
		} else if (!Hosting.isHosting()) {
			status = Component.translatable("orbit_cosmetic.host.offline");
			color = Theme.FAINT;
		} else if (Hosting.address() == null) {
			status = Component.translatable("orbit_cosmetic.host.connecting");
			color = Theme.YELLOW;
		} else {
			status = Component.translatable("orbit_cosmetic.host.online_at", Hosting.address());
			color = Theme.GREEN;
		}
		graphics.fill(x + 14, statusY + 10, x + 18, statusY + 14, color);
		graphics.text(font, Theme.clip(font, status, WIDTH - 40), x + 24, statusY + 8, Theme.TEXT, false);
		if (!Hosting.e4mcInstalled()) {
			paragraph(graphics, Component.translatable("orbit_cosmetic.host.no_e4mc.text"), x + 8, y + 74);
			return;
		}
		int listY = y + 128;
		Theme.separator(graphics, font, x + 8, listY, WIDTH - 16, Component.translatable("orbit_cosmetic.host.players"));
		listY += 14;
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (ServerPlayer player : players) {
			if (listY + 20 > y + panelHeight - 6) {
				break;
			}
			graphics.fill(x + 8, listY, x + WIDTH - 8, listY + 20, Theme.PANEL_2);
			PlayerFaceExtractor.extractRenderState(graphics, orbit().social().skin(player.getUUID(), player.getName().getString()), x + 12, listY + 2, 16);
			graphics.text(font, player.getName(), x + 34, listY + 6, Theme.TEXT, false);
			if (player.getUUID().equals(minecraft.player == null ? null : minecraft.player.getUUID())) {
				Component you = Component.translatable("orbit_cosmetic.host.you").withStyle(ChatFormatting.GRAY);
				graphics.text(font, you, x + WIDTH - 12 - font.width(you), listY + 6, Theme.TEXT, false);
			}
			listY += 22;
		}
	}

	private void worldList(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x, int y, int listHeight) {
		graphics.text(font, Component.translatable("orbit_cosmetic.host.pick_world").withStyle(ChatFormatting.GRAY), x + 8, y, Theme.TEXT, false);
		y += 14;
		listHeight -= 14;
		if (worlds == null) {
			Component text = Component.translatable(worldsFailed ? "orbit_cosmetic.host.worlds_failed" : "orbit_cosmetic.host.loading");
			graphics.text(font, text.copy().withStyle(ChatFormatting.GRAY), x + 8, y + 6, Theme.TEXT, false);
			return;
		}
		if (worlds.isEmpty()) {
			graphics.text(font, Component.translatable("orbit_cosmetic.host.no_worlds").withStyle(ChatFormatting.GRAY), x + 8, y + 6, Theme.TEXT, false);
			return;
		}
		scroll = Math.clamp(scroll, 0, Math.max(0, worlds.size() * ROW - listHeight));
		graphics.enableScissor(x + 4, y, x + WIDTH - 4, y + listHeight);
		DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
		for (int i = 0; i < worlds.size(); i++) {
			int rowY = y + i * ROW - (int) scroll;
			if (rowY + ROW < y || rowY > y + listHeight) {
				continue;
			}
			LevelSummary world = worlds.get(i);
			boolean hot = Theme.inside(mouseX, mouseY, x + 8, rowY, WIDTH - 16, ROW - 2) && mouseY >= y && mouseY < y + listHeight;
			graphics.fill(x + 8, rowY, x + WIDTH - 8, rowY + ROW - 2, hot ? Theme.PANEL_HOVER : Theme.PANEL_2);
			ScreenshotLibrary.Thumbnail icon = orbit().screenshots().thumbnail(world.getIcon(), 64);
			if (icon != null) {
				graphics.blit(icon.id(), x + 11, rowY + 2, x + 35, rowY + 26, 0, 1, 0, 1);
			} else {
				graphics.fill(x + 11, rowY + 2, x + 35, rowY + 26, Theme.PANEL);
				Theme.icon(graphics, "image", x + 19, rowY + 10, Theme.FAINT);
			}
			graphics.text(font, Theme.clip(font, Component.literal(world.getLevelName()), WIDTH - 90), x + 40, rowY + 5, Theme.TEXT, false);
			Component info = Component.empty().append(world.getGameMode().getShortDisplayName()).append(" · ").append(format.format(new Date(world.getLastPlayed())));
			graphics.text(font, Theme.clip(font, info.copy().withStyle(ChatFormatting.GRAY), WIDTH - 90), x + 40, rowY + 16, Theme.TEXT, false);
			if (hot) {
				Theme.icon(graphics, "host", x + WIDTH - 24, rowY + 10, Theme.TEXT);
			}
		}
		graphics.disableScissor();
	}

	private void paragraph(GuiGraphicsExtractor graphics, Component text, int x, int y) {
		for (var line : font.split(text.copy().withStyle(ChatFormatting.GRAY), WIDTH - 16)) {
			graphics.text(font, line, x, y, Theme.TEXT, false);
			y += 10;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (minecraft.level != null || worlds == null) {
			return false;
		}
		int x = left();
		int y = top() + 42;
		int listHeight = Math.min(height - 16, 240) - 50;
		if (event.y() < y || event.y() >= y + listHeight) {
			return false;
		}
		for (int i = 0; i < worlds.size(); i++) {
			int rowY = y + i * ROW - (int) scroll;
			if (Theme.inside(event.x(), event.y(), x + 8, rowY, WIDTH - 16, ROW - 2)) {
				open(worlds.get(i));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll -= scrollY * ROW;
		return true;
	}

	@Override
	public void onClose() {
		orbit().screenshots().releaseAll();
		super.onClose();
	}
}
