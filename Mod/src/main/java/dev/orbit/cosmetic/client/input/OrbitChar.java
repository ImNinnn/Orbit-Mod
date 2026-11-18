package dev.orbit.cosmetic.client.input;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;

/** A character typed in one of Orbit's screens (for text the player writes). */
public final class OrbitChar {
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
}
