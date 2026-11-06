package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import dev.orbit.cosmetic.common.anim.TextureAnimation;
import dev.orbit.cosmetic.common.model.TextureStyles;

import java.util.ArrayList;
import java.util.List;

/**
 * Binary encoding of a player's worn cosmetics, as published to Orbit Cloud (base64 in the presence's
 * {@code equip} field): {@code [0x02][count: u8][cosmetic...]}, big-endian; each cosmetic ends with its style
 * (u8), the vanilla parts it replaces when it is a body part (u8, bits of the anchor ordinals, 0 otherwise), its
 * particle emitters (see {@link ParticleCodec}) and its animated textures ({@code [count: u8]} then texture (u8),
 * frame width and height (u16 each) and ticks per frame (u8) for each).
 *
 * <p>Orbit Cloud stores it as sent and only reads the asset IDs, so every client decodes and validates what other
 * players publish with {@link #decode}.
 */
public final class EquipCodec {
	/** Version of this encoding, published next to it. */
	public static final int FORMAT = 1;
	/** Hard cap on cosmetics per player. */
	public static final int MAX_EQUIPPED = 16;
	public static final int MAX_ANIMATIONS = 64;
	public static final int MAX_PHYSICS_LINKS = 8;
	public static final int MAX_NAME_CHARS = 64;
	/** Far above what MAX_EQUIPPED cosmetics with every setting encode to. */
	public static final int MAX_BYTES = 32000;

	private static final int TAG = 0x02;

	private EquipCodec() {
	}

	public static byte[] encode(List<EquippedCosmetic> cosmetics) {
		ByteWriter out = new ByteWriter(64);
		out.writeByte(TAG);
		int count = Math.min(cosmetics.size(), MAX_EQUIPPED);
		out.writeByte(count);
		for (int i = 0; i < count; i++) {
			EquippedCosmetic cosmetic = cosmetics.get(i);
			out.writeFixed(cosmetic.asset().bytes());
			out.writeString(cosmetic.name(), MAX_NAME_CHARS);
			int animations = Math.min(cosmetic.animations().size(), MAX_ANIMATIONS);
			out.writeByte(animations);
			for (int a = 0; a < animations; a++) {
				AnimationSettings settings = cosmetic.animations().get(a);
				out.writeByte(settings.trigger().ordinal());
				out.writeByte(settings.loop().ordinal());
				out.writeShort(Math.round(settings.speed() * 100));
				out.writeByte(settings.priority());
				out.writeBoolean(settings.interrupt());
				out.writeShort(Math.round(settings.minWait() * 10));
				out.writeShort(Math.round(settings.maxWait() * 10));
			}
			int links = Math.min(cosmetic.physics().size(), MAX_PHYSICS_LINKS);
			out.writeByte(links);
			for (int l = 0; l < links; l++) {
				PhysicsLink link = cosmetic.physics().get(l);
				out.writeShort(link.bone());
				for (AxisDrive drive : link.axes()) {
					out.writeByte(drive.source().ordinal());
					out.writeInt(Float.floatToIntBits(drive.strength()));
					out.writeInt(Float.floatToIntBits(drive.min()));
					out.writeInt(Float.floatToIntBits(drive.max()));
					out.writeInt(Float.floatToIntBits(drive.resistance()));
				}
			}
			out.writeByte(Math.clamp(cosmetic.style(), 0, TextureStyles.MAX - 1));
			out.writeByte(cosmetic.replaces());
			ParticleCodec.write(out, cosmetic.particles());
			int textures = Math.min(cosmetic.textures().size(), TextureAnimation.MAX_PER_COSMETIC);
			out.writeByte(textures);
			for (int t = 0; t < textures; t++) {
				TextureAnimation animation = cosmetic.textures().get(t);
				out.writeByte(Math.clamp(animation.texture(), 0, 255));
				out.writeShort(animation.frameWidth());
				out.writeShort(animation.frameHeight());
				out.writeByte(animation.frameTicks());
			}
		}
		return out.toByteArray();
	}

