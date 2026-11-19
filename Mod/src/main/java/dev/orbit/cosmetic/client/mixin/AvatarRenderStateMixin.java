package dev.orbit.cosmetic.client.mixin;

//? if >=1.21.2 {
import dev.orbit.cosmetic.client.render.PlayerFrame;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} else {
/*import dev.orbit.cosmetic.client.gfx.PlayerState;
import net.minecraft.client.player.AbstractClientPlayer;
*///?}
import dev.orbit.cosmetic.client.render.PlayerFrameHolder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives the player's render state a place for Orbit's frame (see {@link PlayerFrameHolder}). */
//? if >=1.21.2 {
@Mixin(AvatarRenderState.class)
abstract class AvatarRenderStateMixin implements PlayerFrameHolder {
	@Unique
	private @Nullable PlayerFrame orbit$frame;

	@Override
	public @Nullable PlayerFrame orbit$frame() {
		return orbit$frame;
	}

	@Override
	public void orbit$setFrame(@Nullable PlayerFrame frame) {
		orbit$frame = frame;
	}
}
//?} else {
/*// Before 1.21.2: the player entity, while it is drawn.
@Mixin(AbstractClientPlayer.class)
abstract class AvatarRenderStateMixin implements PlayerFrameHolder {
	@Unique
	private @Nullable PlayerState orbit$state;

	@Override
	public @Nullable PlayerState orbit$state() {
		return orbit$state;
	}

	@Override
	public void orbit$setState(@Nullable PlayerState state) {
		orbit$state = state;
	}
}
*///?}
