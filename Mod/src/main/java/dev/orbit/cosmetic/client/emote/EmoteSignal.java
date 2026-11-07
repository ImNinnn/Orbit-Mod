package dev.orbit.cosmetic.client.emote;

import dev.orbit.cosmetic.common.anim.ParticleSettings;

import java.util.List;

/**
 * An emote as published to Orbit Cloud: which file (SHA-256), whether it keeps playing while the player moves, a
 * number that changes every time an emote starts (so playing the same emote again restarts it), and its particles.
 */
public record EmoteSignal(String sha256, boolean mobile, int seq, List<ParticleSettings> particles) {
	public EmoteSignal {
		particles = particles == null ? List.of() : List.copyOf(particles);
	}
}
