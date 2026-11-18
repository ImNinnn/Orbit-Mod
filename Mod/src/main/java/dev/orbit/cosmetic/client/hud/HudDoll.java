package dev.orbit.cosmetic.client.hud;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import net.minecraft.world.entity.LivingEntity;

/**
 * Draws an entity in a box like the inventory's player, but turned the way a HUD component wants: its body at an
 * angle (the paperdoll's rotation), its head and pitch as given (see {@link OrbitGraphics#entity}).
 */
public final class HudDoll {
	private static boolean warned;

	private HudDoll() {
	}

	/**
	 * Draws the entity fitted in the box: {@code body} turns it from facing the viewer (degrees), {@code head} turns
	 * its head from its body, {@code headPitch} tilts it.
	 */
	static void draw(OrbitGraphics graphics, LivingEntity entity, int x0, int y0, int x1, int y1, float body, float head, float headPitch) {
		float height = Math.max(0.3F, entity.getBbHeight());
		float width = Math.max(0.3F, entity.getBbWidth());
		int size = Math.max(1, Math.round(Math.min((y1 - y0) / (height + 0.3F), (x1 - x0) / (width + 0.7F))));
		try {
			graphics.entity(entity, x0, y0, x1, y1, size, 180 + body, head, headPitch);
		} catch (RuntimeException e) {
			// An entity that cannot be drawn here (another mod's renderer, an entity not in the world yet): left out,
			// rather than stopping the game.
			if (!warned) {
				warned = true;
				dev.orbit.cosmetic.client.OrbitClient.LOGGER.warn("A HUD component could not draw {}: {}", entity.getType(), e.toString());
			}
		}
	}
}
