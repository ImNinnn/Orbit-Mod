package dev.orbit.cosmetic.client.skin;

import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

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
	private SkinTextureDownloader downloader;
	private final Map<String, CompletableFuture<ClientAsset.Texture>> textures = new HashMap<>();

	public SkinTextures(Path cache) {
		this.cache = cache;
	}

	private SkinTextureDownloader downloader() {
		if (downloader == null) {
			Minecraft minecraft = Minecraft.getInstance();
			downloader = new SkinTextureDownloader(minecraft.getProxy(), minecraft.getTextureManager(), minecraft);
		}
		return downloader;
	}

	/** A skin file of the player's own. */
	public ClientAsset.Texture local(SkinLibrary.LocalSkin skin) {
		return load("local/" + skin.sha256(), skin.path(), "file:" + skin.key(), true);
	}

	/** A skin Mojang hosts. */
	public ClientAsset.Texture mojangSkin(String hash) {
		return SkinSignal.isHash(hash) ? load("skin/" + hash, cache.resolve(hash + ".png"), SkinSignal.downloadUrl(hash), true) : null;
	}

	/** A cape Mojang hosts. */
	public ClientAsset.Texture mojangCape(String hash) {
		return SkinSignal.isHash(hash) ? load("cape/" + hash, cache.resolve("cape_" + hash + ".png"), SkinSignal.downloadUrl(hash), false) : null;
	}

	/** The texture once it is loaded, or null meanwhile (or if it failed). */
	private ClientAsset.Texture load(String name, Path localCopy, String url, boolean skin) {
		CompletableFuture<ClientAsset.Texture> future = textures.computeIfAbsent(name, key -> {
			Identifier id = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "skins/" + key);
			return downloader().downloadAndRegisterSkin(id, localCopy, url, skin).exceptionally(error -> {
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

	/** A skin with these textures; null while the body is not loaded. The cape may be null. */
	public static PlayerSkin skin(ClientAsset.Texture body, ClientAsset.Texture cape, boolean slim) {
		return body == null ? null : PlayerSkin.insecure(body, cape, cape, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
	}
}
