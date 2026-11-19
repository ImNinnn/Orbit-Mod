package dev.orbit.cosmetic.client.input;

import net.minecraft.client.gui.components.events.GuiEventListener;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
//?} else {
/*import net.minecraft.util.StringUtil;
*///?}

/**
 * A character typed in one of Orbit's screens (for text the player writes). Since 1.21.9 Minecraft hands screens a
 * character event; before, the character and the modifier keys.
 */
public final class OrbitChar {
	//? if >=1.21.9 {
	private final CharacterEvent event;

	public OrbitChar(CharacterEvent event) {
		this.event = event;
	}

	/** The character, as a Unicode code point. */
	public int codepoint() {
		return event.codepoint();
	}

	/** Whether it may be typed in a chat message (not a control character). */
	public boolean isAllowedChatCharacter() {
		return event.isAllowedChatCharacter();
	}

	/** Types the character into a text box, or anything that takes typing (as if it had been typed there). */
	public boolean typeInto(GuiEventListener box) {
		return box.charTyped(event);
	}

	/** Minecraft's own event, for version-specific code. */
	public CharacterEvent raw() {
		return event;
	}
	//?} else {
	/*private final char character;
	private final int modifiers;

	public OrbitChar(char character, int modifiers) {
		this.character = character;
		this.modifiers = modifiers;
	}

	/^* The character, as a Unicode code point. ^/
	public int codepoint() {
		return character;
	}

	public char character() {
		return character;
	}

	public int modifiers() {
		return modifiers;
	}

	/^* Whether it may be typed in a chat message (not a control character). ^/
	public boolean isAllowedChatCharacter() {
		return StringUtil.isAllowedChatCharacter(character);
	}

	/^* Types the character into a text box, or anything that takes typing (as if it had been typed there). ^/
	public boolean typeInto(GuiEventListener box) {
		return box.charTyped(character, modifiers);
	}
	*///?}
}
