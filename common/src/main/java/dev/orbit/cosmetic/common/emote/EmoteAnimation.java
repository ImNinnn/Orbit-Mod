package dev.orbit.cosmetic.common.emote;

import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.BoneTrack;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.PoseBuffer;
import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An emote: one emote file, played on the player's body.
 *
 * <p>Emote files have this group tree, with the player model inside for reference:
 * <pre>
 * ROOT              moves the whole player
 * ├── LEFT_LEG
 * ├── RIGHT_LEG
 * └── UPPERHALF     bends everything but the legs, around the bottom of the body
 *     ├── BODY
 *     ├── LEFT_ARM
 *     ├── RIGHT_ARM
 *     └── HEAD
 * </pre>
 * Animating one of the six body part groups moves that part of the player. Any other geometry in the file (a prop
 * held in a hand, for example) is drawn like a cosmetic while the emote plays. Its animations are known by name
 * (Blockbench's own loop mode is ignored): {@code PLAY} plays once at the start; {@code LOOP} (or {@code IDLE}) then
 * loops. Emotes made for moving also have {@code WALKING}, {@code SPRINTING} (else WALKING plays while sprinting) and
 * {@code BACKWARDS}, which loop while the player moves that way; such emotes are always Mobile (see {@link #isMobile}).
 */
public final class EmoteAnimation {
	public static final String PLAY = "PLAY";
	public static final String LOOP = "LOOP";
	/** Another name for {@link #LOOP}. */
	public static final String IDLE = "IDLE";
	public static final String WALKING = "WALKING";
	public static final String SPRINTING = "SPRINTING";
	public static final String BACKWARDS = "BACKWARDS";
	private static final List<String> NAMES = List.of(PLAY, LOOP, IDLE, WALKING, SPRINTING, BACKWARDS);
	/** The group that moves the whole player, and the one that bends the upper half. */
	public static final String ROOT_GROUP = "ROOT";
	public static final String UPPER_GROUP = "UPPERHALF";
	/** The body parts an emote can move, in the order {@link BodyPose} stores them. */
	public static final Anchor[] PARTS = {Anchor.HEAD, Anchor.BODY, Anchor.RIGHT_ARM, Anchor.LEFT_ARM, Anchor.RIGHT_LEG, Anchor.LEFT_LEG};

	/** How the player moves, which picks the animation of a mobile emote. */
	public enum Movement {
		STILL,
		WALKING,
		SPRINTING,
		BACKWARDS
	}

	/** One animation of the emote, with the body slots it animates. */
	private record Part(AnimationClip clip, boolean[] slots) {
	}

	private final CosmeticModel model;
	/** Bone index of each part's group in the model (then UPPERHALF, then ROOT), or -1. */
	private final int[] slotBones = new int[BodyPose.ROOT + 1];
	private final Part play;
	private final Part loop;
	private final Part walking;
	private final Part sprinting;
	private final Part backwards;
	private final float[] upperPivot;
	private final float[] rootPivot;

	private EmoteAnimation(CosmeticModel model) {
		this.model = model;
		for (int part = 0; part < PARTS.length; part++) {
			slotBones[part] = partBone(model, PARTS[part]);
		}
		slotBones[BodyPose.UPPER] = namedBone(model, UPPER_GROUP);
		slotBones[BodyPose.ROOT] = namedBone(model, ROOT_GROUP);
		play = part(clip(model, PLAY));
		AnimationClip looping = clip(model, LOOP);
		loop = part(looping != null ? looping : clip(model, IDLE));
		walking = part(clip(model, WALKING));
		sprinting = part(clip(model, SPRINTING));
		backwards = part(clip(model, BACKWARDS));
		upperPivot = pivot(model, slotBones[BodyPose.UPPER], new float[] {0, 12, 0});
		rootPivot = pivot(model, slotBones[BodyPose.ROOT], new float[] {0, 24, 0});
	}

	private Part part(AnimationClip clip) {
		if (clip == null) {
			return null;
		}
		boolean[] slots = new boolean[slotBones.length];
		for (BoneTrack track : clip.tracks()) {
			for (int slot = 0; slot < slotBones.length; slot++) {
				if (slotBones[slot] == track.bone()) {
					slots[slot] = true;
				}
			}
		}
		return new Part(clip, slots);
	}

	/** The emote of a parsed emote file, or null when it is not one (see {@link #problem}). */
	public static EmoteAnimation of(CosmeticModel model) {
		return problem(model) == null ? new EmoteAnimation(model) : null;
	}

	/** What is wrong with a file's animations as an emote, or null when they are fine. */
	public static String problem(CosmeticModel model) {
		List<String> others = new ArrayList<>();
		for (AnimationClip clip : model.animations()) {
			if (!NAMES.contains(clip.name().toUpperCase(Locale.ROOT))) {
				others.add(clip.name());
			}
		}
		if (!others.isEmpty()) {
			return "Emote animations are named PLAY, LOOP (or IDLE), WALKING, SPRINTING and BACKWARDS; rename or remove " + String.join(", ", others);
		}
		if (clip(model, PLAY) == null && clip(model, LOOP) == null && clip(model, IDLE) == null) {
			return "An emote needs a PLAY animation (plays once), a LOOP animation (loops), or both";
		}
		return null;
	}

	private static AnimationClip clip(CosmeticModel model, String name) {
		for (AnimationClip clip : model.animations()) {
			if (clip.name().equalsIgnoreCase(name)) {
				return clip;
			}
		}
		return null;
	}

	private static int partBone(CosmeticModel model, Anchor anchor) {
		for (Bone bone : model.bones()) {
			if (bone.parent() < 0 && bone.anchor() == anchor && bone.name().equals(anchor.name())) {
				return bone.index();
			}
		}
		return -1;
	}

	private static int namedBone(CosmeticModel model, String name) {
		for (Bone bone : model.bones()) {
			if (bone.anchor() == Anchor.ROOT && bone.name().equalsIgnoreCase(name)) {
				return bone.index();
			}
		}
		return -1;
	}

	/** A bone's pivot in model space: the anchor's pivot (the neck) plus the pivots down to it. */
	private static float[] pivot(CosmeticModel model, int index, float[] fallback) {
		if (index < 0) {
			return fallback;
		}
		float[] result = new float[3];
		for (int bone = index; bone >= 0; bone = model.bones().get(bone).parent()) {
			Bone b = model.bones().get(bone);
			result[0] += b.pivotX();
			result[1] += b.pivotY();
			result[2] += b.pivotZ();
		}
		return result;
	}

	public CosmeticModel model() {
		return model;
	}

	/** True when the emote keeps going until stopped (it loops, or moves with the player). */
	public boolean loops() {
		return loop != null || isMobile();
	}

	/** True when it has animations for moving (WALKING, SPRINTING, BACKWARDS): it then keeps playing while the player moves. */
	public boolean isMobile() {
		return walking != null || sprinting != null || backwards != null;
	}

	/** Seconds the PLAY animation lasts (0 without one). */
	public double playSeconds() {
		return play == null ? 0 : play.clip().startDelay() + play.clip().length();
	}

	/** True when the emote moves at least one body part (or the whole player). */
	public boolean movesBody() {
		for (Part part : new Part[] {play, loop, walking, sprinting, backwards}) {
			if (part != null) {
				for (boolean slot : part.slots()) {
					if (slot) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** True once an emote that does not loop has played its PLAY animation to the end. */
	public boolean isFinished(double elapsedSeconds) {
		return !loops() && elapsedSeconds > playSeconds();
	}

	/** The looping animation for how the player moves: its own one when the emote has it, else LOOP (or nothing). */
	private Part looping(Movement movement) {
		Part moving = switch (movement) {
			case STILL -> null;
			case WALKING -> walking;
			case SPRINTING -> sprinting != null ? sprinting : walking;
			case BACKWARDS -> backwards != null ? backwards : walking;
		};
		return moving != null ? moving : loop;
	}

	/**
	 * Samples the emote {@code elapsedSeconds} after it started, for a player moving like {@code movement}.
	 *
	 * @param props receives the pose of the file's other bones, for drawing its props; the body part and ROOT groups
	 *              are left at rest there because the player carries their motion
	 * @param body receives the offsets of the body parts, the upper half and the whole player
	 */
	public void evaluate(double elapsedSeconds, Movement movement, MolangContext context, PoseBuffer props, BodyPose body) {
		props.reset();
		body.reset();
		double elapsed = Math.max(0, elapsedSeconds);
		Part part;
		double time;
		boolean looping;
		Part after = looping(movement);
		if (play != null && (elapsed <= playSeconds() || after == null)) {
			part = play;
			time = play.clip().clipTime(elapsed, LoopMode.ONCE);
			looping = false;
		} else if (after != null) {
			part = after;
			time = after.clip().clipTime(elapsed - playSeconds(), LoopMode.LOOP);
			looping = true;
		} else {
			return;
		}
		if (time < 0) {
			return;
		}
		context.resetVariables();
		part.clip().sample(time, looping, context, (bone, channel, x, y, z) -> props.apply(bone, channel, x, y, z, 0));
		System.arraycopy(upperPivot, 0, body.upperPivot, 0, 3);
		System.arraycopy(rootPivot, 0, body.rootPivot, 0, 3);
		for (int slot = 0; slot < slotBones.length; slot++) {
			int bone = slotBones[slot];
			if (bone < 0 || !part.slots()[slot]) {
				continue;
			}
			int o = bone * 3;
			int p = slot * 3;
			for (int axis = 0; axis < 3; axis++) {
				body.rotation[p + axis] = props.rotation[o + axis];
				body.position[p + axis] = props.position[o + axis];
				body.scale[p + axis] = props.scale[o + axis];
				if (slot != BodyPose.UPPER) {
					// The upper half's own pose stays for geometry placed directly in it; the player carries the rest.
					props.rotation[o + axis] = 0;
					props.position[o + axis] = 0;
					props.scale[o + axis] = 1;
				}
			}
			body.animated[slot] = true;
		}
	}
}
