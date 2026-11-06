package dev.orbit.cosmetic.common.anim;

/**
 * Turns successive {@link MotionInput}s into the set of active {@link AnimationTrigger}s for one player.
 * Pure and deterministic so it can be unit tested and behaves the same on every client.
 */
public final class MotionTracker {
	/** Horizontal movement per tick above which the player counts as moving (about 0.3 blocks/s). */
	public static final double MOVING_THRESHOLD = 0.015;
	/** Downward movement per tick above which an airborne player counts as falling. */
	public static final double FALLING_THRESHOLD = -0.1;
	/** How much of the movement must point backwards to count as walking backwards (not sideways). */
	public static final double BACKWARDS_SHARE = 0.4;

	private boolean wasOnGround = true;
	private boolean jumping;
	private int active;
	private int started;
	private MotionInput last;

	/** Advances one tick. */
	public void update(MotionInput input) {
		boolean flying = input.fallFlying() || input.abilityFlying();
		boolean swimming = input.swimming();
		boolean moving = input.horizontalSpeed() > MOVING_THRESHOLD;
		boolean airborneFree = !input.onGround() && !flying && !swimming && !input.passenger();

		int mask = AnimationTrigger.ALWAYS.bit();
		if (swimming) {
			mask |= AnimationTrigger.SWIM.bit();
		}
		if (flying) {
			mask |= AnimationTrigger.FLY.bit();
		}
		if (input.shiftDown() && !flying && !swimming) {
			mask |= AnimationTrigger.SNEAK.bit();
		}
		if (input.crouchPose()) {
			mask |= AnimationTrigger.CROUCH.bit();
		}
		if (input.sprinting() && moving && !flying && !swimming && !input.passenger()) {
			mask |= AnimationTrigger.SPRINT.bit();
		}
		if (moving && input.onGround() && !input.sprinting() && !input.passenger()) {
			mask |= AnimationTrigger.WALK.bit();
			// Walking, mostly away from where the player looks.
			if (input.forwardSpeed() < -BACKWARDS_SHARE * input.horizontalSpeed()) {
				mask |= AnimationTrigger.BACKWARDS.bit();
			}
		}
		if (input.passenger() || input.onGround() && !moving && !swimming && !flying) {
			mask |= AnimationTrigger.IDLE.bit();
		}

		int startedMask = 0;
		if (wasOnGround && airborneFree && input.deltaY() > 0) {
			jumping = true;
			startedMask |= AnimationTrigger.JUMP.bit();
		}
		if (input.onGround() || flying || swimming || input.passenger()) {
			jumping = false;
		}
		if (jumping) {
			mask |= AnimationTrigger.JUMP.bit();
		}
		if (airborneFree && input.deltaY() < FALLING_THRESHOLD && !input.inWater()) {
			mask |= AnimationTrigger.FALL.bit();
		}
		if (input.mining()) {
			mask |= AnimationTrigger.MINE.bit();
		}
		// Moments: active for this tick only; their animations play to the end (see AnimationTrigger.isEvent).
		if (input.hurt()) {
			mask |= AnimationTrigger.DAMAGE.bit();
			startedMask |= AnimationTrigger.DAMAGE.bit();
		}
		if (input.attacked()) {
			mask |= AnimationTrigger.ATTACK.bit();
			startedMask |= AnimationTrigger.ATTACK.bit();
		}

		// Continuous states start when they become active; JUMP starts only on the jump itself.
		startedMask |= mask & ~active & ~AnimationTrigger.JUMP.bit();
		started = startedMask;
		active = mask;
		wasOnGround = input.onGround();
		last = input;
	}

	/** Bit mask of {@link AnimationTrigger#bit()} for the triggers active this tick. */
	public int active() {
		return active;
	}

	/** Bit mask of triggers that became active this tick. */
	public int started() {
		return started;
	}

	public boolean isActive(AnimationTrigger trigger) {
		return (active & trigger.bit()) != 0;
	}

	public MotionInput last() {
		return last;
	}
}
