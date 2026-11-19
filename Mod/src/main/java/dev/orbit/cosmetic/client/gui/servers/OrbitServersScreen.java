package dev.orbit.cosmetic.client.gui.servers;

import dev.orbit.cosmetic.client.gui.ui.OrbitBaseScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.social.ServerLinks;
import dev.orbit.cosmetic.client.social.Social;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Friends and Recommended tabs of the Multiplayer screen, in Minecraft's style: worlds friends host (shared
 * through Orbit Cloud while they host), or the servers Orbit Cloud recommends, sorted by players online. Each is
 * pinged like Minecraft's own list; Join Server joins (as does a double click), and Add to Favorites puts a
 * recommended one into Minecraft's server list (the Favorites tab).
 */
public final class OrbitServersScreen extends OrbitBaseScreen {
	private static final Identifier NO_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_server.png");
	private static final Identifier UNREACHABLE = Identifier.withDefaultNamespace("server_list/unreachable");
	private static final Identifier[] PING = {Identifier.withDefaultNamespace("server_list/ping_1"), Identifier.withDefaultNamespace("server_list/ping_2"),
		Identifier.withDefaultNamespace("server_list/ping_3"), Identifier.withDefaultNamespace("server_list/ping_4"), Identifier.withDefaultNamespace("server_list/ping_5")};
	private static final Identifier[] PINGING = {Identifier.withDefaultNamespace("server_list/pinging_1"), Identifier.withDefaultNamespace("server_list/pinging_2"),
		Identifier.withDefaultNamespace("server_list/pinging_3"), Identifier.withDefaultNamespace("server_list/pinging_4"), Identifier.withDefaultNamespace("server_list/pinging_5")};

