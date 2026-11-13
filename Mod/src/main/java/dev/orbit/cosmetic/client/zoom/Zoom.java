package dev.orbit.cosmetic.client.zoom;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Zoom: while its key is held, the field of view is divided by 3; scrolling zooms further in or out. The view
 * glides between levels (quickly, so it feels snappy), the camera moves smoothly (Minecraft's cinematic camera)
 * and the hand is hidden. Everything goes back to how it was when the key is released.
 */
public final class Zoom {
	private static final double START = 3;
	private static final double MIN = 1.25;
	private static final double MAX = 60;
	/** How much one notch of the scroll wheel zooms. */
	private static final double PER_NOTCH = 1.2;
	/** How fast the view catches up with the zoom level: the share of the gap left after a second. */
	private static final double CATCH_UP = 1.0E-7;

	private static KeyMapping key;
	private static boolean zooming;
	/** The zoom wanted, and the one shown this frame (both divide the field of view). */
	private static double target = 1;
	private static double current = 1;
	private static long lastFrame;
	/** The cinematic camera setting before zooming, put back after. */
	private static boolean smoothCameraBefore;

	private Zoom() {
	}

	public static void setKey(KeyMapping value) {
		key = value;
	}

	/** Once per frame, before the view is set up: follows the key. */
	public static void update() {
		Minecraft minecraft = Minecraft.getInstance();
		boolean held = key != null && key.isDown() && minecraft.player != null && dev.orbit.cosmetic.client.compat.Compat.screen() == null;
		if (held && !zooming) {
			zooming = true;
			target = START;
			smoothCameraBefore = minecraft.options.smoothCamera;
			minecraft.options.smoothCamera = true;
		} else if (!held && zooming) {
			zooming = false;
			target = 1;
			minecraft.options.smoothCamera = smoothCameraBefore;
		}
		long now = System.nanoTime();
		double seconds = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1.0E9);
		lastFrame = now;
		// Eases in logarithmic space, so zooming from 3x to 30x feels as quick as from 1x to 3x.
		double gap = Math.log(target) - Math.log(current);
		current = Math.exp(Math.log(current) + gap * (1 - Math.pow(CATCH_UP, seconds)));
		if (Math.abs(Math.log(target) - Math.log(current)) < 0.001) {
			current = target;
		}
	}

	public static boolean isZooming() {
		return zooming;
	}

	/** The field of view with the zoom applied. */
	/** The field of view the camera last used (vertical, in degrees, zoom included): the long compass shows it. */
	public static volatile float lastFov = 70;

	public static float apply(float fov) {
		return current == 1 ? fov : (float) (fov / current);
	}

	/** Scrolling while zooming: up zooms in, down zooms out. Returns true when the scroll was used. */
	public static boolean scroll(double amount) {
		if (!zooming || amount == 0) {
			return false;
		}
		target = Math.clamp(target * Math.pow(PER_NOTCH, Math.signum(amount) * Math.min(3, Math.abs(amount))), MIN, MAX);
		return true;
	}
}
