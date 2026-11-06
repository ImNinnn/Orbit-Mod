package dev.orbit.cosmetic.client.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.OrbitPaths;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.protocol.EquipCodec;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.BodyParts;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.model.TemplateGenerator;
import dev.orbit.cosmetic.common.model.TextureEmbedder;
import dev.orbit.cosmetic.common.model.TextureStyles;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.util.PngInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The player's own cosmetics: one folder per cosmetic in {@code Orbit/Cosmetics}, holding the {@code .bbmodel} and its
 * settings file. Files are validated in the background and nothing in them is executed. Each cosmetic's animation
 * settings, physics and details live in a JSON file of the same name next to the model (see
 * {@link CosmeticSettingsFile}), so a configured cosmetic can be shared as two files; a cosmetic without one gets
 * one when it is first read. A {@code .bbmodel} dropped
 * straight into {@code Orbit/Cosmetics} is moved into a folder of its own. Which cosmetics are equipped is kept in
 * the {@link OrbitState}.
 */
public final class CosmeticLibrary {
	private static final int MAX_SCAN_DEPTH = 3;
	private static final int MAX_FILES = 512;

	private final OrbitPaths paths;
	private final OrbitState state;
	private final Map<String, LocalCosmetic> entries = new LinkedHashMap<>();
	private final List<Runnable> listeners = new ArrayList<>();
	private boolean scanning;
	private boolean rescan;
	/** Scans finished so far; failures found by the first one (game start) are not announced. */
	private int scans;

	public CosmeticLibrary(OrbitPaths paths, OrbitState state) {
		this.paths = paths;
		this.state = state;
	}

	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	private void changed() {
		for (Runnable listener : List.copyOf(listeners)) {
			listener.run();
		}
	}

	public List<LocalCosmetic> entries() {
		return List.copyOf(entries.values());
	}

	public LocalCosmetic find(AssetId asset) {
		for (LocalCosmetic entry : entries.values()) {
			if (asset.equals(entry.asset) && entry.bytes != null) {
				return entry;
			}
		}
		return null;
	}

	/** The claimed featured cosmetic with this file, or null. The player's own copies do not count. */
	public LocalCosmetic findFeatured(AssetId asset) {
		for (LocalCosmetic entry : entries.values()) {
			if (entry.isFeatured() && asset.equals(entry.asset)) {
				return entry;
			}
		}
		return null;
	}

	/** Usable loaded cosmetics in a stable order. */
	public List<LocalCosmetic> loaded() {
		return entries.values().stream().filter(e -> e.loaded && e.isUsable()).toList();
	}

	public boolean isScanning() {
		return scanning;
	}

	// ---------------------------------------------------------------- scanning

	/** Rescans the orbit folder; changed files are re-validated in the background. */
	public void reload() {
		if (scanning) {
			// Files changed during this scan: scan once more when it is done.
			rescan = true;
			return;
		}
		scanning = true;
		ModelLimits limits = OrbitClient.get().config().modelLimits();
		Util.ioPool().execute(() -> {
			List<Path> files = findFiles();
			Minecraft.getInstance().execute(() -> applyScan(files, limits));
		});
	}

	/** Key prefix of claimed featured cosmetics, which live in {@link OrbitPaths#featuredCosmetics()}. */
	public static final String FEATURED = "featured:";
	/** Key prefix of body parts ({@link OrbitPaths#bodyParts()}); claimed featured ones start with {@link #FEATURED_BODY}. */
	public static final String BODY = "body:";
	public static final String FEATURED_BODY = FEATURED + BODY;

	private List<Path> findFiles() {
		moveLooseFiles(paths.cosmetics());
		moveLooseFiles(paths.bodyParts());
		List<Path> files = new ArrayList<>(scan(paths.cosmetics()));
		files.addAll(scan(paths.featuredCosmetics()));
		files.addAll(scan(paths.bodyParts()));
		files.addAll(scan(paths.featuredBodyParts()));
		return files;
	}

