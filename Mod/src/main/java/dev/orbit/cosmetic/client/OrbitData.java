package dev.orbit.cosmetic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.orbit.cosmetic.client.net.OrbitCloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Orbit's folder outside the Minecraft instance, shared by every instance and launcher of this computer user (like
 * Essential's {@code gg.essential.mod}): {@code %APPDATA%\gg.orbit.mod} on Windows,
 * {@code ~/Library/Application Support/gg.orbit.mod} on macOS, {@code $XDG_DATA_HOME/gg.orbit.mod} (else
 * {@code ~/.local/share/gg.orbit.mod}) elsewhere. A Flatpak launcher's sandbox puts it in that launcher's own data
 * folder.
 * <ul>
 *   <li>{@code settings.json}: what is the same on every instance (update checks, the terms accepted, Discord, the
 *   joke coins)</li>
 *   <li>{@code accounts/<uuid>/profile.json}: a copy of what Orbit Cloud keeps for the account (its page and hidden
 *   players), for when Orbit Cloud cannot be reached, with what changed since</li>
 *   <li>{@code accounts/<uuid>/device.json}: the account's key for end-to-end encrypted messages, private to this
 *   computer user, so every instance reads the same messages</li>
 *   <li>{@code logs/}: Orbit's log, {@code latest.log} for this game, the 9 before it compressed</li>
 * </ul>
 */
