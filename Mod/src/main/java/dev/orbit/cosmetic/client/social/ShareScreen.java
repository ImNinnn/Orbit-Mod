package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sends something to friends: a screenshot, a cosmetic (emote, body part) or an invite to a hosted world, with an
 * optional message. Tick the friends to send it to.
 */
public final class ShareScreen extends OrbitScreen {
	private final Component what;
	private final @Nullable Path picture;
	private final JsonObject content;
	/** Before sending: makes sure Orbit Cloud has the file (a shared cosmetic); then runs the given action. */
	private final @Nullable Consumer<Runnable> prepare;
	private final Set<UUID> chosen = new LinkedHashSet<>();
	private final List<Hotspot> rows = new ArrayList<>();
	private EditBox message;
	private FlatButton send;

	private ShareScreen(@Nullable Screen parent, Component what, @Nullable Path picture, JsonObject content, @Nullable Consumer<Runnable> prepare) {
		super(Component.translatable("orbit_cosmetic.social.share.title"), parent);
		this.what = what;
		this.picture = picture;
		this.content = content;
		this.prepare = prepare;
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	/** Shares a screenshot with friends (picked here). */
	public static void screenshot(@Nullable Screen parent, ScreenshotLibrary.Shot shot) {
		Compat.setScreen(new ShareScreen(parent, Component.literal(shot.name()), shot.path(), new JsonObject(), null));
	}

	/** Sends a screenshot straight to one friend (from their conversation). */
	static void screenshotTo(Screen back, ScreenshotLibrary.Shot shot, @Nullable UUID friend) {
		Compat.setScreen(back);
		if (friend != null) {
			OrbitClient.get().social().send(List.of(friend), new JsonObject(), shot.path(), () -> {
			});
		}
	}

	public static void cosmetic(@Nullable Screen parent, LocalCosmetic cosmetic) {
		JsonObject shared = SharedCosmetics.describe(cosmetic);
		if (shared == null) {
			return;
		}
		JsonObject content = new JsonObject();
		content.add("cosmetic", shared);
		Compat.setScreen(new ShareScreen(parent, Component.literal(cosmetic.displayName()), null, content,
			then -> OrbitClient.get().backend().socialEnsureUploaded(cosmetic.asset(), cosmetic.bytes(), cosmetic.displayName(), false, then,
				error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)))));
	}

	public static void emote(@Nullable Screen parent, EmoteLibrary.LocalEmote emote) {
		JsonObject shared = SharedCosmetics.describe(emote);
		if (shared == null) {
			return;
		}
		JsonObject content = new JsonObject();
		content.add("cosmetic", shared);
		Compat.setScreen(new ShareScreen(parent, Component.literal(emote.displayName()), null, content,
			then -> OrbitClient.get().backend().socialEnsureUploaded(emote.asset(), emote.file().bytes(), emote.displayName(), true, then,
				error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)))));
	}

	/** Invites friends to a hosted world. */
	public static void invite(@Nullable Screen parent, String address, String world) {
		JsonObject invite = new JsonObject();
		invite.addProperty("address", address);
		invite.addProperty("world", world);
		JsonObject content = new JsonObject();
		content.add("invite", invite);
		Compat.setScreen(new ShareScreen(parent, Component.translatable("orbit_cosmetic.host.invite_to", world), null, content, null));
	}

	/**
	 * Invites friends to a multiplayer server. Its name, description and icon go with the invite, for the card to
	 * show until it has pinged the server itself.
	 */
	public static void server(@Nullable Screen parent, ServerData server) {
		JsonObject invite = new JsonObject();
		invite.addProperty("address", server.ip);
		invite.addProperty("world", server.name);
		if (server.motd != null && !server.motd.getString().isBlank()) {
			invite.addProperty("motd", server.motd.getString());
		}
		byte[] icon = ServerData.validateIcon(server.getIconBytes());
		if (icon != null && icon.length <= 24 * 1024) {
			invite.addProperty("icon", Base64.getEncoder().encodeToString(icon));
		}
		JsonObject content = new JsonObject();
		content.add("invite", invite);
		Compat.setScreen(new ShareScreen(parent, Component.translatable("orbit_cosmetic.host.invite_to", server.name), null, content, null));
	}

	@Override
	protected void init() {
		int x = width / 2 - 140;
		message = new EditBox(font, x + 6, height - 52, 268, 12, Component.translatable("orbit_cosmetic.social.message"));
		message.setBordered(false);
		message.setMaxLength(500);
		message.setTextColor(Theme.TEXT);
		message.setHint(Component.translatable("orbit_cosmetic.social.share.message").withStyle(ChatFormatting.DARK_GRAY));
		// Friends to tick, in order: the keyboard goes through them before the message.
		rows.clear();
		List<Social.Person> friends = OrbitClient.get().social().friends();
		int y = 42;
		for (int i = 0; i < friends.size() && y + 24 < height - 60; i++) {
			UUID uuid = friends.get(i).uuid();
			rows.add(addRenderableWidget(new Hotspot(Component.literal(friends.get(i).name()), h -> toggle(uuid)).place(x, y, 280, 22)));
			y += 24;
		}
		addRenderableWidget(message);
		addRenderableWidget(new FlatButton(x, height - 30, 138, 20, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
		send = addRenderableWidget(new FlatButton(x + 142, height - 30, 138, 20, Component.translatable("orbit_cosmetic.social.send"), "send", FlatButton.Style.PRIMARY,
			b -> send()));
		send.active = !chosen.isEmpty();
		if (!rows.isEmpty()) {
			setInitialFocus(rows.getFirst());
		}
	}

	private void toggle(UUID friend) {
		if (!chosen.remove(friend)) {
			chosen.add(friend);
		}
		send.active = !chosen.isEmpty();
	}

	private void send() {
		if (chosen.isEmpty()) {
			return;
		}
		JsonObject sent = content.deepCopy();
		if (!message.getValue().isBlank()) {
			sent.addProperty("text", message.getValue().strip());
		}
		List<UUID> to = List.copyOf(chosen);
		Runnable go = () -> OrbitClient.get().social().send(to, sent, picture,
			() -> Notifications.success(Component.translatable("orbit_cosmetic.social.share.sent"), Component.empty()));
		if (prepare != null) {
			prepare.accept(go);
		} else {
			go.run();
		}
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Enter in the message sends (Enter on a friend ticks them).
		if (message.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			send();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = width / 2 - 140;
		Theme.panel(graphics, x - 6, 6, 292, height - 12);
		graphics.text(font, title, x, 14, Theme.TEXT, false);
		graphics.text(font, Theme.clip(font, what.copy().withStyle(ChatFormatting.GRAY), 280), x, 26, Theme.TEXT, false);
		Social social = OrbitClient.get().social();
		List<Social.Person> friends = social.friends();
		if (friends.isEmpty()) {
			graphics.text(font, Component.translatable("orbit_cosmetic.social.no_friends_short").withStyle(ChatFormatting.GRAY), x, 46, Theme.TEXT, false);
		}
		for (int i = 0; i < rows.size() && i < friends.size(); i++) {
			Social.Person friend = friends.get(i);
			Hotspot row = rows.get(i);
			int y = row.getY();
			boolean on = chosen.contains(friend.uuid());
			graphics.fill(x, y, x + 280, y + 22, on ? Theme.PANEL_HOVER : row.isHovered() ? Theme.PANEL_2 : Theme.PANEL);
			PlayerFaceExtractor.extractRenderState(graphics, social.skin(friend.uuid(), friend.name()), x + 4, y + 3, 16);
			graphics.text(font, friend.name(), x + 26, y + 7, Theme.TEXT, false);
			graphics.outline(x + 262, y + 6, 10, 10, on ? Theme.TEXT : Theme.BORDER_LIGHT);
			if (on) {
				Theme.icon(graphics, "check_small", x + 263, y + 6, Theme.GREEN);
			}
			row.drawFocus(graphics);
		}
		graphics.fill(x, height - 58, x + 280, height - 42, Theme.PANEL_2);
		graphics.outline(x, height - 58, 280, 16, message.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
