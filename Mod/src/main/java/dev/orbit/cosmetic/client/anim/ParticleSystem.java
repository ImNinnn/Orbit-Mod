package dev.orbit.cosmetic.client.anim;

import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One player's particles. They appear at their emitters and then live on their own, in the world (or, for players
 * in menus, in the preview's own space): leaving them behind is what makes a trail. Every client simulates them
 * itself from the shared {@link ParticleSettings}. At most {@link ParticleSettings#MAX_PER_PLAYER} at once.
 * Main thread only; times are real seconds.
 */
public final class ParticleSystem {
	/** One particle. Positions are in the system's space, in blocks. */
	public static final class Particle {
		public double x;
		public double y;
		public double z;
		/** The particle's own frame: the player's when it appeared, turned since. */
		final Quaternionf heading;
		float speedX;
		float speedY;
		float speedZ;
		float turnX;
		float turnY;
		float turnZ;
		final ParticleSettings settings;
		/** Rotation on itself in radians, how fast that turns (radians per second), and age in seconds. */
		public float spin;
		final float spinSpeed;
		float age;
		public final Identifier texture;
		/** The cosmetic or emote whose texture it shows; once that is released, the particle is not drawn. */
		public final BakedCosmetic source;
		public final int light;

		Particle(double x, double y, double z, Quaternionf heading, ParticleSettings settings, float spin, float spinSpeed, BakedCosmetic source,
			Identifier texture, int light) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.heading = heading;
			this.settings = settings;
			this.spin = spin;
			this.spinSpeed = spinSpeed;
			this.texture = texture;
			this.source = source;
			this.light = light;
			speedX = settings.speedX();
			speedY = settings.speedY();
			speedZ = settings.speedZ();
			turnX = (float) Math.toRadians(settings.turnX());
			turnY = (float) Math.toRadians(settings.turnY());
			turnZ = (float) Math.toRadians(settings.turnZ());
		}

		/** Size in blocks: from the start size to the end size over its life. */
		public float size() {
			float t = Math.min(1, age / settings.lifetime());
			return (settings.sizeStart() + (settings.sizeEnd() - settings.sizeStart()) * t) / 16;
		}

		/** 0 to 1: the settings' opacity, faded out over the end of its life. */
		public float alpha() {
			float left = settings.lifetime() - age;
			float fade = settings.fadeOut() > 0 && left < settings.fadeOut() ? Math.max(0, left / settings.fadeOut()) : 1;
			return settings.opacity() * fade;
		}

		public boolean glow() {
			return settings.glow();
		}
	}

	private final List<Particle> particles = new ArrayList<>();
	/** When each emitter may spawn next, by emitter key. */
	private final Map<String, Double> nextSpawn = new HashMap<>();
	/** Where each emitter was last seen, relative to the player and their facing (blocks), for first person. */
	private final Map<String, Vector3f> lastOffset = new HashMap<>();
	private final Random random = new Random();
	private double lastUpdate = Double.NaN;
	private int lastLight = 0xF000F0;

	private static double now() {
		return System.nanoTime() / 1.0e9;
	}

	public List<Particle> particles() {
		return particles;
	}

	public boolean isEmpty() {
		return particles.isEmpty();
	}

	public void clear() {
		particles.clear();
		nextSpawn.clear();
	}

	/** Moves every particle to now and removes the ones whose life is over. */
	public void update() {
		double now = now();
		float dt = Double.isNaN(lastUpdate) ? 0 : (float) Math.min(0.1, Math.max(0, now - lastUpdate));
		lastUpdate = now;
		if (dt <= 0) {
			return;
		}
		Vector3f velocity = new Vector3f();
		Quaternionf turn = new Quaternionf();
		Iterator<Particle> it = particles.iterator();
		while (it.hasNext()) {
			Particle p = it.next();
			p.age += dt;
			if (p.age >= p.settings.lifetime()) {
				it.remove();
				continue;
			}
			ParticleSettings s = p.settings;
			p.speedX += s.speedChangeX() * dt;
			p.speedY += s.speedChangeY() * dt;
			p.speedZ += s.speedChangeZ() * dt;
			p.turnX += (float) Math.toRadians(s.turnChangeX()) * dt;
			p.turnY += (float) Math.toRadians(s.turnChangeY()) * dt;
			p.turnZ += (float) Math.toRadians(s.turnChangeZ()) * dt;
			// Like a car: turn the particle's own frame, then move along it.
			p.heading.mul(turn.rotationXYZ(p.turnX * dt, p.turnY * dt, p.turnZ * dt));
			p.heading.transform(velocity.set(p.speedX, p.speedY, p.speedZ));
			p.x += velocity.x * dt;
			p.y += velocity.y * dt;
			p.z += velocity.z * dt;
			p.spin += p.spinSpeed * dt;
		}
	}

	/**
	 * An emitter at {@code x, y, z} this frame: spawns a particle when its wait is over.
	 *
	 * @param key the emitter's identity (its cosmetic and group)
	 * @param bodyYaw the player's body rotation in degrees, which turns the particle's directions
	 * @param rate 0 to 1, from the emitter group's size: at 1 every wait ends with a particle, at less only that share
	 *             of them, and at 0 none
	 * @param offset where the emitter is relative to the player and their facing, remembered for first person (may be null)
	 */
	public void emit(String key, ParticleSettings settings, double x, double y, double z, float bodyYaw, BakedCosmetic source, Identifier texture, int light,
		float rate, Vector3f offset) {
		if (offset != null) {
			lastOffset.put(key, offset);
			lastLight = light;
		}
		if (rate <= 0) {
			return;
		}
		double now = now();
		Double next = nextSpawn.get(key);
		if (next != null && now < next) {
			return;
		}
		int wait = settings.spawnMin() + (settings.spawnMax() > settings.spawnMin() ? random.nextInt(settings.spawnMax() - settings.spawnMin() + 1) : 0);
		nextSpawn.put(key, now + wait / 20.0);
		if (particles.size() >= ParticleSettings.MAX_PER_PLAYER || rate < 1 && random.nextFloat() >= rate) {
			return;
		}
		// The player's frame, as the renderer turns them: Blockbench axes, facing -Z.
		Quaternionf heading = new Quaternionf().rotationY((float) Math.toRadians(180 - bodyYaw));
		// A random offset from the group, in that frame (pixels to blocks).
		Vector3f shift = heading.transform(new Vector3f(spread(settings.offsetX()), spread(settings.offsetY()), spread(settings.offsetZ())).div(16));
		float spin = (float) Math.toRadians(spread(settings.rotation()));
		float spinSpeed = (float) Math.toRadians(settings.spinMin() + random.nextFloat() * (settings.spinMax() - settings.spinMin()));
		particles.add(new Particle(x + shift.x, y + shift.y, z + shift.z, heading, settings, spin, spinSpeed, source, texture, light));
	}

	/** A random value from {@code -range} to {@code range}. */
	private float spread(float range) {
		return range > 0 ? (random.nextFloat() * 2 - 1) * range : 0;
	}

	/** Where an emitter was last seen relative to the player (see {@link #emit}), or null. */
	public Vector3f lastOffset(String key) {
		return lastOffset.get(key);
	}

	/** The light the player was last drawn with. */
	public int lastLight() {
		return lastLight;
	}

	/** Forgets emitters that are gone (for example after taking a cosmetic off). */
	public void keepEmitters(java.util.Set<String> keys) {
		nextSpawn.keySet().retainAll(keys);
		lastOffset.keySet().retainAll(keys);
	}
}
