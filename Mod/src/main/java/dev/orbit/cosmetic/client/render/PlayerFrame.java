package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.client.anim.ParticleSystem;
import dev.orbit.cosmetic.common.emote.BodyPose;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What Orbit draws on one player in one frame: worked out before the game draws the player (OrbitClient#extract for
 * players in the world, PreviewModel for players in menus) and read while it draws them (the player model, the
 * cosmetic layer, the cape). Each Minecraft version keeps it with the player being drawn its own way (see
 * gfx/PlayerState). Render thread.
 */
public final class PlayerFrame {
	/** The cosmetics drawn (with an emote's own props), or empty. */
	public List<RenderPiece> pieces = List.of();
	/** The emote's pose this frame, or null when none plays. */
	public @Nullable BodyPose emote;
	/** The player's particles: the cosmetics' emitters spawn them where the player is drawn. Null without cosmetics. */
	public @Nullable ParticleSystem particles;
	/** A player in one of Orbit's menus: always the vanilla model, and its particles are drawn with it. */
	public boolean preview;
	/** Where a menu player's faces are kept for clicking on them, or null. */
	public @Nullable PickBuffer pick;
	/** The pose the renderer started the player with (where they stand, before turning them); set when drawing. */
	public @Nullable Matrix4f base;

	/** The vanilla parts body parts replace (bits of Anchor#ordinal()), which the player model hides. */
	public int hiddenParts() {
		int parts = 0;
		for (RenderPiece piece : pieces) {
			parts |= piece.hiddenParts();
		}
		return parts;
	}

	/**
	 * Whether the player is drawn with a plain vanilla model (see VanillaPlayerModels): while emoting, and always in
	 * menus.
	 */
	public boolean usesVanillaModel() {
		return emote != null || preview;
	}
}
