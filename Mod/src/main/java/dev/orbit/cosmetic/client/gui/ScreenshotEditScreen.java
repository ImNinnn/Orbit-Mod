package dev.orbit.cosmetic.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * Edits a screenshot: draw on it (colour, opacity and brush size), crop it with the handles on its corners and
 * sides, rotate it, rename it. Saving writes a copy ("Copy of ..."), so the original stays as it was. As a picture
 * picker (a group chat's picture) the crop stays square and Use hands the edited picture back instead.
 */
public final class ScreenshotEditScreen extends OrbitScreen {
	private static final int BAR = 26;
	private static final int TOOLS = 118;
	private static final int PICKER = 80;
	private static final int HANDLE = 6;
	private static final int[] BRUSHES = {2, 4, 8, 14};
	private static final int[] SWATCHES = {0xFFE02626, 0xFFFFCC4D, 0xFF3CB043, 0xFF2F7FFF, 0xFF141414, 0xFFFFFFFF};
	private static final int MAX_UNDO = 15;

	private final ScreenshotLibrary.Shot shot;
	/** Picking a square picture: gets the edited image (its owner closes it); null when editing a screenshot. */
	private final @Nullable Consumer<NativeImage> picked;
	private @Nullable NativeImage image;
	private @Nullable Identifier textureId;
	private int textureCount;
	private final Deque<NativeImage> undo = new ArrayDeque<>();
	private final Deque<NativeImage> redo = new ArrayDeque<>();
	/** The kept part of the image: x0, y0, x1, y1 in image pixels. */
	private final int[] crop = new int[4];
	private float hue;
	private float saturation = 0.85F;
	private float value = 0.9F;
	private float opacity = 1;
	private int brush = 1;
	private EditBox nameBox;

	/** What the mouse is doing: drawing, moving a crop handle (0-7), or picking a colour. */
	private enum Drag { NONE, DRAW, HANDLE, SV, HUE, ALPHA }

	private Drag drag = Drag.NONE;
	private int handle;
	private double lastX;
	private double lastY;

	public ScreenshotEditScreen(@Nullable Screen parent, ScreenshotLibrary.Shot shot) {
		this(parent, shot, null);
	}

	/** Crops (square), rotates and draws on a screenshot for a picture; {@code picked} gets the result. */
	public ScreenshotEditScreen(@Nullable Screen parent, ScreenshotLibrary.Shot shot, @Nullable Consumer<NativeImage> picked) {
		super(Component.translatable(picked != null ? "orbit_cosmetic.screenshots.edit.picture" : "orbit_cosmetic.screenshots.edit.title"), parent);
		this.shot = shot;
		this.picked = picked;
		Util.ioPool().execute(() -> {
			NativeImage loaded = null;
			try (InputStream in = Files.newInputStream(shot.path())) {
				loaded = NativeImage.read(in);
			} catch (IOException e) {
				OrbitClient.LOGGER.warn("Could not open {}", shot.path(), e);
			}
			NativeImage result = loaded;
			Minecraft.getInstance().execute(() -> {
				if (result == null) {
					Notifications.error(Component.translatable("orbit_cosmetic.screenshots.failed"), Component.literal(shot.name()));
					return;
				}
				setImage(result);
				crop[0] = 0;
				crop[1] = 0;
				crop[2] = result.getWidth();
				crop[3] = result.getHeight();
				squareCrop();
			});
		});
	}

	@Override
	protected void init() {
		backButton(6, 5);
		String name = nameBox != null ? nameBox.getValue() : Component.translatable("orbit_cosmetic.screenshots.edit.copy_name", stripPng(shot.name())).getString();
		nameBox = new EditBox(font, width / 2 - 90, 9, 180, 12, Component.translatable("orbit_cosmetic.screenshots.name"));
		nameBox.setBordered(false);
		nameBox.setTextColor(Theme.TEXT);
		nameBox.setMaxLength(100);
		nameBox.setValue(name);
		nameBox.visible = picked == null;
		addRenderableWidget(nameBox);
		Component saveLabel = Component.translatable(picked != null ? "orbit_cosmetic.screenshots.edit.use" : "orbit_cosmetic.screenshots.edit.save");
		int saveW = font.width(saveLabel) + 30;
		addRenderableWidget(new FlatButton(width - 8 - saveW, height - 24, saveW, 18, saveLabel, "check", FlatButton.Style.PRIMARY, b -> save()));

		int x = 8;
		int y = toolsTop() + PICKER + 34;
		for (int i = 0; i < BRUSHES.length; i++) {
			int index = i;
			FlatButton size = addRenderableWidget(new FlatButton(x + i * 20, y, 18, 18, Component.empty(), null, FlatButton.Style.SECONDARY, b -> {
				brush = index;
				rebuildWidgets();
			}));
			size.setSelected(i == brush);
			size.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("orbit_cosmetic.screenshots.edit.brush", BRUSHES[i])));
		}
		y += 22;
		addRenderableWidget(FlatButton.icon(x, y, 18, "undo", Component.translatable("orbit_cosmetic.screenshots.edit.undo"), FlatButton.Style.SECONDARY, b -> undo()))
			.active = !undo.isEmpty();
		addRenderableWidget(FlatButton.icon(x + 20, y, 18, "redo", Component.translatable("orbit_cosmetic.screenshots.edit.redo"), FlatButton.Style.SECONDARY, b -> redo()))
			.active = !redo.isEmpty();
		addRenderableWidget(FlatButton.icon(x + 40, y, 18, "rotate", Component.translatable("orbit_cosmetic.screenshots.edit.rotate"), FlatButton.Style.SECONDARY, b -> rotate()));
		addRenderableWidget(FlatButton.icon(x + 60, y, 18, "crop", Component.translatable("orbit_cosmetic.screenshots.edit.reset_crop"), FlatButton.Style.SECONDARY, b -> {
			if (image != null) {
				crop[0] = 0;
				crop[1] = 0;
				crop[2] = image.getWidth();
				crop[3] = image.getHeight();
				squareCrop();
			}
		}));
	}

	/** For a picture: the largest square in the middle of the crop. */
	private void squareCrop() {
		if (picked == null) {
			return;
		}
		int w = crop[2] - crop[0];
		int h = crop[3] - crop[1];
		int side = Math.min(w, h);
		crop[0] += (w - side) / 2;
		crop[1] += (h - side) / 2;
		crop[2] = crop[0] + side;
		crop[3] = crop[1] + side;
	}

	private int toolsTop() {
		return Math.max(BAR + 8, height - 200);
	}

	private static String stripPng(String name) {
		return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
	}

	// ---------------------------------------------------------------- the image

	private void setImage(NativeImage value) {
		if (textureId != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
		}
		image = value;
		textureId = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "screenshot_edit/" + textureCount++);
		// The texture takes the image; edits change its pixels and upload them again.
		Minecraft.getInstance().getTextureManager().register(textureId, new DynamicTexture(() -> "Orbit screenshot editor", copy(value)));
	}

	private void upload() {
		if (image != null && textureId != null && Minecraft.getInstance().getTextureManager().getTexture(textureId) instanceof DynamicTexture texture
			&& texture.getPixels() != null) {
			texture.getPixels().copyFrom(image);
			texture.upload();
		}
	}

	private static NativeImage copy(NativeImage source) {
		return source.mappedCopy(IntUnaryOperator.identity());
	}

	private void remember() {
		if (image == null) {
			return;
		}
		undo.push(copy(image));
		while (undo.size() > MAX_UNDO) {
			undo.removeLast().close();
		}
		redo.forEach(NativeImage::close);
		redo.clear();
	}

	private void undo() {
		if (image == null || undo.isEmpty()) {
			return;
		}
		redo.push(image);
		NativeImage previous = undo.pop();
		replace(previous);
		rebuildWidgets();
	}

	private void redo() {
		if (image == null || redo.isEmpty()) {
			return;
		}
		undo.push(image);
		replace(redo.pop());
		rebuildWidgets();
	}

	/** Shows another version of the image (after undo, redo or rotate); the crop is kept inside it. */
	private void replace(NativeImage next) {
		boolean resized = image == null || image.getWidth() != next.getWidth() || image.getHeight() != next.getHeight();
		NativeImage old = image;
		setImage(next);
		if (resized) {
			crop[0] = 0;
			crop[1] = 0;
			crop[2] = next.getWidth();
			crop[3] = next.getHeight();
			squareCrop();
		}
		if (old != null && !undo.contains(old) && !redo.contains(old)) {
			old.close();
		}
	}

	/** Turns the image a quarter turn clockwise. */
	private void rotate() {
		if (image == null) {
			return;
		}
		remember();
		NativeImage turned = new NativeImage(image.getHeight(), image.getWidth(), false);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				turned.setPixel(image.getHeight() - 1 - y, x, image.getPixel(x, y));
			}
		}
		NativeImage old = image;
		image = null;
		setImage(turned);
		crop[0] = 0;
		crop[1] = 0;
		crop[2] = turned.getWidth();
		crop[3] = turned.getHeight();
		squareCrop();
		if (!undo.contains(old)) {
			old.close();
		}
		rebuildWidgets();
	}

	/** Where the image is drawn: x, y, width, height (GUI pixels), and the scale. */
	private float[] canvas() {
		int areaX = TOOLS + 8;
		int areaY = BAR + 10;
		int areaW = width - areaX - 12;
		int areaH = height - BAR - 10 - 32;
		if (image == null) {
			return new float[] {areaX, areaY, areaW, areaH, 1};
		}
		float scale = Math.min(areaW / (float) image.getWidth(), areaH / (float) image.getHeight());
		float w = image.getWidth() * scale;
		float h = image.getHeight() * scale;
		return new float[] {areaX + (areaW - w) / 2, areaY + (areaH - h) / 2, w, h, scale};
	}

	private int color() {
		int rgb = java.awt.Color.HSBtoRGB(hue, saturation, value) & 0xFFFFFF;
		return Math.round(opacity * 255) << 24 | rgb;
	}

	/** Paints a stroke from (x0, y0) to (x1, y1), in image pixels. */
	private void paint(double x0, double y0, double x1, double y1, float scale) {
		if (image == null) {
			return;
		}
		double radius = Math.max(0.5, BRUSHES[brush] / 2.0 / scale);
		double length = Math.hypot(x1 - x0, y1 - y0);
		int steps = Math.max(1, (int) Math.ceil(length / Math.max(0.5, radius / 2)));
		int color = color();
		for (int i = 0; i <= steps; i++) {
			double cx = x0 + (x1 - x0) * i / steps;
			double cy = y0 + (y1 - y0) * i / steps;
			dot(cx, cy, radius, color, i == 0);
		}
	}

	/** A filled circle; strokes do not darken where they overlap themselves (each pixel is painted once per dot). */
	private void dot(double cx, double cy, double radius, int color, boolean first) {
		int alpha = color >>> 24;
		for (int y = (int) Math.floor(cy - radius); y <= Math.ceil(cy + radius); y++) {
			for (int x = (int) Math.floor(cx - radius); x <= Math.ceil(cx + radius); x++) {
				if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight() || (x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) > radius * radius) {
					continue;
				}
				if (alpha >= 255) {
					image.setPixel(x, y, color);
				} else {
					int under = image.getPixel(x, y);
					image.setPixel(x, y, blend(under, color, alpha / 255F));
				}
			}
		}
	}

	private static int blend(int under, int over, float t) {
		int r = Math.round((under >> 16 & 0xFF) * (1 - t) + (over >> 16 & 0xFF) * t);
		int g = Math.round((under >> 8 & 0xFF) * (1 - t) + (over >> 8 & 0xFF) * t);
		int b = Math.round((under & 0xFF) * (1 - t) + (over & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private void save() {
		if (image == null) {
			return;
		}
		int w = crop[2] - crop[0];
		int h = crop[3] - crop[1];
		NativeImage out = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out.setPixel(x, y, image.getPixel(crop[0] + x, crop[1] + y));
			}
		}
		if (picked != null) {
			picked.accept(out);
			return;
		}
		String name = ScreenshotLibrary.cleanName(nameBox.getValue());
		Path target = ScreenshotLibrary.unique(shot.path().getParent(), (name.isEmpty() ? stripPng(shot.name()) + " (edited)" : name) + ".png");
		Util.ioPool().execute(() -> {
			try (out) {
				out.writeToFile(target);
				Minecraft.getInstance().execute(() -> {
					Notifications.success(Component.translatable("orbit_cosmetic.screenshots.edit.saved"), Component.literal(target.getFileName().toString()));
					Compat.setScreen(new ScreenshotViewScreen(parent, orbit().screenshots().shot(target)));
				});
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> Notifications.error(Component.translatable("orbit_cosmetic.screenshots.failed"), Component.literal(e.getMessage())));
			}
		});
	}

	// ---------------------------------------------------------------- input

	/** The crop handles: corners then sides, as x, y on screen. */
	private float[][] handles() {
		float[] c = canvas();
		float s = c[4];
		float x0 = c[0] + crop[0] * s;
		float y0 = c[1] + crop[1] * s;
		float x1 = c[0] + crop[2] * s;
		float y1 = c[1] + crop[3] * s;
		if (picked != null) {
			// A square crop has its corners only.
			return new float[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}};
		}
		return new float[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}, {(x0 + x1) / 2, y0}, {(x0 + x1) / 2, y1}, {x0, (y0 + y1) / 2}, {x1, (y0 + y1) / 2}};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		int px = 8;
		int py = toolsTop();
		if (Theme.inside(mx, my, px, py, PICKER, PICKER)) {
			drag = Drag.SV;
			pick(mx, my);
			return true;
		}
		if (Theme.inside(mx, my, px + PICKER + 6, py, 10, PICKER)) {
			drag = Drag.HUE;
			pick(mx, my);
			return true;
		}
		if (Theme.inside(mx, my, px, py + PICKER + 6, PICKER + 16, 8)) {
			drag = Drag.ALPHA;
			pick(mx, my);
			return true;
		}
		for (int i = 0; i < SWATCHES.length; i++) {
			if (Theme.inside(mx, my, px + i * 16, py + PICKER + 18, 13, 10)) {
				float[] hsb = java.awt.Color.RGBtoHSB(SWATCHES[i] >> 16 & 0xFF, SWATCHES[i] >> 8 & 0xFF, SWATCHES[i] & 0xFF, null);
				hue = hsb[0];
				saturation = hsb[1];
				value = hsb[2];
				return true;
			}
		}
		if (image == null) {
			return false;
		}
		float[][] handles = handles();
		for (int i = 0; i < handles.length; i++) {
			if (Math.abs(mx - handles[i][0]) <= HANDLE && Math.abs(my - handles[i][1]) <= HANDLE) {
				drag = Drag.HANDLE;
				handle = i;
				return true;
			}
		}
		float[] c = canvas();
		if (Theme.inside(mx, my, (int) c[0], (int) c[1], (int) c[2] + 1, (int) c[3] + 1)) {
			remember();
			drag = Drag.DRAW;
			lastX = (mx - c[0]) / c[4];
			lastY = (my - c[1]) / c[4];
			paint(lastX, lastY, lastX, lastY, c[4]);
			upload();
			return true;
		}
		return false;
	}

	private void pick(double mx, double my) {
		int px = 8;
		int py = toolsTop();
		switch (drag) {
			case SV -> {
				saturation = (float) Math.clamp((mx - px) / PICKER, 0, 1);
				value = (float) Math.clamp(1 - (my - py) / PICKER, 0, 1);
			}
			case HUE -> hue = (float) Math.clamp((my - py) / PICKER, 0, 0.999);
			case ALPHA -> opacity = (float) Math.clamp((mx - px) / (PICKER + 16), 0.05, 1);
			default -> {
			}
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (drag == Drag.SV || drag == Drag.HUE || drag == Drag.ALPHA) {
			pick(event.x(), event.y());
			return true;
		}
		if (image == null) {
			return super.mouseDragged(event, dx, dy);
		}
		float[] c = canvas();
		double ix = (event.x() - c[0]) / c[4];
		double iy = (event.y() - c[1]) / c[4];
		if (drag == Drag.DRAW) {
			paint(lastX, lastY, ix, iy, c[4]);
			lastX = ix;
			lastY = iy;
			upload();
			return true;
		}
		if (drag == Drag.HANDLE && picked != null) {
			// A square: a corner moves, the opposite one stays, and the square stays inside the image.
			if (handle > 3) {
				return true;
			}
			boolean left = handle == 0 || handle == 2;
			boolean top = handle == 0 || handle == 1;
			int ax = left ? crop[2] : crop[0];
			int ay = top ? crop[3] : crop[1];
			int room = Math.min(left ? ax : image.getWidth() - ax, top ? ay : image.getHeight() - ay);
			int side = (int) Math.round(Math.clamp(Math.max(Math.abs(ix - ax), Math.abs(iy - ay)), 16, Math.max(16, room)));
			crop[0] = left ? ax - side : ax;
			crop[2] = left ? ax : ax + side;
			crop[1] = top ? ay - side : ay;
			crop[3] = top ? ay : ay + side;
			return true;
		}
		if (drag == Drag.HANDLE) {
			int x = (int) Math.round(Math.clamp(ix, 0, image.getWidth()));
			int y = (int) Math.round(Math.clamp(iy, 0, image.getHeight()));
			int min = 16;
			boolean left = handle == 0 || handle == 2 || handle == 6;
			boolean right = handle == 1 || handle == 3 || handle == 7;
			boolean top = handle == 0 || handle == 1 || handle == 4;
			boolean bottom = handle == 2 || handle == 3 || handle == 5;
			if (left) {
				crop[0] = Math.min(x, crop[2] - min);
			}
			if (right) {
				crop[2] = Math.max(x, crop[0] + min);
			}
			if (top) {
				crop[1] = Math.min(y, crop[3] - min);
			}
			if (bottom) {
				crop[3] = Math.max(y, crop[1] + min);
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (drag == Drag.DRAW) {
			rebuildWidgets();
		}
		drag = Drag.NONE;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Ctrl+Z undoes, Ctrl+Shift+Z or Ctrl+Y redoes (the name box has no undo of its own).
		if (event.hasControlDownWithQuirk() && !event.hasAltDown() && (Compat.isLetter(event, 'z') || Compat.isLetter(event, 'y'))) {
			if (event.hasShiftDown() || Compat.isLetter(event, 'y')) {
				redo();
			} else {
				undo();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		if (textureId != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
		}
		if (image != null) {
			image.close();
		}
		undo.forEach(NativeImage::close);
		redo.forEach(NativeImage::close);
		undo.clear();
		redo.clear();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, BAR, Theme.PANEL);
		graphics.fill(0, BAR - 1, width, BAR, Theme.BORDER);
		if (picked == null) {
			graphics.outline(width / 2 - 94, 5, 188, 16, nameBox.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
		} else {
			graphics.centeredText(font, title, width / 2, 9, Theme.TEXT);
		}
		float[] c = canvas();
		if (image != null && textureId != null) {
			int x = Math.round(c[0]);
			int y = Math.round(c[1]);
			int w = Math.round(c[2]);
			int h = Math.round(c[3]);
			graphics.blit(textureId, x, y, x + w, y + h, 0, 1, 0, 1);
			// Outside the crop: darker.
			float s = c[4];
			int cx0 = Math.round(c[0] + crop[0] * s);
			int cy0 = Math.round(c[1] + crop[1] * s);
			int cx1 = Math.round(c[0] + crop[2] * s);
			int cy1 = Math.round(c[1] + crop[3] * s);
			graphics.fill(x, y, x + w, cy0, 0x99000000);
			graphics.fill(x, cy1, x + w, y + h, 0x99000000);
			graphics.fill(x, cy0, cx0, cy1, 0x99000000);
			graphics.fill(cx1, cy0, x + w, cy1, 0x99000000);
			graphics.outline(cx0, cy0, cx1 - cx0, cy1 - cy0, 0xC0FFFFFF);
			for (float[] handlePoint : handles()) {
				int hx = Math.round(handlePoint[0]);
				int hy = Math.round(handlePoint[1]);
				graphics.fill(hx - 3, hy - 3, hx + 3, hy + 3, 0xFFE6E6EA);
				graphics.outline(hx - 3, hy - 3, 6, 6, Theme.EDGE);
			}
		} else {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.screenshots.loading"), width / 2, height / 2, Theme.MUTED);
		}

		// The colour picker: saturation and brightness, hue, opacity, a few colours, the brush sizes.
		int px = 8;
		int py = toolsTop();
		Theme.panel(graphics, 4, py - 4, TOOLS - 4, height - py - 2);
		int top = java.awt.Color.HSBtoRGB(hue, 1, 1) | 0xFF000000;
		for (int i = 0; i < PICKER; i += 2) {
			int column = java.awt.Color.HSBtoRGB(hue, i / (float) PICKER, 1) | 0xFF000000;
			graphics.fillGradient(px + i, py, px + i + 2, py + PICKER, column, 0xFF000000);
		}
		int markX = px + Math.round(saturation * PICKER);
		int markY = py + Math.round((1 - value) * PICKER);
		graphics.outline(markX - 2, markY - 2, 5, 5, 0xFFFFFFFF);
		for (int i = 0; i < 6; i++) {
			int a = java.awt.Color.HSBtoRGB(i / 6F, 1, 1) | 0xFF000000;
			int b = java.awt.Color.HSBtoRGB((i + 1) / 6F, 1, 1) | 0xFF000000;
			int y0 = py + PICKER * i / 6;
			int y1 = py + PICKER * (i + 1) / 6;
			graphics.fillGradient(px + PICKER + 6, y0, px + PICKER + 16, y1, a, b);
		}
		int hueY = py + Math.round(hue * PICKER);
		graphics.fill(px + PICKER + 4, hueY - 1, px + PICKER + 18, hueY + 1, 0xFFFFFFFF);
		int rgb = color() & 0xFFFFFF;
		int barW = PICKER + 16;
		for (int i = 0; i < barW; i += 2) {
			graphics.fill(px + i, py + PICKER + 6, px + i + 2, py + PICKER + 14, ((i / 4) % 2 == 0 ? 0xFF8A8A8A : 0xFF5A5A5A));
			graphics.fill(px + i, py + PICKER + 6, px + i + 2, py + PICKER + 14, Math.round(i / (float) barW * 255) << 24 | rgb);
		}
		int alphaX = px + Math.round(opacity * barW);
		graphics.fill(alphaX - 1, py + PICKER + 4, alphaX + 1, py + PICKER + 16, 0xFFFFFFFF);
		for (int i = 0; i < SWATCHES.length; i++) {
			graphics.fill(px + i * 16, py + PICKER + 18, px + i * 16 + 13, py + PICKER + 28, SWATCHES[i]);
			graphics.outline(px + i * 16, py + PICKER + 18, 13, 10, Theme.BORDER_LIGHT);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// The brush sizes, drawn over their buttons in the current colour.
		int by = py + PICKER + 34;
		for (int i = 0; i < BRUSHES.length; i++) {
			int size = Math.min(12, BRUSHES[i]);
			int bx = px + i * 20 + 9;
			graphics.fill(bx - size / 2, by + 9 - size / 2, bx - size / 2 + Math.max(2, size), by + 9 - size / 2 + Math.max(2, size), color() | 0xFF000000);
		}
		graphics.fill(px + 84, by, px + 102, by + 18, color() | 0xFF000000);
		graphics.outline(px + 84, by, 18, 18, Theme.BORDER_LIGHT);
	}
}
