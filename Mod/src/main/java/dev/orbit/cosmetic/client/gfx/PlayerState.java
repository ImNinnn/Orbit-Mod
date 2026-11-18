package dev.orbit.cosmetic.client.gfx;

import dev.orbit.cosmetic.client.anim.PlayerMotion;
import dev.orbit.cosmetic.client.render.PlayerFrame;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.jspecify.annotations.Nullable;

/**
 * A player as the game is about to draw them: what Orbit reads (where they are, how they move, their skin and name
 * tag) and what it adds ({@link PlayerFrame}). Since 1.21.2 Minecraft copies all of this into a render state before
 * drawing; older versions read it from the player entity while drawing. Each Minecraft version provides this class.
 * Render thread.
 */
public final class PlayerState {
	/** Orbit's frame on a render state (Fabric keeps it with the state). */
	private static final RenderStateDataKey<PlayerFrame> FRAME = RenderStateDataKey.create(() -> "orbit_cosmetic:frame");
	/** Full brightness, so menu players look the same everywhere. */
	private static final int FULL_BRIGHT = 0xF000F0;

	private final AvatarRenderState state;

	private PlayerState(AvatarRenderState state) {
		this.state = state;
	}

	/** Version-specific code only: wraps Minecraft's render state. */
	public static PlayerState of(AvatarRenderState state) {
		return new PlayerState(state);
	}

	/** A player without an entity, for a menu (see {@link #poseForMenu}). */
	public static PlayerState create() {
		return new PlayerState(new AvatarRenderState());
	}

	/** Minecraft's own render state, for version-specific code. */
	public AvatarRenderState raw() {
		return state;
	}

	/** What Orbit draws on this player this frame, or null for nothing. */
	public @Nullable PlayerFrame frame() {
		return state.getData(FRAME);
	}

	public void setFrame(@Nullable PlayerFrame frame) {
		state.setData(FRAME, frame);
	}

	public @Nullable OrbitSkin skin() {
		return state.skin == null ? null : OrbitSkin.of(state.skin);
	}

	public void setSkin(OrbitSkin skin) {
		state.skin = skin.raw();
	}

	/** The name shown over the player, or null when none is. */
	public @Nullable Component nameTag() {
		return state.nameTag;
	}

	public void setNameTag(@Nullable Component nameTag) {
		state.nameTag = nameTag;
	}

	/** How far the camera is, squared (blocks). */
	public double distanceToCameraSq() {
		return state.distanceToCameraSq;
	}

	public boolean isInvisible() {
		return state.isInvisible;
	}

	/** Where the player stands (world coordinates). */
	public double x() {
		return state.x;
	}

	public double y() {
		return state.y;
	}

	public double z() {
		return state.z;
	}

	/** Which way their body faces (degrees). */
	public float bodyRot() {
		return state.bodyRot;
	}

	/** How they move and look, for animations. */
	public PlayerMotion motion() {
		return new PlayerMotion(state.walkAnimationPos, state.walkAnimationSpeed, state.xRot, state.yRot, state.isCrouching, state.isVisuallySwimming,
			state.isFallFlying, state.isInWater);
	}

	/**
	 * Sets up a menu player: standing, at full brightness, with this skin and the skin parts the options show.
	 *
	 * @param time the menu's time (ticks), for the idle sway and the walk cycle
	 * @param yaw turn to the side in degrees (0 faces the viewer)
	 * @param pitch head tilt in degrees
	 * @param walkSpeed how much the legs swing (0 to 1)
	 */
	public void poseForMenu(OrbitSkin skin, double time, float yaw, float pitch, float walkSpeed) {
		Minecraft minecraft = Minecraft.getInstance();
		state.skin = skin.raw();
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
		state.walkAnimationSpeed = walkSpeed;
		state.walkAnimationPos = (float) time * walkSpeed;
		state.showHat = minecraft.options.isModelPartEnabled(PlayerModelPart.HAT);
		state.showJacket = minecraft.options.isModelPartEnabled(PlayerModelPart.JACKET);
		state.showLeftSleeve = minecraft.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
		state.showRightSleeve = minecraft.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
		state.showLeftPants = minecraft.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
		state.showRightPants = minecraft.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
		state.showCape = minecraft.options.isModelPartEnabled(PlayerModelPart.CAPE);
	}
}
