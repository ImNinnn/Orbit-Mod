package dev.orbit.cosmetic.client.platform;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.OrbitSettingsScreen;
import dev.orbit.cosmetic.common.util.Development;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge's entry: starts Orbit. Forge makes mods while Minecraft is still being made, so Orbit waits for the first of
 * Forge's client hooks (when the texture manager, renderers and HUD exist) and starts then, just before Forge asks
 * for key mappings and HUD overlays; {@link OrbitPlatform} hands Forge what Orbit registered.
 */
@Mod(OrbitClient.MOD_ID)
public final class OrbitForge {
	public OrbitForge() {
		// A client mod: on a dedicated server it does nothing.
		if (FMLEnvironment.dist != Dist.CLIENT) {
			return;
		}
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		OrbitPlatform.listen(modBus);
		modBus.addListener(EventPriority.NORMAL, false, RegisterClientReloadListenersEvent.class, event -> {
			// Test switches only count in development runs.
			Development.set(OrbitPlatform.isDevelopment());
			new OrbitClient().start();
		});
		// The Config button in Forge's mod list opens Orbit's settings.
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
			() -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new OrbitSettingsScreen(parent)));
	}
}
