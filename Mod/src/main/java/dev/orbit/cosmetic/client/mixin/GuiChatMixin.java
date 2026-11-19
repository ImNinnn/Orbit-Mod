package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
/*import dev.orbit.cosmetic.client.platform.OrbitPlatform;
import net.minecraft.client.DeltaTracker;
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
	//? if <1.21.6 {
	/*@Inject(method = "renderChat(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void orbit$underChat(GuiGraphicsExtractor graphics, DeltaTracker tick, CallbackInfo ci) {
		OrbitPlatform.drawUnderChat(graphics, tick.getGameTimeDeltaPartialTick(false));
	}
	*///?}
}
