package dev.orbit.cosmetic.client.platform;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.OrbitSettingsScreen;
import dev.orbit.cosmetic.common.util.Development;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge's entry: starts Orbit. NeoForge makes mods while Minecraft is still being made, so Orbit waits for the
 * first of NeoForge's client hooks (when the texture manager, renderers and HUD exist) and starts then, before
 * NeoForge asks for key mappings and HUD layers; {@link OrbitPlatform} hands NeoForge what Orbit registered.
 */
@Mod(value = OrbitClient.MOD_ID, dist = Dist.CLIENT)
public final class OrbitNeoForge {
	public OrbitNeoForge(IEventBus modBus, ModContainer container) {
		OrbitPlatform.listen(modBus);
		modBus.addListener(EventPriority.NORMAL, false, RegisterClientReloadListenersEvent.class, event -> {
			// Test switches only count in development runs.
			Development.set(OrbitPlatform.isDevelopment());
			new OrbitClient().start();
		});
		// The Config button in NeoForge's mod list opens Orbit's settings.
		container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (mod, parent) -> new OrbitSettingsScreen(parent));
	}
}
