package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.render.CosmeticRenderData;
import dev.orbit.cosmetic.client.render.EmotePoser;
import dev.orbit.cosmetic.client.render.EmoteRenderData;
import dev.orbit.cosmetic.common.model.Anchor;
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
		PlayerModel model = (PlayerModel) (Object) this;
		EmoteRenderData emote = state.getData(EmoteRenderData.KEY);
		if (emote != null) {
			EmotePoser.apply(model, emote.body());
		}
		CosmeticRenderData cosmetics = state.getData(CosmeticRenderData.KEY);
		int hidden = cosmetics == null ? 0 : cosmetics.hiddenParts();
		if (hidden != 0) {
			// Each part's skin layer (hat, jacket, sleeve, trouser leg) is its child, so it goes with it.
			model.head.visible &= (hidden & 1 << Anchor.HEAD.ordinal()) == 0;
			model.body.visible &= (hidden & 1 << Anchor.BODY.ordinal()) == 0;
			model.rightArm.visible &= (hidden & 1 << Anchor.RIGHT_ARM.ordinal()) == 0;
			model.leftArm.visible &= (hidden & 1 << Anchor.LEFT_ARM.ordinal()) == 0;
			model.rightLeg.visible &= (hidden & 1 << Anchor.RIGHT_LEG.ordinal()) == 0;
			model.leftLeg.visible &= (hidden & 1 << Anchor.LEFT_LEG.ordinal()) == 0;
		}
	}
}
