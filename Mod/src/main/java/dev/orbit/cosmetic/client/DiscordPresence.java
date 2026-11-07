package dev.orbit.cosmetic.client;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.AnimationSettingsScreen;
import dev.orbit.cosmetic.client.gui.BodyPartScreen;
import dev.orbit.cosmetic.client.gui.OrbitMenuScreen;
import dev.orbit.cosmetic.client.gui.OrbitSettingsScreen;
import dev.orbit.cosmetic.client.gui.ScreenshotEditScreen;
import dev.orbit.cosmetic.client.gui.ScreenshotViewScreen;
import dev.orbit.cosmetic.client.gui.ScreenshotsScreen;
import dev.orbit.cosmetic.client.host.Hosting;
import dev.orbit.cosmetic.client.social.SocialScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence: one line, what the player does (on a server, in singleplayer, hosting, in the menus), under
 * the Discord application's name ("Orbit Mod - 1.0.0" when pointing at the icon), shown on their Discord profile
 * while the Discord app runs on this computer. Discord always titles it with the application's name: a name sent
 * from here is ignored. Talks to Discord's local
 * socket (named pipe on Windows) directly; nothing leaves the computer except through Discord. Turned off with
 * Discord status in the settings.
 */
public final class DiscordPresence {
	private static final String APP_ID = "1564248359328547007";
	private static final String IMAGE = "https://orbitcosmetics.dev/assets/img/icon-180.png";
	private static final long RETRY_MS = 15_000;
	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Orbit Discord");
		thread.setDaemon(true);
		return thread;
	});
	private static final long STARTED = System.currentTimeMillis() / 1000;

	/** Only touched by the worker thread. */
	private static Pipe pipe;
	private static volatile long nextAttempt;
	/** What was last sent (game thread), or null for nothing shown. */
	private static String shown;
	private static int ticks;

	private DiscordPresence() {
	}

	static void tick(Minecraft minecraft) {
		if (++ticks % 20 != 0) {
			return;
		}
		String state = OrbitClient.get().config().discordPresence ? state(minecraft) : null;
		if (Objects.equals(state, shown)) {
			// Unchanged; try again now and then while Discord is not there.
			if (state == null || System.currentTimeMillis() < nextAttempt) {
				return;
			}
		}
		shown = state;
		String details = "Orbit Mod - " + dev.orbit.cosmetic.client.Updates.modVersion();
		WORKER.execute(() -> send(details, state));
	}

	/** What the player does, as shown under the game. */
	private static String state(Minecraft minecraft) {
		Screen screen = Compat.screen();
		String key;
		Object argument = null;
		if (screen instanceof SocialScreen || screen != null && screen.getClass().getPackageName().endsWith(".social")) {
			key = "chatting";
		} else if (screen instanceof OrbitMenuScreen || screen instanceof AnimationSettingsScreen || screen instanceof BodyPartScreen
			|| screen != null && screen.getClass().getSimpleName().matches("(Particle|Physics).*Screen")) {
			key = "cosmetics";
		} else if (screen instanceof OrbitSettingsScreen || screen != null && screen.getClass().getPackageName().equals("net.minecraft.client.gui.screens.options")) {
			key = "settings";
		} else if (screen instanceof ScreenshotsScreen || screen instanceof ScreenshotViewScreen || screen instanceof ScreenshotEditScreen) {
			key = "screenshots";
		} else if (minecraft.level == null) {
			key = "menu";
		} else if (Hosting.isHosting()) {
			key = "hosting";
		} else if (minecraft.getSingleplayerServer() != null) {
			key = "singleplayer";
		} else {
			ServerData server = minecraft.getCurrentServer();
			key = "server";
			argument = server == null ? "?" : server.ip;
		}
		return argument == null ? Component.translatable("orbit_cosmetic.discord." + key).getString()
			: Component.translatable("orbit_cosmetic.discord." + key, argument).getString();
	}

	// ---------------------------------------------------------------- the worker thread

	private static void send(String details, String state) {
		try {
			if (pipe == null) {
				if (state == null || System.currentTimeMillis() < nextAttempt) {
					return;
				}
				nextAttempt = System.currentTimeMillis() + RETRY_MS;
				pipe = Pipe.open();
				if (pipe == null) {
					return;
				}
				JsonObject hello = new JsonObject();
				hello.addProperty("v", 1);
				hello.addProperty("client_id", APP_ID);
				pipe.write(OP_HANDSHAKE, hello);
				pipe.read();
			}
			JsonObject args = new JsonObject();
			args.addProperty("pid", ProcessHandle.current().pid());
			if (state != null) {
				JsonObject activity = new JsonObject();
				// One line: what the player does.
				activity.addProperty("details", state);
				JsonObject timestamps = new JsonObject();
				timestamps.addProperty("start", STARTED);
				activity.add("timestamps", timestamps);
				JsonObject assets = new JsonObject();
				assets.addProperty("large_image", IMAGE);
				assets.addProperty("large_text", details);
				activity.add("assets", assets);
				args.add("activity", activity);
			}
			JsonObject command = new JsonObject();
			command.addProperty("cmd", "SET_ACTIVITY");
			command.add("args", args);
			command.addProperty("nonce", UUID.randomUUID().toString());
			pipe.write(OP_FRAME, command);
			pipe.read();
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.debug("Discord status: {}", e.getMessage());
			close();
		}
	}

	private static void close() {
		if (pipe != null) {
			try {
				pipe.close();
			} catch (IOException ignored) {
				// gone already
			}
			pipe = null;
		}
	}

	/** Discord's local IPC socket: frames of an opcode and a length (little-endian), then JSON. */
	private abstract static class Pipe implements Closeable {
		abstract void writeBytes(byte[] bytes) throws IOException;

		abstract void readFully(byte[] bytes) throws IOException;

		void write(int op, JsonObject json) throws IOException {
			byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
			ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
			frame.putInt(op).putInt(body.length).put(body);
			writeBytes(frame.array());
		}

		/** Reads (and drops) one answer. */
		void read() throws IOException {
			byte[] header = new byte[8];
			readFully(header);
			int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
			if (length < 0 || length > 1 << 20) {
				throw new IOException("Unexpected answer from Discord");
			}
			readFully(new byte[length]);
		}

		static Pipe open() {
			boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
			for (int i = 0; i < 10; i++) {
				if (windows) {
					try {
						return new WindowsPipe(new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"));
					} catch (IOException ignored) {
						// not this one
					}
					continue;
				}
				for (Path folder : unixFolders()) {
					Path socket = folder.resolve("discord-ipc-" + i);
					if (!Files.exists(socket)) {
						continue;
					}
					try {
						SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
						channel.connect(UnixDomainSocketAddress.of(socket));
						return new UnixPipe(channel);
					} catch (IOException ignored) {
						// not this one
					}
				}
			}
			return null;
		}

		/** Where Discord puts its socket: the runtime and temp folders, and inside them Flatpak and Snap installs. */
		private static List<Path> unixFolders() {
			List<Path> roots = new ArrayList<>();
			for (String name : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
				String value = System.getenv(name);
				if (value != null && !value.isBlank()) {
					roots.add(Path.of(value));
				}
			}
			roots.add(Path.of("/tmp"));
			List<Path> folders = new ArrayList<>();
			for (Path root : roots) {
				folders.add(root);
				folders.add(root.resolve("app/com.discordapp.Discord"));
				folders.add(root.resolve("app/dev.vencord.Vesktop"));
				folders.add(root.resolve("snap.discord"));
			}
			return folders;
		}
	}

	private static final class UnixPipe extends Pipe {
		private final SocketChannel channel;

		UnixPipe(SocketChannel channel) {
			this.channel = channel;
		}

		@Override
		void writeBytes(byte[] bytes) throws IOException {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
		}

		@Override
		void readFully(byte[] bytes) throws IOException {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) {
					throw new IOException("Discord closed the connection");
				}
			}
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	}

	private static final class WindowsPipe extends Pipe {
		private final RandomAccessFile file;

		WindowsPipe(RandomAccessFile file) {
			this.file = file;
		}

		@Override
		void writeBytes(byte[] bytes) throws IOException {
			file.write(bytes);
		}

		@Override
		void readFully(byte[] bytes) throws IOException {
			file.readFully(bytes);
		}

		@Override
		public void close() throws IOException {
			file.close();
		}
	}
}
