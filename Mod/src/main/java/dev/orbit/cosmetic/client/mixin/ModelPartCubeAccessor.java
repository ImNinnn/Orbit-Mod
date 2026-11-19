package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a cube's faces, which are not public before 1.21.2 (used to find where the skin's outer layer is). */
@Mixin(ModelPart.Cube.class)
public interface ModelPartCubeAccessor {
	@Accessor("polygons")
	ModelPart.Polygon[] orbit$polygons();
}