	/** Decodes data published by another player. Anything malformed throws {@link ProtocolException}. */
	public static List<EquippedCosmetic> decode(byte[] payload) throws ProtocolException {
		if (payload.length == 0 || payload.length > MAX_BYTES) {
			throw new ProtocolException("Bad equip size " + payload.length);
		}
		ByteReader in = new ByteReader(payload);
		if (in.readUnsignedByte() != TAG) {
			throw new ProtocolException("Not an equip list");
		}
		int count = in.readUnsignedByte();
		if (count > MAX_EQUIPPED) {
			throw new ProtocolException("Too many cosmetics");
		}
		List<EquippedCosmetic> result = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			AssetId asset = AssetId.fromBytes(in.readFixed(32));
			String name = in.readString(MAX_NAME_CHARS);
			int animations = in.readUnsignedByte();
			if (animations > MAX_ANIMATIONS) {
				throw new ProtocolException("Too many animations");
			}
			List<AnimationSettings> settings = new ArrayList<>(animations);
			for (int a = 0; a < animations; a++) {
				int trigger = in.readUnsignedByte();
				int loop = in.readUnsignedByte();
				int speed = in.readUnsignedShort();
				int priority = in.readUnsignedByte();
				boolean interrupt = in.readBoolean();
				int minWait = in.readUnsignedShort();
				int maxWait = in.readUnsignedShort();
				if (trigger >= AnimationTrigger.values().length || loop >= LoopMode.values().length) {
					throw new ProtocolException("Unknown animation setting");
				}
				settings.add(new AnimationSettings(AnimationTrigger.byOrdinal(trigger), LoopMode.byOrdinal(loop), speed / 100f, priority, interrupt,
					minWait / 10f, maxWait / 10f));
			}
			int links = in.readUnsignedByte();
			if (links > MAX_PHYSICS_LINKS) {
				throw new ProtocolException("Too many physics links");
			}
			List<PhysicsLink> physics = new ArrayList<>(links);
			for (int l = 0; l < links; l++) {
				int bone = in.readUnsignedShort();
				List<AxisDrive> axes = new ArrayList<>(PhysicsLink.AXES);
				for (int a = 0; a < PhysicsLink.AXES; a++) {
					int source = in.readUnsignedByte();
					if (source >= PhysicsSource.values().length) {
						throw new ProtocolException("Unknown physics source");
					}
					axes.add(new AxisDrive(PhysicsSource.byOrdinal(source), Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()),
						Float.intBitsToFloat(in.readInt())));
				}
				physics.add(new PhysicsLink(bone, axes));
			}
			int style = in.readUnsignedByte();
			if (style >= TextureStyles.MAX) {
				throw new ProtocolException("Unknown style");
			}
			int replaces = in.readUnsignedByte();
			if ((replaces & ~dev.orbit.cosmetic.common.model.BodyParts.all()) != 0) {
				throw new ProtocolException("Unknown body parts");
			}
			List<ParticleSettings> particles = ParticleCodec.read(in);
			int textures = in.readUnsignedByte();
			if (textures > TextureAnimation.MAX_PER_COSMETIC) {
				throw new ProtocolException("Too many animated textures");
			}
			List<TextureAnimation> animated = new ArrayList<>(textures);
			for (int t = 0; t < textures; t++) {
				int texture = in.readUnsignedByte();
				int width = in.readUnsignedShort();
				int height = in.readUnsignedShort();
				int ticks = in.readUnsignedByte();
				if (width == 0 || height == 0 || ticks == 0 || ticks > TextureAnimation.MAX_TICKS) {
					throw new ProtocolException("Bad animated texture");
				}
				animated.add(new TextureAnimation(texture, width, height, ticks));
			}
			result.add(new EquippedCosmetic(asset, name, settings, physics, style, particles, replaces, animated));
		}
		in.expectEnd();
		return result;
	}
}
