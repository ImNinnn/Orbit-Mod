package dev.orbit.cosmetic.client.hud;

import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.social.PartyLink;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Party members' health and hunger in the party component, moving as the game's own do: hearts blink when health
 * is lost or gained (the lost ones shown a moment longer) and shake when it is low, shanks jitter with no
 * saturation left; the bars of the other styles flash where health or hunger was lost.
 */
final class PartyBars {
	private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier HEART_CONTAINER_BLINKING = Identifier.withDefaultNamespace("hud/heart/container_blinking");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final Identifier HEART_FULL_BLINKING = Identifier.withDefaultNamespace("hud/heart/full_blinking");
	private static final Identifier HEART_HALF_BLINKING = Identifier.withDefaultNamespace("hud/heart/half_blinking");
	private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
	private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
	/** With AppleSkin, members' saturation shows on their hunger as AppleSkin shows the player's. */
	static boolean appleSkin = FabricLoader.getInstance().isModLoaded("appleskin");
	static final int SATURATION = 0xFFF5D24A;
	private static final int HEALTH = 0xFFE03A3A;
	private static final int HUNGER = 0xFFD69A3A;
	/** How long health lost blinks (the game's 20 ticks), and gained (10 ticks); a blink lasts 3 ticks. */
	private static final long LOST_MS = 1000;
	private static final long GAINED_MS = 500;
	private static final long BLINK_MS = 150;
	private static final long TICK_MS = 50;

	/** What was last seen of a member, and when their health or hunger last went down or up. */
	private static final class Track {
		float health;
		int hunger;
		/** Health blinks until then; the health shown blinking (what it was before the loss). */
		long healthBlinkUntil;
		float healthBefore;
		/** Hunger lost: until when it flashes, and what it was. */
		long hungerFlashUntil;
		int hungerBefore;
	}

	private static final Map<UUID, Track> TRACKS = new HashMap<>();

	private PartyBars() {
	}

	/** The member's track, brought up to their latest status (null when their game has not said). */
	private static @Nullable Track track(UUID member, PartyLink.@Nullable Status status, long now) {
		if (status == null || !status.playing()) {
			TRACKS.remove(member);
			return null;
		}
		Track track = TRACKS.get(member);
		if (track == null) {
			track = new Track();
			track.health = status.health();
			track.hunger = status.hunger();
			track.healthBefore = status.health();
			TRACKS.put(member, track);
			return track;
		}
		if (status.health() < track.health) {
			// Still blinking from an earlier loss: the health shown blinking stays the highest.
			track.healthBefore = now < track.healthBlinkUntil ? Math.max(track.healthBefore, track.health) : track.health;
			track.healthBlinkUntil = now + LOST_MS;
		} else if (status.health() > track.health) {
			track.healthBefore = status.health();
			track.healthBlinkUntil = now + GAINED_MS;
		}
		if (status.hunger() < track.hunger) {
			track.hungerBefore = now < track.hungerFlashUntil ? Math.max(track.hungerBefore, track.hunger) : track.hunger;
			track.hungerFlashUntil = now + LOST_MS;
		}
		track.health = status.health();
		track.hunger = status.hunger();
		return track;
	}

	private static boolean blinkOn(long until, long now) {
		return until > now && (until - now) / BLINK_MS % 2 == 1;
	}

	/** Health out of 20 halves (more health than 20 is scaled to ten hearts). */
	private static int halves(float health, float max) {
		int halves = Math.round(Mth.clamp(health / Math.max(1, max), 0, 1) * 20);
		return health > 0 ? Math.max(1, halves) : 0;
	}

