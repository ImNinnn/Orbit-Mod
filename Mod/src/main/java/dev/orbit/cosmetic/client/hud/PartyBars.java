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
	/** What the component shows of each member (its toggles), as bits. */
	static final int HEALTH_PART = 1;
	static final int HUNGER_PART = 2;
	static final int XP_PART = 4;
	static final int ARMOR_PART = 8;
	static final int AIR_PART = 16;
	static final int ARMOR_SLOTS_PART = 32;
	static final int HELD_PART = 64;
	static final int OFFHAND_PART = 128;
	static final int SLOT_PARTS = ARMOR_SLOTS_PART | HELD_PART | OFFHAND_PART;
	static final int DEFAULT_PARTS = HEALTH_PART | HUNGER_PART | ARMOR_PART | AIR_PART;
	private static final Identifier ARMOR_EMPTY = Identifier.withDefaultNamespace("hud/armor_empty");
	private static final Identifier ARMOR_HALF = Identifier.withDefaultNamespace("hud/armor_half");
	private static final Identifier ARMOR_FULL = Identifier.withDefaultNamespace("hud/armor_full");
	private static final Identifier AIR = Identifier.withDefaultNamespace("hud/air");
	private static final Identifier AIR_BURSTING = Identifier.withDefaultNamespace("hud/air_bursting");
	private static final Identifier AIR_EMPTY = Identifier.withDefaultNamespace("hud/air_empty");
	private static final Identifier XP_BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");
	private static final Identifier XP_PROGRESS = Identifier.withDefaultNamespace("hud/experience_bar_progress");
	private static final int XP = 0xFF80FF20;
	private static final int ARMOR = 0xFFC8CDD3;
	private static final int AIR_COLOR = 0xFF4FA3FF;
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

	/** Whether the game would show the member's air now: under water, or not full again yet. */
	private static boolean airShown(PartyLink.Status status) {
		return status.extra().underwater() || status.extra().air() < status.extra().maxAir();
	}

	/** Whether the row over the hearts and shanks shows: armour points (left) or air (right). */
	private static boolean topRow(PartyLink.@Nullable Status status, int parts) {
		if (!shown(status)) {
			return false;
		}
		return (parts & ARMOR_PART) != 0 && status.extra().armor() > 0 || (parts & AIR_PART) != 0 && airShown(status);
	}

	/**
	 * The height of a member's rows in the vanilla style, under their name: armour and air, hearts and shanks
	 * (taller with more rows of hearts), and the experience bar, as the toggles have them.
	 */
	static int vanillaHeight(UUID member, PartyLink.@Nullable Status status, int parts) {
		if (status != null && status.playing() && !status.showsHealth()) {
			return 9;
		}
		Track track = TRACKS.get(member);
		int height = 0;
		if (topRow(status, parts)) {
			height += 10;
		}
		if ((parts & (HEALTH_PART | HUNGER_PART)) != 0) {
			height += Layout.of(status, track == null ? 0 : track.display).height() + 1;
		}
		if ((parts & XP_PART) != 0) {
			height += 10;
		}
		return Math.max(height, 1);
	}

	/**
	 * A member as the game draws the player's own: armour points over the hearts and air over the shanks, hearts
	 * from {@code left} and shanks (or the mount's hearts) ending at {@code right}, then the experience bar with
	 * its level; each as the toggles have it.
	 */
	static void drawVanilla(GuiGraphicsExtractor graphics, Font font, UUID member, PartyLink.@Nullable Status status, int parts, int left, int top, int right) {
		long now = System.currentTimeMillis();
		Track track = track(member, status, now);
		if (status != null && status.playing() && !status.showsHealth()) {
			// Creative or spectator: the game shows them no health or hunger.
			graphics.text(font, Component.translatable("orbit_cosmetic.hud.party.mode." + status.mode()), left, top + 1, Theme.FAINT, false);
			return;
		}
		long tick = now / TICK_MS;
		Random random = new Random(tick * 312871L ^ member.getLeastSignificantBits());
		int y = top;
		if (topRow(status, parts)) {
			if ((parts & ARMOR_PART) != 0) {
				drawArmor(graphics, status.extra().armor(), left, y);
			}
			if ((parts & AIR_PART) != 0 && airShown(status)) {
				drawAir(graphics, status, right, y, tick, random);
			}
			y += 10;
		}
		if ((parts & (HEALTH_PART | HUNGER_PART)) != 0) {
			Layout layout = Layout.of(status, track == null ? 0 : track.display);
			int base = y + layout.height() - 9;
			if ((parts & HEALTH_PART) != 0) {
				drawHearts(graphics, track, status, layout, left, base, now, tick, random);
			}
			if ((parts & HUNGER_PART) != 0) {
				if (track != null && layout.mountHearts() > 0) {
					drawMount(graphics, status, layout.mountHearts(), base, right);
				} else {
					drawFood(graphics, status, track != null, base, right, tick, random);
				}
			}
			y += layout.height() + 1;
		}
		if ((parts & XP_PART) != 0) {
			drawExperience(graphics, font, status, left, y + 4, right - left);
		}
	}

	/** Armour points, as the game draws them over the hearts (only when there are any). */
	private static void drawArmor(GuiGraphicsExtractor graphics, int armor, int left, int y) {
		if (armor <= 0) {
			return;
		}
		for (int i = 0; i < 10; i++) {
			int x = left + i * 8;
			Identifier sprite = i * 2 + 1 < armor ? ARMOR_FULL : i * 2 + 1 == armor ? ARMOR_HALF : ARMOR_EMPTY;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 9, 9);
		}
	}

	/** Air, as the game draws it over the shanks: full bubbles, the one bursting, and the empty ones wobbling. */
	private static void drawAir(GuiGraphicsExtractor graphics, PartyLink.Status status, int right, int y, long tick, Random random) {
		int max = Math.max(1, status.extra().maxAir());
		int air = Math.clamp(status.extra().air(), 0, max);
		boolean underwater = status.extra().underwater();
		int full = Mth.ceil((air - 2) * 10F / max);
		int bursting = Mth.ceil(air * 10F / max);
		int empty = 10 - Mth.ceil((air + (air != 0 && underwater ? 1 : 0)) * 10F / max);
		for (int bubble = 1; bubble <= 10; bubble++) {
			int x = right - (bubble - 1) * 8 - 9;
			if (bubble <= full) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, AIR, x, y, 9, 9);
			} else if (full != bursting && bubble == bursting && underwater) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, AIR_BURSTING, x, y, 9, 9);
			} else if (bubble > 10 - empty) {
				int wobble = empty == 10 && tick % 2 == 0 ? random.nextInt(2) : 0;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, AIR_EMPTY, x, y + wobble, 9, 9);
			}
		}
	}

	/** The experience bar across the hearts and shanks, its level in green over it, as the game's. */
	private static void drawExperience(GuiGraphicsExtractor graphics, Font font, PartyLink.@Nullable Status status, int left, int y, int width) {
		boolean known = shown(status);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, XP_BACKGROUND, left, y, width, 5);
		int progress = known ? (int) (Mth.clamp(status.extra().xpProgress(), 0, 1) * (width + 1)) : 0;
		if (progress > 0) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, XP_PROGRESS, width, 5, 0, 0, left, y, Math.min(progress, width), 5);
		}
		if (known && status.extra().xpLevel() > 0) {
			Component level = Component.translatable("gui.experience.level", status.extra().xpLevel());
			int x = left + (width - font.width(level)) / 2;
			int ty = y - 4;
			graphics.text(font, level, x + 1, ty, 0xFF000000, false);
			graphics.text(font, level, x - 1, ty, 0xFF000000, false);
			graphics.text(font, level, x, ty + 1, 0xFF000000, false);
			graphics.text(font, level, x, ty - 1, 0xFF000000, false);
			graphics.text(font, level, x, ty, 0xFF80FF20, false);
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

	/** The bars the toggles show, top to bottom: health (3 high), then hunger, experience, armour and air (2 high). */
	static int barsHeight(int parts) {
		int height = 0;
		for (int part : new int[] {HEALTH_PART, HUNGER_PART, XP_PART, ARMOR_PART, AIR_PART}) {
			if ((parts & part) != 0) {
				height += (height == 0 ? 0 : 1) + (part == HEALTH_PART ? 3 : 2);
			}
		}
		return height;
	}

	/**
	 * Health (coloured by the hearts' state, absorption in gold after it), hunger (amber, green with the hunger
	 * effect, its saturation in gold with AppleSkin), experience (green), armour (grey) and air (blue, while it is
	 * not full), as the toggles have them; health and hunger lost flash white. Creative and spectator players have
	 * none.
	 */
	static void drawBars(GuiGraphicsExtractor graphics, UUID member, PartyLink.@Nullable Status status, int parts, int x, int y, int width) {
		long now = System.currentTimeMillis();
		Track track = track(member, status, now);
		if (status != null && status.playing() && !status.showsHealth()) {
			return;
		}
		int row = y;
		for (int part : new int[] {HEALTH_PART, HUNGER_PART, XP_PART, ARMOR_PART, AIR_PART}) {
			if ((parts & part) == 0) {
				continue;
			}
			int height = part == HEALTH_PART ? 3 : 2;
			graphics.fill(x, row, x + width, row + height, 0x80000000);
			if (track != null) {
				switch (part) {
					case HEALTH_PART -> drawHealthBar(graphics, status, track, now, x, row, width);
					case HUNGER_PART -> drawHungerBar(graphics, status, track, now, x, row, width);
					case XP_PART -> graphics.fill(x, row, x + Math.round(width * Mth.clamp(status.extra().xpProgress(), 0, 1)), row + 2, XP);
					case ARMOR_PART -> graphics.fill(x, row, x + Math.round(width * Mth.clamp(status.extra().armor() / 20F, 0, 1)), row + 2, ARMOR);
					default -> {
						if (airShown(status)) {
							float air = Mth.clamp(status.extra().air() / (float) Math.max(1, status.extra().maxAir()), 0, 1);
							graphics.fill(x, row, x + Math.round(width * air), row + 2, AIR_COLOR);
						}
					}
				}
			}
			row += height + 1;
		}
	}

	private static void drawHealthBar(GuiGraphicsExtractor graphics, PartyLink.Status status, Track track, long now, int x, int y, int width) {
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
	}

	private static void drawHungerBar(GuiGraphicsExtractor graphics, PartyLink.Status status, Track track, long now, int x, int y, int width) {
		int hunger = Math.round(width * Mth.clamp(status.hunger() / 20F, 0, 1));
		int hungerColor = status.has(PartyLink.HUNGER) ? HUNGRY : HUNGER;
		boolean hungerFlash = blinkOn(track.hungerFlashUntil, now);
		graphics.fill(x, y, x + hunger, y + 2, hungerFlash ? Theme.mix(hungerColor, 0xFFFFFFFF, 0.45F) : hungerColor);
		if (track.hungerFlashUntil > now) {
			int before = Math.round(width * Mth.clamp(track.hungerBefore / 20F, 0, 1));
			graphics.fill(x + hunger, y, x + before, y + 2, hungerFlash ? 0xFFFFFFFF : 0x80FFFFFF);
		}
		if (appleSkin) {
			graphics.fill(x, y + 1, x + Math.round(width * Mth.clamp(status.saturation() / 20F, 0, 1)), y + 2, SATURATION);
		}
	}

	// ---------------------------------------------------------------- items

	/** The slots shown, left to right: off hand, main hand, then the armour from head to feet. */
	private static final String[][] SLOT_GROUPS = {{"offhand"}, {"mainhand"}, {"head", "chest", "legs", "feet"}};
	private static final int[] SLOT_GROUP_PARTS = {OFFHAND_PART, HELD_PART, ARMOR_SLOTS_PART};
	/** Items made from what members' games sent (by what was sent), so they are not made again every frame. */
	private static final Map<String, net.minecraft.world.item.ItemStack> STACKS = new HashMap<>();

	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
	private static final Identifier HOTBAR_OFFHAND = Identifier.withDefaultNamespace("hud/hotbar_offhand_left");

	/** How wide the slots are: flat squares, or the game's hotbar slots (the off hand's own) in the vanilla style. */
	static int slotsWidth(int parts, boolean vanilla) {
		int width = 0;
		for (int group = 0; group < SLOT_GROUPS.length; group++) {
			if ((parts & SLOT_GROUP_PARTS[group]) != 0) {
				int slots = SLOT_GROUPS[group].length;
				width += vanilla ? (group == 0 ? 29 : slots * 20 + 2) + 3 : slots * 19 + 3;
			}
		}
		return width;
	}

	static int slotsHeight(int parts, boolean vanilla) {
		return (parts & SLOT_PARTS) == 0 ? 0 : vanilla ? 25 : 19;
	}

	/** The member's items in slots, as the toggles have them (empty slots while not known). */
	static void drawSlots(GuiGraphicsExtractor graphics, Font font, PartyLink.@Nullable Status status, int parts, int x, int y, boolean itemsReady, boolean vanilla) {
		boolean known = itemsReady && status != null && status.playing();
		for (int group = 0; group < SLOT_GROUPS.length; group++) {
			if ((parts & SLOT_GROUP_PARTS[group]) == 0) {
				continue;
			}
			String[] slots = SLOT_GROUPS[group];
			if (vanilla && group == 0) {
				// The off hand's own slot, as the game draws it left of the hotbar.
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR_OFFHAND, x, y, 29, 24);
				drawItem(graphics, font, known ? stack(status.extra().items().get(slots[0])) : null, x + 3, y + 4);
				x += 29 + 3;
				continue;
			}
			if (vanilla) {
				// A piece of the game's hotbar, as many slots long, with its right edge.
				int width = 1 + slots.length * 20;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 0, 0, x, y + 1, width, 22);
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 181, 0, x + width, y + 1, 1, 22);
				for (int i = 0; i < slots.length; i++) {
					drawItem(graphics, font, known ? stack(status.extra().items().get(slots[i])) : null, x + 3 + i * 20, y + 4);
				}
				x += width + 1 + 3;
				continue;
			}
			for (String slot : slots) {
				graphics.fill(x, y, x + 18, y + 18, 0x60000000);
				graphics.outline(x, y, 18, 18, 0x60FFFFFF);
				drawItem(graphics, font, known ? stack(status.extra().items().get(slot)) : null, x + 1, y + 1);
				x += 19;
			}
			x += 3;
		}
	}

	private static void drawItem(GuiGraphicsExtractor graphics, Font font, net.minecraft.world.item.@Nullable ItemStack stack, int x, int y) {
		if (stack != null) {
			graphics.item(stack, x, y);
			graphics.itemDecorations(font, stack, x, y);
		}
	}

	/** The item a member's game described (its ID, count, damage and glint), or null when there is none. */
	private static net.minecraft.world.item.@Nullable ItemStack stack(com.google.gson.@Nullable JsonObject item) {
		if (item == null || !item.has("id")) {
			return null;
		}
		String key = item.toString();
		var made = STACKS.get(key);
		if (made != null) {
			return made;
		}
		Identifier id = Identifier.tryParse(item.get("id").getAsString());
		if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
			return null;
		}
		var stack = new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id), Math.max(1, item.has("count") ? item.get("count").getAsInt() : 1));
		if (stack.isDamageableItem() && item.has("damage")) {
			stack.setDamageValue(item.get("damage").getAsInt());
		}
		if (item.has("glint") && item.get("glint").getAsBoolean()) {
			stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		if (STACKS.size() > 256) {
			STACKS.clear();
		}
		STACKS.put(key, stack);
		return stack;
	}
}
