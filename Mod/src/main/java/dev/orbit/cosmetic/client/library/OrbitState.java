package dev.orbit.cosmetic.client.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.orbit.cosmetic.client.OrbitClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player chose, kept in {@code Orbit/orbit-state.json}: equipped cosmetics and their alternate textures, how
 * each emote plays, the emote wheels, the skin and cape, and saved outfits. Cosmetics are referenced by their path inside {@code Orbit/Cosmetics}; emotes by
 * {@code <file inside Orbit/Emotes>#<animation name>}.
 */
public final class OrbitState {
	public static final int WHEEL_SLOTS = 8;
	public static final int MAX_WHEELS = 10;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** One emote wheel: a name and eight slots holding emote keys (or null). */
	public static final class Wheel {
		public String name;
		public String[] slots = new String[WHEEL_SLOTS];

		public Wheel(String name) {
			this.name = name;
		}
	}

	/**
	 * A saved look: cosmetics (with their alternate textures), and optionally a skin (a file in Orbit/Skins) and a cape
	 * (an ID, "" for none; null keeps whatever is worn).
	 */
	public static final class Outfit {
		public String name;
		public List<String> equipped = new ArrayList<>();
		public Map<String, Integer> styles = new LinkedHashMap<>();
		public String skin;
		public String cape;
		/**
		 * Cosmetics of the outfit (restored from Orbit Cloud) whose files are on neither this computer nor Orbit
		 * Cloud: shown as missing, and kept so the outfit stays whole for the other computers.
		 */
		public List<MissingItem> missing = new ArrayList<>();

		public Outfit(String name) {
			this.name = name;
		}
	}

	/** A cosmetic of an outfit this computer does not have: its file's SHA-256, name and alternate texture. */
	public static final class MissingItem {
		public String sha256;
		public String name;
		public int style;
		public boolean bodyPart;

		public MissingItem(String sha256, String name, int style, boolean bodyPart) {
			this.sha256 = sha256;
			this.name = name;
			this.style = style;
			this.bodyPart = bodyPart;
		}
	}

	private static final class Stored {
		List<String> equipped = new ArrayList<>();
		/** Cosmetic key -> chosen alternate texture; cosmetics not listed use the first. */
		Map<String, Integer> styles = new LinkedHashMap<>();
		List<Wheel> wheels = new ArrayList<>();
		int wheel;
		/** The skin worn (a file name in Orbit/Skins), or null for the account's own. */
		String skin;
		/** The cape worn: an ID, "" for none, or null for the account's own. */
		String cape;
		/** Skin file hash -> Mojang's texture of it, once Mojang accepted it (so switching back needs no upload). */
		Map<String, String> skinTextures = new LinkedHashMap<>();
		List<Outfit> outfits = new ArrayList<>();
		/** Seconds spent in worlds and on servers with Orbit. */
		long playedSeconds;
	}

	private final Path file;
	private Stored stored = new Stored();

	public OrbitState(Path file) {
		this.file = file;
		load();
	}

	public List<String> equipped() {
		return stored.equipped;
	}

	public Map<String, Integer> styles() {
		return stored.styles;
	}

	public String skin() {
		return stored.skin;
	}

	public void setSkin(String skin) {
		stored.skin = skin;
	}

	public String cape() {
		return stored.cape;
	}

	public void setCape(String cape) {
		stored.cape = cape;
	}

	public Map<String, String> skinTextures() {
		return stored.skinTextures;
	}

	public List<Outfit> outfits() {
		return stored.outfits;
	}

	public List<Wheel> wheels() {
		return stored.wheels;
	}

	public int selectedWheel() {
		return Math.floorMod(stored.wheel, stored.wheels.size());
	}

	public long playedSeconds() {
		return stored.playedSeconds;
	}

	/** Adds time played (saved with the next save). */
	public void addPlayed(long seconds) {
		stored.playedSeconds += seconds;
	}

	public void selectWheel(int index) {
		stored.wheel = Math.floorMod(index, stored.wheels.size());
		save();
	}

	private void load() {
		if (Files.exists(file)) {
			try {
				Stored loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
				if (loaded != null) {
					stored = loaded;
				}
			} catch (IOException | JsonParseException e) {
				OrbitClient.LOGGER.warn("Could not read {}", file, e);
			}
		}
		// Whatever the file holds, keep the shape the rest of the mod relies on.
		if (stored.equipped == null) {
			stored.equipped = new ArrayList<>();
		}
		if (stored.styles == null) {
			stored.styles = new LinkedHashMap<>();
		}
		stored.styles.values().removeIf(style -> style == null);
		if (stored.skinTextures == null) {
			stored.skinTextures = new LinkedHashMap<>();
		}
		if (stored.outfits == null) {
			stored.outfits = new ArrayList<>();
		}
		stored.outfits.removeIf(outfit -> outfit == null);
		for (Outfit outfit : stored.outfits) {
			outfit.name = outfit.name == null || outfit.name.isBlank() ? "Outfit" : outfit.name;
			outfit.equipped = outfit.equipped == null ? new ArrayList<>() : outfit.equipped;
			outfit.styles = outfit.styles == null ? new LinkedHashMap<>() : outfit.styles;
		}
		if (stored.wheels == null) {
			stored.wheels = new ArrayList<>();
		}
		stored.wheels.removeIf(wheel -> wheel == null);
		if (stored.wheels.size() > MAX_WHEELS) {
			stored.wheels = new ArrayList<>(stored.wheels.subList(0, MAX_WHEELS));
		}
		for (Wheel wheel : stored.wheels) {
			wheel.slots = wheel.slots == null ? new String[WHEEL_SLOTS] : Arrays.copyOf(wheel.slots, WHEEL_SLOTS);
			if (wheel.name == null || wheel.name.isBlank()) {
				wheel.name = "Wheel";
			}
		}
		if (stored.wheels.isEmpty()) {
			stored.wheels.add(new Wheel("Wheel #1"));
		}
	}

	public void save() {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(stored), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save {}", file, e);
		}
	}
}
