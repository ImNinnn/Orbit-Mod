package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;

import java.util.List;

/**
 * Plays the animations of one cosmetic on one wearer. {@link #tick} advances trigger state once per game tick;
 * {@link #evaluate} can be called every frame with a partial tick time.
 *
 * <p>Times are ticks of the shared game time (smoothed by {@link AnimationClock}), so
 * {@link AnimationTrigger#RANDOM} animations, which are scheduled from it, start at the same moment on every
 * client.
 */
public final class AnimationPlayer {
	private final List<AnimationClip> clips;
	private final long seed;
	private AnimationSettings[] settings;
	/** Whether an animation is assigned to SPRINT; without one, WALK animations also play while sprinting. */
	private boolean hasSprint;
	private RandomSchedule[] schedules;
	private final double[] startTick;
	private final boolean[] playing;
	private double lastTime = Double.NaN;

	/** Ticks over which the pose fades from one set of animations to the next (see {@link #setSmoothing}). */
	public static final double BLEND_TICKS = 5;
	private boolean smoothing;
	/** The animations that made the last pose, the last pose itself, the pose a fade starts from, and when it started. */
	private final boolean[] shown;
	private PoseBuffer lastPose;
	private PoseBuffer fadeFrom;
	private double fadeStart = Double.NaN;
	private double lastEvaluated = Double.NaN;

	/**
	 * @param seed base seed for random schedules; use {@link RandomSchedule#seed} with index 0 so every
	 *             client derives the same value
	 */
	public AnimationPlayer(List<AnimationClip> clips, AnimationSettings[] settings, long seed) {
		this.clips = clips;
		this.seed = seed;
		this.startTick = new double[clips.size()];
		this.playing = new boolean[clips.size()];
		this.shown = new boolean[clips.size()];
		setSettings(settings);
	}

	public AnimationPlayer(List<AnimationClip> clips, AnimationSettings[] settings) {
		this(clips, settings, 0);
	}

	/** Replaces the settings; missing entries fall back to {@link AnimationSettings#defaultsFor}. */
	public void setSettings(AnimationSettings[] newSettings) {
		AnimationSettings[] resolved = new AnimationSettings[clips.size()];
		for (int i = 0; i < resolved.length; i++) {
			resolved[i] = newSettings != null && i < newSettings.length && newSettings[i] != null
				? newSettings[i]
				: AnimationSettings.defaultsFor(clips.get(i));
		}
		this.settings = resolved;
		this.hasSprint = java.util.Arrays.stream(resolved).anyMatch(s -> s.trigger() == AnimationTrigger.SPRINT);
		this.schedules = new RandomSchedule[resolved.length];
		for (int i = 0; i < resolved.length; i++) {
			AnimationSettings s = resolved[i];
			if (s.trigger() == AnimationTrigger.RANDOM) {
				double playTicks = clips.get(i).length() / s.speed() * 20.0;
				schedules[i] = new RandomSchedule(seed + i * 0x9E3779B97F4A7C15L, playTicks, s.minWait() * 20.0, s.maxWait() * 20.0);
			}
		}
	}

	/**
	 * Smoothing: when an animation starts or ends while others play (one chained after another), the pose fades
	 * from the old animations into the new ones over {@link #BLEND_TICKS} instead of jumping, so parts do not snap
	 * or clip through each other.
	 */
	public void setSmoothing(boolean value) {
		smoothing = value;
	}

	public AnimationSettings[] settings() {
		return settings;
	}

