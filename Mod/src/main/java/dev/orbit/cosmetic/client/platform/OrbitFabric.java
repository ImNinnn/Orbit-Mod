package dev.orbit.cosmetic.client.platform;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.util.Development;
import net.fabricmc.api.ClientModInitializer;

/** Fabric's entrypoint: starts Orbit. */
public final class OrbitFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Test switches only count in development runs.
		Development.set(OrbitPlatform.isDevelopment());
		new OrbitClient().start();
	}
}
