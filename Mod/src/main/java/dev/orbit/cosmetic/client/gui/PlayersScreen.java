package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Every Orbit player on the current server, with options to hide their cosmetics or report them. */
public final class PlayersScreen extends OrbitScreen {
	private static final int ROW = 22;

	/** A player as listed here. */
	record OrbitPlayer(UUID id, String name, Wearer wearer) {
	}

	private List<OrbitPlayer> players = List.of();
	private @Nullable OrbitPlayer selected;
	private FlatButton hideButton;
	private FlatButton reportButton;
	private FlatButton friendButton;
	private FlatButton statsButton;
	private FlatButton blockButton;
	private double scroll;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;

	public PlayersScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.players.title"), parent);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	/** Whether the menu has anything to show: connected where Orbit players can see each other. */
	public static boolean available() {
		return Minecraft.getInstance().player != null && OrbitClient.get().backend().isActive();
	}

	@Override
	protected void init() {
		panelW = Math.min(width - 40, 340);
		panelH = Math.min(height - 16, 280);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		backButton(panelX - 22, panelY);
		int buttonWidth = (panelW - 16 - 4) / 2;
		int y = panelY + panelH - 26;
		hideButton = addRenderableWidget(new FlatButton(panelX + 8, y, buttonWidth, 18, Component.translatable("orbit_cosmetic.players.hide"), "close", FlatButton.Style.SECONDARY, b -> toggleHidden()));
		reportButton = addRenderableWidget(new FlatButton(panelX + 12 + buttonWidth, y, buttonWidth, 18, Component.translatable("orbit_cosmetic.players.report"), null, FlatButton.Style.DANGER, b -> {
			if (selected != null) {
				Compat.setScreen(new ReportScreen(this, selected.id(), selected.name(), selected.wearer()));
			}
		}));
		reportButton.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.players.report.tooltip")));
		// Orbit's friends: add, see their statistics (friends only), block.
		int third = (panelW - 16 - 8) / 3;
		y -= 22;
		friendButton = addRenderableWidget(new FlatButton(panelX + 8, y, third, 18, Component.translatable("orbit_cosmetic.social.add_friend"), "person_add", FlatButton.Style.SECONDARY,
			b -> {
				if (selected != null) {
					OrbitClient.get().social().request(selected.name(), this::updateButtons);
				}
			}));
		statsButton = addRenderableWidget(new FlatButton(panelX + 12 + third, y, third, 18, Component.translatable("orbit_cosmetic.menu.stats"), "account", FlatButton.Style.SECONDARY,
			b -> {
				if (selected != null) {
					Compat.setScreen(new StatsScreen(this, selected.id(), selected.name()));
				}
			}));
		statsButton.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.players.stats.tooltip")));
		blockButton = addRenderableWidget(new FlatButton(panelX + 16 + third * 2, y, panelW - 24 - third * 2, 18, Component.translatable("orbit_cosmetic.social.block"), "block",
			FlatButton.Style.DANGER, b -> {
				if (selected != null) {
					var social = OrbitClient.get().social();
					if (social.isBlocked(selected.id())) {
						social.unblock(selected.id());
					} else {
						social.block(selected.id());
					}
				}
			}));
		refresh();
	}

