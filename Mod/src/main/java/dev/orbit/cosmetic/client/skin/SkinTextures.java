package dev.orbit.cosmetic.client.skin;

import dev.orbit.cosmetic.common.protocol.SkinSignal;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gfx.OrbitTextures;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Skin and cape textures for Orbit's skins: the player's own skin files, and Mojang textures (by hash) that other
 * Orbit players wear. Loaded with Minecraft's own skin downloader (which also converts old 64x32 skins) and kept
 * for the session. Main thread only.
 */
public final class SkinTextures {
	private final Path cache;
	private final Map<String, CompletableFuture<Identifier>> textures = new HashMap<>();

	public SkinTextures(Path cache) {
		this.cache = cache;
	}

	/** A skin file of the player's own. */
	public Identifier local(SkinLibrary.LocalSkin skin) {
		return load("local/" + skin.sha256(), skin.path(), "file:" + skin.key(), true);
	}

	/** A skin Mojang hosts. */
	public Identifier mojangSkin(String hash) {
		return SkinSignal.isHash(hash) ? load("skin/" + hash, cache.resolve(hash + ".png"), SkinSignal.downloadUrl(hash), true) : null;
	}

	/** A cape Mojang hosts. */
	public Identifier mojangCape(String hash) {
		return SkinSignal.isHash(hash) ? load("cape/" + hash, cache.resolve("cape_" + hash + ".png"), SkinSignal.downloadUrl(hash), false) : null;
	}

	/** The texture once it is loaded, or null meanwhile (or if it failed). */
	private Identifier load(String name, Path localCopy, String url, boolean skin) {
		CompletableFuture<Identifier> future = textures.computeIfAbsent(name, key -> {
			Identifier id = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "skins/" + key);
			return OrbitTextures.downloadSkin(id, localCopy, url, skin).exceptionally(error -> {
				OrbitClient.LOGGER.debug("Could not load the texture {}: {}", url, error.getMessage());
				return null;
			});
		});
		return future.isDone() ? future.getNow(null) : null;
	}

	/** Forgets a local skin's texture (its file changed). */
	public void forget(SkinLibrary.LocalSkin skin) {
		textures.remove("local/" + skin.sha256());
	}
}
