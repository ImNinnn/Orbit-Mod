package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How far the block being broken is along, for the "looking at" HUD component. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("destroyProgress")
	float orbit$destroyProgress();

	@Accessor("destroyBlockPos")
	BlockPos orbit$destroyBlockPos();
}