public final class OrbitData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String NAME = "gg.orbit.mod";
	private static final int LOGS_KEPT = 10;

	/** What is the same on every instance. */
	public static final class Settings {
		public boolean checkUpdates = true;
		/** The version of the terms of Orbit Cloud accepted, "" for none (Orbit Cloud is not used before). */
		public String termsAccepted = "";
		public boolean discordPresence = true;
		public boolean jokeCoins = false;
		public long jokeCoinBalance = 375;
		/** Risky: party commands on servers with their own parties (Hypixel) sent on their own, not on a click. */
		public boolean autoServerParty = false;

		/** Orbit Cloud may be used: the current terms were accepted. */
		public boolean hasAcceptedTerms() {
			return OrbitCloud.TERMS_VERSION.equals(termsAccepted);
		}

		public void save() {
			write(folder().resolve("settings.json"), this);
		}
	}

	/** What Orbit Cloud keeps for an account, as last seen (and changed here since, when it could not be reached). */
	public static final class Account {
		public int statsColor = 0xC21E1E;
		public String description = "";
		public String pronouns = "";
		/** The outfit the page shows, by name ("" for none). */
		public String outfit = "";
		public List<String> hiddenPlayers = new ArrayList<>();
		/** Changed here and not sent to Orbit Cloud yet: sent instead of taking Orbit Cloud's next time. */
		public boolean profileChanged;
		public boolean hiddenChanged;
		private transient UUID uuid;

		public boolean isHidden(UUID player) {
			return hiddenPlayers.contains(player.toString());
		}

		public void save() {
			write(accountFolder(uuid).resolve("profile.json"), this);
		}

		private void normalize() {
			statsColor &= 0xFFFFFF;
			description = clip(description, 160);
			pronouns = clip(pronouns, 24);
			outfit = clip(outfit, 64);
			LinkedHashSet<String> hidden = new LinkedHashSet<>();
			for (String value : hiddenPlayers == null ? List.<String>of() : hiddenPlayers) {
				try {
					hidden.add(UUID.fromString(value).toString().toLowerCase(Locale.ROOT));
				} catch (IllegalArgumentException | NullPointerException ignored) {
					// dropped
				}
			}
			hiddenPlayers = new ArrayList<>(hidden.stream().limit(2000).toList());
		}
	}

	private static Settings settings;
	private static final Map<UUID, Account> ACCOUNTS = new HashMap<>();

	private OrbitData() {
	}

	/** The folder (made if needed). */
	public static Path folder() {
		Path folder = base().resolve(NAME);
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			// Written to later; failing there is reported then.
		}
		return folder;
	}

	private static Path base() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return appData != null && !appData.isBlank() ? Path.of(appData) : Path.of(home, "AppData", "Roaming");
		}
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support");
		}
		String data = System.getenv("XDG_DATA_HOME");
		return data != null && !data.isBlank() ? Path.of(data) : Path.of(home, ".local", "share");
	}

	public static synchronized Settings settings() {
		if (settings == null) {
			settings = read(folder().resolve("settings.json"), Settings.class);
			if (settings == null) {
				settings = new Settings();
			}
			settings.jokeCoinBalance = Math.clamp(settings.jokeCoinBalance, 0, 999_999_999L);
			if (settings.termsAccepted == null) {
				settings.termsAccepted = "";
			}
		}
		return settings;
	}

	/** The settings read fresh from the file (before the game started: the update check). */
	public static Settings readSettings() {
		Settings read = read(folder().resolve("settings.json"), Settings.class);
		return read != null ? read : new Settings();
	}

	public static Path accountFolder(UUID account) {
		return folder().resolve("accounts").resolve(account.toString());
	}

	/** What Orbit Cloud keeps for this account, as last seen here. */
	public static synchronized Account account(UUID uuid) {
		return ACCOUNTS.computeIfAbsent(uuid, key -> {
			Account account = read(accountFolder(key).resolve("profile.json"), Account.class);
			if (account == null) {
				account = new Account();
			}
			account.uuid = key;
			account.normalize();
			return account;
		});
	}

	/** The key for end-to-end encrypted messages of this account (the same file for every instance). */
	public static Path deviceKey(UUID account) {
		return accountFolder(account).resolve("device.json");
	}

	private static String clip(String text, int max) {
		return text == null ? "" : text.length() > max ? text.substring(0, max) : text;
	}

	private static <T> T read(Path file, Class<T> type) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
		} catch (IOException | JsonParseException e) {
			System.err.println("[Orbit] Could not read " + file + ": " + e);
			return null;
		}
	}

	private static void write(Path file, Object value) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
			Files.writeString(temp, GSON.toJson(value), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save {}: {}", file, e.toString());
		}
	}

	// ---------------------------------------------------------------- logs

	private static FileChannel logLock;

	/**
	 * The file this game's Orbit log goes to: {@code logs/latest.log}, the previous one compressed first and only the
	 * last 10 kept. Another game running at the same time keeps {@code latest.log}: this one then writes beside it,
	 * compressed when a game next starts.
	 */
	public static synchronized Path logFile() throws IOException {
		Path logs = folder().resolve("logs");
		Files.createDirectories(logs);
		logLock = FileChannel.open(logs.resolve("latest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		FileLock lock = logLock.tryLock();
		if (lock == null) {
			return logs.resolve("running-" + ProcessHandle.current().pid() + ".log");
		}
		// Every finished log (the previous latest, and those of games that ran beside it) compressed by its date.
		try (Stream<Path> files = Files.list(logs)) {
			for (Path log : files.filter(file -> file.getFileName().toString().endsWith(".log") && !stillRunning(file)).toList()) {
				compress(log, logs);
			}
		}
		try (Stream<Path> files = Files.list(logs)) {
			List<Path> old = files.filter(file -> file.getFileName().toString().endsWith(".log.gz"))
				.sorted(Comparator.comparing(file -> file.getFileName().toString())).toList();
			for (int i = 0; i < old.size() - (LOGS_KEPT - 1); i++) {
				Files.deleteIfExists(old.get(i));
			}
		}
		return logs.resolve("latest.log");
	}

	/** A log of a game that is still running (written beside the latest): left alone. */
	private static boolean stillRunning(Path log) {
		String name = log.getFileName().toString();
		if (!name.startsWith("running-")) {
			return false;
		}
		try {
			return ProcessHandle.of(Long.parseLong(name.substring(8, name.length() - 4))).map(ProcessHandle::isAlive).orElse(false);
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static void compress(Path log, Path logs) throws IOException {
		LocalDateTime when = LocalDateTime.ofInstant(Files.getLastModifiedTime(log).toInstant(), ZoneId.systemDefault());
		String name = when.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
		Path target = logs.resolve(name + ".log.gz");
		for (int n = 2; Files.exists(target); n++) {
			target = logs.resolve(name + "-" + n + ".log.gz");
		}
		try (InputStream in = Files.newInputStream(log); OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
			in.transferTo(out);
		}
		Files.delete(log);
	}
}
