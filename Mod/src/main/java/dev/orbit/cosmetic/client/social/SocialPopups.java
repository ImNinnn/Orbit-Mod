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
 * Social news in the bottom right corner, coloured by what it is about: messages (and friend requests) in red,
 * the party in green (invitations to follow, votes on where to play), a friend coming online in gold, and
 * invitations to a world in blue. Each is as wide as its text needs (up to a limit). With the mouse free (any
 * screen open), clicking one opens what it is about, and its buttons do what they say; Shift and the social key
 * press the newest one's button without freeing the mouse. They go away after a few seconds (not while hovered).
 */
public final class SocialPopups {
	private static final int MIN_WIDTH = 170;
	private static final int MAX_WIDTH = 340;
	private static final int HEIGHT = 34;
	private static final int MARGIN = 4;
	private static final long LIFETIME_MS = 7000;
	private static final long INVITE_LIFETIME_MS = 15_000;
	private static final long ACTION_LIFETIME_MS = 30_000;
	private static final long SLIDE_MS = 180;
	private static final int MAX_SHOWN = 4;
	private static final int MIN_BUTTON = 34;
	private static final int MESSAGE_COLOR = Theme.RED;
	private static final int PARTY_COLOR = Theme.GREEN;
	private static final int ONLINE_COLOR = Theme.GOLD;
	private static final int WORLD_COLOR = 0xFF4FA3FF;

	private enum Kind { MESSAGE, REQUEST, INVITE, ACTION, ONLINE, VOTE }

	private static final class Popup {
		final Kind kind;
		final UUID player;
		final String name;
		final Component text;
		final @Nullable String address;
		final @Nullable String world;
		/** The icon an invite to a server carried (base64 PNG), until the ping answers. */
		@Nullable String icon;
		/** Its stripe's colour (what it is about). */
		int color;
		/** Its buttons (none, one, or two) and what they do; their labels may change while it shows (a vote's count). */
		java.util.function.@Nullable Supplier<Component> button;
		@Nullable Runnable action;
		java.util.function.@Nullable Supplier<Component> second;
		@Nullable Runnable secondAction;
		/** A vote's lines, as they change, and whether it is still on. */
		java.util.function.@Nullable Supplier<Component> titleLine;
		java.util.function.@Nullable Supplier<Component> detailLine;
		java.util.function.@Nullable BooleanSupplier alive;
		final long shownAt = System.currentTimeMillis();
		long hoveredFor;
		long lastFrame = System.currentTimeMillis();
		int x;
		int y;
		int width = MIN_WIDTH;

		Popup(Kind kind, UUID player, String name, Component text, @Nullable String address, @Nullable String world) {
			this.kind = kind;
			this.player = player;
			this.name = name;
			this.text = text;
			this.address = address;
			this.world = world;
			this.color = switch (kind) {
				case MESSAGE, REQUEST -> MESSAGE_COLOR;
				case INVITE -> WORLD_COLOR;
				case ONLINE -> ONLINE_COLOR;
				case ACTION, VOTE -> PARTY_COLOR;
			};
		}

		long age() {
			return System.currentTimeMillis() - shownAt - hoveredFor;
		}

		long lifetime() {
			if (kind == Kind.VOTE) {
				// Until the vote is over.
				return alive != null && alive.getAsBoolean() ? Long.MAX_VALUE / 4 : 0;
			}
			return kind == Kind.INVITE ? INVITE_LIFETIME_MS : kind == Kind.ACTION ? ACTION_LIFETIME_MS : LIFETIME_MS;
		}

		Component title() {
			return switch (kind) {
				case MESSAGE -> Component.literal(name);
				case INVITE -> Component.literal(world == null ? "" : world);
				case REQUEST -> Component.translatable("orbit_cosmetic.popup.request.title", name);
				case ONLINE -> Component.translatable("orbit_cosmetic.social.friend_online", name);
				case ACTION -> Component.literal(world == null ? "" : world);
				case VOTE -> titleLine == null ? Component.empty() : titleLine.get();
			};
		}

		Component detail() {
			return switch (kind) {
				case INVITE -> players(this);
				case VOTE -> detailLine == null ? Component.empty() : detailLine.get();
				default -> text;
			};
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
			popup.button = () -> Component.translatable("orbit_cosmetic.popup.play");
			addInvite(popup);
			return;
		}
		add(new Popup(Kind.MESSAGE, friend, name, Component.literal(Social.preview(content)), null, null));
	}

	private static void addInvite(Popup popup) {
		add(popup);
		if (POPUPS.contains(popup)) {
			if (links == null) {
				links = new ServerLinks();
			}
			links.status(popup.address, popup.world);
		}
	}

	/**
	 * A popup that offers to do something (the party: join it, invite and accept on a server): the player's head, a
	 * title and a line, and a button. Like every popup, it can be clicked while a screen is open (the chat, for one).
	 */
	public static void action(UUID player, String name, Component title, Component detail, Component button, Runnable action) {
		Popup popup = new Popup(Kind.ACTION, player, name, detail, null, title.getString());
		popup.button = () -> button;
		popup.action = action;
		add(popup);
	}

