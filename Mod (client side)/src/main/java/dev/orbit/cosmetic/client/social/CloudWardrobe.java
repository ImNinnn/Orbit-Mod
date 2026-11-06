package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.net.CatalogItem;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The wardrobe that follows the account: the featured items the player claimed and their outfits, kept by Orbit
 * Cloud. When the game first reaches Orbit Cloud it brings back what this computer lacks (claimed items from the
 * catalog, outfits and their cosmetics from Orbit Cloud by SHA-256); what cannot be found anywhere is shown as
 * missing on its outfit. Then what this computer has is sent back whenever it changes. Game thread only.
 */
public final class CloudWardrobe {
	private static final long PUSH_EVERY_MS = 30_000;

	private boolean pulled;
	private boolean pulling;
	private long nextPush;
	private @Nullable String lastPushed;
	/** Outfit cosmetics being brought back: the outfit and the item (several outfits may wait for one file). */
	private final List<Pending> pending = new ArrayList<>();
	/** Files being downloaded for outfits, so each is downloaded once. */
	private final Set<String> fetching = new java.util.HashSet<>();
	/** Downloads and installs still under way; the summary is shown when none are left. */
	private int working;
	private int restored;
	private final Set<String> missing = new LinkedHashSet<>();
	/** Claimed items being brought back (outfits wait for them instead of downloading copies). */
	private final Set<String> restoringClaimed = new java.util.HashSet<>();

	private record Pending(String outfit, OrbitState.MissingItem item) {
	}

	/** Another account: everything starts again. */
	void reset() {
		pulled = false;
		pulling = false;
		lastPushed = null;
		pending.clear();
		fetching.clear();
		restoringClaimed.clear();
		working = 0;
		restored = 0;
		missing.clear();
	}

	/** Every tick while Orbit Cloud is reachable. */
	void tick() {
		if (!pulled) {
			if (!pulling) {
				pull();
			}
			return;
		}
		resolvePending();
		long now = System.currentTimeMillis();
		if (now >= nextPush) {
			nextPush = now + PUSH_EVERY_MS;
			push();
		}
	}

	/** The names of an outfit's things this computer lacks (cosmetics, and its skin). */
	public static List<String> missing(OrbitState.Outfit outfit) {
		List<String> names = new ArrayList<>();
		if (outfit.missing != null) {
			outfit.missing.forEach(item -> names.add(item.name));
		}
		if (outfit.skin != null && OrbitClient.get().skins().library().find(outfit.skin) == null) {
			names.add(Component.translatable("orbit_cosmetic.outfits.missing_skin").getString());
		}
		return names;
	}

	// ---------------------------------------------------------------- bringing it back

	private void pull() {
		pulling = true;
		OrbitClient orbit = OrbitClient.get();
		orbit.backend().socialCall("GET", "/wardrobe", null, wardrobe -> orbit.backend().loadCatalog(catalog -> merge(wardrobe), error -> merge(wardrobe)),
			error -> {
				pulling = false;
				nextPush = System.currentTimeMillis() + PUSH_EVERY_MS;
			});
	}

	private void merge(JsonObject wardrobe) {
		pulling = false;
		pulled = true;
		OrbitClient orbit = OrbitClient.get();
		if (wardrobe.get("claimed") instanceof JsonArray claimed) {
			for (JsonElement element : claimed) {
				if (element instanceof JsonObject item && !haveClaimed(item.get("sha256").getAsString())) {
					restoringClaimed.add(item.get("sha256").getAsString());
					restoreClaimed(item.get("sha256").getAsString(), item.get("name").getAsString(), item.get("kind").getAsString());
				}
			}
		}
		if (wardrobe.get("outfits") instanceof JsonArray outfits) {
			for (JsonElement element : outfits) {
				if (element instanceof JsonObject outfit && orbit.state().outfits().stream().noneMatch(o -> o.name.equals(outfit.get("name").getAsString()))) {
					restoreOutfit(outfit);
				}
			}
			orbit.state().save();
		}
		// Sent back right away: what this computer has that Orbit Cloud did not.
		nextPush = 0;
		summary();
	}

	private static boolean haveClaimed(String sha) {
		OrbitClient orbit = OrbitClient.get();
		AssetId asset = AssetId.tryParse(sha);
		return asset == null || orbit.library().findFeatured(asset) != null || orbit.emotes().findFeaturedFile(asset) != null;
	}

