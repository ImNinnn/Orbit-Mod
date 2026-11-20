package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
/*import dev.orbit.cosmetic.client.platform.OrbitPlatform;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Orbit's HUD layers that go under the chat, where Fabric API has no way to place them (before 1.21.6 it only calls
 * back after the whole HUD). Since 1.21.6 Fabric API places them itself and this has nothing to do.
 */
@Mixin(Gui.class)
abstract class GuiChatMixin {
	//? if >=1.21 <1.21.6 {
	/*@Inject(method = "renderChat(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void orbit$underChat(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker tick, CallbackInfo ci) {
		OrbitPlatform.drawUnderChat(graphics, tick.getGameTimeDeltaPartialTick(false));
	}
	*///?} else if <1.21 {
	/*// Before 1.21 the HUD draws the chat in its own drawing.
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;III)V"))
	private void orbit$underChat(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
		OrbitPlatform.drawUnderChat(graphics, partialTick);
	}
	*///?}
}
