package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.ColorRow;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** A group's name, colour and picture (a screenshot, or its members' heads), for its leader. */
final class GroupEditScreen extends OrbitScreen {
	private static final int WIDTH = 300;
	private static final int HEIGHT = 150;

	private final Social.Group group;
	private @Nullable EditBox name;
	private @Nullable ColorRow colors;
	private String typedName;
	private int color;

	GroupEditScreen(@Nullable Screen parent, Social.Group group) {
		super(Component.translatable("orbit_cosmetic.social.group.edit"), parent);
		this.group = group;
		this.typedName = group.name();
		this.color = group.color();
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	private int left() {
		return width / 2 - WIDTH / 2;
	}

	private int top() {
		return Math.max(6, (height - HEIGHT) / 2);
	}

	@Override
	protected void init() {
		Social social = OrbitClient.get().social();
		int x = left() + 8;
		int y = top() + 28;
		name = new EditBox(font, x + 74, y + 4, WIDTH - 16 - 78, 10, Component.translatable("orbit_cosmetic.social.group.name"));
		name.setBordered(false);
		name.setMaxLength(48);
		name.setTextColor(Theme.TEXT);
		name.setHint(Component.literal(group.title(social.me())).withStyle(ChatFormatting.DARK_GRAY));
		name.setValue(typedName);
		name.setResponder(text -> typedName = text);
		addRenderableWidget(name);
		y += 24;
		colors = new ColorRow(font, x + 74, y, color, chosen -> color = chosen, this::addRenderableWidget);
		y += 24;
		int half = (WIDTH - 16 - 78 - 4) / 2;
		addRenderableWidget(new FlatButton(x + 74, y, half, 18, Component.translatable("orbit_cosmetic.social.group.picture.screenshot"), "image", FlatButton.Style.SECONDARY,
			b -> Compat.setScreen(new PickScreenshotScreen(this, shot ->
				// Cropped (square), turned and drawn on as the player likes, then used.
				Compat.setScreen(new dev.orbit.cosmetic.client.gui.ScreenshotEditScreen(this, shot, edited -> {
					social.setGroupPicture(group.id(), edited);
					Compat.setScreen(this);
				}))))));
		FlatButton heads = addRenderableWidget(new FlatButton(x + 74 + half + 4, y, half, 18, Component.translatable("orbit_cosmetic.social.group.picture.heads"), "account",
			FlatButton.Style.SECONDARY, b -> social.setGroupPicture(group.id(), (java.nio.file.Path) null)));
		heads.active = group.picture() != 0;
		int by = top() + HEIGHT - 26;
		int buttons = (WIDTH - 16 - 4) / 2;
		addRenderableWidget(new FlatButton(x, by, buttons, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
		addRenderableWidget(new FlatButton(x + buttons + 4, by, buttons, 18, Component.translatable("orbit_cosmetic.profile.save"), "check", FlatButton.Style.PRIMARY, b -> save()));
	}

	private void save() {
		Social social = OrbitClient.get().social();
		if (!typedName.strip().equals(group.name())) {
			social.renameGroup(group.id(), typedName.strip());
		}
		if (color != group.color()) {
			social.setGroupColor(group.id(), color);
		}
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = left();
		int y = top();
		Theme.panel(graphics, x, y, WIDTH, HEIGHT);
		graphics.fillGradient(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, 0xFF000000 | Theme.shade(0xFF000000 | color, 0.75F), Theme.PANEL);
		SocialPopups.face(graphics, group.id(), group.title(OrbitClient.get().social().me()), x + 8, y + 7, 12);
		graphics.text(font, title, x + 26, y + 9, Theme.TEXT, true);
		int ly = y + 33;
		graphics.text(font, Component.translatable("orbit_cosmetic.social.group.name"), x + 8, ly, Theme.TEXT, false);
		if (name != null) {
			graphics.fill(name.getX() - 4, name.getY() - 4, name.getX() + name.getWidth() + 2, name.getY() + 12, 0x88000000);
			graphics.outline(name.getX() - 4, name.getY() - 4, name.getWidth() + 6, 16, name.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
		}
		ly += 24;
		graphics.text(font, Component.translatable("orbit_cosmetic.social.group.color"), x + 8, ly, Theme.TEXT, false);
		if (colors != null) {
			colors.draw(graphics);
		}
		ly += 24;
		graphics.text(font, Component.translatable("orbit_cosmetic.social.group.picture"), x + 8, ly + 5, Theme.TEXT, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
