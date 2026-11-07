package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.common.emote.BodyPose;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/** The emote's body pose for one frame, attached to a player's render state and applied after the model's own pose. */
public record EmoteRenderData(BodyPose body) {
	public static final RenderStateDataKey<EmoteRenderData> KEY = RenderStateDataKey.create(() -> "orbit_cosmetic:emote");
	/** Set on players drawn in Orbit's menus, which always use the vanilla player model. */
	public static final RenderStateDataKey<Boolean> MENU_PREVIEW = RenderStateDataKey.create(() -> "orbit_cosmetic:menu_preview");
}
