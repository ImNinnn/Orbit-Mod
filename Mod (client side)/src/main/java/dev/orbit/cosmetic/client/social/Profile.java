package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The player's profile, on their statistics page: a description, pronouns, and one of their outfits, shown on their
 * player. Kept in the settings and shared with the statistics (see {@link Social#shareStats}). Friends' outfits
 * are downloaded from Orbit Cloud by SHA-256.
 */
public final class Profile {
	public static final int MAX_DESCRIPTION = 160;
	public static final int MAX_PRONOUNS = 24;

	private static final Set<String> REQUESTED = new HashSet<>();
	private static final Map<String, Long> RETRY_AT = new HashMap<>();

	private Profile() {
	}

	/** The outfit the profile shows, or null for none (or one since deleted). */
	public static OrbitState.@Nullable Outfit outfit() {
		String name = OrbitClient.get().config().profileOutfit;
		return name.isEmpty() ? null : OrbitClient.get().state().outfits().stream().filter(outfit -> outfit.name.equals(name)).findFirst().orElse(null);
	}

	/** The cosmetics (and body parts) of the profile's outfit that are still in the library. */
	public static List<LocalCosmetic> outfitEntries() {
		OrbitState.Outfit outfit = outfit();
		List<LocalCosmetic> entries = new ArrayList<>();
		if (outfit != null) {
			for (LocalCosmetic entry : OrbitClient.get().library().entries()) {
				if (entry.isUsable() && entry.asset() != null && outfit.equipped.contains(entry.key())) {
					entries.add(entry);
				}
			}
		}
		return entries;
	}

	/** One's own outfit as the profile shows it, with the outfit's alternate textures. */
	public static List<EquippedCosmetic> ownOutfit() {
		OrbitState.Outfit outfit = outfit();
		List<EquippedCosmetic> worn = new ArrayList<>();
		for (LocalCosmetic entry : outfitEntries()) {
			worn.add(entry.toEquipped().withStyle(outfit.styles.getOrDefault(entry.key(), entry.style())));
		}
		return worn;
	}

	/** What the profile shares of the outfit: each file's SHA-256, alternate texture, and whether it is a body part. */
	public static JsonArray outfitJson() {
		OrbitState.Outfit outfit = outfit();
		JsonArray items = new JsonArray();
		for (LocalCosmetic entry : outfitEntries()) {
			JsonObject item = new JsonObject();
			item.addProperty("sha256", entry.asset().hex());
			item.addProperty("style", outfit.styles.getOrDefault(entry.key(), entry.style()));
			item.addProperty("body_part", entry.isBodyPart());
			items.add(item);
		}
		return items;
	}

	/**
	 * Makes sure Orbit Cloud has every file of the outfit (friends download them from there), one after another;
	 * then runs {@code done}. A file that cannot be uploaded is skipped: friends just do not see it.
	 */
	static void uploadOutfit(Runnable done) {
		upload(new ArrayList<>(outfitEntries()), done);
	}

	private static void upload(List<LocalCosmetic> left, Runnable done) {
		if (left.isEmpty()) {
			done.run();
			return;
		}
		LocalCosmetic entry = left.removeFirst();
		if (entry.bytes() == null) {
			upload(left, done);
			return;
		}
		OrbitClient.get().backend().socialEnsureUploaded(entry.asset(), entry.bytes(), entry.displayName(), false, () -> upload(left, done), error -> {
			OrbitClient.LOGGER.debug("Profile: {} not uploaded: {}", entry.displayName(), error);
			upload(left, done);
		});
	}

	/**
	 * A friend's outfit from their page: the files loaded so far (the others are downloaded, and show once they
	 * arrive). {@code inUse} gets every file, to keep them loaded.
	 */
	public static List<EquippedCosmetic> friendOutfit(@Nullable JsonArray items, Consumer<AssetId> inUse) {
		List<EquippedCosmetic> worn = new ArrayList<>();
		if (items == null) {
			return worn;
		}
		for (JsonElement element : items) {
			if (!(element instanceof JsonObject item) || !item.has("sha256")) {
				continue;
			}
			AssetId asset = AssetId.tryParse(item.get("sha256").getAsString());
			if (asset == null) {
				continue;
			}
			inUse.accept(asset);
			BakedCosmetic baked = ensure(asset);
			if (baked == null) {
				continue;
			}
			int style = item.has("style") ? item.get("style").getAsInt() : 0;
			boolean bodyPart = item.has("body_part") && item.get("body_part").getAsBoolean();
			var textures = dev.orbit.cosmetic.common.anim.TextureAnimation.defaults(baked.model().textures());
			worn.add(bodyPart ? new EquippedCosmetic(asset, "", List.of(), List.of(), style, List.of(), baked.bodyParts(), textures)
				: new EquippedCosmetic(asset, "", defaults(baked), List.of(), style, List.of(), 0, textures));
		}
		return worn;
	}

	private static @Nullable BakedCosmetic ensure(AssetId asset) {
		OrbitClient orbit = OrbitClient.get();
		BakedCosmetic baked = orbit.registry().get(asset);
		String sha = asset.hex();
		if (baked == null && !orbit.registry().isKnown(asset) && System.currentTimeMillis() >= RETRY_AT.getOrDefault(sha, 0L) && REQUESTED.add(sha)) {
			orbit.backend().socialDownloadModel(sha, bytes -> {
				REQUESTED.remove(sha);
				orbit.remotes().acceptAsset(asset, bytes);
			}, error -> {
				REQUESTED.remove(sha);
				RETRY_AT.put(sha, System.currentTimeMillis() + 30_000);
			});
		}
		return baked;
	}

	private static List<AnimationSettings> defaults(BakedCosmetic baked) {
		List<AnimationSettings> settings = new ArrayList<>();
		for (AnimationClip clip : baked.model().animations()) {
			settings.add(AnimationSettings.defaultsFor(clip));
		}
		return settings;
	}
}