	public void tick(double tick, MotionTracker motion) {
		if (tick < lastTime) {
			// The timeline moved back (new level or server): start times from the old one would lie in the future.
			restart();
		}
		lastTime = tick;
		int active = motion.active();
		int started = motion.started();
		if (!hasSprint && (active & AnimationTrigger.SPRINT.bit()) != 0) {
			// No sprint animation: the walk one keeps going (or starts) while sprinting.
			active |= AnimationTrigger.WALK.bit();
		}
		for (int i = 0; i < clips.size(); i++) {
			AnimationTrigger trigger = settings[i].trigger();
			if (trigger == AnimationTrigger.NONE) {
				playing[i] = false;
				continue;
			}
			if (trigger == AnimationTrigger.RANDOM) {
				double start = schedules[i].activeStart(tick);
				playing[i] = !Double.isNaN(start);
				if (playing[i]) {
					startTick[i] = start;
				}
				continue;
			}
			if (trigger.isEvent()) {
				// Each moment starts the animation again; it then plays to its end.
				if ((started & trigger.bit()) != 0) {
					playing[i] = true;
					startTick[i] = tick;
				}
				continue;
			}
			boolean condition = (active & trigger.bit()) != 0;
			if (!condition) {
				playing[i] = false;
			} else if ((started & trigger.bit()) != 0 || !playing[i]) {
				playing[i] = true;
				startTick[i] = tick;
			}
		}
	}

	/** Restarts every animation, e.g. after the settings changed. */
	public void restart() {
		java.util.Arrays.fill(playing, false);
	}

	/**
	 * Writes the blended pose at {@code time} (ticks, including the partial tick) into {@code out}.
	 * {@code context} must already hold the wearer's queries; {@code animTime} is set per clip.
	 */
	public void evaluate(double time, MolangContext context, PoseBuffer out) {
		out.reset();
		int interruptPriority = Integer.MIN_VALUE;
		for (int i = 0; i < clips.size(); i++) {
			if (isRunning(i, time) && settings[i].interrupt()) {
				interruptPriority = Math.max(interruptPriority, settings[i].priority());
			}
		}
		boolean changed = false;
		for (int i = 0; i < clips.size(); i++) {
			boolean show = isRunning(i, time) && settings[i].priority() >= interruptPriority;
			changed |= show != shown[i];
			shown[i] = show;
		}
		for (int i = 0; i < clips.size(); i++) {
			if (!shown[i]) {
				continue;
			}
			AnimationSettings s = settings[i];
			AnimationClip clip = clips.get(i);
			LoopMode loop = loopMode(i);
			double clipTime = clip.clipTime(elapsedSeconds(i, time), loop);
			int priority = s.priority();
			context.resetVariables();
			clip.sample(clipTime, loop == LoopMode.LOOP, context, (bone, channel, x, y, z) -> out.apply(bone, channel, x, y, z, priority));
		}
		smooth(time, changed, out);
	}

	/** Fades into a new set of animations from the pose shown just before it (see {@link #setSmoothing}). */
	private void smooth(double time, boolean changed, PoseBuffer out) {
		if (!smoothing || lastPose != null && lastPose.boneCount() != out.boneCount()) {
			lastPose = null;
			fadeStart = Double.NaN;
			return;
		}
		if (lastPose == null) {
			lastPose = new PoseBuffer(out.boneCount());
			fadeFrom = new PoseBuffer(out.boneCount());
			lastPose.copyFrom(out);
			lastEvaluated = time;
			return;
		}
		if (time < lastEvaluated) {
			// The timeline moved back: nothing to fade from.
			fadeStart = Double.NaN;
		} else if (changed) {
			// Starts from what was on screen, even in the middle of another fade.
			fadeFrom.copyFrom(lastPose);
			fadeStart = time;
		}
		lastEvaluated = time;
		if (!Double.isNaN(fadeStart) && time - fadeStart < BLEND_TICKS) {
			double t = (time - fadeStart) / BLEND_TICKS;
			// Ease in and out.
			out.blendFrom(fadeFrom, (float) (t * t * (3 - 2 * t)));
		} else {
			fadeStart = Double.NaN;
		}
		lastPose.copyFrom(out);
	}

	private boolean isRunning(int index, double time) {
		return playing[index] && !clips.get(index).isFinished(elapsedSeconds(index, time), loopMode(index));
	}

	/** Random animations and moments (damage, attacks) always play once per occurrence. */
	private LoopMode loopMode(int index) {
		AnimationTrigger trigger = settings[index].trigger();
		return trigger == AnimationTrigger.RANDOM || trigger.isEvent() ? LoopMode.ONCE : settings[index].loop();
	}

	private double elapsedSeconds(int index, double time) {
		return Math.max(0, time - startTick[index]) / 20.0 * settings[index].speed();
	}
}
