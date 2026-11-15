package dev.orbit.cosmetic.client.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The player's exhaustion, for party members' AppleSkin exhaustion (the game keeps it to itself). */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
	@Accessor("exhaustionLevel")
	float orbit$exhaustion();
}
