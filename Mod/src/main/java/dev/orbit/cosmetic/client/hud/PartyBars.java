package dev.orbit.cosmetic.client.hud;

import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.social.PartyLink;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Party members' health and hunger in the party component. The vanilla style draws them as the game draws the
 * player's own: its heart types (poisoned, withered, frozen, absorbing, hardcore), more rows closer together with
 * more health or absorption, blinking on loss and gain, shaking when low, the regeneration wave, hungry shanks
 * jittering with no saturation left, and the mount's hearts in place of the shanks while riding; with AppleSkin,
 * its saturation outlines and exhaustion underlay. The other styles colour their bars by the same states and
 * flash where health or hunger was lost.
 */
final class PartyBars {
	private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
	private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
	private static final Identifier FOOD_EMPTY_HUNGER = Identifier.withDefaultNamespace("hud/food_empty_hunger");
	private static final Identifier FOOD_FULL_HUNGER = Identifier.withDefaultNamespace("hud/food_full_hunger");
	private static final Identifier FOOD_HALF_HUNGER = Identifier.withDefaultNamespace("hud/food_half_hunger");
	private static final Identifier MOUNT_CONTAINER = Identifier.withDefaultNamespace("hud/heart/vehicle_container");
	private static final Identifier MOUNT_FULL = Identifier.withDefaultNamespace("hud/heart/vehicle_full");
	private static final Identifier MOUNT_HALF = Identifier.withDefaultNamespace("hud/heart/vehicle_half");
	/** With AppleSkin, members' saturation and exhaustion show on their hunger as AppleSkin shows the player's. */
	static boolean appleSkin = FabricLoader.getInstance().isModLoaded("appleskin");
	static final int SATURATION = 0xFFF5D24A;
	private static final int HEALTH = 0xFFE03A3A;
	private static final int POISONED = 0xFFA3A82B;
	private static final int WITHERED = 0xFF6E6E6E;
	private static final int FROZEN = 0xFF8FD3F5;
	private static final int REGENERATING = 0xFFF27BA6;
	private static final int ABSORBING = 0xFFF2C94C;
	private static final int HUNGER = 0xFFD69A3A;
	private static final int HUNGRY = 0xFF7FA03A;
	/** How long health lost blinks (the game's 20 ticks), and gained (10 ticks); a blink lasts 3 ticks. */
	private static final long LOST_MS = 1000;
	private static final long GAINED_MS = 500;
	private static final long BLINK_MS = 150;
	private static final long TICK_MS = 50;

	/** The game's heart types: the sprites' names start with these (the container's are named differently). */
	private enum Heart {
		NORMAL(""), POISONED("poisoned_"), WITHERED("withered_"), FROZEN("frozen_"), ABSORBING("absorbing_");

		final String prefix;

		Heart(String prefix) {
			this.prefix = prefix;
		}

		Identifier sprite(boolean hardcore, boolean half, boolean blinking) {
			return Identifier.withDefaultNamespace("hud/heart/" + prefix + (hardcore ? "hardcore_" : "") + (half ? "half" : "full") + (blinking ? "_blinking" : ""));
		}

		/** The type a member's hearts are drawn with, as the game picks the player's: poison, then wither, then frozen. */
		static Heart of(PartyLink.Status status) {
			return status.has(PartyLink.POISON) ? POISONED : status.has(PartyLink.WITHER) ? WITHERED : status.has(PartyLink.FROZEN) ? FROZEN : NORMAL;
		}
	}

	private static Identifier container(boolean hardcore, boolean blinking) {
		return Identifier.withDefaultNamespace("hud/heart/container" + (hardcore ? "_hardcore" : "") + (blinking ? "_blinking" : ""));
	}

	/** What was last seen of a member (whole half-hearts, as the game counts), and when it last went down or up. */
	private static final class Track {
		int health;
		/** The health shown blinking: what it was, caught up a second after it last changed. */
		int display;
		long changedAt;
		long blinkUntil;
		int hunger;
		/** Hunger lost: until when it flashes, and what it was. */
		long hungerFlashUntil;
		int hungerBefore;
	}

	private static final Map<UUID, Track> TRACKS = new HashMap<>();

	private PartyBars() {
	}

