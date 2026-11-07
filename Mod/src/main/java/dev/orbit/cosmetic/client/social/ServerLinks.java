package dev.orbit.cosmetic.client.social;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.Identifier;
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
	/** Server icons by address, and the icon bytes each shows. */
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private final Map<String, byte[]> iconBytes = new HashMap<>();

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
		if (known != null && known.state() == ServerData.State.PINGING && System.currentTimeMillis() - pingedAt.getOrDefault(address, 0L) > 10_000) {
			known.setState(ServerData.State.UNREACHABLE);
		}
		pingedAt.putIfAbsent(address, System.currentTimeMillis());
		return servers.computeIfAbsent(address, key -> {
			ServerData data = new ServerData(name == null ? address : name, address, ServerData.Type.OTHER);
			data.setState(ServerData.State.PINGING);
			// Off the game thread: looking the address up and connecting can take seconds (as Minecraft's list does).
			boolean nativeTransport = Minecraft.getInstance().options.useNativeTransport();
			PINGS.execute(() -> {
				try {
					pinger.pingServer(data, () -> {
					}, () -> data.setState(ServerData.State.SUCCESSFUL), EventLoopGroupHolder.remote(nativeTransport));
				} catch (Exception e) {
					data.setState(ServerData.State.UNREACHABLE);
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
		byte[] bytes = data == null ? null : ServerData.validateIcon(data.getIconBytes());
		if (bytes == null && shared != null) {
			try {
				bytes = ServerData.validateIcon(Base64.getDecoder().decode(shared));
			} catch (IllegalArgumentException e) {
				bytes = null;
			}
		}
		if (bytes == null) {
			return null;
		}
		FaviconTexture icon = icons.get(address);
		if (icon != null && Arrays.equals(iconBytes.get(address), bytes)) {
			return icon.textureLocation();
		}
		iconBytes.put(address, bytes);
		try (NativeImage image = NativeImage.read(bytes)) {
			if (icon == null) {
				icon = FaviconTexture.forServer(Minecraft.getInstance().getTextureManager(), "orbit/" + address);
				icons.put(address, icon);
			}
			icon.upload(image);
			return icon.textureLocation();
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
		icons.values().forEach(FaviconTexture::close);
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
			minecraft.disconnectWithSavingScreen();
			parent = new TitleScreen();
		}
		ServerData data = new ServerData(name, address, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(parent == null ? new TitleScreen() : parent, minecraft, ServerAddress.parseString(address), data, false, null);
	}

	/** Leaves menus of the game to the title screen: used when joining from them. */
	public static void home() {
		Compat.setScreen(new TitleScreen());
	}
}
