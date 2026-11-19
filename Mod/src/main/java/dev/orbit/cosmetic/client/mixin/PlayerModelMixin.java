package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.render.PlayerFrame;
import dev.orbit.cosmetic.client.gfx.PlayerState;
import dev.orbit.cosmetic.client.gfx.PlayerBody;
import dev.orbit.cosmetic.client.render.EmotePoser;
import net.minecraft.client.model.player.PlayerModel;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
*///?}
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
	//? if >=1.21.2 {
	/** The game shows the body and limbs again in every pose, but never the head: it is shown here. */
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("HEAD"))
	private void orbit$showHead(AvatarRenderState state, CallbackInfo ci) {
		((PlayerModel) (Object) this).head.visible = true;
	}

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void orbit$applyEmote(AvatarRenderState state, CallbackInfo ci) {
		PlayerBody model = PlayerBody.of((PlayerModel) (Object) this);
		PlayerFrame frame = PlayerState.of(state).frame();
		if (frame == null) {
			return;
		}
		if (frame.emote != null) {
			EmotePoser.apply(model, frame.emote);
		}
		// Each part's skin layer (hat, jacket, sleeve, trouser leg) is its child, so it goes with it.
		EmotePoser.hide(model, frame.hiddenParts());
	}
	//?} else {
	/*// Before 1.21.2 the model is posed from the player entity, which carries Orbit's state while it is drawn (the
	// player renderer shows every part again for each player).
	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void orbit$applyEmote(LivingEntity entity, float walkPos, float walkSpeed, float ageInTicks, float headYaw, float headPitch, CallbackInfo ci) {
		PlayerState state = entity instanceof AbstractClientPlayer player ? PlayerState.of(player) : null;
		PlayerFrame frame = state == null ? null : state.frame();
		if (frame == null) {
			return;
		}
		PlayerBody model = PlayerBody.of((PlayerModel<?>) (Object) this);
		if (frame.emote != null) {
			EmotePoser.apply(model, frame.emote);
		}
		EmotePoser.hide(model, frame.hiddenParts());
	}
	*///?}
}