	/** Whether the member's game shows them health and hunger now (in a world, in survival or adventure). */
	private static boolean shown(PartyLink.@Nullable Status status) {
		return status != null && status.playing() && status.showsHealth();
	}

	/** The member's track, brought up to their latest status, as the game keeps the player's (null when not shown). */
	private static @Nullable Track track(UUID member, PartyLink.@Nullable Status status, long now) {
		if (!shown(status)) {
			TRACKS.remove(member);
			return null;
		}
		int health = Mth.ceil(status.health());
		Track track = TRACKS.get(member);
		if (track == null) {
			track = new Track();
			track.health = health;
			track.display = health;
			track.hunger = status.hunger();
			TRACKS.put(member, track);
			return track;
		}
		if (health < track.health) {
			track.changedAt = now;
			track.blinkUntil = now + LOST_MS;
		} else if (health > track.health) {
			track.changedAt = now;
			track.blinkUntil = now + GAINED_MS;
		}
		if (now - track.changedAt > 1000) {
			track.display = health;
			track.changedAt = now;
		}
		track.health = health;
		if (status.hunger() < track.hunger) {
			track.hungerBefore = now < track.hungerFlashUntil ? Math.max(track.hungerBefore, track.hunger) : track.hunger;
			track.hungerFlashUntil = now + LOST_MS;
		}
		track.hunger = status.hunger();
		return track;
	}

	private static boolean blinkOn(long until, long now) {
		return until > now && (until - now) / BLINK_MS % 2 == 1;
	}

	// ---------------------------------------------------------------- the game's look

	/** The rows of hearts and their spacing, as the game works them out (closer together the more there are). */
	private record Layout(float maxHealth, int absorption, int rows, int rowHeight, int mountHearts) {
		static Layout of(PartyLink.@Nullable Status status, int display) {
			if (!shown(status)) {
				return new Layout(20, 0, 1, 10, 0);
			}
			float maxHealth = Math.max(status.maxHealth(), Math.max(display, Mth.ceil(status.health())));
			int absorption = Mth.ceil(status.absorption());
			int rows = Math.max(1, Mth.ceil((maxHealth + absorption) / 2.0F / 10.0F));
			int mountHearts = status.mountMaxHealth() <= 0 ? 0 : Math.min(30, (int) (status.mountMaxHealth() + 0.5F) / 2);
			return new Layout(maxHealth, absorption, rows, Math.max(10 - (rows - 2), 3), mountHearts);
		}

		int heartsHeight() {
			return 9 + (rows - 1) * rowHeight;
		}

		/** The mount's hearts go up in rows of ten, 10 apart (or the one row of shanks). */
		int rightHeight() {
			return mountHearts == 0 ? 9 : 9 + (Mth.ceil(mountHearts / 10.0) - 1) * 10;
		}

		int height() {
			return Math.max(heartsHeight(), rightHeight());
		}
	}

	/** The height of a member's hearts and shanks in the vanilla style (taller with more rows of hearts). */
	static int vanillaHeight(UUID member, PartyLink.@Nullable Status status) {
		Track track = TRACKS.get(member);
		return Layout.of(status, track == null ? 0 : track.display).height();
	}

	/**
	 * Hearts from {@code left} and shanks (or the mount's hearts) ending at {@code right}, as the game draws the
	 * player's own, in {@code height} from {@code top} (the rows start from its bottom and go up, as the game's).
	 */
	static void drawVanilla(GuiGraphicsExtractor graphics, Font font, UUID member, PartyLink.@Nullable Status status, int left, int top, int right) {
		long now = System.currentTimeMillis();
		Track track = track(member, status, now);
		if (status != null && status.playing() && !status.showsHealth()) {
			// Creative or spectator: the game shows them no health or hunger.
			graphics.text(font, Component.translatable("orbit_cosmetic.hud.party.mode." + status.mode()), left, top + 1, Theme.FAINT, false);
			return;
		}
		Layout layout = Layout.of(status, track == null ? 0 : track.display);
		int base = top + layout.height() - 9;
		long tick = now / TICK_MS;
		Random random = new Random(tick * 312871L ^ member.getLeastSignificantBits());
		drawHearts(graphics, track, status, layout, left, base, now, tick, random);
		if (track != null && layout.mountHearts() > 0) {
			drawMount(graphics, status, layout.mountHearts(), base, right);
		} else {
			drawFood(graphics, status, track != null, base, right, tick, random);
		}
	}

