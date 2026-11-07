package dev.orbit.cosmetic.client.net;

import com.google.gson.JsonElement;
import dev.orbit.cosmetic.client.library.TileColors;

/**
 * One item of the Orbit Cloud featured catalog: a cosmetic, an emote or a body part picked by the moderators.
 *
 * @param plus Feature+: shown four times as large in the catalog
 * @param hasSettings a cosmetic shipped with its settings file (animations, physics)
 * @param gradient the tile colours as Orbit Cloud sent them (see {@link TileColors}), or null
 */
public record CatalogItem(String id, Kind kind, String name, String sha256, int sizeBytes, String author, String description, int animations,
	boolean hasSettings, boolean plus, JsonElement gradient) {
	/** The tile's top and bottom colours, or null. */
	public int[] tileColors() {
		return TileColors.of(gradient);
	}

	public enum Kind {
		COSMETIC,
		EMOTE,
		/** Replaces parts of the player (see dev.orbit.cosmetic.common.model.BodyParts). */
		BODY_PART
	}

	public boolean isEmote() {
		return kind == Kind.EMOTE;
	}

	public boolean isBodyPart() {
		return kind == Kind.BODY_PART;
	}
}
