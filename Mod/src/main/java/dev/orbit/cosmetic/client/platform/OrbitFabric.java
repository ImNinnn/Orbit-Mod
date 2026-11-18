package dev.orbit.cosmetic.client.platform;

import dev.orbit.cosmetic.client.OrbitClient;
import net.fabricmc.api.ClientModInitializer;

/** Fabric's entrypoint: starts Orbit. */
public final class OrbitFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		new OrbitClient().start();
	}
}
