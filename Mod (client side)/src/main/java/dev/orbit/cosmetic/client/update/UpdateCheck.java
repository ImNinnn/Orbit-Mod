package dev.orbit.cosmetic.client.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.Updates;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Before Minecraft loads, asks Orbit Cloud whether there is a newer Orbit for this Minecraft version. A newer
 * version this one still works with is offered in the game ({@link Updates}). One Orbit Cloud requires makes
 * Minecraft wait while {@link UpdateWindow} (in a process of its own) asks whether to update: with no answer within
 * 10 seconds (unless the player stopped the countdown), or "Not now", loading goes on; after a successful update
 * Minecraft closes, to be started again with the new version. Any problem (no connection, no answer from Orbit
 * Cloud) just lets the game load.
 *
 * <p>Only Fabric Loader, Gson and the JDK are used here: Minecraft's classes are not loaded yet.
 */
public final class UpdateCheck implements PreLaunchEntrypoint {
	private static final Logger LOGGER = LoggerFactory.getLogger("Orbit");
	/** What the update window needs besides the JDK: itself, the release key, the icons, and Minecraft's font. */
	private static final List<String> WINDOW_FILES = List.of("dev/orbit/cosmetic/client/update/UpdateWindow.class", "orbit-release.pub", "assets/orbit_cosmetic/icon.png",
		"assets/orbit_cosmetic/textures/gui/sprites/icon/orbit.png", "assets/orbit_cosmetic/textures/gui/sprites/icon/close.png",
		"assets/orbit_cosmetic/textures/gui/sprites/icon/update.png", "assets/orbit_cosmetic/textures/gui/sprites/icon/check.png",
		"assets/minecraft/textures/font/ascii.png");

	@Override
	public void onPreLaunch() {
		try {
			check();
		} catch (Exception e) {
			LOGGER.warn("Could not check for a new version of Orbit: {}", e.toString());
		}
	}

	/** A newer version Orbit Cloud has: the update window's arguments for it. */
	private record Found(String version, boolean required, List<String> arguments) {
	}

