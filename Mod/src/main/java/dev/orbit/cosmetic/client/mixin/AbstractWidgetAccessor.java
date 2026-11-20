package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A widget's height can be changed from outside (before 1.20.2 the game has no setHeight). */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
	@Accessor("height")
	void orbit$setHeight(int height);
}
