package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.common.model.ModelLimits;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Orbit's settings, by category: each setting is a row with its name, what it does, and its control. */
public final class OrbitSettingsScreen extends OrbitScreen {
	private static final int HEADER = 22;
	private static final int ROW_GAP = 4;

	enum Category {
		GENERAL,
		APPEARANCE,
		EMOTES,
		CLOUD,
		PERFORMANCE,
		ADVANCED;

		Component label() {
			return Component.translatable("orbit_cosmetic.settings.category." + name().toLowerCase(Locale.ROOT));
		}
	}

	/** One setting. Rows draw themselves and handle their own clicks. */
	private abstract static class Row {
		final Component title;
		final @Nullable Component description;

		Row(Component title, @Nullable Component description) {
			this.title = title;
			this.description = description;
		}

		int height(OrbitSettingsScreen screen, int width) {
			int lines = description == null ? 0 : Math.min(3, screen.font.split(description, width - controlWidth() - 24).size());
			return Math.max(24, 12 + 10 + lines * 9);
		}

		int controlWidth() {
			return 30;
		}

		void draw(OrbitSettingsScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
			Theme.panel(graphics, x, y, width, height, Theme.PANEL);
			graphics.text(screen.font, title.copy().withStyle(ChatFormatting.BOLD), x + 8, y + 7, Theme.TEXT, false);
			if (description != null) {
				int lineY = y + 18;
				for (FormattedCharSequence line : screen.font.split(description, width - controlWidth() - 24)) {
					if (lineY > y + height - 9) {
						break;
					}
					graphics.text(screen.font, line, x + 8, lineY, Theme.MUTED, false);
					lineY += 9;
				}
			}
		}

		void click(OrbitSettingsScreen screen, double mouseX, double mouseY, int x, int y, int width, int height) {
		}

		void drag(double mouseX, int x, int width) {
		}
	}

	/** A section heading inside a category. */
	private static final class Heading extends Row {
		Heading(Component title) {
			super(title, null);
		}

		@Override
		int height(OrbitSettingsScreen screen, int width) {
			return 16;
		}

		@Override
		void draw(OrbitSettingsScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
			Theme.separator(graphics, screen.font, x, y + 9, width, title);
		}
	}

	private static final class Toggle extends Row {
		private final BooleanSupplier value;
		private final Consumer<Boolean> setter;

		Toggle(String key, BooleanSupplier value, Consumer<Boolean> setter) {
			super(Component.translatable("orbit_cosmetic.settings." + key), Component.translatable("orbit_cosmetic.settings." + key + ".tooltip"));
			this.value = value;
			this.setter = setter;
		}

		@Override
		void draw(OrbitSettingsScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
			super.draw(screen, graphics, x, y, width, height, mouseX, mouseY);
			boolean on = value.getAsBoolean();
			int sx = x + width - 30;
			int sy = y + (height - 10) / 2;
			graphics.fill(sx, sy, sx + 20, sy + 10, on ? Theme.BLUE : Theme.PANEL_2);
			graphics.outline(sx, sy, 20, 10, on ? Theme.BLUE : Theme.BORDER_LIGHT);
			graphics.fill(on ? sx + 11 : sx + 1, sy + 1, on ? sx + 19 : sx + 9, sy + 9, on ? Theme.TEXT : Theme.FAINT);
		}

		@Override
		void click(OrbitSettingsScreen screen, double mouseX, double mouseY, int x, int y, int width, int height) {
			setter.accept(!value.getAsBoolean());
			save();
		}
	}

	private static final class Slider extends Row {
		private final double min;
		private final double max;
		private final double step;
		private final DoubleSupplier value;
		private final DoubleConsumer setter;
		private final DoubleFunction<String> format;

		Slider(String key, double min, double max, double step, DoubleSupplier value, DoubleConsumer setter, DoubleFunction<String> format) {
			super(Component.translatable("orbit_cosmetic.settings." + key), Component.translatable("orbit_cosmetic.settings." + key + ".tooltip"));
			this.min = min;
			this.max = max;
			this.step = step;
			this.value = value;
			this.setter = setter;
			this.format = format;
		}

		@Override
		int controlWidth() {
			return 96;
		}

