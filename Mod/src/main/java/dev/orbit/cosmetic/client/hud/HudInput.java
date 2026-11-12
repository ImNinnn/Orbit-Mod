package dev.orbit.cosmetic.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What the CPS and mouse component shows: clicks in the last second on each mouse button, and a dot that follows how
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
		}
		// Back to the middle, and never out of its circle.
		float settle = (float) Math.exp(-dt * 8);
		dotX = Math.clamp(dotX * settle, -1, 1);
		dotY = Math.clamp(dotY * settle, -1, 1);
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
