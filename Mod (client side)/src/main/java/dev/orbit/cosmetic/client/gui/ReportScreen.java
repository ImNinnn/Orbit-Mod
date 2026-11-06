package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.UUID;

/** Reports another player's cosmetics to the Orbit Cloud moderators. */
public final class ReportScreen extends OrbitScreen {
	/** Values understood by Orbit Cloud (POST /v1/reports). */
	private static final List<String> REASONS = List.of("inappropriate", "offensive", "lag", "impersonation", "other");
	private static final int MAX_NOTE = 200;
	private static final int WIDTH = 300;

	private final UUID player;
	private final String name;
	private final List<String> cosmetics;
	private String reason = REASONS.getFirst();
	private EditBox note;
	private FlatButton send;
	private Component status;
	private boolean failed;

	public ReportScreen(Screen parent, UUID player, String name, Wearer wearer) {
		super(Component.translatable("orbit_cosmetic.report.title", name), parent);
		this.player = player;
		this.name = name;
		this.cosmetics = wearer == null ? List.of() : wearer.worn().stream().map(worn -> worn.asset().hex()).toList();
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	private int left() {
		return (width - WIDTH) / 2;
	}

	private int top() {
		return height / 2 - 70;
	}

	@Override
	protected void init() {
		int left = left();
		int y = top() + 46;
		int reasonWidth = (WIDTH - 4 * (REASONS.size() - 1)) / REASONS.size();
		for (int i = 0; i < REASONS.size(); i++) {
			String value = REASONS.get(i);
			addRenderableWidget(new FlatButton(left + i * (reasonWidth + 4), y, reasonWidth, 18, Component.translatable("orbit_cosmetic.report.reason." + value), null,
				FlatButton.Style.SECONDARY, b -> {
					reason = value;
					rebuildWidgets();
				})).setSelected(value.equals(reason));
		}
		note = new EditBox(font, left + 6, y + 30, WIDTH - 12, 10, Component.translatable("orbit_cosmetic.report.note"));
		note.setBordered(false);
		note.setMaxLength(MAX_NOTE);
		note.setTextColor(Theme.TEXT);
		note.setHint(Component.translatable("orbit_cosmetic.report.note").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(note);
		send = addRenderableWidget(new FlatButton(left, y + 56, (WIDTH - 4) / 2, 18, Component.translatable("orbit_cosmetic.report.send"), "check", FlatButton.Style.PRIMARY, b -> sendReport()));
		addRenderableWidget(new FlatButton(left + (WIDTH + 4) / 2, y + 56, (WIDTH - 4) / 2, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	private void sendReport() {
		send.active = false;
		failed = false;
		status = Component.translatable("orbit_cosmetic.report.sending");
		OrbitClient.get().backend().report(player, name, reason, note.getValue().trim(), cosmetics,
			created -> {
				Notifications.success(Component.translatable("orbit_cosmetic.notify.report_sent", name),
					Component.translatable(created ? "orbit_cosmetic.notify.report_sent.detail" : "orbit_cosmetic.notify.report_updated"));
				onClose();
			},
			message -> {
				failed = true;
				status = Component.literal(message);
				send.active = true;
			});
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int left = left();
		int top = top();
		Theme.panel(graphics, left - 10, top - 10, WIDTH + 20, 150, Theme.PANEL);
		graphics.text(font, title, left, top, Theme.TEXT, false);
		int y = top + 14;
		for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.report.explain"), WIDTH)) {
			graphics.text(font, line, left, y, Theme.MUTED, false);
			y += 9;
		}
		Theme.panel(graphics, left, top + 72, WIDTH, 16, Theme.BACKGROUND);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (status != null) {
			int y = top() + 128;
			for (FormattedCharSequence line : font.split(status, WIDTH)) {
				graphics.text(font, line, left(), y, failed ? 0xFFFF6B6B : Theme.MUTED, false);
				y += 9;
			}
		}
	}
}
