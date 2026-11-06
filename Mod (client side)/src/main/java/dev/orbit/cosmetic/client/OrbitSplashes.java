package dev.orbit.cosmetic.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Orbit's own splash texts, set by the Orbit team (o!splash): downloaded from Orbit Cloud and kept in
 * {@code Orbit/.cache/splashes.json}, so the first title screen of a start has them too. Minecraft's splash picker
 * gives one of them, in red, one time in five (see SplashManagerMixin): wherever a splash is picked, also by mods that
 * cycle the splash text.
 */
public final class OrbitSplashes {
	private static final Random RANDOM = new Random();
	private static final int COLOR = 0xFF5555;
	private static volatile List<String> texts = List.of();
	private static boolean loaded;

	private OrbitSplashes() {
	}

	private static @Nullable Path file() {
		OrbitClient orbit = OrbitClient.get();
		return orbit == null || orbit.paths() == null ? null : orbit.paths().cache().resolve("splashes.json");
	}

	/** One of Orbit's splash texts one time in five (null otherwise, or without any). */
	public static @Nullable SplashRenderer pick() {
		if (!loaded) {
			loaded = true;
			load();
		}
		List<String> current = texts;
		if (current.isEmpty() || RANDOM.nextInt(5) != 0) {
			return null;
		}
		return new SplashRenderer(Component.literal(current.get(RANDOM.nextInt(current.size()))).withColor(COLOR));
	}

	/** The texts Orbit Cloud has now (any thread): used at once, and kept for the next start. */
	public static void set(List<String> found) {
		texts = List.copyOf(found);
		loaded = true;
		Path file = file();
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, new Gson().toJson(found), StandardCharsets.UTF_8);
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Could not keep the splash texts: {}", e.toString());
		}
	}

	private static void load() {
		Path file = file();
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			List<String> found = new ArrayList<>();
			if (json instanceof JsonArray list) {
				for (JsonElement element : list) {
					if (element.isJsonPrimitive() && found.size() < 30) {
						String text = element.getAsString();
						found.add(text.length() > 60 ? text.substring(0, 60) : text);
					}
				}
			}
			texts = List.copyOf(found);
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.debug("Could not read the kept splash texts: {}", e.toString());
		}
	}
}
