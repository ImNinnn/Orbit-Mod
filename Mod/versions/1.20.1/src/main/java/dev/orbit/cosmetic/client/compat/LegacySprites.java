package dev.orbit.cosmetic.client.compat;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GUI sprites on Minecraft 1.20.1, which has no sprite atlas for the GUI yet (it came with 1.20.2). Orbit names
 * sprites as later versions do: Minecraft's own (hearts, the hotbar, buttons...) are found here in the texture sheets
 * 1.20.1 draws them from, and Orbit's own are drawn from their files (textures/gui/sprites), nine-sliced as their
 * .mcmeta says.
 */
public final class LegacySprites {
	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
	private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
	private static final ResourceLocation TABS = new ResourceLocation("textures/gui/tab_button.png");
	/** Heart types and their column in icons.png (as Gui.HeartType numbers them); the plain one last. */
	private static final String[] HEART_TYPES = {"poisoned_", "withered_", "absorbing_", "frozen_", ""};
	private static final int[] HEART_INDEX = {4, 6, 8, 9, 2};
	private static final Map<ResourceLocation, Optional<Region>> CACHE = new HashMap<>();

	private LegacySprites() {
	}

	/**
	 * Where a sprite is: part of a texture of {@code textureWidth} by {@code textureHeight}, and the borders that keep
	 * their size when it is stretched (all 0 to stretch it whole).
	 */
	private record Region(ResourceLocation texture, int u, int v, int width, int height, int textureWidth, int textureHeight, int left, int top, int right,
		int bottom) {
		static Region of(ResourceLocation texture, int u, int v, int width, int height) {
			return new Region(texture, u, v, width, height, 256, 256, 0, 0, 0, 0);
		}
	}

	/** A sprite stretched over the rectangle; nothing for a sprite 1.20.1 does not have. */
	public static void draw(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
		Region region = region(sprite);
		if (region == null || width <= 0 || height <= 0) {
			return;
		}
		if (region.left == 0 && region.top == 0 && region.right == 0 && region.bottom == 0) {
			part(graphics, region, region.u, region.v, region.width, region.height, x, y, width, height);
			return;
		}
		// Nine slices: the corners keep their size, the edges and the middle stretch.
		int l = Math.min(region.left, width / 2);
		int r = Math.min(region.right, width - l);
		int t = Math.min(region.top, height / 2);
		int b = Math.min(region.bottom, height - t);
		int[] xs = {x, x + l, x + width - r, x + width};
		int[] ys = {y, y + t, y + height - b, y + height};
		int[] us = {region.u, region.u + region.left, region.u + region.width - region.right, region.u + region.width};
		int[] vs = {region.v, region.v + region.top, region.v + region.height - region.bottom, region.v + region.height};
		for (int column = 0; column < 3; column++) {
			for (int row = 0; row < 3; row++) {
				part(graphics, region, us[column], vs[row], us[column + 1] - us[column], vs[row + 1] - vs[row], xs[column], ys[row], xs[column + 1] - xs[column],
					ys[row + 1] - ys[row]);
			}
		}
	}

	/**
	 * Part of a sprite taken as {@code spriteWidth} by {@code spriteHeight} pixels: the part from (u, v), {@code width}
	 * by {@code height}, drawn at (x, y).
	 */
	public static void drawPart(GuiGraphics graphics, ResourceLocation sprite, int spriteWidth, int spriteHeight, int u, int v, int x, int y, int width,
		int height) {
		Region region = region(sprite);
		if (region == null || width <= 0 || height <= 0) {
			return;
		}
		float scaleU = (float) region.width / spriteWidth;
		float scaleV = (float) region.height / spriteHeight;
		part(graphics, region, region.u + Math.round(u * scaleU), region.v + Math.round(v * scaleV), Math.round(width * scaleU), Math.round(height * scaleV), x, y,
			width, height);
	}

	private static void part(GuiGraphics graphics, Region region, int u, int v, int uWidth, int vHeight, int x, int y, int width, int height) {
		if (uWidth > 0 && vHeight > 0 && width > 0 && height > 0) {
			graphics.blit(region.texture, x, y, width, height, u, v, uWidth, vHeight, region.textureWidth, region.textureHeight);
		}
	}

	private static @Nullable Region region(ResourceLocation sprite) {
		return CACHE.computeIfAbsent(sprite, key -> Optional.ofNullable(key.getNamespace().equals("minecraft") ? vanilla(key.getPath()) : own(key))).orElse(null);
	}

