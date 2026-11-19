package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.9 {
/*import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.orbit.cosmetic.client.gui.servers.ServerTabs;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Before 1.21.9 the Multiplayer screen draws its title itself, over where Orbit's server tabs are: it is left out
 * while they are shown. (Since 1.21.9 the title is a widget, which ServerTabs hides; nothing to do here.)
 */
@Mixin(JoinMultiplayerScreen.class)
abstract class MultiplayerTitleMixin {
	//? if <1.21.9 {
	/*@WrapWithCondition(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private boolean orbit$title(GuiGraphicsExtractor graphics, Font font, Component title, int x, int y, int color) {
		return !ServerTabs.shownOn((JoinMultiplayerScreen) (Object) this);
	}
	*///?}
}
