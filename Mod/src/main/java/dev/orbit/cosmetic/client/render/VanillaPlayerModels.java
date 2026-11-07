package dev.orbit.cosmetic.client.render;

import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Plain vanilla player models (wide and slim arms, and the cape), baked by Orbit itself so model replacement mods
 * never change them. Used for players playing an emote and for the players in Orbit's menus.
 */
public final class VanillaPlayerModels {
	private static PlayerModel wide;
	private static PlayerModel slim;
	private static PlayerCapeModel cape;

	private VanillaPlayerModels() {
	}

	/** True when this player is drawn with the vanilla models: while emoting, and always in Orbit's menus. */
	public static boolean isUsedFor(AvatarRenderState state) {
		return state.getData(EmoteRenderData.KEY) != null || state.getData(EmoteRenderData.MENU_PREVIEW) != null;
	}

	/**
	 * The cape. Model packs animate their cape from their own body (Fresh Animations' player cape follows its body
	 * variables), which a vanilla body never sets, so their cape would end up inside the player.
	 */
	public static PlayerCapeModel cape() {
		if (cape == null) {
			cape = new PlayerCapeModel(PlayerCapeModel.createCapeLayer().bakeRoot());
		}
		return cape;
	}

	public static PlayerModel forSkin(PlayerSkin skin) {
		return model(skin.model() == PlayerModelType.SLIM);
	}

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
