package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gfx.OrbitImage;
import dev.orbit.cosmetic.client.gfx.OrbitTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.Identifier;
//? if >=1.21.11
import net.minecraft.server.network.EventLoopGroupHolder;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server addresses in messages: hosted worlds (e4mc links) and anything that looks like a server ("play.x.net",
 * "host:25565"). Each becomes a card with the server's icon, description (MOTD) and player count, pinged like the
 * server list, and a Join button. An invite carries the icon and description it had when sent, shown until the
 * ping answers (or if the server does not).
 */
public final class ServerLinks {
	private static final Pattern ADDRESS = Pattern.compile("(?<![\\w.-])((?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{2,5})?|\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{2,5})(?![\\w-])",
		Pattern.CASE_INSENSITIVE);

	/** Where servers are pinged. */
	private static final ExecutorService PINGS = Executors.newFixedThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "Orbit server ping");
		thread.setDaemon(true);
		return thread;
	});

	private final ServerStatusPinger pinger = new ServerStatusPinger();
	private final Map<String, ServerData> servers = new HashMap<>();
	private final Map<String, Long> pingedAt = new HashMap<>();
	/** Server icons by address (textures), and the icon bytes each shows. */
	private final Map<String, Identifier> icons = new HashMap<>();
	private int nextIcon;
	private final Map<String, byte[]> iconBytes = new HashMap<>();

	/**
	 * How a ping went: the game's own states, which only exist since 1.20.2 (before, Orbit keeps them, see
	 * {@link #ping}). OTHER is anything else (not pinged yet, an incompatible server).
	 */
	public enum Ping {
		PINGING, ANSWERED, UNREACHABLE, OTHER
	}

	//? if <1.20.2 {
	/*/^* The states of the servers pinged here (before 1.20.2 the game keeps none). ^/
	private static final Map<ServerData, Ping> PINGED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
	*///?}

	/** How the server's ping went. */
	public static Ping ping(ServerData data) {
		//? if >=1.20.2 {
		return switch (data.state()) {
			case PINGING -> Ping.PINGING;
			case SUCCESSFUL -> Ping.ANSWERED;
			case UNREACHABLE -> Ping.UNREACHABLE;
			default -> Ping.OTHER;
		};
		//?} else {
		/*return PINGED.getOrDefault(data, Ping.OTHER);
		*///?}
	}

	private static void setPing(ServerData data, Ping ping) {
		//? if >=1.20.2 {
		data.setState(switch (ping) {
			case PINGING -> ServerData.State.PINGING;
			case ANSWERED -> ServerData.State.SUCCESSFUL;
			case UNREACHABLE -> ServerData.State.UNREACHABLE;
			case OTHER -> ServerData.State.INITIAL;
		});
		//?} else {
		/*PINGED.put(data, ping);
		*///?}
	}

	/** A server for the game's lists and its connect screen (not a LAN one). */
	public static ServerData server(String name, String address) {
		//? if >=1.20.2 {
		return new ServerData(name, address, ServerData.Type.OTHER);
		//?} else {
		/*return new ServerData(name, address, false);
		*///?}
	}

	/** A server's icon (PNG bytes) if the game accepts it, else null. */
	public static byte @Nullable [] validIcon(byte @Nullable [] png) {
		//? if >=1.20.2 {
		return ServerData.validateIcon(png);
		//?} else {
		/*// Before 1.20.2 the game checks icons only when it shows them: Orbit checks the size itself (see icon).
		return png;
		*///?}
	}

	/** Server addresses in a text, at most three. */
	public static List<String> find(String text) {
		List<String> found = new ArrayList<>();
		Matcher matcher = ADDRESS.matcher(text);
		while (matcher.find() && found.size() < 3) {
			String address = matcher.group(1).toLowerCase(Locale.ROOT);
			boolean server = address.endsWith(".e4mc.link") || address.contains(":") || address.startsWith("mc.") || address.startsWith("play.")
				|| address.startsWith("hub.") || address.startsWith("join.");
			if (server && !found.contains(address)) {
				found.add(address);
			}
		}
		return found;
	}

	/**
	 * The server's status, pinged the first time it is asked for (it fills in a moment later). A ping that has not
	 * answered after 10 seconds failed (Minecraft's pinger leaves those "pinging").
	 */
	public ServerData status(String address, @Nullable String name) {
		ServerData known = servers.get(address);
		if (known != null && ping(known) == Ping.PINGING && System.currentTimeMillis() - pingedAt.getOrDefault(address, 0L) > 10_000) {
			setPing(known, Ping.UNREACHABLE);
		}
		pingedAt.putIfAbsent(address, System.currentTimeMillis());
		return servers.computeIfAbsent(address, key -> {
			ServerData data = server(name == null ? address : name, address);
			setPing(data, Ping.PINGING);
			// Off the game thread: looking the address up and connecting can take seconds (as Minecraft's list does).
			boolean nativeTransport = Minecraft.getInstance().options.useNativeTransport();
			PINGS.execute(() -> {
				try {
					//? if >=1.21.11 {
					pinger.pingServer(data, () -> {
					}, () -> setPing(data, Ping.ANSWERED), EventLoopGroupHolder.remote(nativeTransport));
					//?} else if >=1.20.2 {
					/*pinger.pingServer(data, () -> {
					}, () -> setPing(data, Ping.ANSWERED));
					*///?} else {
					/*// Before 1.20.2 the pinger calls back once the server answered.
					pinger.pingServer(data, () -> setPing(data, Ping.ANSWERED));
					*///?}
				} catch (Exception e) {
					setPing(data, Ping.UNREACHABLE);
				}
			});
			return data;
		});
	}

	/**
	 * The server's icon (64x64), or null for none: from the ping, else the PNG an invite carried
	 * ({@code shared}, base64).
	 */
	public @Nullable Identifier icon(String address, @Nullable String shared) {
		ServerData data = servers.get(address);
		byte[] bytes = data == null ? null : validIcon(data.getIconBytes());
		if (bytes == null && shared != null) {
			try {
				bytes = validIcon(Base64.getDecoder().decode(shared));
			} catch (IllegalArgumentException e) {
				bytes = null;
			}
		}
		if (bytes == null) {
			return null;
		}
		Identifier icon = icons.get(address);
		if (icon != null && Arrays.equals(iconBytes.get(address), bytes)) {
			return icon;
		}
		iconBytes.put(address, bytes);
		try {
			OrbitImage image = OrbitImage.read(bytes);
			if (image.width() != 64 || image.height() != 64) {
				image.close();
				return null;
			}
			if (icon == null) {
				icon = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "server_icon/" + nextIcon++);
				icons.put(address, icon);
			}
			// A new picture under the same name replaces the old one.
			OrbitTextures.register(icon, "Orbit server icon " + address, image);
			return icon;
		} catch (IOException | RuntimeException e) {
			// Not a 64x64 PNG: no icon.
			return null;
		}
	}

	public void tick() {
		pinger.tick();
	}

	public void close() {
		pinger.removeAll();
		servers.clear();
		pingedAt.clear();
		icons.values().forEach(OrbitTextures::release);
		icons.clear();
		iconBytes.clear();
	}

	/** Joins a server (leaving the world first, saving it). */
	public static void join(@Nullable Screen parent, String address, String name) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!ServerAddress.isValidAddress(address)) {
			return;
		}
		if (minecraft.level != null) {
			//? if >=1.21.2 {
			minecraft.disconnectWithSavingScreen();
			//?} else if >=1.20.2 {
			/*// As the pause menu leaves a world before 1.21.2.
			boolean local = minecraft.isLocalServer();
			minecraft.level.disconnect();
			if (local) {
				minecraft.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
			} else {
				minecraft.disconnect();
			}
			*///?} else {
			/*boolean local = minecraft.isLocalServer();
			minecraft.level.disconnect();
			if (local) {
				minecraft.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
			} else {
				minecraft.clearLevel();
			}
			*///?}
			parent = new TitleScreen();
		}
		ServerData data = server(name, address);
		//? if >=1.20.5 {
		ConnectScreen.startConnecting(parent == null ? new TitleScreen() : parent, minecraft, ServerAddress.parseString(address), data, false, null);
		//?} else {
		/*ConnectScreen.startConnecting(parent == null ? new TitleScreen() : parent, minecraft, ServerAddress.parseString(address), data, false);
		*///?}
	}

	/** Leaves menus of the game to the title screen: used when joining from them. */
	public static void home() {
		Compat.setScreen(new TitleScreen());
	}
}
