package dev.orbit.cosmetic.client.gfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The textures Orbit makes itself: pictures it uploads under its own names (cosmetic textures, screenshots, group
 * pictures, server icons, masked skins), and skins and capes it downloads. Each Minecraft version provides this one
 * class, so the rest of Orbit never meets that version's texture classes.
 * <p>
 * Render thread, except where a method says otherwise.
 */
public final class OrbitTextures {
	private static @Nullable SkinTextureDownloader downloader;

	private OrbitTextures() {
	}

	/** Uploads a picture as the texture {@code id} (replacing one of that name). The texture now owns the picture. */
	public static void register(Identifier id, String label, OrbitImage image) {
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> label, image.raw()));
	}

	/**
	 * Copies new pixels into a texture made by {@link #register} and uploads them again (the picture must have the
	 * same size; it stays the caller's). Does nothing when there is no such texture.
	 */
	public static void update(Identifier id, OrbitImage pixels) {
		if (Minecraft.getInstance().getTextureManager().getTexture(id) instanceof DynamicTexture texture && texture.getPixels() != null) {
			texture.getPixels().copyFrom(pixels.raw());
			texture.upload();
		}
	}

	/** Frees a texture (and its picture). */
	public static void release(Identifier id) {
		Minecraft.getInstance().getTextureManager().release(id);
	}

	/**
	 * A copy of a texture's pixels, to change and upload under another name: textures made in the game (downloaded
	 * skins) keep theirs; the others (the default skins) are read from the resources. Null when it has none.
	 */
	public static @Nullable OrbitImage pixels(Identifier texture) throws IOException {
		Minecraft minecraft = Minecraft.getInstance();
		AbstractTexture loaded = minecraft.getTextureManager().getTexture(texture);
		if (loaded instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
			return OrbitImage.of(dynamic.getPixels()).copy();
		}
		Optional<Resource> resource = minecraft.getResourceManager().getResource(texture);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return OrbitImage.read(in);
		}
	}

	/**
	 * Downloads a skin or cape (or reads {@code localCopy} if it is there already, saving it there otherwise) and
	 * registers it as {@code id}; the future gives {@code id} once it is ready. {@code url} may be a {@code file:}
	 * URL. A skin ({@code skin} true) in the old 64 by 32 layout is converted, as Minecraft does for players.
	 */
	public static CompletableFuture<Identifier> downloadSkin(Identifier id, Path localCopy, String url, boolean skin) {
		if (downloader == null) {
			Minecraft minecraft = Minecraft.getInstance();
			downloader = new SkinTextureDownloader(minecraft.getProxy(), minecraft.getTextureManager(), minecraft);
		}
		return downloader.downloadAndRegisterSkin(id, localCopy, url, skin).thenApply(ClientAsset.Texture::texturePath);
	}
}
