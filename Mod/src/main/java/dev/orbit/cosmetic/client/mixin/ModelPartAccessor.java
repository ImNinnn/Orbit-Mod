package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * Read access to a model part's children, used to find the path from the model root to a body part, and to its
 * cubes, used to find where the skin's outer layer is.
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("children")
	Map<String, ModelPart> orbit$children();

	@Accessor("cubes")
	List<ModelPart.Cube> orbit$cubes();
}
