package dev.orbit.cosmetic.client.net;

import java.util.List;

/**
 * The Orbit Cloud featured catalog: featured cosmetics and emotes (newest first) and bundles.
 *
 * @param complete every featured item was read (so an item missing from it is not featured)
 */
public record Catalog(List<CatalogItem> items, List<Bundle> bundles, boolean complete) {
	/** Before the catalog is loaded. */
	public static final Catalog EMPTY = new Catalog(List.of(), List.of(), false);

	/** Several items claimed together, such as a full outfit and an emote. */
	public record Bundle(String id, String name, List<CatalogItem> items) {
	}

	public boolean contains(String sha256) {
		return items.stream().anyMatch(item -> item.sha256().equals(sha256))
			|| bundles.stream().anyMatch(bundle -> bundle.items().stream().anyMatch(item -> item.sha256().equals(sha256)));
	}
}
