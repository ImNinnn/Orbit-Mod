package dev.orbit.cosmetic.client.anim;

import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.client.render.RenderPiece;
import dev.orbit.cosmetic.common.anim.AnimationPlayer;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.MotionInput;
import dev.orbit.cosmetic.common.anim.MotionTracker;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsState;
import dev.orbit.cosmetic.common.anim.PoseBuffer;
import dev.orbit.cosmetic.common.anim.RandomSchedule;
import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.emote.BodyPose;
import dev.orbit.cosmetic.common.emote.EmoteAnimation;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Everything Orbit tracks for one player: what they wear, how their animations and physics are configured, the
 * emote they play, and the motion state that drives them. Times are ticks of the
 * {@link dev.orbit.cosmetic.common.anim.AnimationClock}.
 */
public final class Wearer {
	/** An emote being played. Its file may still be downloading; it starts showing once it is available. */
	public static final class Emote {
		public final AssetId asset;
		public final boolean mobile;
		public final int seq;
		/** Its particles; a menu editing them changes them while it plays. */
		public List<ParticleSettings> particles;
		/** Clock time the emote was received, and how far into it the player already was (seconds). */
		final double receivedAt;
		final double age;
		/** Clock time the emote started; set once its file is available. */
		double startTime = Double.NaN;
		EmoteAnimation resolved;
		BakedCosmetic boundTo;
		PoseBuffer props;

		public Emote(AssetId asset, boolean mobile, int seq, List<ParticleSettings> particles, double receivedAt, double age) {
			this.asset = asset;
			this.mobile = mobile;
			this.seq = seq;
			this.particles = List.copyOf(particles);
			this.receivedAt = receivedAt;
			this.age = age;
		}

		/** The emote file as loaded, or null while it is not available yet. */
		public BakedCosmetic baked() {
			return boundTo;
		}

		/** Seconds since the emote started, or -1 while its file is not available yet. */
		public double elapsed(double time) {
			return Double.isNaN(startTime) ? -1 : (time - startTime) / 20.0;
		}

		/** True once an emote that plays once has ended. */
		public boolean isFinished(double time) {
			return resolved != null && resolved.isFinished(elapsed(time));
		}

		/** True once it ended, or (for one that loops) once it has looped for {@code loopSeconds}: for showing emotes in turn. */
		public boolean hasShown(double time, double loopSeconds) {
			return isFinished(time) || resolved != null && resolved.loops() && elapsed(time) > resolved.playSeconds() + loopSeconds;
		}
	}

	/** One worn cosmetic. Animation state survives configuration updates for the same asset. */
	public static final class Worn {
		final AssetId asset;
		List<AnimationSettings> settings;
		List<PhysicsLink> physicsLinks;
		int style;
		List<ParticleSettings> particles;
		/** For a body part, the parts it replaces (see EquippedCosmetic#replaces); 0 for other cosmetics. */
		int replaces;
		/** Its animated textures (see EquippedCosmetic#textures). */
		List<dev.orbit.cosmetic.common.anim.TextureAnimation> textures = List.of();
		AnimationPlayer player;
		final PhysicsState physics = new PhysicsState();
		BakedCosmetic boundTo;
		PoseBuffer pose;

		Worn(AssetId asset, List<AnimationSettings> settings, List<PhysicsLink> physicsLinks, int style, List<ParticleSettings> particles) {
			this.asset = asset;
			this.settings = settings;
			this.physicsLinks = physicsLinks;
			this.style = style;
			this.particles = particles;
		}

		public AssetId asset() {
			return asset;
		}

		public List<PhysicsLink> physicsLinks() {
			return physicsLinks;
		}

		public PhysicsState physics() {
			return physics;
		}
	}

	public final UUID id;
	public boolean showIcon;
	private final MotionTracker motion = new MotionTracker();
	private final MolangContext molang = new MolangContext();
	private List<Worn> worn = List.of();
	private double groundSpeed;
	private double forwardSpeed;
	private double verticalSpeed;
	private PhysicsState.Input movement = PhysicsState.Input.REST;
	/** For the DAMAGE and ATTACK triggers: the hurt time last tick, and when the player last swung and attacked. */
	private int lastHurtTime;
	private long lastSwing = Long.MIN_VALUE / 2;
	private long lastAttack = Long.MIN_VALUE / 2;
	private int clockGeneration;
	private Emote emote;
	/** The body pose of the emote for the last frame drawn (see {@link #pieces}). */
	private final BodyPose body = new BodyPose();
	/** This player's particles, which live in the world once they appear. */
	private final ParticleSystem particles = new ParticleSystem();

