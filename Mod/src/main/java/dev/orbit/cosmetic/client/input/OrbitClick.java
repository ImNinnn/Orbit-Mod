package dev.orbit.cosmetic.client.input;

//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;
*///?}

/**
 * A mouse button pressed, released or dragged in one of Orbit's screens or widgets, where the mouse is (in GUI
 * pixels). {@link #button()} is Minecraft's number for the button on the running version (compare it with
 * {@code InputConstants.MOUSE_BUTTON_...}). Since 1.21.9 Minecraft hands screens a mouse event; before, the
 * position and button as numbers (the modifier keys are read from the keyboard then).
 */
public final class OrbitClick {
	//? if >=1.21.9 {
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
	//?} else {
	/*private final double x;
	private final double y;
	private final int button;

	public OrbitClick(double x, double y, int button) {
		this.x = x;
		this.y = y;
		this.button = button;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public int button() {
		return button;
	}

	public boolean hasShiftDown() {
		return Screen.hasShiftDown();
	}

	public boolean hasControlDown() {
		return Screen.hasControlDown();
	}

	public boolean hasAltDown() {
		return Screen.hasAltDown();
	}

	// Double clicks: the same button again within a quarter of a second, as 1.21.9 and later tell screens themselves.
	// Worked out once per press (MouseHandlerMixin), since a press reaches a screen and its widgets.
	private static long lastPress;
	private static int lastButton = -1;
	private static boolean lastDouble;

	/^* Called by MouseHandlerMixin each time a mouse button goes down, before screens hear of it. ^/
	public static void pressed(int button) {
		long now = System.currentTimeMillis();
		lastDouble = button == lastButton && now - lastPress < 250;
		lastPress = now;
		lastButton = button;
	}

	/^* Whether the press being handled is the second of a double click. ^/
	public static boolean isDoubleClick() {
		return lastDouble;
	}
	*///?}
}
