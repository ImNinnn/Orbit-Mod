package dev.orbit.cosmetic.client.gui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.host.HostScreen;
import dev.orbit.cosmetic.client.hud.HudComponent;
import dev.orbit.cosmetic.client.hud.HudEditorScreen;
import dev.orbit.cosmetic.client.hud.HudLayout;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.skin.SkinLibrary;
import dev.orbit.cosmetic.client.social.SocialScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * What the quick menu's nine squares do (a 3 by 3 grid, read row by row), set by the player in
 * {@link QuickMenuEditScreen} and kept in {@code config/orbit_quick_menu.json}. The first time, it is Orbit's usual
 * cross: Host, Social, Wardrobe, Settings and Players, and HUD components in a corner.
 */
public final class QuickMenuLayout {
	public static final int CELLS = 9;
	public static final int CENTER = 4;

	/** What a square can do. Some take an argument: the outfit's name, the skin's or emote's key, the component's ID. */
	public enum Kind {
		EMPTY(""),
		WARDROBE("orbit"),
		SOCIAL("social"),
		HOST("host"),
		SETTINGS("settings"),
		SCREENSHOTS("image"),
		HUD_EDITOR("hud"),
		PLAYERS("player"),
		STATS("account"),
		TOGGLE_COSMETICS("shirt"),
		TOGGLE_HUD("hud"),
		PERSPECTIVE("rotate"),
		HIDE_GUI("grid"),
		OUTFIT("outfit"),
		SKIN("skin"),
		EMOTE("emote"),
		TOGGLE_COMPONENT("hud"),
		HUD_PROFILE("folder");

		public final String icon;

		Kind(String icon) {
			this.icon = icon;
		}

		/** Whether it opens a screen (others act and close the quick menu). */
		boolean opensScreen() {
			return ordinal() >= WARDROBE.ordinal() && ordinal() <= STATS.ordinal();
		}
	}

	/** One square: what it does, and its argument ("" when it takes none). */
	public record Action(Kind kind, String arg) {
		public static final Action NONE = new Action(Kind.EMPTY, "");

		public static Action of(Kind kind) {
			return new Action(kind, "");
		}

		/** Its name in the quick menu: the outfit's, skin's or emote's own name for those. */
		public Component label() {
			OrbitClient orbit = OrbitClient.get();
			return switch (kind) {
				case OUTFIT -> Component.literal(arg);
				case SKIN -> {
					SkinLibrary.LocalSkin skin = orbit.skins().library().find(arg);
					yield skin != null ? Component.literal(skin.name()) : Component.translatable("orbit_cosmetic.quick.missing");
				}
				case EMOTE -> {
					EmoteLibrary.LocalEmote emote = orbit.emotes().find(arg);
					yield emote != null ? Component.literal(emote.displayName()) : Component.translatable("orbit_cosmetic.quick.missing");
				}
				case TOGGLE_COMPONENT -> {
					HudComponent component = component();
					yield component != null ? Component.translatable("orbit_cosmetic.quick.action.toggle_component.named", component.type.label())
						: Component.translatable("orbit_cosmetic.quick.missing");
				}
				case SOCIAL -> Component.translatable("orbit_cosmetic.quick.message");
				case HUD_PROFILE -> profile() != null ? Component.literal(arg) : Component.translatable("orbit_cosmetic.quick.missing");
				default -> Component.translatable("orbit_cosmetic.quick.action." + kind.name().toLowerCase(Locale.ROOT));
			};
		}

		/** What kind of action it is, under its name in the editor ("Outfit", "Emote"...). */
		public Component kindLabel() {
			return Component.translatable("orbit_cosmetic.quick.kind." + kind.name().toLowerCase(Locale.ROOT));
		}