	public Wearer(UUID id) {
		this.id = id;
	}

	public List<Worn> worn() {
		return worn;
	}

	public Emote emote() {
		return emote;
	}

	public ParticleSystem particles() {
		return particles;
	}

	/** Plays an emote (replacing the current one), or stops with null. The same start (seq) is not restarted. */
	public void setEmote(Emote next) {
		if (next != null && emote != null && emote.asset.equals(next.asset) && emote.seq == next.seq) {
			return;
		}
		emote = next;
		body.reset();
	}

	/** The emote's offsets for the six body parts in the last frame drawn; empty without an emote. */
	public BodyPose body() {
		return body;
	}

	/** Replaces the worn list, keeping animation and physics state of assets that are still worn. */
	public void setWorn(List<EquippedCosmetic> cosmetics) {
		Map<AssetId, Worn> previous = new HashMap<>();
		for (Worn w : worn) {
			previous.put(w.asset, w);
		}
		List<Worn> updated = new ArrayList<>(cosmetics.size());
		for (EquippedCosmetic cosmetic : cosmetics) {
			Worn existing = previous.get(cosmetic.asset());
			if (existing == null) {
				existing = new Worn(cosmetic.asset(), cosmetic.animations(), cosmetic.physics(), cosmetic.style(), cosmetic.particles());
				existing.replaces = cosmetic.replaces();
				existing.textures = cosmetic.textures();
			} else {
				if (!existing.settings.equals(cosmetic.animations())) {
					existing.settings = cosmetic.animations();
					if (existing.player != null) {
						existing.player.setSettings(cosmetic.animations().toArray(new AnimationSettings[0]));
						existing.player.restart();
					}
				}
				existing.physicsLinks = cosmetic.physics();
				existing.style = cosmetic.style();
				existing.particles = cosmetic.particles();
				existing.replaces = cosmetic.replaces();
				existing.textures = cosmetic.textures();
			}
			updated.add(existing);
		}
		worn = List.copyOf(updated);
	}

	/**
	 * Advances motion state, animation triggers and physics by one game tick.
	 *
	 * @param time the animation clock's time, which follows the game time shared by every client
	 * @param generation the clock's generation; when it changes (new server or level), animations restart
	 */
	public void tick(Player player, double time, int generation, boolean localFlying, Function<AssetId, BakedCosmetic> lookup) {
		boolean newTimeline = generation != clockGeneration;
		clockGeneration = generation;
		double dx = player.getX() - player.xo;
		double dz = player.getZ() - player.zo;
		double dy = player.getY() - player.yo;
		groundSpeed = Math.sqrt(dx * dx + dz * dz);
		verticalSpeed = dy;
		movement = PhysicsState.Input.fromMovement(dx, dy, dz, player.yBodyRot, Mth.wrapDegrees(player.yBodyRot - player.yBodyRotO));
		boolean flying = localFlying;
		// The movement towards where the player looks: negative when walking backwards.
		double look = Math.toRadians(player.getYRot());
		forwardSpeed = -dx * Math.sin(look) + dz * Math.cos(look);
		motion.update(new MotionInput(
			player.onGround(),
			dy,
			groundSpeed,
			player.isSprinting(),
			player.isShiftKeyDown(),
			player.getPose() == Pose.CROUCHING,
			player.isVisuallySwimming(),
			player.isFallFlying(),
			flying,
			player.isInWater(),
			player.isPassenger(),
			forwardSpeed,
			tookDamage(player),
			attacked(player),
			MiningTracker.isMining(player.getId(), player.level().getGameTime())
		));
		for (Worn w : worn) {
			BakedCosmetic baked = lookup.apply(w.asset);
			if (baked == null) {
				continue;
			}
			if (w.boundTo != baked) {
				long seed = RandomSchedule.seed(id, w.asset.hex(), 0);
				// Body parts have no animations: they move with the player.
				w.player = baked.model().hasAnimations() && w.replaces == 0 ? new AnimationPlayer(baked.model().animations(), w.settings.toArray(new AnimationSettings[0]), seed) : null;
				w.pose = new PoseBuffer(baked.model().bones().size());
				w.boundTo = baked;
			}
			int bones = baked.model().bones().size();
			w.physics.setLinks(w.physicsLinks.stream().filter(link -> link.bone() < bones).toList());
			if (w.player != null) {
				if (newTimeline) {
					w.player.restart();
				}
				w.player.tick(time, motion);
			}
			w.physics.tick(movement);
		}
		tickEmote(time, newTimeline, lookup);
	}