	/** A claimed item: from the catalog (with its settings), else Orbit Cloud's copy of the file. */
	private void restoreClaimed(String sha, String name, String kind) {
		OrbitClient orbit = OrbitClient.get();
		CatalogItem item = orbit.backend().catalog().items().stream().filter(i -> i.sha256().equals(sha)).findFirst().orElse(null);
		working++;
		if (item != null) {
			orbit.backend().downloadItem(item, (bytes, settings) -> installClaimed(sha, name, kind, bytes, settings, item.gradient()), error -> fromUploads(sha, name, kind));
		} else {
			fromUploads(sha, name, kind);
		}
	}

	private void fromUploads(String sha, String name, String kind) {
		OrbitClient.get().backend().socialDownloadModel(sha, bytes -> installClaimed(sha, name, kind, bytes, null, null), error -> {
			claimedFailed(sha, name);
			done();
		});
	}

	private void installClaimed(String sha, String name, String kind, byte[] bytes, byte @Nullable [] settings, @Nullable JsonElement gradient) {
		OrbitClient orbit = OrbitClient.get();
		try {
			if (kind.equals("emote")) {
				orbit.emotes().installFeatured(name, bytes, settings, gradient);
			} else {
				orbit.library().installFeatured(name, bytes, settings, gradient, 0, kind.equals("bodypart"), false);
			}
			restored++;
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Wardrobe: could not restore {}: {}", name, e.getMessage());
			claimedFailed(sha, name);
		}
		done();
	}

	/** A claimed item that could not be brought back: missing, also on the outfits waiting for it. */
	private void claimedFailed(String sha, String name) {
		unavailable(sha, name);
	}

	/** A file found neither here nor on Orbit Cloud: missing on every outfit waiting for it. */
	private void unavailable(String sha, String name) {
		missing.add(name);
		for (Pending waiting : List.copyOf(pending)) {
			if (waiting.item.sha256.equals(sha)) {
				pending.remove(waiting);
				markMissing(waiting.outfit, waiting.item);
			}
		}
	}

	private void restoreOutfit(JsonObject json) {
		OrbitClient orbit = OrbitClient.get();
		OrbitState.Outfit outfit = new OrbitState.Outfit(json.get("name").getAsString());
		outfit.missing = new ArrayList<>();
		if (json.get("items") instanceof JsonArray items) {
			for (JsonElement element : items) {
				JsonObject item = element.getAsJsonObject();
				OrbitState.MissingItem wanted = new OrbitState.MissingItem(item.get("sha256").getAsString(), item.get("name").getAsString(),
					item.has("style") ? item.get("style").getAsInt() : 0, item.has("body_part") && item.get("body_part").getAsBoolean());
				LocalCosmetic local = local(wanted.sha256);
				if (local != null) {
					outfit.equipped.add(local.key());
					outfit.styles.put(local.key(), wanted.style);
				} else if (restoringClaimed.contains(wanted.sha256)) {
					// A claimed item on its way: into the outfit once it is installed.
					pending.add(new Pending(outfit.name, wanted));
				} else {
					fetchForOutfit(outfit.name, wanted);
				}
			}
		}
		outfit.skin = json.get("skin") instanceof JsonElement skin && skin.isJsonPrimitive() ? skin.getAsString() : null;
		outfit.cape = json.get("cape") instanceof JsonElement cape && cape.isJsonPrimitive() ? cape.getAsString() : null;
		orbit.state().outfits().add(outfit);
	}

	private static @Nullable LocalCosmetic local(String sha) {
		AssetId asset = AssetId.tryParse(sha);
		return asset == null ? null : OrbitClient.get().library().find(asset);
	}

	/** An outfit's cosmetic this computer lacks: Orbit Cloud's copy, into the player's own folders. */
	private void fetchForOutfit(String outfit, OrbitState.MissingItem item) {
		OrbitClient orbit = OrbitClient.get();
		// Into the outfit once the library has read it.
		pending.add(new Pending(outfit, item));
		if (!fetching.add(item.sha256)) {
			return;
		}
		working++;
		orbit.backend().socialDownloadModel(item.sha256, bytes -> {
			try {
				Path root = item.bodyPart ? orbit.paths().bodyParts() : orbit.paths().cosmetics();
				Path folder = CosmeticLibrary.uniqueFolder(root, CosmeticLibrary.folderName(item.name));
				Files.createDirectories(folder);
				Files.write(folder.resolve(folder.getFileName() + ".bbmodel"), bytes);
				orbit.library().reload();
				restored++;
			} catch (IOException e) {
				unavailable(item.sha256, item.name);
			}
			done();
		}, error -> {
			unavailable(item.sha256, item.name);
			done();
		});
	}

	private void markMissing(String outfitName, OrbitState.MissingItem item) {
		missing.add(item.name);
		OrbitState.Outfit outfit = outfit(outfitName);
		if (outfit != null) {
			if (outfit.missing == null) {
				outfit.missing = new ArrayList<>();
			}
			outfit.missing.add(item);
			OrbitClient.get().state().save();
		}
	}

