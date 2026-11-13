package dev.orbit.cosmetic.client.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

/**
 * Draws an entity in a box like the inventory's player, but turned the way a HUD component wants: its body at an
 * angle (the paperdoll's rotation), its head and pitch as given. Minecraft's inventory drawing sets the pose just
 * before drawing; {@link #pose} (called there by a mixin) replaces it while a HUD component draws.
 */
public final class HudDoll {
	private static boolean active;
	private static float bodyRot;
	private static float headRot;
	private static float pitch;
	private static boolean warned;

	private HudDoll() {
	}

	/** Called by the inventory drawing's mixin with the pose it is about to draw. */
	public static void pose(EntityRenderState state) {
		if (active && state instanceof LivingEntityRenderState living) {
			living.bodyRot = bodyRot;
			living.yRot = headRot;
			living.xRot = pitch;
			living.nameTag = null;
		}
	}

	/**
	 * Draws the entity fitted in the box: {@code body} turns it from facing the viewer (degrees), {@code head} turns
	 * its head from its body, {@code headPitch} tilts it.
	 */
	static void draw(GuiGraphicsExtractor graphics, LivingEntity entity, int x0, int y0, int x1, int y1, float body, float head, float headPitch) {
		float height = Math.max(0.3F, entity.getBbHeight());
		float width = Math.max(0.3F, entity.getBbWidth());
		int size = Math.max(1, Math.round(Math.min((y1 - y0) / (height + 0.3F), (x1 - x0) / (width + 0.7F))));
		active = true;
		bodyRot = 180 + body;
		headRot = head;
		pitch = headPitch;
		try {
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x0, y0, x1, y1, size, 0.0625F, (x0 + x1) / 2F, (y0 + y1) / 2F, entity);
		} catch (RuntimeException e) {
			// An entity that cannot be drawn here (another mod's renderer, an entity not in the world yet): left out,
			// rather than stopping the game.
			if (!warned) {
				warned = true;
				dev.orbit.cosmetic.client.OrbitClient.LOGGER.warn("A HUD component could not draw {}: {}", entity.getType(), e.toString());
			}
		} finally {
			active = false;
		}
	}
}
