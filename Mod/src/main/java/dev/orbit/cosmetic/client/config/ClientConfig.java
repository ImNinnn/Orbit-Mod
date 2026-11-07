package dev.orbit.cosmetic.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.net.OrbitCloud;
import dev.orbit.cosmetic.common.model.ModelLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Local preferences, saved as {@code config/orbit_cosmetic.json}. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum AttachMode {
		/** Follow the parts of the model actually being rendered (vanilla, or EMF/Fresh Animations). */
		ANIMATED_MODEL,
		/** Always follow a private vanilla player model, ignoring model replacement mods. */
		VANILLA
	}

	public boolean renderCosmetics = true;
	public boolean showOtherPlayers = true;
	public boolean showInFirstPerson = true;
	public boolean playAnimations = true;
	public boolean showNameplateIcons = true;
	/** Show your own nameplate in third-person view. */
	public boolean showOwnNameplate = false;
	public boolean downloadFromServers = true;
	/** Share cosmetics and emotes with other Orbit players through Orbit Cloud (on every multiplayer server). */
	public boolean useBackend = true;
	/** Cosmetics on players further away than this many blocks are not rendered. */
	public int renderDistance = 64;
	/** Animations of players further away than this many blocks are frozen. */
	public int animationDistance = 32;
	/** Performance limit: faces per player before further cosmetics on that player are skipped. */
	public int maxFacesPerPlayer = 8192;
	public int maxCacheMegabytes = 128;
	public AttachMode attachMode = AttachMode.ANIMATED_MODEL;
	/** Chained animations fade into each other instead of jumping (see AnimationPlayer#setSmoothing). */
	public boolean smoothAnimations = true;
	/** New screenshots show as a picture in the corner (with Edit, Favorite, Copy, Send) instead of a chat line. */
	public boolean screenshotNotifications = true;
	/** On-screen notifications (loading cosmetics, connections, bans, uploads). */
	public boolean showNotifications = true;
	/** When Minecraft starts, ask Orbit Cloud for a newer Orbit Cosmetic and offer to install it (read by UpdateCheck). */
	public boolean checkUpdates = true;
	/** The version of the Terms of Service and Privacy Policy accepted (see {@link OrbitCloud#TERMS_VERSION}); read by UpdateCheck. */
	public String termsAccepted = "";
	/** Players whose cosmetics you hid in the Players menu (UUIDs). */
	public List<String> hiddenPlayers = new ArrayList<>();
	/** Show the emotes other Orbit players play. */
	public boolean showOtherEmotes = true;
	/** The title and pause screens show your player, wearing your cosmetics and now and then playing an emote. */
	public boolean menuPlayer = true;
	/** "<friend> is online" notifications. */
	public boolean friendOnlineNotifications = true;
	/** Friends can see your statistics page (time played, screenshots, wardrobe); off removes it from Orbit Cloud. */
	public boolean shareStatistics = true;
	/** The colour of your statistics page (RGB). */
	public int statsColor = 0xC21E1E;
	/** The profile on your statistics page: a description, pronouns, and the name of the outfit it shows ("" for none). */
	public String profileDescription = "";
	public String profilePronouns = "";
	public String profileOutfit = "";
	/** The newer version whose prompt the player turned off ("Don't show this again"); a later one asks again. */
	public String updatePromptSkipped = "";
	/** Show what you do in Orbit on your Discord profile (Rich Presence). */
	public boolean discordPresence = true;
	/** Orbit's screens on a grid; off shows Minecraft's own menu background. */
	public boolean gridBackground = true;
	/** Orbit's buttons drawn with Minecraft's button texture. */
	public boolean vanillaButtons = false;
	/** A joke: made-up coin prices on everything, and a coin balance you can top up for free. Nothing is ever sold. */
	public boolean jokeCoins = false;
	/** The joke coin balance. */
	public long jokeCoinBalance = 375;

	private transient Path file;

	public static ClientConfig load(Path file) {
		ClientConfig config = new ClientConfig();
		if (Files.exists(file)) {
			try {
				ClientConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				OrbitClient.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		config.file = file;
		config.normalize();
		config.save();
		return config;
	}

	public void normalize() {
		renderDistance = clamp(renderDistance, 8, 512);
		animationDistance = clamp(animationDistance, 4, renderDistance);
		maxFacesPerPlayer = clamp(maxFacesPerPlayer, 100, ModelLimits.HARD.maxQuads() * 4);
		maxCacheMegabytes = clamp(maxCacheMegabytes, 8, 4096);
		jokeCoinBalance = Math.max(0, Math.min(jokeCoinBalance, 999_999_999L));
		statsColor &= 0xFFFFFF;
		profileDescription = profileDescription == null ? "" : profileDescription.length() > 160 ? profileDescription.substring(0, 160) : profileDescription;
		profilePronouns = profilePronouns == null ? "" : profilePronouns.length() > 24 ? profilePronouns.substring(0, 24) : profilePronouns;
		if (profileOutfit == null) {
			profileOutfit = "";
		}
		if (termsAccepted == null) {
			termsAccepted = "";
		}
		if (attachMode == null) {
			attachMode = AttachMode.ANIMATED_MODEL;
		}
		LinkedHashSet<String> hidden = new LinkedHashSet<>();
		if (hiddenPlayers != null) {
			for (String value : hiddenPlayers) {
				try {
					hidden.add(UUID.fromString(value).toString().toLowerCase(Locale.ROOT));
				} catch (IllegalArgumentException | NullPointerException ignored) {
					// dropped
				}
			}
		}
		hiddenPlayers = new ArrayList<>(hidden.stream().limit(2000).toList());
	}

	/** Orbit Cloud may be used: the current terms were accepted. */
	public boolean hasAcceptedTerms() {
		return OrbitCloud.TERMS_VERSION.equals(termsAccepted);
	}

	public boolean isHidden(UUID player) {
		return hiddenPlayers.contains(player.toString());
	}

	public void setHidden(UUID player, boolean hidden) {
		hiddenPlayers.remove(player.toString());
		if (hidden) {
			hiddenPlayers.add(player.toString());
		}
		save();
	}

	public void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save {}", file, e);
		}
	}

	/** Limits for models this client loads, locally or from servers. */
	public ModelLimits modelLimits() {
		return ModelLimits.DEFAULT;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