	/** A friend came online (gold). */
	public static void online(UUID player, String name) {
		add(new Popup(Kind.ONLINE, player, name, Component.translatable("orbit_cosmetic.popup.online"), null, null));
	}

	/**
	 * A party member's vote on where to play: their head, what they would like and the time left, Join (with how
	 * many are in) and Opt out. It stays while the vote is on.
	 */
	static void vote(UUID player, String name, java.util.function.Supplier<Component> title, java.util.function.Supplier<Component> detail,
		java.util.function.Supplier<Component> join, Runnable onJoin, java.util.function.@Nullable Supplier<Component> optOut, Runnable onOptOut,
		java.util.function.BooleanSupplier alive) {
		Popup popup = new Popup(Kind.VOTE, player, name, Component.empty(), null, null);
		popup.titleLine = title;
		popup.detailLine = detail;
		popup.button = join;
		popup.action = onJoin;
		popup.second = optOut;
		popup.secondAction = onOptOut;
		popup.alive = alive;
		add(popup);
	}

	/**
	 * The social key with Shift: the newest popup's button (Join, Accept, Invite...) without freeing the mouse; a
	 * popup without one opens what it is about. False when there is no popup.
	 */
	public static boolean pressNewest() {
		Popup newest = POPUPS.stream().filter(popup -> popup.button != null).findFirst().orElse(POPUPS.isEmpty() ? null : POPUPS.getFirst());
		if (newest == null) {
			return false;
		}
		use(newest, Compat.screen(), 1);
		return true;
	}

	public static void request(UUID player, String name) {
		add(new Popup(Kind.REQUEST, player, name, Component.translatable("orbit_cosmetic.popup.request"), null, null));
	}

	private static void add(Popup popup) {
		if (!OrbitClient.get().config().showNotifications && popup.kind != Kind.VOTE) {
			return;
		}
		POPUPS.addFirst(popup);
		while (POPUPS.size() > MAX_SHOWN) {
			// The oldest goes, but a vote still on stays.
			Popup oldest = POPUPS.reversed().stream().filter(shown -> shown.kind != Kind.VOTE).findFirst().orElse(POPUPS.getLast());
			POPUPS.remove(oldest);
		}
	}

	/** A button's width: its label and some room, at least as wide as Play. */
	private static int buttonWidth(Font font, @Nullable Component label) {
		return label == null ? 0 : Math.max(MIN_BUTTON, font.width(label) + 10);
	}

	/** Where the text starts (after the head or icon), and how much the buttons take on the right. */
	private static int textLeft(Popup popup) {
		return popup.kind == Kind.MESSAGE ? 7 : 37;
	}

	private static int buttonsWidth(Font font, Popup popup) {
		int width = 0;
		if (popup.button != null) {
			width += buttonWidth(font, popup.button.get()) + 5;
		}
		if (popup.second != null && popup.second.get() != null) {
			width += buttonWidth(font, popup.second.get()) + 3;
		}
		return width;
	}

