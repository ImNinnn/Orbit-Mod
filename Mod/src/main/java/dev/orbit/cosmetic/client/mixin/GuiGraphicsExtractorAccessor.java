package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=1.21.6 {
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else {
/*import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
	//? if >=1.21.6 {
	/** Lets Orbit add its own picture-in-picture states (batches of player previews). */
	@Accessor("guiRenderState")
	GuiRenderState orbit$guiRenderState();
	//?} else {
	/*/^* Draws a texture between two corners with any UVs (before 1.21.6 only this method of the game's does). ^/
	@Invoker("innerBlit")
	void orbit$innerBlit(Identifier texture, int x0, int x1, int y0, int y1, int z, float u0, float u1, float v0, float v1);
	*///?}
}
