package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Social news in the bottom right corner. A message shows the friend's head and name over the message; a world
 * invite the world's icon, name and players online (pinged like the server list); a friend request the friend's
 * head. With
 * the mouse free (any screen open), clicking one opens the conversation (or the friend list), and an invite's Play
 * button joins the world or server. They go away after a few seconds (not while hovered).
 */
public final class SocialPopups {
	private static final int WIDTH = 170;
	private static final int HEIGHT = 34;
	private static final int MARGIN = 4;
	private static final long LIFETIME_MS = 7000;
	private static final long INVITE_LIFETIME_MS = 15_000;
	private static final long SLIDE_MS = 180;
	private static final int MAX_SHOWN = 4;
	private static final int PLAY_W = 34;

	private enum Kind { MESSAGE, REQUEST, INVITE }

	private static final class Popup {
		final Kind kind;
		final UUID player;
		final String name;
		final Component text;
		final @Nullable String address;
		final @Nullable String world;
		/** The icon an invite to a server carried (base64 PNG), until the ping answers. */
		@Nullable String icon;
		final long shownAt = System.currentTimeMillis();
		long hoveredFor;
		long lastFrame = System.currentTimeMillis();
		int x;
		int y;

		Popup(Kind kind, UUID player, String name, Component text, @Nullable String address, @Nullable String world) {
			this.kind = kind;
			this.player = player;
			this.name = name;
			this.text = text;
			this.address = address;
			this.world = world;
		}

		long age() {
			return System.currentTimeMillis() - shownAt - hoveredFor;
		}

		long lifetime() {
			return kind == Kind.INVITE ? INVITE_LIFETIME_MS : LIFETIME_MS;
		}
	}

	private static final List<Popup> POPUPS = new ArrayList<>();
	private static final Identifier NO_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_server.png");
	/** Pings the worlds of invites on screen (closed when none is left). */
	private static @Nullable ServerLinks links;

