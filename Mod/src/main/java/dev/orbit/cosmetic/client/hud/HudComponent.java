package dev.orbit.cosmetic.client.hud;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.mixin.MultiPlayerGameModeAccessor;
import dev.orbit.cosmetic.client.zoom.Zoom;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One HUD component placed by the player: what it shows (its type and style), where, how big, and how it looks.
 * Drawn in its own unscaled size, then scaled.
 * <p>
 * Where: across, the point it holds on to is its left edge, its middle or its right edge (whichever third of the
 * screen it is in), as a fraction of the screen's width, so a component whose text gets longer grows away from the
 * nearest side; down, a fraction of the room left above and under it.
 */
public final class HudComponent {
	/** What a component shows, and its styles (the first is the default). */
	public enum Type {
		FPS(false),
		COORDINATES(true, "stacked", "line"),
		ROTATION(true, "stacked", "line"),
		KEYSTROKES(false, "movement", "all_keys"),
		CPS_MOUSE(false, "both", "cps", "mouse"),
		PAPERDOLL(true),
		INVENTORY(true, "full", "top_row", "middle_row", "bottom_row"),
		COMPASS(true, "ribbon", "long"),
		ARMOR(true, "full", "hotbar"),
		ITEM(true, "main_hand", "off_hand"),
		TIME(false, "game_time", "real_time"),
		LOOKING_AT(true),
		LAST_HIT(true);

		/** Needs a world (shown only in one). */
		final boolean world;
		final String[] variants;

		Type(boolean world, String... variants) {
			this.world = world;
			this.variants = variants;
		}

		public Component label() {
			return Component.translatable("orbit_cosmetic.hud.type." + name().toLowerCase(Locale.ROOT));
		}

		public boolean hasVariants() {
			return variants.length > 1;
		}

		public Component variantLabel(int variant) {
			return Component.translatable("orbit_cosmetic.hud.variant." + variants[Math.floorMod(variant, variants.length)]);
		}
	}

	/** What the full armour and item components write next to an item. */
	public enum Detail {
		DURABILITY, PERCENT, NAME;

		public Component label() {
			return Component.translatable("orbit_cosmetic.hud.detail." + name().toLowerCase(Locale.ROOT));
		}
	}

	public static final float MIN_SCALE = 0.5F;
	public static final float MAX_SCALE = 4;
	public static final int DEFAULT_ACCENT = 0xFF2626;
	/** Which point across a component holds on to. */
	public static final int LEFT = 0;
	public static final int CENTER = 1;
	public static final int RIGHT = 2;
	/** Which side of the member it is linked to a component is on. */
	public static final int SIDE_RIGHT = 0;
	public static final int SIDE_LEFT = 1;
	public static final int SIDE_BELOW = 2;
	public static final int SIDE_ABOVE = 3;
	public static final int AVOID_EFFECTS = 1;
	public static final int AVOID_BOSS_BARS = 2;
	public static final int DOCK_NONE = 0;
	public static final int DOCK_OPPOSITE = 1;
	public static final int DOCK_BESIDE = 2;
	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** The full armour component's text where there are no items to show (no world yet). */
	private static final String[] SAMPLE_ARMOR = {"327/363", "290/528", "180/225", "39/195"};

	public final UUID id;
	public final Type type;
	/** Across: {@link #LEFT}, {@link #CENTER} or {@link #RIGHT}, and where that point is (a fraction of the width). */
	public int anchor = LEFT;
	public float ax;
	/** Down: a fraction of the room left above and under it. */
	public float fy;
	/** A position saved by Orbit 1.0.5 and older (a fraction of the room across), turned into an anchor when first used. */
	private float legacyX = -1;
	public float scale = 1;
	public int variant;
	public Detail detail = Detail.DURABILITY;
	public boolean inMenus;
	public boolean background = true;
	/** Hidden (from the quick menu or its settings): kept in the layout, not drawn. */
	public boolean hidden;
	public int color = 0xFFFFFF;
	/** Replaces Orbit's red: the bar on the left, labels, pressed keys, the compass's marker. */
	public int accent = DEFAULT_ACCENT;
	/** The paperdoll's turn from facing the viewer, in degrees. */
	public int rotation = 20;
	/** In every HUD profile (with its whole linked group). */
	public boolean pinned;
	/** Armour and held item: hidden when there is nothing to show. */
	public boolean hideEmpty;
	/** Armour: only the slots with something in them. */
	public boolean hideEmptySlots;
	/** Moves down out of the way of the status effect icons ({@link #AVOID_EFFECTS}) and/or the boss bars ({@link #AVOID_BOSS_BARS}). */
	public int avoid;
	/** Linked: the member it is attached to, on which side, how far from it and how far along (in that member's pixels). */
	public @Nullable UUID parent;
	public int side = SIDE_RIGHT;
	public float gap;
	public float offset;
	/** Hotbar armour docked beside the hotbar: {@link #DOCK_NONE}, opposite the off hand slot, or beside it. */
	public int dock = DOCK_NONE;
	/** Whether it is a sample (the add menu's cards): sample values even in a world. */
	private boolean sample;

	public HudComponent(UUID id, Type type) {
		this.id = id;
		this.type = type;
	}

	/** A component to show as an example (the add menu's cards). */
	static HudComponent sample(Type type) {
		HudComponent component = new HudComponent(new UUID(0, type.ordinal()), type);
		component.sample = true;
		return component;
	}

	public String variantId() {
		return type.variants.length == 0 ? "" : type.variants[Math.floorMod(variant, type.variants.length)];
	}

	private boolean is(String variantId) {
		return variantId().equals(variantId);
	}

	public boolean isLongCompass() {
		return type == Type.COMPASS && is("long");
	}

	public boolean isArmorHotbar() {
		return type == Type.ARMOR && is("hotbar");
	}

	/** Whether the full armour or item component's text setting applies. */
	public boolean hasDetail() {
		return type == Type.ITEM || type == Type.ARMOR && is("full");
	}

	private @Nullable LocalPlayer player() {
		return sample ? null : Minecraft.getInstance().player;
	}

	/** Items can only be made in a world (before one, their data is not ready). */
	private static boolean itemsReady() {
		return Minecraft.getInstance().level != null;
	}

	private static Font font() {
		return Minecraft.getInstance().font;
	}

	/** Rounded up, so text that changes a little does not resize the component every frame. */
	private static int steady(int width) {
		return (width + 7) / 8 * 8;
	}