	private static void drawHearts(GuiGraphicsExtractor graphics, @Nullable Track track, PartyLink.@Nullable Status status, Layout layout, int left, int base, long now,
		long tick, Random random) {
		if (track == null) {
			// Not heard from, or out of a world: empty hearts.
			for (int i = 0; i < 10; i++) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, container(false, false), left + i * 8, base, 9, 9);
			}
			return;
		}
		Heart type = Heart.of(status);
		boolean hardcore = status.has(PartyLink.HARDCORE);
		boolean blinking = blinkOn(track.blinkUntil, now);
		int current = track.health;
		int old = track.display;
		int absorption = layout.absorption();
		int containers = Mth.ceil(layout.maxHealth() / 2.0);
		int absorptionContainers = Mth.ceil(absorption / 2.0);
		int maxHalves = containers * 2;
		// The regeneration wave: one heart a tick lifted, along the hearts and a little past them.
		int lifted = status.has(PartyLink.REGENERATION) ? (int) (tick % Mth.ceil(layout.maxHealth() + 5.0F)) : -1;
		for (int i = containers + absorptionContainers - 1; i >= 0; i--) {
			int x = left + i % 10 * 8;
			int y = base - i / 10 * layout.rowHeight();
			if (current + absorption <= 4) {
				y += random.nextInt(2);
			}
			if (i < containers && i == lifted) {
				y -= 2;
			}
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, container(hardcore, blinking), x, y, 9, 9);
			int halves = i * 2;
			if (i >= containers) {
				int absorptionHalves = halves - maxHalves;
				if (absorptionHalves < absorption) {
					Heart shown = type == Heart.WITHERED ? type : Heart.ABSORBING;
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, shown.sprite(hardcore, absorptionHalves + 1 == absorption, false), x, y, 9, 9);
				}
			}
			if (blinking && halves < old) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, type.sprite(hardcore, halves + 1 == old, true), x, y, 9, 9);
			}
			if (halves < current) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, type.sprite(hardcore, halves + 1 == current, false), x, y, 9, 9);
			}
		}
	}

	/** Shanks from the right, hungry ones with the hunger effect; AppleSkin's exhaustion under them, saturation over. */
	private static void drawFood(GuiGraphicsExtractor graphics, PartyLink.@Nullable Status status, boolean known, int y, int right, long tick, Random random) {
		int food = known ? Mth.clamp(status.hunger(), 0, 20) : 0;
		boolean hungry = known && status.has(PartyLink.HUNGER);
		if (known && appleSkin && status.exhaustion() > 0) {
			// AppleSkin: how close the next point of saturation or hunger is to going, from the right.
			int width = (int) (Mth.clamp(status.exhaustion() / 4.0F, 0, 1) * 81);
			graphics.fill(right - width, y, right, y + 9, 0x50D8D8D8);
		}
		boolean jitter = known && status.saturation() <= 0 && tick % (food * 3L + 1) == 0;
		for (int i = 0; i < 10; i++) {
			int foodY = jitter ? y + random.nextInt(3) - 1 : y;
			int x = right - i * 8 - 9;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, hungry ? FOOD_EMPTY_HUNGER : FOOD_EMPTY, x, foodY, 9, 9);
			if (i * 2 + 1 < food) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, hungry ? FOOD_FULL_HUNGER : FOOD_FULL, x, foodY, 9, 9);
			}
			if (i * 2 + 1 == food) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, hungry ? FOOD_HALF_HUNGER : FOOD_HALF, x, foodY, 9, 9);
			}
			if (known && appleSkin) {
				// AppleSkin: each shank's saturation outlined, in quarters.
				float saturation = status.saturation() / 2.0F - i;
				if (saturation > 0) {
					int quarters = saturation >= 1 ? 4 : saturation > 0.5F ? 3 : saturation > 0.25F ? 2 : 1;
					Theme.icon(graphics, "saturation_" + quarters, x, foodY, SATURATION);
				}
			}
		}
	}

	/** While riding something with hearts, the game shows its hearts in place of the shanks: rows of ten going up. */
	private static void drawMount(GuiGraphicsExtractor graphics, PartyLink.Status status, int hearts, int base, int right) {
		int current = (int) Math.ceil(status.mountHealth());
		int y = base;
		for (int from = 0; hearts > 0; from += 20) {
			int row = Math.min(hearts, 10);
			hearts -= row;
			for (int i = 0; i < row; i++) {
				int x = right - i * 8 - 9;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MOUNT_CONTAINER, x, y, 9, 9);
				if (i * 2 + 1 + from < current) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MOUNT_FULL, x, y, 9, 9);
				}
				if (i * 2 + 1 + from == current) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MOUNT_HALF, x, y, 9, 9);
				}
			}
			y -= 10;
		}
	}

	// ---------------------------------------------------------------- bars

	/** The health bar's colour, by what the game would draw the hearts as (regeneration in pink). */
	private static int healthColor(PartyLink.Status status) {
		return switch (Heart.of(status)) {
			case POISONED -> POISONED;
			case WITHERED -> WITHERED;
			case FROZEN -> FROZEN;
			default -> status.has(PartyLink.REGENERATION) ? REGENERATING : HEALTH;
		};
	}

	/**
	 * Health (coloured by the hearts' state, absorption in gold after it) over hunger (amber, green with the hunger
	 * effect, its saturation in gold with AppleSkin); what was just lost flashes white. Creative and spectator
	 * players have none.
	 */
	static void drawBars(GuiGraphicsExtractor graphics, UUID member, PartyLink.@Nullable Status status, int x, int y, int width) {
		long now = System.currentTimeMillis();
		Track track = track(member, status, now);
		if (status != null && status.playing() && !status.showsHealth()) {
			return;
		}
		graphics.fill(x, y, x + width, y + 3, 0x80000000);
		graphics.fill(x, y + 4, x + width, y + 6, 0x80000000);
		if (track == null) {
			return;
		}
		// Health and absorption share the bar as hearts share the rows: the absorption after the health's room.
		float room = Math.max(status.maxHealth(), track.health);
		float total = room + Mth.ceil(status.absorption());
		int health = Math.round(width * Mth.clamp(track.health / total, 0, 1));
		int roomEnd = Math.round(width * Mth.clamp(room / total, 0, 1));
		int color = healthColor(status);
		boolean lost = track.display > track.health && track.blinkUntil > now;
		boolean flash = lost && blinkOn(track.blinkUntil, now);
		graphics.fill(x, y, x + health, y + 3, flash ? Theme.mix(color, 0xFFFFFFFF, 0.45F) : color);
		if (lost) {
			int before = Math.round(width * Mth.clamp(Math.min(track.display, room) / total, 0, 1));
			graphics.fill(x + health, y, x + before, y + 3, flash ? 0xFFFFFFFF : 0x80FFFFFF);
		}
		if (status.absorption() > 0) {
			int absorption = Math.round(width * Mth.clamp(status.absorption() / total, 0, 1));
			graphics.fill(x + roomEnd, y, x + Math.min(width, roomEnd + absorption), y + 3, Heart.of(status) == Heart.WITHERED ? WITHERED : ABSORBING);
		}
		int hunger = Math.round(width * Mth.clamp(status.hunger() / 20F, 0, 1));
		int hungerColor = status.has(PartyLink.HUNGER) ? HUNGRY : HUNGER;
		boolean hungerFlash = blinkOn(track.hungerFlashUntil, now);
		graphics.fill(x, y + 4, x + hunger, y + 6, hungerFlash ? Theme.mix(hungerColor, 0xFFFFFFFF, 0.45F) : hungerColor);
		if (track.hungerFlashUntil > now) {
			int before = Math.round(width * Mth.clamp(track.hungerBefore / 20F, 0, 1));
			graphics.fill(x + hunger, y + 4, x + before, y + 6, hungerFlash ? 0xFFFFFFFF : 0x80FFFFFF);
		}
		if (appleSkin) {
			graphics.fill(x, y + 5, x + Math.round(width * Mth.clamp(status.saturation() / 20F, 0, 1)), y + 6, SATURATION);
		}
	}
}
