package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.OrbitPaths;
import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cosmetics, emotes and body parts shared in messages: what is sent ({@link #describe}), and Add to wardrobe, which
 * downloads the file from Orbit Cloud (checked against its SHA-256) into the player's own folders with its settings.
 */
public final class SharedCosmetics {
	private SharedCosmetics() {
	}

	/** What a message carries for a cosmetic (or body part); null when it cannot be shared (not loaded). */
	public static @Nullable JsonObject describe(LocalCosmetic cosmetic) {
		if (cosmetic.asset() == null || cosmetic.bytes() == null) {
			return null;
		}
		return describe(cosmetic.isBodyPart() ? "bodypart" : "cosmetic", cosmetic.displayName(), cosmetic.asset(), cosmetic.settingsPath());
	}

	public static @Nullable JsonObject describe(EmoteLibrary.LocalEmote emote) {
		if (emote.asset() == null || emote.file().bytes() == null) {
			return null;
		}
		Path model = emote.file().path();
		return describe("emote", emote.displayName(), emote.asset(), model.resolveSibling(model.getFileName().toString().replaceAll("\\.bbmodel$", ".json")));
	}

	private static JsonObject describe(String kind, String name, AssetId asset, Path settings) {
		JsonObject json = new JsonObject();
		json.addProperty("kind", kind);
		json.addProperty("name", name);
		json.addProperty("sha256", asset.hex());
		try {
			if (Files.isRegularFile(settings) && Files.size(settings) < 48 * 1024) {
				json.addProperty("settings", Files.readString(settings));
			}
		} catch (IOException ignored) {
			// shared without its settings
		}
		return json;
	}

	/** Downloads a shared item into Orbit/Cosmetics, Orbit/Emotes or Orbit/Body Parts. */
	public static void addToWardrobe(JsonObject shared) {
		String sha = shared.get("sha256").getAsString();
		AssetId asset = AssetId.tryParse(sha);
		String name = shared.get("name").getAsString();
		String kind = shared.has("kind") ? shared.get("kind").getAsString() : "cosmetic";
		if (asset == null) {
			return;
		}
		OrbitClient orbit = OrbitClient.get();
		boolean owned = kind.equals("emote") ? orbit.emotes().emotes().stream().anyMatch(e -> asset.equals(e.asset()))
			: orbit.library().entries().stream().anyMatch(e -> asset.equals(e.asset()));
		if (owned) {
			Notifications.info(Component.translatable("orbit_cosmetic.social.add.owned", name), Component.empty());
			return;
		}
		orbit.backend().socialDownloadModel(sha, bytes -> {
			OrbitPaths paths = orbit.paths();
			Path root = switch (kind) {
				case "emote" -> paths.emotes();
				case "bodypart" -> paths.bodyParts();
				default -> paths.cosmetics();
			};
			try {
				Path folder = CosmeticLibrary.uniqueFolder(root, CosmeticLibrary.folderName(name));
				Files.createDirectories(folder);
				Path model = folder.resolve(folder.getFileName() + ".bbmodel");
				if (shared.has("settings")) {
					Files.writeString(folder.resolve(folder.getFileName() + ".json"), shared.get("settings").getAsString(), StandardCharsets.UTF_8);
				}
				Files.write(model, bytes);
				orbit.library().reload();
				orbit.emotes().reload();
				Notifications.success(Component.translatable("orbit_cosmetic.social.add.done", name), Component.translatable("orbit_cosmetic.social.kind." + kind));
			} catch (IOException e) {
				Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(e.getMessage()));
			}
		}, error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}
}
