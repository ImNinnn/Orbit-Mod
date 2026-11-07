package dev.orbit.cosmetic.client.library;

import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.protocol.AssetId;

import com.google.gson.JsonObject;

import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.BodyParts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cosmetic in Orbit/Cosmetics: one .bbmodel in its own folder. Mutated only on the client thread. */
public final class LocalCosmetic {
	/** Where a cosmetic sits on the player, for the Orbit menu's sections. */
	public enum Category {
		HEAD,
		BODY,
		ARMS,
		LEGS
	}

	public enum Status {
		/** Being read and validated in the background. */
		CHECKING,
		READY,
		INVALID
	}

	final Path path;
	/** Path relative to Orbit/Cosmetics with '/' separators; the key used in orbit-state.json. */
	final String key;
	long modified;
	long size;
	Status status = Status.CHECKING;
	List<Issue> issues = List.of();
	CosmeticModel model;
	AssetId asset;
	byte[] bytes;
	boolean loaded;
	String duplicateOf;
	/** Saved settings by animation name; survives edits of the model that reorder animations. */
	Map<String, AnimationSettings> savedSettings = Map.of();
	/** Saved physics by part key (see {@link #boneKey}); survives edits that reorder groups. */
	Map<String, List<AxisDrive>> savedPhysics = new LinkedHashMap<>();
	/** Saved particle settings by part key; emitters without any use the defaults. */
	Map<String, CosmeticSettingsFile.SavedParticles> savedParticles = new LinkedHashMap<>();
	/** Contents of the settings file, kept so unknown keys survive rewriting. */
	JsonObject settingsRoot = new JsonObject();
	long settingsModified = -1;
	/** A same-named JSON exists that is not an Orbit settings file; it is never written. */
	boolean settingsForeign;
	String settingsProblem;
	String nameOverride;
	/** The cosmetic's folder name, or its file name when a folder holds several models (set by every scan). */
	String baseName = "";
	String author;
	String description;
	/** The chosen alternate texture (see {@link dev.orbit.cosmetic.common.model.TextureStyles}). */
	int style;
	/** Tile colours from the settings file, or null. */
	int[] gradient;
	/** For a body part: the parts its file can replace (see {@link BodyParts#valid}), and those turned off in game. */
	int validParts;
	int disabledParts;

	LocalCosmetic(Path path, String key) {
		this.path = path;
		this.key = key;
	}

	public Path path() {
		return path;
	}

	/** A body part (Orbit/Body Parts): replaces parts of the player instead of adding to them. */
	public boolean isBodyPart() {
		return key.startsWith(CosmeticLibrary.BODY) || key.startsWith(CosmeticLibrary.FEATURED_BODY);
	}

	/** For a body part: the parts it can replace (bits of {@link Anchor#ordinal()}, see {@link BodyParts}). */
	public int validParts() {
		return validParts;
	}

	/** For a body part: the parts it replaces when worn (those it can replace, minus those turned off). */
	public int replacedParts() {
		return validParts & ~disabledParts;
	}

	/** Claimed from the featured catalog: kept as Orbit Cloud has it, and not edited. */
	public boolean isFeatured() {
		return key.startsWith(CosmeticLibrary.FEATURED);
	}

	public String key() {
		return key;
	}

	public String displayName() {
		if (nameOverride != null) {
			return nameOverride;
		}
		// The folder name, with underscores as spaces: Orbit/Cosmetics/cool_wings/ is "cool wings".
		return baseName.replace('_', ' ').trim();
	}

	public Status status() {
		return status;
	}

	public List<Issue> issues() {
		if (settingsProblem == null) {
			return issues;
		}
		List<Issue> all = new ArrayList<>(issues);
		all.add(Issue.warning("settings_file", settingsProblem));
		return all;
	}

	/** The JSON file next to the model that holds this cosmetic's settings and details. */
	public Path settingsPath() {
		return CosmeticSettingsFile.pathFor(path);
	}

	public String author() {
		return author;
	}

	public String description() {
		return description;
	}

	public CosmeticModel model() {
		return model;
	}

	public AssetId asset() {
		return asset;
	}

	/** Canonical (texture-embedded) bytes; what gets uploaded and what {@link #asset()} hashes. */
	public byte[] bytes() {
		return bytes;
	}

	public boolean isLoaded() {
		return loaded;
	}

	public boolean isUsable() {
		return status == Status.READY && duplicateOf == null && (!isBodyPart() || replacedParts() != 0);
	}

	public String duplicateOf() {
		return duplicateOf;
	}

