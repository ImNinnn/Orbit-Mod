package dev.orbit.cosmetic.common.emote;

/** How an emote reacts to the player moving. */
public enum EmoteMode {
	/** Stops as soon as the player moves (the default). */
	STANDARD,
	/** Keeps playing while the player walks or runs: for walking and running animations. */
	MOBILE;

	public static EmoteMode byName(String name) {
		return "mobile".equalsIgnoreCase(name) ? MOBILE : STANDARD;
	}
}