	/** Hearts from {@code left} and shanks ending at {@code right}, as the game draws the player's own. */
	static void drawVanilla(GuiGraphicsExtractor graphics, UUID member, PartyLink.@Nullable Status status, int left, int y, int right) {
		long now = System.currentTimeMillis();
		Track track = track(member, status, now);
		long tick = now / TICK_MS;
		Random random = new Random(tick * 312871L ^ member.getLeastSignificantBits());
		boolean blinking = track != null && blinkOn(track.healthBlinkUntil, now);
		int halves = track == null ? 0 : halves(status.health(), status.maxHealth());
		int before = track == null || track.healthBlinkUntil <= now ? halves : halves(track.healthBefore, status.maxHealth());
		boolean shaking = track != null && status.health() > 0 && status.health() <= 4;
		for (int i = 0; i < 10; i++) {
			int x = left + i * 8;
			int heartY = shaking ? y + random.nextInt(2) : y;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, blinking ? HEART_CONTAINER_BLINKING : HEART_CONTAINER, x, heartY, 9, 9);
			if (blinking && i * 2 < before) {
				// The health just lost (or gained), blinking under what is left.
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, i * 2 + 1 == before ? HEART_HALF_BLINKING : HEART_FULL_BLINKING, x, heartY, 9, 9);
			}
			if (i * 2 < halves) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, i * 2 + 1 == halves ? HEART_HALF : HEART_FULL, x, heartY, 9, 9);
			}
		}
		int hunger = track == null ? 0 : Mth.clamp(status.hunger(), 0, 20);
		int saturation = track != null && appleSkin ? Mth.clamp(Math.round(status.saturation()), 0, 20) : 0;
		// With no saturation left, the shanks jitter now and then (more often the hungrier they are).
		boolean jitter = track != null && status.saturation() <= 0 && tick % (hunger * 3L + 1) == 0;
		// Shanks fill from the right, as the game's.
		for (int i = 0; i < 10; i++) {
			int x = right - 9 - i * 8;
			int foodY = jitter ? y + random.nextInt(3) - 1 : y;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_EMPTY, x, foodY, 9, 9);
			if (hunger > i * 2 + 1) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_FULL, x, foodY, 9, 9);
			} else if (hunger == i * 2 + 1) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_HALF, x, foodY, 9, 9);
			}
			if (saturation > i * 2 + 1) {
				Theme.icon(graphics, "saturation", x, foodY, SATURATION);
			} else if (saturation == i * 2 + 1) {
				Theme.icon(graphics, "saturation_half", x, foodY, SATURATION);
			}
		}
	}

	/** Health (red) over hunger (amber, its saturation in gold with AppleSkin); what was just lost flashes white. */
	static void drawBars(GuiGraphicsExtractor graphics, UUID member, PartyLink.@Nullable Status status, int x, int y, int width) {
		long now = System.currentTimeMillis();
		Track track = track(member, status, now);
		graphics.fill(x, y, x + width, y + 3, 0x80000000);
		graphics.fill(x, y + 4, x + width, y + 6, 0x80000000);
		if (track == null) {
			return;
		}
		float max = Math.max(1, status.maxHealth());
		int health = Math.round(width * Mth.clamp(status.health() / max, 0, 1));
		boolean healthFlash = status.health() < track.healthBefore && blinkOn(track.healthBlinkUntil, now);
		graphics.fill(x, y, x + health, y + 3, healthFlash ? Theme.mix(HEALTH, 0xFFFFFFFF, 0.45F) : HEALTH);
		if (status.health() < track.healthBefore && track.healthBlinkUntil > now) {
			int before = Math.round(width * Mth.clamp(track.healthBefore / max, 0, 1));
			graphics.fill(x + health, y, x + before, y + 3, healthFlash ? 0xFFFFFFFF : 0x80FFFFFF);
		}
		int hunger = Math.round(width * Mth.clamp(status.hunger() / 20F, 0, 1));
		boolean hungerFlash = blinkOn(track.hungerFlashUntil, now);
		graphics.fill(x, y + 4, x + hunger, y + 6, hungerFlash ? Theme.mix(HUNGER, 0xFFFFFFFF, 0.45F) : HUNGER);
		if (track.hungerFlashUntil > now) {
			int before = Math.round(width * Mth.clamp(track.hungerBefore / 20F, 0, 1));
			graphics.fill(x + hunger, y + 4, x + before, y + 6, hungerFlash ? 0xFFFFFFFF : 0x80FFFFFF);
		}
		if (appleSkin) {
			graphics.fill(x, y + 5, x + Math.round(width * Mth.clamp(status.saturation() / 20F, 0, 1)), y + 6, SATURATION);
		}
	}
}
