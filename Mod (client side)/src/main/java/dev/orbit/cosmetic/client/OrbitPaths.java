package dev.orbit.cosmetic.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locations inside {@code <minecraft instance>/Orbit/}:
 * <ul>
 *   <li>{@code Orbit/Cosmetics/<name>/<name>.bbmodel} - one folder per cosmetic, with its settings file
 *       ({@code <name>.json}) next to the model; the folder name is the cosmetic's name ({@code cool_wings} shows as
 *       "cool wings")</li>
 *   <li>{@code Orbit/Emotes/<name>/<name>.bbmodel} - one folder per emote, with its settings file, like cosmetics</li>
 *   <li>{@code Orbit/Skins/*.png} - skins ({@code name_classic.png}, {@code name_slim.png})</li>
 *   <li>{@code Orbit/.cache/} - cosmetics and emotes of other players, named by hash</li>
 *   <li>{@code Orbit/.featured/} - items claimed from the featured catalog, kept unchanged (Cosmetics and Emotes, laid
 *       out like the player's own folders)</li>
 *   <li>{@code Orbit/orbit-state.json} - what is equipped, outfits and emote wheels</li>
 * </ul>
 */
public final class OrbitPaths {
	private final Path root;

	public OrbitPaths(Path gameDirectory) {
		this.root = gameDirectory.resolve("Orbit").toAbsolutePath().normalize();
	}

	public Path root() {
		return root;
	}

	public Path cosmetics() {
		return root.resolve("Cosmetics");
	}

	public Path emotes() {
		return root.resolve("Emotes");
	}

	/** Body parts: cosmetics that replace parts of the player. Same layout as {@link #cosmetics()}. */
	public Path bodyParts() {
		return root.resolve("Body Parts");
	}

	public Path skins() {
		return root.resolve("Skins");
	}

	/** Featured cosmetics the player claimed. They are not edited; "Make editable" moves one into {@link #cosmetics()}. */
	public Path featuredCosmetics() {
		return root.resolve(".featured").resolve("Cosmetics");
	}

	/** Featured body parts the player claimed. */
	public Path featuredBodyParts() {
		return root.resolve(".featured").resolve("Body Parts");
	}

	/** Featured emotes the player claimed. */
	public Path featuredEmotes() {
		return root.resolve(".featured").resolve("Emotes");
	}

	public Path cache() {
		return root.resolve(".cache");
	}

	public Path stateFile() {
		return root.resolve("orbit-state.json");
	}

	public void create() throws IOException {
		Files.createDirectories(cosmetics());
		Files.createDirectories(emotes());
		Files.createDirectories(skins());
		Files.createDirectories(cache());
		Files.createDirectories(featuredCosmetics());
		Files.createDirectories(featuredEmotes());
	}
}
