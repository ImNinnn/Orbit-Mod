package dev.orbit.cosmetic.client.hud;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One HUD component placed by the player: what it shows (its type and style), where (a fraction of the room left on
 * the screen, so it stays on screen when the window changes), how big, and how it looks. Drawn in its own unscaled
 * size, then scaled.
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
		INVENTORY(true),
		COMPASS(true, "ribbon", "long"),
		ARMOR(true, "full", "hotbar"),
		ITEM(true, "main_hand", "off_hand");

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
	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	public final UUID id;
	public final Type type;
	/** Where its top left corner is: a fraction of the screen's width and height left over beside it. */
	public float fx;
	public float fy;
	public float scale = 1;
	public int variant;
	public Detail detail = Detail.DURABILITY;
	public boolean inMenus;
	public boolean background = true;
	public int color = 0xFFFFFF;
	/** Replaces Orbit's red: the bar on the left, labels, pressed keys, the compass's marker. */
	public int accent = DEFAULT_ACCENT;
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

	/** Whether the full armour or item component's text setting applies. */
	public boolean hasDetail() {
		return type == Type.ITEM || type == Type.ARMOR && is("full");
	}

	private @Nullable LocalPlayer player() {
		return sample ? null : Minecraft.getInstance().player;
	}

	private static Font font() {
		return Minecraft.getInstance().font;
	}

	/** Rounded up, so text that changes a little does not move the component every frame. */
	private static int steady(int width) {
		return (width + 7) / 8 * 8;
	}

	public int width() {
		Font font = font();
		return switch (type) {
			case FPS -> Math.max(48, font.width(fpsText()) + 12);
			case COORDINATES -> is("line") ? steady(lineWidth(coordinateParts())) : 76;
			case ROTATION -> is("line") ? steady(lineWidth(rotationParts())) : 84;
			case KEYSTROKES -> is("all_keys") ? 150 : 58;
			case CPS_MOUSE -> is("mouse") ? 24 : 58;
			case PAPERDOLL -> 44;
			case INVENTORY -> 166;
			case COMPASS -> is("long") && !sample ? (int) Math.ceil(Minecraft.getInstance().getWindow().getGuiScaledWidth() / scale) : 150;
			case ARMOR -> {
				if (is("hotbar")) {
					yield 82;
				}
				int widest = 0;
				for (ItemStack stack : armor()) {
					widest = Math.max(widest, font.width(detailText(stack)));
				}
				yield Math.max(60, steady(widest + 28));
			}
			case ITEM -> Math.max(60, steady(font.width(detailText(heldItem())) + 28));
		};
	}

	public int height() {
		return switch (type) {
			case FPS -> 14;
			case COORDINATES, ROTATION -> is("line") ? 14 : 34;
			case KEYSTROKES -> is("all_keys") ? 16 : 64;
			case CPS_MOUSE -> is("mouse") ? 24 : 16;
			case PAPERDOLL -> 72;
			case INVENTORY -> 58;
			case COMPASS -> 20;
			case ARMOR -> is("hotbar") ? 22 : 76;
			case ITEM -> 20;
		};
	}

	public int scaledWidth() {
		return Math.round(width() * scale);
	}

	public int scaledHeight() {
		return Math.round(height() * scale);
	}

	public int x(int screenWidth) {
		return Math.round(fx * Math.max(0, screenWidth - scaledWidth()));
	}

	public int y(int screenHeight) {
		return Math.round(fy * Math.max(0, screenHeight - scaledHeight()));
	}

	/** Puts its top left corner at this place on a screen of this size. */
	public void place(int x, int y, int screenWidth, int screenHeight) {
		int roomX = screenWidth - scaledWidth();
		int roomY = screenHeight - scaledHeight();
		fx = roomX <= 0 ? 0 : Mth.clamp(x / (float) roomX, 0, 1);
		fy = roomY <= 0 ? 0 : Mth.clamp(y / (float) roomY, 0, 1);
	}

	/** Whether it can show now: world components need a world. */
	public boolean available() {
		return !type.world || Minecraft.getInstance().player != null;
	}

	// ---------------------------------------------------------------- drawing

	/** Draws it at its place; {@code preview} shows sample values where there is nothing to show (the editor). */
	public void draw(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight, boolean preview, float partialTick) {
		drawAt(graphics, x(screenWidth), y(screenHeight), scale, preview);
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
				net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + 2, y + 2, x + w - 2, y + h - 2,
					Math.round(30 * scale), 0.0625F, x + w / 2F, y + h * 0.35F, player);
			} else if (preview) {
				drawDollPlaceholder(graphics, x, y, w, h);
			}
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		drawScaled(graphics, preview);
		graphics.pose().popMatrix();
	}

	/** A plain figure where there is no player to show (the title screen, the add menu). */
	private void drawDollPlaceholder(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		int unit = Math.max(1, h / 36);
		int cx = x + w / 2;
		int top = y + (h - 32 * unit) / 2;
		int body = 0x80A3A3AD;
		graphics.fill(cx - 4 * unit, top, cx + 4 * unit, top + 8 * unit, 0xC0A3A3AD);
		graphics.fill(cx - 4 * unit, top + 8 * unit, cx + 4 * unit, top + 20 * unit, body);
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

	private void drawScaled(GuiGraphicsExtractor graphics, boolean preview) {
		Font font = font();
		LocalPlayer player = player();
		int w = width();
		int h = height();
		switch (type) {
			case FPS -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				graphics.text(font, fpsText(), 6, 3, text(), false);
			}
			case COORDINATES, ROTATION -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				List<String[]> parts = type == Type.COORDINATES ? coordinateParts() : rotationParts();
				if (is("line")) {
					int x = 6;
					for (String[] part : parts) {
						x = drawPart(graphics, font, part, x, 3) + 7;
					}
				} else {
					int labelWidth = 0;
					for (String[] part : parts) {
						labelWidth = Math.max(labelWidth, font.width(part[0]));
					}
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
				for (int i = 0; i < 27; i++) {
					int sx = 4 + i % 9 * 18;
					int sy = 2 + i / 9 * 18;
					graphics.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
					ItemStack stack = player == null ? sampleInventory(i) : player.getInventory().getItem(9 + i);
					if (!stack.isEmpty()) {
						graphics.item(stack, sx, sy);
						graphics.itemDecorations(font, stack, sx, sy);
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
					for (int i = 0; i < armor.length; i++) {
						drawItemRow(graphics, font, armor[i], 5, 2 + i * 18);
					}
				}
			}
			case ITEM -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				drawItemRow(graphics, font, heldItem(), 5, 2);
			}
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
		boolean fromRight = fx > 0.5F && !sample;
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

	/** The long compass: as wide as the screen, fading out towards its ends. */
	private void drawLongCompass(GuiGraphicsExtractor graphics, Font font, float yRot) {
		int w = width();
		int h = height();
		if (background) {
			int solid = 0xB01C1C20;
			int clear = 0x001C1C20;
			int fade = w / 4;
			Theme.hGradient(graphics, 0, 0, fade, h, clear, solid);
			graphics.fill(fade, 0, w - fade, h, solid);
			Theme.hGradient(graphics, w - fade, 0, w, h, solid, clear);
		}
		float heading = Mth.positiveModulo(yRot + 180, 360);
		float perDegree = 2.4F;
		float half = w / 2F;
		for (int degree = 0; degree < 360; degree += 5) {
			float delta = Mth.wrapDegrees(degree - heading);
			float px = half + delta * perDegree;
			if (px < 2 || px > w - 2) {
				continue;
			}
			float edge = 1 - Math.abs(px - half) / half;
			int alpha = Math.round(Mth.clamp(edge * 1.6F, 0, 1) * 255);
			if (alpha < 8) {
				continue;
			}
			int x = Math.round(px);
			String letter = degree % 15 == 0 ? compassLetter(degree) : null;
			if (letter != null) {
				int base = degree % 90 == 0 ? text() : Theme.MUTED;
				graphics.centeredText(font, letter, x, 3, base & 0xFFFFFF | alpha << 24);
			} else if (degree % 15 == 0) {
				graphics.fill(x, h - 7, x + 1, h - 3, 0xFFFFFF | alpha / 2 << 24);
			} else {
				graphics.fill(x, h - 5, x + 1, h - 3, 0xFFFFFF | alpha / 4 << 24);
			}
		}
		int mid = Math.round(half);
		graphics.fill(mid, h - 5, mid + 1, h, accentColor());
		graphics.fill(mid - 1, 0, mid + 2, 1, accentColor());
	}

	// ---------------------------------------------------------------- items

	private ItemStack[] armor() {
		LocalPlayer player = player();
		ItemStack[] stacks = new ItemStack[ARMOR_SLOTS.length];
		for (int i = 0; i < ARMOR_SLOTS.length; i++) {
			stacks[i] = player == null ? sampleArmor(i) : player.getItemBySlot(ARMOR_SLOTS[i]);
		}
		return stacks;
	}

	private ItemStack heldItem() {
		LocalPlayer player = player();
		boolean off = is("off_hand");
		if (player == null) {
			return off ? new ItemStack(Items.SHIELD) : damaged(new ItemStack(Items.DIAMOND_PICKAXE), 0.3F);
		}
		return off ? player.getOffhandItem() : player.getMainHandItem();
	}

	private static ItemStack damaged(ItemStack stack, float used) {
		stack.setDamageValue(Math.round(stack.getMaxDamage() * used));
		return stack;
	}

	private static ItemStack sampleArmor(int slot) {
		return switch (slot) {
			case 0 -> damaged(new ItemStack(Items.DIAMOND_HELMET), 0.1F);
			case 1 -> damaged(new ItemStack(Items.DIAMOND_CHESTPLATE), 0.45F);
			case 2 -> damaged(new ItemStack(Items.IRON_LEGGINGS), 0.2F);
			default -> damaged(new ItemStack(Items.IRON_BOOTS), 0.8F);
		};
	}

	private static ItemStack sampleInventory(int slot) {
		return switch (slot) {
			case 0 -> new ItemStack(Items.DIAMOND, 12);
			case 4 -> new ItemStack(Items.DIAMOND_SWORD);
			case 11 -> new ItemStack(Items.OAK_LOG, 64);
			case 26 -> new ItemStack(Items.APPLE, 5);
			default -> ItemStack.EMPTY;
		};
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

	/** An item and its text, one row of the full armour or item component. */
	private void drawItemRow(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
		if (stack.isEmpty()) {
			graphics.fill(x, y, x + 16, y + 16, 0x30000000);
			graphics.text(font, "-", x + 21, y + 4, Theme.FAINT, false);
			return;
		}
		graphics.item(stack, x, y);
		String text = detailText(stack);
		int color = text();
		if (stack.isDamageableItem() && detail != Detail.NAME && stack.getDamageValue() * 4 >= stack.getMaxDamage() * 3) {
			// A quarter or less left: in the accent colour, to catch the eye.
			color = accentColor();
		}
		graphics.text(font, text, x + 21, y + 4, color, false);
	}

	/** Armour in a small hotbar, like Minecraft's own (with durability bars). */
	private void drawArmorHotbar(GuiGraphicsExtractor graphics, Font font) {
		if (background) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 0, 0, 0, 0, 81, 22);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 181, 0, 81, 0, 1, 22);
		}
		ItemStack[] armor = armor();
		for (int i = 0; i < armor.length; i++) {
			if (!armor[i].isEmpty()) {
				graphics.item(armor[i], 3 + i * 20, 3);
				graphics.itemDecorations(font, armor[i], 3 + i * 20, 3);
			}
		}
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
		json.addProperty("x", fx);
		json.addProperty("y", fy);
		json.addProperty("scale", scale);
		json.addProperty("variant", variantId());
		json.addProperty("detail", detail.name());
		json.addProperty("menus", inMenus);
		json.addProperty("background", background);
		json.addProperty("color", color);
		json.addProperty("accent", accent);
		return json;
	}

	static @Nullable HudComponent fromJson(JsonObject json) {
		try {
			HudComponent component = new HudComponent(UUID.fromString(json.get("id").getAsString()), Type.valueOf(json.get("type").getAsString()));
			component.fx = Mth.clamp(json.get("x").getAsFloat(), 0, 1);
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
			component.color = json.has("color") ? json.get("color").getAsInt() & 0xFFFFFF : 0xFFFFFF;
			component.accent = json.has("accent") ? json.get("accent").getAsInt() & 0xFFFFFF : DEFAULT_ACCENT;
			return component;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