	private void refresh() {
		List<OrbitPlayer> found = new ArrayList<>();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection != null) {
			for (Wearer wearer : OrbitClient.get().remotes().wearers().values()) {
				PlayerInfo info = connection.getPlayerInfo(wearer.id);
				if (info != null) {
					found.add(new OrbitPlayer(wearer.id, info.getProfile().name(), wearer));
				}
			}
		}
		found.sort(Comparator.comparing(player -> player.name().toLowerCase(Locale.ROOT)));
		players = List.copyOf(found);
		if (selected != null) {
			UUID id = selected.id();
			selected = players.stream().filter(player -> player.id().equals(id)).findFirst().orElse(null);
		}
		updateButtons();
	}

	private void updateButtons() {
		boolean has = selected != null;
		hideButton.active = has;
		reportButton.active = has;
		var social = OrbitClient.get().social();
		boolean friend = has && social.friend(selected.id()) != null;
		boolean asked = has && social.outgoing().stream().anyMatch(person -> person.uuid().equals(selected.id()));
		boolean blocked = has && social.isBlocked(selected.id());
		friendButton.active = has && social.isLoaded() && !friend && !asked && !blocked;
		friendButton.setMessage(Component.translatable(friend ? "orbit_cosmetic.players.friend" : asked ? "orbit_cosmetic.players.asked" : "orbit_cosmetic.social.add_friend"));
		statsButton.active = friend;
		blockButton.active = has && social.isLoaded();
		blockButton.setMessage(Component.translatable(blocked ? "orbit_cosmetic.social.action.unblock" : "orbit_cosmetic.social.block"));
		boolean hidden = has && OrbitClient.get().config().isHidden(selected.id());
		hideButton.setMessage(Component.translatable(hidden ? "orbit_cosmetic.players.show" : "orbit_cosmetic.players.hide"));
		hideButton.setIcon(hidden ? "check" : "close");
	}

	private void toggleHidden() {
		if (selected != null) {
			var config = OrbitClient.get().config();
			config.setHidden(selected.id(), !config.isHidden(selected.id()));
			updateButtons();
		}
	}

	@Override
	public void tick() {
		// Players come and go while the menu is open.
		if (minecraft.player != null && minecraft.player.tickCount % 20 == 0) {
			refresh();
		}
	}

	private int listTop() {
		return panelY + 30;
	}

	private int listBottom() {
		return panelY + panelH - 54;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (Theme.inside(event.x(), event.y(), panelX + 8, listTop(), panelW - 16, listBottom() - listTop())) {
			int index = (int) ((event.y() - listTop() + scroll) / ROW);
			if (index >= 0 && index < players.size()) {
				selected = players.get(index);
				updateButtons();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.clamp(scroll - scrollY * ROW, 0, Math.max(0, players.size() * ROW - (listBottom() - listTop())));
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		Theme.panel(graphics, panelX, panelY, panelW, panelH, Theme.PANEL);
		graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 22, Theme.PANEL_2);
		graphics.text(font, title, panelX + 8, panelY + 7, Theme.TEXT, false);
		Component source = Component.translatable("orbit_cosmetic.players.source.cloud");
		graphics.text(font, source, panelX + panelW - 8 - font.width(source), panelY + 7, Theme.FAINT, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = listTop();
		int bottom = listBottom();
		graphics.enableScissor(panelX + 8, top, panelX + panelW - 8, bottom);
		int y = top - (int) scroll;
		for (OrbitPlayer player : players) {
			boolean isSelected = selected != null && selected.id().equals(player.id());
			boolean hovered = Theme.inside(mouseX, mouseY, panelX + 8, y, panelW - 16, ROW - 2) && mouseY >= top && mouseY < bottom;
			graphics.fill(panelX + 8, y, panelX + panelW - 8, y + ROW - 2, isSelected ? Theme.PANEL_HOVER : hovered ? Theme.PANEL_2 : Theme.PANEL);
			graphics.outline(panelX + 8, y, panelW - 16, ROW - 2, isSelected ? Theme.RED : Theme.BORDER);
			graphics.text(font, player.name(), panelX + 14, y + 3, Theme.TEXT, false);
			Component status = Component.translatable("orbit_cosmetic.players.cosmetics", player.wearer().worn().size());
			if (OrbitClient.get().config().isHidden(player.id())) {
				status = status.copy().append(" · ").append(Component.translatable("orbit_cosmetic.players.hidden"));
			}
			graphics.text(font, status, panelX + 14, y + 11, Theme.MUTED, false);
			y += ROW;
		}
		graphics.disableScissor();
		if (players.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.players.empty"), panelX + panelW / 2, top + 20, Theme.MUTED);
		}
	}
}
