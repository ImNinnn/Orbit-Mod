package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.anim.ParticleSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Binary encoding of particle emitter settings, inside an equip list (per cosmetic) and on its own for the emote a
 * player plays ({@code [0x03][list]}, base64 in the presence's {@code emote.particles}). A list is
 * {@code [count: u8]} then per emitter {@code [bone: u16][flags: u8][texture: u8][25 values: f32]}; flags are 1 for
 * enabled and 2 for glow, texture 255 for none. Decoded values are clamped like any {@link ParticleSettings}.
 */
public final class ParticleCodec {
	/** Far above what {@link ParticleSettings#MAX_EMITTERS} emitters encode to. */
	public static final int MAX_BYTES = 2048;
	private static final int TAG = 0x03;
	private static final int VALUES = ParticleSettings.VALUE_NAMES.length;

	private ParticleCodec() {
	}

	public static void write(ByteWriter out, List<ParticleSettings> particles) {
		int count = Math.min(particles.size(), ParticleSettings.MAX_EMITTERS);
		out.writeByte(count);
		for (int i = 0; i < count; i++) {
			ParticleSettings settings = particles.get(i);
			out.writeShort(settings.bone());
			out.writeByte((settings.enabled() ? 1 : 0) | (settings.glow() ? 2 : 0));
			out.writeByte(settings.texture() < 0 ? 255 : settings.texture());
			for (float value : settings.values()) {
				out.writeInt(Float.floatToIntBits(value));
			}
		}
	}

	public static List<ParticleSettings> read(ByteReader in) throws ProtocolException {
		int count = in.readUnsignedByte();
		if (count > ParticleSettings.MAX_EMITTERS) {
			throw new ProtocolException("Too many particle emitters");
		}
		List<ParticleSettings> result = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int bone = in.readUnsignedShort();
			int flags = in.readUnsignedByte();
			int texture = in.readUnsignedByte();
			float[] values = new float[VALUES];
			for (int v = 0; v < VALUES; v++) {
				values[v] = Float.intBitsToFloat(in.readInt());
			}
			ParticleSettings settings = ParticleSettings.defaults(bone, texture == 255 ? -1 : texture).withValues(values);
			result.add(settings.withEnabled((flags & 1) != 0 && texture != 255).withGlow((flags & 2) != 0));
		}
		return result;
	}

	/** The particles of an emote, on their own. */
	public static byte[] encode(List<ParticleSettings> particles) {
		ByteWriter out = new ByteWriter(16);
		out.writeByte(TAG);
		write(out, particles);
		return out.toByteArray();
	}

	/** Decodes data published by another player. Anything malformed throws {@link ProtocolException}. */
	public static List<ParticleSettings> decode(byte[] payload) throws ProtocolException {
		if (payload.length == 0 || payload.length > MAX_BYTES) {
			throw new ProtocolException("Bad particles size " + payload.length);
		}
		ByteReader in = new ByteReader(payload);
		if (in.readUnsignedByte() != TAG) {
			throw new ProtocolException("Not a particle list");
		}
		List<ParticleSettings> result = read(in);
		in.expectEnd();
		return result;
	}
}
