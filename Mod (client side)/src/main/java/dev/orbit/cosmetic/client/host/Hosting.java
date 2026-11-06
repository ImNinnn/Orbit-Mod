package dev.orbit.cosmetic.client.host;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosting the open singleplayer world for friends, through e4mc (an optional mod that puts LAN worlds online at
 * an address like {@code xyz.e4mc.link}). The address e4mc writes in the chat is kept, to invite friends with.
 */
public final class Hosting {
	private static final Pattern E4MC = Pattern.compile("([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.e4mc\\.link)", Pattern.CASE_INSENSITIVE);

	private static @Nullable String address;
	/** The address friends were told about, and when. */
	private static @Nullable String shared;
	private static long sharedAt;
	/** The world to open the host window for, once it has loaded (opened from the title screen). */
	private static boolean openWhenLoaded;

	private Hosting() {
	}

	public static boolean e4mcInstalled() {
		// Development runs can pretend it is there, to test the host window on LAN only (-Dorbit.testE4mc=true).
		return FabricLoader.getInstance().isModLoaded("e4mc") || FabricLoader.getInstance().isDevelopmentEnvironment() && Boolean.getBoolean("orbit.testE4mc");
	}

	/** The online address of the hosted world, or null. */
	public static @Nullable String address() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || !server.isPublished()) {
			address = null;
		}
		return address;
	}

	public static boolean isHosting() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server != null && server.isPublished();
	}

	/** Puts the world online (LAN, which e4mc makes reachable from anywhere). */
	public static boolean start(GameType mode, boolean cheats) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || server.isPublished()) {
			return false;
		}
		boolean started = Compat.publishWorld(server, mode, cheats, HttpUtil.getAvailablePort());
		if (!started) {
			Notifications.error(Component.translatable("orbit_cosmetic.host.failed"), Component.empty());
		}
		return started;
	}

	public static boolean stop() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || !Compat.unpublishWorld(server)) {
			return false;
		}
		address = null;
		return true;
	}

	/** Called for every chat line shown: picks up the address e4mc announces. */
	public static void chatLine(Component line) {
		Matcher matcher = E4MC.matcher(line.getString());
		if (matcher.find()) {
			address = matcher.group(1).toLowerCase(Locale.ROOT);
			Notifications.success(Component.translatable("orbit_cosmetic.host.online"), Component.literal(address));
		}
	}

	/** Shows the host window once the world being opened has loaded (false: it was cancelled). */
	public static void openWhenLoaded(boolean open) {
		openWhenLoaded = open;
	}

	/**
	 * Every tick: shows the host window once a world opened from it has loaded, and tells friends about the hosted
	 * world (through Orbit Cloud: their Multiplayer screen lists it), again every minute and when it closes.
	 */
	public static void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		String online = address();
		long now = System.currentTimeMillis();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (online != null && server != null && (!online.equals(shared) || now - sharedAt > 60_000)) {
			shared = online;
			sharedAt = now;
			JsonObject body = new JsonObject();
			body.addProperty("address", online);
			body.addProperty("world", server.getWorldData().getLevelName());
			body.addProperty("motd", server.getMotd());
			OrbitClient.get().backend().socialCall("PUT", "/hosting", body, ok -> {
			}, error -> OrbitClient.LOGGER.debug("Hosting: friends not told: {}", error));
		} else if (online == null && shared != null) {
			shared = null;
			OrbitClient.get().backend().socialCall("DELETE", "/hosting", null, ok -> {
			}, error -> OrbitClient.LOGGER.debug("Hosting: {}", error));
		}
		if (openWhenLoaded && minecraft.player != null && minecraft.getSingleplayerServer() != null && Compat.screen() == null) {
			openWhenLoaded = false;
			Compat.setScreen(new HostScreen(null));
		} else if (openWhenLoaded && minecraft.level == null && Compat.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
			openWhenLoaded = false;
		}
	}
}