	/** Minecraft's own sprites, where 1.20.1 keeps them. */
	private static @Nullable Region vanilla(String path) {
		switch (path) {
			case "hud/hotbar":
				return Region.of(WIDGETS, 0, 0, 182, 22);
			case "hud/hotbar_offhand_left":
				return Region.of(WIDGETS, 24, 22, 29, 24);
			case "hud/experience_bar_background":
				return Region.of(ICONS, 0, 64, 182, 5);
			case "hud/experience_bar_progress":
				return Region.of(ICONS, 0, 69, 182, 5);
			case "hud/armor_empty":
				return Region.of(ICONS, 16, 9, 9, 9);
			case "hud/armor_half":
				return Region.of(ICONS, 25, 9, 9, 9);
			case "hud/armor_full":
				return Region.of(ICONS, 34, 9, 9, 9);
			case "hud/air":
				return Region.of(ICONS, 16, 18, 9, 9);
			case "hud/air_bursting":
				return Region.of(ICONS, 25, 18, 9, 9);
			case "hud/food_empty":
				return Region.of(ICONS, 16, 27, 9, 9);
			case "hud/food_full":
				return Region.of(ICONS, 52, 27, 9, 9);
			case "hud/food_half":
				return Region.of(ICONS, 61, 27, 9, 9);
			case "hud/food_empty_hunger":
				return Region.of(ICONS, 133, 27, 9, 9);
			case "hud/food_full_hunger":
				return Region.of(ICONS, 88, 27, 9, 9);
			case "hud/food_half_hunger":
				return Region.of(ICONS, 97, 27, 9, 9);
			case "hud/heart/vehicle_container":
				return Region.of(ICONS, 52, 9, 9, 9);
			case "hud/heart/vehicle_full":
				return Region.of(ICONS, 88, 9, 9, 9);
			case "hud/heart/vehicle_half":
				return Region.of(ICONS, 97, 9, 9, 9);
			case "widget/button":
				return new Region(WIDGETS, 0, 66, 200, 20, 256, 256, 20, 4, 20, 4);
			case "widget/button_highlighted":
				return new Region(WIDGETS, 0, 86, 200, 20, 256, 256, 20, 4, 20, 4);
			case "widget/button_disabled":
				return new Region(WIDGETS, 0, 46, 200, 20, 256, 256, 20, 4, 20, 4);
			case "widget/tab_selected":
				return new Region(TABS, 0, 0, 130, 24, 256, 256, 2, 2, 2, 0);
			case "widget/tab_selected_highlighted":
				return new Region(TABS, 0, 24, 130, 24, 256, 256, 2, 2, 2, 0);
			case "widget/tab":
				return new Region(TABS, 0, 48, 130, 24, 256, 256, 2, 2, 2, 0);
			case "widget/tab_highlighted":
				return new Region(TABS, 0, 72, 130, 24, 256, 256, 2, 2, 2, 0);
			case "server_list/unreachable":
				return Region.of(ICONS, 0, 216, 10, 8);
			default:
				break;
		}
		if (path.startsWith("server_list/ping_")) {
			// ping_5 is the best (the top row).
			return Region.of(ICONS, 0, 176 + (5 - Integer.parseInt(path.substring(17))) * 8, 10, 8);
		}
		if (path.startsWith("server_list/pinging_")) {
			return Region.of(ICONS, 10, 176 + (Integer.parseInt(path.substring(20)) - 1) * 8, 10, 8);
		}
		if (path.startsWith("hud/heart/")) {
			return heart(path.substring(10));
		}
		return null;
	}

	/** A heart, from its later name ("container_hardcore_blinking", "poisoned_half"...). */
	private static @Nullable Region heart(String name) {
		boolean hardcore = name.contains("hardcore");
		boolean blinking = name.endsWith("_blinking");
		int v = hardcore ? 45 : 0;
		if (name.startsWith("container")) {
			return Region.of(ICONS, 16 + (blinking ? 9 : 0), v, 9, 9);
		}
		boolean half = name.contains("half");
		for (int type = 0; type < HEART_TYPES.length; type++) {
			if (name.startsWith(HEART_TYPES[type])) {
				int index = HEART_INDEX[type];
				// Absorbing and frozen hearts never blink in 1.20.1.
				int column = (half ? 1 : 0) + (blinking && index < 8 ? 2 : 0);
				return Region.of(ICONS, 16 + (index * 2 + column) * 9, v, 9, 9);
			}
		}
		return null;
	}

	/** One of Orbit's sprites, from its own file (and .mcmeta for nine slices). */
	private static @Nullable Region own(ResourceLocation sprite) {
		ResourceLocation texture = new ResourceLocation(sprite.getNamespace(), "textures/gui/sprites/" + sprite.getPath() + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
		if (resource.isEmpty()) {
			return null;
		}
		try {
			int[] size = pngSize(resource.get());
			int left = 0;
			int top = 0;
			int right = 0;
			int bottom = 0;
			JsonObject scaling = resource.get().metadata().getSection(GuiScaling.SECTION).map(GuiScaling::json).orElse(null);
			if (scaling != null && "nine_slice".equals(scaling.get("type").getAsString())) {
				if (scaling.get("border").isJsonPrimitive()) {
					left = top = right = bottom = scaling.get("border").getAsInt();
				} else {
					JsonObject border = scaling.getAsJsonObject("border");
					left = border.get("left").getAsInt();
					top = border.get("top").getAsInt();
					right = border.get("right").getAsInt();
					bottom = border.get("bottom").getAsInt();
				}
			}
			return new Region(texture, 0, 0, size[0], size[1], size[0], size[1], left, top, right, bottom);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** A PNG's width and height, from its header. */
	private static int[] pngSize(Resource resource) throws IOException {
		try (DataInputStream in = new DataInputStream(resource.open())) {
			in.skipNBytes(16);
			return new int[] {in.readInt(), in.readInt()};
		}
	}

	/** The "gui" section of a sprite's .mcmeta, kept as JSON ("scaling" only). */
	private record GuiScaling(JsonObject json) {
		static final net.minecraft.server.packs.metadata.MetadataSectionSerializer<GuiScaling> SECTION =
			new net.minecraft.server.packs.metadata.MetadataSectionSerializer<>() {
				@Override
				public String getMetadataSectionName() {
					return "gui";
				}

				@Override
				public GuiScaling fromJson(JsonObject json) {
					return new GuiScaling(json.getAsJsonObject("scaling"));
				}
			};
	}
}
