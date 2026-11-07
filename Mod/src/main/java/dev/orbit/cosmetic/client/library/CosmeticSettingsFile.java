package dev.orbit.cosmetic.client.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.ParticleEmitters;
import dev.orbit.cosmetic.common.protocol.EquipCodec;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shareable settings of one cosmetic, stored next to the model with the same name:
 * {@code top_hat.bbmodel} has {@code top_hat.json}. It holds optional details (name, author, description), the
 * animation settings by animation name, the movement physics by part, and the colours of its tile in the Orbit
 * menu (one or two hex colours, see {@link TileColors}). Send both files to share a configured cosmetic.
 *
 * <pre>{@code
 * {
 *   "orbitCosmetic": 1,
 *   "model": "top_hat.bbmodel",
 *   "name": "Top hat", "author": "...", "description": "...",
 *   "animations": { "IDLE": { "trigger": "IDLE", "loop": "LOOP", "speed": 1.0, "priority": 10, "interrupt": false } },
 *   "physics": { "<group uuid>": { "part": "brim", "rotation_x": { "source": "FORWARD", "strength": 8, "min": 0, "max": 40, "resistance": 0.6 } } },
 *   "particles": { "<group uuid>": { "part": "particle1", "enabled": true, "texture": "particle", "glow": false, "speedY": 0.5, ... } },
 *   "gradient": ["#3B82F6"]
 * }
 * }</pre>
 *
 * Emote files have the same kind of file, with {@code "mode"} (standard or mobile) and {@code "particles"}.
 *
 * Files may come from other people, so they are read as untrusted data: size-limited, every value sanitized,
 * unknown keys kept untouched. A same-named JSON without the {@code orbitCosmetic} key belongs to another
 * program (Blockbench can export one) and is never overwritten.
 */
public final class CosmeticSettingsFile {
	static final String MARKER = "orbitCosmetic";
	static final int FORMAT = 1;
	private static final int MAX_BYTES = 256 * 1024;
	private static final int MAX_TEXT = 512;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** What was read from disk. {@code root} keeps unknown keys so rewriting does not lose them. */
	record Data(
		JsonObject root,
		boolean exists,
		boolean foreign,
		String problem,
		long modified,
		String name,
		String author,
		String description,
		Map<String, AnimationSettings> animations,
		Map<String, List<AxisDrive>> physics,
		Map<String, SavedParticles> particles
	) {
		static Data empty() {
			return new Data(new JsonObject(), false, false, null, -1, null, null, null, Map.of(), new LinkedHashMap<>(), new LinkedHashMap<>());
		}
	}

	/**
	 * Saved settings of one particle emitter. The texture is kept by name, so it survives textures being added or
	 * reordered; the settings' bone and texture indexes are filled in against the model when used.
	 */
	record SavedParticles(String texture, ParticleSettings settings) {
	}

	private CosmeticSettingsFile() {
	}

	static Path pathFor(Path model) {
		String file = model.getFileName().toString();
		String base = file.toLowerCase(Locale.ROOT).endsWith(".bbmodel") ? file.substring(0, file.length() - ".bbmodel".length()) : file;
		return model.resolveSibling(base + ".json");
	}

	static long modified(Path model) {
		try {
			Path file = pathFor(model);
			return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.getLastModifiedTime(file).toMillis() : -1;
		} catch (IOException e) {
			return -1;
		}
	}

