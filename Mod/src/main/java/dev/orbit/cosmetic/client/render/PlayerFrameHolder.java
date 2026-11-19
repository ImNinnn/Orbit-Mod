package dev.orbit.cosmetic.client.render;

//? if <1.21.2 {
/*import dev.orbit.cosmetic.client.gfx.PlayerState;
*///?}
import org.jspecify.annotations.Nullable;

/**
 * A player render state that carries Orbit's {@link PlayerFrame} (given to Minecraft's class by
 * AvatarRenderStateMixin). Orbit keeps the frame in a field of its own rather than through a loader's API, so it works
 * the same with every Fabric API version (1.21.6's has no render state data) and on every loader. Before 1.21.2 the
 * game has no render states: the player entity carries Orbit's whole state while it is drawn.
 */
public interface PlayerFrameHolder {
	//? if >=1.21.2 {
	@Nullable PlayerFrame orbit$frame();

	void orbit$setFrame(@Nullable PlayerFrame frame);
	//?} else {
	/*@Nullable PlayerState orbit$state();

	void orbit$setState(@Nullable PlayerState state);
	*///?}
}