	/** The sections of the Orbit menu this cosmetic appears in, from the body parts its geometry follows. */
	public Set<Category> categories() {
		Set<Category> categories = EnumSet.noneOf(Category.class);
		if (model == null || isBodyPart()) {
			return categories;
		}
		for (Anchor anchor : model.anchorsUsed()) {
			categories.add(switch (anchor) {
				case HEAD -> Category.HEAD;
				case LEFT_ARM, RIGHT_ARM -> Category.ARMS;
				case LEFT_LEG, RIGHT_LEG -> Category.LEGS;
				case BODY, ROOT -> Category.BODY;
			});
		}
		return categories;
	}

	public boolean hasAnimations() {
		return model != null && !isBodyPart() && model.hasAnimations();
	}

	/** Stable identity of a part: its Blockbench uuid, or its name for files without uuids. */
	public String boneKey(int bone) {
		if (model == null || bone < 0 || bone >= model.bones().size()) {
			return null;
		}
		Bone b = model.bones().get(bone);
		return b.uuid() != null ? b.uuid() : "name:" + b.name();
	}

	/** Physics links resolved against the current model; links to parts that no longer exist are skipped. */
	public List<PhysicsLink> physicsLinks() {
		if (model == null) {
			return List.of();
		}
		List<PhysicsLink> links = new ArrayList<>();
		for (Map.Entry<String, List<AxisDrive>> entry : savedPhysics.entrySet()) {
			for (Bone bone : model.bones()) {
				String key = bone.uuid() != null ? bone.uuid() : "name:" + bone.name();
				if (key.equals(entry.getKey())) {
					links.add(new PhysicsLink(bone.index(), entry.getValue()));
					break;
				}
			}
		}
		return links;
	}

	/** The axes configured for a part, or null if it has no physics. */
	public List<AxisDrive> physicsFor(int bone) {
		String key = boneKey(bone);
		return key == null ? null : savedPhysics.get(key);
	}

	/** Every particle emitter's settings (saved, or the defaults), resolved against the current model. */
	public List<ParticleSettings> particles() {
		return model == null ? List.of() : CosmeticSettingsFile.particles(model, savedParticles);
	}

	/** What this cosmetic looks like in the equipped list published to Orbit Cloud. */
	public EquippedCosmetic toEquipped() {
		if (isBodyPart()) {
			// Body parts have no animations or physics: they move with the player.
			return new EquippedCosmetic(asset, displayName(), List.of(), List.of(), style, particles(), replacedParts(), textureAnimations());
		}
		return new EquippedCosmetic(asset, displayName(), animationSettings(), physicsLinks(), style, particles(), 0, textureAnimations());
	}

	/**
	 * The animated textures (frames stacked in the image): as set in the settings file ({@code "textures"}, by
	 * texture name), else as Blockbench saved them in the model.
	 */
	public List<dev.orbit.cosmetic.common.anim.TextureAnimation> textureAnimations() {
		if (model == null) {
			return List.of();
		}
		List<dev.orbit.cosmetic.common.anim.TextureAnimation> defaults = dev.orbit.cosmetic.common.anim.TextureAnimation.defaults(model.textures());
		com.google.gson.JsonObject saved = settingsRoot.get("textures") instanceof com.google.gson.JsonObject object ? object : null;
		List<dev.orbit.cosmetic.common.anim.TextureAnimation> result = new ArrayList<>();
		for (int i = 0; i < model.textures().size() && result.size() < dev.orbit.cosmetic.common.anim.TextureAnimation.MAX_PER_COSMETIC; i++) {
			var texture = model.textures().get(i);
			int index = i;
			if (saved != null && saved.get(texture.name()) instanceof com.google.gson.JsonObject json) {
				var animation = new dev.orbit.cosmetic.common.anim.TextureAnimation(i, number(json, "frame_width", texture.width()),
					number(json, "frame_height", texture.height()), number(json, "frame_ticks", 1));
				if (animation.frames(texture) > 1) {
					result.add(animation);
				}
				continue;
			}
			defaults.stream().filter(animation -> animation.texture() == index).findFirst().ifPresent(result::add);
		}
		return List.copyOf(result);
	}

	private static int number(com.google.gson.JsonObject json, String key, int fallback) {
		try {
			return json.has(key) ? json.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** The chosen alternate texture, from 0. */
	public int style() {
		return style;
	}

	/** The tile's top and bottom colours from the settings file ({@code "gradient"}), or null. */
	public int[] gradient() {
		return gradient;
	}

	/** Settings for every animation of the model, in model order. */
	public List<AnimationSettings> animationSettings() {
		if (model == null) {
			return List.of();
		}
		List<AnimationSettings> result = new ArrayList<>(model.animations().size());
		for (AnimationClip clip : model.animations()) {
			AnimationSettings saved = savedSettings.get(clip.name());
			result.add(saved != null ? saved : AnimationSettings.defaultsFor(clip));
		}
		return result;
	}
}