	static List<Path> scan(Path root) {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH, new FileVisitOption[0])) {
			return stream
				.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.filter(path -> isModel(path) && !path.getParent().equals(root))
				.sorted()
				.limit(MAX_FILES)
				.toList();
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not scan {}", root, e);
			return List.of();
		}
	}

	/**
	 * The key of a file: its path in Orbit/Cosmetics, or {@link #FEATURED} and its path in the featured folder; body
	 * parts the same with {@link #BODY} or {@link #FEATURED_BODY}.
	 */
	private String keyOf(Path file) {
		if (file.startsWith(paths.featuredBodyParts())) {
			return FEATURED_BODY + paths.featuredBodyParts().relativize(file).toString().replace('\\', '/');
		}
		if (file.startsWith(paths.bodyParts())) {
			return BODY + paths.bodyParts().relativize(file).toString().replace('\\', '/');
		}
		if (file.startsWith(paths.featuredCosmetics())) {
			return FEATURED + paths.featuredCosmetics().relativize(file).toString().replace('\\', '/');
		}
		return paths.cosmetics().relativize(file).toString().replace('\\', '/');
	}

	/** Each cosmetic lives in its own folder: a model saved straight into Orbit/Cosmetics is moved into one. */
	static void moveLooseFiles(Path root) {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> stream = Files.list(root)) {
			for (Path file : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isModel(path)).toList()) {
				Path folder = uniqueFolder(root, stripExtension(file.getFileName().toString()));
				Files.createDirectories(folder);
				Path settings = CosmeticSettingsFile.pathFor(file);
				if (Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)) {
					Files.move(settings, folder.resolve(settings.getFileName()));
				}
				Files.move(file, folder.resolve(file.getFileName()));
				OrbitClient.LOGGER.info("Moved {} into its own folder {}", file.getFileName(), folder.getFileName());
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not move loose cosmetics in {} into folders", root, e);
		}
	}

	static boolean isModel(Path path) {
		return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel");
	}

	private void applyScan(List<Path> files, ModelLimits limits) {
		scanning = false;
		if (rescan) {
			rescan = false;
			reload();
		}
		boolean announce = scans++ > 0;
		Set<String> seen = new LinkedHashSet<>();
		Map<Path, Integer> perFolder = new HashMap<>();
		for (Path file : files) {
			perFolder.merge(file.getParent(), 1, Integer::sum);
		}
		for (Path file : files) {
			String key = keyOf(file);
			seen.add(key);
			String baseName = perFolder.get(file.getParent()) == 1 ? file.getParent().getFileName().toString() : stripExtension(file.getFileName().toString());
			LocalCosmetic known = entries.get(key);
			if (known != null) {
				known.baseName = baseName;
			}
			long modified;
			long size;
			try {
				modified = Files.getLastModifiedTime(file).toMillis();
				size = Files.size(file);
			} catch (IOException e) {
				continue;
			}
			LocalCosmetic entry = entries.get(key);
			if (entry != null && entry.modified == modified && entry.size == size && entry.status != LocalCosmetic.Status.CHECKING) {
				if (entry.status == LocalCosmetic.Status.READY && CosmeticSettingsFile.modified(file) != entry.settingsModified) {
					reloadSettings(entry);
				}
				continue;
			}
			if (entry == null) {
				entry = new LocalCosmetic(file, key);
				entry.baseName = baseName;
				entry.loaded = state.equipped().contains(key);
				entry.style = Math.clamp(state.styles().getOrDefault(key, 0), 0, TextureStyles.MAX - 1);
				entries.put(key, entry);
			}
			entry.modified = modified;
			entry.size = size;
			entry.status = LocalCosmetic.Status.CHECKING;
			validate(entry, limits, announce);
		}
		entries.keySet().removeIf(key -> {
			if (!seen.contains(key)) {
				LocalCosmetic removed = entries.get(key);
				if (removed.asset != null) {
					OrbitClient.get().registry().invalidate(removed.asset);
				}
				return true;
			}
			return false;
		});
		updateDuplicates();
		changed();
	}

	private void validate(LocalCosmetic entry, ModelLimits limits, boolean announce) {
		Path file = entry.path;
		Util.backgroundExecutor().execute(() -> {
			Loaded loaded = load(file, limits);
			CosmeticSettingsFile.Data settings = CosmeticSettingsFile.read(file);
			Minecraft.getInstance().execute(() -> finishValidation(entry, loaded.result(), loaded.bytes(), loaded.issues(), settings, announce));
		});
	}

	/** A model file read with its textures embedded (the bytes other players receive) and parsed. */
	record Loaded(byte[] bytes, BBModelParser.Result result, List<Issue> issues) {
	}

	/** Reads, embeds the textures of and parses a model file. Runs in the background. */
	static Loaded load(Path file, ModelLimits limits) {
		List<Issue> issues = new ArrayList<>();
		byte[] canonical = null;
		BBModelParser.Result result = null;
		try {
			long size = Files.size(file);
			if (size > limits.maxFileBytes()) {
				issues.add(Issue.error("too_large", "The file is " + size / 1024 + " KiB; the limit is " + limits.maxFileBytes() / 1024 + " KiB"));
			} else {
				byte[] raw = Files.readAllBytes(file);
				TextureEmbedder.Result embedded = TextureEmbedder.embed(raw, limits, (relative, absolute, name) -> readTexture(file.getParent(), relative, absolute, name, limits));
				canonical = embedded.bytes();
				if (embedded.missing() > 0) {
					issues.add(Issue.warning("missing_texture", embedded.missing() + " texture file(s) could not be found next to the model. Save the model in Blockbench with textures embedded."));
				}
				result = BBModelParser.parse(canonical, stripExtension(file.getFileName().toString()), limits);
				issues.addAll(result.issues());
			}
		} catch (BBModelParser.InvalidModelException e) {
			issues.add(Issue.error(e.code(), e.getMessage()));
		} catch (IOException e) {
			issues.add(Issue.error("io", "Could not read the file: " + e.getMessage()));
		}
		return new Loaded(canonical, result, issues);
	}

	private void finishValidation(LocalCosmetic entry, BBModelParser.Result result, byte[] bytes, List<Issue> issues, CosmeticSettingsFile.Data settings, boolean announce) {
		if (entries.get(entry.key) != entry) {
			return;
		}
		AssetId previous = entry.asset;
		entry.issues = List.copyOf(issues);
		if (result != null && result.ok()) {
			entry.status = LocalCosmetic.Status.READY;
			entry.model = result.model();
			if (entry.isBodyPart()) {
				entry.validParts = BodyParts.valid(entry.model);
				entry.issues = bodyPartIssues(entry.issues, entry.model);
			}
			entry.bytes = bytes;
			entry.asset = AssetId.of(bytes);
			applySettings(entry, settings);
			if (!settings.exists() && !entry.isFeatured()) {
				// Every cosmetic gets its settings file, ready to edit (name, author, colours) and to share.
				writeSettings(entry);
			}
			OrbitClient.get().registry().prepare(entry.asset, entry.model);
		} else {
			entry.status = LocalCosmetic.Status.INVALID;
			entry.model = null;
			entry.bytes = null;
			entry.asset = null;
			if (announce || entry.loaded) {
				String reason = readable(issues.stream().filter(Issue::isError).map(Issue::message).findFirst().orElse("Not a valid Blockbench model"));
				Notifications.error(Component.translatable("orbit_cosmetic.notify.load_failed", entry.displayName()), Component.literal(reason));
			}
		}
		if (previous != null && !previous.equals(entry.asset)) {
			OrbitClient.get().registry().invalidate(previous);
		}
		updateDuplicates();
		changed();
	}

	/** A body part's own problems: parts that are too big (the player's own part shows), or no usable part at all. */
	private static List<Issue> bodyPartIssues(List<Issue> issues, CosmeticModel model) {
		List<Issue> all = new ArrayList<>(issues);
		BodyParts.Check[] checks = BodyParts.check(model);
		for (Anchor part : BodyParts.PARTS) {
			if (checks[part.ordinal()] == BodyParts.Check.TOO_BIG) {
				all.add(Issue.warning("part_too_big", part.name() + " is more than 50% bigger than the player's own part, so the player's own part shows instead"));
			}
		}
		if (BodyParts.valid(model) == 0) {
			all.add(Issue.error("no_parts", "Nothing replaces a part of the player: put geometry (at most 50% bigger than the part) in HEAD, BODY, an arm or a leg group"));
		}
		if (model.hasAnimations()) {
			all.add(Issue.warning("animations_ignored", "Body parts have no animations: they move with the player"));
		}
		return List.copyOf(all);
	}

	private void applySettings(LocalCosmetic entry, CosmeticSettingsFile.Data data) {
		entry.settingsRoot = data.root();
		entry.settingsModified = data.modified();
		entry.settingsForeign = data.foreign();
		entry.settingsProblem = data.problem();
		entry.nameOverride = data.name() == null || data.name().isBlank() ? null : BBModelParser.sanitizeName(data.name());
		entry.author = data.author();
		entry.description = data.description();
		entry.gradient = TileColors.of(data.root().get("gradient"));
		entry.savedSettings = Map.copyOf(data.animations());
		entry.savedPhysics = new LinkedHashMap<>(data.physics());
		entry.savedParticles = new LinkedHashMap<>(data.particles());
		entry.disabledParts = 0;
		if (entry.isBodyPart() && data.root().get("parts") instanceof JsonObject parts) {
			for (Anchor part : BodyParts.PARTS) {
				JsonElement value = parts.get(part.name());
				if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean()) {
					entry.disabledParts |= BodyParts.bit(part);
				}
			}
		}
	}

	/** Re-reads a settings file edited outside the game. */
	private void reloadSettings(LocalCosmetic entry) {
		entry.settingsModified = CosmeticSettingsFile.modified(entry.path);
		Util.ioPool().execute(() -> {
			CosmeticSettingsFile.Data data = CosmeticSettingsFile.read(entry.path);
			Minecraft.getInstance().execute(() -> {
				if (entries.get(entry.key) == entry && entry.model != null) {
					applySettings(entry, data);
					changed();
				}
			});
		});
	}

	/** Writes the cosmetic's settings file. Returns false if it cannot be written (or belongs to another program). */
	private boolean writeSettings(LocalCosmetic entry) {
		if (entry.model == null || entry.settingsForeign || entry.isFeatured()) {
			return false;
		}
		if (entry.isBodyPart()) {
			// Which parts it replaces: every part in the file, unless turned off here.
			JsonObject parts = new JsonObject();
			for (Anchor part : BodyParts.PARTS) {
				if ((entry.validParts & BodyParts.bit(part)) != 0) {
					parts.addProperty(part.name(), (entry.disabledParts & BodyParts.bit(part)) == 0);
				}
			}
			entry.settingsRoot.add("parts", parts);
		}
		long modified = CosmeticSettingsFile.write(entry.path, entry.settingsRoot, entry.model, entry.animationSettings(), entry.savedPhysics, entry.savedParticles);
		if (modified < 0) {
			OrbitClient.LOGGER.warn("Could not save {}", CosmeticSettingsFile.pathFor(entry.path));
			return false;
		}
		entry.settingsModified = modified;
		return true;
	}

	/**
	 * Finds a texture referenced by a model. Only PNG files inside the model's own folder (or its subfolders)
	 * are accepted, so a model can never pull arbitrary files from the disk into an uploaded cosmetic.
	 */
	static byte[] readTexture(Path modelDirectory, String relative, String absolute, String name, ModelLimits limits) {
		List<String> candidates = new ArrayList<>();
		if (relative != null) {
			candidates.add(relative);
		}
		for (String other : new String[] {absolute, name}) {
			if (other != null && !other.isBlank()) {
				String fileName = Path.of(other.replace('\\', '/').replaceAll(".*/", "")).toString();
				candidates.add(fileName);
				if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
					candidates.add(fileName + ".png");
				}
			}
		}
		Path base = modelDirectory.toAbsolutePath().normalize();
		for (String candidate : candidates) {
			try {
				Path path = base.resolve(candidate.replace('\\', '/')).toAbsolutePath().normalize();
				if (!path.startsWith(base) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
					continue;
				}
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limits.maxTotalTextureBytes()) {
					continue;
				}
				byte[] png = Files.readAllBytes(path);
				if (PngInfo.read(png) != null) {
					return png;
				}
			} catch (IOException | RuntimeException ignored) {
				// try the next candidate
			}
		}
		return null;
	}

	/** The same file twice among the player's own cosmetics (or twice among claimed featured ones) is used once. */
	private void updateDuplicates() {
		Map<String, LocalCosmetic> first = new HashMap<>();
		for (LocalCosmetic entry : entries.values()) {
			entry.duplicateOf = null;
			if (entry.asset == null) {
				continue;
			}
			// Featured items are separate from the player's own: an editable copy is not a duplicate of the original.
			LocalCosmetic original = first.putIfAbsent((entry.isFeatured() ? "f:" : "u:") + entry.asset.hex(), entry);
			if (original != null) {
				entry.duplicateOf = original.key;
			}
		}
	}

	// ---------------------------------------------------------------- actions

	public void setLoaded(LocalCosmetic entry, boolean loaded) {
		if (entry.loaded == loaded) {
			return;
		}
		entry.loaded = loaded;
		state.equipped().remove(entry.key);
		if (loaded) {
			state.equipped().add(entry.key);
		}
		state.save();
		if (loaded) {
			Notifications.success(Component.translatable("orbit_cosmetic.notify.loaded", entry.displayName()), null);
		} else {
			Notifications.info(Component.translatable("orbit_cosmetic.notify.unloaded", entry.displayName()), null);
		}
		changed();
	}

	/** Chooses the alternate texture a cosmetic is worn with. */
	public void setStyle(LocalCosmetic entry, int style) {
		int chosen = Math.clamp(style, 0, TextureStyles.MAX - 1);
		if (entry.style == chosen) {
			return;
		}
		entry.style = chosen;
		if (chosen == 0) {
			state.styles().remove(entry.key);
		} else {
			state.styles().put(entry.key, chosen);
		}
		state.save();
		changed();
	}

	public void setAnimationSettings(LocalCosmetic entry, int index, AnimationSettings settings) {
		if (entry.model == null || index < 0 || index >= entry.model.animations().size()) {
			return;
		}
		AnimationClip clip = entry.model.animations().get(index);
		Map<String, AnimationSettings> updated = new LinkedHashMap<>(entry.savedSettings);
		updated.put(clip.name(), settings);
		entry.savedSettings = Map.copyOf(updated);
		writeSettings(entry);
		changed();
	}

	public void resetAnimationSettings(LocalCosmetic entry) {
		entry.savedSettings = Map.of();
		writeSettings(entry);
		changed();
	}

	/** Sets (or, with all axes off, keeps) the movement physics of one part. */
	public void setPhysics(LocalCosmetic entry, int bone, List<AxisDrive> axes) {
		String key = entry.boneKey(bone);
		if (key == null) {
			return;
		}
		Map<String, List<AxisDrive>> updated = new LinkedHashMap<>(entry.savedPhysics);
		if (!updated.containsKey(key) && updated.size() >= EquipCodec.MAX_PHYSICS_LINKS) {
			return;
		}
		updated.put(key, List.copyOf(axes));
		entry.savedPhysics = updated;
		writeSettings(entry);
		changed();
	}

	/** Makes a group a particle emitter, with the default settings (at most {@link ParticleSettings#MAX_EMITTERS}). */
	public void addParticles(LocalCosmetic entry, int bone) {
		if (entry.model != null && entry.particles().size() < ParticleSettings.MAX_EMITTERS && entry.particles().stream().noneMatch(p -> p.bone() == bone)) {
			setParticles(entry, CosmeticSettingsFile.newEmitter(entry.model, bone));
		}
	}

	/** A group stops being a particle emitter. */
	public void removeParticles(LocalCosmetic entry, int bone) {
		String key = entry.boneKey(bone);
		if (key == null || !entry.savedParticles.containsKey(key)) {
			return;
		}
		Map<String, CosmeticSettingsFile.SavedParticles> updated = new LinkedHashMap<>(entry.savedParticles);
		updated.remove(key);
		entry.savedParticles = updated;
		writeSettings(entry);
		changed();
	}

	/**
	 * Sets how a texture is animated: frames of this size stacked in the image, each shown {@code ticks} ticks (a
	 * frame as big as the image: not animated).
	 */
	public void setTextureAnimation(LocalCosmetic entry, int texture, int frameWidth, int frameHeight, int ticks) {
		if (entry.model == null || texture < 0 || texture >= entry.model.textures().size()) {
			return;
		}
		JsonObject textures = entry.settingsRoot.get("textures") instanceof JsonObject object ? object.deepCopy() : new JsonObject();
		JsonObject json = new JsonObject();
		json.addProperty("frame_width", frameWidth);
		json.addProperty("frame_height", frameHeight);
		json.addProperty("frame_ticks", ticks);
		textures.add(entry.model.textures().get(texture).name(), json);
		entry.settingsRoot = entry.settingsRoot.deepCopy();
		entry.settingsRoot.add("textures", textures);
		writeSettings(entry);
		changed();
	}

	/** Back to the texture animations the model file sets. */
	public void resetTextureAnimations(LocalCosmetic entry) {
		if (entry.settingsRoot.has("textures")) {
			entry.settingsRoot = entry.settingsRoot.deepCopy();
			entry.settingsRoot.remove("textures");
			writeSettings(entry);
			changed();
		}
	}

	/** Sets the settings of one particle emitter. */
	public void setParticles(LocalCosmetic entry, ParticleSettings settings) {
		String key = entry.boneKey(settings.bone());
		if (key == null) {
			return;
		}
		Map<String, CosmeticSettingsFile.SavedParticles> updated = new LinkedHashMap<>(entry.savedParticles);
		String texture = settings.texture() >= 0 && settings.texture() < entry.model.textures().size() ? entry.model.textures().get(settings.texture()).name() : null;
		updated.put(key, new CosmeticSettingsFile.SavedParticles(texture, settings));
		entry.savedParticles = updated;
		writeSettings(entry);
		changed();
	}

	public void removePhysics(LocalCosmetic entry, int bone) {
		String key = entry.boneKey(bone);
		if (key == null || !entry.savedPhysics.containsKey(key)) {
			return;
		}
		Map<String, List<AxisDrive>> updated = new LinkedHashMap<>(entry.savedPhysics);
		updated.remove(key);
		entry.savedPhysics = updated;
		writeSettings(entry);
		changed();
	}

	/** Deletes the model, its settings file and its folder if nothing else is in it. The caller confirms first. */
	public boolean remove(LocalCosmetic entry) {
		try {
			Files.deleteIfExists(entry.path);
			if (!entry.settingsForeign) {
				Files.deleteIfExists(CosmeticSettingsFile.pathFor(entry.path));
			}
			Path folder = entry.path.getParent();
			if (!folder.equals(paths.cosmetics()) && !folder.equals(paths.featuredCosmetics()) && !folder.equals(paths.bodyParts())
				&& !folder.equals(paths.featuredBodyParts())) {
				try (Stream<Path> rest = Files.list(folder)) {
					if (rest.findAny().isEmpty()) {
						Files.delete(folder);
					}
				}
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not delete {}", entry.path, e);
			return false;
		}
		entries.remove(entry.key);
		state.equipped().remove(entry.key);
		state.styles().remove(entry.key);
		state.save();
		if (entry.asset != null) {
			OrbitClient.get().registry().invalidate(entry.asset);
		}
		updateDuplicates();
		changed();
		return true;
	}

	/**
	 * Copies .bbmodel files into the orbit folder, embedding textures found next to them.
	 *
	 * @return one human-readable line per file
	 */
	public List<String> importFiles(List<Path> files) {
		List<String> report = new ArrayList<>();
		ModelLimits limits = OrbitClient.get().config().modelLimits();
		for (Path source : files) {
			String fileName = source.getFileName().toString();
			if (!fileName.toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
				report.add(fileName + ": not a .bbmodel file");
				continue;
			}
			try {
				if (!Files.isRegularFile(source) || Files.size(source) > limits.maxFileBytes()) {
					report.add(fileName + ": missing or larger than " + limits.maxFileBytes() / 1024 + " KiB");
					continue;
				}
				byte[] raw = Files.readAllBytes(source);
				TextureEmbedder.Result embedded = TextureEmbedder.embed(raw, limits, (relative, absolute, name) -> readTexture(source.getParent(), relative, absolute, name, limits));
				AssetId id = AssetId.of(embedded.bytes());
				LocalCosmetic existing = entries.values().stream().filter(e -> id.equals(e.asset)).findFirst().orElse(null);
				if (existing != null) {
					report.add(fileName + ": already in the library as " + existing.key);
					continue;
				}
				String base = stripExtension(sanitizeFileName(fileName));
				Path folder = uniqueFolder(paths.cosmetics(), base);
				Files.createDirectories(folder);
				Path target = folder.resolve(base + ".bbmodel");
				Path temp = target.resolveSibling(target.getFileName() + ".tmp");
				Files.write(temp, embedded.bytes());
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
				// Bring the cosmetic's settings along when they were shared with it.
				Path settings = CosmeticSettingsFile.pathFor(source);
				boolean withSettings = CosmeticSettingsFile.isSettingsFile(settings);
				if (withSettings) {
					Files.copy(settings, CosmeticSettingsFile.pathFor(target), StandardCopyOption.REPLACE_EXISTING);
				}
				report.add(fileName + ": imported" + (withSettings ? " with its settings" : "") + (embedded.missing() > 0 ? " (some textures were not found)" : ""));
			} catch (IOException | BBModelParser.InvalidModelException e) {
				report.add(fileName + ": " + e.getMessage());
			}
		}
		reload();
		return report;
	}

	/**
	 * Saves a cosmetic claimed from the featured catalog into {@code Orbit/Cosmetics/<name>/} and equips it once it is
	 * checked. The bytes must already match the catalog's SHA-256; they are written unchanged so the cosmetic keeps
	 * the same identity as on Orbit Cloud.
	 *
	 * @return the existing entry if this cosmetic is already in the library (it is equipped), otherwise null
	 */
	public LocalCosmetic installFeatured(String name, byte[] bytes, byte[] settings, JsonElement gradient, int style) throws IOException {
		return installFeatured(name, bytes, settings, gradient, style, false);
	}

	/** Like {@link #installFeatured(String, byte[], byte[], JsonElement, int)}; {@code bodyPart} for a body part. */
	public LocalCosmetic installFeatured(String name, byte[] bytes, byte[] settings, JsonElement gradient, int style, boolean bodyPart) throws IOException {
		return installFeatured(name, bytes, settings, gradient, style, bodyPart, true);
	}

	/** Like the others; {@code equip} false only adds it to the wardrobe (restored from Orbit Cloud). */
	public LocalCosmetic installFeatured(String name, byte[] bytes, byte[] settings, JsonElement gradient, int style, boolean bodyPart, boolean equip) throws IOException {
		AssetId id = AssetId.of(bytes);
		LocalCosmetic existing = findFeatured(id);
		if (existing != null) {
			if (equip) {
				setLoaded(existing, true);
			}
			return existing;
		}
		String base = folderName(name);
		Path folder = uniqueFolder(bodyPart ? paths.featuredBodyParts() : paths.featuredCosmetics(), base);
		Files.createDirectories(folder);
		Path target = folder.resolve(folder.getFileName() + ".bbmodel");
		byte[] settingsFile = settings != null && CosmeticSettingsFile.isSettingsJson(settings) ? settings : null;
		if (gradient != null) {
			// The catalog's tile colours go into the settings file, which then exists even for cosmetics without one.
			settingsFile = CosmeticSettingsFile.withGradient(settingsFile, target.getFileName().toString(), gradient);
		}
		if (settingsFile != null) {
			// Written first, so the model is read with its animation and physics settings.
			Path settingsPath = CosmeticSettingsFile.pathFor(target);
			Path settingsTemp = settingsPath.resolveSibling(settingsPath.getFileName() + ".tmp");
			Files.write(settingsTemp, settingsFile);
			Files.move(settingsTemp, settingsPath, StandardCopyOption.REPLACE_EXISTING);
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		if (equip) {
			state.equipped().add(keyOf(target));
		}
		if (style > 0) {
			state.styles().put(keyOf(target), Math.min(style, TextureStyles.MAX - 1));
		}
		state.save();
		reload();
		return null;
	}

	/**
	 * Replaces a claimed featured cosmetic with the catalog's current version (model and settings file). It keeps its
	 * place: same folder, still equipped, same style.
	 */
	public void updateFeatured(LocalCosmetic entry, byte[] bytes, byte[] settings, JsonElement gradient) throws IOException {
		byte[] settingsFile = settings != null && CosmeticSettingsFile.isSettingsJson(settings) ? settings : null;
		if (gradient != null) {
			settingsFile = CosmeticSettingsFile.withGradient(settingsFile, entry.path.getFileName().toString(), gradient);
		}
		if (settingsFile != null) {
			Path settingsPath = CosmeticSettingsFile.pathFor(entry.path);
			Path settingsTemp = settingsPath.resolveSibling(settingsPath.getFileName() + ".tmp");
			Files.write(settingsTemp, settingsFile);
			Files.move(settingsTemp, settingsPath, StandardCopyOption.REPLACE_EXISTING);
		}
		Path temp = entry.path.resolveSibling(entry.path.getFileName() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, entry.path, StandardCopyOption.REPLACE_EXISTING);
		reload();
	}

	/**
	 * Makes an editable copy of a claimed featured cosmetic: its folder is copied into Orbit/Cosmetics, where the copy
	 * can be changed. The claimed one stays as it is (and stays equipped if it was).
	 */
	public void makeEditable(LocalCosmetic entry) throws IOException {
		if (!entry.isFeatured()) {
			return;
		}
		Path source = entry.path.getParent();
		Path target = uniqueFolder(entry.isBodyPart() ? paths.bodyParts() : paths.cosmetics(), source.getFileName().toString());
		Files.createDirectories(target);
		try (Stream<Path> files = Files.list(source)) {
			for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
				Files.copy(file, target.resolve(file.getFileName()));
			}
		}
		reload();
	}

	/**
	 * Wears exactly these cosmetics (keys) with these alternate textures, as an outfit does. Keys of cosmetics that are
	 * gone are left out.
	 */
	public void wear(List<String> keys, Map<String, Integer> styles) {
		state.equipped().clear();
		for (LocalCosmetic entry : entries.values()) {
			entry.loaded = keys.contains(entry.key);
			if (entry.loaded) {
				state.equipped().add(entry.key);
			}
			Integer style = styles.get(entry.key);
			if (style != null) {
				entry.style = Math.clamp(style, 0, TextureStyles.MAX - 1);
				if (entry.style == 0) {
					state.styles().remove(entry.key);
				} else {
					state.styles().put(entry.key, entry.style);
				}
			}
		}
		state.save();
		changed();
	}

	/** A folder name for a display name: "Cool Wings" becomes "Cool_Wings", which shows as "Cool Wings" again. */
	public static String folderName(String name) {
		String cleaned = name.trim().replace(' ', '_').replaceAll("[^A-Za-z0-9._-]", "");
		return cleaned.isEmpty() || cleaned.startsWith(".") ? "cosmetic" + cleaned : cleaned;
	}

	public static Path uniqueFolder(Path directory, String base) {
		Path folder = directory.resolve(base);
		for (int i = 2; Files.exists(folder); i++) {
			folder = directory.resolve(base + "_" + i);
		}
		return folder;
	}

	/**
	 * Writes a new cosmetic template straight into a folder of its own in Orbit/Cosmetics (new_cosmetic, new_cosmetic_2,
	 * ...), with a short guide next to it, and returns its path. It shows up in the menu right away.
	 */
	public Path createTemplate() throws IOException {
		Path folder = uniqueFolder(paths.cosmetics(), "new_cosmetic");
		Files.createDirectories(folder);
		Path target = folder.resolve(folder.getFileName() + ".bbmodel");
		Files.writeString(folder.resolve("HOW_TO.txt"), TemplateGenerator.guide(), StandardCharsets.UTF_8);
		Files.write(target, TemplateGenerator.generate(folder.getFileName().toString()));
		return target;
	}

	/** Like {@link #createTemplate()}, for a body part: Orbit/Body Parts/new_body_part/, with its guide. */
	public Path createBodyPartTemplate() throws IOException {
		Path folder = uniqueFolder(paths.bodyParts(), "new_body_part");
		Files.createDirectories(folder);
		Path target = folder.resolve(folder.getFileName() + ".bbmodel");
		Files.writeString(folder.resolve("HOW_TO.txt"), TemplateGenerator.bodyPartGuide(), StandardCharsets.UTF_8);
		Files.write(target, TemplateGenerator.generateBodyPart(folder.getFileName().toString()));
		return target;
	}

	/** Turns one part of a body part on or off (the last part in use stays on). */
	public void setPartEnabled(LocalCosmetic entry, Anchor part, boolean enabled) {
		int disabled = enabled ? entry.disabledParts & ~BodyParts.bit(part) : entry.disabledParts | BodyParts.bit(part);
		if ((entry.validParts & ~disabled) == 0 || disabled == entry.disabledParts) {
			return;
		}
		entry.disabledParts = disabled;
		writeSettings(entry);
		changed();
	}

	static String stripExtension(String fileName) {
		return fileName.toLowerCase(Locale.ROOT).endsWith(".bbmodel") ? fileName.substring(0, fileName.length() - ".bbmodel".length()) : fileName;
	}

	private static String sanitizeFileName(String fileName) {
		String cleaned = fileName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		return cleaned.isEmpty() || cleaned.startsWith(".") ? "cosmetic" + cleaned + ".bbmodel" : cleaned;
	}

	/** Parser messages can quote Java exception names, which mean nothing to players; this removes them. */
	public static String readable(String message) {
		return message.replaceAll("(?:[a-z0-9_]+\\.)+[A-Za-z0-9_]*(?:Exception|Error): ", "");
	}

	public static Comparator<LocalCosmetic> byName() {
		return Comparator.comparing(entry -> entry.displayName().toLowerCase(Locale.ROOT));
	}
}
