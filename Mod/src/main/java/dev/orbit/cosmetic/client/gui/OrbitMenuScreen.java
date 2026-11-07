package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.CosmeticStatus;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.render.PickBuffer;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.social.ShareScreen;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.library.CosmeticSettingsFile;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.net.Catalog;
import dev.orbit.cosmetic.client.net.CatalogItem;
import dev.orbit.cosmetic.client.net.FeaturedCheck;
import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.client.skin.MojangAccount;
import dev.orbit.cosmetic.client.skin.SkinLibrary;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.emote.EmoteMode;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.emote.EmoteAnimation;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Orbit menu. A sidebar with the sections (everything the player has, their own catalog, the featured catalog,
 * the wardrobe by body part, and emotes), a grid of square tiles in the middle, and the player on the right wearing
 * what is equipped plus the selected item. Featured items are claimed from here; emotes are put on the emote wheels
 * from here; cosmetics with alternate textures get colour buttons on their tile.
 */
public final class OrbitMenuScreen extends OrbitScreen {
	private static final int HEADER = 22;
	private static final int ROW = 16;
	private static final int PAD = 6;
	private static final int GAP = 4;
	private static final int MIN_CELL = 62;
	private static final int WARDROBE_GAP = 20;
	/** The sidebar's row height and the room for the "Wardrobe" label, smaller on short windows. */
	private int sideRow = ROW;
	private int sideGap = WARDROBE_GAP;
	/** Tile colours of bundles and of Feature+ items that have none of their own. */
	private static final int[] RED_TILE = {0x00000000, 0x33FF2626};

	enum Section {
		USER("grid"),
		FEATURED("star"),
		// The wardrobe: everything that can be worn.
		ALL("all"),
		HEAD("player"),
		ARMS("shirt"),
		LEGS("shirt"),
		BODY("shirt"),
		BODY_PARTS("player"),
		SKINS("skin"),
		OUTFITS("outfit"),
		EMOTES("emote");

		final String icon;

		Section(String icon) {
			this.icon = icon;
		}

		Component label() {
			return Component.translatable("orbit_cosmetic.menu.section." + name().toLowerCase(Locale.ROOT));
		}

		LocalCosmetic.Category category() {
			return switch (this) {
				case HEAD -> LocalCosmetic.Category.HEAD;
				case ARMS -> LocalCosmetic.Category.ARMS;
				case LEGS -> LocalCosmetic.Category.LEGS;
				case BODY -> LocalCosmetic.Category.BODY;
				default -> null;
			};
		}
	}

	enum Filter {
		ALL,
		COSMETICS,
		EMOTES,
		BODY_PARTS,
		BUNDLES;

		Component label() {
			return Component.translatable("orbit_cosmetic.menu.filter." + name().toLowerCase(Locale.ROOT));
		}
	}

	/** Something shown as a tile. */
	sealed interface Item permits MyCosmetic, MyEmote, CatalogEntry, BundleEntry, SkinEntry, CapeEntry, OutfitEntry {
		String key();

		String name();

		/** Shown four tiles large. */
		default boolean big() {
			return false;
		}

		/** Takes the whole width of the grid and two rows. */
		default boolean wide() {
			return false;
		}
	}

	/** A skin file in Orbit/Skins. */
	record SkinEntry(SkinLibrary.LocalSkin skin) implements Item {
		public String key() {
			return "s:" + skin.key();
		}

		public String name() {
			return skin.name();
		}
	}

	/** A cape the account owns, or no cape (null). */
	record CapeEntry(MojangAccount.@Nullable Cape cape) implements Item {
		public String key() {
			return "k:" + (cape == null ? "" : cape.id());
		}

		public String name() {
			return cape == null ? Component.translatable("orbit_cosmetic.skins.no_cape").getString() : cape.alias();
		}
	}

	/** A saved outfit, by its place in the list. */
	record OutfitEntry(int index, OrbitState.Outfit outfit) implements Item {
		public String key() {
			return "o:" + index;
		}

		public String name() {
			return outfit.name;
		}
	}

	record MyCosmetic(LocalCosmetic entry) implements Item {
		public String key() {
			return "c:" + entry.key();
		}

		public String name() {
			return entry.displayName();
		}
	}

	record MyEmote(EmoteLibrary.LocalEmote emote) implements Item {
		public String key() {
			return "e:" + emote.key();
		}

		public String name() {
			return emote.displayName();
		}
	}

	record CatalogEntry(CatalogItem item) implements Item {
		public String key() {
			return "f:" + item.sha256();
		}

		public String name() {
			return item.name();
		}

		public boolean big() {
			return item.plus();
		}
	}

	record BundleEntry(Catalog.Bundle bundle) implements Item {
		public String key() {
			return "b:" + bundle.id();
		}

		public String name() {
			return bundle.name();
		}

		public boolean wide() {
			return true;
		}
	}

	/** A tile or a separator line, positioned inside the scrolling grid. */
	private record Placed(@Nullable Item item, @Nullable Component separator, int x, int y, int w, int h) {
	}

	private static Section lastSection = Section.ALL;

	private Section section = lastSection;
	private Filter filter = Filter.ALL;
	private String search = "";
	private @Nullable Item selected;
	/** Set when the selected cosmetic was unequipped: the preview stops trying it on until it is selected again. */
	private boolean selectionTakenOff;
	private List<Placed> placed = List.of();
	private int contentHeight;
	private double scroll;
	private final Map<String, PreviewModel> previews = new HashMap<>();
	/** Alternate textures picked for catalog items: shown, and given to the cosmetic when it is claimed. */
	private static final Map<String, Integer> catalogStyles = new HashMap<>();
	private @Nullable EditBox outfitName;
	private final PreviewModel panelPreview = new PreviewModel();
	/** Where the big preview's cosmetics were drawn, to click on them. */
	private final PickBuffer panelPick = new PickBuffer();
	/** The cosmetic under the mouse on the big preview, or null. */
	private @Nullable AssetId hoveredAsset;
	/** Where the mouse was pressed on the big preview: a click (not a drag) there selects the cosmetic under it. */
	private double pressX;
	private double pressY;
	/** Catalog files being downloaded, and when a failed one may be tried again. */
	private final Set<String> requested = new HashSet<>();
	private final Map<String, Long> retryAt = new HashMap<>();
	/** Featured items' particles for previews, by SHA-256 (see {@link #catalogParticles}). */
	private static final Map<String, List<ParticleSettings>> catalogParticles = new HashMap<>();
	private final List<net.minecraft.client.gui.components.AbstractWidget> actions = new ArrayList<>();
	private Catalog catalog;
	private @Nullable String catalogError;
	private boolean claiming;
	private float panelYaw = 25;
	private boolean draggingPreview;
	private int selectedSlot = -1;
	private final Runnable libraryListener = this::rebuildGrid;

	// Layout, computed in init().
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int sideW;
	private int midX;
	private int midW;
	private int rightX;
	private int rightW;
	private int gridX;
	private int gridY;
	private int gridW;
	private int gridH;
	private int cols;
	private int cellW;
	private int cellH;
	private EditBox searchBox;
	/** The grid's tiles for the keyboard (the mouse is handled by the screen), in the grid's order, by item key. */
	private final Map<String, Hotspot> tileSpots = new LinkedHashMap<>();

