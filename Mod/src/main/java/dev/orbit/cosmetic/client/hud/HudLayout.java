package dev.orbit.cosmetic.client.hud;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.OrbitClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The HUD components the player placed, kept in {@code config/orbit_hud.json}. */
public final class HudLayout {
	private static final List<HudComponent> COMPONENTS = new ArrayList<>();
	private static boolean loaded;

	private HudLayout() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("orbit_hud.json");
	}

	public static List<HudComponent> components() {
		if (!loaded) {
			loaded = true;
			load();
		}
		return COMPONENTS;
	}

	public static HudComponent add(HudComponent.Type type, int screenWidth, int screenHeight) {
		HudComponent component = new HudComponent(UUID.randomUUID(), type);
		// In the middle, a little apart from the last one added.
		int offset = components().size() % 6 * 10;
		component.place((screenWidth - component.scaledWidth()) / 2 + offset, (screenHeight - component.scaledHeight()) / 2 + offset, screenWidth, screenHeight);
		COMPONENTS.add(component);
		save();
		return component;
	}

	public static void remove(HudComponent component) {
		COMPONENTS.remove(component);
		save();
	}

	public static void save() {
		JsonArray array = new JsonArray();
		components().forEach(component -> array.add(component.toJson()));
		JsonObject root = new JsonObject();
		root.add("components", array);
		try {
			Path temp = file().resolveSibling("orbit_hud.json.tmp");
			Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save the HUD components: {}", e.toString());
		}
	}

	private static void load() {
		if (!Files.isRegularFile(file())) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
			if (root instanceof JsonObject object && object.get("components") instanceof JsonArray array) {
				for (JsonElement element : array) {
					if (element instanceof JsonObject json && COMPONENTS.size() < 64) {
						HudComponent component = HudComponent.fromJson(json);
						if (component != null) {
							COMPONENTS.add(component);
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.warn("Could not read the HUD components: {}", e.toString());
		}
	}
}
