package dev.orbit.cosmetic.client.input;

import dev.orbit.cosmetic.client.compat.Compat;
import net.minecraft.client.KeyMapping;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
//?} else {
/*import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
*///?}

/**
 * A key pressed or released in one of Orbit's screens or widgets. {@link #key()} is Minecraft's code for the key on
 * the running version (compare it with {@code InputConstants.KEY_...}, which follow the version too). Since 1.21.9
 * Minecraft hands screens a key event; before, the key, its scan code and the modifier keys as numbers.
 */
public final class OrbitKey {
	//? if >=1.21.9 {
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
	//?} else {
	/*private final int key;
	private final int scanCode;
	private final int modifiers;

	public OrbitKey(int key, int scanCode, int modifiers) {
		this.key = key;
		this.scanCode = scanCode;
		this.modifiers = modifiers;
	}

	public int key() {
		return key;
	}

	public int scanCode() {
		return scanCode;
	}

	public int modifiers() {
		return modifiers;
	}

	public boolean hasShiftDown() {
		return (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
	}

	public boolean hasControlDown() {
		return (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
	}

	public boolean hasAltDown() {
		return (modifiers & GLFW.GLFW_MOD_ALT) != 0;
	}

	/^* Ctrl, or Cmd on macOS (the shortcut key: Ctrl+F, Ctrl+Z...). ^/
	public boolean hasControlDownWithQuirk() {
		return (modifiers & (Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL)) != 0;
	}

	/^* Whether it is the key bound to this key mapping (Orbit's or the game's). ^/
	public boolean matches(KeyMapping mapping) {
		return mapping.matches(key, scanCode);
	}

	/^* Whether it is the key that types this letter on the player's keyboard layout ('z' on AZERTY and QWERTY alike). ^/
	public boolean isLetter(char letter) {
		return Compat.isLetter(key, letter);
	}
	*///?}
}
