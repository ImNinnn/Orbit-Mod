package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.model.CosmeticModel;

import java.util.List;

/** What the particle screens edit: a cosmetic of the player's or an emote. */
interface ParticleTarget {
	String name();

	CosmeticModel model();

	/** Every emitter's settings, in model order. */
	List<ParticleSettings> particles();

	/** False for claimed featured items, which stay as Orbit Cloud has them. */
	boolean editable();

	void set(ParticleSettings settings);

	/** Makes a group an emitter, with the default settings. */
	void add(int bone);

	/** A group stops being an emitter. */
	void remove(int bone);

	/** Shows it on a preview player with its current particles: worn, or the emote played again and again. */
	void preview(PreviewModel model);

	default ParticleSettings get(int bone) {
		for (ParticleSettings settings : particles()) {
			if (settings.bone() == bone) {
				return settings;
			}
		}
		return null;
	}

	static ParticleTarget of(LocalCosmetic cosmetic) {
		return new ParticleTarget() {
			public String name() {
				return cosmetic.displayName();
			}

			public CosmeticModel model() {
				return cosmetic.model();
			}

			public List<ParticleSettings> particles() {
				return cosmetic.particles();
			}

			public boolean editable() {
				return !cosmetic.isFeatured();
			}

			public void set(ParticleSettings settings) {
				OrbitClient.get().library().setParticles(cosmetic, settings);
			}

			public void add(int bone) {
				OrbitClient.get().library().addParticles(cosmetic, bone);
			}

			public void remove(int bone) {
				OrbitClient.get().library().removeParticles(cosmetic, bone);
			}

			public void preview(PreviewModel model) {
				model.setWorn(cosmetic.isUsable() ? List.of(cosmetic.toEquipped()) : List.of());
			}
		};
	}

	static ParticleTarget of(EmoteLibrary.LocalEmote emote) {
		return new ParticleTarget() {
			public String name() {
				return emote.displayName();
			}

			public CosmeticModel model() {
				return emote.file().model();
			}

			public List<ParticleSettings> particles() {
				return emote.file().particles();
			}

			public boolean editable() {
				return !emote.file().isFeatured();
			}

			public void set(ParticleSettings settings) {
				OrbitClient.get().emotes().setParticles(emote, settings);
			}

			public void add(int bone) {
				OrbitClient.get().emotes().addParticles(emote, bone);
			}

			public void remove(int bone) {
				OrbitClient.get().emotes().removeParticles(emote, bone);
			}

			public void preview(PreviewModel model) {
				if (emote.asset() != null) {
					model.loopEmote(emote.asset(), emote.file().particles());
				}
			}
		};
	}
}