	private final @Nullable Screen parent;
	private final int tab;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, ServerTabs.TAB_HEIGHT, 60);
	private final ServerLinks links = new ServerLinks();
	private @Nullable List<Server> servers;
	private @Nullable Component message;
	private Servers list;
	private Button join;
	private @Nullable Button favorite;
	private @Nullable ServerList favorites;
	private int lastVersion = -1;
	private int sortedAt;

	/** A server or hosted world of the list. */
	private record Server(String name, String address, @Nullable String motd) {
	}

	public OrbitServersScreen(@Nullable Screen parent, int tab) {
		super(Component.translatable("multiplayer.title"));
		this.parent = parent;
		this.tab = tab;
	}

	@Override
	protected void init() {
		list = layout.addToContents(new Servers(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), 36));
		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		LinearLayout top = footer.addChild(LinearLayout.horizontal().spacing(4));
		boolean recommended = tab == ServerTabs.RECOMMENDED;
		int width = recommended ? 98 : 150;
		join = top.addChild(Button.builder(Component.translatable("selectServer.select"), b -> joinSelected()).width(width).build());
		if (recommended) {
			favorite = top.addChild(Button.builder(Component.translatable("orbit_cosmetic.servers.favorite"), b -> favoriteSelected()).width(100).build());
		}
		top.addChild(Button.builder(Component.translatable("selectServer.refresh"), b -> Compat.setScreen(new OrbitServersScreen(parent, tab))).width(width).build());
		footer.addChild(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(304).build());
		layout.visitWidgets(this::addRenderableWidget);
		var tabs = ServerTabs.tabs(this.width, tab, parent);
		ServerTabs.underTabs(list, tabs);
		tabs.forEach(this::addRenderableWidget);
		repositionElements();
		load();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) {
			list.updateSize(width, layout);
		}
	}

	private void load() {
		if (tab == ServerTabs.FRIENDS) {
			Social social = OrbitClient.get().social();
			lastVersion = social.version();
			List<Server> found = new ArrayList<>();
			for (Social.Person friend : social.friends()) {
				JsonObject hosting = friend.hosting();
				if (hosting != null) {
					String world = hosting.has("world") ? hosting.get("world").getAsString() : "";
					String name = world.isBlank() ? Component.translatable("orbit_cosmetic.servers.friend_world", friend.name()).getString()
						: Component.translatable("orbit_cosmetic.servers.friend_world_named", friend.name(), world).getString();
					found.add(new Server(name, hosting.get("address").getAsString(), hosting.has("motd") ? hosting.get("motd").getAsString() : null));
				}
			}
			show(found, Component.translatable(social.isLoaded() ? "orbit_cosmetic.servers.no_friends_hosting" : "orbit_cosmetic.social.loading"));
		} else if (servers == null) {
			message = Component.translatable("orbit_cosmetic.social.loading");
			OrbitClient.get().backend().recommendedServers(array -> {
				List<Server> found = new ArrayList<>();
				for (JsonElement element : array) {
					if (element instanceof JsonObject server && server.has("address") && server.has("name")) {
						found.add(new Server(server.get("name").getAsString(), server.get("address").getAsString(), null));
					}
				}
				show(found, Component.translatable("orbit_cosmetic.servers.none_recommended"));
			}, error -> message = Component.literal(error));
		} else {
			show(servers, Component.translatable("orbit_cosmetic.servers.none_recommended"));
		}
	}

	private void show(List<Server> found, Component empty) {
		servers = found;
		message = found.isEmpty() ? empty : null;
		Server selected = list.getSelected() == null ? null : list.getSelected().server;
		list.show(found.stream().map(Entry::new).toList());
		list.children().stream().filter(entry -> entry.server.equals(selected)).findFirst().ifPresent(list::setSelected);
	}

	/** Whether Minecraft's server list (the Favorites tab) has this address. */
	private boolean isFavorite(String address) {
		if (favorites == null) {
			favorites = new ServerList(minecraft);
			favorites.load();
		}
		return favorites.get(address) != null;
	}

	/** Adds the selected server to Minecraft's server list: it shows in the Favorites tab. */
	private void favoriteSelected() {
		Entry entry = list.getSelected();
		if (entry == null || isFavorite(entry.server.address())) {
			return;
		}
		favorites.add(new ServerData(entry.server.name(), entry.server.address(), ServerData.Type.OTHER), false);
		favorites.save();
		Notifications.success(Component.translatable("orbit_cosmetic.servers.favorited", entry.server.name()), Component.empty());
	}

	private void joinSelected() {
		Entry entry = list.getSelected();
		if (entry != null) {
			ServerLinks.join(this, entry.server.address(), entry.server.name());
		}
	}

	@Override
	public void tick() {
		links.tick();
		join.active = list.getSelected() != null;
		if (favorite != null) {
			favorite.active = list.getSelected() != null && !isFavorite(list.getSelected().server.address());
		}
		if (tab == ServerTabs.FRIENDS && OrbitClient.get().social().version() != lastVersion) {
			load();
		} else if (tab == ServerTabs.RECOMMENDED && servers != null && ++sortedAt % 20 == 0) {
			// The most players first, as the pings answer.
			List<Server> sorted = new ArrayList<>(servers);
			sorted.sort(Comparator.comparingInt((Server server) -> {
				ServerData data = links.status(server.address(), server.name());
				return data.players == null ? -1 : data.players.online();
			}).reversed());
			if (!sorted.equals(servers)) {
				show(sorted, Component.empty());
			}
		}
	}

	@Override
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawWidgets(graphics, mouseX, mouseY, partialTick);
		if (message != null) {
			graphics.centeredText(font, message.copy().withStyle(ChatFormatting.GRAY), width / 2, layout.getHeaderHeight() + 30, -1);
		}
	}

	@Override
	public void onClose() {
		Compat.setScreen(parent);
	}

	@Override
	public void removed() {
		links.close();
	}

	private final class Servers extends ObjectSelectionList<Entry> {
		Servers(Minecraft minecraft, int width, int height, int y, int itemHeight) {
			super(minecraft, width, height, y, itemHeight);
		}

		@Override
		public int getRowWidth() {
			return 305;
		}

		/** The list's servers from now on (replaceEntries is not public before 1.21.2). */
		void show(java.util.Collection<OrbitServersScreen.Entry> entries) {
			replaceEntries(entries);
		}
	}

	private final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final Server server;

		Entry(Server server) {
			this.server = server;
		}

		//? if >=1.21.9 {
		@Override
		public void extractContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, boolean hovered, float partialTick) {
			draw(OrbitGraphics.of(extractor), getContentX(), getContentY(), getContentWidth());
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			return clicked(doubleClick);
		}
		//?} else {
		/*@Override
		public void render(GuiGraphicsExtractor extractor, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
			draw(OrbitGraphics.of(extractor), left, top, width);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return clicked(dev.orbit.cosmetic.client.input.OrbitClick.isDoubleClick());
		}
		*///?}

		/** The row: its content starts at (x, y) and is {@code width} wide. */
		private void draw(OrbitGraphics graphics, int x, int y, int width) {
			ServerData data = links.status(server.address(), server.name());
			Identifier icon = links.icon(server.address(), null);
			graphics.image(icon != null ? icon : NO_ICON, x, y, x + 32, y + 32, 0, 1, 0, 1);
			graphics.text(font, server.name(), x + 35, y + 1, -1);
			if (tab == ServerTabs.RECOMMENDED && isFavorite(server.address())) {
				Theme.icon(graphics, "star", x + 38 + font.width(server.name()), y, Theme.GOLD);
			}
			Component motd = data.state() == ServerData.State.SUCCESSFUL && data.motd != null ? data.motd
				: data.state() == ServerData.State.UNREACHABLE ? Component.translatable("multiplayer.status.cannot_connect").withStyle(ChatFormatting.DARK_RED)
				: server.motd() != null ? Component.literal(server.motd()) : Component.literal(server.address());
			List<FormattedCharSequence> lines = font.split(motd, width - 34);
			for (int i = 0; i < Math.min(2, lines.size()); i++) {
				graphics.text(font, lines.get(i), x + 35, y + 12 + 9 * i, -8355712);
			}
			int statusX = x + width - 15;
			Identifier status;
			if (data.state() == ServerData.State.PINGING) {
				status = PINGING[(int) (System.currentTimeMillis() / 100 % 5)];
			} else if (data.state() != ServerData.State.SUCCESSFUL) {
				status = UNREACHABLE;
			} else {
				long ping = data.ping;
				status = PING[ping < 0 ? 0 : ping < 150 ? 4 : ping < 300 ? 3 : ping < 600 ? 2 : ping < 1000 ? 1 : 0];
			}
			graphics.sprite(status, statusX, y, 10, 8);
			if (data.state() == ServerData.State.SUCCESSFUL && data.status != null) {
				graphics.text(font, data.status, statusX - font.width(data.status) - 5, y + 1, -8355712);
			}
		}

		private boolean clicked(boolean doubleClick) {
			list.setSelected(this);
			if (doubleClick) {
				joinSelected();
			}
			return true;
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", server.name());
		}
	}
}
