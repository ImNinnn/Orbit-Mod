package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.anim.PoseBuffer;
import dev.orbit.cosmetic.common.model.Anchor;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One cosmetic to draw on one player this frame. {@code pose} may be null for the rest pose; {@code style} is the
 * alternate texture; {@code particles} are its particle emitters; {@code overlay} (see {@link OverlayTexture}) and
 * {@code color} (multiplied with its textures) tint it, which menus use to highlight it.
 *
 * @param anchors the body parts whose geometry is drawn (bits of {@link Anchor#ordinal()}): all of them for a
 *                cosmetic, only those it replaces for a body part
 * @param replaces a body part: the vanilla parts in {@code anchors} are hidden
 * @param skin the wearer's skin, drawn on the model's PLAYER/SKIN textures (null: unknown, they stay empty)
 */
public record RenderPiece(BakedCosmetic baked, PoseBuffer pose, int style, List<ParticleSettings> particles, int overlay, int color, int anchors,
	boolean replaces, @Nullable Identifier skin, float @Nullable [][] frames) {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int ALL_ANCHORS = (1 << Anchor.values().length) - 1;

	public RenderPiece(BakedCosmetic baked, PoseBuffer pose, int style, List<ParticleSettings> particles) {
		this(baked, pose, style, particles, OverlayTexture.NO_OVERLAY, WHITE, ALL_ANCHORS, false, null, null);
	}

	public RenderPiece(BakedCosmetic baked, PoseBuffer pose, int style) {
		this(baked, pose, style, List.of());
	}

	/** Whether this piece draws the geometry of a body part. */
	public boolean draws(Anchor anchor) {
		return (anchors & 1 << anchor.ordinal()) != 0;
	}

	/** The vanilla parts this piece hides (bits of {@link Anchor#ordinal()}); 0 unless it is a body part. */
	public int hiddenParts() {
		return replaces ? anchors : 0;
	}

	/** Hovered in a menu: slightly whitened. */
	public RenderPiece hovered() {
		return new RenderPiece(baked, pose, style, particles, OverlayTexture.pack(OverlayTexture.u(0.3F), OverlayTexture.v(false)), WHITE, anchors, replaces, skin, frames);
	}

	/** Selected in a menu: slightly reddened. */
	public RenderPiece selected() {
		return new RenderPiece(baked, pose, style, particles, OverlayTexture.NO_OVERLAY, 0xFFFFC4C4, anchors, replaces, skin, frames);
	}

	/** A body part replacing these parts of the player (bits of {@link Anchor#ordinal()}). */
	public RenderPiece replacing(int parts) {
		return new RenderPiece(baked, pose, style, particles, overlay, color, parts, true, skin, frames);
	}

	/**
	 * The frame each animated texture shows (see TextureAnimation#transforms): {u scale, v scale, v offset} per
	 * texture of the model, or null.
	 */
	public RenderPiece withFrames(float @Nullable [][] value) {
		return new RenderPiece(baked, pose, style, particles, overlay, color, anchors, replaces, skin, value);
	}

	public RenderPiece withSkin(@Nullable Identifier value) {
		return new RenderPiece(baked, pose, style, particles, overlay, color, anchors, replaces, value, frames);
	}
}
