package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.render.PlayerFrame;
import dev.orbit.cosmetic.client.render.PlayerFrameHolder;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives the player's render state a place for Orbit's frame (see {@link PlayerFrameHolder}). */
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
