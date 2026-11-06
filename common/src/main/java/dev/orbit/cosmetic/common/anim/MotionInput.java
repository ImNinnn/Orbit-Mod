package dev.orbit.cosmetic.common.anim;

/**
 * Per-tick player state used to derive animation triggers. Everything here is either synchronized by
 * vanilla (position, on-ground, sprint/sneak/swim/elytra flags, pose) or by Orbit ({@code abilityFlying}).
 *
 * @param deltaY vertical movement since the last tick, in blocks
 * @param horizontalSpeed horizontal movement since the last tick, in blocks
 * @param forwardSpeed the part of it towards where the player looks (negative when moving backwards), in blocks
 * @param hurt the player took damage this tick
 * @param attacked the player hit something this tick
 * @param mining the player is breaking a block
 */
public record MotionInput(
	boolean onGround,
	double deltaY,
	double horizontalSpeed,
	boolean sprinting,
	boolean shiftDown,
	boolean crouchPose,
	boolean swimming,
	boolean fallFlying,
	boolean abilityFlying,
	boolean inWater,
	boolean passenger,
	double forwardSpeed,
	boolean hurt,
	boolean attacked,
	boolean mining
) {
	/** Without damage, attacks or mining. */
	public MotionInput(boolean onGround, double deltaY, double horizontalSpeed, boolean sprinting, boolean shiftDown, boolean crouchPose, boolean swimming,
		boolean fallFlying, boolean abilityFlying, boolean inWater, boolean passenger, double forwardSpeed) {
		this(onGround, deltaY, horizontalSpeed, sprinting, shiftDown, crouchPose, swimming, fallFlying, abilityFlying, inWater, passenger, forwardSpeed, false, false,
			false);
	}

	/** Movement straight ahead (no backwards information). */
	public MotionInput(boolean onGround, double deltaY, double horizontalSpeed, boolean sprinting, boolean shiftDown, boolean crouchPose, boolean swimming,
		boolean fallFlying, boolean abilityFlying, boolean inWater, boolean passenger) {
		this(onGround, deltaY, horizontalSpeed, sprinting, shiftDown, crouchPose, swimming, fallFlying, abilityFlying, inWater, passenger, horizontalSpeed);
	}
}
