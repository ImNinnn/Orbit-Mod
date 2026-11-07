package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.Updates;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A newer Orbit is out: update now? Shown once per start on the title screen (unless "Don't show this again" was
 * ticked for that version), and from the arrow next to Settings or the settings' Update row. Updating runs the
 * update window, which downloads and checks the new version; Minecraft then has to be closed to use it.
 */
public final class UpdatePromptScreen extends OrbitScreen {
	private static final int WIDTH = 280;
	private static boolean shown;

	private enum Stage { ASK, UPDATING, DONE, FAILED }

	private Stage stage = Stage.ASK;
	/** The version on offer (kept: once installed, it is no longer offered). */
	private final @Nullable String version = Updates.available();
	private @Nullable String failure;
	private boolean dontShow;
	private List<FormattedCharSequence> lines = List.of();
	private @Nullable Hotspot checkbox;

	public UpdatePromptScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.update.title"), parent);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	/** Asks on the first title screen of a start, after the terms. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			ClientConfig config = OrbitClient.get().config();
			String version = Updates.available();
			if (screen instanceof TitleScreen && !shown && version != null && !version.equals(config.updatePromptSkipped)
				&& (config.hasAcceptedTerms() || !config.useBackend)) {
				shown = true;
				client.execute(() -> Compat.setScreen(new UpdatePromptScreen(screen)));
			}
		});
	}

	private int height() {
		return 74 + lines.size() * 10 + (stage == Stage.ASK ? 22 : 0);
	}

	private int top() {
		return Math.max(6, (height - height()) / 2);
	}

	@Override
	protected void init() {
		Component text = switch (stage) {
			case ASK -> Component.translatable("orbit_cosmetic.update.text", version, Updates.modVersion());
			case UPDATING -> Component.translatable("orbit_cosmetic.update.updating", version);
			case DONE -> Component.translatable("orbit_cosmetic.update.done", version);
			case FAILED -> Component.translatable("orbit_cosmetic.update.failed", failure == null ? "?" : failure);
		};
		lines = font.split(text, WIDTH - 16);
		int x = width / 2 - WIDTH / 2 + 8;
		int y = top() + 30 + lines.size() * 10 + 8;
		int half = (WIDTH - 16 - 4) / 2;
		checkbox = null;
		switch (stage) {
			case ASK -> {
				checkbox = addRenderableWidget(new Hotspot(Component.translatable("orbit_cosmetic.update.dont_show"), h -> dontShow = !dontShow));
				checkbox.place(x, y, WIDTH - 16, 12);
				y += 22;
				addRenderableWidget(new FlatButton(x, y, half, 20, Component.translatable("orbit_cosmetic.update.later"), null, FlatButton.Style.SECONDARY, b -> onClose()));
				FlatButton update = addRenderableWidget(new FlatButton(x + half + 4, y, half, 20, Component.translatable("orbit_cosmetic.update.now"), "download",
					FlatButton.Style.PRIMARY, b -> update()));
				setInitialFocus(update);
			}
			case UPDATING -> addRenderableWidget(new FlatButton(x, y, WIDTH - 16, 20, Component.translatable("orbit_cosmetic.update.hide"), null, FlatButton.Style.SECONDARY,
				b -> onClose()));
			case DONE -> {
				addRenderableWidget(new FlatButton(x, y, half, 20, Component.translatable("orbit_cosmetic.update.later"), null, FlatButton.Style.SECONDARY, b -> onClose()));
				addRenderableWidget(new FlatButton(x + half + 4, y, half, 20, Component.translatable("orbit_cosmetic.update.quit"), "check", FlatButton.Style.PRIMARY,
					b -> Minecraft.getInstance().stop()));
			}
			case FAILED -> addRenderableWidget(new FlatButton(x, y, WIDTH - 16, 20, Component.translatable("gui.back"), null, FlatButton.Style.SECONDARY, b -> onClose()));
		}
	}

	private void update() {
		stage = Stage.UPDATING;
		rebuildWidgets();
		Updates.install(result -> Minecraft.getInstance().execute(() -> {
			if (result.equals("UPDATED")) {
				stage = Stage.DONE;
			} else {
				stage = Stage.FAILED;
				failure = result.startsWith("FAILED ") ? result.substring(7) : result;
			}
			if (Compat.screen() != this) {
				Compat.setScreen(this);
			} else {
				rebuildWidgets();
			}
		}));
	}

	@Override
	public void onClose() {
		if (stage == Stage.ASK && dontShow) {
			ClientConfig config = orbit().config();
			config.updatePromptSkipped = version;
			config.save();
		}
		super.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = width / 2 - WIDTH / 2;
		int y = top();
		Theme.panel(graphics, x, y, WIDTH, height());
		Theme.icon(graphics, "download", x + 8, y + 9, Theme.RED);
		graphics.text(font, title, x + 20, y + 9, Theme.TEXT, false);
		int ly = y + 28;
		for (FormattedCharSequence line : lines) {
			graphics.text(font, line, x + 8, ly, Theme.MUTED, false);
			ly += 10;
		}
		if (checkbox != null) {
			int cx = checkbox.getX();
			int cy = checkbox.getY();
			boolean hot = checkbox.isHoveredOrFocused();
			graphics.fill(cx, cy + 1, cx + 10, cy + 11, hot ? Theme.TEXT : Theme.BORDER_LIGHT);
			graphics.fill(cx + 1, cy + 2, cx + 9, cy + 10, Theme.PANEL_2);
			if (dontShow) {
				Theme.icon(graphics, "check_small", cx, cy + 1, Theme.GREEN);
			}
			graphics.text(font, Component.translatable("orbit_cosmetic.update.dont_show").withStyle(ChatFormatting.GRAY), cx + 15, cy + 2, Theme.TEXT, false);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
