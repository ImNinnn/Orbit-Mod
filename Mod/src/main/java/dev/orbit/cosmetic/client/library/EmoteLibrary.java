package dev.orbit.cosmetic.client.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.OrbitPaths;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.emote.EmoteAnimation;
import dev.orbit.cosmetic.common.emote.EmoteMode;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.model.TemplateGenerator;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The player's emotes: one per folder in {@code Orbit/Emotes}, like cosmetics ({@code Orbit/Emotes/wave/wave.bbmodel}
 * is the emote "wave"); a model saved straight into Orbit/Emotes is moved into a folder of its own. An emote file has
 * a PLAY animation, a LOOP animation, or both (see {@link EmoteAnimation}). The {@code .json} next to it (see
 * {@link CosmeticSettingsFile}) holds its name, tile colours ({@code "gradient"}), whether it is Standard or Mobile
 * ({@code "mode"}) and its particles; emotes without one get one when first read. The emote wheels are kept in the
 * {@link OrbitState}.
 */
public final class EmoteLibrary {
	private static final int MAX_SIDECAR_BYTES = 256 * 1024;

	/** One emote file. Mutated only on the client thread. */
	public static final class EmoteFile {
		final Path path;
		final String key;
		long modified;
		long size;
		boolean checking = true;
		List<Issue> issues = List.of();
		CosmeticModel model;
		EmoteAnimation animation;
		byte[] bytes;
		AssetId asset;
		int[] gradient;
		/** The {@code "name"} in the file's {@code .json} (a claimed featured emote's catalog name), or null. */
		String jsonName;
		EmoteMode mode = EmoteMode.STANDARD;
		Map<String, CosmeticSettingsFile.SavedParticles> particles = new LinkedHashMap<>();
		/** Contents of the {@code .json}, kept so unknown keys survive rewriting; null when it is not Orbit's. */
		JsonObject settingsRoot;

		EmoteFile(Path path, String key) {
			this.path = path;
			this.key = key;
		}

		public Path path() {
			return path;
		}

		/** Path inside Orbit/Emotes (or the featured folder, with {@link CosmeticLibrary#FEATURED} first). */
		public String key() {
			return key;
		}

		public boolean isChecking() {
			return checking;
		}

		public List<Issue> issues() {
			return issues;
		}

		public CosmeticModel model() {
			return model;
		}

		/** Canonical (texture-embedded) bytes; what gets uploaded and what {@link #asset()} hashes. */
		public byte[] bytes() {
			return bytes;
		}

		public AssetId asset() {
			return asset;
		}

		/** The tiles' top and bottom colours from the file's {@code .json}, or null. */
		public int[] gradient() {
			return gradient;
		}

		/** The name it has in the featured catalog: from its {@code .json}, or else the folder name. */
		public String catalogName() {
			return jsonName != null ? jsonName : folderName();
		}

		/** The folder's name, with underscores as spaces: Orbit/Emotes/big_wave/ is "big wave". */
		String folderName() {
			Path folder = path.getParent();
			String name = folder == null ? CosmeticLibrary.stripExtension(path.getFileName().toString()) : folder.getFileName().toString();
			return name.replace('_', ' ').trim();
		}

		/** Claimed from the featured catalog: kept as Orbit Cloud has it. */
		public boolean isFeatured() {
			return key.startsWith(CosmeticLibrary.FEATURED);
		}

		/** Every particle emitter's settings (saved, or the defaults). */
		public List<ParticleSettings> particles() {
			return model == null ? List.of() : CosmeticSettingsFile.particles(model, particles);
		}
	}

	/** One emote, ready to play. */
	public record LocalEmote(EmoteFile file, EmoteAnimation animation) {
		/** The key used for wheels: the file's. */
		public String key() {
			return file.key;
		}

		public String displayName() {
			return file.isFeatured() ? file.catalogName() : file.jsonName != null ? file.jsonName : file.folderName();
		}

		public AssetId asset() {
			return file.asset;
		}
	}

	private final OrbitPaths paths;
	private final OrbitState state;
	private final Map<String, EmoteFile> files = new LinkedHashMap<>();
	private final List<Runnable> listeners = new ArrayList<>();
	private List<LocalEmote> emotes = List.of();
	private boolean scanning;
	private boolean rescan;
	private int scans;