		@Override
		void draw(OrbitSettingsScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
			super.draw(screen, graphics, x, y, width, height, mouseX, mouseY);
			int sx = x + width - 96;
			int sy = y + height / 2 + 2;
			double fraction = (value.getAsDouble() - min) / (max - min);
			graphics.fill(sx, sy, sx + 86, sy + 2, Theme.PANEL_2);
			graphics.fill(sx, sy, sx + (int) (86 * fraction), sy + 2, Theme.BLUE);
			int knob = sx + (int) (86 * fraction);
			graphics.fill(knob - 2, sy - 3, knob + 3, sy + 5, Theme.TEXT);
			String text = format.apply(value.getAsDouble());
			graphics.text(screen.font, text, sx + 86 - screen.font.width(text), sy - 13, Theme.MUTED, false);
		}

		@Override
		void click(OrbitSettingsScreen screen, double mouseX, double mouseY, int x, int y, int width, int height) {
			drag(mouseX, x, width);
		}

		@Override
		void drag(double mouseX, int x, int width) {
			int sx = x + width - 96;
			double fraction = Mth.clamp((mouseX - sx) / 86.0, 0, 1);
			double raw = min + fraction * (max - min);
			setter.accept(Mth.clamp(Math.round(raw / step) * step, min, max));
			save();
		}
	}

	/** A row with a button on the right. */
	private static final class Action extends Row {
		private final Supplier<Component> label;
		private final Runnable action;

		Action(String key, Supplier<Component> label, Runnable action) {
			super(Component.translatable("orbit_cosmetic.settings." + key), Component.translatable("orbit_cosmetic.settings." + key + ".tooltip"));
			this.label = label;
			this.action = action;
		}

		@Override
		int controlWidth() {
			return 90;
		}

		@Override
		void draw(OrbitSettingsScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
			super.draw(screen, graphics, x, y, width, height, mouseX, mouseY);
			int bx = x + width - 90;
			int by = y + (height - 16) / 2;
			boolean hovered = Theme.inside(mouseX, mouseY, bx, by, 82, 16);
			graphics.fill(bx, by, bx + 82, by + 16, hovered ? Theme.PANEL_HOVER : Theme.PANEL_2);
			graphics.outline(bx, by, 82, 16, Theme.BORDER);
			graphics.centeredText(screen.font, Theme.clip(screen.font, label.get(), 76), bx + 41, by + 4, Theme.TEXT);
		}

		@Override
		void click(OrbitSettingsScreen screen, double mouseX, double mouseY, int x, int y, int width, int height) {
			if (Theme.inside(mouseX, mouseY, x + width - 90, y + (height - 16) / 2, 82, 16)) {
				action.run();
			}
		}
	}

	/** A choice between named values, cycled by clicking. */
	private static final class Choice<E extends Enum<E>> extends Row {
		private final E[] values;
		private final Supplier<E> value;
		private final Consumer<E> setter;
		private final String key;

		Choice(String key, E[] values, Supplier<E> value, Consumer<E> setter) {
			super(Component.translatable("orbit_cosmetic.settings." + key), Component.translatable("orbit_cosmetic.settings." + key + ".tooltip"));
			this.key = key;
			this.values = values;
			this.value = value;
			this.setter = setter;
		}

		@Override
		int controlWidth() {
			return 90;
		}

		@Override
		void draw(OrbitSettingsScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
			super.draw(screen, graphics, x, y, width, height, mouseX, mouseY);
			int bx = x + width - 90;
			int by = y + (height - 16) / 2;
			graphics.fill(bx, by, bx + 82, by + 16, Theme.PANEL_2);
			graphics.outline(bx, by, 82, 16, Theme.BORDER);
			Component label = Component.translatable("orbit_cosmetic.settings." + key + "." + value.get().name().toLowerCase(Locale.ROOT));
			graphics.text(screen.font, Theme.clip(screen.font, label, 64), bx + 5, by + 4, Theme.TEXT, false);
			Theme.icon(graphics, "chevron_down", bx + 70, by + 4, Theme.MUTED);
		}

		@Override
		void click(OrbitSettingsScreen screen, double mouseX, double mouseY, int x, int y, int width, int height) {
			setter.accept(values[(value.get().ordinal() + 1) % values.length]);
			save();
		}
	}

	private static Category category = Category.GENERAL;
	private final List<Row> rows = new ArrayList<>();
	private double scroll;
	private int contentHeight;
	private @Nullable Row dragging;
	private long cacheBytes = -1;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int sideW;
	private int contentX;
	private int contentY;
	private int contentW;
	private int contentH;

