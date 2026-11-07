package dev.orbit.cosmetic.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.screenshot.ImageClipboard;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One screenshot, as large as it fits, with what can be done with it: edit, favourite, copy, send to friends,
 * rename, show in its folder, delete. The arrows (or the arrow keys) go to the previous and next screenshot.
 */
public final class ScreenshotViewScreen extends OrbitScreen {
	private static final int BAR = 26;

	private ScreenshotLibrary.Shot shot;
	private EditBox nameBox;

	public ScreenshotViewScreen(@Nullable Screen parent, ScreenshotLibrary.Shot shot) {
		super(Component.literal(shot.name()), parent);
		this.shot = shot;
	}

	/** Sends a screenshot to friends: opens the friend picker (with a message). */
	public static void send(@Nullable Screen parent, ScreenshotLibrary.Shot shot) {
		dev.orbit.cosmetic.client.social.ShareScreen.screenshot(parent, shot);
	}

	@Override
	protected void init() {
		backButton(6, 5);
		nameBox = new EditBox(font, width / 2 - 90, 9, 180, 12, Component.translatable("orbit_cosmetic.screenshots.name"));
		nameBox.setBordered(false);
		nameBox.setTextColor(Theme.TEXT);
		nameBox.setMaxLength(100);
		nameBox.setValue(shot.name().endsWith(".png") ? shot.name().substring(0, shot.name().length() - 4) : shot.name());
		addRenderableWidget(nameBox);

		int y = height - BAR + 4;
		String[] actions = {"edit", "favorite", "copy", "send", "folder", "trash"};
		int size = 18;
		int total = actions.length * (size + 4) - 4;
		int x = (width - total) / 2;
		for (String action : actions) {
			String icon = action.equals("favorite") ? (shot.favorite() ? "heart" : "heart_empty") : action;
			FlatButton button = addRenderableWidget(FlatButton.icon(x, y, size, icon, Component.translatable("orbit_cosmetic.screenshots.action." + action
				+ (action.equals("favorite") && shot.favorite() ? ".remove" : "")), action.equals("trash") ? FlatButton.Style.DANGER : FlatButton.Style.SECONDARY, b -> act(action)));
			if (action.equals("favorite") && shot.favorite()) {
				button.iconColor(Theme.RED);
			}
			x += size + 4;
		}
		addRenderableWidget(FlatButton.icon(8, height / 2 - 9, 18, "chevron_left", Component.translatable("orbit_cosmetic.screenshots.previous"), FlatButton.Style.SECONDARY,
			b -> step(-1)));
		addRenderableWidget(FlatButton.icon(width - 26, height / 2 - 9, 18, "chevron_right", Component.translatable("orbit_cosmetic.screenshots.next"), FlatButton.Style.SECONDARY,
			b -> step(1)));
	}

	private void act(String action) {
		ScreenshotLibrary library = orbit().screenshots();
		switch (action) {
			case "edit" -> Compat.setScreen(new ScreenshotEditScreen(this, shot));
			case "favorite" -> {
				shot = library.toggleFavorite(shot);
				rebuildWidgets();
			}
			case "copy" -> ImageClipboard.copy(shot.path());
			case "send" -> send(this, shot);
			case "folder" -> Compat.openPath(shot.path().getParent());
			case "trash" -> Compat.setScreen(new ConfirmScreen(yes -> {
				if (yes && library.delete(shot)) {
					onClose();
				} else {
					Compat.setScreen(this);
				}
			}, Component.translatable("orbit_cosmetic.screenshots.delete.title"), Component.translatable("orbit_cosmetic.screenshots.delete.text", shot.name())));
			default -> {
			}
		}
	}

	/** Shows the previous (-1) or next (1) screenshot of the same tab, newest first. */
	private void step(int direction) {
		rename();
		List<ScreenshotLibrary.Shot> all = orbit().screenshots().list();
		int index = -1;
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).path().equals(shot.path())) {
				index = i;
			}
		}
		if (index >= 0 && index + direction >= 0 && index + direction < all.size()) {
			shot = all.get(index + direction);
			rebuildWidgets();
		}
	}

	/** Applies an edited name. */
	private void rename() {
		if (nameBox != null) {
			shot = orbit().screenshots().rename(shot, nameBox.getValue());
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!nameBox.isFocused() && (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT)) {
			step(event.key() == InputConstants.KEY_LEFT ? -1 : 1);
			return true;
		}
		if (nameBox.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			rename();
			nameBox.setFocused(false);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		rename();
		super.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, BAR, Theme.PANEL);
		graphics.fill(0, BAR - 1, width, BAR, Theme.BORDER);
		graphics.fill(0, height - BAR, width, height, Theme.PANEL);
		graphics.fill(0, height - BAR, width, height - BAR + 1, Theme.BORDER);
		ScreenshotLibrary.Thumbnail image = orbit().screenshots().thumbnail(shot.path(), 2048);
		if (image != null) {
			int areaW = width - 80;
			int areaH = height - BAR * 2 - 16;
			float scale = Math.min(areaW / (float) image.width(), areaH / (float) image.height());
			int w = Math.round(image.width() * scale);
			int h = Math.round(image.height() * scale);
			int x = (width - w) / 2;
			int y = BAR + 8 + (areaH - h) / 2;
			graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, Theme.BORDER);
			graphics.blit(image.id(), x, y, x + w, y + h, 0, 1, 0, 1);
		}
		graphics.outline(width / 2 - 94, 5, 188, 16, nameBox.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
