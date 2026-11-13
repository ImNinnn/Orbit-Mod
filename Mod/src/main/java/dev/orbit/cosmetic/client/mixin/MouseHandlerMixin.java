package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.zoom.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While zooming, the scroll wheel zooms instead of changing the selected hotbar slot. Every mouse button pressed is
 * noted for the keystrokes HUD component (Minecraft only keeps the left, middle and right ones).
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void orbit$buttons(long handle, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
		if (handle == Minecraft.getInstance().getWindow().handle()) {
			dev.orbit.cosmetic.client.hud.HudInput.mouseButton(info.button(), action != com.mojang.blaze3d.platform.InputConstants.RELEASE);
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void orbit$scrollZoom(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (handle == Minecraft.getInstance().getWindow().handle() && Compat.screen() == null && Zoom.scroll(yoffset)) {
			ci.cancel();
		}
	}
}
