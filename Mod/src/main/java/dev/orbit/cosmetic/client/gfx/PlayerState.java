package dev.orbit.cosmetic.client.gfx;

import dev.orbit.cosmetic.client.anim.PlayerMotion;
import dev.orbit.cosmetic.client.render.PlayerFrame;
import dev.orbit.cosmetic.client.render.PlayerFrameHolder;
import net.minecraft.client.Minecraft;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
*///?}
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
	//? if >=1.21.2 {
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
		return ((PlayerFrameHolder) state).orbit$frame();
	}

	public void setFrame(@Nullable PlayerFrame frame) {
		((PlayerFrameHolder) state).orbit$setFrame(frame);
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
		//? if >=1.21.9 {
		state.lightCoords = FULL_BRIGHT;
		//?}
		// (Before 1.21.9 the light is given when drawing: see compat/PreviewRenderer.)
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
	//?} else {
	/*/^* Full brightness, so menu players look the same everywhere. ^/
	private static final int FULL_BRIGHT = 0xF000F0;

	/^* The player drawn, or null for a menu player. ^/
	private final @Nullable AbstractClientPlayer player;
	private @Nullable PlayerFrame frame;
	private @Nullable OrbitSkin skin;
	private @Nullable Component nameTag;
	private double distanceToCameraSq;
	private boolean invisible;
	private double x;
	private double y;
	private double z;
	private float bodyRot;
	private PlayerMotion motion = new PlayerMotion(0, 0, 0, 0, false, false, false, false);
	/^* A menu player's time (ticks), for the idle sway. ^/
	private float ageInTicks;
	/^* The skin parts a menu player shows (bits of PlayerModelPart#getMask). ^/
	private int shownParts = -1;

	private PlayerState(@Nullable AbstractClientPlayer player) {
		this.player = player;
	}

	/^*
	 * Version-specific code only: the state of a player the game is about to draw (before 1.21.2 the game keeps
	 * none of its own, so Orbit reads the entity).
	 ^/
	public static PlayerState capture(AbstractClientPlayer player, float partialTicks) {
		PlayerState state = new PlayerState(player);
		state.skin = OrbitSkin.of(player);
		state.nameTag = player.getDisplayName();
		state.distanceToCameraSq = Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(player);
		state.invisible = player.isInvisible();
		state.x = Mth.lerp(partialTicks, player.xo, player.getX());
		state.y = Mth.lerp(partialTicks, player.yo, player.getY());
		state.z = Mth.lerp(partialTicks, player.zo, player.getZ());
		state.bodyRot = Mth.rotLerp(partialTicks, player.yBodyRotO, player.yBodyRot);
		float headRot = Mth.wrapDegrees(Mth.rotLerp(partialTicks, player.yHeadRotO, player.yHeadRot) - state.bodyRot);
		boolean walking = !player.isPassenger() && player.isAlive();
		state.motion = new PlayerMotion(walking ? player.walkAnimation.position(partialTicks) : 0, walking ? Math.min(1, player.walkAnimation.speed(partialTicks)) : 0,
			Mth.lerp(partialTicks, player.xRotO, player.getXRot()), headRot, player.isCrouching(), player.isVisuallySwimming(), player.isFallFlying(), player.isInWater());
		state.ageInTicks = player.tickCount + partialTicks;
		return state;
	}

	/^* Version-specific code only: the state of a player while the game draws them, or null. ^/
	public static @Nullable PlayerState of(AbstractClientPlayer player) {
		return ((PlayerFrameHolder) player).orbit$state();
	}

	/^* A player without an entity, for a menu (see {@link #poseForMenu}). ^/
	public static PlayerState create() {
		return new PlayerState(null);
	}

	/^* The player drawn, or null for a menu player (version-specific code only). ^/
	public @Nullable AbstractClientPlayer entity() {
		return player;
	}

	/^* What Orbit draws on this player this frame, or null for nothing. ^/
	public @Nullable PlayerFrame frame() {
		return frame;
	}

	public void setFrame(@Nullable PlayerFrame frame) {
		this.frame = frame;
	}

	public @Nullable OrbitSkin skin() {
		return skin;
	}

	public void setSkin(OrbitSkin skin) {
		this.skin = skin;
	}

	/^* The name shown over the player, or null when none is. ^/
	public @Nullable Component nameTag() {
		return nameTag;
	}

	public void setNameTag(@Nullable Component nameTag) {
		this.nameTag = nameTag;
	}

	/^* How far the camera is, squared (blocks). ^/
	public double distanceToCameraSq() {
		return distanceToCameraSq;
	}

	public boolean isInvisible() {
		return invisible;
	}

	/^* Where the player stands (world coordinates). ^/
	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	/^* Which way their body faces (degrees). ^/
	public float bodyRot() {
		return bodyRot;
	}

	/^* How they move and look, for animations. ^/
	public PlayerMotion motion() {
		return motion;
	}

	/^* A menu player's time (ticks), for its idle sway (version-specific code only). ^/
	public float ageInTicks() {
		return ageInTicks;
	}

	/^* Whether a menu player shows a part of the skin's outer layer or the cape (version-specific code only). ^/
	public boolean shows(PlayerModelPart part) {
		return (shownParts & part.getMask()) != 0;
	}

	/^* Full brightness, the light menu players are drawn with (version-specific code only). ^/
	public static int menuLight() {
		return FULL_BRIGHT;
	}

	/^*
	 * Sets up a menu player: standing, at full brightness, with this skin and the skin parts the options show.
	 *
	 * @param time the menu's time (ticks), for the idle sway and the walk cycle
	 * @param yaw turn to the side in degrees (0 faces the viewer)
	 * @param pitch head tilt in degrees
	 * @param walkSpeed how much the legs swing (0 to 1)
	 ^/
	public void poseForMenu(OrbitSkin skin, double time, float yaw, float pitch, float walkSpeed) {
		Minecraft minecraft = Minecraft.getInstance();
		this.skin = skin;
		ageInTicks = (float) time;
		bodyRot = 180.0F + yaw;
		nameTag = null;
		motion = new PlayerMotion((float) time * walkSpeed, walkSpeed, pitch, 0.0F, false, false, false, false);
		shownParts = 0;
		for (PlayerModelPart part : PlayerModelPart.values()) {
			if (minecraft.options.isModelPartEnabled(part)) {
				shownParts |= part.getMask();
			}
		}
	}
	*///?}
}
