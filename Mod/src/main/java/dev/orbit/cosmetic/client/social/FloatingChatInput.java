package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.input.OrbitClick;
import dev.orbit.cosmetic.client.input.OrbitKey;
import dev.orbit.cosmetic.client.gui.ui.OrbitBaseScreen;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Typing in a floating chat window: a see-through screen with a text field over the window's message box. Enter
 * sends, Escape (or a click outside the windows) goes back to the game.
 */
final class FloatingChatInput extends OrbitBaseScreen {
	private final FloatingChats.Window window;
	private final @Nullable Screen parent;
	private EditBox box;

	FloatingChatInput(FloatingChats.Window window, @Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.social.message"));
		this.window = window;
		this.parent = parent;
	}

	FloatingChats.Window window() {
		return window;
	}

	@Nullable Screen parent() {
		return parent;
	}

	@Override
	protected void init() {
		String typed = box == null ? "" : box.getValue();
		box = new EditBox(font, window.inputX() + 4, window.inputY() + 3, window.inputW() - 8, FloatingChats.INPUT - 4, Component.translatable("orbit_cosmetic.social.message"));
		box.setBordered(false);
		box.setMaxLength(1000);
		box.setTextColor(Theme.TEXT);
		box.setValue(typed);
		addRenderableWidget(box);
		setInitialFocus(box);
		OrbitClient.get().social().view(window.friend);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (!FloatingChats.exists(window) || window.hidden) {
			onClose();
		}
	}

	@Override
	public boolean keyPressed(OrbitKey event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			String text = box.getValue().strip();
			if (!text.isEmpty()) {
				JsonObject content = new JsonObject();
				content.addProperty("text", text);
				OrbitClient.get().social().send(List.of(window.friend), content, null, () -> {
				});
				box.setValue("");
				window.scroll = 0;
				window.active = System.currentTimeMillis();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(OrbitClick event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		// Outside the windows (a click on one is used by the windows first): back to the game.
		onClose();
		return true;
	}

	@Override
	protected void drawBackground(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// The windows first, then the text field on top of its window's message box (which may have moved).
		FloatingChats.draw(graphics, mouseX, mouseY, true);
		box.setX(window.inputX() + 4);
		box.setY(window.inputY() + 3);
		box.setWidth(window.inputW() - 8);
		graphics.layer();
		drawWidgets(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		Compat.setScreen(parent);
	}

	@Override
	public void removed() {
		OrbitClient.get().social().view(null);
	}
}