	public int width() {
		Font font = font();
		return switch (type) {
			case FPS -> Math.max(48, font.width(fpsText()) + 12);
			case COORDINATES -> is("line") ? steady(lineWidth(coordinateParts())) : Math.max(60, steady(stackedWidth(coordinateParts())));
			case ROTATION -> is("line") ? steady(lineWidth(rotationParts())) : Math.max(70, steady(stackedWidth(rotationParts())));
			case KEYSTROKES -> is("all_keys") ? 150 : 58;
			case CPS_MOUSE -> is("mouse") ? 24 : 58;
			case PAPERDOLL -> 44;
			case INVENTORY -> 166;
			case COMPASS -> isLongCompass() && !sample ? (int) Math.ceil(Minecraft.getInstance().getWindow().getGuiScaledWidth() / scale) : 150;
			case ARMOR -> {
				if (is("hotbar")) {
					yield Math.max(1, armorSlots().size()) * 20 + 2;
				}
				int widest = 0;
				String[] texts = armorTexts();
				for (int slot : armorSlots()) {
					widest = Math.max(widest, font.width(texts[slot]));
				}
				yield Math.max(60, steady(widest + 28));
			}
			case ITEM -> Math.max(60, steady(font.width(itemText()) + 28));
			case TIME -> steady(lineWidth(timeParts()));
			case LOOKING_AT -> {
				Look look = look();
				if (look == null) {
					// The sample: Stone, Minecraft and a pickaxe.
					yield Math.max(80, steady(font.width("Minecraft") + 34 + 20));
				}
				// The name and the health (or "Unbreakable") on one line, the mod under them.
				int first = font.width(look.name()) + (look.info().getString().isEmpty() ? 0 : 5 + font.width(look.info()));
				int widest = Math.max(first, font.width(look.mod()));
				yield Math.max(80, steady(widest + 34 + (look.tool() != null ? 20 : 0)));
			}
			case LAST_HIT -> {
				LivingEntity target = lastHit();
				int name = font.width(target != null ? target.getDisplayName() : Component.translatable("orbit_cosmetic.hud.sample.zombie"));
				yield Math.max(110, steady(name + 44));
			}
		};
	}

	public int height() {
		return switch (type) {
			case FPS, TIME -> 14;
			case COORDINATES -> is("line") ? 14 : 34;
			case ROTATION -> is("line") ? 14 : 34;
			case KEYSTROKES -> is("all_keys") ? 16 : 64;
			case CPS_MOUSE -> is("mouse") ? 24 : 16;
			case PAPERDOLL -> 72;
			case INVENTORY -> inventoryRows().length * 18 + 4;
			case COMPASS -> is("long") ? 16 : 20;
			case ARMOR -> is("hotbar") ? 22 : Math.max(1, armorSlots().size()) * 18 + 4;
			case ITEM -> 20;
			case LOOKING_AT -> 24;
			case LAST_HIT -> 42;
		};
	}

	public int scaledWidth() {
		return Math.round(width() * scale);
	}

	public int scaledHeight() {
		return Math.round(height() * scale);
	}

	/** Where it is across (its group and docking included). */
	public int x(int screenWidth) {
		return HudLayout.position(this, screenWidth, Minecraft.getInstance().getWindow().getGuiScaledHeight())[0];
	}

	/** Where it is down (its group and docking included). */
	public int y(int screenHeight) {
		return HudLayout.position(this, Minecraft.getInstance().getWindow().getGuiScaledWidth(), screenHeight)[1];
	}

	/** Where it was put across, on its own. */
	int ownX(int screenWidth) {
		int w = scaledWidth();
		if (legacyX >= 0) {
			// A position from before anchors: as it was, then held the new way.
			int x = Math.round(legacyX * Math.max(0, screenWidth - w));
			legacyX = -1;
			anchor = LEFT;
			ax = 0;
			placeX(x, screenWidth);
		}
		float point = ax * screenWidth;
		int x = Math.round(switch (anchor) {
			case RIGHT -> point - w;
			case CENTER -> point - w / 2F;
			default -> point;
		});
		return Math.clamp(x, 0, Math.max(0, screenWidth - w));
	}

	/** Where it was put down, on its own. */
	int ownY(int screenHeight) {
		return Math.round(fy * Math.max(0, screenHeight - scaledHeight()));
	}

	/** Puts its top left corner at this place on a screen of this size (undocking it). */
	public void place(int x, int y, int screenWidth, int screenHeight) {
		legacyX = -1;
		dock = DOCK_NONE;
		placeX(x, screenWidth);
		int roomY = screenHeight - scaledHeight();
		fy = roomY <= 0 ? 0 : Mth.clamp(y / (float) roomY, 0, 1);
	}

	/** Holds on to its left edge, middle or right edge, whichever third of the screen it is in (or the edge it touches). */
	private void placeX(int x, int screenWidth) {
		int w = scaledWidth();
		float middle = x + w / 2F;
		if (screenWidth <= 0) {
			return;
		}
		if (x <= 0 || middle < screenWidth / 3F && x + w < screenWidth) {
			anchor = LEFT;
			ax = Math.max(0, x) / (float) screenWidth;
		} else if (x + w >= screenWidth || middle > screenWidth * 2 / 3F) {
			anchor = RIGHT;
			ax = Math.min(screenWidth, x + w) / (float) screenWidth;
		} else {
			anchor = CENTER;
			ax = middle / screenWidth;
		}
	}

	/** Whether it can show now: world components need a world. */
	public boolean available() {
		return !type.world || Minecraft.getInstance().player != null;
	}

	// ---------------------------------------------------------------- drawing

	/** Draws it at its place; {@code preview} shows sample values where there is nothing to show (the editor). */
	public void draw(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight, boolean preview, float partialTick) {
		if (!preview && !hasContent()) {
			return;
		}
		drawAt(graphics, x(screenWidth), y(screenHeight), scale, preview);
	}

	/** Whether there is something to show in game (the "looking at" and last hit components show only then). */
	private boolean hasContent() {
		return switch (type) {
			case LOOKING_AT -> look() != null;
			case LAST_HIT -> lastHit() != null;
			case ARMOR -> !(hideEmpty || hideEmptySlots) || !armorSlots().isEmpty() && armorSlots().stream().anyMatch(slot -> {
				ItemStack stack = armor()[slot];
				return stack == null || !stack.isEmpty();
			});
			case ITEM -> {
				ItemStack stack = heldItem();
				yield !hideEmpty || stack == null || !stack.isEmpty();
			}
			default -> true;
		};
	}

