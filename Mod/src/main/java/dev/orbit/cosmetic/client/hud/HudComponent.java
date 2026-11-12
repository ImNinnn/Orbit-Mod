package dev.orbit.cosmetic.client.hud;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * One HUD component placed by the player: what it shows, where (a fraction of the room left on the screen, so it
 * stays on screen when the window changes), how big, and how it looks. Drawn in its own unscaled size, then scaled.
 */
public final class HudComponent {
	/** What a component shows, with its size before scaling (the FPS counter grows with its text). */
	public enum Type {
		FPS(48, 14, false),
		COORDINATES(76, 34, true),
		ROTATION(84, 34, true),
		KEYSTROKES(58, 64, false),
		CPS_MOUSE(58, 16, false),
		PAPERDOLL(44, 72, true),
		INVENTORY(166, 58, true),
		COMPASS(150, 20, true);

		final int width;
		final int height;
		/** Needs a world (shown only in one). */
		final boolean world;

		Type(int width, int height, boolean world) {
			this.width = width;
			this.height = height;
			this.world = world;
		}

		public Component label() {
			return Component.translatable("orbit_cosmetic.hud.type." + name().toLowerCase(Locale.ROOT));
		}
	}

	public static final float MIN_SCALE = 0.5F;
	public static final float MAX_SCALE = 4;

	public final UUID id;
	public final Type type;
	/** Where its top left corner is: a fraction of the screen's width and height left over beside it. */
	public float fx;
	public float fy;
	public float scale = 1;
	public boolean inMenus;
	public boolean background = true;
	public int color = 0xFFFFFF;

	public HudComponent(UUID id, Type type) {
		this.id = id;
		this.type = type;
	}

	public int width() {
		if (type == Type.FPS) {
			return Math.max(type.width, Minecraft.getInstance().font.width(fpsText()) + 12);
		}
		return type.width;
	}

