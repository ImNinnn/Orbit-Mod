package dev.orbit.cosmetic.client.gui.preview;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.render.ParticleRenderData;
import dev.orbit.cosmetic.client.render.PickBuffer;
import dev.orbit.cosmetic.common.anim.PhysicsState;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.client.render.CosmeticRenderData;
import dev.orbit.cosmetic.client.render.EmoteRenderData;
import dev.orbit.cosmetic.client.render.RenderPiece;
import dev.orbit.cosmetic.common.emote.BodyPose;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;

import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Quad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A player shown in a menu: the local player's skin wearing a given set of cosmetics, maybe playing an emote.
 * It needs no player entity, so it also works on the title screen. Time is {@link OrbitClient#menuTime()}.
 */
public final class PreviewModel {
	/** The bare player in model pixels (Y down, feet at 24): top of the head, feet, and half the width. */
	private static final float PLAYER_TOP = -8;
	private static final float PLAYER_BOTTOM = 24;
	private static final float PLAYER_RADIUS = 8;
	/** Extents of cosmetic models, measured once each. */
	private static final Map<CosmeticModel, float[]> EXTENTS = Collections.synchronizedMap(new WeakHashMap<>());

	/** Full brightness, so previews look the same everywhere. */
	private static final int FULL_BRIGHT = 0xF000F0;

	private final AvatarRenderState state = new AvatarRenderState();
	private final Wearer wearer;
	private List<EquippedCosmetic> worn = List.of();
	/** The skin shown; null for the local player's. */
	private PlayerSkin skin;
	private long lastTick = Long.MIN_VALUE;
	private int emoteSeq;
	/** The made-up movement shown (blocks per second, relative to the body); at rest by default. */
	private PhysicsState.Input moving = PhysicsState.Input.REST;
	/** Where its faces are kept for clicking on them, or null. */
	private PickBuffer pick;
	private AssetId hovered;
	private AssetId selected;

	public PreviewModel() {
		this(UUID.randomUUID());
	}

	public PreviewModel(UUID id) {
		this.wearer = new Wearer(id);
	}

	public void setWorn(List<EquippedCosmetic> cosmetics) {
		if (!cosmetics.equals(worn)) {
			worn = List.copyOf(cosmetics);
			wearer.setWorn(worn);
		}
	}

	/** Shows this skin instead of the local player's (null: the local player's again). */
	public void setSkin(PlayerSkin skin) {
		this.skin = skin;
	}

	/** Plays an emote from the start, with its particles. */
	public void playEmote(AssetId asset, List<ParticleSettings> particles) {
		wearer.setEmote(new Wearer.Emote(asset, true, ++emoteSeq, particles, OrbitClient.get().menuTime(), 0));
	}

	/**
	 * Keeps an emote playing on this preview, again and again, with these particles: starts it when another (or
	 * none) is shown or it ended, and otherwise only changes its particles, so editing them does not restart it.
	 */
	public void loopEmote(AssetId asset, List<ParticleSettings> particles) {
		Wearer.Emote playing = wearer.emote();
		if (playing == null || !playing.asset.equals(asset) || playing.isFinished(OrbitClient.get().menuTime())) {
			playEmote(asset, particles);
		} else if (!playing.particles.equals(particles)) {
			playing.particles = List.copyOf(particles);
		}
	}

	/**
	 * Shows the player moving at this speed (blocks per second): forward (negative is backward), to its right and up.
	 * Animations and movement physics react to it and the legs swing; the player itself stays where it is.
	 */
	public void setMoving(double forward, double sideways, double up) {
		moving = new PhysicsState.Input(forward, sideways, up, 0);
	}

	/** Keeps where its cosmetics are drawn in this buffer, for clicking on them (null: not kept). */
	public void setPicking(PickBuffer value) {
		pick = value;
	}

	/** Tints a cosmetic under the mouse (whitened) and the selected one (reddened); null for none. */
	public void setHighlight(AssetId hoveredAsset, AssetId selectedAsset) {
		hovered = hoveredAsset;
		selected = selectedAsset;
	}

	/** Changes the particles of the emote being shown, without restarting it. */
	public void setEmoteParticles(List<ParticleSettings> particles) {
		Wearer.Emote playing = wearer.emote();
		if (playing != null && !playing.particles.equals(particles)) {
			playing.particles = List.copyOf(particles);
		}
	}

	public void stopEmote() {
		wearer.setEmote(null);
	}

	/** The emote being shown, or null once it ended. */
	public Wearer.Emote emote() {
		return wearer.emote();
	}

	/**
	 * Prepares this frame and returns the batch entry drawing the player as large as fits in a box, everything it
	 * wears included, so nothing reaches outside the box (animations aside).
	 *
	 * @param x the box's left edge, from the batch's left edge (GUI pixels)
	 * @param y the box's top edge, from the batch's top edge (GUI pixels)
	 */
	public PreviewBatch.Entry entryIn(float x, float y, float width, float height, float yaw, float pitch, float partialTick) {
		return entryIn(x, y, width, height, yaw, pitch, partialTick, false);
	}

	/**
	 * Like {@link #entryIn(float, float, float, float, float, float, float)}; {@code steady} keeps the size the same
	 * whether an emote plays or not (room for raised arms is always kept, an emote's own props are not fitted).
	 */
	public PreviewBatch.Entry entryIn(float x, float y, float width, float height, float yaw, float pitch, float partialTick, boolean steady) {
		float[] extent = extent(steady);
		float tall = extent[1] - extent[0];
		float scale = Math.min(height / tall, width / (2 * extent[2]));
		float feetY = y + (height - tall * scale) / 2 + (PLAYER_BOTTOM - extent[0]) * scale;
		return entry(x + width / 2, feetY, scale * 16, yaw, pitch, partialTick);
	}

	/** Top, bottom and half-width of the player with what it wears and its emote, in model pixels, with a margin. */
	private float[] extent(boolean steady) {
		float[] result = {PLAYER_TOP, PLAYER_BOTTOM, PLAYER_RADIUS};
		OrbitClient orbit = OrbitClient.get();
		for (EquippedCosmetic cosmetic : worn) {
			include(result, orbit.registry().get(cosmetic.asset()));
		}
		Wearer.Emote emote = wearer.emote();
		if (emote != null || steady) {
			// Emotes raise arms above the head and swing them out.
			result[0] = Math.min(result[0], PLAYER_TOP - 6);
			result[2] = Math.max(result[2], PLAYER_RADIUS + 6);
		}
		if (emote != null && !steady) {
			include(result, emote.baked());
		}
		float margin = (result[1] - result[0]) * 0.04F;
		return new float[] {result[0] - margin, result[1] + margin, result[2] + margin};
	}

	private static void include(float[] result, BakedCosmetic baked) {
		if (baked == null || baked.isReleased()) {
			return;
		}
		float[] extent = EXTENTS.computeIfAbsent(baked.model(), PreviewModel::measure);
		result[0] = Math.min(result[0], extent[0]);
		result[1] = Math.max(result[1], extent[1]);
		result[2] = Math.max(result[2], extent[2]);
	}

	/** The model's geometry in player model space, from the bones' rest pivots (rest rotations are left out). */
	private static float[] measure(CosmeticModel model) {
		float[] extent = {Float.MAX_VALUE, -Float.MAX_VALUE, 0};
		List<Bone> bones = model.bones();
		float[][] pivots = new float[bones.size()][];
		for (Bone bone : bones) {
			float[] base = bone.parent() >= 0 && pivots[bone.parent()] != null ? pivots[bone.parent()] : anchorPivot(bone.anchor());
			float[] pivot = {base[0] + bone.pivotX(), base[1] + bone.pivotY(), base[2] + bone.pivotZ()};
			pivots[bone.index()] = pivot;
			for (Quad quad : bone.quads()) {
				float[] positions = quad.positions();
				for (int i = 0; i < positions.length; i += 3) {
					float x = pivot[0] + positions[i];
					float y = pivot[1] + positions[i + 1];
					float z = pivot[2] + positions[i + 2];
					extent[0] = Math.min(extent[0], y);
					extent[1] = Math.max(extent[1], y);
					extent[2] = Math.max(extent[2], Math.max(Math.abs(x), Math.abs(z)));
				}
			}
		}
		return extent[0] > extent[1] ? new float[] {PLAYER_TOP, PLAYER_BOTTOM, 0} : extent;
	}

	/** Where a body part's joint is in player model space (Y down, the neck at 0, the feet at 24). */
	private static float[] anchorPivot(Anchor anchor) {
		return new float[] {-anchor.pivotX(), 24 - anchor.pivotY(), anchor.pivotZ()};
	}

	/**
	 * Prepares this frame and returns the batch entry drawing it.
	 *
	 * @param x where the feet are, from the batch's left edge (GUI pixels)
	 * @param y where the feet are, from the batch's top edge (GUI pixels)
	 * @param size GUI pixels per block
	 * @param yaw turn to the side in degrees (0 faces the viewer)
	 * @param pitch head tilt in degrees
	 */
	public PreviewBatch.Entry entry(float x, float y, float size, float yaw, float pitch, float partialTick) {
		OrbitClient orbit = OrbitClient.get();
		Minecraft minecraft = Minecraft.getInstance();
		double time = orbit.menuTime();
		long tick = (long) Math.floor(time);
		if (tick != lastTick) {
			lastTick = tick;
			wearer.tickMoving(tick, moving, orbit.registry()::get);
		}
		state.skin = skin != null ? skin : orbit.menuSkin();
		state.lightCoords = FULL_BRIGHT;
		state.boundingBoxWidth = 0.6F;
		state.boundingBoxHeight = 1.8F;
		state.eyeHeight = 1.62F;
		state.ageInTicks = (float) time;
		state.scale = 1.0F;
		state.bodyRot = 180.0F + yaw;
		state.yRot = 0.0F;
		state.xRot = pitch;
		state.nameTag = null;
		// Legs swing like the game's: a share of a full swing from the distance covered in a tick.
		state.walkAnimationSpeed = (float) Math.min(1, Math.hypot(moving.forward(), moving.sideways()) / 20 * 4);
		state.walkAnimationPos = (float) time * state.walkAnimationSpeed;
		// Menus always show the vanilla player model, whatever model packs change in the world.
		state.setData(EmoteRenderData.MENU_PREVIEW, Boolean.TRUE);
		state.showHat = minecraft.options.isModelPartEnabled(PlayerModelPart.HAT);
		state.showJacket = minecraft.options.isModelPartEnabled(PlayerModelPart.JACKET);
		state.showLeftSleeve = minecraft.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
		state.showRightSleeve = minecraft.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
		state.showLeftPants = minecraft.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
		state.showRightPants = minecraft.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
		state.showCape = minecraft.options.isModelPartEnabled(PlayerModelPart.CAPE);
		// Physics move once per tick of the menu's time; frames in between interpolate.
		List<RenderPiece> pieces = wearer.pieces(state, time, (float) (time - tick), orbit.config().playAnimations, orbit.config().maxFacesPerPlayer, orbit.registry()::get);
		if (hovered != null || selected != null) {
			List<RenderPiece> highlighted = new ArrayList<>(pieces.size());
			for (RenderPiece piece : pieces) {
				AssetId asset = piece.baked().id();
				highlighted.add(asset.equals(selected) ? piece.selected() : asset.equals(hovered) ? piece.hovered() : piece);
			}
			pieces = highlighted;
		}
		state.setData(PickBuffer.KEY, pick);
		state.setData(CosmeticRenderData.KEY, pieces.isEmpty() ? null : new CosmeticRenderData(pieces));
		state.setData(ParticleRenderData.KEY, new ParticleRenderData(wearer.particles(), true));
		Wearer.Emote emote = wearer.emote();
		state.skin = orbit.skinMasks().apply(state.skin, pieces, emote == null ? null : emote.baked());
		BodyPose body = wearer.body();
		if (body.isEmpty()) {
			state.setData(EmoteRenderData.KEY, null);
		} else {
			BodyPose copy = new BodyPose();
			copy.copyFrom(body);
			state.setData(EmoteRenderData.KEY, new EmoteRenderData(copy));
		}
		Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(-6));
		Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(tilt);
		return new PreviewBatch.Entry(state, x, y, size, rotation, tilt);
	}
}
