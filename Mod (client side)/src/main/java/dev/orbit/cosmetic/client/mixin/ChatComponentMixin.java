package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.OrbitKeys;
import dev.orbit.cosmetic.client.host.Hosting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reads the address e4mc announces when a world goes online, to invite friends with it; and shows the whole chat,
 * as when typing, while the chat peek key is held.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
	@Inject(method = "addClientSystemMessage", at = @At("HEAD"))
	private void orbit$clientLine(Component message, CallbackInfo ci) {
		Hosting.chatLine(message);
	}

	@Inject(method = "addServerSystemMessage", at = @At("HEAD"))
	private void orbit$serverLine(Component message, CallbackInfo ci) {
		Hosting.chatLine(message);
	}

	@ModifyVariable(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("HEAD"), argsOnly = true)
	private ChatComponent.DisplayMode orbit$peek(ChatComponent.DisplayMode mode) {
		return mode == ChatComponent.DisplayMode.BACKGROUND && OrbitKeys.peekingChat() ? ChatComponent.DisplayMode.FOREGROUND : mode;
	}

	/**
	 * As many lines as when typing. (Not by claiming the chat is focused: the HUD does not draw the chat at all
	 * while it is, since the chat screen draws it then.)
	 */
	@Inject(method = "getHeight()I", at = @At("HEAD"), cancellable = true)
	private void orbit$peekHeight(CallbackInfoReturnable<Integer> cir) {
		if (OrbitKeys.peekingChat()) {
			cir.setReturnValue(ChatComponent.getHeight(Minecraft.getInstance().options.chatHeightFocused().get()));
		}
	}
}
