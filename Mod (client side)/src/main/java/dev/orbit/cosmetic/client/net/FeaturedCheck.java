package dev.orbit.cosmetic.client.net;

import dev.orbit.cosmetic.common.protocol.AssetId;

import java.util.Locale;

/**
 * Whether a claimed featured cosmetic or emote is still what the featured catalog has. A claimed copy is only
 * featured while Orbit Cloud features that exact file: once it is unfeatured, or the copy no longer matches (changed
 * on disk, or the catalog has a newer version under the same name), it counts as one of the player's own items.
 * Orbit Cloud applies the same rule itself, so a copy that is not in the catalog is never shared during a feature ban.
 *
 * @param item the catalog item it was claimed from (by file, or else by name); null when there is none
 */
public record FeaturedCheck(State state, CatalogItem item) {
	public enum State {
		/** In the catalog with exactly this file. */
		CURRENT,
		/** The catalog has an item of this name with another file: the copy can be updated. */
		OUTDATED,
		/** Not in the featured catalog any more. */
		UNFEATURED,
		/** The catalog is not loaded (or not completely): nothing is known yet. */
		UNKNOWN
	}

	private static final FeaturedCheck UNKNOWN = new FeaturedCheck(State.UNKNOWN, null);
	private static final FeaturedCheck UNFEATURED = new FeaturedCheck(State.UNFEATURED, null);

	/** Still counts as featured (or nothing says otherwise yet). */
	public boolean featured() {
		return state == State.CURRENT || state == State.UNKNOWN;
	}

	/**
	 * @param asset the claimed copy's file as it is on disk, or null when it cannot be read
	 * @param name its name: the catalog name it was claimed under
	 */
	public static FeaturedCheck of(Catalog catalog, AssetId asset, String name, boolean emote) {
		if (asset != null) {
			for (CatalogItem item : catalog.items()) {
				if (item.isEmote() == emote && item.sha256().equals(asset.hex())) {
					return new FeaturedCheck(State.CURRENT, item);
				}
			}
		}
		if (!catalog.complete()) {
			return UNKNOWN;
		}
		String wanted = normalize(name);
		for (CatalogItem item : catalog.items()) {
			if (item.isEmote() == emote && normalize(item.name()).equals(wanted)) {
				return new FeaturedCheck(State.OUTDATED, item);
			}
		}
		return UNFEATURED;
	}

	/** Names compare without case, and with underscores as spaces (the claimed copy's folder is "Cool_Wings"). */
	private static String normalize(String name) {
		return name == null ? "" : name.replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}
}