		private @Nullable HudComponent component() {
			try {
				return HudLayout.find(UUID.fromString(arg));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		/** Whether it can be used now (Players needs a server, an emote a world, a missing outfit nothing). */
		public boolean available() {
			OrbitClient orbit = OrbitClient.get();
			Minecraft minecraft = Minecraft.getInstance();
			return switch (kind) {
				case EMPTY -> false;
				case PLAYERS -> PlayersScreen.available();
				case OUTFIT -> outfit() != null;
				case SKIN -> orbit.skins().library().find(arg) != null;
				case EMOTE -> minecraft.player != null && orbit.emotes().find(arg) != null;
				case TOGGLE_COMPONENT -> component() != null;
				case HUD_PROFILE -> profile() != null;
				case PERSPECTIVE, HIDE_GUI -> minecraft.level != null;
				default -> true;
			};
		}

		private HudLayout.@Nullable Profile profile() {
			for (HudLayout.Profile profile : HudLayout.profiles()) {
				if (profile.name.equals(arg)) {
					return profile;
				}
			}
			return null;
		}

		private OrbitState.@Nullable Outfit outfit() {
			for (OrbitState.Outfit outfit : OrbitClient.get().state().outfits()) {
				if (outfit.name.equals(arg)) {
					return outfit;
				}
			}
			return null;
		}

		/** Does it: opens its screen, or closes the quick menu and acts. */
		public void run() {
			if (!available()) {
				return;
			}
			if (kind.opensScreen()) {
				Compat.setScreen(switch (kind) {
					case WARDROBE -> new OrbitMenuScreen(null);
					case SOCIAL -> new SocialScreen(null);
					case HOST -> new HostScreen(null);
					case SETTINGS -> new OrbitSettingsScreen(null);
					case SCREENSHOTS -> new ScreenshotsScreen(null);
					case HUD_EDITOR -> new HudEditorScreen(null);
					case PLAYERS -> new PlayersScreen(null);
					default -> new StatsScreen(null);
				});
				return;
			}
			Compat.setScreen(null);
			OrbitClient orbit = OrbitClient.get();
			Minecraft minecraft = Minecraft.getInstance();
			switch (kind) {
				case TOGGLE_COSMETICS -> {
					orbit.config().renderCosmetics = !orbit.config().renderCosmetics;
					orbit.config().save();
					Notifications.info(Component.translatable(orbit.config().renderCosmetics ? "orbit_cosmetic.notify.cosmetics_shown" : "orbit_cosmetic.notify.cosmetics_hidden"),
						Component.empty());
				}
				case TOGGLE_HUD -> {
					HudLayout.setAllHidden(!HudLayout.allHidden());
					Notifications.info(Component.translatable(HudLayout.allHidden() ? "orbit_cosmetic.notify.hud_hidden" : "orbit_cosmetic.notify.hud_shown"), Component.empty());
				}
				case TOGGLE_COMPONENT -> {
					HudComponent component = component();
					if (component != null) {
						component.hidden = !component.hidden;
						HudLayout.save();
					}
				}
				case PERSPECTIVE -> minecraft.options.setCameraType(minecraft.options.getCameraType().cycle());
				case HIDE_GUI -> Compat.toggleHud();
				case OUTFIT -> wear(outfit());
				case HUD_PROFILE -> {
					HudLayout.Profile profile = profile();
					if (profile != null) {
						HudLayout.switchTo(profile);
						Notifications.info(Component.translatable("orbit_cosmetic.notify.hud_profile", profile.name), Component.empty());
					}
				}
				case SKIN -> {
					SkinLibrary.LocalSkin skin = orbit.skins().library().find(arg);
					if (skin != null) {
						orbit.skins().wearSkin(skin);
						Notifications.success(Component.translatable("orbit_cosmetic.quick.wearing_skin", skin.name()), null);
					}
				}
				case EMOTE -> {
					EmoteLibrary.LocalEmote emote = orbit.emotes().find(arg);
					if (emote != null) {
						orbit.emoteController().play(emote);
					}
				}
				default -> {
				}
			}
		}

		/** Puts an outfit on, as the wardrobe's Wear does: its cosmetics, then its skin and cape if it has them. */
		private static void wear(OrbitState.@Nullable Outfit outfit) {
			if (outfit == null) {
				return;
			}
			OrbitClient orbit = OrbitClient.get();
			orbit.library().wear(outfit.equipped, outfit.styles);
			SkinLibrary.LocalSkin skin = orbit.skins().library().find(outfit.skin);
			if (skin != null && !skin.key().equals(orbit.state().skin())) {
				orbit.skins().wearSkin(skin);
			}
			if (outfit.cape != null && !outfit.cape.equals(orbit.state().cape())) {
				orbit.skins().wearCape(outfit.cape);
			}
			Notifications.success(Component.translatable("orbit_cosmetic.outfits.wearing", outfit.name), null);
		}
	}

	private static final Action[] ACTIONS = new Action[CELLS];
	private static boolean loaded;

	private QuickMenuLayout() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("orbit_quick_menu.json");
	}

	/** The square at this index (row by row). */
	public static Action get(int cell) {
		load();
		return ACTIONS[cell];
	}

	public static void set(int cell, Action action) {
		load();
		ACTIONS[cell] = action;
		save();
	}

	/** A HUD profile got a new name: the squares that switch to it follow. */
	public static void profileRenamed(String old, String name) {
		load();
		boolean changed = false;
		for (int i = 0; i < CELLS; i++) {
			if (ACTIONS[i].kind() == Kind.HUD_PROFILE && ACTIONS[i].arg().equals(old)) {
				ACTIONS[i] = new Action(Kind.HUD_PROFILE, name);
				changed = true;
			}
		}
		if (changed) {
			save();
		}
	}

	/** Orbit's usual layout. */
	public static void reset() {
		java.util.Arrays.fill(ACTIONS, Action.NONE);
		ACTIONS[1] = Action.of(Kind.HOST);
		ACTIONS[2] = Action.of(Kind.HUD_EDITOR);
		ACTIONS[3] = Action.of(Kind.SOCIAL);
		ACTIONS[CENTER] = Action.of(Kind.WARDROBE);
		ACTIONS[5] = Action.of(Kind.SETTINGS);
		ACTIONS[7] = Action.of(Kind.PLAYERS);
		loaded = true;
		save();
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		java.util.Arrays.fill(ACTIONS, Action.NONE);
		if (!Files.isRegularFile(file())) {
			reset();
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
			if (root instanceof JsonObject object && object.get("cells") instanceof JsonArray cells) {
				for (int i = 0; i < Math.min(CELLS, cells.size()); i++) {
					if (cells.get(i) instanceof JsonObject cell) {
						try {
							ACTIONS[i] = new Action(Kind.valueOf(cell.get("kind").getAsString()), cell.has("arg") ? cell.get("arg").getAsString() : "");
						} catch (RuntimeException e) {
							ACTIONS[i] = Action.NONE;
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.warn("Could not read the quick menu: {}", e.toString());
		}
	}

	private static void save() {
		JsonArray cells = new JsonArray();
		for (Action action : ACTIONS) {
			JsonObject cell = new JsonObject();
			cell.addProperty("kind", action.kind().name());
			if (!action.arg().isEmpty()) {
				cell.addProperty("arg", action.arg());
			}
			cells.add(cell);
		}
		JsonObject root = new JsonObject();
		root.add("cells", cells);
		try {
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save the quick menu: {}", e.toString());
		}
	}
}