	public OrbitSettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.settings.title"), parent);
	}

	private static ClientConfig config() {
		return OrbitClient.get().config();
	}

	private static void save() {
		config().normalize();
		config().save();
	}

	@Override
	protected void init() {
		panelW = Math.min(width - 40, 520);
		panelH = Math.min(height - 16, 320);
		panelX = Math.max(30, (width - panelW) / 2);
		panelW = Math.min(panelW, width - panelX - 8);
		panelY = (height - panelH) / 2;
		sideW = Math.clamp(panelW / 4, 96, 130);
		contentX = panelX + sideW + 8;
		contentY = panelY + HEADER + 6;
		contentW = panelW - sideW - 20;
		contentH = panelH - HEADER - 12;
		backButton(panelX - 22, panelY);
		int y = panelY + HEADER + 4;
		for (Category entry : Category.values()) {
			addRenderableWidget(new FlatButton(panelX + 4, y, sideW - 8, 16, entry.label(), null, FlatButton.Style.GHOST, b -> {
				category = entry;
				scroll = 0;
				rebuildWidgets();
			}).alignLeft()).setSelected(entry == category);
			y += 17;
		}
		buildRows();
		Util.ioPool().execute(() -> {
			long size = OrbitClient.get().cache().sizeBytes();
			minecraft.execute(() -> cacheBytes = size);
		});
	}

	private void buildRows() {
		rows.clear();
		ClientConfig config = config();
		switch (category) {
			case GENERAL -> {
				if (dev.orbit.cosmetic.client.Updates.available() != null) {
					// A newer Orbit is out: first, where it is seen.
					rows.add(new Action("update_now", () -> Component.translatable("orbit_cosmetic.settings.update_now.button", dev.orbit.cosmetic.client.Updates.available()),
						() -> Compat.setScreen(new UpdatePromptScreen(this))));
				}
				rows.add(new Toggle("render", () -> config.renderCosmetics, v -> config.renderCosmetics = v));
				rows.add(new Toggle("others", () -> config.showOtherPlayers, v -> config.showOtherPlayers = v));
				rows.add(new Toggle("first_person", () -> config.showInFirstPerson, v -> config.showInFirstPerson = v));
				rows.add(new Toggle("animations", () -> config.playAnimations, v -> config.playAnimations = v));
				rows.add(new Toggle("smooth_animations", () -> config.smoothAnimations, v -> config.smoothAnimations = v));
				rows.add(new Toggle("notifications", () -> config.showNotifications, v -> config.showNotifications = v));
				rows.add(new Toggle("updates", () -> config.checkUpdates, v -> config.checkUpdates = v));
				rows.add(new Toggle("screenshot_notifications", () -> config.screenshotNotifications, v -> config.screenshotNotifications = v));
				rows.add(new Toggle("friend_online", () -> config.friendOnlineNotifications, v -> config.friendOnlineNotifications = v));
				rows.add(new Toggle("discord", () -> config.discordPresence, v -> config.discordPresence = v));
			}
			case APPEARANCE -> {
				rows.add(new Toggle("menu_player", () -> config.menuPlayer, v -> config.menuPlayer = v));
				rows.add(new Toggle("grid_background", () -> config.gridBackground, v -> config.gridBackground = v));
				rows.add(new Toggle("vanilla_buttons", () -> config.vanillaButtons, v -> config.vanillaButtons = v));
				rows.add(new Toggle("icons", () -> config.showNameplateIcons, v -> config.showNameplateIcons = v));
				rows.add(new Toggle("own_nameplate", () -> config.showOwnNameplate, v -> config.showOwnNameplate = v));
				rows.add(new Choice<>("attach", ClientConfig.AttachMode.values(), () -> config.attachMode, v -> config.attachMode = v));
			}
			case EMOTES -> {
				rows.add(new Toggle("other_emotes", () -> config.showOtherEmotes, v -> config.showOtherEmotes = v));
				rows.add(new Action("emote_wheels", () -> Component.translatable("orbit_cosmetic.settings.emote_wheels.open"),
					() -> Compat.setScreen(OrbitMenuScreen.emotes(this))));
				rows.add(new Heading(Component.translatable("orbit_cosmetic.settings.emote_key", OrbitClient.get().emoteWheelKey().getTranslatedKeyMessage())));
			}
			case CLOUD -> {
				rows.add(new Toggle("backend", () -> config.useBackend, v -> config.useBackend = v));
				rows.add(new Toggle("downloads", () -> config.downloadFromServers, v -> config.downloadFromServers = v));
				rows.add(new Toggle("share_statistics", () -> config.shareStatistics, v -> {
					config.shareStatistics = v;
					OrbitClient.get().social().shareStats();
				}));
				// Orbit Cloud is connected from launch (friends, messages); servers only add sharing what you wear.
				rows.add(new Heading(Component.translatable(OrbitClient.get().social().isLoaded() ? "orbit_cosmetic.settings.cloud.connected" : "orbit_cosmetic.settings.cloud.idle")));
			}
			case PERFORMANCE -> {
				rows.add(new Slider("render_distance", 8, 256, 8, () -> config.renderDistance, v -> config.renderDistance = (int) v, v -> (int) v + " blocks"));
				rows.add(new Slider("animation_distance", 4, 256, 4, () -> config.animationDistance, v -> config.animationDistance = (int) v, v -> (int) v + " blocks"));
				rows.add(new Slider("max_faces", 500, ModelLimits.HARD.maxQuads(), 500, () -> config.maxFacesPerPlayer, v -> config.maxFacesPerPlayer = (int) v, v -> String.valueOf((int) v)));
			}
			case ADVANCED -> {
				rows.add(new Slider("cache_size", 16, 1024, 16, () -> config.maxCacheMegabytes, v -> config.maxCacheMegabytes = (int) v, v -> (int) v + " MiB"));
				rows.add(new Action("clear_cache", () -> cacheBytes < 0 ? Component.translatable("orbit_cosmetic.settings.clear_cache.button")
					: Component.translatable("orbit_cosmetic.settings.cache_usage", cacheBytes / 1024), this::clearCache));
				rows.add(new Action("folder", () -> Component.translatable("orbit_cosmetic.settings.folder.open"), () -> Compat.openPath(OrbitClient.get().paths().root())));
				// Hidden at the very bottom, as promised.
				rows.add(new Heading(Component.translatable("orbit_cosmetic.settings.just_for_fun")));
				rows.add(new Toggle("joke_coins", () -> config.jokeCoins, v -> config.jokeCoins = v));
			}
		}
		contentHeight = 0;
		for (Row row : rows) {
			contentHeight += row.height(this, contentW) + ROW_GAP;
		}
	}

	private void clearCache() {
		cacheBytes = -1;
		Util.ioPool().execute(() -> {
			int removed = OrbitClient.get().cache().clear();
			minecraft.execute(() -> {
				cacheBytes = 0;
				dev.orbit.cosmetic.client.Notifications.info(Component.translatable("orbit_cosmetic.settings.cache_cleared", removed), null);
			});
		});
	}

	@Override
	public void onClose() {
		save();
		super.onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (!Theme.inside(event.x(), event.y(), contentX, contentY, contentW, contentH)) {
			return false;
		}
		int y = contentY - (int) scroll;
		for (Row row : rows) {
			int height = row.height(this, contentW);
			if (event.y() >= y && event.y() < y + height) {
				row.click(this, event.x(), event.y(), contentX, y, contentW, height);
				dragging = row instanceof Slider ? row : null;
				return true;
			}
			y += height + ROW_GAP;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			dragging.drag(event.x(), contentX, contentW);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * 20, 0, Math.max(0, contentHeight - contentH));
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		Theme.panel(graphics, panelX, panelY, sideW, panelH, Theme.PANEL);
		Theme.panel(graphics, panelX + sideW - 1, panelY, panelW - sideW + 1, panelH, Theme.BACKGROUND);
		graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + HEADER, Theme.PANEL_2);
		graphics.fill(panelX + 1, panelY + HEADER, panelX + panelW - 1, panelY + HEADER + 1, Theme.BORDER);
		graphics.text(font, title, panelX + 8, panelY + 7, Theme.TEXT, false);
		graphics.text(font, category.label(), contentX, panelY + 7, Theme.MUTED, false);
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		graphics.text(font, Component.translatable("orbit_cosmetic.settings.version", version), panelX + 8, panelY + panelH - 12, Theme.FAINT, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
		int y = contentY - (int) scroll;
		for (Row row : rows) {
			int height = row.height(this, contentW);
			if (y + height >= contentY && y <= contentY + contentH) {
				row.draw(this, graphics, contentX, y, contentW, height, mouseX, mouseY);
			}
			y += height + ROW_GAP;
		}
		graphics.disableScissor();
	}
}
