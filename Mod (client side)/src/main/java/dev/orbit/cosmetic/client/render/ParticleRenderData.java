package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.client.anim.ParticleSystem;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import org.joml.Matrix4f;

/**
 * A player's particle system, attached to their render state during extraction: {@link CosmeticLayer} spawns
 * particles at the emitters. Players in the world have their particles drawn with the level; players in menus
 * ({@code preview}) have them drawn with the player, in the preview's own space.
 */
public record ParticleRenderData(ParticleSystem system, boolean preview) {
	public static final RenderStateDataKey<ParticleRenderData> KEY = RenderStateDataKey.create(() -> "orbit_cosmetic:particles");
	/** The pose the renderer started the player with: where they stand, before turning them (set when drawing). */
	public static final RenderStateDataKey<Matrix4f> BASE = RenderStateDataKey.create(() -> "orbit_cosmetic:base_pose");
}
