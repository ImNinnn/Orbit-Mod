package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.client.render.CosmeticRenderData;
import dev.orbit.cosmetic.client.render.EmotePoser;
import dev.orbit.cosmetic.client.render.EmoteRenderData;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies a playing emote after the game (or a model mod) posed the player, and hides the parts body parts replace.
 * The game poses the model again for every player it draws, so this is right for each of them; model mods such as
 * EMF pose the same model, so their parts are hidden too.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	/** The game shows the body and limbs again in every pose, but never the head: it is shown here. */
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("HEAD"))
	private void orbit$showHead(AvatarRenderState state, CallbackInfo ci) {
		((PlayerModel) (Object) this).head.visible = true;
	}

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void orbit$applyEmote(AvatarRenderState state, CallbackInfo ci) {
		PlayerBody model = PlayerBody.of((PlayerModel) (Object) this);
		EmoteRenderData emote = state.getData(EmoteRenderData.KEY);
		if (emote != null) {
			EmotePoser.apply(model, emote.body());
		}
		CosmeticRenderData cosmetics = state.getData(CosmeticRenderData.KEY);
		// Each part's skin layer (hat, jacket, sleeve, trouser leg) is its child, so it goes with it.
		EmotePoser.hide(model, cosmetics == null ? 0 : cosmetics.hiddenParts());
	}
}
