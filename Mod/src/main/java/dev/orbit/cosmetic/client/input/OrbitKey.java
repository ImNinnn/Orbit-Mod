package dev.orbit.cosmetic.client.input;

import dev.orbit.cosmetic.client.compat.Compat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

/**
 * A key pressed or released in one of Orbit's screens or widgets. {@link #key()} is Minecraft's code for the key on
 * the running version (compare it with {@code InputConstants.KEY_...}, which follow the version too).
 */
public final class OrbitKey {
	private final KeyEvent event;

	public OrbitKey(KeyEvent event) {
		this.event = event;
	}

	public int key() {
		return event.key();
	}

	public int modifiers() {
		return event.modifiers();
	}

	public boolean hasShiftDown() {
		return event.hasShiftDown();
	}

	public boolean hasControlDown() {
		return event.hasControlDown();
	}

	public boolean hasAltDown() {
		return event.hasAltDown();
	}

	/** Ctrl, or Cmd on macOS (the shortcut key: Ctrl+F, Ctrl+Z...). */
	public boolean hasControlDownWithQuirk() {
		return event.hasControlDownWithQuirk();
	}

	/** Whether it is the key bound to this key mapping (Orbit's or the game's). */
	public boolean matches(KeyMapping mapping) {
		return mapping.matches(event);
	}

	/** Whether it is the key that types this letter on the player's keyboard layout ('z' on AZERTY and QWERTY alike). */
	public boolean isLetter(char letter) {
		return Compat.isLetter(event, letter);
	}

	/** Minecraft's own event, for version-specific code. */
	public KeyEvent raw() {
		return event;
	}
}
