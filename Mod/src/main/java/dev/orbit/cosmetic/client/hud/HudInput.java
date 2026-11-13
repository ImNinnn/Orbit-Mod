package dev.orbit.cosmetic.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.compat.Compat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the all keys style of Keystrokes shows: every key and mouse button held in game (never while a screen is
 * open, so nothing typed in the chat or a text field shows), fading away for a moment when let go. And what the CPS
 * and mouse component shows: clicks in the last second on each mouse button, and a dot that follows how
 * the player turns (the mouse moves the camera) and settles back in the middle. Updated every frame.
 */
final class HudInput {
	private static final Deque<Long> LEFT = new ArrayDeque<>();
	private static final Deque<Long> RIGHT = new ArrayDeque<>();
	private static boolean leftWas;
	private static boolean rightWas;
	private static float dotX;
	private static float dotY;
	private static float lastYaw = Float.NaN;
	private static float lastPitch;
	private static long lastFrame;
	/** How the body turns lately (degrees, smoothed): the paperdoll swings a little with it. */
	private static float sway;
	private static float lastBody = Float.NaN;
	private static final long FADE_MS = 250;
	/** The keys there are (their codes differ between Minecraft versions). */
	private static final int[] KEYS = Compat.keyCodes();
	/** Keys held (0) or let go (when), in the order they were pressed: key codes, and -1 - button for mouse buttons. */
	private static final Map<Integer, Long> HELD = new LinkedHashMap<>();

	/** A key the all keys style shows: its name, and how visible (1 while held, less while fading). */
	record Held(String name, float alpha) {
	}

	private HudInput() {
	}

	static void frame() {
		Minecraft minecraft = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		boolean left = minecraft.options.keyAttack.isDown();
		boolean right = minecraft.options.keyUse.isDown();
		if (left && !leftWas) {
			LEFT.addLast(now);
		}
		if (right && !rightWas) {
			RIGHT.addLast(now);
		}
		leftWas = left;
		rightWas = right;
		while (!LEFT.isEmpty() && now - LEFT.peekFirst() > 1000) {
			LEFT.removeFirst();
		}
		while (!RIGHT.isEmpty() && now - RIGHT.peekFirst() > 1000) {
			RIGHT.removeFirst();
		}
		pollKeys(minecraft, now);
		float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000F);
		lastFrame = now;
		LocalPlayer player = minecraft.player;
		if (player != null) {
			float yaw = player.getYRot();
			float pitch = player.getXRot();
			if (!Float.isNaN(lastYaw)) {
				dotX += (yaw - lastYaw) * 0.08F;
				dotY += (pitch - lastPitch) * 0.08F;
			}
			lastYaw = yaw;
			lastPitch = pitch;
			float body = player.yBodyRot;
			if (!Float.isNaN(lastBody) && dt > 0) {
				float turn = net.minecraft.util.Mth.wrapDegrees(body - lastBody);
				// Degrees a second, as a swing of up to 25 degrees.
				sway = sway + (Math.clamp(turn / dt * 0.08F, -25, 25) - sway) * Math.min(1, dt * 6);
			}
			lastBody = body;
		}
		// Back to the middle, and never out of its circle.
		float settle = (float) Math.exp(-dt * 8);
		dotX = Math.clamp(dotX * settle, -1, 1);
		dotY = Math.clamp(dotY * settle, -1, 1);
	}

	private static void pollKeys(Minecraft minecraft, long now) {
		boolean playing = Compat.screen() == null;
		for (int key : KEYS) {
			update(key, playing && Compat.keyDown(key), now);
		}
		var mouse = minecraft.mouseHandler;
		update(-1 - InputConstants.MOUSE_BUTTON_LEFT, playing && mouse.isLeftPressed(), now);
		update(-1 - InputConstants.MOUSE_BUTTON_RIGHT, playing && mouse.isRightPressed(), now);
		update(-1 - InputConstants.MOUSE_BUTTON_MIDDLE, playing && mouse.isMiddlePressed(), now);
		for (Iterator<Long> it = HELD.values().iterator(); it.hasNext();) {
			long released = it.next();
			if (released != 0 && now - released > FADE_MS) {
				it.remove();
			}
		}
	}

	private static void update(int code, boolean down, long now) {
		Long state = HELD.get(code);
		if (down) {
			if (state == null || state != 0) {
				// Pressed (again): to the end of the line.
				HELD.remove(code);
				HELD.put(code, 0L);
			}
		} else if (state != null && state == 0) {
			HELD.put(code, now);
		}
	}

	static List<Held> heldKeys() {
		long now = System.currentTimeMillis();
		List<Held> keys = new ArrayList<>();
		HELD.forEach((code, released) -> {
			String name = code >= 0 ? Compat.keyName(code) : InputConstants.Type.MOUSE.getOrCreate(-1 - code).getDisplayName().getString();
			float alpha = released == 0 ? 1 : 1 - (now - released) / (float) FADE_MS;
			keys.add(new Held(name, Math.max(0, alpha)));
		});
		return keys;
	}

	static float sway() {
		return sway;
	}

	static int leftCps() {
		return LEFT.size();
	}

	static int rightCps() {
		return RIGHT.size();
	}

	static boolean leftDown() {
		return leftWas;
	}

	static boolean rightDown() {
		return rightWas;
	}

	static float dotX() {
		return dotX;
	}

	static float dotY() {
		return dotY;
	}
}
