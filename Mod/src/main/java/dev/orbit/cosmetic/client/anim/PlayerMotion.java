package dev.orbit.cosmetic.client.anim;

/**
 * How a player being drawn moves and looks, for the Molang values of animations (see Wearer): the walk cycle
 * (distance and speed of the legs' swing), where the head looks (degrees), and what the player is doing.
 */
public record PlayerMotion(float distanceMoved, float moveSpeed, float headXRotation, float headYRotation, boolean sneaking, boolean swimming,
	boolean flying, boolean inWater) {
}
