package dev.orbit.cosmetic.client.skin;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.util.Hashes;
import dev.orbit.cosmetic.common.util.PngInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The player's skins: PNG files in {@code Orbit/Skins}. {@code name_slim.png} has slim (Alex) arms, anything else
 * classic ones ({@code name_classic.png}). Only 64x64 and old 64x32 skins are listed.
 */
public final class SkinLibrary {
	private static final int MAX_FILES = 256;
	private static final int MAX_BYTES = 256 * 1024;

	/**
	 * One skin file.
	 *
	 * @param key its file name, which outfits and the state file use
	 * @param sha256 the file's hash, which Orbit remembers Mojang's texture of
	 */
	public record LocalSkin(Path path, String key, String name, boolean slim, byte[] bytes, String sha256) {
	}

	private final Path folder;
	private final List<Runnable> listeners = new ArrayList<>();
	private List<LocalSkin> skins = List.of();
	private boolean scanning;
	private boolean rescan;

	public SkinLibrary(Path folder) {
		this.folder = folder;
	}

	public Path folder() {
		return folder;
	}

	public List<LocalSkin> skins() {
		return skins;
	}

	public LocalSkin find(String key) {
		return key == null ? null : skins.stream().filter(skin -> skin.key().equals(key)).findFirst().orElse(null);
	}

	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	public void reload() {
		if (scanning) {
			rescan = true;
			return;
		}
		scanning = true;
		Util.ioPool().execute(() -> {
			List<LocalSkin> found = scan();
			Minecraft.getInstance().execute(() -> {
				scanning = false;
				skins = found;
				List.copyOf(listeners).forEach(Runnable::run);
				if (rescan) {
					rescan = false;
					reload();
				}
			});
		});
	}

	private List<LocalSkin> scan() {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		List<LocalSkin> result = new ArrayList<>();
		try (Stream<Path> files = Files.list(folder)) {
			for (Path path : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().limit(MAX_FILES).toList()) {
				LocalSkin skin = read(path);
				if (skin != null) {
					result.add(skin);
				}
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not list {}", folder, e);
		}
		result.sort(Comparator.comparing(skin -> skin.name().toLowerCase(Locale.ROOT)));
		return List.copyOf(result);
	}

	private static LocalSkin read(Path path) {
		try {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
				return null;
			}
			byte[] bytes = Files.readAllBytes(path);
			PngInfo info = PngInfo.read(bytes);
			if (info == null || info.width() != 64 || info.height() != 64 && info.height() != 32) {
				return null;
			}
			String file = path.getFileName().toString();
			String base = file.substring(0, file.length() - ".png".length());
			String lower = base.toLowerCase(Locale.ROOT);
			boolean slim = lower.endsWith("_slim");
			if (slim || lower.endsWith("_classic")) {
				base = base.substring(0, base.lastIndexOf('_'));
			}
			return new LocalSkin(path, file, base.replace('_', ' ').trim(), slim, bytes, Hashes.toHex(Hashes.sha256(bytes)));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Saves a skin into the folder as {@code <name>_<classic|slim>.png} and returns its key. */
	public String save(String name, boolean slim, byte[] png) throws IOException {
		Files.createDirectories(folder);
		String base = name.trim().replace(' ', '_').replaceAll("[^A-Za-z0-9._-]", "");
		base = base.isEmpty() || base.startsWith(".") ? "skin" + base : base;
		String suffix = slim ? "_slim" : "_classic";
		Path target = folder.resolve(base + suffix + ".png");
		for (int i = 2; Files.exists(target); i++) {
			target = folder.resolve(base + "_" + i + suffix + ".png");
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temp, png);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		reload();
		return target.getFileName().toString();
	}

	public boolean delete(LocalSkin skin) {
		try {
			Files.deleteIfExists(skin.path());
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not delete {}", skin.path(), e);
			return false;
		}
		reload();
		return true;
	}
}