	private SocialPopups() {
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "social_popups"), (graphics, tick) -> {
			if (Compat.screen() == null && !Compat.hudHidden()) {
				draw(graphics, -1, -1);
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> draw(graphics, mouseX, mouseY));
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !click(current, event.x(), event.y()));
		});
	}

	/** A new message from a friend: an invite in it gets its own popup with Play. */
	public static void message(UUID friend, String name, JsonObject content) {
		if (FloatingChats.has(friend) && !content.has("invite")) {
			return; // its floating window shows it
		}
		if (content.get("invite") instanceof JsonObject invite && invite.has("address")) {
			String world = invite.has("world") ? invite.get("world").getAsString() : invite.get("address").getAsString();
			Popup popup = new Popup(Kind.INVITE, friend, name, Component.translatable("orbit_cosmetic.popup.invite", world), invite.get("address").getAsString(), world);
			popup.icon = invite.has("icon") ? invite.get("icon").getAsString() : null;
			add(popup);
			if (POPUPS.contains(popup)) {
				if (links == null) {
					links = new ServerLinks();
				}
				links.status(popup.address, world);
			}
			return;
		}
		add(new Popup(Kind.MESSAGE, friend, name, Component.literal(Social.preview(content)), null, null));
	}

	public static void request(UUID player, String name) {
		add(new Popup(Kind.REQUEST, player, name, Component.translatable("orbit_cosmetic.popup.request"), null, null));
	}

	private static void add(Popup popup) {
		if (!OrbitClient.get().config().showNotifications) {
			return;
		}
		POPUPS.addFirst(popup);
		while (POPUPS.size() > MAX_SHOWN) {
			POPUPS.removeLast();
		}
	}

	private static void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (links != null) {
			if (POPUPS.stream().noneMatch(popup -> popup.kind == Kind.INVITE)) {
				links.close();
				links = null;
			} else {
				links.tick();
			}
		}
		if (POPUPS.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int y = minecraft.getWindow().getGuiScaledHeight() - MARGIN - HEIGHT;
		long now = System.currentTimeMillis();
		Social social = OrbitClient.get().social();
		for (Popup popup : List.copyOf(POPUPS)) {
			long age = popup.age();
			if (age > popup.lifetime() + SLIDE_MS) {
				POPUPS.remove(popup);
				continue;
			}
			// Slides in from the right, and out again at the end.
			float shown = age < SLIDE_MS ? age / (float) SLIDE_MS : age > popup.lifetime() ? 1 - (age - popup.lifetime()) / (float) SLIDE_MS : 1;
			shown = 1 - (1 - shown) * (1 - shown);
			int x = screenWidth - Math.round((WIDTH + MARGIN) * shown);
			popup.x = x;
			popup.y = y;
			boolean hovered = mouseX >= x && mouseY >= y && mouseX < x + WIDTH && mouseY < y + HEIGHT;
			if (hovered) {
				popup.hoveredFor += now - popup.lastFrame;
			}
			popup.lastFrame = now;
			Theme.panel(graphics, x, y, WIDTH, HEIGHT, hovered ? Theme.PANEL_HOVER : Theme.PANEL);
			graphics.fill(x, y, x + 2, y + HEIGHT, popup.kind == Kind.INVITE ? Theme.GREEN : Theme.RED);
			switch (popup.kind) {
				case MESSAGE -> {
					// The friend's head and name on top, the message across the whole popup under them.
					face(graphics, popup.player, popup.name, x + 7, y + 5, 10);
					graphics.text(font, Theme.clip(font, Component.literal(popup.name), WIDTH - 26), x + 21, y + 6, Theme.TEXT, false);
					graphics.text(font, Theme.clip(font, popup.text.copy().withStyle(ChatFormatting.GRAY), WIDTH - 14), x + 7, y + 20, Theme.TEXT, false);
				}
				case INVITE -> {
					// The world's icon and name, and how many play there now.
					int textW = WIDTH - 41 - PLAY_W - 4;
					Identifier icon = links == null ? null : links.icon(popup.address, popup.icon);
					graphics.blit(icon != null ? icon : NO_ICON, x + 7, y + 5, x + 31, y + 29, 0, 1, 0, 1);
					graphics.text(font, Theme.clip(font, Component.literal(popup.world), textW), x + 37, y + 7, Theme.TEXT, false);
					graphics.text(font, Theme.clip(font, players(popup).copy().withStyle(ChatFormatting.GRAY), textW), x + 37, y + 19, Theme.TEXT, false);
				}
				case REQUEST -> {
					face(graphics, popup.player, popup.name, x + 7, y + 5, 24);
					int textW = WIDTH - 41;
					graphics.text(font, Theme.clip(font, Component.translatable("orbit_cosmetic.popup.request.title", popup.name), textW), x + 37, y + 7, Theme.TEXT, false);
					graphics.text(font, Theme.clip(font, popup.text.copy().withStyle(ChatFormatting.GRAY), textW), x + 37, y + 19, Theme.TEXT, false);
				}
			}
			if (popup.kind == Kind.INVITE) {
				int bx = playX(popup);
				int by = y + (HEIGHT - 18) / 2;
				boolean hot = mouseX >= bx && mouseY >= by && mouseX < bx + PLAY_W && mouseY < by + 18;
				Theme.buttonFace(graphics, bx, by, PLAY_W, 18, true, hot, hot ? 0xFF4CA35A : 0xFF3A7D44, 0xFF9CE3A8);
				graphics.centeredText(font, Component.translatable("orbit_cosmetic.popup.play"), bx + PLAY_W / 2, by + 5, Theme.TEXT);
			}
			y -= HEIGHT + MARGIN;
		}
	}

	/** "3 players online" (and who invited) as the world's ping answers. */
	private static Component players(Popup popup) {
		net.minecraft.client.multiplayer.ServerData data = links == null ? null : links.status(popup.address, popup.world);
		if (data == null || data.state() == net.minecraft.client.multiplayer.ServerData.State.PINGING) {
			return Component.translatable("orbit_cosmetic.popup.invite.from", popup.name);
		}
		if (data.state() != net.minecraft.client.multiplayer.ServerData.State.SUCCESSFUL || data.players == null) {
			return Component.translatable("orbit_cosmetic.popup.invite.offline", popup.name);
		}
		return Component.translatable("orbit_cosmetic.popup.invite.players", data.players.online(), popup.name);
	}

	private static int playX(Popup popup) {
		return popup.x + WIDTH - PLAY_W - 5;
	}

	/** A click on a popup: true when it was used. */
	private static boolean click(Screen screen, double mouseX, double mouseY) {
		for (Popup popup : List.copyOf(POPUPS)) {
			if (mouseX < popup.x || mouseY < popup.y || mouseX >= popup.x + WIDTH || mouseY >= popup.y + HEIGHT) {
				continue;
			}
			POPUPS.remove(popup);
			int by = popup.y + (HEIGHT - 18) / 2;
			if (popup.kind == Kind.INVITE && mouseX >= playX(popup) && mouseY >= by && mouseY < by + 18) {
				ServerLinks.join(screen, popup.address, popup.world);
			} else if (popup.kind == Kind.REQUEST) {
				Compat.setScreen(SocialScreen.friends(screen));
			} else {
				Compat.setScreen(SocialScreen.chat(screen, popup.player));
			}
			return true;
		}
		return false;
	}

	private static final Identifier ORBIT_ICON = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "icon.png");

	/** A player's face, or Orbit's icon for Orbit's messages to every player. */
	public static void face(GuiGraphicsExtractor graphics, UUID player, String name, int x, int y, int size) {
		Social social = OrbitClient.get().social();
		Social.Group group = social.group(player);
		if (group != null) {
			groupFace(graphics, social, group, x, y, size);
			return;
		}
		if (Social.isOrbit(player)) {
			graphics.blit(ORBIT_ICON, x, y, x + size, y + size, 0, 1, 0, 1);
		} else {
			PlayerFaceExtractor.extractRenderState(graphics, OrbitClient.get().social().skin(player, name), x, y, size);
		}
	}

	/** A group's picture, or up to four of its other members' heads. */
	private static void groupFace(GuiGraphicsExtractor graphics, Social social, Social.Group group, int x, int y, int size) {
		Identifier picture = social.groupPicture(group);
		if (picture != null) {
			graphics.blit(picture, x, y, x + size, y + size, 0, 1, 0, 1);
			return;
		}
		List<Social.Person> others = group.others(social.me());
		if (others.isEmpty()) {
			graphics.fill(x, y, x + size, y + size, Theme.PANEL_2);
			return;
		}
		if (others.size() == 1) {
			PlayerFaceExtractor.extractRenderState(graphics, social.skin(others.getFirst().uuid(), others.getFirst().name()), x, y, size);
			return;
		}
		int half = size / 2;
		int[][] spots = others.size() == 2 ? new int[][] {{0, 0}, {half, half}} : others.size() == 3 ? new int[][] {{0, 0}, {half, 0}, {half / 2, half}}
			: new int[][] {{0, 0}, {half, 0}, {0, half}, {half, half}};
		for (int i = 0; i < spots.length; i++) {
			Social.Person person = others.get(i);
			PlayerFaceExtractor.extractRenderState(graphics, social.skin(person.uuid(), person.name()), x + spots[i][0], y + spots[i][1], half);
		}
	}
}
