package dev.orbit.cosmetic.client.hud;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

/** The last entity the player hit (for the last hit component): shown for 15 seconds after the hit, 2 after it dies. */
public final class HudTargets {
	private static final long SHOWN_MS = 15_000;
	private static final long AFTER_DEATH_MS = 2_000;
	private static WeakReference<LivingEntity> lastHit = new WeakReference<>(null);
	private static long hitAt;
	private static long diedAt;

	private HudTargets() {
	}

	static void register() {
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && entity instanceof LivingEntity living) {
				lastHit = new WeakReference<>(living);
				hitAt = System.currentTimeMillis();
				diedAt = 0;
			}
			return InteractionResult.PASS;
		});
	}

	static @Nullable LivingEntity lastHit() {
		LivingEntity entity = lastHit.get();
		long now = System.currentTimeMillis();
		if (entity == null || now - hitAt > SHOWN_MS) {
			return null;
		}
		if (entity.isRemoved() || entity.isDeadOrDying()) {
			if (diedAt == 0) {
				diedAt = now;
			}
			if (now - diedAt > AFTER_DEATH_MS) {
				lastHit.clear();
				return null;
			}
		}
		return entity;
	}
}
