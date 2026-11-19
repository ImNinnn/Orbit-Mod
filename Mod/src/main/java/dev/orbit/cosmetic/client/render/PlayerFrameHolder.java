package dev.orbit.cosmetic.client.render;

import org.jspecify.annotations.Nullable;

/**
 * A player render state that carries Orbit's {@link PlayerFrame} (given to Minecraft's class by
 * AvatarRenderStateMixin). Orbit keeps the frame in a field of its own rather than through a loader's API, so it works
 * the same with every Fabric API version (1.21.6's has no render state data) and on every loader.
 */
public interface PlayerFrameHolder {
	@Nullable PlayerFrame orbit$frame();

	void orbit$setFrame(@Nullable PlayerFrame frame);
}