	private void check() throws IOException, InterruptedException {
		FabricLoader loader = FabricLoader.getInstance();
		// Development builds are not updated, unless a test asks (-Dorbit.updateCheck=force).
		if (loader.isDevelopmentEnvironment() && !"force".equals(System.getProperty("orbit.updateCheck"))) {
			return;
		}
		ModContainer mod = loader.getModContainer("orbit_cosmetic").orElseThrow();
		String version = mod.getMetadata().getVersion().getFriendlyString();
		var targetValue = mod.getMetadata().getCustomValue("orbit_cosmetic:target");
		String target = targetValue == null ? null : targetValue.getAsString();
		if (target == null) {
			return;
		}
		// While the game runs: asked again every 10 minutes, and whatever is found is offered in the game.
		Updates.checker(() -> {
			try {
				Found again = query(loader, mod, version, target);
				if (again != null) {
					Updates.offer(again.version(), result -> installFromGame(again.arguments(), result));
					return again.version();
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.debug("Could not check for a new version of Orbit: {}", e.toString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		});
		Found found = query(loader, mod, version, target);
		if (found == null) {
			return;
		}
		String newVersion = found.version();
		List<String> arguments = found.arguments();
		if (!found.required()) {
			// Not needed to play: offered in the game (Settings, and a prompt on the title screen).
			LOGGER.info("Orbit {} is available (this is {}); offered in the game", newVersion, version);
			Updates.offer(newVersion, result -> installFromGame(arguments, result));
			return;
		}
		LOGGER.info("Orbit {} is needed to play with Orbit Cloud (this is {}); asking whether to update", newVersion, version);
		BlockingQueue<String> lines = startWindow(arguments, List.of());

		// 10 seconds to answer, plus the time the window takes to open; once the player stopped the countdown
		// (WAIT), as long as they take.
		String answer = lines.poll(25, TimeUnit.SECONDS);
		if ("WAIT".equals(answer)) {
			LOGGER.info("Waiting for the answer in the update window");
			answer = lines.take();
		}
		if (!"YES".equals(answer)) {
			LOGGER.info("Not updating now; loading Orbit {}", version);
			return;
		}
		String result = lines.poll(5, TimeUnit.MINUTES);
		if (result != null && result.equals("UPDATED")) {
			LOGGER.info("Orbit {} is installed; closing Minecraft so it starts with the new version", newVersion);
			System.exit(0);
		}
		LOGGER.warn("The update to Orbit {} failed ({}); loading {}", newVersion, result, version);
	}

	/**
	 * Asks Orbit Cloud for a newer version (when the player allows it: terms accepted, Orbit Cloud and update checks
	 * on, read from the config file each time); null when there is none.
	 */
	private static Found query(FabricLoader loader, ModContainer mod, String version, String target) throws IOException, InterruptedException {
		JsonObject config = readConfig(loader.getConfigDir().resolve("orbit_cosmetic.json"));
		// Nothing goes to Orbit Cloud before its terms are accepted (in game, on the first start).
		boolean termsAccepted = config != null && config.get("termsAccepted") instanceof JsonElement accepted && accepted.isJsonPrimitive()
			&& dev.orbit.cosmetic.client.net.OrbitCloud.TERMS_VERSION.equals(accepted.getAsString());
		if (!termsAccepted || !bool(config, "checkUpdates", true) || !bool(config, "useBackend", true)) {
			return null;
		}
		String baseUrl = dev.orbit.cosmetic.client.net.OrbitCloud.url();

		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
		URI uri = URI.create(baseUrl + "/v1/mod/update?target=" + URLEncoder.encode(target, StandardCharsets.UTF_8) + "&version="
			+ URLEncoder.encode(version, StandardCharsets.UTF_8));
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).header("Accept", "application/json")
			.header("User-Agent", "OrbitCosmetic/" + version + " (+https://orbitcosmetics.dev)").GET().build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			return null;
		}
		JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonElement updateElement = body.get("update");
		if (updateElement == null || !updateElement.isJsonObject()) {
			return null;
		}
		JsonObject update = updateElement.getAsJsonObject();
		String newVersion = text(update, "version", "");
		String url = text(update, "url", "");
		String sha256 = text(update, "sha256", "");
		String signature = text(update, "signature", "");
		long size = update.has("size") ? update.get("size").getAsLong() : 0;
		if (!UpdateWindow.isNewer(newVersion, version) || !url.startsWith("/v1/mod/") || !sha256.matches("[0-9a-f]{64}") || !signature.matches("[A-Za-z0-9+/=]{20,200}")) {
			return null;
		}
		boolean required = body.has("required") && body.get("required").getAsBoolean();

		Path jar = ownJar(mod);
		if (jar == null && System.getProperty("orbit.updateOldJar") != null) {
			// Development test: a stand-in for the installed jar.
			jar = Path.of(System.getProperty("orbit.updateOldJar")).toAbsolutePath();
		}
		Path mods = jar != null ? jar.getParent() : Path.of(System.getProperty("orbit.updateMods", loader.getGameDir().resolve("mods").toString()));
		List<String> arguments = List.of(version, newVersion, String.valueOf(required), baseUrl + url, sha256, String.valueOf(size), signature, target, mods.toString(),
			jar == null ? "-" : jar.toString(), String.valueOf(ProcessHandle.current().pid()));
		return new Found(newVersion, required, arguments);
	}

	/**
	 * Installs from the game (the player chose Update): the update window runs as when Minecraft starts, already
	 * answered, and removes the old jar once Minecraft has closed. {@code result} gets UPDATED or FAILED.
	 */
	private static void installFromGame(List<String> arguments, java.util.function.Consumer<String> result) {
		Thread thread = new Thread(() -> {
			try {
				BlockingQueue<String> lines = startWindow(arguments, List.of("-Dorbit.updateAnswer=yes", "-Dorbit.updateFromGame=true"));
				while (true) {
					String line = lines.poll(10, TimeUnit.MINUTES);
					if (line == null || line.equals("END")) {
						result.accept("FAILED the update window closed");
						return;
					}
					if (line.equals("UPDATED") || line.startsWith("FAILED")) {
						result.accept(line);
						return;
					}
				}
			} catch (IOException e) {
				result.accept("FAILED " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "Orbit update");
		thread.setDaemon(true);
		thread.start();
	}

	/** Starts the update window (a process of its own) and returns its lines as they come, then END. */
	private static BlockingQueue<String> startWindow(List<String> arguments, List<String> options) throws IOException {
		Path window = extractWindow();
		Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase().contains("win") ? "javaw.exe" : "java");
		List<String> command = new java.util.ArrayList<>(List.of(java.toString(), "-Djava.awt.headless=false"));
		for (String testOption : List.of("orbit.updateAnswer", "orbit.updateShot")) {
			if (System.getProperty(testOption) != null && options.stream().noneMatch(option -> option.startsWith("-D" + testOption + "="))) {
				command.add("-D" + testOption + "=" + System.getProperty(testOption));
			}
		}
		command.addAll(options);
		command.addAll(List.of("-cp", window.toString(), UpdateWindow.class.getName()));
		command.addAll(arguments);
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		Thread reader = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					lines.add(line);
				}
			} catch (IOException ignored) {
				// the window closed
			}
			lines.add("END");
		}, "Orbit update window");
		reader.setDaemon(true);
		reader.start();
		return lines;
	}

	/** The jar this mod was loaded from, or null when it was not a jar (development). */
	private static Path ownJar(ModContainer mod) {
		List<Path> paths = mod.getOrigin().getPaths();
		if (paths.size() == 1 && Files.isRegularFile(paths.getFirst()) && paths.getFirst().toString().endsWith(".jar")) {
			return paths.getFirst().toAbsolutePath();
		}
		return null;
	}

	/**
	 * Copies the update window out of the mod into a temporary folder and runs it from there: running it from the
	 * mod's jar would keep that jar open, so it could not be replaced on Windows.
	 */
	private static Path extractWindow() throws IOException {
		Path folder = Files.createTempDirectory("orbit-update");
		ClassLoader loader = UpdateCheck.class.getClassLoader();
		for (String name : WINDOW_FILES) {
			try (InputStream in = loader.getResourceAsStream(name)) {
				if (in == null) {
					continue;
				}
				Path file = folder.resolve(name);
				Files.createDirectories(file.getParent());
				Files.write(file, in.readAllBytes());
			}
		}
		folder.toFile().deleteOnExit();
		return folder;
	}

	private static JsonObject readConfig(Path file) {
		try {
			JsonElement element = JsonParser.parseString(Files.readString(file));
			return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	private static boolean bool(JsonObject json, String key, boolean fallback) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
	}

	private static String text(JsonObject json, String key, String fallback) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : fallback;
	}
}
