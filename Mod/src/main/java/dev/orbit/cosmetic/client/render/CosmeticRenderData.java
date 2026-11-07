package dev.orbit.cosmetic.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

import java.util.List;

/** Attached to a player's render state during extraction and consumed by {@link CosmeticLayer}. */
public record CosmeticRenderData(List<RenderPiece> pieces) {
	public static final RenderStateDataKey<CosmeticRenderData> KEY = RenderStateDataKey.create(() -> "orbit_cosmetic:cosmetics");

	/** The vanilla parts body parts replace (bits of Anchor#ordinal()), which the player model hides. */
	public int hiddenParts() {
		int parts = 0;
		for (RenderPiece piece : pieces) {
			parts |= piece.hiddenParts();
		}
		return parts;
	}
}
