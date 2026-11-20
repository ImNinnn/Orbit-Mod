package dev.orbit.cosmetic.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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
	//? if >=26.1 {
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
	//?} else {
	/*// System messages (the game's own and the server's) all arrive here before 26.1.
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
	private void orbit$systemLine(Component message, CallbackInfo ci) {
		Hosting.chatLine(message);
	}
	*///?}

	//? if >=26.1 {
	//?} else if >=1.21.11 {
	/*// The first flag is "focused" (the chat screen passes true): every line, with its background.
	@ModifyVariable(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean orbit$peek(boolean focused) {
		return focused || OrbitKeys.peekingChat();
	}
	*///?} else if >=1.20.2 {
	/*// The flag is "focused" (the chat screen passes true): every line, with its background.
	@ModifyVariable(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean orbit$peek(boolean focused) {
		return focused || OrbitKeys.peekingChat();
	}
	*///?} else {
	/*// Before 1.20.2 the chat asks itself whether it is focused (the chat screen is open).
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;III)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z"))
	private boolean orbit$peek(boolean focused) {
		return focused || OrbitKeys.peekingChat();
	}
	*///?}

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