	public OrbitMenuScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.menu.title"), parent);
		// Pick up files saved since the menu was last open (only changed files are checked again).
		orbit().library().reload();
		orbit().emotes().reload();
		panelPreview.setPicking(panelPick);
	}

	/** The menu opened on the emote section (from the emote wheel's Edit button). */
	public static OrbitMenuScreen emotes(@Nullable Screen parent) {
		OrbitMenuScreen screen = new OrbitMenuScreen(parent);
		screen.section = Section.EMOTES;
		lastSection = Section.EMOTES;
		return screen;
	}

	// ---------------------------------------------------------------- setup

	@Override
	protected void init() {
		tileSpots.clear();
		orbit().library().addListener(libraryListener);
		orbit().emotes().addListener(libraryListener);
		orbit().skins().library().addListener(libraryListener);
		if (section == Section.SKINS) {
			orbit().skins().account().refresh();
		}
		if (catalog == null) {
			catalog = orbit().backend().catalog();
			loadCatalog();
		}

		int[] panel = panel();
		panelX = panel[0];
		panelY = panel[1];
		panelW = panel[2];
		panelH = panel[3];
		// Narrower side columns on small windows, so the grid keeps room.
		sideW = Math.clamp(panelW * 26 / 100, Math.min(128, panelW * 30 / 100), 140);
		rightW = Math.clamp(panelW * 28 / 100, Math.min(104, panelW * 26 / 100), 176);
		midX = panelX + sideW;
		midW = panelW - sideW - rightW;
		rightX = midX + midW;
		gridX = midX + PAD;
		gridY = panelY + HEADER + PAD;
		gridW = midW - PAD * 2 - 4;
		gridH = panelH - HEADER - PAD * 2;
		// Cells of about 80 pixels, never narrower than MIN_CELL.
		cols = Math.max(2, Math.min((gridW + GAP) / (MIN_CELL + GAP), Math.round((gridW + GAP) / (80F + GAP))));
		cellW = (gridW - GAP * (cols - 1)) / cols;
		// Square tiles; the name is drawn over the bottom of the tile.
		cellH = cellW;

		backButton(panelX - 22, panelY);

		// Sidebar sections, between the header and the player's row: on short windows the rows get lower, and then the
		// "Wardrobe" label makes room too.
		int sections = Section.values().length;
		int room = panelH - HEADER - 4 - 26;
		sideGap = WARDROBE_GAP;
		sideRow = Math.clamp((room - sideGap) / sections - 1, 11, ROW);
		if (sections * (sideRow + 1) + sideGap > room) {
			sideGap = 0;
			sideRow = Math.clamp(room / sections - 1, 9, ROW);
		}
		int y = panelY + HEADER + 4;
		for (Section entry : Section.values()) {
			if (entry == Section.ALL) {
				// Room for the "Wardrobe" label between the catalogs and what can be worn.
				y += sideGap;
			}
			FlatButton button = addRenderableWidget(new FlatButton(panelX + 4, y, sideW - 8, sideRow, entry.label(), entry.icon, FlatButton.Style.GHOST, b -> setSection(entry)).alignLeft());
			button.setSelected(entry == section);
			if (entry == Section.FEATURED) {
				button.iconColor(Theme.RED);
			}
			y += sideRow + 1;
		}
		addRenderableWidget(FlatButton.icon(panelX + sideW - 20, panelY + 3, 16, "folder", Component.translatable("orbit_cosmetic.menu.open_folder"), FlatButton.Style.GHOST,
			b -> Compat.openPath(orbit().paths().root())));
		addRenderableWidget(FlatButton.icon(panelX + sideW - 20, panelY + panelH - 20, 16, "settings", Component.translatable("orbit_cosmetic.menu.settings"), FlatButton.Style.GHOST,
			b -> Compat.setScreen(new OrbitSettingsScreen(this))));
		String update = dev.orbit.cosmetic.client.Updates.available();
		if (update != null) {
			// A newer Orbit is out: the arrow next to Settings.
			FlatButton arrow = addRenderableWidget(FlatButton.icon(panelX + sideW - 40, panelY + panelH - 20, 16, "download",
				Component.translatable("orbit_cosmetic.update.button", update), FlatButton.Style.PRIMARY, b -> Compat.setScreen(new UpdatePromptScreen(this))));
			arrow.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("orbit_cosmetic.update.button", update)));
		}

		// Middle header: search, filter, template, coins.
		int headerRight = midX + midW - PAD;
		if (JokeCoins.enabled()) {
			String balance = JokeCoins.format(JokeCoins.balance());
			int coinWidth = font.width(balance) + 20;
			FlatButton coins = addRenderableWidget(new FlatButton(headerRight - coinWidth, panelY + 3, coinWidth, 16, Component.literal(balance), "coin", FlatButton.Style.PRIMARY, b -> {
				JokeCoins.addFreeCoins();
				rebuildWidgets();
			}));
			coins.iconColor(Theme.YELLOW);
			coins.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.coins.tooltip")));
			headerRight -= coinWidth + 4;
		}
		if (section == Section.FEATURED) {
			int filterWidth = Math.min(70, Math.max(54, font.width(filter.label()) + 22));
			addRenderableWidget(new FlatButton(headerRight - filterWidth, panelY + 3, filterWidth, 16, filter.label(), "filter", FlatButton.Style.SECONDARY, b -> {
				filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
				scroll = 0;
				rebuildWidgets();
			}));
			headerRight -= filterWidth + 4;
		} else if (section == Section.SKINS) {
			addRenderableWidget(FlatButton.icon(headerRight - 16, panelY + 3, 16, "plus", Component.translatable("orbit_cosmetic.skins.save_current"), FlatButton.Style.SECONDARY,
				b -> saveAccountSkin()));
			headerRight -= 20;
		} else if (section == Section.OUTFITS) {
			addRenderableWidget(FlatButton.icon(headerRight - 16, panelY + 3, 16, "plus", Component.translatable("orbit_cosmetic.outfits.new"), FlatButton.Style.SECONDARY,
				b -> newOutfit()));
			headerRight -= 20;
		} else {
			String kind = section == Section.EMOTES ? "emote" : section == Section.BODY_PARTS ? "body_part" : "cosmetic";
			addRenderableWidget(FlatButton.icon(headerRight - 16, panelY + 3, 16, "plus",
				Component.translatable("orbit_cosmetic.menu.template." + kind), FlatButton.Style.SECONDARY, b -> createTemplate(kind)));
			headerRight -= 20;
		}
		int titleWidth = font.width(section.label()) + 8;
		int searchWidth = Math.clamp(headerRight - (midX + PAD + titleWidth), 40, 120);
		searchBox = new EditBox(font, headerRight - searchWidth + 12, panelY + 7, searchWidth - 14, 10, Component.translatable("orbit_cosmetic.menu.search"));
		searchBox.setBordered(false);
		// The hint only when it fits (Minecraft does not clip it to the field).
		if (font.width(Component.translatable("orbit_cosmetic.menu.search")) <= searchBox.getWidth() - 2) {
			searchBox.setHint(Component.translatable("orbit_cosmetic.menu.search").withStyle(ChatFormatting.DARK_GRAY));
		}
		searchBox.setTextColor(Theme.TEXT);
		searchBox.setValue(search);
		searchBox.setResponder(text -> {
			search = text.strip().toLowerCase(Locale.ROOT);
			scroll = 0;
			rebuildGrid();
		});
		addRenderableWidget(searchBox);

		rebuildGrid();
	}

	@Override
	public void removed() {
		orbit().library().removeListener(libraryListener);
		orbit().emotes().removeListener(libraryListener);
		orbit().skins().library().removeListener(libraryListener);
	}

	private void setSection(Section next) {
		if (section != next) {
			section = next;
			lastSection = next;
			selected = null;
			selectedSlot = -1;
			scroll = 0;
			filter = Filter.ALL;
			if (next == Section.SKINS) {
				orbit().skins().account().refresh();
			}
			if (next == Section.FEATURED) {
				loadCatalog();
			}
			rebuildWidgets();
		}
	}

	private void loadCatalog() {
		catalogError = null;
		orbit().backend().loadCatalog(loaded -> {
			catalog = loaded;
			rebuildGrid();
		}, error -> {
			catalogError = error;
			rebuildGrid();
		});
	}

	/** @param kind "emote", "body_part" or "cosmetic" */
	private void createTemplate(String kind) {
		try {
			Path template = switch (kind) {
				case "emote" -> orbit().emotes().createTemplate();
				case "body_part" -> orbit().library().createBodyPartTemplate();
				default -> orbit().library().createTemplate();
			};
			Compat.openPath(template.getParent());
			Notifications.info(Component.translatable("orbit_cosmetic.menu.template.created"), Component.literal(orbit().paths().root().relativize(template).toString()));
		} catch (IOException e) {
			Notifications.error(Component.translatable("orbit_cosmetic.menu.template.failed"), Component.literal(e.getMessage()));
		}
	}

	// ---------------------------------------------------------------- items and layout

	private List<Item> items() {
		List<Item> result = new ArrayList<>();
		LocalCosmetic.Category category = section.category();
		switch (section) {
			case FEATURED -> {
				for (CatalogItem item : catalog.items()) {
					if (filter == Filter.ALL || filter == Filter.COSMETICS && item.kind() == CatalogItem.Kind.COSMETIC || filter == Filter.EMOTES && item.isEmote()
						|| filter == Filter.BODY_PARTS && item.isBodyPart()) {
						result.add(new CatalogEntry(item));
					}
				}
			}
			case EMOTES -> orbit().emotes().emotes().forEach(emote -> result.add(new MyEmote(emote)));
			case BODY_PARTS -> {
				List<LocalCosmetic> parts = new ArrayList<>(orbit().library().entries().stream().filter(LocalCosmetic::isBodyPart).toList());
				parts.sort(CosmeticLibrary.byName());
				parts.forEach(entry -> result.add(new MyCosmetic(entry)));
			}
			case SKINS -> orbit().skins().library().skins().forEach(skin -> result.add(new SkinEntry(skin)));
			case OUTFITS -> {
				List<OrbitState.Outfit> outfits = orbit().state().outfits();
				for (int i = 0; i < outfits.size(); i++) {
					result.add(new OutfitEntry(i, outfits.get(i)));
				}
			}
			default -> {
				// The User Catalog is the player's own items; All Items and the wardrobe add the claimed featured ones.
				// A claimed copy that is no longer featured counts as the player's own.
				boolean ownOnly = section == Section.USER;
				List<LocalCosmetic> cosmetics = new ArrayList<>(orbit().library().entries());
				cosmetics.sort(CosmeticLibrary.byName());
				for (LocalCosmetic entry : cosmetics) {
					if ((category == null || entry.categories().contains(category)) && !(ownOnly && entry.isFeatured() && check(entry).featured())) {
						result.add(new MyCosmetic(entry));
					}
				}
				if (section == Section.USER || section == Section.ALL) {
					for (EmoteLibrary.LocalEmote emote : orbit().emotes().emotes()) {
						if (!(ownOnly && emote.file().isFeatured() && check(emote.file()).featured())) {
							result.add(new MyEmote(emote));
						}
					}
				}
			}
		}
		if (!search.isEmpty()) {
			result.removeIf(item -> !item.name().toLowerCase(Locale.ROOT).contains(search));
		}
		return result;
	}

	private List<Item> bundles() {
		if (section != Section.FEATURED || filter == Filter.COSMETICS || filter == Filter.EMOTES || filter == Filter.BODY_PARTS) {
			return List.of();
		}
		List<Item> result = new ArrayList<>();
		for (Catalog.Bundle bundle : catalog.bundles()) {
			if (search.isEmpty() || bundle.name().toLowerCase(Locale.ROOT).contains(search)) {
				result.add(new BundleEntry(bundle));
			}
		}
		return result;
	}

	/** Lays the items out: featured items, then the bundles between two lines, then the remaining items. */
	private void rebuildGrid() {
		if (cols == 0) {
			return;
		}
		List<Item> items = filter == Filter.BUNDLES ? List.of() : items();
		List<Item> bundles = bundles();
		List<Placed> result = new ArrayList<>();
		int y = 0;
		if (section == Section.SKINS) {
			// The skins, then the capes the account owns (and no cape).
			y = pack(items, result, y);
			List<Item> capes = new ArrayList<>();
			capes.add(new CapeEntry(null));
			MojangAccount.Profile profile = orbit().skins().account().profile();
			shownProfile = profile;
			if (profile != null) {
				profile.capes().forEach(cape -> capes.add(new CapeEntry(cape)));
			}
			capes.removeIf(item -> !search.isEmpty() && !item.name().toLowerCase(Locale.ROOT).contains(search));
			y = separator(Component.translatable("orbit_cosmetic.skins.capes"), result, y);
			y = pack(capes, result, y);
		} else if (bundles.isEmpty()) {
			y = pack(items, result, y);
		} else {
			// The first two rows of items, then the bundles, then the rest.
			int slots = cols * 2;
			int split = 0;
			while (split < items.size() && slots > 0) {
				slots -= items.get(split).big() ? (cols >= 3 ? 4 : 2) : 1;
				if (slots >= 0) {
					split++;
				}
			}
			y = pack(items.subList(0, split), result, y);
			y = separator(Component.translatable("orbit_cosmetic.menu.separator.bundles"), result, y);
			y = pack(bundles, result, y);
			if (split < items.size()) {
				y = separator(Component.translatable("orbit_cosmetic.menu.separator.more"), result, y);
				y = pack(items.subList(split, items.size()), result, y);
			}
		}
		placed = List.copyOf(result);
		contentHeight = y;
		scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - gridH));
		if (selected != null && placed.stream().noneMatch(p -> p.item() != null && p.item().key().equals(selected.key()))) {
			// The selection is gone (deleted, filtered out): keep it only if it still exists somewhere.
			selected = refreshed(selected);
		}
		rebuildTileSpots();
		refreshActions();
	}

	/**
	 * Makes the keyboard's tiles again, in the grid's order (so Tab goes through them in order); the keyboard stays
	 * on the tile it was on.
	 */
	private void rebuildTileSpots() {
		String focusedKey = null;
		for (Map.Entry<String, Hotspot> entry : tileSpots.entrySet()) {
			if (entry.getValue() == getFocused()) {
				focusedKey = entry.getKey();
			}
			removeWidget(entry.getValue());
		}
		tileSpots.clear();
		for (Placed p : placed) {
			if (p.item() != null && !tileSpots.containsKey(p.item().key())) {
				String key = p.item().key();
				tileSpots.put(key, addRenderableWidget(new Hotspot(Component.literal(p.item().name()), h -> pressTile(key)).keyboardOnly()));
			}
		}
		placeTileSpots();
		if (focusedKey != null && tileSpots.containsKey(focusedKey)) {
			setFocused(tileSpots.get(focusedKey));
		}
	}

	private void placeTileSpots() {
		for (Placed p : placed) {
			Hotspot spot = p.item() == null ? null : tileSpots.get(p.item().key());
			if (spot != null) {
				spot.place(gridX + p.x(), gridY - (int) scroll + p.y(), p.w(), p.h(), gridY, gridY + gridH);
			}
		}
	}

	private @Nullable Placed placedOf(String key) {
		for (Placed p : placed) {
			if (p.item() != null && p.item().key().equals(key)) {
				return p;
			}
		}
		return null;
	}

	/**
	 * Enter on a tile (it is selected when the keyboard reaches it): equips or takes off a cosmetic, and otherwise
	 * does the item's main action (play, wear, claim).
	 */
	private void pressTile(String key) {
		Placed p = placedOf(key);
		if (p == null) {
			return;
		}
		if (selected == null || !selected.key().equals(key)) {
			select(p.item());
			return;
		}
		if (p.item() instanceof MyCosmetic cosmetic && (cosmetic.entry().isUsable() || cosmetic.entry().isLoaded())) {
			toggleEquipped(cosmetic.entry());
			return;
		}
		for (net.minecraft.client.gui.components.AbstractWidget widget : List.copyOf(actions)) {
			if (widget instanceof FlatButton button && button.active && button.getStyle() == FlatButton.Style.PRIMARY) {
				button.press();
				return;
			}
		}
	}

	@Override
	public void setFocused(net.minecraft.client.gui.components.events.@Nullable GuiEventListener listener) {
		super.setFocused(listener);
		// The keyboard on a tile: it is selected, and scrolled into sight.
		if (listener instanceof Hotspot spot) {
			for (Map.Entry<String, Hotspot> entry : tileSpots.entrySet()) {
				if (entry.getValue() == spot) {
					Placed p = placedOf(entry.getKey());
					if (p != null) {
						if (p.y() < scroll) {
							scroll = Math.max(0, p.y() - GAP);
						} else if (p.y() + p.h() > scroll + gridH) {
							scroll = Math.min(Math.max(0, contentHeight - gridH), p.y() + p.h() - gridH + GAP);
						}
						placeTileSpots();
						if (selected == null || !selected.key().equals(entry.getKey())) {
							select(p.item());
						}
					}
				}
			}
		}
	}

	private int separator(Component label, List<Placed> out, int y) {
		int top = y == 0 ? 6 : y + 6;
		out.add(new Placed(null, label, 0, top, gridW, 1));
		return top + 10;
	}

	/**
	 * Packs tiles left to right, top to bottom. Big tiles take two by two cells and are spread out: small tiles
	 * between them, and every other one on the right. Wide tiles take two whole rows. Returns the height used.
	 */
	private int pack(List<Item> unordered, List<Placed> out, int top) {
		List<Item> items = spreadBig(unordered);
		List<BitSet> rows = new ArrayList<>();
		int usedRows = 0;
		int bigCount = 0;
		for (Item item : items) {
			// Big tiles take two by two cells; in a two-column grid that would be everything, so two by one.
			int span = item.wide() ? cols : item.big() ? 2 : 1;
			int spanRows = item.wide() || item.big() && cols >= 3 ? 2 : 1;
			boolean right = item.big() && bigCount++ % 2 == 1;
			for (int row = 0; ; row++) {
				while (rows.size() < row + spanRows) {
					rows.add(new BitSet(cols));
				}
				int column = free(rows, row, span, spanRows, right);
				if (column >= 0) {
					for (int r = row; r < row + spanRows; r++) {
						rows.get(r).set(column, column + span);
					}
					int x = column * (cellW + GAP);
					int y = top + row * (cellH + GAP);
					out.add(new Placed(item, null, x, y, span * cellW + (span - 1) * GAP, spanRows * cellH + (spanRows - 1) * GAP));
					usedRows = Math.max(usedRows, row + spanRows);
					break;
				}
			}
		}
		return usedRows == 0 ? top : top + usedRows * (cellH + GAP) - GAP;
	}

	/** The items with the big ones spaced out among the others (in order otherwise). */
	private List<Item> spreadBig(List<Item> items) {
		List<Item> big = items.stream().filter(Item::big).toList();
		List<Item> rest = items.stream().filter(item -> !item.big()).toList();
		if (big.isEmpty()) {
			return items;
		}
		// At least two rows of small tiles between two big ones (a big one fills two cells of a row).
		int between = Math.max(Math.max(1, cols - 2) * 2, rest.size() / (big.size() + 1));
		List<Item> result = new ArrayList<>();
		int next = 0;
		for (Item item : big) {
			result.add(item);
			for (int i = 0; i < between && next < rest.size(); i++) {
				result.add(rest.get(next++));
			}
		}
		result.addAll(rest.subList(next, rest.size()));
		return result;
	}

	/** The first column (or the last one, {@code fromRight}) where a tile fits at this row, or -1. */
	private int free(List<BitSet> rows, int row, int span, int spanRows, boolean fromRight) {
		for (int i = 0; i + span <= cols; i++) {
			int column = fromRight ? cols - span - i : i;
			if (fits(rows, row, column, span, spanRows)) {
				return column;
			}
		}
		return -1;
	}

	private static boolean fits(List<BitSet> rows, int row, int column, int span, int spanRows) {
		for (int r = row; r < row + spanRows; r++) {
			int taken = rows.get(r).nextSetBit(column);
			if (taken >= 0 && taken < column + span) {
				return false;
			}
		}
		return true;
	}


	/** The current version of an item after the libraries changed, or null if it no longer exists. */
	private @Nullable Item refreshed(Item item) {
		return switch (item) {
			case MyCosmetic cosmetic -> orbit().library().entries().stream().filter(e -> e.key().equals(cosmetic.entry().key())).findFirst().<Item>map(MyCosmetic::new).orElse(null);
			case MyEmote emote -> {
				EmoteLibrary.LocalEmote current = orbit().emotes().find(emote.emote().key());
				yield current == null ? null : new MyEmote(current);
			}
			case SkinEntry skin -> {
				SkinLibrary.LocalSkin current = orbit().skins().library().find(skin.skin().key());
				yield current == null ? null : new SkinEntry(current);
			}
			case OutfitEntry outfit -> outfit.index() < orbit().state().outfits().size() && orbit().state().outfits().get(outfit.index()) == outfit.outfit() ? item : null;
			default -> item;
		};
	}

	// ---------------------------------------------------------------- previews

	private PreviewModel preview(Item item) {
		PreviewModel model = previews.computeIfAbsent(item.key(), key -> new PreviewModel());
		switch (item) {
			case MyCosmetic cosmetic -> model.setWorn(cosmetic.entry().isUsable() ? List.of(cosmetic.entry().toEquipped()) : List.of());
			case MyEmote emote -> loopEmote(model, emote.emote());
			case CatalogEntry entry -> showCatalogItem(model, List.of(entry.item()));
			case BundleEntry bundle -> showCatalogItem(model, bundle.bundle().items());
			case SkinEntry skin -> {
				model.setWorn(orbit().equipped());
				model.setSkin(orbit().skins().with(orbit().accountSkin(), skin.skin(), orbit().skins().cape()));
			}
			case CapeEntry cape -> model.setSkin(orbit().skins().with(orbit().menuSkin(), null, cape.cape() == null ? "" : cape.cape().id()));
			case OutfitEntry outfit -> {
				model.setWorn(outfitCosmetics(outfit.outfit()));
				model.setSkin(outfitSkin(outfit.outfit()));
			}
		}
		return model;
	}

	/** The cosmetics of an outfit (those still in the library), with the outfit's alternate textures. */
	private List<EquippedCosmetic> outfitCosmetics(OrbitState.Outfit outfit) {
		List<EquippedCosmetic> worn = new ArrayList<>();
		for (LocalCosmetic entry : orbit().library().entries()) {
			if (entry.isUsable() && outfit.equipped.contains(entry.key())) {
				worn.add(entry.toEquipped().withStyle(outfit.styles.getOrDefault(entry.key(), entry.style())));
			}
		}
		return worn;
	}

	/** How the player looks in an outfit: its skin and cape, or what is worn now where it has none. */
	private net.minecraft.world.entity.player.PlayerSkin outfitSkin(OrbitState.Outfit outfit) {
		SkinLibrary.LocalSkin skin = outfit.skin == null ? orbit().skins().skin() : orbit().skins().library().find(outfit.skin);
		return orbit().skins().with(orbit().accountSkin(), skin, outfit.cape != null ? outfit.cape : orbit().skins().cape());
	}

	/** Shows catalog items: cosmetics worn, their emotes played one after another; downloads them first (they are cached). */
	private void showCatalogItem(PreviewModel model, List<CatalogItem> items) {
		List<EquippedCosmetic> worn = new ArrayList<>();
		addCatalog(model, worn, items);
		model.setWorn(worn);
	}

	private static List<AnimationSettings> defaults(BakedCosmetic baked) {
		List<AnimationSettings> settings = new ArrayList<>();
		for (AnimationClip clip : baked.model().animations()) {
			settings.add(AnimationSettings.defaultsFor(clip));
		}
		return settings;
	}

	/**
	 * Makes sure a catalog item's file is loaded (from the cache, or downloaded). Unused files are released after a
	 * while, so an item scrolled out of sight is loaded again when it comes back.
	 */
	private @Nullable BakedCosmetic ensure(CatalogItem item, AssetId asset) {
		BakedCosmetic baked = orbit().registry().get(asset);
		String sha = item.sha256();
		if (baked == null && !orbit().registry().isKnown(asset) && System.currentTimeMillis() >= retryAt.getOrDefault(sha, 0L) && requested.add(sha)) {
			orbit().backend().downloadModel(item, bytes -> {
				requested.remove(sha);
				orbit().remotes().acceptAsset(asset, bytes);
			}, error -> {
				requested.remove(sha);
				retryAt.put(sha, System.currentTimeMillis() + 10_000);
			});
		}
		return baked;
	}

	/** Plays an emote on a preview, again and again. */
	private static void loopEmote(PreviewModel model, EmoteLibrary.LocalEmote emote) {
		if (emote.asset() != null) {
			model.loopEmote(emote.asset(), emote.file().particles());
		}
	}

	/**
	 * The cosmetic of the big preview at a point (GUI pixels from its top left corner) that can be selected, or null
	 * (nothing there, the player's body in front, or an emote's props).
	 */
	private @Nullable AssetId pickAt(double x, double y) {
		float scale = (float) minecraft.getWindow().getGuiScale();
		AssetId asset = panelPick.pick((float) x * scale, (float) y * scale);
		return asset != null && itemFor(asset) != null ? asset : null;
	}

	/** Outlines cosmetics of the big preview: the selected one in red, the hovered one in white (drawn over it). */
	private void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, @Nullable AssetId selectedAsset, @Nullable AssetId hovered) {
		if (selectedAsset == null && hovered == null || width <= 0 || height <= 0) {
			return;
		}
		PickBuffer.Coverage coverage = panelPick.coverage(width, height, (float) minecraft.getWindow().getGuiScale());
		if (selectedAsset != null) {
			for (int[] run : coverage.outline(selectedAsset)) {
				graphics.fill(x + run[0], y + run[1], x + run[0] + run[2], y + run[1] + 1, Theme.RED);
			}
		}
		if (hovered != null && !hovered.equals(selectedAsset)) {
			for (int[] run : coverage.outline(hovered)) {
				graphics.fill(x + run[0], y + run[1], x + run[0] + run[2], y + run[1] + 1, 0xFFFFFFFF);
			}
		}
	}

	/** What selecting a cosmetic of the big preview selects: the selected catalog item, or the cosmetic of the library. */
	private @Nullable Item itemFor(AssetId asset) {
		List<CatalogItem> shown = switch (selected) {
			case CatalogEntry entry -> List.of(entry.item());
			case BundleEntry bundle -> bundle.bundle().items();
			case null, default -> List.of();
		};
		for (CatalogItem item : shown) {
			if (!item.isEmote() && asset.equals(AssetId.tryParse(item.sha256()))) {
				return selected;
			}
		}
		LocalCosmetic found = null;
		for (LocalCosmetic entry : orbit().library().entries()) {
			if (entry.isUsable() && asset.equals(entry.asset()) && (found == null || entry.isLoaded() && !found.isLoaded())) {
				found = entry;
			}
		}
		return found == null ? null : new MyCosmetic(found);
	}

	/** The selected cosmetic's file, shown red on the big preview; null when the selection is not one cosmetic. */
	private @Nullable AssetId selectedAsset() {
		return switch (selected) {
			case MyCosmetic cosmetic -> cosmetic.entry().asset();
			case CatalogEntry entry when !entry.item().isEmote() -> AssetId.tryParse(entry.item().sha256());
			case null, default -> null;
		};
	}

	/** What the big preview on the right shows: what is equipped, plus the selection. */
	private void updatePanelPreview() {
		List<EquippedCosmetic> worn = new ArrayList<>(orbit().equipped());
		panelPreview.setSkin(null);
		switch (selected) {
			case SkinEntry skin -> panelPreview.setSkin(orbit().skins().with(orbit().accountSkin(), skin.skin(), orbit().skins().cape()));
			case CapeEntry cape -> panelPreview.setSkin(orbit().skins().with(orbit().menuSkin(), null, cape.cape() == null ? "" : cape.cape().id()));
			case OutfitEntry outfit -> {
				worn = new ArrayList<>(outfitCosmetics(outfit.outfit()));
				panelPreview.setSkin(outfitSkin(outfit.outfit()));
			}
			case MyCosmetic cosmetic when cosmetic.entry().isUsable() && !cosmetic.entry().isLoaded() && !selectionTakenOff -> worn.add(cosmetic.entry().toEquipped());
			case MyEmote emote -> loopEmote(panelPreview, emote.emote());
			case CatalogEntry entry -> addCatalog(panelPreview, worn, List.of(entry.item()));
			case BundleEntry bundle -> addCatalog(panelPreview, worn, bundle.bundle().items());
			case null, default -> {
			}
		}
		if (!(selected instanceof MyEmote) && !(selected instanceof CatalogEntry entry && entry.item().isEmote()) && !(selected instanceof BundleEntry)) {
			panelPreview.stopEmote();
		}
		Map<AssetId, EquippedCosmetic> unique = new LinkedHashMap<>();
		worn.forEach(cosmetic -> unique.putIfAbsent(cosmetic.asset(), cosmetic));
		panelPreview.setWorn(List.copyOf(unique.values()));
	}

	/** Adds catalog items to what a preview wears, and plays their emotes on it in turn. */
	private void addCatalog(PreviewModel model, List<EquippedCosmetic> worn, List<CatalogItem> items) {
		List<CatalogItem> emotes = new ArrayList<>();
		for (CatalogItem item : items) {
			AssetId asset = AssetId.tryParse(item.sha256());
			BakedCosmetic baked = asset == null ? null : ensure(item, asset);
			if (asset != null && item.isEmote()) {
				emotes.add(item);
			} else if (baked != null && item.isBodyPart()) {
				// Body parts have no animations; a file with no usable part replaces nothing.
				if (baked.bodyParts() != 0) {
					worn.add(new EquippedCosmetic(asset, item.name(), List.of(), List.of(), catalogStyle(item), catalogParticles(item, baked), baked.bodyParts(),
						dev.orbit.cosmetic.common.anim.TextureAnimation.defaults(baked.model().textures())));
				}
			} else if (baked != null) {
				worn.add(new EquippedCosmetic(asset, item.name(), defaults(baked), List.of(), catalogStyle(item), catalogParticles(item, baked), 0,
					dev.orbit.cosmetic.common.anim.TextureAnimation.defaults(baked.model().textures())));
			}
		}
		playInTurn(model, emotes);
	}

	/**
	 * A catalog item's particles, from its settings file: downloaded once for previews (claiming downloads it again
	 * with the model). Empty until it arrives, or without one.
	 */
	private List<ParticleSettings> catalogParticles(CatalogItem item, @Nullable BakedCosmetic baked) {
		String sha = item.sha256();
		if (baked == null) {
			return List.of();
		}
		List<ParticleSettings> known = catalogParticles.get(sha);
		if (known != null) {
			return known;
		}
		if (item.hasSettings() && System.currentTimeMillis() >= retryAt.getOrDefault("settings:" + sha, 0L) && requested.add("settings:" + sha)) {
			orbit().backend().downloadSettings(item, settings -> {
				requested.remove("settings:" + sha);
				catalogParticles.put(sha, settings == null ? List.of() : CosmeticSettingsFile.particlesIn(settings, baked.model()));
			}, error -> {
				requested.remove("settings:" + sha);
				retryAt.put("settings:" + sha, System.currentTimeMillis() + 10_000);
			});
		} else if (!item.hasSettings()) {
			catalogParticles.put(sha, List.of());
		}
		return List.of();
	}

	/** Plays emotes on a preview one after another, starting over after the last. */
	private void playInTurn(PreviewModel model, List<CatalogItem> items) {
		if (items.isEmpty()) {
			return;
		}
		List<AssetId> emotes = items.stream().map(item -> AssetId.tryParse(item.sha256())).toList();
		Wearer.Emote playing = model.emote();
		int current = playing == null ? -1 : emotes.indexOf(playing.asset);
		if (current >= 0) {
			// Its particles arrive with its settings file, maybe after it started.
			model.setEmoteParticles(catalogParticles(items.get(current), orbit().registry().get(playing.asset)));
		}
		// A looping emote is shown for a few seconds before the next one.
		if (current >= 0 && !playing.hasShown(OrbitClient.get().menuTime(), 4)) {
			return;
		}
		int nextIndex = (current + 1) % emotes.size();
		AssetId next = emotes.get(nextIndex);
		BakedCosmetic baked = next == null ? null : orbit().registry().get(next);
		if (baked != null && EmoteAnimation.of(baked.model()) != null) {
			model.playEmote(next, catalogParticles(items.get(nextIndex), baked));
		}
	}

	// ---------------------------------------------------------------- actions (right panel)

	private void select(@Nullable Item item) {
		if (item == null || selected == null || !item.key().equals(selected.key())) {
			selectionTakenOff = false;
		}
		selected = item;
		refreshActions();
	}

	/** Equips or unequips a cosmetic; unequipped, it also leaves the preview. */
	private void toggleEquipped(LocalCosmetic entry) {
		orbit().library().setLoaded(entry, !entry.isLoaded());
		selectionTakenOff = !entry.isLoaded();
		refreshActions();
	}

	private void refreshActions() {
		// The keyboard stays on the same button (Equip becomes Unequip, a wheel slot gets its emote).
		int focusedAction = actions.indexOf(getFocused());
		refreshActionWidgets();
		if (focusedAction >= 0 && focusedAction < actions.size() && actions.get(focusedAction).active) {
			setFocused(actions.get(focusedAction));
		}
	}

	private void refreshActionWidgets() {
		actions.forEach(this::removeWidget);
		actions.clear();
		int x = rightX + PAD;
		int w = rightW - PAD * 2;
		int y = panelY + panelH - PAD - 18;
		if (section == Section.EMOTES) {
			addWheelButtons(x, w);
			addWheelSlots();
			y = wheelTop() - 22;
			if (selected instanceof MyEmote emote) {
				int buttons = emoteFileButtons(x + w, y, emote.emote());
				emoteRow(x, y, w - buttons, emote.emote(), FlatButton.Style.PRIMARY);
			}
			return;
		}
		switch (selected) {
			case MyCosmetic cosmetic -> {
				LocalCosmetic entry = cosmetic.entry();
				boolean equipped = entry.isLoaded();
				// Claimed featured cosmetics: [Equip] [make an editable copy] [remove] on one row.
				int icons = entry.isFeatured() ? 2 * (18 + 4) : 0;
				FlatButton equip = action(new FlatButton(x, y, w - icons, 18, Component.translatable(equipped ? "orbit_cosmetic.menu.unequip" : "orbit_cosmetic.menu.equip"),
					equipped ? "close" : "check", equipped ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY, b -> toggleEquipped(entry)));
				equip.active = entry.isUsable() || equipped;
				if (entry.isFeatured()) {
					action(FlatButton.icon(x + w - 18 * 2 - 4, y, 18, "edit", Component.translatable("orbit_cosmetic.menu.make_editable.tooltip"), FlatButton.Style.SECONDARY, b -> {
						try {
							orbit().library().makeEditable(entry);
							Notifications.success(Component.translatable("orbit_cosmetic.menu.made_editable", entry.displayName()), Component.translatable("orbit_cosmetic.menu.made_editable.detail"));
						} catch (IOException e) {
							Notifications.error(Component.translatable("orbit_cosmetic.menu.make_editable.failed"), Component.literal(e.getMessage()));
						}
					}));
					action(FlatButton.icon(x + w - 18, y, 18, "trash", Component.translatable("orbit_cosmetic.menu.remove_featured"), FlatButton.Style.DANGER, b -> delete(entry)));
					FeaturedCheck check = check(entry);
					if (check.state() == FeaturedCheck.State.OUTDATED) {
						// The catalog's version differs from this copy.
						action(new FlatButton(x, y - 22, w, 18, Component.translatable("orbit_cosmetic.menu.update"), "update", FlatButton.Style.PRIMARY,
							b -> update(check.item(), entry.displayName(), (bytes, settings) -> orbit().library().updateFeatured(entry, bytes, settings, check.item().gradient()))))
							.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.menu.update.tooltip")));
					}
					return;
				}
				y -= 22;
				FlatButton animations = action(new FlatButton(x, y, w - 3 * (18 + 4), 18, Component.translatable("orbit_cosmetic.menu.animations"), "edit", FlatButton.Style.SECONDARY,
					b -> Compat.setScreen(entry.isBodyPart() ? new BodyPartScreen(this, entry) : new AnimationSettingsScreen(this, entry))));
				animations.active = entry.isUsable() && entry.model() != null;
				FlatButton send = action(FlatButton.icon(x + w - 18 * 3 - 8, y, 18, "send", Component.translatable("orbit_cosmetic.social.share.button"), FlatButton.Style.SECONDARY,
					b -> ShareScreen.cosmetic(this, entry)));
				send.active = entry.isUsable() && entry.asset() != null;
				action(FlatButton.icon(x + w - 18 * 2 - 4, y, 18, "folder", Component.translatable("orbit_cosmetic.menu.show_file"), FlatButton.Style.SECONDARY,
					b -> Compat.openPath(entry.path().getParent())));
				action(FlatButton.icon(x + w - 18, y, 18, "trash", Component.translatable("orbit_cosmetic.menu.delete"), FlatButton.Style.DANGER, b -> delete(entry)));
			}
			case MyEmote emote -> {
				int buttons = emoteFileButtons(x + w, y, emote.emote());
				action(new FlatButton(x, y, w - buttons, 18, Component.translatable("orbit_cosmetic.menu.emote.play"), "play", FlatButton.Style.PRIMARY, b -> {
					if (orbit().emoteController().play(emote.emote())) {
						onClose();
					} else {
						panelPreview.playEmote(emote.emote().asset(), emote.emote().file().particles());
					}
				})).active = emote.emote().asset() != null;
				emoteRow(x, y - 22, w, emote.emote(), FlatButton.Style.SECONDARY);
			}
			case CatalogEntry entry -> claimButton(x, y, w, List.of(entry.item()), entry.item().sha256(), entry.item().isEmote(), entry.item().plus(), false);
			case BundleEntry bundle -> claimButton(x, y, w, bundle.bundle().items(), bundle.bundle().id(), false, false, true);
			case SkinEntry skin -> {
				boolean worn = skin.skin().key().equals(orbit().state().skin());
				action(new FlatButton(x, y, w - 2 * (18 + 4), 18, Component.translatable(worn ? "orbit_cosmetic.skins.worn" : "orbit_cosmetic.skins.wear"), "check",
					FlatButton.Style.PRIMARY, b -> {
						orbit().skins().wearSkin(skin.skin());
						refreshActions();
					})).active = !worn;
				action(FlatButton.icon(x + w - 18 * 2 - 4, y, 18, "folder", Component.translatable("orbit_cosmetic.menu.show_file"), FlatButton.Style.SECONDARY,
					b -> Compat.openPath(orbit().skins().library().folder())));
				action(FlatButton.icon(x + w - 18, y, 18, "trash", Component.translatable("orbit_cosmetic.menu.delete"), FlatButton.Style.DANGER, b -> delete(skin.skin())));
			}
			case CapeEntry cape -> {
				String id = cape.cape() == null ? "" : cape.cape().id();
				boolean worn = id.equals(orbit().skins().cape());
				action(new FlatButton(x, y, w, 18, Component.translatable(worn ? "orbit_cosmetic.skins.worn" : "orbit_cosmetic.skins.wear"), "check",
					FlatButton.Style.PRIMARY, b -> {
						orbit().skins().wearCape(id);
						refreshActions();
					})).active = !worn;
			}
			case OutfitEntry outfit -> {
				action(new FlatButton(x, y, w - 18 - 4, 18, Component.translatable("orbit_cosmetic.outfits.wear"), "check", FlatButton.Style.PRIMARY, b -> wearOutfit(outfit.outfit())));
				action(FlatButton.icon(x + w - 18, y, 18, "trash", Component.translatable("orbit_cosmetic.menu.delete"), FlatButton.Style.DANGER, b -> deleteOutfit(outfit)));
				action(new FlatButton(x, y - 22, w, 18, Component.translatable("orbit_cosmetic.outfits.save"), "edit", FlatButton.Style.SECONDARY, b -> {
					saveLookInto(outfit.outfit());
					Notifications.success(Component.translatable("orbit_cosmetic.outfits.saved", outfit.outfit().name), null);
				})).setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.outfits.save.tooltip")));
				// The outfit's name, editable.
				outfitName = new EditBox(font, x + 1, y - 44, w - 2, 16, Component.translatable("orbit_cosmetic.outfits.name"));
				outfitName.setMaxLength(32);
				outfitName.setValue(outfit.outfit().name);
				outfitName.setResponder(text -> {
					outfit.outfit().name = text.isBlank() ? outfit.outfit().name : text.strip();
					orbit().state().save();
				});
				actions.add(addRenderableWidget(outfitName));
			}
			case null -> {
			}
		}
	}

	private int catalogStyle(CatalogItem item) {
		return catalogStyles.getOrDefault(item.sha256(), 0);
	}

	// ---------------------------------------------------------------- skins and outfits

	/** Saves the account's current skin (as Minecraft shows it) into Orbit/Skins. */
	private void saveAccountSkin() {
		MojangAccount.Profile profile = orbit().skins().account().profile();
		if (profile == null || profile.skinHash() == null) {
			orbit().skins().account().refresh();
			Notifications.warning(Component.translatable("orbit_cosmetic.skins.save_current.wait"), Component.translatable("orbit_cosmetic.skins.save_current.wait.detail"));
			return;
		}
		String hash = profile.skinHash();
		boolean slim = profile.slim();
		String name = profile.name().isEmpty() ? "skin" : profile.name();
		net.minecraft.util.Util.ioPool().execute(() -> {
			try {
				java.net.http.HttpResponse<byte[]> response = java.net.http.HttpClient.newHttpClient().send(
					java.net.http.HttpRequest.newBuilder(java.net.URI.create(dev.orbit.cosmetic.client.skin.SkinSignal.downloadUrl(hash))).build(),
					java.net.http.HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() != 200 || response.body().length > 256 * 1024) {
					throw new IOException("Mojang answered " + response.statusCode());
				}
				byte[] png = response.body();
				minecraft.execute(() -> {
					try {
						String key = orbit().skins().library().save(name, slim, png);
						// Mojang already has this one: wearing it again needs no upload.
						orbit().state().skinTextures().put(dev.orbit.cosmetic.common.util.Hashes.toHex(dev.orbit.cosmetic.common.util.Hashes.sha256(png)), hash);
						orbit().state().save();
						Notifications.success(Component.translatable("orbit_cosmetic.skins.saved"), Component.literal(key));
					} catch (IOException e) {
						Notifications.error(Component.translatable("orbit_cosmetic.skins.save_failed"), Component.literal(e.getMessage()));
					}
				});
			} catch (IOException e) {
				minecraft.execute(() -> Notifications.error(Component.translatable("orbit_cosmetic.skins.save_failed"), Component.literal(e.getMessage())));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private void delete(SkinLibrary.LocalSkin skin) {
		Compat.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed && orbit().skins().library().delete(skin)) {
				selected = null;
			}
			Compat.setScreen(this);
		}, Component.translatable("orbit_cosmetic.menu.delete.confirm", skin.name()), Component.translatable("orbit_cosmetic.skins.delete.text")));
	}

	/** A new outfit with what is worn now. */
	private void newOutfit() {
		List<OrbitState.Outfit> outfits = orbit().state().outfits();
		OrbitState.Outfit outfit = new OrbitState.Outfit(Component.translatable("orbit_cosmetic.outfits.default_name", outfits.size() + 1).getString());
		saveLookInto(outfit);
		outfits.add(outfit);
		orbit().state().save();
		rebuildGrid();
		select(new OutfitEntry(outfits.size() - 1, outfit));
	}

	/** Puts what is worn now (cosmetics and their textures, skin, cape) into an outfit. */
	private void saveLookInto(OrbitState.Outfit outfit) {
		outfit.equipped = new ArrayList<>();
		outfit.styles = new LinkedHashMap<>();
		for (LocalCosmetic entry : orbit().library().loaded()) {
			outfit.equipped.add(entry.key());
			if (entry.style() != 0) {
				outfit.styles.put(entry.key(), entry.style());
			}
		}
		outfit.skin = orbit().state().skin();
		outfit.cape = orbit().state().cape();
		orbit().state().save();
	}

	private void wearOutfit(OrbitState.Outfit outfit) {
		orbit().library().wear(outfit.equipped, outfit.styles);
		SkinLibrary.LocalSkin skin = orbit().skins().library().find(outfit.skin);
		if (skin != null && !skin.key().equals(orbit().state().skin())) {
			orbit().skins().wearSkin(skin);
		}
		if (outfit.cape != null && !outfit.cape.equals(orbit().state().cape())) {
			orbit().skins().wearCape(outfit.cape);
		}
		Notifications.success(Component.translatable("orbit_cosmetic.outfits.wearing", outfit.name), null);
		refreshActions();
	}

	private void deleteOutfit(OutfitEntry outfit) {
		Compat.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				orbit().state().outfits().remove(outfit.outfit());
				orbit().state().save();
				selected = null;
			}
			Compat.setScreen(this);
			rebuildGrid();
		}, Component.translatable("orbit_cosmetic.menu.delete.confirm", outfit.outfit().name), Component.translatable("orbit_cosmetic.outfits.delete.text")));
	}

	/** The emote's settings (particles, Standard or Mobile) and "Add to wheel". */
	private void emoteRow(int x, int y, int w, EmoteLibrary.LocalEmote emote, FlatButton.Style addStyle) {
		int half = (w - 4) / 2;
		action(new FlatButton(x, y, half, 18, Component.translatable("orbit_cosmetic.menu.emote.settings"), "settings", FlatButton.Style.SECONDARY,
			b -> Compat.setScreen(ParticlesScreen.forEmote(this, emote)))).setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.menu.emote.settings.tooltip")));
		action(new FlatButton(x + half + 4, y, w - half - 4, 18, Component.translatable("orbit_cosmetic.menu.emote.add"), null, addStyle, b -> {
			if (!orbit().emotes().addToWheel(orbit().emotes().selectedWheel(), emote)) {
				Notifications.warning(Component.translatable("orbit_cosmetic.menu.emote.wheel_full"), Component.translatable("orbit_cosmetic.menu.wheel.hint"));
			} else {
				Notifications.success(Component.translatable("orbit_cosmetic.menu.emote.added", emote.displayName()), null);
			}
		}));
	}

	/**
	 * The icon buttons for an emote's file, right-aligned so they end at {@code right}: "Make editable" for featured
	 * emotes, and the bin (removes a claimed featured emote, deletes one of the player's own). Returns the width
	 * they take, gap included.
	 */
	private int emoteFileButtons(int right, int y, EmoteLibrary.LocalEmote emote) {
		boolean featured = emote.file().isFeatured();
		int x = right - 18;
		action(FlatButton.icon(x, y, 18, "trash", Component.translatable(featured ? "orbit_cosmetic.menu.remove_featured" : "orbit_cosmetic.menu.delete"),
			FlatButton.Style.DANGER, b -> delete(emote)));
		if (!featured && emote.asset() != null) {
			x -= 22;
			action(FlatButton.icon(x, y, 18, "send", Component.translatable("orbit_cosmetic.social.share.button"), FlatButton.Style.SECONDARY,
				b -> ShareScreen.emote(this, emote)));
		}
		if (featured) {
			x -= 22;
			action(FlatButton.icon(x, y, 18, "edit", Component.translatable("orbit_cosmetic.menu.make_editable.emote"), FlatButton.Style.SECONDARY, b -> {
				try {
					orbit().emotes().makeEditable(emote.file());
					Notifications.success(Component.translatable("orbit_cosmetic.menu.made_editable", emote.displayName()), Component.translatable("orbit_cosmetic.menu.made_editable.emote_detail"));
				} catch (IOException e) {
					Notifications.error(Component.translatable("orbit_cosmetic.menu.make_editable.failed"), Component.literal(e.getMessage()));
				}
			}));
			FeaturedCheck check = check(emote.file());
			if (check.state() == FeaturedCheck.State.OUTDATED) {
				x -= 22;
				EmoteLibrary.EmoteFile file = emote.file();
				action(FlatButton.icon(x, y, 18, "update", Component.translatable("orbit_cosmetic.menu.update.tooltip"), FlatButton.Style.PRIMARY,
					b -> update(check.item(), emote.displayName(), (bytes, settings) -> orbit().emotes().updateFeatured(file, bytes, settings, check.item().gradient()))));
			}
		}
		return right - x + 4;
	}

	/** Whether a claimed copy is an older version of this catalog item. */
	private static boolean sameItem(FeaturedCheck check, CatalogItem item) {
		return check.state() == FeaturedCheck.State.OUTDATED && check.item().sha256().equals(item.sha256());
	}

	/** What the featured catalog says about a claimed featured cosmetic. */
	private FeaturedCheck check(LocalCosmetic entry) {
		return FeaturedCheck.of(orbit().backend().catalog(), entry.asset(), entry.displayName(), false);
	}

	private FeaturedCheck check(EmoteLibrary.EmoteFile file) {
		return FeaturedCheck.of(orbit().backend().catalog(), file.asset(), file.catalogName(), true);
	}

	private interface Install {
		void install(byte[] bytes, byte[] settings) throws IOException;
	}

	/** Downloads the catalog's version of a claimed item and puts it in place of the player's copy. */
	private void update(CatalogItem item, String name, Install install) {
		orbit().backend().downloadItem(item, (bytes, settings) -> {
			try {
				install.install(bytes, settings);
				Notifications.success(Component.translatable("orbit_cosmetic.menu.updated", name), Component.translatable("orbit_cosmetic.menu.updated.detail"));
			} catch (IOException e) {
				Notifications.error(Component.translatable("orbit_cosmetic.menu.update.failed", name), Component.literal(e.getMessage()));
			}
		}, error -> Notifications.error(Component.translatable("orbit_cosmetic.menu.update.failed", name), Component.literal(error)));
	}

	private FlatButton action(FlatButton button) {
		actions.add(addRenderableWidget(button));
		return button;
	}

	private void claimButton(int x, int y, int w, List<CatalogItem> items, String priceKey, boolean emote, boolean plus, boolean bundle) {
		boolean owned = items.stream().allMatch(this::owned);
		Component label;
		if (claiming) {
			label = Component.translatable(shop("orbit_cosmetic.menu.claiming"));
		} else if (owned) {
			label = Component.translatable(shop("orbit_cosmetic.menu.claimed"));
		} else if (JokeCoins.enabled()) {
			label = Component.translatable("orbit_cosmetic.menu.purchase", JokeCoins.format(JokeCoins.price(priceKey, emote, plus, bundle).coins()));
		} else {
			label = Component.translatable("orbit_cosmetic.menu.claim");
		}
		FlatButton claim = action(new FlatButton(x, y, w, 18, label, owned ? "check" : JokeCoins.enabled() ? "coin" : "plus", FlatButton.Style.PRIMARY, b -> claim(items, priceKey, emote, plus, bundle)));
		claim.active = !owned && !claiming;
		claim.setTooltip(Tooltip.create(Component.translatable(shop(bundle ? "orbit_cosmetic.menu.claim.bundle_tooltip" : "orbit_cosmetic.menu.claim.tooltip"))));
	}

	private boolean owned(CatalogItem item) {
		AssetId asset = AssetId.tryParse(item.sha256());
		if (asset == null) {
			return false;
		}
		// Only claimed featured items count: the player's own copies (made editable) are separate.
		return item.isEmote() ? orbit().emotes().findFeaturedFile(asset) != null : orbit().library().findFeatured(asset) != null;
	}

	/** Adds catalog items to the player's own catalog: cosmetics are equipped, emotes go on the wheel. */
	private void claim(List<CatalogItem> items, String priceKey, boolean emote, boolean plus, boolean bundle) {
		int price = JokeCoins.enabled() ? JokeCoins.price(priceKey, emote, plus, bundle).coins() : 0;
		if (JokeCoins.enabled() && !JokeCoins.spend(price)) {
			return;
		}
		// A purchase that fails gives its coins back, once.
		int[] refund = {price};
		Runnable refundOnce = () -> {
			if (refund[0] > 0) {
				JokeCoins.refund(refund[0]);
				refund[0] = 0;
				Notifications.warning(Component.translatable("orbit_cosmetic.shop.refunded"), Component.empty());
			}
		};
		List<CatalogItem> missing = items.stream().filter(item -> !owned(item)).toList();
		claiming = true;
		refreshActions();
		int[] remaining = {missing.size()};
		for (CatalogItem item : missing) {
			orbit().backend().downloadItem(item, (bytes, settings) -> {
				try {
					// A claimed copy of an older version is updated rather than claimed a second time.
					LocalCosmetic oldCosmetic = item.isEmote() ? null : orbit().library().entries().stream()
						.filter(entry -> entry.isFeatured() && sameItem(check(entry), item)).findFirst().orElse(null);
					EmoteLibrary.EmoteFile oldEmote = !item.isEmote() ? null : orbit().emotes().files().stream()
						.filter(file -> file.isFeatured() && sameItem(check(file), item)).findFirst().orElse(null);
					if (oldCosmetic != null) {
						orbit().library().updateFeatured(oldCosmetic, bytes, settings, item.gradient());
						orbit().library().setLoaded(oldCosmetic, true);
						orbit().library().setStyle(oldCosmetic, catalogStyle(item));
					} else if (oldEmote != null) {
						orbit().emotes().updateFeatured(oldEmote, bytes, settings, item.gradient());
					} else if (item.isEmote()) {
						String key = orbit().emotes().installFeatured(item.name(), bytes, settings, item.gradient());
						addToWheelLater(key);
					} else {
						orbit().library().installFeatured(item.name(), bytes, settings, item.gradient(), catalogStyle(item), item.isBodyPart());
					}
					Notifications.success(Component.translatable(shop("orbit_cosmetic.menu.claim.done"), item.name()), Component.translatable(shop(item.isEmote()
						? "orbit_cosmetic.menu.claim.done.emote" : "orbit_cosmetic.menu.claim.done.cosmetic")));
				} catch (IOException e) {
					Notifications.error(Component.translatable(shop("orbit_cosmetic.menu.claim.failed"), item.name()), Component.literal(e.getMessage()));
					refundOnce.run();
				}
				finishClaim(remaining);
			}, error -> {
				Notifications.error(Component.translatable(shop("orbit_cosmetic.menu.claim.failed"), item.name()), Component.literal(error));
				refundOnce.run();
				finishClaim(remaining);
			});
		}
		if (missing.isEmpty()) {
			finishClaim(remaining);
		}
	}

	/** With coin prices on, claiming reads like a shop: the "shop" version of a claim text. */
	private static String shop(String claimKey) {
		return JokeCoins.enabled() ? claimKey.replace("orbit_cosmetic.menu.claim", "orbit_cosmetic.shop.claim") : claimKey;
	}

	private void finishClaim(int[] remaining) {
		if (--remaining[0] <= 0) {
			claiming = false;
			refreshActions();
		}
	}

	/** Puts a claimed emote on the current wheel once its file has been read. */
	private void addToWheelLater(String key) {
		Runnable[] listener = new Runnable[1];
		listener[0] = () -> {
			EmoteLibrary.LocalEmote emote = orbit().emotes().find(key);
			if (emote != null) {
				orbit().emotes().removeListener(listener[0]);
				orbit().emotes().addToWheel(orbit().emotes().selectedWheel(), emote);
			}
		};
		orbit().emotes().addListener(listener[0]);
	}

	private void delete(LocalCosmetic entry) {
		Compat.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed && orbit().library().remove(entry)) {
				selected = null;
			}
			Compat.setScreen(this);
		}, Component.translatable("orbit_cosmetic.menu.delete.confirm", entry.displayName()),
			Component.translatable(entry.isFeatured() ? "orbit_cosmetic.menu.remove_featured.text" : "orbit_cosmetic.menu.delete.confirm.text")));
	}

	/** Removes a claimed featured emote, or deletes one of the player's own emotes. */
	private void delete(EmoteLibrary.LocalEmote emote) {
		EmoteLibrary.EmoteFile file = emote.file();
		Component text = Component.translatable(file.isFeatured() ? "orbit_cosmetic.menu.remove_featured.text" : "orbit_cosmetic.menu.delete.confirm.text");
		Compat.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed && orbit().emotes().remove(file)) {
				selected = null;
				selectedSlot = -1;
			}
			Compat.setScreen(this);
		}, Component.translatable("orbit_cosmetic.menu.delete.confirm", emote.displayName()), text));
	}

	// ---------------------------------------------------------------- the emote wheel editor

	private int wheelCell() {
		return Math.min(34, (rightW - PAD * 2 - 8) / 3);
	}

	private int wheelTop() {
		return panelY + panelH - PAD - 18 - 6 - wheelCell() * 3 - 4 - 16;
	}

	private void addWheelButtons(int x, int w) {
		int top = wheelTop();
		addWheelAction(FlatButton.icon(x, top, 16, "back", Component.translatable("orbit_cosmetic.wheel.previous"), FlatButton.Style.GHOST,
			b -> switchWheel(-1)));
		addWheelAction(FlatButton.icon(x + w - 16, top, 16, "forward", Component.translatable("orbit_cosmetic.wheel.next"), FlatButton.Style.GHOST,
			b -> switchWheel(1)));
		int half = (w - 4) / 2;
		int bottom = panelY + panelH - PAD - 18;
		addWheelAction(new FlatButton(x, bottom, half, 18, Component.translatable("orbit_cosmetic.menu.wheel.add"), "plus", FlatButton.Style.SECONDARY, b -> {
			if (orbit().emotes().addWheel()) {
				refreshActions();
			}
		})).active = orbit().emotes().wheels().size() < OrbitState.MAX_WHEELS;
		addWheelAction(new FlatButton(x + half + 4, bottom, w - half - 4, 18, Component.translatable("orbit_cosmetic.menu.wheel.remove"), "trash", FlatButton.Style.DANGER, b -> {
			orbit().emotes().removeWheel(orbit().emotes().selectedWheel());
			refreshActions();
		})).active = orbit().emotes().wheels().size() > 1;
	}

	private FlatButton addWheelAction(FlatButton button) {
		return action(button);
	}

	/**
	 * The wheel's slots for the keyboard (the mouse is handled by the screen): Enter puts the selected emote in the
	 * slot (or selects the slot's emote), Delete empties it.
	 */
	private void addWheelSlots() {
		int cell = wheelCell();
		int left = rightX + (rightW - cell * 3) / 2;
		int gridTop = wheelTop() + 20;
		for (int slot = 0; slot < OrbitState.WHEEL_SLOTS; slot++) {
			int[] cellPos = EmoteWheelScreen.cellOfSlot(slot);
			int index = slot;
			Hotspot spot = new Hotspot(Component.translatable("orbit_cosmetic.menu.wheel.slot", slot + 1), h -> {
				int wheel = orbit().emotes().selectedWheel();
				if (selected instanceof MyEmote emote) {
					orbit().emotes().setSlot(wheel, index, emote.emote());
				} else {
					EmoteLibrary.LocalEmote inSlot = orbit().emotes().slot(wheel, index);
					select(inSlot == null ? null : new MyEmote(inSlot));
				}
			}).keyboardOnly().onSecondary(h -> orbit().emotes().setSlot(orbit().emotes().selectedWheel(), index, null));
			spot.place(left + cellPos[0] * cell + 1, gridTop + cellPos[1] * cell + 1, cell - 2, cell - 2);
			actions.add(addRenderableWidget(spot));
		}
	}

	private void switchWheel(int delta) {
		orbit().emotes().selectWheel(orbit().emotes().selectedWheel() + delta);
		selectedSlot = -1;
		refreshActions();
	}

	/** The wheel's slot under the mouse (0-7, clockwise from the top), or -1. */
	private int slotAt(double mouseX, double mouseY) {
		int cell = wheelCell();
		int left = rightX + (rightW - cell * 3) / 2;
		int top = wheelTop() + 20;
		int column = (int) Math.floor((mouseX - left) / cell);
		int row = (int) Math.floor((mouseY - top) / cell);
		if (column < 0 || column > 2 || row < 0 || row > 2 || column == 1 && row == 1) {
			return -1;
		}
		return EmoteWheelScreen.slotOfCell(column, row);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mouseX = event.x();
		double mouseY = event.y();
		if (section == Section.EMOTES) {
			int slot = slotAt(mouseX, mouseY);
			if (slot >= 0) {
				int wheel = orbit().emotes().selectedWheel();
				if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
					orbit().emotes().setSlot(wheel, slot, null);
				} else if (selected instanceof MyEmote emote) {
					orbit().emotes().setSlot(wheel, slot, emote.emote());
				} else {
					EmoteLibrary.LocalEmote inSlot = orbit().emotes().slot(wheel, slot);
					select(inSlot == null ? null : new MyEmote(inSlot));
				}
				return true;
			}
		}
		if (Theme.inside(mouseX, mouseY, gridX, gridY, gridW + 4, gridH)) {
			Placed hit = tileAt(mouseX, mouseY);
			if (hit != null && hit.item() != null) {
				int style = styleAt(hit, mouseX, mouseY);
				if (style >= 0) {
					setStyle(hit.item(), style);
					select(hit.item());
					return true;
				}
			}
			if (hit != null && hit.item() != null) {
				select(hit.item());
				// The keyboard goes on from here.
				Hotspot spot = tileSpots.get(hit.item().key());
				if (spot != null) {
					setFocused(spot);
				}
				if (doubleClick && hit.item() instanceof MyCosmetic cosmetic && cosmetic.entry().isUsable()) {
					toggleEquipped(cosmetic.entry());
				}
				return true;
			}
			// The grid's background: nothing selected.
			select(null);
			return true;
		}
		if (Theme.inside(mouseX, mouseY, rightX, panelY + HEADER, rightW, previewBottom() - panelY - HEADER)) {
			draggingPreview = true;
			pressX = mouseX;
			pressY = mouseY;
			return true;
		}
		if (!Theme.inside(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
			// The background around the menu.
			select(null);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingPreview && Math.abs(event.x() - pressX) < 3 && Math.abs(event.y() - pressY) < 3) {
			// A click on a cosmetic of the big preview selects it.
			Item item = hoveredAsset == null ? null : itemFor(hoveredAsset);
			if (item != null) {
				select(item);
			}
		}
		draggingPreview = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingPreview) {
			panelYaw = (panelYaw - (float) dx * 2.5F) % 360;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (Theme.inside(mouseX, mouseY, gridX, gridY, gridW + 4, gridH)) {
			scroll = Math.clamp(scroll - scrollY * (cellH / 2.0), 0, Math.max(0, contentHeight - gridH));
			placeTileSpots();
			return true;
		}
		if (section == Section.EMOTES && Theme.inside(mouseX, mouseY, rightX, wheelTop(), rightW, panelY + panelH - wheelTop())) {
			switchWheel(scrollY > 0 ? -1 : 1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Ctrl+F searches; Enter or Down in the search goes to the first tile.
		if (event.hasControlDownWithQuirk() && Compat.isLetter(event, 'f')) {
			setFocused(searchBox);
			return true;
		}
		if (searchBox.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER || event.key() == InputConstants.KEY_DOWN)
			&& !tileSpots.isEmpty()) {
			setFocused(tileSpots.values().iterator().next());
			return true;
		}
		if (!searchBox.isFocused() && !(outfitName != null && outfitName.isFocused()) && orbit().openMenuKey().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		if (section == Section.EMOTES) {
			int copied = 0;
			for (Path file : files) {
				if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
					try {
						Files.copy(file, orbit().paths().emotes().resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
						copied++;
					} catch (IOException e) {
						Notifications.error(Component.translatable("orbit_cosmetic.menu.import.failed", file.getFileName().toString()), Component.literal(e.getMessage()));
					}
				}
			}
			orbit().emotes().reload();
			Notifications.info(Component.translatable("orbit_cosmetic.menu.import.emotes", copied), null);
			return;
		}
		List<String> report = orbit().library().importFiles(files);
		Notifications.info(Component.translatable("orbit_cosmetic.menu.import.result"), Component.literal(String.join("; ", report)));
	}

	private @Nullable Placed tileAt(double mouseX, double mouseY) {
		if (!Theme.inside(mouseX, mouseY, gridX, gridY, gridW, gridH)) {
			return null;
		}
		double x = mouseX - gridX;
		double y = mouseY - gridY + scroll;
		for (Placed p : placed) {
			if (p.item() != null && x >= p.x() && x < p.x() + p.w() && y >= p.y() && y < p.y() + p.h()) {
				return p;
			}
		}
		return null;
	}

	/** The alternate texture of a cosmetic tile whose colour button is under the mouse, or -1. */
	private int styleAt(Placed p, double mouseX, double mouseY) {
		BakedCosmetic baked = p.item() == null ? null : styledModel(p.item());
		int count = baked == null ? 1 : baked.styleCount();
		int x = gridX + p.x();
		int y = gridY + p.y() - (int) scroll;
		for (int style = 0; count > 1 && style < count; style++) {
			int[] button = styleButton(p, x, y, style, count);
			if (Theme.inside(mouseX, mouseY, button[0] - 1, button[1] - 1, button[2] + 2, button[2] + 2)) {
				return style;
			}
		}
		return -1;
	}

	/** The model whose alternate textures a tile offers: the player's cosmetics and catalog cosmetics; else null. */
	private @Nullable BakedCosmetic styledModel(Item item) {
		AssetId asset = switch (item) {
			case MyCosmetic cosmetic -> cosmetic.entry().asset();
			case CatalogEntry entry when !entry.item().isEmote() -> AssetId.tryParse(entry.item().sha256());
			default -> null;
		};
		return asset == null ? null : orbit().registry().get(asset);
	}

	/** The alternate texture a tile shows. */
	private int currentStyle(Item item) {
		return switch (item) {
			case MyCosmetic cosmetic -> cosmetic.entry().style();
			case CatalogEntry entry -> catalogStyle(entry.item());
			default -> 0;
		};
	}

	/** Picks an alternate texture: worn from now on, or for a catalog item, shown and kept when it is claimed. */
	private void setStyle(Item item, int style) {
		switch (item) {
			case MyCosmetic cosmetic -> orbit().library().setStyle(cosmetic.entry(), style);
			case CatalogEntry entry -> catalogStyles.put(entry.item().sha256(), style);
			default -> {
			}
		}
	}

	/** Where a style's colour button is on a tile: down the left edge, under the status square. x, y, size. */
	private static int[] styleButton(Placed p, int x, int y, int style, int count) {
		int size = 7;
		int pitch = Math.max(size + 1, Math.min(size + 3, (p.h() - 12 - 13) / count));
		return new int[] {x + 2, y + 13 + style * pitch, size};
	}

	private int previewBottom() {
		return section == Section.EMOTES ? wheelTop() - 26 : panelY + panelH - PAD - 18 - 22 - 34;
	}

	@Override
	public void tick() {
		// Keep the grid current while files are checked or downloaded.
		if (minecraftTick++ % 10 == 0) {
			refreshActionsIfStale();
		}
		// The capes are listed once Mojang has described the account.
		MojangAccount.Profile profile = orbit().skins().account().profile();
		if (section == Section.SKINS && profile != shownProfile) {
			shownProfile = profile;
			rebuildGrid();
		}
	}

	private MojangAccount.@Nullable Profile shownProfile;

	private int minecraftTick;
	private @Nullable String actionsFor;

	private void refreshActionsIfStale() {
		String state = selected == null ? "" : selected.key() + (selected instanceof MyCosmetic c ? ":" + c.entry().isLoaded() + c.entry().status()
			+ (c.entry().isFeatured() ? check(c.entry()).state() : "") : "")
			+ (selected instanceof MyEmote e && e.emote().file().isFeatured() ? ":" + check(e.emote().file()).state() : "")
			+ (selected instanceof CatalogEntry entry ? ":" + owned(entry.item()) : "") + (selected instanceof BundleEntry b ? ":" + b.bundle().items().stream().allMatch(this::owned) : "");
		if (!state.equals(actionsFor)) {
			actionsFor = state;
			refreshActions();
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		drawGrid(graphics, mouseX, mouseY, partialTick);
		drawRight(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		// The three columns.
		Theme.panel(graphics, panelX, panelY, sideW, panelH, Theme.PANEL);
		Theme.panel(graphics, midX - 1, panelY, midW + 1, panelH, Theme.BACKGROUND);
		Theme.grid(graphics, midX, panelY + HEADER, midX + midW - 1, panelY + panelH - 1);
		Theme.panel(graphics, rightX - 1, panelY, rightW + 1, panelH, Theme.PANEL);
		for (int x : new int[] {panelX, midX - 1, rightX - 1}) {
			int w = x == panelX ? sideW : x == midX - 1 ? midW + 1 : rightW + 1;
			graphics.fill(x + 1, panelY + 1, x + w - 1, panelY + HEADER, Theme.PANEL_2);
			graphics.fill(x + 1, panelY + HEADER, x + w - 1, panelY + HEADER + 1, Theme.BORDER);
		}
		// Sidebar: title, group label, player name.
		graphics.text(font, Component.translatable("orbit_cosmetic.menu.title"), panelX + 8, panelY + 7, Theme.TEXT, false);
		if (sideGap > 0) {
			int groupY = panelY + HEADER + 4 + (sideRow + 1) * Section.ALL.ordinal() + (sideGap - 8) / 2 + 1;
			graphics.text(font, Component.translatable("orbit_cosmetic.menu.group.wardrobe").withStyle(ChatFormatting.BOLD), panelX + 8, groupY, Theme.FAINT, false);
		}
		graphics.fill(panelX + 1, panelY + panelH - 24, panelX + sideW - 1, panelY + panelH - 23, Theme.BORDER);
		PlayerFaceExtractor.extractRenderState(graphics, orbit().menuSkin(), panelX + 7, panelY + panelH - 17, 10);
		graphics.text(font, Theme.clip(font, Component.literal(minecraft.getGameProfile().name()), sideW - 44), panelX + 20, panelY + panelH - 15, Theme.TEXT, false);
		// Middle header: section title and the search box frame.
		int boxX = searchBox.getX() - 12;
		// The section's name, cut short where the search field starts.
		graphics.text(font, Theme.clip(font, section.label(), boxX - 4 - (midX + PAD)), midX + PAD, panelY + 7, Theme.TEXT, false);
		Theme.panel(graphics, boxX, panelY + 3, searchBox.getWidth() + 14, 16, Theme.BACKGROUND);
		Theme.icon(graphics, "search", boxX + 3, panelY + 7, Theme.FAINT);
	}

	private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
		int top = gridY - (int) scroll;
		Placed hovered = tileAt(mouseX, mouseY);
		List<PreviewBatch.Entry> entries = new ArrayList<>();
		for (Placed p : placed) {
			int x = gridX + p.x();
			int y = top + p.y();
			if (y + p.h() < gridY || y > gridY + gridH) {
				continue;
			}
			if (p.separator() != null) {
				Theme.separator(graphics, font, x, y + 4, p.w(), p.separator());
				continue;
			}
			Item item = p.item();
			boolean isSelected = selected != null && selected.key().equals(item.key());
			graphics.fill(x, y, x + p.w(), y + p.h(), p == hovered ? Theme.PANEL_HOVER : Theme.PANEL_2);
			int[] colors = tileColors(item);
			if (colors != null) {
				graphics.fillGradient(x + 1, y + 1, x + p.w() - 1, y + p.h() - 1, colors[0], colors[1]);
			}
			graphics.outline(x, y, p.w(), p.h(), isSelected ? Theme.RED : p == hovered ? Theme.BORDER_LIGHT : Theme.BORDER);
			Theme.bevel(graphics, x + 1, y + 1, p.w() - 2, p.h() - 2, 1);
			float yaw = p == hovered ? (float) (orbit().menuTime() * 6 % 360) : 25;
			if (item instanceof CapeEntry && p != hovered) {
				yaw += 180; // capes are seen from behind
			}
			if (item instanceof BundleEntry) {
				// A bundle: the player wearing all of it on the left, the list of items on the right.
				entries.add(preview(item).entryIn(x - gridX + 3, y - gridY + 14, bundleSplit(p) - 6, p.h() - 30, yaw, 0, partialTick));
			} else {
				// Everything it wears fits in the tile, above the name.
				entries.add(preview(item).entryIn(x - gridX + 3, y - gridY + 4, p.w() - 6, p.h() - 18, yaw, 0, partialTick));
			}
		}
		PreviewBatch.grid(graphics, gridX, gridY, gridX + gridW, gridY + gridH, entries);
		placeTileSpots();
		for (Placed p : placed) {
			int y = top + p.y();
			if (p.item() == null || y + p.h() < gridY || y > gridY + gridH) {
				continue;
			}
			drawTileOverlay(graphics, p, gridX + p.x(), y, mouseX, mouseY);
			Hotspot spot = tileSpots.get(p.item().key());
			if (spot != null) {
				spot.drawFocus(graphics);
			}
		}
		graphics.disableScissor();
		drawScrollbar(graphics);
		if (placed.isEmpty()) {
			drawEmpty(graphics);
		}
	}

	/**
	 * The mark of a claimed featured item: a gold star while it is featured, a blue arrow when the catalog has another
	 * version of it, a grey star once Orbit Cloud no longer features it. Returns the mark's tooltip when the mouse is on it.
	 */
	private @Nullable List<FormattedCharSequence> featuredMark(GuiGraphicsExtractor graphics, FeaturedCheck check, int x, int y, int mouseX, int mouseY) {
		boolean outdated = check.state() == FeaturedCheck.State.OUTDATED;
		boolean unfeatured = check.state() == FeaturedCheck.State.UNFEATURED;
		// Grey: Orbit Cloud does not feature it any more.
		Theme.icon(graphics, outdated ? "update" : "star", x, y, outdated ? Theme.BLUE : unfeatured ? Theme.FAINT : Theme.GOLD);
		if (Theme.inside(mouseX, mouseY, x - 1, y - 1, 11, 11) && (outdated || unfeatured)) {
			return List.of(Component.translatable(outdated ? "orbit_cosmetic.menu.update.available" : "orbit_cosmetic.menu.featured.unfeatured").getVisualOrderText());
		}
		return null;
	}

	/** A tile's top and bottom colours: from its settings file or the catalog; bundles are red. Null for none. */
	private static int @Nullable [] tileColors(Item item) {
		return switch (item) {
			case MyCosmetic cosmetic -> cosmetic.entry().gradient();
			case MyEmote emote -> emote.emote().file().gradient();
			case CatalogEntry entry -> entry.item().tileColors() != null ? entry.item().tileColors() : entry.item().plus() ? RED_TILE : null;
			case BundleEntry bundle -> RED_TILE;
			case SkinEntry skin -> null;
			case CapeEntry cape -> null;
			case OutfitEntry outfit -> null;
		};
	}

	/** Where a bundle tile's player ends and its list of items begins, from the tile's left edge. */
	private static int bundleSplit(Placed p) {
		return Math.min(p.w() / 2, p.h() + 20);
	}

	private void drawTileOverlay(GuiGraphicsExtractor graphics, Placed p, int x, int y, int mouseX, int mouseY) {
		Item item = p.item();
		if (item instanceof BundleEntry bundle) {
			drawBundle(graphics, p, bundle, x, y);
			return;
		}
		graphics.fill(x + 1, y + p.h() - 12, x + p.w() - 1, y + p.h() - 1, 0xC0141416);
		graphics.text(font, Theme.clip(font, Component.literal(item.name()), p.w() - 6), x + 3, y + p.h() - 10, Theme.TEXT, false);
		List<FormattedCharSequence> tooltip = null;
		switch (item) {
			case MyCosmetic cosmetic -> {
				CosmeticStatus status = orbit().cosmeticStatus(cosmetic.entry());
				graphics.fill(x + 3, y + 3, x + 8, y + 8, status.kind().color);
				if (Theme.inside(mouseX, mouseY, x + 1, y + 1, 9, 9)) {
					tooltip = List.of(status.kind().label().getVisualOrderText(), status.detail().copy().withStyle(ChatFormatting.GRAY).getVisualOrderText());
				}
				if (cosmetic.entry().isLoaded()) {
					Theme.icon(graphics, "check", x + p.w() - 11, y + 2, Theme.GREEN);
				}
				if (cosmetic.entry().isFeatured()) {
					List<FormattedCharSequence> tip = featuredMark(graphics, check(cosmetic.entry()), x + p.w() - (cosmetic.entry().isLoaded() ? 21 : 11), y + 2, mouseX, mouseY);
					tooltip = tip != null ? tip : tooltip;
				}
			}
			case MyEmote emote -> {
				boolean wheel = onWheel(emote.emote());
				// The emote icon shows the emote's state, like a cosmetic's square.
				CosmeticStatus status = CosmeticStatus.ofEmote(emote.emote(), wheel, orbit().backend());
				Theme.icon(graphics, "emote", x + 2, y + 2, status.kind().color);
				if (Theme.inside(mouseX, mouseY, x + 1, y + 1, 11, 11)) {
					tooltip = List.of(status.kind().emoteLabel().getVisualOrderText(), status.detail().copy().withStyle(ChatFormatting.GRAY).getVisualOrderText());
				}
				if (wheel) {
					Theme.icon(graphics, "wheel", x + p.w() - 11, y + 2, Theme.MUTED);
				}
				if (emote.emote().file().isFeatured()) {
					List<FormattedCharSequence> tip = featuredMark(graphics, check(emote.emote().file()), x + p.w() - (wheel ? 21 : 11), y + 2, mouseX, mouseY);
					tooltip = tip != null ? tip : tooltip;
				}
				if (orbit().emotes().mode(emote.emote()) == EmoteMode.MOBILE) {
					graphics.text(font, Component.translatable("orbit_cosmetic.menu.tag.mobile"), x + 13, y + 3, Theme.MUTED, false);
				}
			}
			case CatalogEntry entry -> {
				if (entry.item().isEmote()) {
					Theme.icon(graphics, "emote", x + 2, y + 2, Theme.BLUE);
				}
				if (owned(entry.item())) {
					Theme.icon(graphics, "check", x + p.w() - 11, y + 2, Theme.GREEN);
				}
				drawPrice(graphics, x, y + p.h() - 22, entry.item().sha256(), entry.item().isEmote(), entry.item().plus(), false);
			}
			case BundleEntry bundle -> {
			}
			case SkinEntry skin -> {
				if (skin.skin().key().equals(orbit().state().skin())) {
					Theme.icon(graphics, "check", x + p.w() - 11, y + 2, Theme.GREEN);
				}
				if (skin.skin().slim()) {
					graphics.text(font, Component.translatable("orbit_cosmetic.skins.slim"), x + 3, y + 3, Theme.MUTED, false);
				}
			}
			case CapeEntry cape -> {
				if ((cape.cape() == null ? "" : cape.cape().id()).equals(orbit().skins().cape())) {
					Theme.icon(graphics, "check", x + p.w() - 11, y + 2, Theme.GREEN);
				}
			}
			case OutfitEntry outfit -> {
				Theme.icon(graphics, "outfit", x + 2, y + 2, Theme.MUTED);
				// Things of the outfit this computer does not have (restored from Orbit Cloud).
				List<String> missing = dev.orbit.cosmetic.client.social.CloudWardrobe.missing(outfit.outfit());
				if (!missing.isEmpty()) {
					Theme.icon(graphics, "close", x + p.w() - 11, y + 2, Theme.RED);
					if (Theme.inside(mouseX, mouseY, x + p.w() - 12, y + 1, 11, 11)) {
						tooltip = List.of(Component.translatable("orbit_cosmetic.outfits.missing", String.join(", ", missing)).getVisualOrderText());
					}
				}
			}
		}
		// Alternate textures: a colour button each, while the tile is hovered or selected.
		BakedCosmetic styled = styledModel(item);
		int styles = styled == null ? 1 : styled.styleCount();
		boolean shown = Theme.inside(mouseX, mouseY, x, y, p.w(), p.h()) || selected != null && selected.key().equals(item.key());
		if (styles > 1 && shown) {
			for (int style = 0; style < styles; style++) {
				int[] button = styleButton(p, x, y, style, styles);
				boolean current = style == Math.min(currentStyle(item), styles - 1);
				boolean over = Theme.inside(mouseX, mouseY, button[0] - 1, button[1] - 1, button[2] + 2, button[2] + 2);
				graphics.fill(button[0] - 1, button[1] - 1, button[0] + button[2] + 1, button[1] + button[2] + 1, current || over ? Theme.TEXT : 0xFF0A0A0D);
				graphics.fill(button[0], button[1], button[0] + button[2], button[1] + button[2], styled.styleColor(style));
				if (over) {
					tooltip = List.of(Component.translatable("orbit_cosmetic.menu.style", style + 1, styles).getVisualOrderText());
				}
			}
		}
		if (tooltip != null) {
			graphics.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
		}
	}

	/** A bundle tile: its name, item count and price over the player, and the list of its items on the right. */
	private void drawBundle(GuiGraphicsExtractor graphics, Placed p, BundleEntry bundle, int x, int y) {
		int split = bundleSplit(p);
		Theme.icon(graphics, "bundle", x + 3, y + 3, Theme.RED);
		graphics.text(font, Theme.clip(font, Component.literal(bundle.name()), split - 18), x + 15, y + 4, Theme.TEXT, false);
		drawPrice(graphics, x, y + p.h() - 13, bundle.bundle().id(), false, false, true);
		graphics.fill(x + split, y + 6, x + split + 1, y + p.h() - 6, Theme.BORDER);
		int listX = x + split + 8;
		int listW = p.w() - split - 14;
		graphics.text(font, Component.translatable("orbit_cosmetic.menu.tag.bundle", bundle.bundle().items().size()), listX, y + 6, Theme.MUTED, false);
		List<CatalogItem> items = bundle.bundle().items();
		int rows = Math.max(1, (p.h() - 24) / 12);
		int lineY = y + 20;
		for (int i = 0; i < items.size(); i++) {
			if (i == rows - 1 && items.size() > rows) {
				graphics.text(font, Component.translatable("orbit_cosmetic.menu.bundle.more", items.size() - i), listX, lineY, Theme.FAINT, false);
				break;
			}
			CatalogItem item = items.get(i);
			Theme.icon(graphics, item.isEmote() ? "emote" : "shirt", listX, lineY - 1, item.isEmote() ? Theme.BLUE : Theme.MUTED);
			boolean owned = owned(item);
			graphics.text(font, Theme.clip(font, Component.literal(item.name()), listW - (owned ? 26 : 14)), listX + 12, lineY, Theme.TEXT, false);
			if (owned) {
				Theme.icon(graphics, "check", listX + listW - 10, lineY - 1, Theme.GREEN);
			}
			lineY += 12;
		}
	}

	private void drawPrice(GuiGraphicsExtractor graphics, int x, int y, String key, boolean emote, boolean plus, boolean bundle) {
		if (!JokeCoins.enabled()) {
			return;
		}
		JokeCoins.Price price = JokeCoins.price(key, emote, plus, bundle);
		Theme.icon(graphics, "coin", x + 3, y, Theme.YELLOW);
		String text = JokeCoins.format(price.coins());
		graphics.text(font, text, x + 14, y + 1, Theme.YELLOW, true);
		if (price.was() > 0) {
			String was = JokeCoins.format(price.was());
			int wasX = x + 18 + font.width(text);
			graphics.text(font, was, wasX, y + 1, Theme.FAINT, false);
			graphics.fill(wasX - 1, y + 4, wasX + font.width(was), y + 5, Theme.FAINT);
		}
	}

	private boolean onWheel(EmoteLibrary.LocalEmote emote) {
		for (OrbitState.Wheel wheel : orbit().emotes().wheels()) {
			for (String slot : wheel.slots) {
				if (emote.key().equals(slot)) {
					return true;
				}
			}
		}
		return false;
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics) {
		if (contentHeight <= gridH) {
			return;
		}
		int x = gridX + gridW + 2;
		graphics.fill(x, gridY, x + 2, gridY + gridH, Theme.PANEL_2);
		int thumb = Math.max(16, gridH * gridH / contentHeight);
		int thumbY = gridY + (int) ((gridH - thumb) * scroll / (contentHeight - gridH));
		graphics.fill(x, thumbY, x + 2, thumbY + thumb, Theme.BORDER_LIGHT);
	}

	/**
	 * Where a skin or cape stands: not worn; worn but the account cannot be changed; on its way to the account; and
	 * whether other Orbit players see it ({@code shared}: Mojang has the texture).
	 */
	private Component skinStatus(boolean worn, boolean shared, boolean pending, int seconds) {
		var account = orbit().skins().account();
		if (!worn) {
			return Component.translatable("orbit_cosmetic.skins.status.not_worn").withStyle(ChatFormatting.GRAY);
		}
		if (account.isUnavailable()) {
			return Component.translatable("orbit_cosmetic.skins.status.offline").withStyle(ChatFormatting.YELLOW);
		}
		if (pending) {
			return Component.translatable(shared ? "orbit_cosmetic.skins.status.pending_shared" : "orbit_cosmetic.skins.status.pending", seconds)
				.withStyle(ChatFormatting.GRAY);
		}
		if (account.problem() != null) {
			return Component.translatable("orbit_cosmetic.skins.problem." + dev.orbit.cosmetic.client.skin.OrbitSkins.problemKey(account.problem())).withStyle(ChatFormatting.YELLOW);
		}
		return Component.translatable(shared ? "orbit_cosmetic.skins.status.shared" : "orbit_cosmetic.skins.status.waiting").withStyle(ChatFormatting.GRAY);
	}

	private void drawEmpty(GuiGraphicsExtractor graphics) {
		Component message;
		if (section == Section.FEATURED) {
			message = catalogError != null ? Component.translatable("orbit_cosmetic.menu.featured.error", catalogError)
				: !search.isEmpty() ? Component.translatable("orbit_cosmetic.menu.empty.search")
				: Component.translatable("orbit_cosmetic.menu.featured.empty");
		} else if (!search.isEmpty()) {
			message = Component.translatable("orbit_cosmetic.menu.empty.search");
		} else if (orbit().library().isScanning() || orbit().emotes().isScanning()) {
			message = Component.translatable("orbit_cosmetic.menu.loading");
		} else {
			message = Component.translatable("orbit_cosmetic.menu.empty." + switch (section) {
				case ALL, USER -> "user";
				case EMOTES -> "emotes";
				case OUTFITS -> "outfits";
				default -> "section";
			});
		}
		int y = gridY + 20;
		for (FormattedCharSequence line : font.split(message, gridW - 20)) {
			graphics.centeredText(font, line, gridX + gridW / 2, y, Theme.MUTED);
			y += 10;
		}
	}

	private void drawRight(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Component title = selected == null ? Component.translatable("orbit_cosmetic.menu.preview") : Component.literal(selected.name());
		graphics.text(font, Theme.clip(font, title, rightW - PAD * 2), rightX + PAD, panelY + 7, Theme.TEXT, false);
		updatePanelPreview();
		int previewTop = panelY + HEADER + 2;
		int bottom = previewBottom();
		PreviewBatch.panel(graphics, rightX, previewTop, rightX + rightW - 1, bottom,
			List.of(panelPreview.entryIn(4, 4, rightW - 9, bottom - previewTop - 8, panelYaw, 0, partialTick)));
		hoveredAsset = section != Section.EMOTES && Theme.inside(mouseX, mouseY, rightX, previewTop, rightW - 1, bottom - previewTop)
			? pickAt(mouseX - rightX, mouseY - previewTop) : null;
		// The Featured Catalog shows the item itself on the player: no tint or outline marks it as selected.
		AssetId selectedAsset = section == Section.FEATURED ? null : selectedAsset();
		panelPreview.setHighlight(hoveredAsset, selectedAsset);
		outline(graphics, rightX, previewTop, rightW - 1, bottom - previewTop, selectedAsset, hoveredAsset);
		drawDetails(graphics, bottom);
		if (section == Section.EMOTES) {
			drawWheel(graphics, mouseX, mouseY, partialTick);
		}
	}

	/** One or two lines about the selection, above its buttons. */
	private void drawDetails(GuiGraphicsExtractor graphics, int y) {
		if (section == Section.EMOTES || selected instanceof OutfitEntry) {
			// No room: the wheel (the mode button's tooltip explains Standard and Mobile), or the outfit's name.
			return;
		}
		Component line = switch (selected) {
			case SkinEntry skin -> skinStatus(skin.skin().key().equals(orbit().state().skin()), orbit().skins().mojangTexture(skin.skin()) != null,
				orbit().skins().account().isSkinPending(), orbit().skins().account().skinWaitingSeconds());
			case CapeEntry cape -> skinStatus((cape.cape() == null ? "" : cape.cape().id()).equals(orbit().skins().cape()), true,
				orbit().skins().account().isCapePending(), orbit().skins().account().capeWaitingSeconds());
			case OutfitEntry outfit -> Component.empty();
			case MyCosmetic cosmetic -> {
				LocalCosmetic entry = cosmetic.entry();
				Issue problem = entry.issues().stream().filter(Issue::isError).findFirst().orElse(entry.issues().stream().findFirst().orElse(null));
				if (problem != null) {
					yield Component.literal(CosmeticLibrary.readable(problem.message())).withStyle(problem.isError() ? ChatFormatting.RED : ChatFormatting.YELLOW);
				}
				FeaturedCheck.State featured = entry.isFeatured() ? check(entry).state() : null;
				if (featured == FeaturedCheck.State.OUTDATED) {
					yield Component.translatable("orbit_cosmetic.menu.featured.outdated").withStyle(ChatFormatting.YELLOW);
				}
				if (featured == FeaturedCheck.State.UNFEATURED) {
					yield Component.translatable("orbit_cosmetic.menu.featured.unfeatured").withStyle(ChatFormatting.GRAY);
				}
				yield orbit().cosmeticStatus(entry).detail().copy().withStyle(ChatFormatting.GRAY);
			}
			case MyEmote emote -> Component.translatable(orbit().emotes().mode(emote.emote()) == EmoteMode.MOBILE
				? "orbit_cosmetic.menu.emote.mobile_hint" : "orbit_cosmetic.menu.emote.standard_hint").withStyle(ChatFormatting.GRAY);
			case CatalogEntry entry -> (entry.item().author().isEmpty() ? Component.literal(entry.item().description())
				: Component.translatable("orbit_cosmetic.menu.by", entry.item().author())).copy().withStyle(ChatFormatting.GRAY);
			case BundleEntry bundle -> Component.translatable("orbit_cosmetic.menu.bundle.contents",
				String.join(", ", bundle.bundle().items().stream().map(CatalogItem::name).toList())).withStyle(ChatFormatting.GRAY);
			case null -> section == Section.EMOTES ? Component.empty() : Component.translatable("orbit_cosmetic.menu.select").withStyle(ChatFormatting.GRAY);
		};
		List<FormattedCharSequence> lines = font.split(line, rightW - PAD * 2);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			graphics.text(font, lines.get(i), rightX + PAD, y + 2 + i * 9, Theme.MUTED, false);
		}
	}

	private void drawWheel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int top = wheelTop();
		int wheel = orbit().emotes().selectedWheel();
		Component name = Component.literal(orbit().emotes().wheels().get(wheel).name);
		graphics.centeredText(font, name, rightX + rightW / 2, top + 4, Theme.TEXT);
		int cell = wheelCell();
		int left = rightX + (rightW - cell * 3) / 2;
		int gridTop = top + 20;
		int hovered = slotAt(mouseX, mouseY);
		List<PreviewBatch.Entry> entries = new ArrayList<>();
		for (int slot = 0; slot < OrbitState.WHEEL_SLOTS; slot++) {
			int[] cellPos = EmoteWheelScreen.cellOfSlot(slot);
			int x = left + cellPos[0] * cell;
			int y = gridTop + cellPos[1] * cell;
			EmoteLibrary.LocalEmote emote = orbit().emotes().slot(wheel, slot);
			graphics.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, slot == hovered ? Theme.PANEL_HOVER : Theme.PANEL_2);
			graphics.outline(x + 1, y + 1, cell - 2, cell - 2, emote != null && selected instanceof MyEmote mine && mine.emote().key().equals(emote.key()) ? Theme.RED : Theme.BORDER);
			Theme.bevel(graphics, x + 2, y + 2, cell - 4, cell - 4, 1);
			if (emote != null) {
				PreviewModel model = previews.computeIfAbsent("w:" + emote.key(), key -> new PreviewModel());
				loopEmote(model, emote);
				entries.add(model.entryIn(x - rightX + 3, y - gridTop + 3, cell - 6, cell - 6, 20, 0, partialTick));
			} else {
				Theme.icon(graphics, "plus", x + (cell - 9) / 2, y + (cell - 9) / 2, Theme.FAINT);
			}
			if (slot == hovered) {
				Component tip = emote != null ? Component.literal(emote.displayName()) : Component.translatable("orbit_cosmetic.menu.wheel.slot.empty");
				graphics.setTooltipForNextFrame(font, List.of(tip.getVisualOrderText(), Component.translatable("orbit_cosmetic.menu.wheel.hint").withStyle(ChatFormatting.GRAY).getVisualOrderText()), mouseX, mouseY);
			}
		}
		Theme.icon(graphics, "wheel", left + cell + (cell - 9) / 2, gridTop + cell + (cell - 9) / 2, Theme.FAINT);
		for (net.minecraft.client.gui.components.AbstractWidget widget : actions) {
			if (widget instanceof Hotspot spot) {
				spot.drawFocus(graphics);
			}
		}
		PreviewBatch.extra(graphics, rightX, gridTop, rightX + rightW - 1, gridTop + cell * 3, entries);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