	/** As wide as its title and line need, between the smallest and the widest (and the screen). */
	private static int widthOf(Font font, Popup popup, int screenWidth) {
		int text = Math.max(font.width(popup.title()) + (popup.kind == Kind.MESSAGE ? 14 : 0), font.width(popup.detail()));
		int wanted = textLeft(popup) + text + 6 + buttonsWidth(font, popup);
		return Math.clamp(wanted, MIN_WIDTH, Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, screenWidth - 2 * MARGIN)));
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
		for (Popup popup : List.copyOf(POPUPS)) {
			long age = popup.age();
			long lifetime = popup.lifetime();
			if (age > lifetime + SLIDE_MS) {
				POPUPS.remove(popup);
				continue;
			}
			int width = widthOf(font, popup, screenWidth);
			popup.width = width;
			// Slides in from the right, and out again at the end.
			float shown = age < SLIDE_MS ? age / (float) SLIDE_MS : age > lifetime ? 1 - (age - lifetime) / (float) SLIDE_MS : 1;
			shown = 1 - (1 - shown) * (1 - shown);
			int x = screenWidth - Math.round((width + MARGIN) * shown);
			popup.x = x;
			popup.y = y;
			boolean hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + HEIGHT;
			if (hovered) {
				popup.hoveredFor += now - popup.lastFrame;
			}
			popup.lastFrame = now;
			Theme.panel(graphics, x, y, width, HEIGHT, hovered ? Theme.PANEL_HOVER : Theme.PANEL);
			graphics.fill(x, y, x + 2, y + HEIGHT, popup.color);
			int textW = width - textLeft(popup) - 6 - buttonsWidth(font, popup);
			if (popup.kind == Kind.MESSAGE) {
				// The friend's head and name on top, the message across the whole popup under them.
				face(graphics, popup.player, popup.name, x + 7, y + 5, 10);
				graphics.text(font, Theme.clip(font, popup.title(), width - 26), x + 21, y + 6, Theme.TEXT, false);
				graphics.text(font, Theme.clip(font, popup.detail().copy().withStyle(ChatFormatting.GRAY), width - 14), x + 7, y + 20, Theme.TEXT, false);
			} else {
				if (popup.kind == Kind.INVITE) {
					Identifier icon = links == null ? null : links.icon(popup.address, popup.icon);
					graphics.blit(icon != null ? icon : NO_ICON, x + 7, y + 5, x + 31, y + 29, 0, 1, 0, 1);
				} else {
					face(graphics, popup.player, popup.name, x + 7, y + 5, 24);
				}
				graphics.text(font, Theme.clip(font, popup.title(), textW), x + 37, y + 7, Theme.TEXT, false);
				graphics.text(font, Theme.clip(font, popup.detail().copy().withStyle(ChatFormatting.GRAY), textW), x + 37, y + 19, Theme.TEXT, false);
			}
			int by = y + (HEIGHT - 18) / 2;
			int bx = x + width - 5;
			if (popup.second != null && popup.second.get() != null) {
				Component label = popup.second.get();
				int bw = buttonWidth(font, label);
				bx -= bw;
				boolean hot = mouseX >= bx && mouseY >= by && mouseX < bx + bw && mouseY < by + 18;
				Theme.buttonFace(graphics, bx, by, bw, 18, true, hot, hot ? 0xFF5A5A62 : 0xFF45454C, 0xFF8A8A92);
				graphics.centeredText(font, label, bx + bw / 2, by + 5, Theme.TEXT);
				bx -= 3;
			}
			if (popup.button != null) {
				Component label = popup.button.get();
				int bw = buttonWidth(font, label);
				bx -= bw;
				boolean hot = mouseX >= bx && mouseY >= by && mouseX < bx + bw && mouseY < by + 18;
				Theme.buttonFace(graphics, bx, by, bw, 18, true, hot, hot ? 0xFF4CA35A : 0xFF3A7D44, 0xFF9CE3A8);
				graphics.centeredText(font, label, bx + bw / 2, by + 5, Theme.TEXT);
			}
			y -= HEIGHT + MARGIN;
		}
	}

	/** "3 players online" (and who invited) as the world's ping answers. */
	private static Component players(Popup popup) {
		net.minecraft.client.multiplayer.ServerData data = links == null || popup.address == null ? null : links.status(popup.address, popup.world);
		if (data == null || data.state() == net.minecraft.client.multiplayer.ServerData.State.PINGING) {
			return Component.translatable("orbit_cosmetic.popup.invite.from", popup.name);
		}
		if (data.state() != net.minecraft.client.multiplayer.ServerData.State.SUCCESSFUL || data.players == null) {
			return Component.translatable("orbit_cosmetic.popup.invite.offline", popup.name);
		}
		return Component.translatable("orbit_cosmetic.popup.invite.players", data.players.online(), popup.name);
	}

	/** A click on a popup: true when it was used. */
	private static boolean click(Screen screen, double mouseX, double mouseY) {
		Font font = Minecraft.getInstance().font;
		for (Popup popup : List.copyOf(POPUPS)) {
			if (mouseX < popup.x || mouseY < popup.y || mouseX >= popup.x + popup.width || mouseY >= popup.y + HEIGHT) {
				continue;
			}
			int by = popup.y + (HEIGHT - 18) / 2;
			int pressed = 0;
			if (mouseY >= by && mouseY < by + 18) {
				int bx = popup.x + popup.width - 5;
				if (popup.second != null && popup.second.get() != null) {
					int bw = buttonWidth(font, popup.second.get());
					if (mouseX >= bx - bw && mouseX < bx) {
						pressed = 2;
					}
					bx -= bw + 3;
				}
				if (pressed == 0 && popup.button != null && mouseX >= bx - buttonWidth(font, popup.button.get()) && mouseX < bx) {
					pressed = 1;
				}
			}
			use(popup, screen, pressed);
			return true;
		}
		return false;
	}

	/** Uses a popup: its first or second button (1, 2), or what it is about (0: the chat, the friends). */
	private static void use(Popup popup, @Nullable Screen screen, int button) {
		if (popup.kind == Kind.VOTE) {
			// A vote stays while it is on: Join keeps it (with the count), Opt out takes it away.
			if (button == 1 && popup.action != null) {
				popup.action.run();
			} else if (button == 2 && popup.secondAction != null) {
				popup.secondAction.run();
				POPUPS.remove(popup);
			}
			return;
		}
		POPUPS.remove(popup);
		if (popup.kind == Kind.ACTION) {
			if (button == 1 && popup.action != null) {
				popup.action.run();
			}
		} else if (popup.kind == Kind.INVITE) {
			if (button == 1 && popup.address != null) {
				ServerLinks.join(screen, popup.address, popup.world);
			}
		} else if (popup.kind == Kind.REQUEST) {
			Compat.setScreen(SocialScreen.friends(screen));
		} else {
			Compat.setScreen(SocialScreen.chat(screen, popup.player));
		}
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
