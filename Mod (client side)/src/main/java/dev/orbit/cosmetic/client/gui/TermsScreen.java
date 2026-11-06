package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.net.OrbitCloud;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;

/**
 * Asked on the first start (and whenever the terms change): Orbit Cloud is only used once the player accepted the
 * Terms of Service and the Privacy Policy, both on the website. Declining keeps Orbit offline for this game; it asks
 * again on the next start.
 */
public final class TermsScreen extends OrbitScreen {
	private static final int WIDTH = 300;
	/** Declined (or already shown) in this game: not shown again on its own until the next start. */
	private static boolean shown;

	private List<FormattedCharSequence> lines = List.of();

	public TermsScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.terms.title"), parent);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	/** Shows the terms over the first title screen while they are not accepted. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			ClientConfig config = OrbitClient.get().config();
			if (screen instanceof TitleScreen && !shown && config.useBackend && !config.hasAcceptedTerms()) {
				shown = true;
				client.execute(() -> Compat.setScreen(new TermsScreen(screen)));
			}
		});
	}

	private int height() {
		return 100 + lines.size() * 10;
	}

	private int top() {
		return Math.max(6, (height - height()) / 2);
	}

	@Override
	protected void init() {
		lines = font.split(Component.translatable("orbit_cosmetic.terms.text"), WIDTH - 16);
		int x = width / 2 - WIDTH / 2 + 8;
		int y = top() + 30 + lines.size() * 10 + 8;
		int half = (WIDTH - 16 - 4) / 2;
		addRenderableWidget(new FlatButton(x, y, half, 18, Component.translatable("orbit_cosmetic.terms.terms"), "forward", FlatButton.Style.SECONDARY,
			b -> Compat.openUri(URI.create(OrbitCloud.TERMS_URL))));
		addRenderableWidget(new FlatButton(x + half + 4, y, half, 18, Component.translatable("orbit_cosmetic.terms.privacy"), "forward", FlatButton.Style.SECONDARY,
			b -> Compat.openUri(URI.create(OrbitCloud.PRIVACY_URL))));
		y += 34;
		addRenderableWidget(new FlatButton(x, y, half, 20, Component.translatable("orbit_cosmetic.terms.decline"), null, FlatButton.Style.SECONDARY, b -> onClose()));
		FlatButton accept = addRenderableWidget(new FlatButton(x + half + 4, y, half, 20, Component.translatable("orbit_cosmetic.terms.accept"), "check", FlatButton.Style.PRIMARY,
			b -> accept()));
		setInitialFocus(accept);
	}

	private void accept() {
		ClientConfig config = orbit().config();
		config.termsAccepted = OrbitCloud.TERMS_VERSION;
		config.save();
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = width / 2 - WIDTH / 2;
		int y = top();
		Theme.panel(graphics, x, y, WIDTH, height());
		Theme.icon(graphics, "orbit", x + 8, y + 9, Theme.RED);
		graphics.text(font, title, x + 20, y + 9, Theme.TEXT, false);
		int ly = y + 28;
		for (FormattedCharSequence line : lines) {
			graphics.text(font, line, x + 8, ly, Theme.MUTED, false);
			ly += 10;
		}
		graphics.text(font, Component.translatable("orbit_cosmetic.terms.agree").withStyle(ChatFormatting.DARK_GRAY), x + 8, y + 61 + lines.size() * 10, Theme.TEXT, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