	/** True on the tick the player gets hurt (every client sees it: the game shows every hurt player flash red). */
	private boolean tookDamage(Player player) {
		boolean hurt = player.hurtTime > lastHurtTime;
		lastHurtTime = player.hurtTime;
		return hurt;
	}

	/** Swings at most this many ticks before a creature or player near it is hurt count as hitting it. */
	private static final int ATTACK_WINDOW = 3;
	private static final double ATTACK_REACH = 6;

	/**
	 * True when the player hits something: it swung an arm and a creature or player within reach was just hurt.
	 * Swinging alone also places blocks or opens doors, and other players' attacks only reach this client as a
	 * swing and a hurt creature, so this is the same for everyone.
	 */
	private boolean attacked(Player player) {
		long now = player.level().getGameTime();
		if (dev.orbit.cosmetic.client.compat.Compat.isSwinging(player)) {
			lastSwing = now;
		}
		if (now - lastSwing > ATTACK_WINDOW || now - lastAttack <= ATTACK_WINDOW + 1) {
			return false;
		}
		boolean hit = !player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(ATTACK_REACH),
			entity -> entity != player && entity.hurtTime > 0 && entity.hurtTime >= entity.hurtDuration - 1).isEmpty();
		if (hit) {
			lastAttack = now;
		}
		return hit;
	}

	/**
	 * Advances animations for a player standing still, for previews in menus (where there is no player entity).
	 * Call once per game tick of {@code time}.
	 */
	public void tickStill(double time, Function<AssetId, BakedCosmetic> lookup) {
		tickMoving(time, PhysicsState.Input.REST, lookup);
	}

	/** Players run faster than this (blocks per second); walking is about 4.3. */
	private static final double SPRINT_SPEED = 5;

	/**
	 * Like {@link #tickStill}, for a preview player moving at a made-up speed (blocks per second, relative to its
	 * body), so its animations and movement physics react as if it really moved.
	 */
	public void tickMoving(double time, PhysicsState.Input moving, Function<AssetId, BakedCosmetic> lookup) {
		double horizontal = Math.hypot(moving.forward(), moving.sideways());
		groundSpeed = horizontal / 20;
		verticalSpeed = moving.vertical() / 20;
		forwardSpeed = moving.forward() / 20;
		movement = moving;
		motion.update(new MotionInput(moving.vertical() == 0, verticalSpeed, groundSpeed, horizontal > SPRINT_SPEED, false, false, false, false, false, false, false,
			forwardSpeed));
		for (Worn w : worn) {
			BakedCosmetic baked = lookup.apply(w.asset);
			if (baked == null) {
				continue;
			}
			if (w.boundTo != baked) {
				long seed = RandomSchedule.seed(id, w.asset.hex(), 0);
				// Body parts have no animations: they move with the player.
				w.player = baked.model().hasAnimations() && w.replaces == 0 ? new AnimationPlayer(baked.model().animations(), w.settings.toArray(new AnimationSettings[0]), seed) : null;
				w.pose = new PoseBuffer(baked.model().bones().size());
				w.boundTo = baked;
			}
			int bones = baked.model().bones().size();
			w.physics.setLinks(w.physicsLinks.stream().filter(link -> link.bone() < bones).toList());
			if (w.player != null) {
				w.player.tick(time, motion);
			}
			w.physics.tick(movement);
		}
		tickEmote(time, false, lookup);
	}

	/** Resolves the emote once its file is available, and ends it when it finished. Also used without a player (menus). */
	public void tickEmote(double time, boolean newTimeline, Function<AssetId, BakedCosmetic> lookup) {
		Emote current = emote;
		if (current == null) {
			return;
		}
		if (newTimeline) {
			// A new level and clock: the start time no longer means anything. Other players' emotes come back with
			// the next presence update.
			emote = null;
			body.reset();
			return;
		}
		BakedCosmetic baked = lookup.apply(current.asset);
		if (baked == null) {
			return;
		}
		if (current.boundTo != baked) {
			current.boundTo = baked;
			current.resolved = EmoteAnimation.of(baked.model());
			current.props = new PoseBuffer(baked.model().bones().size());
			if (current.resolved == null) {
				emote = null;
				return;
			}
			if (Double.isNaN(current.startTime)) {
				// Catch up with the player, but show most of a short emote even when it arrived late.
				double offset = current.resolved.loops() ? current.age : Math.max(0, Math.min(current.age, current.resolved.playSeconds() - 1.5));
				current.startTime = current.receivedAt - offset * 20;
			}
		}
		if (current.isFinished(time)) {
			emote = null;
			body.reset();
		}
	}

	/**
	 * Builds this frame's render pieces.
	 *
	 * @param state the player's render state, or null when drawing first-person arms
	 * @param time animation clock time for this frame
	 * @param partialTick progress from the last tick to the next one (0-1)
	 * @param animate false to draw the rest pose (animations disabled, or the player is far away)
	 * @param faceBudget maximum faces to draw for this player
	 */
	public List<RenderPiece> pieces(AvatarRenderState state, double time, float partialTick, boolean animate, int faceBudget, Function<AssetId, BakedCosmetic> lookup) {
		List<RenderPiece> pieces = new ArrayList<>(worn.size() + 1);
		int faces = 0;
		boolean contextReady = false;
		Emote current = emote;
		body.reset();
		if (current != null && current.resolved != null && current.boundTo != null && !current.boundTo.isReleased()) {
			double elapsed = current.elapsed(time);
			if (elapsed >= 0) {
				fillContext(state, time);
				contextReady = true;
				current.resolved.evaluate(elapsed, emoteMovement(), molang, current.props, body);
				// Its props (if it has any) and its particles.
				faces += current.boundTo.model().quadCount();
				pieces.add(new RenderPiece(current.boundTo, current.props, 0, current.particles)
					.withSkin(state == null || state.skin == null ? null : state.skin.body().texturePath()));
			}
		}
		// Body parts: when two replace the same part, the one put on last shows it. A part that breaks the rules
		// (empty, or too big) is never drawn, so the player's own part shows there.
		int[] bodyParts = new int[worn.size()];
		int claimed = 0;
		for (int i = worn.size() - 1; i >= 0; i--) {
			Worn w = worn.get(i);
			BakedCosmetic baked = w.replaces == 0 ? null : lookup.apply(w.asset);
			if (baked != null) {
				bodyParts[i] = w.replaces & baked.bodyParts() & ~claimed;
				claimed |= bodyParts[i];
			}
		}
		net.minecraft.resources.Identifier skin = state == null || state.skin == null ? null : state.skin.body().texturePath();
		for (int index = 0; index < worn.size(); index++) {
			Worn w = worn.get(index);
			BakedCosmetic baked = lookup.apply(w.asset);
			if (baked == null || w.replaces != 0 && bodyParts[index] == 0) {
				continue;
			}
			faces += baked.model().quadCount();
			if (faces > faceBudget) {
				break;
			}
			PoseBuffer pose = null;
			if (animate && w.boundTo == baked && (w.player != null || !w.physics.isEmpty())) {
				if (w.player != null) {
					if (!contextReady) {
						fillContext(state, time);
						contextReady = true;
					}
					w.player.setSmoothing(dev.orbit.cosmetic.client.OrbitClient.get().config().smoothAnimations);
					w.player.evaluate(time, molang, w.pose);
				} else {
					w.pose.reset();
				}
				w.physics.apply(partialTick, w.pose);
				pose = w.pose;
			}
			RenderPiece piece = new RenderPiece(baked, pose, w.style, w.particles).withSkin(skin)
				.withFrames(w.textures.isEmpty() ? null : dev.orbit.cosmetic.common.anim.TextureAnimation.transforms(baked.model().textures(), w.textures, time));
			pieces.add(w.replaces != 0 ? piece.replacing(bodyParts[index]) : piece);
		}
		return pieces;
	}

	/** How the player moves, for emotes with WALKING, SPRINTING and BACKWARDS animations (jumping keeps it). */
	private EmoteAnimation.Movement emoteMovement() {
		if (groundSpeed <= MotionTracker.MOVING_THRESHOLD) {
			return EmoteAnimation.Movement.STILL;
		}
		if (motion.last() != null && motion.last().sprinting()) {
			return EmoteAnimation.Movement.SPRINTING;
		}
		return forwardSpeed < -MotionTracker.BACKWARDS_SHARE * groundSpeed ? EmoteAnimation.Movement.BACKWARDS : EmoteAnimation.Movement.WALKING;
	}

	private void fillContext(AvatarRenderState state, double time) {
		molang.lifeTime = time / 20.0;
		molang.groundSpeed = groundSpeed * 20;
		molang.verticalSpeed = verticalSpeed * 20;
		molang.onGround = motion.last() == null || motion.last().onGround();
		molang.sprinting = motion.last() != null && motion.last().sprinting();
		if (state == null) {
			return;
		}
		molang.distanceMoved = state.walkAnimationPos;
		molang.moveSpeed = state.walkAnimationSpeed;
		molang.headXRotation = state.xRot;
		molang.headYRotation = state.yRot;
		molang.sneaking = state.isCrouching;
		molang.swimming = state.isVisuallySwimming;
		molang.flying = state.isFallFlying;
		molang.inWater = state.isInWater;
	}
}
