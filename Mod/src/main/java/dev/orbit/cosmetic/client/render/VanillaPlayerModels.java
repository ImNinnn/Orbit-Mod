package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.client.gfx.PlayerState;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
//? if >=1.21.2 {
import net.minecraft.client.model.player.PlayerCapeModel;
//?}
import net.minecraft.client.model.player.PlayerModel;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
*///?}
//? if >=1.21.9
import net.minecraft.world.entity.player.PlayerModelType;
//? if >=1.20.2
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Plain vanilla player models (wide and slim arms, and the cape), baked by Orbit itself so model replacement mods
 * never change them. Used for players playing an emote and for the players in Orbit's menus.
 */
public final class VanillaPlayerModels {
	private static PlayerModel wide;
	private static PlayerModel slim;
	//? if >=1.21.2
	private static PlayerCapeModel cape;

	private VanillaPlayerModels() {
	}

	/** True when this player is drawn with the vanilla models: while emoting, and always in Orbit's menus. */
	//? if >=1.21.2 {
	public static boolean isUsedFor(AvatarRenderState state) {
		PlayerFrame frame = PlayerState.of(state).frame();
		return frame != null && frame.usesVanillaModel();
	}
	//?} else {
	/*public static boolean isUsedFor(AbstractClientPlayer player) {
		PlayerState state = PlayerState.of(player);
		PlayerFrame frame = state == null ? null : state.frame();
		return frame != null && frame.usesVanillaModel();
	}
	*///?}

	/**
	 * The cape. Model packs animate their cape from their own body (Fresh Animations' player cape follows its body
	 * variables), which a vanilla body never sets, so their cape would end up inside the player.
	 */
	//? if >=1.21.2 {
	public static PlayerCapeModel cape() {
		if (cape == null) {
			cape = new PlayerCapeModel(PlayerCapeModel.createCapeLayer().bakeRoot());
		}
		return cape;
	}
	//?}
	// (Before 1.21.2 the cape is part of the player model.)

	//? if >=1.20.2 {
	public static PlayerModel forSkin(PlayerSkin skin) {
		//? if >=1.21.9 {
		return model(skin.model() == PlayerModelType.SLIM);
		//?} else {
		/*return model(skin.model() == PlayerSkin.Model.SLIM);
		*///?}
	}
	//?}

	public static PlayerModel model(boolean isSlim) {
		if (isSlim) {
			if (slim == null) {
				slim = bake(true);
			}
			return slim;
		}
		if (wide == null) {
			wide = bake(false);
		}
		return wide;
	}

	private static PlayerModel bake(boolean slimArms) {
		return new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slimArms), 64, 64).bakeRoot(), slimArms);
	}
}
