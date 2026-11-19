package dev.orbit.cosmetic.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Leaves out the mixins Orbit only needs on some installations: its own world-drawing hook (LevelRendererMixin)
 * only where Fabric API has no world render events.
 */
public final class OrbitMixinPlugin implements IMixinConfigPlugin {
	/** Fabric API's world render events: in rendering/v1 before 1.21.9, "world" until 26.1, "level" since. */
	private static final String[] FABRIC_WORLD_EVENTS = {
		"net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents.class",
		"net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderEvents.class",
		"net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents.class"};

	/** True when the installed Fabric API can tell Orbit when the world is drawn (no class is loaded to find out). */
	public static boolean hasFabricWorldEvents() {
		ClassLoader loader = OrbitMixinPlugin.class.getClassLoader();
		for (String name : FABRIC_WORLD_EVENTS) {
			if (loader.getResource(name) != null) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".LevelRendererMixin")) {
			return !hasFabricWorldEvents();
		}
		return true;
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
