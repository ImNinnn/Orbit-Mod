package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.TextureAnimation;

import java.util.List;

/**
 * One cosmetic in a player's equipped configuration: which asset, how its animations are assigned, which parts
 * react to movement, and which of its alternate textures it is drawn with.
 *
 * @param name display name supplied by the wearer; informational only and never trusted for identity
 * @param animations settings per animation index of the asset
 * @param physics movement-driven parts (see {@link PhysicsLink})
 * @param style the alternate texture, from 0 (see {@link dev.orbit.cosmetic.common.model.TextureStyles})
 * @param particles its particle emitters (see {@link ParticleSettings})
 * @param replaces for a body part, the vanilla parts it replaces (see {@link dev.orbit.cosmetic.common.model.BodyParts});
 *                 0 for other cosmetics
 * @param textures its animated textures (see {@link TextureAnimation})
 */
public record EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations, List<PhysicsLink> physics, int style, List<ParticleSettings> particles,
	int replaces, List<TextureAnimation> textures) {
	public EquippedCosmetic {
		animations = List.copyOf(animations);
		physics = physics == null ? List.of() : List.copyOf(physics);
		particles = particles == null ? List.of() : List.copyOf(particles);
		replaces &= dev.orbit.cosmetic.common.model.BodyParts.all();
		textures = textures == null ? List.of() : List.copyOf(textures);
	}

	public EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations, List<PhysicsLink> physics, int style, List<ParticleSettings> particles,
		int replaces) {
		this(asset, name, animations, physics, style, particles, replaces, List.of());
	}

	public EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations, List<PhysicsLink> physics, int style, List<ParticleSettings> particles) {
		this(asset, name, animations, physics, style, particles, 0);
	}

	public EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations, List<PhysicsLink> physics, int style) {
		this(asset, name, animations, physics, style, List.of());
	}

	public EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations, List<PhysicsLink> physics) {
		this(asset, name, animations, physics, 0, List.of());
	}

	public EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations) {
		this(asset, name, animations, List.of(), 0, List.of());
	}

	public EquippedCosmetic withParticles(List<ParticleSettings> value) {
		return new EquippedCosmetic(asset, name, animations, physics, style, value, replaces, textures);
	}

	public EquippedCosmetic withStyle(int value) {
		return new EquippedCosmetic(asset, name, animations, physics, value, particles, replaces, textures);
	}

	public EquippedCosmetic withTextures(List<TextureAnimation> value) {
		return new EquippedCosmetic(asset, name, animations, physics, style, particles, replaces, value);
	}

	/** True for a body part (it replaces vanilla parts). */
	public boolean isBodyPart() {
		return replaces != 0;
	}
}
