package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.screenshot.ScreenshotToasts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.io.File;
import java.util.function.Consumer;

/** A saved screenshot shows as Orbit's screenshot notification instead of its name in the chat. */
@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
	@ModifyVariable(
		method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private static Consumer<Component> orbit$showAsNotification(Consumer<Component> callback) {
		if (!OrbitClient.get().config().screenshotNotifications) {
			return callback;
		}
		return message -> {
			File file = savedFile(message);
			if (file == null) {
				callback.accept(message);
			} else {
				Minecraft.getInstance().execute(() -> {
					dev.orbit.cosmetic.client.OrbitClient.get().social().addScreenshot();
					ScreenshotToasts.show(file.toPath());
				});
			}
		};
	}

	/** The file of a "screenshot saved" message (its name links to it), or null for anything else. */
	private static File savedFile(Component message) {
		if (message.getContents() instanceof TranslatableContents contents && contents.getKey().equals("screenshot.success") && contents.getArgs().length > 0
			&& contents.getArgs()[0] instanceof Component name && name.getStyle().getClickEvent() instanceof ClickEvent.OpenFile open) {
			return open.file();
		}
		return null;
	}
}
