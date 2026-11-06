package dev.orbit.cosmetic.client.anim;

import java.util.HashMap;
import java.util.Map;

/**
 * Who is breaking a block: the game shows every player's cracks (its own while mining, others' from the server) by
 * the breaking entity's id, and stops them with a progress outside 0-9. Fed by ClientLevelMixin; game thread only.
 */
public final class MiningTracker {
	/** Cracks the game has not heard about for this long are forgotten, as the game itself does (ticks). */
	private static final long STALE_TICKS = 400;
	/** Breaker entity id: when its cracks last changed (game time). */
	private static final Map<Integer, Long> BREAKING = new HashMap<>();
	private static Object level;

	private MiningTracker() {
	}

	public static void progress(Object currentLevel, int breakerId, int progress, long gameTime) {
		if (currentLevel != level) {
			// Ids belong to one level.
			BREAKING.clear();
			level = currentLevel;
		}
		if (progress >= 0 && progress < 10) {
			BREAKING.put(breakerId, gameTime);
		} else {
			BREAKING.remove(breakerId);
		}
	}

	public static boolean isMining(int entityId, long gameTime) {
		Long since = BREAKING.get(entityId);
		return since != null && gameTime - since < STALE_TICKS;
	}
}