	public EmoteLibrary(OrbitPaths paths, OrbitState state) {
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
		List<LocalEmote> all = new ArrayList<>();
		for (EmoteFile file : files.values()) {
			if (file.model != null && file.animation != null) {
				all.add(new LocalEmote(file, file.animation));
			}
		}
		all.sort(Comparator.comparing(emote -> emote.displayName().toLowerCase(Locale.ROOT)));
		emotes = List.copyOf(all);
		for (Runnable listener : List.copyOf(listeners)) {
			listener.run();
		}
	}

	/** Every usable emote, by name. */
	public List<LocalEmote> emotes() {
		return emotes;
	}

	public List<EmoteFile> files() {
		return List.copyOf(files.values());
	}

	public LocalEmote find(String key) {
		if (key == null) {
			return null;
		}
		for (LocalEmote emote : emotes) {
			if (emote.key().equals(key)) {
				return emote;
			}
		}
		return null;
	}

	/** The local emote file with this hash, if any (so other players' copies of it need no download). */
	public EmoteFile findFile(AssetId asset) {
		for (EmoteFile file : files.values()) {
			if (asset.equals(file.asset)) {
				return file;
			}
		}
		return null;
	}

	/** The claimed featured emote file with this hash, or null. The player's own copies do not count. */
	public EmoteFile findFeaturedFile(AssetId asset) {
		for (EmoteFile file : files.values()) {
			if (file.isFeatured() && asset.equals(file.asset)) {
				return file;
			}
		}
		return null;
	}

	public boolean isScanning() {
		return scanning;
	}

	// ---------------------------------------------------------------- settings and wheels

	/** Standard or Mobile; emotes you can walk with (WALKING, SPRINTING, BACKWARDS) are always Mobile. */
	public EmoteMode mode(LocalEmote emote) {
		return emote.animation().isMobile() ? EmoteMode.MOBILE : emote.file().mode;
	}

	/** Standard or Mobile; kept in the emote's {@code .json}. */
	public void setMode(LocalEmote emote, EmoteMode mode) {
		emote.file().mode = mode;
		writeSettings(emote.file());
		changed();
	}

	/** Makes a group of the emote a particle emitter, with the default settings (the player's own emotes only). */
	public void addParticles(LocalEmote emote, int bone) {
		EmoteFile file = emote.file();
		if (file.model != null && file.particles().size() < ParticleSettings.MAX_EMITTERS && file.particles().stream().noneMatch(p -> p.bone() == bone)) {
			setParticles(emote, CosmeticSettingsFile.newEmitter(file.model, bone));
		}
	}

	/** A group of the emote stops being a particle emitter. */
	public void removeParticles(LocalEmote emote, int bone) {
		EmoteFile file = emote.file();
		if (file.isFeatured() || file.model == null || bone >= file.model.bones().size()) {
			return;
		}
		Map<String, CosmeticSettingsFile.SavedParticles> updated = new LinkedHashMap<>(file.particles);
		if (updated.remove(CosmeticSettingsFile.boneKey(file.model.bones().get(bone))) != null) {
			file.particles = updated;
			writeSettings(file);
			changed();
		}
	}

	/** Sets the settings of one of the emote's particle emitters (the player's own emotes only). */
	public void setParticles(LocalEmote emote, ParticleSettings settings) {
		EmoteFile file = emote.file();
		if (file.isFeatured() || file.model == null || settings.bone() >= file.model.bones().size()) {
			return;
		}
		String key = CosmeticSettingsFile.boneKey(file.model.bones().get(settings.bone()));
		String texture = settings.texture() >= 0 && settings.texture() < file.model.textures().size() ? file.model.textures().get(settings.texture()).name() : null;
		Map<String, CosmeticSettingsFile.SavedParticles> updated = new LinkedHashMap<>(file.particles);
		updated.put(key, new CosmeticSettingsFile.SavedParticles(texture, settings));
		file.particles = updated;
		writeSettings(file);
		changed();
	}