	public int height() {
		return type.height;
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
		int x = x(screenWidth);
		int y = y(screenHeight);
		if (type == Type.PAPERDOLL) {
			// The player model is drawn into its own picture, in screen pixels: not scaled with the pose.
			drawFrame(graphics, x, y, scaledWidth(), scaledHeight(), Math.max(1, Math.round(scale)));
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				int cx = x + scaledWidth() / 2;
				net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + 2, y + 2, x + scaledWidth() - 2,
					y + scaledHeight() - 2, Math.round(30 * scale), 0.0625F, cx, y + scaledHeight() * 0.35F, player);
			} else if (preview) {
				Font font = Minecraft.getInstance().font;
				graphics.centeredText(font, type.label(), x + scaledWidth() / 2, y + scaledHeight() / 2 - 4, Theme.MUTED);
			}
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		drawScaled(graphics, preview);
		graphics.pose().popMatrix();
	}

	private int text() {
		return 0xFF000000 | color;
	}

	/** The background: a dark panel with Orbit's red strip on its left (when turned on). */
	private void drawFrame(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int unit) {
		if (!background) {
			return;
		}
		graphics.fill(x, y, x + w, y + h, 0xB01C1C20);
		graphics.outline(x, y, w, h, 0xC02E2E35);
		graphics.fill(x, y, x + 2 * unit, y + h, Theme.RED);
	}

	private void drawScaled(GuiGraphicsExtractor graphics, boolean preview) {
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		LocalPlayer player = minecraft.player;
		int w = width();
		int h = height();
		switch (type) {
			case FPS -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				graphics.text(font, fpsText(), 6, 3, text(), false);
			}
			case COORDINATES -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				double[] pos = player == null ? new double[] {128.5, 64, -42.5} : new double[] {player.getX(), player.getY(), player.getZ()};
				String[] axes = {"X", "Y", "Z"};
				for (int i = 0; i < 3; i++) {
					graphics.text(font, axes[i], 6, 3 + i * 10, Theme.RED, false);
					graphics.text(font, String.format(Locale.ROOT, "%.1f", pos[i]), 16, 3 + i * 10, text(), false);
				}
			}
			case ROTATION -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				float yaw = player == null ? 90 : Mth.wrapDegrees(player.getYRot());
				float pitch = player == null ? 12 : player.getXRot();
				graphics.text(font, Component.translatable("orbit_cosmetic.hud.yaw"), 6, 3, Theme.RED, false);
				graphics.text(font, String.format(Locale.ROOT, "%.1f°", yaw), 36, 3, text(), false);
				graphics.text(font, Component.translatable("orbit_cosmetic.hud.pitch"), 6, 13, Theme.RED, false);
				graphics.text(font, String.format(Locale.ROOT, "%.1f°", pitch), 36, 13, text(), false);
				graphics.text(font, Component.translatable("orbit_cosmetic.hud.facing." + facing(yaw)), 6, 23, Theme.MUTED, false);
			}
			case KEYSTROKES -> drawKeystrokes(graphics, font);
			case CPS_MOUSE -> drawCps(graphics, font);
			case INVENTORY -> {
				drawFrame(graphics, 0, 0, w, h, 1);
				for (int i = 0; i < 27; i++) {
					int sx = 4 + i % 9 * 18;
					int sy = 2 + i / 9 * 18;
					graphics.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
					ItemStack stack = player == null ? ItemStack.EMPTY : player.getInventory().getItem(9 + i);
					if (!stack.isEmpty()) {
						graphics.item(stack, sx, sy);
						graphics.itemDecorations(font, stack, sx, sy);
					}
				}
			}
			case COMPASS -> drawCompass(graphics, font, player == null ? 30 : player.getYRot());
			default -> {
			}
		}
	}

	/** W, A S D, the space bar, Sneak and Sprint: keys light up red while pressed. */
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
		boolean down = mapping.isDown();
		if (background || down) {
			graphics.fill(x, y, x + w, y + h, down ? 0xFFC21E1E : 0xB01C1C20);
			graphics.outline(x, y, w, h, down ? Theme.RED : 0xC02E2E35);
		}
		String text = label != null ? label : shortName(mapping);
		if (text.isEmpty()) {
			// The space bar: a line.
			graphics.fill(x + w / 2 - 8, y + h / 2, x + w / 2 + 8, y + h / 2 + 1, down ? 0xFFFFFFFF : text());
			return;
		}
		graphics.centeredText(font, text, x + w / 2, y + (h - 8) / 2 + 1, down ? 0xFFFFFFFF : text());
	}

	private static String shortName(KeyMapping mapping) {
		String name = mapping.getTranslatedKeyMessage().getString();
		return name.length() > 3 ? name.substring(0, 3) : name;
	}

	/** Clicks a second on each mouse button, with a dot between them that follows the mouse and settles back. */
	private void drawCps(GuiGraphicsExtractor graphics, Font font) {
		int w = width();
		int h = height();
		drawFrame(graphics, 0, 0, w, h, 1);
		graphics.text(font, String.valueOf(HudInput.leftCps()), 6, 4, HudInput.leftDown() ? Theme.RED : text(), false);
		String right = String.valueOf(HudInput.rightCps());
		graphics.text(font, right, w - 5 - font.width(right), 4, HudInput.rightDown() ? Theme.RED : text(), false);
		int cx = w / 2;
		int cy = h / 2;
		graphics.outline(cx - 6, cy - 6, 12, 12, 0x60FFFFFF);
		int dx = Math.round(HudInput.dotX() * 4);
		int dy = Math.round(HudInput.dotY() * 4);
		graphics.fill(cx - 1 + dx, cy - 1 + dy, cx + 1 + dx, cy + 1 + dy, text());
	}

	/** A ribbon of directions and degrees, the way the player looks in the middle. */
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
			String letter = switch (degree) {
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
			if (letter != null) {
				graphics.centeredText(font, letter, px, 3, degree % 90 == 0 ? text() : Theme.MUTED);
			} else {
				graphics.fill(px, h - 7, px + 1, h - 3, 0x80FFFFFF);
			}
		}
		graphics.fill(w / 2, h - 5, w / 2 + 1, h, Theme.RED);
		graphics.fill(w / 2 - 1, 0, w / 2 + 2, 1, Theme.RED);
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
		json.addProperty("menus", inMenus);
		json.addProperty("background", background);
		json.addProperty("color", color);
		return json;
	}

	static @Nullable HudComponent fromJson(JsonObject json) {
		try {
			HudComponent component = new HudComponent(UUID.fromString(json.get("id").getAsString()), Type.valueOf(json.get("type").getAsString()));
			component.fx = Mth.clamp(json.get("x").getAsFloat(), 0, 1);
			component.fy = Mth.clamp(json.get("y").getAsFloat(), 0, 1);
			component.scale = Mth.clamp(json.get("scale").getAsFloat(), MIN_SCALE, MAX_SCALE);
			component.inMenus = json.has("menus") && json.get("menus").getAsBoolean();
			component.background = !json.has("background") || json.get("background").getAsBoolean();
			component.color = json.has("color") ? json.get("color").getAsInt() & 0xFFFFFF : 0xFFFFFF;
			return component;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