	/** Draws it with its top left corner here, at this scale. */
	public void drawAt(GuiGraphicsExtractor graphics, int x, int y, float scale, boolean preview) {
		if (type == Type.PAPERDOLL) {
			// The player model is drawn into its own picture, in screen pixels: not scaled with the pose.
			int w = Math.round(width() * scale);
			int h = Math.round(height() * scale);
			drawFrame(graphics, x, y, w, h, Math.max(1, Math.round(scale)));
			LocalPlayer player = player();
			if (player != null) {
				// Held to the body: turned to its angle, swinging a little as the player turns, the head as the player's.
				float head = Math.clamp(Mth.wrapDegrees(player.yHeadRot - player.yBodyRot), -75, 75);
				HudDoll.draw(graphics, player, x + 2, y + 2, x + w - 2, y + h - 2, rotation + HudInput.sway(), head, player.getXRot());
			} else if (preview) {
				drawDollPlaceholder(graphics, x, y, w, h);
			}
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		drawScaled(graphics, preview, x, y, scale);
		graphics.pose().popMatrix();
	}

	/** A plain figure where there is no entity to show (the title screen, the add menu). */
	private static void drawDollPlaceholder(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		int unit = Math.max(1, Math.min(h / 36, w / 18));
		int cx = x + w / 2;
		int top = y + (h - 32 * unit) / 2;
		graphics.fill(cx - 4 * unit, top, cx + 4 * unit, top + 8 * unit, 0xC0A3A3AD);
		graphics.fill(cx - 4 * unit, top + 8 * unit, cx + 4 * unit, top + 20 * unit, 0x80A3A3AD);
		graphics.fill(cx - 8 * unit, top + 8 * unit, cx - 4 * unit, top + 20 * unit, 0x60A3A3AD);
		graphics.fill(cx + 4 * unit, top + 8 * unit, cx + 8 * unit, top + 20 * unit, 0x60A3A3AD);
		graphics.fill(cx - 4 * unit, top + 20 * unit, cx, top + 32 * unit, 0x70A3A3AD);
		graphics.fill(cx, top + 20 * unit, cx + 4 * unit, top + 32 * unit, 0x70A3A3AD);
	}

	private int text() {
		return 0xFF000000 | color;
	}

	private int accentColor() {
		return 0xFF000000 | accent;
	}

	/** The background: a dark panel with the accent bar on its left (when turned on). */
	private void drawFrame(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int unit) {
		if (!background) {
			return;
		}
		graphics.fill(x, y, x + w, y + h, 0xB01C1C20);
		graphics.outline(x, y, w, h, 0xC02E2E35);
		graphics.fill(x, y, x + 2 * unit, y + h, accentColor());
	}

	/**
	 * Draws it in its own pixels (the pose is moved and scaled). {@code screenX}, {@code screenY} and {@code scale}
	 * are for the entity pictures, drawn in screen pixels.
	 */
	private void drawScaled(GuiGraphicsExtractor graphics, boolean preview, int screenX, int screenY, float scale) {
		Font font = font();
		LocalPlayer player = player();
		int w = width();
		int h = height();
		switch (type) {
			case FPS -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				graphics.text(font, fpsText(), 6, 3, text(), false);
			}
			case COORDINATES, ROTATION, TIME -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				List<String[]> parts = type == Type.COORDINATES ? coordinateParts() : type == Type.ROTATION ? rotationParts() : timeParts();
				if (type == Type.TIME || is("line")) {
					int x = 6;
					for (String[] part : parts) {
						x = drawPart(graphics, font, part, x, 3) + 7;
					}
				} else {
					int labelWidth = labelWidth(parts);
					for (int i = 0; i < parts.size(); i++) {
						String[] part = parts.get(i);
						if (part[1].isEmpty()) {
							graphics.text(font, part[0], 6, 3 + i * 10, Theme.MUTED, false);
						} else {
							graphics.text(font, part[0], 6, 3 + i * 10, accentColor(), false);
							graphics.text(font, part[1], 6 + labelWidth + 4, 3 + i * 10, text(), false);
						}
					}
				}
			}
			case KEYSTROKES -> {
				if (is("all_keys")) {
					drawAllKeys(graphics, font, preview);
				} else {
					drawKeystrokes(graphics, font);
				}
			}
			case CPS_MOUSE -> drawCps(graphics, font);
			case INVENTORY -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				int[] rows = inventoryRows();
				for (int r = 0; r < rows.length; r++) {
					for (int column = 0; column < 9; column++) {
						int i = rows[r] * 9 + column;
						int sx = 4 + column * 18;
						int sy = 2 + r * 18;
						graphics.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
						ItemStack stack = player == null ? sampleInventory(i) : player.getInventory().getItem(9 + i);
						if (!stack.isEmpty()) {
							graphics.item(stack, sx, sy);
							graphics.itemDecorations(font, stack, sx, sy);
						}
					}
				}
			}
			case COMPASS -> {
				float yRot = player == null ? 30 : player.getYRot();
				if (is("long")) {
					drawLongCompass(graphics, font, yRot);
				} else {
					drawCompass(graphics, font, yRot);
				}
			}
			case ARMOR -> {
				if (is("hotbar")) {
					drawArmorHotbar(graphics, font);
				} else {
					drawFrame(graphics, 0, 0, w, h, 1);
					ItemStack[] armor = armor();
					String[] texts = armorTexts();
					List<Integer> slots = armorSlots();
					for (int row = 0; row < slots.size(); row++) {
						int i = slots.get(row);
						boolean low = armor[i] == null ? i == 3 : isLow(armor[i]);
						drawItemRow(graphics, font, armor[i], texts[i], low, 5, 2 + row * 18);
					}
				}
			}
			case ITEM -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				ItemStack stack = heldItem();
				drawItemRow(graphics, font, stack, itemText(), stack != null && isLow(stack), 5, 2);
			}
			case LOOKING_AT -> drawLookingAt(graphics, font, preview, screenX, screenY, scale);
			case LAST_HIT -> drawLastHit(graphics, font, screenX, screenY, scale);
			default -> {
			}
		}
	}

	/** An accent label and a value, at x; returns where it ends. */
	private int drawPart(GuiGraphicsExtractor graphics, Font font, String[] part, int x, int y) {
		if (part[1].isEmpty()) {
			graphics.text(font, part[0], x, y, Theme.MUTED, false);
			return x + font.width(part[0]);
		}
		graphics.text(font, part[0], x, y, accentColor(), false);
		x += font.width(part[0]) + 3;
		graphics.text(font, part[1], x, y, text(), false);
		return x + font.width(part[1]);
	}

	private static int lineWidth(List<String[]> parts) {
		Font font = font();
		int width = 12 - 7;
		for (String[] part : parts) {
			width += font.width(part[0]) + (part[1].isEmpty() ? 0 : 3 + font.width(part[1])) + 7;
		}
		return width;
	}

	private static int labelWidth(List<String[]> parts) {
		int width = 0;
		for (String[] part : parts) {
			if (!part[1].isEmpty()) {
				width = Math.max(width, font().width(part[0]));
			}
		}
		return width;
	}

	/** One part a line, the values lined up after the widest label (a part without a value takes the line alone). */
	private static int stackedWidth(List<String[]> parts) {
		Font font = font();
		int labels = labelWidth(parts);
		int width = 0;
		for (String[] part : parts) {
			width = Math.max(width, part[1].isEmpty() ? font.width(part[0]) : labels + 4 + font.width(part[1]));
		}
		return width + 12;
	}

	/** X, Y and Z, as labels and values. */
	private List<String[]> coordinateParts() {
		LocalPlayer player = player();
		double[] pos = player == null ? new double[] {128.5, 64, -42.5} : new double[] {player.getX(), player.getY(), player.getZ()};
		String[] axes = {"X", "Y", "Z"};
		String[][] parts = new String[3][];
		for (int i = 0; i < 3; i++) {
			parts[i] = new String[] {axes[i], String.format(Locale.ROOT, "%.1f", pos[i])};
		}
		return List.of(parts);
	}

	/** Yaw, pitch and the way the player faces. */
	private List<String[]> rotationParts() {
		LocalPlayer player = player();
		float yaw = player == null ? 90 : Mth.wrapDegrees(player.getYRot());
		float pitch = player == null ? 12 : player.getXRot();
		String facing = Component.translatable((is("line") ? "orbit_cosmetic.hud.dir." : "orbit_cosmetic.hud.facing.") + facing(yaw)).getString();
		return List.of(new String[] {Component.translatable("orbit_cosmetic.hud.yaw").getString(), String.format(Locale.ROOT, "%.1f°", yaw)},
			new String[] {Component.translatable("orbit_cosmetic.hud.pitch").getString(), String.format(Locale.ROOT, "%.1f°", pitch)},
			new String[] {facing, ""});
	}

	/** The world's day and time of day, or the time on the computer's clock. */
	private List<String[]> timeParts() {
		if (is("real_time")) {
			LocalTime now = sample ? LocalTime.of(21, 4) : LocalTime.now();
			return List.<String[]>of(new String[] {Component.translatable("orbit_cosmetic.hud.time.real").getString(), String.format(Locale.ROOT, "%02d:%02d", now.getHour(),
				now.getMinute())});
		}
		var level = Minecraft.getInstance().level;
		// The sample: day 12, 13:45.
		long ticks = sample || level == null ? 11 * 24000 + 7750 : level.getDefaultClockTime();
		long day = ticks / 24000 + 1;
		// Tick 0 is six in the morning.
		long ofDay = Math.floorMod(ticks, 24000L);
		int hours = (int) ((ofDay / 1000 + 6) % 24);
		int minutes = (int) (ofDay % 1000 * 60 / 1000);
		return List.<String[]>of(new String[] {Component.translatable("orbit_cosmetic.hud.time.day", day).getString(), String.format(Locale.ROOT, "%02d:%02d", hours, minutes)});
	}

	/** W, A S D, the space bar, Sneak and Sprint: keys light up in the accent colour while pressed. */
	private void drawKeystrokes(GuiGraphicsExtractor graphics, Font font) {
		var options = Minecraft.getInstance().options;
		key(graphics, font, options.keyUp, 20, 0, 18, 18, null);
		key(graphics, font, options.keyLeft, 0, 20, 18, 18, null);
		key(graphics, font, options.keyDown, 20, 20, 18, 18, null);
		key(graphics, font, options.keyRight, 40, 20, 18, 18, null);
		key(graphics, font, options.keyJump, 0, 40, 58, 9, "");
		key(graphics, font, options.keyShift, 0, 51, 28, 13, Component.translatable("orbit_cosmetic.hud.sneak").getString());
		key(graphics, font, options.keySprint, 30, 51, 28, 13, Component.translatable("orbit_cosmetic.hud.sprint").getString());
	}

	private void key(GuiGraphicsExtractor graphics, Font font, KeyMapping mapping, int x, int y, int w, int h, @Nullable String label) {
		boolean down = !sample && mapping.isDown();
		keyBox(graphics, x, y, w, h, down);
		String text = label != null ? label : shortName(mapping);
		if (text.isEmpty()) {
			// The space bar: a line.
			graphics.fill(x + w / 2 - 8, y + h / 2, x + w / 2 + 8, y + h / 2 + 1, down ? 0xFFFFFFFF : text());
			return;
		}
		graphics.centeredText(font, text, x + w / 2, y + (h - 8) / 2 + 1, down ? 0xFFFFFFFF : text());
	}

	private void keyBox(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean down) {
		if (background || down) {
			graphics.fill(x, y, x + w, y + h, down ? Theme.shade(accentColor(), 0.76F) : 0xB01C1C20);
			graphics.outline(x, y, w, h, down ? accentColor() : 0xC02E2E35);
		}
	}

	private static String shortName(KeyMapping mapping) {
		String name = mapping.getTranslatedKeyMessage().getString();
		return name.length() > 3 ? name.substring(0, 3) : name;
	}

	/**
	 * Every key and mouse button held, in the order they were pressed, each as a key that fades away when let go.
	 * They line up from the side of the screen the component is on.
	 */
	private void drawAllKeys(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		List<HudInput.Held> held = sample ? List.of() : HudInput.heldKeys();
		if (held.isEmpty() && (preview || sample)) {
			held = List.of(new HudInput.Held("W", 1), new HudInput.Held("Left Shift", 1), new HudInput.Held("Space", 0.5F));
		}
		boolean fromRight = anchor == RIGHT && !sample;
		int w = width();
		int x = fromRight ? w : 0;
		for (HudInput.Held key : held) {
			int kw = font.width(key.name()) + 8;
			if (fromRight) {
				x -= kw;
				if (x < 0) {
					break;
				}
			} else if (x + kw > w) {
				break;
			}
			int alpha = Math.round(key.alpha() * 255);
			if (alpha > 8) {
				int fill = Theme.shade(accentColor(), 0.76F) & 0xFFFFFF | Math.round(key.alpha() * (background ? 255 : 220)) << 24;
				graphics.fill(x, 0, x + kw, 16, fill);
				graphics.outline(x, 0, kw, 16, accentColor() & 0xFFFFFF | alpha << 24);
				graphics.text(font, key.name(), x + 4, 4, 0xFFFFFF | alpha << 24, false);
			}
			x = fromRight ? x - 2 : x + kw + 2;
		}
	}

	/** Clicks a second on each mouse button, with a dot between them that follows the mouse and settles back. */
	private void drawCps(GuiGraphicsExtractor graphics, Font font) {
		int w = width();
		int h = height();
		drawFrame(graphics, 0, 0, w, h, 1);
		boolean live = !sample;
		if (!is("mouse")) {
			int leftColor = live && HudInput.leftDown() ? accentColor() : text();
			int rightColor = live && HudInput.rightDown() ? accentColor() : text();
			graphics.text(font, String.valueOf(live ? HudInput.leftCps() : 7), 6, 4, leftColor, false);
			String right = String.valueOf(live ? HudInput.rightCps() : 2);
			graphics.text(font, right, w - 5 - font.width(right), 4, rightColor, false);
		}
		if (is("cps")) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.hud.cps"), w / 2 + 1, 4, Theme.MUTED);
			return;
		}
		int cx = w / 2 + (is("mouse") ? 1 : 0);
		int cy = h / 2;
		int radius = is("mouse") ? 8 : 6;
		graphics.outline(cx - radius, cy - radius, radius * 2, radius * 2, 0x60FFFFFF);
		float reach = radius - 2;
		int dx = live ? Math.round(HudInput.dotX() * reach) : 2;
		int dy = live ? Math.round(HudInput.dotY() * reach) : -1;
		graphics.fill(cx - 1 + dx, cy - 1 + dy, cx + 1 + dx, cy + 1 + dy, text());
	}

	private static @Nullable String compassLetter(int degree) {
		return switch (degree) {
			case 0 -> "N";
			case 90 -> "E";
			case 180 -> "S";
			case 270 -> "W";
			case 45 -> "NE";
			case 135 -> "SE";
			case 225 -> "SW";
			case 315 -> "NW";
			default -> null;
		};
	}

	/** A ribbon of directions, the way the player looks in the middle. */
	private void drawCompass(GuiGraphicsExtractor graphics, Font font, float yRot) {
		int w = width();
		int h = height();
		drawFrame(graphics, 0, 0, w, h, 1);
		float heading = Mth.positiveModulo(yRot + 180, 360);
		float perDegree = (w - 8) / 180F;
		for (int degree = 0; degree < 360; degree += 15) {
			float delta = Mth.wrapDegrees(degree - heading);
			if (Math.abs(delta) > 88) {
				continue;
			}
			int px = Math.round(w / 2F + delta * perDegree);
			String letter = compassLetter(degree);
			if (letter != null) {
				graphics.centeredText(font, letter, px, 3, degree % 90 == 0 ? text() : Theme.MUTED);
			} else {
				graphics.fill(px, h - 7, px + 1, h - 3, 0x80FFFFFF);
			}
		}
		graphics.fill(w / 2, h - 5, w / 2 + 1, h, accentColor());
		graphics.fill(w / 2 - 1, 0, w / 2 + 2, 1, accentColor());
	}

	/**
	 * The long compass: as wide as the screen, only the directions (the main ones in the accent colour) fading out
	 * towards its ends, and a thin line under them as wide as what the camera sees (zooming in shortens it).
	 */
	private void drawLongCompass(GuiGraphicsExtractor graphics, Font font, float yRot) {
		int w = width();
		int h = height();
		float half = w / 2F;
		if (background) {
			// Only a faint shade, so the directions read over a bright sky.
			int shade = 0x30000000;
			Theme.hGradient(graphics, 0, 0, w / 3, h, 0, shade);
			graphics.fill(w / 3, 0, w - w / 3, h, shade);
			Theme.hGradient(graphics, w - w / 3, 0, w, h, shade, 0);
		}
		float heading = Mth.positiveModulo(yRot + 180, 360);
		float perDegree = w / 360F * 1.6F;
		for (int degree = 0; degree < 360; degree += 45) {
			float delta = Mth.wrapDegrees(degree - heading);
			float px = half + delta * perDegree;
			if (px < 6 || px > w - 6) {
				continue;
			}
			float edge = 1 - Math.abs(px - half) / half;
			int alpha = Math.round(Mth.clamp(edge * 1.5F, 0, 1) * 255);
			if (alpha < 8) {
				continue;
			}
			boolean main = degree % 90 == 0;
			int base = main ? accentColor() : text();
			int shown = main ? alpha : alpha * 3 / 5;
			graphics.centeredText(font, compassLetter(degree), Math.round(px), 2, base & 0xFFFFFF | shown << 24);
		}
		// What the camera sees, across: from the vertical field of view and the window's shape.
		var window = Minecraft.getInstance().getWindow();
		double aspect = window.getWidth() / (double) Math.max(1, window.getHeight());
		double vertical = Math.toRadians(Mth.clamp(sample ? 70 : Zoom.lastFov, 1, 170));
		float across = (float) Math.toDegrees(2 * Math.atan(Math.tan(vertical / 2) * aspect));
		int line = Math.min(w - 8, Math.round(across * perDegree));
		int left = Math.round(half - line / 2F);
		Theme.hGradient(graphics, left, h - 3, left + line / 2, h - 2, text() & 0xFFFFFF | 0x20 << 24, text() & 0xFFFFFF | 0xB0 << 24);
		Theme.hGradient(graphics, left + line / 2, h - 3, left + line, h - 2, text() & 0xFFFFFF | 0xB0 << 24, text() & 0xFFFFFF | 0x20 << 24);
	}

	// ---------------------------------------------------------------- items

	/** The four armour pieces (head first); null for each where there are no items to show (no world yet). */
	private @Nullable ItemStack[] armor() {
		LocalPlayer player = player();
		ItemStack[] stacks = new ItemStack[ARMOR_SLOTS.length];
		for (int i = 0; i < ARMOR_SLOTS.length; i++) {
			stacks[i] = player == null ? sampleArmor(i) : player.getItemBySlot(ARMOR_SLOTS[i]);
		}
		return stacks;
	}

	/** The armour slots shown (head first): all of them, or only those with something in them. */
	private List<Integer> armorSlots() {
		ItemStack[] armor = armor();
		List<Integer> slots = new java.util.ArrayList<>();
		for (int i = 0; i < armor.length; i++) {
			if (!hideEmptySlots || armor[i] == null || !armor[i].isEmpty()) {
				slots.add(i);
			}
		}
		return slots;
	}

	/** The rows of the inventory shown (0 is the top one). */
	private int[] inventoryRows() {
		return switch (variantId()) {
			case "top_row" -> new int[] {0};
			case "middle_row" -> new int[] {1};
			case "bottom_row" -> new int[] {2};
			default -> new int[] {0, 1, 2};
		};
	}

	private String[] armorTexts() {
		ItemStack[] armor = armor();
		String[] texts = new String[armor.length];
		for (int i = 0; i < armor.length; i++) {
			texts[i] = armor[i] == null ? SAMPLE_ARMOR[i] : detailText(armor[i]);
		}
		return texts;
	}

	/** The item in the hand; null where there are no items to show (no world yet). */
	private @Nullable ItemStack heldItem() {
		LocalPlayer player = player();
		boolean off = is("off_hand");
		if (player == null) {
			if (!itemsReady()) {
				return null;
			}
			return off ? new ItemStack(Items.SHIELD) : damaged(new ItemStack(Items.DIAMOND_PICKAXE), 0.3F);
		}
		return off ? player.getOffhandItem() : player.getMainHandItem();
	}

	private String itemText() {
		ItemStack stack = heldItem();
		return stack == null ? "1093/1561" : detailText(stack);
	}

	private static ItemStack damaged(ItemStack stack, float used) {
		stack.setDamageValue(Math.round(stack.getMaxDamage() * used));
		return stack;
	}

	private static @Nullable ItemStack sampleArmor(int slot) {
		if (!itemsReady()) {
			return null;
		}
		return switch (slot) {
			case 0 -> damaged(new ItemStack(Items.DIAMOND_HELMET), 0.1F);
			case 1 -> damaged(new ItemStack(Items.DIAMOND_CHESTPLATE), 0.45F);
			case 2 -> damaged(new ItemStack(Items.IRON_LEGGINGS), 0.2F);
			default -> damaged(new ItemStack(Items.IRON_BOOTS), 0.8F);
		};
	}

	private static ItemStack sampleInventory(int slot) {
		if (!itemsReady()) {
			return ItemStack.EMPTY;
		}
		return switch (slot) {
			case 0 -> new ItemStack(Items.DIAMOND, 12);
			case 4 -> new ItemStack(Items.DIAMOND_SWORD);
			case 11 -> new ItemStack(Items.OAK_LOG, 64);
			case 26 -> new ItemStack(Items.APPLE, 5);
			default -> ItemStack.EMPTY;
		};
	}

	/** A quarter or less of its durability left (and the text shows durability). */
	private boolean isLow(ItemStack stack) {
		return stack.isDamageableItem() && detail != Detail.NAME && stack.getDamageValue() * 4 >= stack.getMaxDamage() * 3;
	}

	/** What the full armour and item components write next to an item: durability, percent or name (the count for items without durability). */
	private String detailText(ItemStack stack) {
		if (stack.isEmpty()) {
			return "-";
		}
		if (detail == Detail.NAME) {
			return stack.getHoverName().getString();
		}
		if (!stack.isDamageableItem()) {
			return stack.getCount() > 1 || type == Type.ITEM ? String.valueOf(stack.getCount()) : "";
		}
		int left = stack.getMaxDamage() - stack.getDamageValue();
		if (detail == Detail.PERCENT) {
			return Math.round(left * 100F / stack.getMaxDamage()) + "%";
		}
		return left + "/" + stack.getMaxDamage();
	}

	/** An item and its text, one row of the full armour or item component (a grey square for the item when there is none to show). */
	private void drawItemRow(GuiGraphicsExtractor graphics, Font font, @Nullable ItemStack stack, String text, boolean low, int x, int y) {
		if (stack == null || stack.isEmpty()) {
			graphics.fill(x, y, x + 16, y + 16, 0x30000000);
			if (stack != null) {
				graphics.text(font, "-", x + 21, y + 4, Theme.FAINT, false);
				return;
			}
		} else {
			graphics.item(stack, x, y);
		}
		// A quarter or less left: in the accent colour, to catch the eye.
		graphics.text(font, text, x + 21, y + 4, low ? accentColor() : text(), false);
	}

	/** Armour in a small hotbar, like Minecraft's own (with durability bars). */
	private void drawArmorHotbar(GuiGraphicsExtractor graphics, Font font) {
		if (background) {
			int slots = Math.max(1, armorSlots().size());
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 0, 0, 0, 0, slots * 20 + 1, 22);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 181, 0, slots * 20 + 1, 0, 1, 22);
		}
		ItemStack[] armor = armor();
		List<Integer> slots = armorSlots();
		for (int n = 0; n < slots.size(); n++) {
			ItemStack stack = armor[slots.get(n)];
			if (stack != null && !stack.isEmpty()) {
				graphics.item(stack, 3 + n * 20, 3);
				graphics.itemDecorations(font, stack, 3 + n * 20, 3);
			}
		}
	}

	// ---------------------------------------------------------------- looking at, last hit

	/**
	 * What the player looks at: a block or an entity, its name, what breaks it (or its health), the mod it is from,
	 * and how far its breaking (or its health) is along.
	 */
	private record Look(@Nullable ItemStack icon, @Nullable LivingEntity living, Component name, Component info, int infoColor, String mod, float bar,
		boolean health, @Nullable ItemStack tool, boolean toolOk) {
	}

	private @Nullable Look look() {
		if (sample || Minecraft.getInstance().player == null) {
			return null;
		}
		Minecraft minecraft = Minecraft.getInstance();
		HitResult hit = minecraft.hitResult;
		var level = minecraft.level;
		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			String mod = modName(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
			if (entity instanceof LivingEntity living) {
				Component health = Component.literal("❤ " + healthText(living));
				return new Look(null, living, entity.getDisplayName(), health, 0xFFFF5555, mod, living.getHealth() / Math.max(1, living.getMaxHealth()), true, null,
					false);
			}
			ItemStack pick = entity.getPickResult();
			return new Look(pick, null, entity.getDisplayName(), Component.empty(), Theme.MUTED, mod, 0, false, null, false);
		}
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK && level != null) {
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				return null;
			}
			ItemStack icon = new ItemStack(state.getBlock());
			float progress = 0;
			if (minecraft.gameMode != null && minecraft.gameMode.isDestroying()) {
				MultiPlayerGameModeAccessor mode = (MultiPlayerGameModeAccessor) minecraft.gameMode;
				if (pos.equals(mode.orbit$destroyBlockPos())) {
					progress = Mth.clamp(mode.orbit$destroyProgress(), 0, 1);
				}
			}
			boolean unbreakable = state.getDestroySpeed(level, pos) < 0;
			ItemStack tool = unbreakable ? null : neededTool(state);
			boolean canDrop = !state.requiresCorrectToolForDrops() || minecraft.player.getMainHandItem().isCorrectToolForDrops(state);
			Component info = unbreakable ? Component.translatable("orbit_cosmetic.hud.tool.unbreakable") : Component.empty();
			return new Look(icon.isEmpty() ? null : icon, null, state.getBlock().getName(), info, Theme.MUTED,
				modName(BuiltInRegistries.BLOCK.getKey(state.getBlock())), progress, false, tool, canDrop);
		}
		return null;
	}

	/**
	 * The weakest tool that makes a block drop (a diamond pickaxe for ancient debris, a wooden axe for logs), or null
	 * when no tool is better than another for it.
	 */
	private static @Nullable ItemStack neededTool(BlockState state) {
		int tool = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? 0 : state.is(BlockTags.MINEABLE_WITH_AXE) ? 1
			: state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? 2 : state.is(BlockTags.MINEABLE_WITH_HOE) ? 3 : -1;
		if (tool < 0) {
			return null;
		}
		int tier = !state.requiresCorrectToolForDrops() ? 0 : state.is(BlockTags.NEEDS_DIAMOND_TOOL) ? 3 : state.is(BlockTags.NEEDS_IRON_TOOL) ? 2
			: state.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0;
		net.minecraft.world.item.Item[][] tools = {
			{Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE},
			{Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE},
			{Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL},
			{Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE}};
		return new ItemStack(tools[tool][tier]);
	}

	/** The mod something is from, by the namespace of its ID ("Minecraft" for Minecraft's own). */
	private static String modName(Identifier id) {
		String namespace = id.getNamespace();
		if (namespace.equals("minecraft")) {
			return "Minecraft";
		}
		return FabricLoader.getInstance().getModContainer(namespace).map(container -> container.getMetadata().getName()).orElse(namespace);
	}

	private static String healthText(LivingEntity living) {
		return formatHealth(living.getHealth()) + "/" + formatHealth(living.getMaxHealth());
	}

	private static String formatHealth(float health) {
		return health == Math.floor(health) ? String.valueOf((int) health) : String.format(Locale.ROOT, "%.1f", health);
	}

	private void drawLookingAt(GuiGraphicsExtractor graphics, Font font, boolean preview, int screenX, int screenY, float scale) {
		Look look = look();
		int w = width();
		int h = height();
		drawFrame(graphics, 0, 0, w, h, 1);
		Component name = look != null ? look.name() : Component.translatable("orbit_cosmetic.hud.sample.stone");
		Component info = look != null ? look.info() : Component.empty();
		int infoColor = look != null ? look.infoColor() : Theme.MUTED;
		String mod = look != null ? look.mod() : "Minecraft";
		float bar = look != null ? look.bar() : 0.4F;
		boolean health = look != null && look.health();
		// The icon (or the entity, in screen pixels), then the lines.
		if (look != null && look.living() != null) {
			graphics.pose().popMatrix();
			int x0 = screenX + Math.round(4 * scale);
			int y0 = screenY + Math.round(2 * scale);
			HudDoll.draw(graphics, look.living(), x0, y0, x0 + Math.round(18 * scale), y0 + Math.round(20 * scale), 20, 0, 0);
			graphics.pose().pushMatrix();
			graphics.pose().translate(screenX, screenY);
			graphics.pose().scale(scale, scale);
		} else if (look != null && look.icon() != null && !look.icon().isEmpty()) {
			graphics.item(look.icon(), 6, 3);
		} else if (look == null) {
			// The sample: a stone-coloured block.
			graphics.fill(6, 3, 22, 19, 0xFF7D7D7D);
			graphics.fill(8, 5, 12, 8, 0xFF6A6A6A);
			graphics.fill(15, 11, 20, 14, 0xFF8E8E8E);
		}
		// The name, its health (or "Unbreakable") beside it, and the mod under them.
		int modColor = accentColor() & 0xFFFFFF | 0xC0 << 24;
		graphics.text(font, name, 28, 3, text(), false);
		if (!info.getString().isEmpty()) {
			graphics.text(font, info, 28 + font.width(name) + 5, 3, infoColor, false);
		}
		graphics.text(font, mod, 28, 13, modColor, false);
		// The tool it needs, on the right, with a tick when the item in hand is enough (a cross when it is not).
		ItemStack tool = look != null ? look.tool() : itemsReady() ? new ItemStack(Items.WOODEN_PICKAXE) : null;
		if (tool != null || look == null) {
			int tx = w - 20;
			int ty = (h - 16) / 2 - 1;
			if (tool != null) {
				graphics.item(tool, tx, ty);
			}
			drawCheck(graphics, tx + 11, ty + 11, look == null || look.toolOk());
		}
		if (bar > 0 || health) {
			int barW = w - 4;
			graphics.fill(2, h - 2, w - 1, h - 1, 0x60000000);
			graphics.fill(2, h - 2, 2 + Math.round(barW * bar), h - 1, health ? 0xFFFF5555 : accentColor());
		}
	}

	/** A small tick (green) or cross (red) on a dark square, 7 pixels wide. */
	private static void drawCheck(GuiGraphicsExtractor graphics, int x, int y, boolean ok) {
		graphics.fill(x - 1, y - 1, x + 8, y + 8, 0xE0101012);
		int color = ok ? 0xFF7CE38B : 0xFFFF5555;
		if (ok) {
			int[][] tick = {{0, 3}, {1, 4}, {2, 5}, {3, 4}, {4, 3}, {5, 2}, {6, 1}};
			for (int[] p : tick) {
				graphics.fill(x + p[0], y + p[1], x + p[0] + 1, y + p[1] + 2, color);
			}
		} else {
			for (int i = 0; i < 7; i++) {
				graphics.fill(x + i, y + i, x + i + 1, y + i + 1, color);
				graphics.fill(x + 6 - i, y + i, x + 7 - i, y + i + 1, color);
			}
		}
	}

	private @Nullable LivingEntity lastHit() {
		return sample ? null : HudTargets.lastHit();
	}

	private void drawLastHit(GuiGraphicsExtractor graphics, Font font, int screenX, int screenY, float scale) {
		LivingEntity target = lastHit();
		int w = width();
		int h = height();
		drawFrame(graphics, 0, 0, w, h, 1);
		if (target != null) {
			graphics.pose().popMatrix();
			int x0 = screenX + Math.round(5 * scale);
			int y0 = screenY + Math.round(3 * scale);
			HudDoll.draw(graphics, target, x0, y0, x0 + Math.round(28 * scale), y0 + Math.round(36 * scale), 25, 0, 0);
			graphics.pose().pushMatrix();
			graphics.pose().translate(screenX, screenY);
			graphics.pose().scale(scale, scale);
		} else {
			graphics.pose().popMatrix();
			drawDollPlaceholder(graphics, screenX + Math.round(5 * scale), screenY + Math.round(3 * scale), Math.round(28 * scale), Math.round(36 * scale));
			graphics.pose().pushMatrix();
			graphics.pose().translate(screenX, screenY);
			graphics.pose().scale(scale, scale);
		}
		Component name = target != null ? target.getDisplayName() : Component.translatable("orbit_cosmetic.hud.sample.zombie");
		float health = target != null ? target.getHealth() : 14;
		float max = target != null ? Math.max(1, target.getMaxHealth()) : 20;
		graphics.text(font, name, 38, 5, text(), false);
		int barX = 38;
		int barW = w - barX - 6;
		graphics.fill(barX, 17, barX + barW, 22, 0x80000000);
		graphics.fill(barX, 17, barX + Math.round(barW * Mth.clamp(health / max, 0, 1)), 22, accentColor());
		graphics.outline(barX - 1, 16, barW + 2, 7, 0x60000000);
		String hearts = "❤ " + formatHealth(Math.max(0, health)) + "/" + formatHealth(max);
		graphics.text(font, hearts, barX, 27, Theme.MUTED, false);
	}

	private static String facing(float yaw) {
		int index = Math.floorMod(Math.round(yaw / 90F), 4);
		return new String[] {"south", "west", "north", "east"}[index];
	}

	private static String fpsText() {
		return Component.translatable("orbit_cosmetic.hud.fps", Minecraft.getInstance().getFps()).getString();
	}

	// ---------------------------------------------------------------- saving

	JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("id", id.toString());
		json.addProperty("type", type.name());
		if (legacyX >= 0) {
			json.addProperty("x", legacyX);
		} else {
			json.addProperty("anchor", anchor);
			json.addProperty("ax", ax);
		}
		json.addProperty("y", fy);
		json.addProperty("scale", scale);
		json.addProperty("variant", variantId());
		json.addProperty("detail", detail.name());
		json.addProperty("menus", inMenus);
		json.addProperty("background", background);
		json.addProperty("hidden", hidden);
		json.addProperty("color", color);
		json.addProperty("accent", accent);
		json.addProperty("rotation", rotation);
		json.addProperty("pinned", pinned);
		json.addProperty("hideEmpty", hideEmpty);
		json.addProperty("hideEmptySlots", hideEmptySlots);
		json.addProperty("dock", dock);
		json.addProperty("avoid", avoid);
		if (parent != null) {
			json.addProperty("parent", parent.toString());
			json.addProperty("side", side);
			json.addProperty("gap", gap);
			json.addProperty("offset", offset);
		}
		return json;
	}

	static @Nullable HudComponent fromJson(JsonObject json) {
		try {
			HudComponent component = new HudComponent(UUID.fromString(json.get("id").getAsString()), Type.valueOf(json.get("type").getAsString()));
			if (json.has("anchor")) {
				component.anchor = Mth.clamp(json.get("anchor").getAsInt(), LEFT, RIGHT);
				component.ax = Mth.clamp(json.get("ax").getAsFloat(), 0, 1);
			} else {
				component.legacyX = Mth.clamp(json.get("x").getAsFloat(), 0, 1);
			}
			component.fy = Mth.clamp(json.get("y").getAsFloat(), 0, 1);
			component.scale = Mth.clamp(json.get("scale").getAsFloat(), MIN_SCALE, MAX_SCALE);
			if (json.has("variant")) {
				component.variant = Math.max(0, List.of(component.type.variants).indexOf(json.get("variant").getAsString()));
			}
			if (json.has("detail")) {
				component.detail = Detail.valueOf(json.get("detail").getAsString());
			}
			component.inMenus = json.has("menus") && json.get("menus").getAsBoolean();
			component.background = !json.has("background") || json.get("background").getAsBoolean();
			component.hidden = json.has("hidden") && json.get("hidden").getAsBoolean();
			component.color = json.has("color") ? json.get("color").getAsInt() & 0xFFFFFF : 0xFFFFFF;
			component.accent = json.has("accent") ? json.get("accent").getAsInt() & 0xFFFFFF : DEFAULT_ACCENT;
			component.rotation = json.has("rotation") ? Math.floorMod(json.get("rotation").getAsInt() + 180, 360) - 180 : 20;
			component.pinned = json.has("pinned") && json.get("pinned").getAsBoolean();
			component.hideEmpty = json.has("hideEmpty") && json.get("hideEmpty").getAsBoolean();
			component.hideEmptySlots = json.has("hideEmptySlots") && json.get("hideEmptySlots").getAsBoolean();
			component.avoid = json.has("avoid") ? json.get("avoid").getAsInt() & 3 : 0;
			component.dock = json.has("dock") ? Mth.clamp(json.get("dock").getAsInt(), DOCK_NONE, DOCK_BESIDE) : DOCK_NONE;
			if (json.has("parent")) {
				component.parent = UUID.fromString(json.get("parent").getAsString());
				component.side = Mth.clamp(json.get("side").getAsInt(), SIDE_RIGHT, SIDE_ABOVE);
				component.gap = Math.max(0, json.get("gap").getAsFloat());
				component.offset = json.get("offset").getAsFloat();
			}
			return component;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
