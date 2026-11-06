package dev.orbit.cosmetic.client.gui.servers;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.mixin.JoinMultiplayerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Three tabs over Minecraft's Multiplayer screen, laid out and drawn like Minecraft's own tab bar (Create World):
 * Favorites (Minecraft's server list, as it is), Friends (worlds friends host) and Recommended (popular servers). The
 * top bar becomes as high as the tabs (they take the title's place), and the selected tab is filled with the list's
 * background, so it opens onto the list under it.
 */
public final class ServerTabs {
	public static final int FAVORITES = 0;
	public static final int FRIENDS = 1;
	public static final int RECOMMENDED = 2;
	private static final String[] KEYS = {"orbit_cosmetic.servers.favorites", "orbit_cosmetic.servers.friends", "orbit_cosmetic.servers.recommended"};
	/** The top bar's height with the tabs in it. */
	public static final int TAB_HEIGHT = 24;
	private static final int MAX_WIDTH = 400;
	private static final int MARGIN = 14;

	private ServerTabs() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof JoinMultiplayerScreen && OrbitClient.get().config().useBackend) {
				// The tabs take the title's place.
				for (var widget : Screens.getWidgets(screen)) {
					if (widget instanceof StringWidget text && text.getMessage().getString().equals(screen.getTitle().getString())) {
						text.visible = false;
					}
				}
				JoinMultiplayerScreenAccessor multiplayer = (JoinMultiplayerScreenAccessor) screen;
				Screen parent = multiplayer.orbit$lastScreen();
				// The top bar holds the tabs: as high as they are, with the list right under it.
				multiplayer.orbit$layout().setHeaderHeight(TAB_HEIGHT);
				multiplayer.orbit$layout().arrangeElements();
				if (multiplayer.orbit$serverList() != null) {
					multiplayer.orbit$serverList().updateSize(screen.width, multiplayer.orbit$layout());
				}
				List<AbstractWidget> tabs = tabs(screen.width, FAVORITES, parent);
				underTabs(multiplayer.orbit$serverList(), tabs);
				Screens.getWidgets(screen).addAll(tabs);
			}
		});
	}

	/**
	 * The three tabs at the top of a screen {@code width} wide, as Minecraft lays out its tab bar (at most 400 wide,
	 * 14 from each side); {@code parent} is where Back goes.
	 */
	public static List<AbstractWidget> tabs(int width, int selected, @Nullable Screen parent) {
		int tabsWidth = Math.min(MAX_WIDTH, width) - MARGIN * 2;
		int tabWidth = tabsWidth / 3 / 2 * 2;
		int left = (width - tabWidth * 3) / 2 / 2 * 2;
		return List.of(new Tab(left, tabWidth, FAVORITES, selected, parent), new Tab(left + tabWidth, tabWidth, FRIENDS, selected, parent),
			new Tab(left + tabWidth * 2, tabWidth, RECOMMENDED, selected, parent));
	}

	/** Lists right under the tabs, and where the tabs are (their top line is drawn beside the tabs only). */
	private static final Map<AbstractSelectionList<?>, int[]> UNDER_TABS = new WeakHashMap<>();

	/** Marks a list as being right under these tabs. */
	public static void underTabs(@Nullable AbstractSelectionList<?> list, List<AbstractWidget> tabs) {
		if (list != null && !tabs.isEmpty()) {
			UNDER_TABS.put(list, new int[] {tabs.getFirst().getX(), tabs.getLast().getRight()});
		}
	}

	/**
	 * Draws a list's top and bottom lines when it is under the tabs (the top one beside the tabs only, as Minecraft's
	 * tab bar does); false for any other list.
	 */
	public static boolean drawSeparators(AbstractSelectionList<?> list, GuiGraphicsExtractor graphics) {
		int[] tabs = UNDER_TABS.get(list);
		if (tabs == null) {
			return false;
		}
		boolean inWorld = Minecraft.getInstance().level != null;
		Identifier header = inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR;
		Identifier footer = inWorld ? Screen.INWORLD_FOOTER_SEPARATOR : Screen.FOOTER_SEPARATOR;
		int y = list.getY() - 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, header, list.getX(), y, 0, 0, Math.max(0, tabs[0] - list.getX()), 2, 32, 2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, header, tabs[1], y, 0, 0, Math.max(0, list.getRight() - tabs[1]), 2, 32, 2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, footer, list.getX(), list.getBottom(), 0, 0, list.getWidth(), 2, 32, 2);
		return true;
	}

	static void open(int tab, @Nullable Screen parent) {
		Compat.setScreen(switch (tab) {
			case FRIENDS -> new OrbitServersScreen(parent, FRIENDS);
			case RECOMMENDED -> new OrbitServersScreen(parent, RECOMMENDED);
			default -> new JoinMultiplayerScreen(parent);
		});
	}

	/** One tab, with Minecraft's tab textures. */
	private static final class Tab extends AbstractWidget {
		private static final Identifier SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
		private static final Identifier SELECTED_HOT = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");
		private static final Identifier NORMAL = Identifier.withDefaultNamespace("widget/tab");
		private static final Identifier NORMAL_HOT = Identifier.withDefaultNamespace("widget/tab_highlighted");

		private final int tab;
		private final boolean selected;
		private final @Nullable Screen parent;

		private static final Identifier LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
		private static final Identifier INWORLD_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");

		Tab(int x, int width, int tab, int selected, @Nullable Screen parent) {
			super(x, 0, width, TAB_HEIGHT, Component.translatable(KEYS[tab]));
			this.tab = tab;
			this.selected = tab == selected;
			this.parent = parent;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			boolean hot = isHoveredOrFocused();
			Identifier sprite = selected ? hot ? SELECTED_HOT : SELECTED : hot ? NORMAL_HOT : NORMAL;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
			Font font = Minecraft.getInstance().font;
			if (selected) {
				// Filled like the server list under it (over its top line), so the tab opens onto it.
				Identifier background = Minecraft.getInstance().level == null ? LIST_BACKGROUND : INWORLD_LIST_BACKGROUND;
				Screen.extractMenuBackgroundTexture(graphics, background, getX() + 2, getY() + 2, getX() + 2, getY() + 2, getWidth() - 4, getHeight() - 2);
				int w = Math.min(font.width(getMessage()), getWidth() - 4);
				int left = getX() + (getWidth() - w) / 2;
				graphics.fill(left, getBottom() - 2, left + w, getBottom() - 1, -1);
			}
			int textY = getY() + (getHeight() - 8) / 2 + (selected ? 0 : 2);
			graphics.centeredText(font, getMessage(), getX() + getWidth() / 2, textY, selected ? -1 : 0xFFA0A0A0);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (!selected) {
				open(tab, parent);
			}
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_NUMPADENTER)) {
				onClick(null, false);
				return true;
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
