package dev.orbit.cosmetic.client.input;

import net.minecraft.client.input.MouseButtonEvent;

/**
 * A mouse button pressed, released or dragged in one of Orbit's screens or widgets, where the mouse is (in GUI
 * pixels). {@link #button()} is Minecraft's number for the button on the running version (compare it with
 * {@code InputConstants.MOUSE_BUTTON_...}).
 */
public final class OrbitClick {
	private final MouseButtonEvent event;

	public OrbitClick(MouseButtonEvent event) {
		this.event = event;
	}

	public double x() {
		return event.x();
	}

	public double y() {
		return event.y();
	}

	public int button() {
		return event.button();
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

	/** Minecraft's own event, for version-specific code. */
	public MouseButtonEvent raw() {
		return event;
	}
}
