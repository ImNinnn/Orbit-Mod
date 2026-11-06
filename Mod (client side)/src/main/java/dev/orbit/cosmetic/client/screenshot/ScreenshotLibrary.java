package dev.orbit.cosmetic.client.screenshot;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The player's screenshots: the game's {@code screenshots} folder, and {@code screenshots/favorites} for the
 * favourites. Also small preview textures of them (thumbnails), made in the background and kept until released.
 * Game thread only, except where noted.
 */
public final class ScreenshotLibrary {
	/** One screenshot file. */
	public record Shot(Path path, long modified, boolean favorite) {
		public String name() {
			return path.getFileName().toString();
		}
	}

	/** A preview texture: its id and its size in pixels. */
	public record Thumbnail(Identifier id, int width, int height) {
	}

	private static final int MAX_FILES = 2000;
	private final Map<Path, Thumbnail> thumbnails = new HashMap<>();
	private final Map<Path, Integer> thumbnailSizes = new HashMap<>();
	private final java.util.Set<Path> loading = new java.util.HashSet<>();
	private int nextTexture;

	public Path folder() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
	}

	public Path favorites() {
		return folder().resolve("favorites");
	}

	/** Every screenshot, newest first. */
	public List<Shot> list() {
		List<Shot> shots = new ArrayList<>();
		scan(folder(), false, shots);
		scan(favorites(), true, shots);
		shots.sort(Comparator.comparingLong(Shot::modified).reversed());
		return shots.size() > MAX_FILES ? shots.subList(0, MAX_FILES) : shots;
	}

	private static void scan(Path folder, boolean favorite, List<Shot> into) {
		if (!Files.isDirectory(folder)) {
			return;
		}
		try (Stream<Path> files = Files.list(folder)) {
			for (Path path : files.filter(ScreenshotLibrary::isImage).toList()) {
				into.add(new Shot(path, Files.getLastModifiedTime(path).toMillis(), favorite));
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not list {}", folder, e);
		}
	}

	static boolean isImage(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && name.endsWith(".png") && !name.startsWith(".");
	}

	/** The screenshot at a path (it may have just been saved). */
	public Shot shot(Path path) {
		long modified;
		try {
			modified = Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			modified = System.currentTimeMillis();
		}
		return new Shot(path, modified, path.getParent() != null && path.getParent().equals(favorites()));
	}

	/** Moves a screenshot into (or out of) the favourites folder. Returns where it is now. */
	public Shot toggleFavorite(Shot shot) {
		Path target = shot.favorite() ? folder() : favorites();
		try {
			Files.createDirectories(target);
			Path moved = Files.move(shot.path(), unique(target, shot.name()));
			forget(shot.path());
			return new Shot(moved, shot.modified(), !shot.favorite());
		} catch (IOException e) {
			Notifications.error(Component.translatable("orbit_cosmetic.screenshots.failed"), Component.literal(e.getMessage()));
			return shot;
		}
	}

	/** Renames a screenshot (the extension is kept). Returns where it is now. */
	public Shot rename(Shot shot, String name) {
		String clean = cleanName(name);
		if (clean.isEmpty() || (clean + ".png").equals(shot.name())) {
			return shot;
		}
		try {
			Path moved = Files.move(shot.path(), unique(shot.path().getParent(), clean + ".png"));
			forget(shot.path());
			return new Shot(moved, shot.modified(), shot.favorite());
		} catch (IOException e) {
			Notifications.error(Component.translatable("orbit_cosmetic.screenshots.failed"), Component.literal(e.getMessage()));
			return shot;
		}
	}

	public boolean delete(Shot shot) {
		try {
			Files.deleteIfExists(shot.path());
			forget(shot.path());
			return true;
		} catch (IOException e) {
			Notifications.error(Component.translatable("orbit_cosmetic.screenshots.failed"), Component.literal(e.getMessage()));
			return false;
		}
	}

	/** A file name without what file systems refuse, and without the extension. */
	public static String cleanName(String name) {
		String base = name.trim();
		if (base.toLowerCase(Locale.ROOT).endsWith(".png")) {
			base = base.substring(0, base.length() - 4);
		}
		return base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^\\.+", "").trim();
	}

	/** {@code name} in {@code folder}, or {@code name (2)}, {@code name (3)}... when taken. */
	public static Path unique(Path folder, String name) {
		Path candidate = folder.resolve(name);
		String base = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
		for (int i = 2; Files.exists(candidate); i++) {
			candidate = folder.resolve(base + " (" + i + ").png");
		}
		return candidate;
	}

	// ---------------------------------------------------------------- previews

	/**
	 * A preview of a screenshot no bigger than {@code maxSize} pixels, or null while it is being made (in the
	 * background; asking again later returns it).
	 */
	public @Nullable Thumbnail thumbnail(Path path, int maxSize) {
		Thumbnail known = thumbnails.get(path);
		if (known != null && thumbnailSizes.getOrDefault(path, 0) >= maxSize) {
			return known;
		}
		if (loading.add(path)) {
			Util.ioPool().execute(() -> {
				NativeImage image = readScaled(path, maxSize);
				Minecraft.getInstance().execute(() -> {
					loading.remove(path);
					if (image == null) {
						return;
					}
					forget(path);
					Identifier id = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "screenshot/" + nextTexture++);
					int width = image.getWidth();
					int height = image.getHeight();
					Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Orbit screenshot " + path.getFileName(), image));
					thumbnails.put(path, new Thumbnail(id, width, height));
					thumbnailSizes.put(path, maxSize);
				});
			});
		}
		return known;
	}

	/** Reads an image and shrinks it to fit {@code maxSize} (averaging pixels). Any thread. */
	public static @Nullable NativeImage readScaled(Path path, int maxSize) {
		try (InputStream in = Files.newInputStream(path); NativeImage full = NativeImage.read(in)) {
			int step = Math.max(1, (int) Math.ceil(Math.max(full.getWidth(), full.getHeight()) / (double) maxSize));
			int width = Math.max(1, full.getWidth() / step);
			int height = Math.max(1, full.getHeight() / step);
			NativeImage small = new NativeImage(width, height, false);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					long a = 0;
					long r = 0;
					long g = 0;
					long b = 0;
					int count = 0;
					for (int dy = 0; dy < step; dy++) {
						for (int dx = 0; dx < step; dx++) {
							int pixel = full.getPixel(x * step + dx, y * step + dy);
							a += pixel >>> 24;
							r += pixel >> 16 & 0xFF;
							g += pixel >> 8 & 0xFF;
							b += pixel & 0xFF;
							count++;
						}
					}
					small.setPixel(x, y, (int) (a / count) << 24 | (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count));
				}
			}
			return small;
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.warn("Could not read {}", path, e);
			return null;
		}
	}

	/** Drops the preview of a file (it changed or moved). */
	public void forget(Path path) {
		Thumbnail thumbnail = thumbnails.remove(path);
		thumbnailSizes.remove(path);
		if (thumbnail != null) {
			Minecraft.getInstance().getTextureManager().release(thumbnail.id());
		}
	}

	/** Frees every preview (when the screenshot screens close). */
	public void releaseAll() {
		for (Path path : List.copyOf(thumbnails.keySet())) {
			forget(path);
		}
	}

	/** Copies a file next to itself with another name, for edits saved as a copy. */
	public static Path copyNextTo(Path source, String name) throws IOException {
		Path target = unique(source.getParent(), cleanName(name) + ".png");
		Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
		return target;
	}
}
