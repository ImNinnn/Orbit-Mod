package dev.orbit.cosmetic.client.mixin;

import dev.orbit.cosmetic.client.gui.servers.ServerTabs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Under Orbit's server tabs, a list's top line leaves room for the tabs, as Minecraft's own tab bar does. */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {
	@Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true)
	private void orbit$openUnderTabs(GuiGraphicsExtractor graphics, CallbackInfo info) {
		if (ServerTabs.drawSeparators((AbstractSelectionList<?>) (Object) this, graphics)) {
			info.cancel();
		}
	}
}
