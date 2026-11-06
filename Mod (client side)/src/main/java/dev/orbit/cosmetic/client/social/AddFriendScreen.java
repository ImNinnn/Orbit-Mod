package dev.orbit.cosmetic.client.social;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Asks for a player name and sends them a friend request. */
final class AddFriendScreen extends OrbitScreen {
	private EditBox name;

	AddFriendScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.social.add_friend"), parent);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	@Override
	protected void init() {
		int x = width / 2 - 100;
		int y = height / 2 - 30;
		name = new EditBox(font, x + 6, y + 30, 188, 12, Component.translatable("orbit_cosmetic.social.player_name"));
		name.setBordered(false);
		name.setMaxLength(16);
		name.setTextColor(Theme.TEXT);
		name.setHint(Component.translatable("orbit_cosmetic.social.player_name").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(name);
		setInitialFocus(name);
		addRenderableWidget(new FlatButton(x, y + 50, 98, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
		addRenderableWidget(new FlatButton(x + 102, y + 50, 98, 18, Component.translatable("orbit_cosmetic.social.send_request"), "person_add", FlatButton.Style.PRIMARY, b -> send()));
	}

	private void send() {
		if (name.getValue().matches("[A-Za-z0-9_]{1,16}")) {
			OrbitClient.get().social().request(name.getValue(), this::onClose);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			send();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = width / 2 - 108;
		int y = height / 2 - 40;
		Theme.panel(graphics, x, y, 216, 88);
		graphics.text(font, title, x + 8, y + 8, Theme.TEXT, false);
		graphics.text(font, Component.translatable("orbit_cosmetic.social.add_friend.text").withStyle(ChatFormatting.GRAY), x + 8, y + 20, Theme.TEXT, false);
		graphics.fill(x + 8, y + 36, x + 208, y + 52, Theme.PANEL_2);
		graphics.outline(x + 8, y + 36, 200, 16, name.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