	/** Writes an emote's {@code .json}: mode and particles, keeping everything else in it. */
	private void writeSettings(EmoteFile file) {
		if (file.model == null || file.settingsRoot == null) {
			return;
		}
		JsonObject out = file.settingsRoot.deepCopy();
		out.addProperty(CosmeticSettingsFile.MARKER, CosmeticSettingsFile.FORMAT);
		out.addProperty("model", file.path.getFileName().toString());
		out.addProperty("mode", file.mode.name().toLowerCase(Locale.ROOT));
		CosmeticSettingsFile.writeParticles(out, file.model, file.particles);
		long modified = CosmeticSettingsFile.save(file.path, file.settingsRoot, out);
		if (modified < 0) {
			OrbitClient.LOGGER.warn("Could not save {}", CosmeticSettingsFile.pathFor(file.path));
			return;
		}
		// Not a change of the file: no need to read it again.
		file.modified = Math.max(file.modified, modified);
	}

	public List<OrbitState.Wheel> wheels() {
		return state.wheels();
	}

	public int selectedWheel() {
		return state.selectedWheel();
	}

	public void selectWheel(int index) {
		state.selectWheel(index);
	}

	public LocalEmote slot(int wheel, int slot) {
		return find(state.wheels().get(wheel).slots[slot]);
	}

	public void setSlot(int wheel, int slot, LocalEmote emote) {
		state.wheels().get(wheel).slots[slot] = emote == null ? null : emote.key();
		state.save();
		changed();
	}