	private static OrbitState.@Nullable Outfit outfit(String name) {
		return OrbitClient.get().state().outfits().stream().filter(o -> o.name.equals(name)).findFirst().orElse(null);
	}

	/** Downloaded outfit cosmetics go into their outfit once the library has them. */
	private void resolvePending() {
		if (pending.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (Pending wanted : List.copyOf(pending)) {
			LocalCosmetic local = local(wanted.item.sha256);
			OrbitState.Outfit outfit = outfit(wanted.outfit);
			if (local == null) {
				continue;
			}
			pending.remove(wanted);
			if (outfit != null && !outfit.equipped.contains(local.key())) {
				outfit.equipped.add(local.key());
				outfit.styles.put(local.key(), wanted.item.style);
				changed = true;
			}
		}
		if (changed) {
			OrbitClient.get().state().save();
		}
	}

	private void done() {
		working--;
		summary();
	}

	/** Once everything was tried: what came back, and what could not be found. */
	private void summary() {
		if (working > 0) {
			return;
		}
		if (restored > 0) {
			Notifications.success(Component.translatable("orbit_cosmetic.wardrobe.restored", restored), Component.translatable("orbit_cosmetic.wardrobe.restored.detail"));
		}
		if (!missing.isEmpty()) {
			Notifications.warning(Component.translatable("orbit_cosmetic.wardrobe.missing", missing.size()), Component.literal(String.join(", ", missing)));
		}
		restored = 0;
		missing.clear();
	}

	// ---------------------------------------------------------------- sending it

	/** What this computer has, as Orbit Cloud keeps it. */
	private static JsonObject current() {
		OrbitClient orbit = OrbitClient.get();
		JsonArray claimed = new JsonArray();
		for (LocalCosmetic entry : orbit.library().entries()) {
			if (entry.isFeatured() && entry.asset() != null) {
				claimed.add(claimedJson(entry.asset().hex(), entry.isBodyPart() ? "bodypart" : "cosmetic", entry.displayName()));
			}
		}
		for (EmoteLibrary.EmoteFile file : orbit.emotes().files()) {
			if (file.isFeatured() && file.asset() != null) {
				claimed.add(claimedJson(file.asset().hex(), "emote", file.catalogName()));
			}
		}
		JsonArray outfits = new JsonArray();
		for (OrbitState.Outfit outfit : orbit.state().outfits()) {
			JsonObject json = new JsonObject();
			json.addProperty("name", outfit.name);
			JsonArray items = new JsonArray();
			for (LocalCosmetic entry : orbit.library().entries()) {
				if (outfit.equipped.contains(entry.key()) && entry.asset() != null) {
					items.add(itemJson(entry.asset().hex(), entry.displayName(), outfit.styles.getOrDefault(entry.key(), entry.style()), entry.isBodyPart()));
				}
			}
			if (outfit.missing != null) {
				// Kept for the computers that have them.
				outfit.missing.forEach(item -> items.add(itemJson(item.sha256, item.name, item.style, item.bodyPart)));
			}
			json.add("items", items);
			json.addProperty("skin", outfit.skin);
			json.addProperty("cape", outfit.cape);
			outfits.add(json);
		}
		JsonObject wardrobe = new JsonObject();
		wardrobe.add("claimed", claimed);
		wardrobe.add("outfits", outfits);
		return wardrobe;
	}

	private static JsonObject claimedJson(String sha, String kind, String name) {
		JsonObject json = new JsonObject();
		json.addProperty("sha256", sha);
		json.addProperty("kind", kind);
		json.addProperty("name", name.length() > 64 ? name.substring(0, 64) : name);
		return json;
	}

	private static JsonObject itemJson(String sha, String name, int style, boolean bodyPart) {
		JsonObject json = new JsonObject();
		json.addProperty("sha256", sha);
		json.addProperty("name", name.length() > 64 ? name.substring(0, 64) : name);
		json.addProperty("style", Math.clamp(style, 0, 15));
		json.addProperty("body_part", bodyPart);
		return json;
	}

	private void push() {
		OrbitClient orbit = OrbitClient.get();
		if (orbit.library().isScanning()) {
			// Not while the folders are being read: half a wardrobe would replace the whole one.
			nextPush = System.currentTimeMillis() + 2000;
			return;
		}
		JsonObject wardrobe = current();
		String text = wardrobe.toString();
		if (text.equals(lastPushed)) {
			return;
		}
		orbit.backend().socialCall("PUT", "/wardrobe", wardrobe, ok -> lastPushed = text, error -> OrbitClient.LOGGER.debug("Wardrobe: not saved: {}", error));
	}
}
