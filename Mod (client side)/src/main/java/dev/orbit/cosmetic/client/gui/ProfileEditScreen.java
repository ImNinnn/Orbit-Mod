package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.social.Profile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits the profile of one's statistics page: its colour, pronouns, a description, and the outfit shown on the
 * player. Saved (and shared with friends) with Save.
 */
public final class ProfileEditScreen extends OrbitScreen {
	private static final int WIDTH = 300;
	private static final int HEIGHT = 228;
	/** The colours a page can have. */
	static final int[] COLORS = {0xC21E1E, 0xE0701E, 0xD6B21E, 0x3A9D4A, 0x1E9D9D, 0x2B5FD6, 0x8A3BD6, 0xD63B8A, 0x5A5A66};

	private final List<Hotspot> swatches = new ArrayList<>();
	private int color;
	private String outfit;
	private @Nullable EditBox pronouns;
	private @Nullable MultiLineEditBox description;

	public ProfileEditScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.profile.title"), parent);
		ClientConfig config = orbit().config();
		color = config.statsColor;
		outfit = Profile.outfit() == null ? "" : config.profileOutfit;
		// What a ban blocks, as Orbit Cloud says now.
		var social = orbit().social();
		if (social.me() != null) {
			social.statsOf(social.me(), page -> {
				if (dev.orbit.cosmetic.client.compat.Compat.screen() == this) {
					rebuildWidgets();
				}
			}, error -> {
			});
		}
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	private int left() {
		return width / 2 - WIDTH / 2;
	}

	private int top() {
		return Math.max(8, (height - HEIGHT) / 2);
	}

	@Override
	protected void init() {
		ClientConfig config = orbit().config();
		int x = left() + 8;
		int y = top() + 30;
		// Typed text stays when the window is resized.
		String typedPronouns = pronouns == null ? config.profilePronouns : pronouns.getValue();
		String typedDescription = description == null ? config.profileDescription : description.getValue();

		swatches.clear();
		for (int i = 0; i < COLORS.length; i++) {
			int chosen = COLORS[i];
			Hotspot swatch = addRenderableWidget(new Hotspot(Component.translatable("orbit_cosmetic.stats.color"), h -> color = chosen));
			swatch.place(x + 70 + i * 17, y, 14, 14);
			swatch.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.stats.color")));
			swatches.add(swatch);
		}
		y += 24;
		pronouns = new EditBox(font, x + 74, y + 4, WIDTH - 16 - 78, 10, Component.translatable("orbit_cosmetic.profile.pronouns"));
		pronouns.setBordered(false);
		pronouns.setMaxLength(Profile.MAX_PRONOUNS);
		pronouns.setTextColor(Theme.TEXT);
		pronouns.setHint(Component.translatable("orbit_cosmetic.profile.pronouns.hint").withStyle(ChatFormatting.DARK_GRAY));
		pronouns.setValue(typedPronouns);
		java.util.Set<String> banned = orbit().social().restricted();
		if (banned.contains("pronouns")) {
			pronouns.setValue("");
			pronouns.setEditable(false);
			pronouns.setHint(Component.translatable("orbit_cosmetic.profile.banned").withStyle(ChatFormatting.DARK_GRAY));
		}
		addRenderableWidget(pronouns);
		y += 24;
		description = MultiLineEditBox.builder().setX(x).setY(y + 12).setTextColor(Theme.TEXT).setShowBackground(false).setShowDecorations(false)
			.setPlaceholder(Component.translatable("orbit_cosmetic.profile.description.hint").withStyle(ChatFormatting.DARK_GRAY))
			.build(font, WIDTH - 16, 52, Component.translatable("orbit_cosmetic.profile.description"));
		description.setCharacterLimit(Profile.MAX_DESCRIPTION);
		description.setLineLimit(4);
		description.setValue(banned.contains("description") ? "" : typedDescription);
		description.active = !banned.contains("description");
		addRenderableWidget(description);
		y += 72;
		List<String> outfits = new ArrayList<>();
		outfits.add("");
		for (OrbitState.Outfit saved : orbit().state().outfits()) {
			outfits.add(saved.name);
		}
		if (banned.contains("outfit")) {
			outfit = "";
		}
		var outfitCycle = addRenderableWidget(FlatCycle.of(x, y, WIDTH - 16, 18, Component.translatable("orbit_cosmetic.profile.outfit"), outfits, outfit,
			name -> name.isEmpty() ? Component.translatable("orbit_cosmetic.profile.outfit.none") : Component.literal(name),
			name -> Component.translatable(banned.contains("outfit") ? "orbit_cosmetic.profile.banned" : "orbit_cosmetic.profile.outfit.tooltip"), name -> outfit = name));
		outfitCycle.active = !banned.contains("outfit");
		int half = (WIDTH - 16 - 4) / 2;
		int by = top() + HEIGHT - 26;
		addRenderableWidget(new FlatButton(x, by, half, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
		addRenderableWidget(new FlatButton(x + half + 4, by, half, 18, Component.translatable("orbit_cosmetic.profile.save"), "check", FlatButton.Style.PRIMARY, b -> save()));
	}

	private void save() {
		ClientConfig config = orbit().config();
		config.statsColor = color;
		config.profilePronouns = pronouns.getValue().strip();
		config.profileDescription = description.getValue().strip();
		config.profileOutfit = outfit;
		config.save();
		orbit().social().shareStats();
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = left();
		int y = top();
		Theme.panel(graphics, x, y, WIDTH, HEIGHT);
		graphics.fillGradient(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, 0xFF000000 | Theme.shade(0xFF000000 | color, 0.75F), Theme.PANEL);
		graphics.text(font, title, x + 8, y + 10, Theme.TEXT, true);
		int ly = y + 33;
		graphics.text(font, Component.translatable("orbit_cosmetic.stats.color"), x + 8, ly, Theme.TEXT, false);
		for (int i = 0; i < swatches.size(); i++) {
			Hotspot swatch = swatches.get(i);
			int sx = swatch.getX();
			int sy = swatch.getY();
			boolean chosen = COLORS[i] == color;
			graphics.fill(sx, sy, sx + 14, sy + 14, chosen || swatch.isHoveredOrFocused() ? Theme.TEXT : Theme.EDGE);
			graphics.fill(sx + 1, sy + 1, sx + 13, sy + 13, 0xFF000000 | COLORS[i]);
			swatch.drawFocus(graphics);
		}
		ly += 24;
		graphics.text(font, Component.translatable("orbit_cosmetic.profile.pronouns"), x + 8, ly, Theme.TEXT, false);
		field(graphics, pronouns.getX() - 4, pronouns.getY() - 4, pronouns.getWidth() + 6, 16, pronouns.isFocused());
		ly += 24;
		graphics.text(font, Component.translatable("orbit_cosmetic.profile.description"), x + 8, ly, Theme.TEXT, false);
		String count = description.getValue().length() + " / " + Profile.MAX_DESCRIPTION;
		graphics.text(font, count, x + WIDTH - 8 - font.width(count), ly, Theme.FAINT, false);
		field(graphics, description.getX(), description.getY(), description.getWidth(), description.getHeight(), description.isFocused());
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (!description.active) {
			graphics.text(font, Component.translatable("orbit_cosmetic.profile.banned").withStyle(ChatFormatting.ITALIC), description.getX() + 4, description.getY() + 4, Theme.FAINT, false);
		}
	}

	private static void field(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean focused) {
		graphics.fill(x, y, x + w, y + h, 0x88000000);
		graphics.outline(x, y, w, h, focused ? Theme.BORDER_LIGHT : Theme.BORDER);
	}
}