	/** Puts the emote in the first empty slot of the wheel; false if the wheel is full. */
	public boolean addToWheel(int wheel, LocalEmote emote) {
		String[] slots = state.wheels().get(wheel).slots;
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] == null || find(slots[i]) == null) {
				setSlot(wheel, i, emote);
				return true;
			}
		}
		return false;
	}

	public boolean addWheel() {
		if (state.wheels().size() >= OrbitState.MAX_WHEELS) {
			return false;
		}
		state.wheels().add(new OrbitState.Wheel("Wheel #" + (state.wheels().size() + 1)));
		state.selectWheel(state.wheels().size() - 1);
		changed();
		return true;
	}

	public void removeWheel(int wheel) {
		if (state.wheels().size() > 1) {
			state.wheels().remove(wheel);
			state.selectWheel(Math.min(wheel, state.wheels().size() - 1));
			changed();
		}
	}

	public void renameWheel(int wheel, String name) {
		String cleaned = name.strip();
		state.wheels().get(wheel).name = cleaned.isEmpty() ? "Wheel #" + (wheel + 1) : cleaned.substring(0, Math.min(24, cleaned.length()));
		state.save();
		changed();
	}

	// ---------------------------------------------------------------- scanning

	public void reload() {
		if (scanning) {
			// Files changed during this scan: scan once more when it is done.
			rescan = true;
			return;
		}
		scanning = true;
		ModelLimits limits = OrbitClient.get().config().modelLimits();
		Util.ioPool().execute(() -> {
			CosmeticLibrary.moveLooseFiles(paths.emotes());
			List<Path> found = new ArrayList<>(CosmeticLibrary.scan(paths.emotes()));
			found.addAll(CosmeticLibrary.scan(paths.featuredEmotes()));
			Minecraft.getInstance().execute(() -> applyScan(found, limits));
		});
	}

	/** Path in Orbit/Emotes, or {@link CosmeticLibrary#FEATURED} and the path in the featured folder. */
	private String keyOf(Path file) {
		if (file.startsWith(paths.featuredEmotes())) {
			return CosmeticLibrary.FEATURED + paths.featuredEmotes().relativize(file).toString().replace('\\', '/');
		}
		return paths.emotes().relativize(file).toString().replace('\\', '/');
	}

	private void applyScan(List<Path> found, ModelLimits limits) {
		scanning = false;
		if (rescan) {
			rescan = false;
			reload();
		}
		boolean announce = scans++ > 0;
		Set<String> seen = new LinkedHashSet<>();
		Set<Path> folders = new LinkedHashSet<>();
		for (Path path : found) {
			// One emote per folder: a second model in the same folder is left alone.
			if (!folders.add(path.getParent())) {
				continue;
			}
			String key = keyOf(path);
			seen.add(key);
			long modified;
			long size;
			try {
				// A changed .json counts as a change of the emote.
				modified = Math.max(Files.getLastModifiedTime(path).toMillis(), CosmeticSettingsFile.modified(path));
				size = Files.size(path);
			} catch (IOException e) {
				continue;
			}
			EmoteFile file = files.get(key);
			if (file != null && file.modified == modified && file.size == size && !file.checking) {
				continue;
			}
			if (file == null) {
				file = new EmoteFile(path, key);
				files.put(key, file);
			}
			file.modified = modified;
			file.size = size;
			file.checking = true;
			validate(file, limits, announce);
		}
		files.keySet().removeIf(key -> !seen.contains(key));
		changed();
	}

	private void validate(EmoteFile file, ModelLimits limits, boolean announce) {
		Util.backgroundExecutor().execute(() -> {
			CosmeticLibrary.Loaded loaded = CosmeticLibrary.load(file.path, limits);
			CosmeticSettingsFile.Data settings = CosmeticSettingsFile.read(file.path);
			Minecraft.getInstance().execute(() -> {
				if (files.get(file.key) != file) {
					return;
				}
				file.checking = false;
				file.gradient = TileColors.of(settings.root().get("gradient"));
				file.jsonName = settings.name() == null || settings.name().isBlank() ? null : BBModelParser.sanitizeName(settings.name());
				file.mode = EmoteMode.byName(text(settings.root(), "mode"));
				file.particles = new LinkedHashMap<>(settings.particles());
				file.settingsRoot = settings.foreign() ? null : settings.root();
				List<Issue> issues = new ArrayList<>(loaded.issues());
				BBModelParser.Result result = loaded.result();
				String problem = result != null && result.ok() ? EmoteAnimation.problem(result.model()) : null;
				if (problem != null) {
					issues.add(Issue.error("emote_animations", problem));
				}
				file.issues = List.copyOf(issues);
				if (result != null && result.ok() && problem == null) {
					if (!file.isFeatured() && !settings.exists()) {
						writeSidecar(file.path, CosmeticSettingsFile.minimal(file.path.getFileName().toString()));
						file.settingsRoot = CosmeticSettingsFile.parse(new String(CosmeticSettingsFile.minimal(file.path.getFileName().toString()), StandardCharsets.UTF_8));
					}
					file.model = result.model();
					file.animation = EmoteAnimation.of(file.model);
					file.bytes = loaded.bytes();
					file.asset = AssetId.of(loaded.bytes());
					OrbitClient.get().registry().prepare(file.asset, file.model);
				} else {
					file.model = null;
					file.animation = null;
					file.bytes = null;
					file.asset = null;
					if (announce) {
						String reason = CosmeticLibrary.readable(issues.stream().filter(Issue::isError).map(Issue::message).findFirst().orElse("Not a valid Blockbench model"));
						Notifications.error(Component.translatable("orbit_cosmetic.notify.emote_failed", file.folderName()), Component.literal(reason));
					}
				}
				changed();
			});
		});
	}

	private static String text(JsonObject root, String key) {
		JsonElement element = root.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	/** Writes an emote file's {@code .json} next to it; a failure only means the file has none. */
	private static void writeSidecar(Path emoteFile, byte[] content) {
		Path sidecar = CosmeticSettingsFile.pathFor(emoteFile);
		try {
			Path temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
			Files.write(temp, content);
			Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Could not create {}", sidecar, e);
		}
	}

	// ---------------------------------------------------------------- files

	/**
	 * Keeps an emote claimed from the featured catalog in {@code Orbit/.featured/Emotes/<name>/<name>.bbmodel},
	 * unchanged so it keeps its identity on Orbit Cloud.
	 *
	 * @return the key the emote will have once the file is read
	 */
	public String installFeatured(String name, byte[] bytes, byte[] settings, JsonElement gradient) throws IOException {
		AssetId id = AssetId.of(bytes);
		EmoteFile existing = findFeaturedFile(id);
		if (existing != null) {
			return existing.key;
		}
		String base = CosmeticLibrary.folderName(name);
		Path folder = CosmeticLibrary.uniqueFolder(paths.featuredEmotes(), base);
		Files.createDirectories(folder);
		Path target = folder.resolve(folder.getFileName() + ".bbmodel");
		// Orbit Cloud's settings file (details, tile colours, particles), or one made from the catalog's colours.
		byte[] sidecar = settings != null && settings.length <= MAX_SIDECAR_BYTES && CosmeticSettingsFile.isSettingsJson(settings) ? settings : null;
		if (gradient != null) {
			sidecar = CosmeticSettingsFile.withGradient(sidecar, target.getFileName().toString(), gradient);
		}
		if (sidecar != null) {
			Files.write(CosmeticSettingsFile.pathFor(target), sidecar);
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		reload();
		return keyOf(target);
	}

	/**
	 * Replaces a claimed featured emote file with the catalog's current version (and its {@code .json}). It keeps its
	 * place: same file, same wheels.
	 */
	public void updateFeatured(EmoteFile file, byte[] bytes, byte[] settings, JsonElement gradient) throws IOException {
		byte[] sidecar = settings != null && settings.length <= MAX_SIDECAR_BYTES && CosmeticSettingsFile.isSettingsJson(settings) ? settings : null;
		if (gradient != null) {
			sidecar = CosmeticSettingsFile.withGradient(sidecar, file.path.getFileName().toString(), gradient);
		}
		if (sidecar != null) {
			writeSidecar(file.path, sidecar);
		}
		Path temp = file.path.resolveSibling(file.path.getFileName() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, file.path, StandardCopyOption.REPLACE_EXISTING);
		reload();
	}

	/**
	 * Makes an editable copy of a claimed featured emote (its folder) in Orbit/Emotes. The claimed one stays as it is,
	 * on the wheels it was on.
	 */
	public void makeEditable(EmoteFile file) throws IOException {
		if (!file.isFeatured()) {
			return;
		}
		Path folder = CosmeticLibrary.uniqueFolder(paths.emotes(), CosmeticLibrary.folderName(file.catalogName()));
		Files.createDirectories(folder);
		Path target = folder.resolve(folder.getFileName() + ".bbmodel");
		Path sidecar = CosmeticSettingsFile.pathFor(file.path);
		if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
			// The copy is the player's own: named after its folder, like any other.
			JsonObject root = CosmeticSettingsFile.parse(Files.readString(sidecar, StandardCharsets.UTF_8));
			if (root != null) {
				root.remove("name");
				root.addProperty("model", target.getFileName().toString());
				CosmeticSettingsFile.save(target, new JsonObject(), root);
			}
		}
		Files.copy(file.path, target);
		reload();
	}

	/** Deletes an emote (for featured ones, the claimed copy): its folder's model and {@code .json}. It leaves the wheels. */
	public boolean remove(EmoteFile file) {
		try {
			Files.deleteIfExists(file.path);
			Files.deleteIfExists(CosmeticSettingsFile.pathFor(file.path));
			Path folder = file.path.getParent();
			if (folder != null && !folder.equals(paths.emotes()) && !folder.equals(paths.featuredEmotes())) {
				try (var rest = Files.list(folder)) {
					if (rest.findAny().isEmpty()) {
						Files.delete(folder);
					}
				}
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not delete {}", file.path, e);
			return false;
		}
		for (OrbitState.Wheel wheel : state.wheels()) {
			for (int i = 0; i < wheel.slots.length; i++) {
				if (file.key.equals(wheel.slots[i])) {
					wheel.slots[i] = null;
				}
			}
		}
		state.save();
		files.remove(file.key);
		changed();
		reload();
		return true;
	}

	/**
	 * Writes a new emote template straight into a folder of its own in Orbit/Emotes (new_emote, new_emote_2, ...), with
	 * a short guide next to it, and returns its path. It shows up in the menu right away.
	 */
	public Path createTemplate() throws IOException {
		Path folder = CosmeticLibrary.uniqueFolder(paths.emotes(), "new_emote");
		Files.createDirectories(folder);
		Path target = folder.resolve(folder.getFileName() + ".bbmodel");
		Files.writeString(folder.resolve("HOW_TO.txt"), TemplateGenerator.emoteGuide(), StandardCharsets.UTF_8);
		Files.write(target, TemplateGenerator.generateEmote(folder.getFileName().toString()));
		return target;
	}
}
