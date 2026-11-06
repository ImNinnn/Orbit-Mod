package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.model.TextureData;
import dev.orbit.cosmetic.common.model.TextureStyles;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shares baked cosmetics between everyone wearing the same asset. Parsing and image decoding run on a
 * background thread; texture upload happens on the render thread. Unused cosmetics are released after a delay;
 * asking for one of the player's own files again bakes it again (see {@link #setLocalModels}).
 */
public final class CosmeticRegistry {
	private static final long RELEASE_DELAY_MS = 30_000;

	public enum State {
		LOADING,
		READY,
		FAILED
	}

	private static final class Holder {
		State state = State.LOADING;
		BakedCosmetic baked;
		CosmeticModel model;
		String failure;
		long lastUsed = System.currentTimeMillis();
	}

	private final Map<AssetId, Holder> holders = new HashMap<>();
	private Function<AssetId, CosmeticModel> localModels = id -> null;

	/**
	 * Where the player's own models come from. A model that was released (unused for a while, or another file with
	 * the same content was removed) is baked again from there as soon as it is asked for.
	 */
	public void setLocalModels(Function<AssetId, CosmeticModel> localModels) {
		this.localModels = localModels;
	}

	/** Called on the main thread. */
	public BakedCosmetic get(AssetId id) {
		Holder holder = holders.get(id);
		if (holder == null) {
			CosmeticModel model = localModels.apply(id);
			if (model != null) {
				prepare(id, model);
			}
			return null;
		}
		if (holder.state != State.READY) {
			return null;
		}
		holder.lastUsed = System.currentTimeMillis();
		return holder.baked;
	}

	public State state(AssetId id) {
		Holder holder = holders.get(id);
		return holder == null ? null : holder.state;
	}

	public String failure(AssetId id) {
		Holder holder = holders.get(id);
		return holder == null ? null : holder.failure;
	}

	public CosmeticModel model(AssetId id) {
		Holder holder = holders.get(id);
		return holder == null ? null : holder.model;
	}

	public boolean isKnown(AssetId id) {
		return holders.containsKey(id);
	}

	/** Bakes an already parsed model (local library files). */
	public void prepare(AssetId id, CosmeticModel model) {
		if (holders.containsKey(id)) {
			return;
		}
		Holder holder = new Holder();
		holder.model = model;
		holders.put(id, holder);
		Util.backgroundExecutor().execute(() -> decode(id, holder, model));
	}

	/** Parses and bakes verified asset bytes (downloads and the download cache). */
	public void prepare(AssetId id, byte[] bytes, ModelLimits limits) {
		if (holders.containsKey(id)) {
			return;
		}
		Holder holder = new Holder();
		holders.put(id, holder);
		Util.backgroundExecutor().execute(() -> {
			BBModelParser.Result result = BBModelParser.parse(bytes, null, limits);
			if (!result.ok()) {
				String reason = result.errors().isEmpty() ? "invalid model" : result.errors().getFirst().message();
				Minecraft.getInstance().execute(() -> fail(id, holder, reason));
				return;
			}
			holder.model = result.model();
			decode(id, holder, result.model());
		});
	}

	private void decode(AssetId id, Holder holder, CosmeticModel model) {
		NativeImage[] images = new NativeImage[model.textures().size()];
		boolean[] translucent = new boolean[images.length];
		TextureStyles styles = TextureStyles.of(model.textures());
		int[] styleColors = new int[styles.count()];
		try {
			for (int i = 0; i < images.length; i++) {
				TextureData texture = model.textures().get(i);
				images[i] = NativeImage.read(texture.png());
				translucent[i] = hasPartialAlpha(images[i]);
			}
			for (int style = 0; style < styleColors.length; style++) {
				int sample = styles.sample()[style];
				styleColors[style] = sample >= 0 ? averageColor(images[sample]) : 0xFF808080;
			}
		} catch (IOException | RuntimeException e) {
			closeAll(images);
			OrbitClient.LOGGER.warn("Could not decode textures of cosmetic {}", id.shortHex(), e);
			Minecraft.getInstance().execute(() -> fail(id, holder, "A texture could not be decoded"));
			return;
		}
		Minecraft.getInstance().execute(() -> {
			if (holders.get(id) != holder) {
				closeAll(images); // released while decoding
				return;
			}
			try {
				holder.baked = BakedCosmetic.create(id, model, images, translucent, styles, styleColors);
				holder.state = State.READY;
			} catch (RuntimeException e) {
				closeAll(images);
				OrbitClient.LOGGER.warn("Could not upload cosmetic {}", id.shortHex(), e);
				fail(id, holder, "Could not upload textures");
			}
		});
	}

	private static boolean hasPartialAlpha(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int alpha = image.getPixel(x, y) >>> 24;
				if (alpha > 0 && alpha < 255) {
					return true;
				}
			}
		}
		return false;
	}

	/** The average colour of a texture's visible pixels, as opaque ARGB. */
	private static int averageColor(NativeImage image) {
		long red = 0;
		long green = 0;
		long blue = 0;
		long weight = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getPixel(x, y);
				int alpha = argb >>> 24;
				red += (long) (argb >> 16 & 0xFF) * alpha;
				green += (long) (argb >> 8 & 0xFF) * alpha;
				blue += (long) (argb & 0xFF) * alpha;
				weight += alpha;
			}
		}
		if (weight == 0) {
			return 0xFF808080;
		}
		return 0xFF000000 | (int) (red / weight) << 16 | (int) (green / weight) << 8 | (int) (blue / weight);
	}

	private static void closeAll(NativeImage[] images) {
		for (NativeImage image : images) {
			if (image != null) {
				image.close();
			}
		}
	}

	private void fail(AssetId id, Holder holder, String reason) {
		if (holders.get(id) == holder) {
			holder.state = State.FAILED;
			holder.failure = reason;
		}
	}

	/** Releases cosmetics nobody has used recently. Main thread. */
	public void collect(Set<AssetId> inUse) {
		long now = System.currentTimeMillis();
		holders.entrySet().removeIf(entry -> {
			Holder holder = entry.getValue();
			if (inUse.contains(entry.getKey())) {
				holder.lastUsed = now;
				return false;
			}
			if (holder.state == State.LOADING || now - holder.lastUsed < RELEASE_DELAY_MS) {
				return false;
			}
			if (holder.baked != null) {
				holder.baked.release();
			}
			return true;
		});
	}

	/** Forgets a cosmetic immediately (e.g. the local file changed). */
	public void invalidate(AssetId id) {
		Holder holder = holders.remove(id);
		if (holder != null && holder.baked != null) {
			holder.baked.release();
		}
	}

	public void releaseAll() {
		for (Holder holder : holders.values()) {
			if (holder.baked != null) {
				holder.baked.release();
			}
		}
		holders.clear();
	}

	public int loadedCount() {
		return (int) holders.values().stream().filter(h -> h.state == State.READY).count();
	}
}