	/** Reads the settings file of a model. Blocking; never throws. */
	static Data read(Path model) {
		Path file = pathFor(model);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return Data.empty();
		}
		long modified = modified(model);
		JsonObject root;
		try {
			if (Files.size(file) > MAX_BYTES) {
				return new Data(new JsonObject(), true, true, file.getFileName() + " is larger than " + MAX_BYTES / 1024 + " KiB and was ignored", modified, null, null, null, Map.of(), new LinkedHashMap<>(), new LinkedHashMap<>());
			}
			root = parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | JsonParseException | IllegalStateException | StackOverflowError e) {
			return new Data(new JsonObject(), true, true, file.getFileName() + " could not be read; it is left untouched and defaults are used", modified, null, null, null, Map.of(), new LinkedHashMap<>(), new LinkedHashMap<>());
		}
		if (root == null || !root.has(MARKER)) {
			return new Data(new JsonObject(), true, true, file.getFileName() + " is not an Orbit settings file; settings changes are not saved to it", modified, null, null, null, Map.of(), new LinkedHashMap<>(), new LinkedHashMap<>());
		}
		return new Data(root, true, false, null, modified, text(root, "name"), text(root, "author"), text(root, "description"),
			readAnimations(root), readPhysics(root), readParticles(root));
	}

	/** Parses settings JSON (also used for imported files). Returns null if it is not a JSON object. */
	static JsonObject parse(String text) {
		JsonReader reader = new JsonReader(new StringReader(text));
		reader.setStrictness(Strictness.LENIENT);
		reader.setNestingLimit(32);
		JsonElement element = JsonParser.parseReader(reader);
		return element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static Map<String, AnimationSettings> readAnimations(JsonObject root) {
		Map<String, AnimationSettings> result = new LinkedHashMap<>();
		JsonObject animations = object(root, "animations");
		if (animations == null) {
			return result;
		}
		for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
			if (result.size() >= EquipCodec.MAX_ANIMATIONS || !entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject s = entry.getValue().getAsJsonObject();
			AnimationTrigger trigger = parseEnum(AnimationTrigger.class, text(s, "trigger"), AnimationTrigger.NONE);
			result.put(BBModelParser.sanitizeName(entry.getKey()), new AnimationSettings(
				trigger,
				parseEnum(LoopMode.class, text(s, "loop"), trigger.defaultLoop()),
				number(s, "speed", 1),
				(int) number(s, "priority", trigger.defaultPriority()),
				bool(s, "interrupt"),
				number(s, "minWait", AnimationSettings.DEFAULT_MIN_WAIT),
				number(s, "maxWait", AnimationSettings.DEFAULT_MAX_WAIT)
			));
		}
		return result;
	}

	private static Map<String, List<AxisDrive>> readPhysics(JsonObject root) {
		Map<String, List<AxisDrive>> result = new LinkedHashMap<>();
		JsonObject physics = object(root, "physics");
		if (physics == null) {
			return result;
		}
		for (Map.Entry<String, JsonElement> entry : physics.entrySet()) {
			if (result.size() >= EquipCodec.MAX_PHYSICS_LINKS || !entry.getValue().isJsonObject() || entry.getKey().length() > MAX_TEXT) {
				continue;
			}
			JsonObject part = entry.getValue().getAsJsonObject();
			List<AxisDrive> axes = new ArrayList<>(PhysicsLink.AXES);
			for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
				JsonObject drive = object(part, PhysicsLink.AXIS_NAMES[axis]);
				axes.add(drive == null ? AxisDrive.OFF : new AxisDrive(
					parseEnum(PhysicsSource.class, text(drive, "source"), PhysicsSource.NONE),
					number(drive, "strength", 0),
					number(drive, "min", 0),
					number(drive, "max", 0),
					number(drive, "resistance", 0.5f)
				).clamped(axis >= 3));
			}
			result.put(entry.getKey(), List.copyOf(axes));
		}
		return result;
	}

	/** The particle emitters' settings by part key, as stored. Values are clamped like any {@link ParticleSettings}. */
	static Map<String, SavedParticles> readParticles(JsonObject root) {
		Map<String, SavedParticles> result = new LinkedHashMap<>();
		JsonObject particles = object(root, "particles");
		if (particles == null) {
			return result;
		}
		for (Map.Entry<String, JsonElement> entry : particles.entrySet()) {
			if (result.size() >= ParticleSettings.MAX_EMITTERS || !entry.getValue().isJsonObject() || entry.getKey().length() > MAX_TEXT) {
				continue;
			}
			JsonObject json = entry.getValue().getAsJsonObject();
			ParticleSettings defaults = ParticleSettings.defaults(0, 0);
			float[] values = defaults.values();
			for (int i = 0; i < values.length; i++) {
				values[i] = number(json, ParticleSettings.VALUE_NAMES[i], values[i]);
			}
			JsonElement enabled = json.get("enabled");
			ParticleSettings settings = defaults.withValues(values)
				.withEnabled(enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean() || enabled.getAsBoolean())
				.withGlow(bool(json, "glow"));
			result.put(entry.getKey(), new SavedParticles(text(json, "texture"), settings));
		}
		return result;
	}

	/** Puts particle settings into a settings file's JSON (replacing what it had). */
	static void writeParticles(JsonObject out, CosmeticModel model, Map<String, SavedParticles> particles) {
		JsonObject parts = new JsonObject();
		for (Map.Entry<String, SavedParticles> entry : particles.entrySet()) {
			ParticleSettings settings = entry.getValue().settings();
			JsonObject json = new JsonObject();
			String name = partName(model, entry.getKey());
			if (name != null) {
				json.addProperty("part", name);
			}
			json.addProperty("enabled", settings.enabled());
			if (entry.getValue().texture() != null) {
				json.addProperty("texture", entry.getValue().texture());
			}
			json.addProperty("glow", settings.glow());
			float[] values = settings.values();
			for (int i = 0; i < values.length; i++) {
				json.addProperty(ParticleSettings.VALUE_NAMES[i], values[i]);
			}
			parts.add(entry.getKey(), json);
		}
		out.add("particles", parts);
	}

	/**
	 * The emitters' settings for this model: the groups that have saved particle settings and still exist, in model
	 * order. A saved texture the model no longer has falls back to the default one.
	 */
	static List<ParticleSettings> particles(CosmeticModel model, Map<String, SavedParticles> saved) {
		List<ParticleSettings> result = new ArrayList<>();
		for (Bone bone : model.bones()) {
			SavedParticles entry = saved.get(boneKey(bone));
			if (entry == null || result.size() >= ParticleSettings.MAX_EMITTERS) {
				continue;
			}
			int texture = textureIndex(model, entry.texture());
			result.add(entry.settings().withBone(bone.index()).withTexture(texture >= 0 ? texture : ParticleEmitters.defaultTexture(model)));
		}
		return result;
	}

	/**
	 * The particle emitters in a settings file (a featured item's, for previews), for this model; empty when the
	 * file has none or cannot be read.
	 */
	public static List<ParticleSettings> particlesIn(byte[] settings, CosmeticModel model) {
		try {
			JsonObject root = parse(new String(settings, java.nio.charset.StandardCharsets.UTF_8));
			return root == null ? List.of() : particles(model, readParticles(root));
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	/** Settings for a group that just became an emitter. */
	static ParticleSettings newEmitter(CosmeticModel model, int bone) {
		return ParticleSettings.defaults(bone, ParticleEmitters.defaultTexture(model));
	}

	/** The key a part's settings are stored under: its group's UUID, or its name when it has none. */
	static String boneKey(Bone bone) {
		return bone.uuid() != null ? bone.uuid() : "name:" + bone.name();
	}

	static int textureIndex(CosmeticModel model, String name) {
		if (name == null) {
			return -1;
		}
		for (int i = 0; i < model.textures().size(); i++) {
			if (name.equals(model.textures().get(i).name())) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Writes the settings, keeping details and unknown keys already in the file.
	 *
	 * @return the new modification time, or -1 if writing failed
	 */
	static long write(Path model, JsonObject root, CosmeticModel cosmetic, List<AnimationSettings> settings, Map<String, List<AxisDrive>> physics,
		Map<String, SavedParticles> particles) {
		JsonObject out = root.deepCopy();
		out.addProperty(MARKER, FORMAT);
		out.addProperty("model", model.getFileName().toString());
		JsonObject animations = new JsonObject();
		for (int i = 0; i < cosmetic.animations().size() && i < settings.size(); i++) {
			AnimationClip clip = cosmetic.animations().get(i);
			AnimationSettings s = settings.get(i);
			JsonObject json = new JsonObject();
			json.addProperty("trigger", s.trigger().name());
			json.addProperty("loop", s.loop().name());
			json.addProperty("speed", s.speed());
			json.addProperty("priority", s.priority());
			json.addProperty("interrupt", s.interrupt());
			if (s.trigger() == AnimationTrigger.RANDOM) {
				json.addProperty("minWait", s.minWait());
				json.addProperty("maxWait", s.maxWait());
			}
			animations.add(clip.name(), json);
		}
		out.add("animations", animations);
		JsonObject parts = new JsonObject();
		for (Map.Entry<String, List<AxisDrive>> entry : physics.entrySet()) {
			JsonObject part = new JsonObject();
			String name = partName(cosmetic, entry.getKey());
			if (name != null) {
				part.addProperty("part", name);
			}
			for (int axis = 0; axis < PhysicsLink.AXES && axis < entry.getValue().size(); axis++) {
				AxisDrive drive = entry.getValue().get(axis);
				if (drive.source() == PhysicsSource.NONE && drive.strength() == 0 && drive.min() == 0 && drive.max() == 0) {
					continue;
				}
				JsonObject json = new JsonObject();
				json.addProperty("source", drive.source().name());
				json.addProperty("strength", drive.strength());
				json.addProperty("min", drive.min());
				json.addProperty("max", drive.max());
				json.addProperty("resistance", drive.resistance());
				part.add(PhysicsLink.AXIS_NAMES[axis], json);
			}
			parts.add(entry.getKey(), part);
		}
		out.add("physics", parts);
		writeParticles(out, cosmetic, particles);
		return save(model, root, out);
	}

	/** Writes {@code out} as the model's settings file and makes {@code root} hold it; returns the new modification time or -1. */
	static long save(Path model, JsonObject root, JsonObject out) {
		Path file = pathFor(model);
		try {
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			root.entrySet().clear();
			out.entrySet().forEach(e -> root.add(e.getKey(), e.getValue()));
			return modified(model);
		} catch (IOException e) {
			return -1;
		}
	}

	/** Whether a file is an Orbit settings file (checked before importing a JSON next to a model). */
	static boolean isSettingsFile(Path file) {
		try {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
				return false;
			}
			JsonObject root = parse(Files.readString(file, StandardCharsets.UTF_8));
			return root != null && root.has(MARKER);
		} catch (IOException | JsonParseException | IllegalStateException | StackOverflowError e) {
			return false;
		}
	}

	/** A new settings file holding only the marker and the model's name (for emote files). */
	static byte[] minimal(String modelFile) {
		JsonObject root = new JsonObject();
		root.addProperty(MARKER, FORMAT);
		root.addProperty("model", modelFile);
		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	/** Whether downloaded bytes are an Orbit settings file (size-limited JSON object with the marker key). */
	static boolean isSettingsJson(byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			return false;
		}
		try {
			JsonObject root = parse(new String(bytes, StandardCharsets.UTF_8));
			return root != null && root.has(MARKER);
		} catch (JsonParseException | IllegalStateException | StackOverflowError e) {
			return false;
		}
	}

	/**
	 * A settings file with tile colours: {@code settings} (an Orbit settings file, or null for a new one) with its
	 * {@code "gradient"} set, unless it has one already.
	 */
	static byte[] withGradient(byte[] settings, String modelFile, JsonElement gradient) {
		JsonObject root = settings == null ? null : parse(new String(settings, StandardCharsets.UTF_8));
		if (root == null) {
			root = new JsonObject();
			root.addProperty(MARKER, FORMAT);
			root.addProperty("model", modelFile);
		}
		if (!root.has("gradient")) {
			root.add("gradient", gradient.deepCopy());
		}
		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	private static String partName(CosmeticModel model, String key) {
		for (Bone bone : model.bones()) {
			String boneKey = bone.uuid() != null ? bone.uuid() : "name:" + bone.name();
			if (boneKey.equals(key)) {
				return bone.name();
			}
		}
		return null;
	}

	private static JsonObject object(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static String text(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		String value = element.getAsString();
		if (value.length() > MAX_TEXT) {
			value = value.substring(0, MAX_TEXT);
		}
		// Strip control characters and formatting codes so shared text cannot mess up the UI.
		return value.replaceAll("[\\p{Cntrl}§]", " ").trim();
	}

	private static float number(JsonObject parent, String key, float fallback) {
		JsonElement element = parent.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			float value = element.getAsFloat();
			return Float.isFinite(value) ? value : fallback;
		}
		return fallback;
	}

	private static boolean bool(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
		if (name == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
