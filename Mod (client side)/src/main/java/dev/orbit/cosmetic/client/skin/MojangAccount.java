package dev.orbit.cosmetic.client.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.OrbitClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The player's Minecraft account skin and capes, through Mojang's profile API ({@code api.minecraftservices.com}),
 * with the game's own access token, which goes nowhere else. A change is sent at once; after it, that kind of
 * request (skin, cape, profile) waits a minute before the next one. Changes made during that minute wait for it to
 * end, and only the latest of them is sent. Mojang checks every skin it is given; a skin it refuses is never shared.
 */
public final class MojangAccount {
	private static final long REQUEST_EVERY_MS = 60_000;
	private static final int MAX_RESPONSE_BYTES = 256 * 1024;
	private static final String API = "https://api.minecraftservices.com";

	/** A cape the account owns. {@code hash} is its texture on textures.minecraft.net. */
	public record Cape(String id, String alias, String hash, boolean active) {
	}

	/** The account as Mojang last described it. */
	public record Profile(String name, String skinHash, boolean slim, List<Cape> capes) {
		public Cape activeCape() {
			return capes.stream().filter(Cape::active).findFirst().orElse(null);
		}

		public Cape cape(String id) {
			return capes.stream().filter(cape -> cape.id().equals(id)).findFirst().orElse(null);
		}
	}

	/** A skin to give the account: a file Mojang has not seen, or a texture it already hosts. */
	private record SkinChange(String localSha, byte[] png, String hash, boolean slim) {
	}

	/** The kinds of requests, each with its own limit of one a minute. */
	private enum Kind {
		SKIN,
		CAPE,
		PROFILE
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String api;
	private final Consumer<Runnable> onGameThread;
	/** Called on the game thread after Mojang accepted a skin file: its hash, and the texture Mojang made of it. */
	private final SkinAccepted accepted;
	private final Runnable changed;

	private SkinChange pendingSkin;
	/** The cape to wear: an ID, "" for none, or null for no change. */
	private String pendingCape;
	private boolean pendingProfile;
	/** When each kind of request may be sent again. */
	private final long[] nextAt = new long[Kind.values().length];
	private volatile Profile profile;
	private volatile String problem;
	private volatile boolean unavailable;
	private Thread worker;

	public interface SkinAccepted {
		void accepted(String localSha, String hash);
	}

	public MojangAccount(Consumer<Runnable> onGameThread, SkinAccepted accepted, Runnable changed) {
		this.onGameThread = onGameThread;
		this.accepted = accepted;
		this.changed = changed;
		// Tests point this at a local fake of Mojang's API; only in a development environment.
		String override = System.getProperty("orbit.mojangApi");
		this.api = override != null && FabricLoader.getInstance().isDevelopmentEnvironment() ? override.replaceAll("/+$", "") : API;
	}

	/** The account as last fetched, or null before that. */
	public Profile profile() {
		return profile;
	}

	/** Why the last request failed (shown in the menu), or null. */
	public String problem() {
		return problem;
	}

	/** True when the game's account cannot use Mojang's API (offline or demo accounts): changes stay in Orbit. */
	public boolean isUnavailable() {
		return unavailable;
	}

	/** Seconds until the waiting skin is sent, else 0. */
	public synchronized int skinWaitingSeconds() {
		return pendingSkin == null ? 0 : secondsUntil(Kind.SKIN);
	}

	/** Seconds until the waiting cape is sent, else 0. */
	public synchronized int capeWaitingSeconds() {
		return pendingCape == null ? 0 : secondsUntil(Kind.CAPE);
	}

	private int secondsUntil(Kind kind) {
		return (int) Math.max(0, (nextAt[kind.ordinal()] - System.currentTimeMillis() + 999) / 1000);
	}

	public synchronized boolean isSkinPending() {
		return pendingSkin != null;
	}

	public synchronized boolean isCapePending() {
		return pendingCape != null;
	}

	/** Asks for the account's skin and capes (once a minute at most). */
	public synchronized void refresh() {
		pendingProfile = true;
		wake();
	}

	/** Gives the account a skin. {@code hash} is Mojang's texture of it when known, so the file need not be sent. */
	public synchronized void setSkin(SkinLibrary.LocalSkin skin, String hash) {
		pendingSkin = new SkinChange(skin.sha256(), skin.bytes(), hash, skin.slim());
		wake();
	}

	/** Wears a cape: its ID, or "" for none. */
	public synchronized void setCape(String capeId) {
		pendingCape = capeId == null ? "" : capeId;
		wake();
	}

	private void wake() {
		if (worker == null) {
			worker = new Thread(this::run, "Orbit Mojang account");
			worker.setDaemon(true);
			worker.start();
		}
		notifyAll();
	}

	private boolean isPending(Kind kind) {
		return switch (kind) {
			case SKIN -> pendingSkin != null;
			case CAPE -> pendingCape != null;
			case PROFILE -> pendingProfile;
		};
	}

	private void run() {
		while (true) {
			Runnable request;
			synchronized (this) {
				Kind kind;
				try {
					while (true) {
						// The first waiting request whose minute is over (skins first); otherwise wait for the soonest.
						long now = System.currentTimeMillis();
						long wait = Long.MAX_VALUE;
						kind = null;
						for (Kind candidate : Kind.values()) {
							if (isPending(candidate)) {
								long until = nextAt[candidate.ordinal()] - now;
								if (until <= 0) {
									kind = candidate;
									break;
								}
								wait = Math.min(wait, until);
							}
						}
						if (kind != null) {
							break;
						}
						if (wait == Long.MAX_VALUE) {
							wait();
						} else {
							wait(wait);
						}
					}
				} catch (InterruptedException e) {
					return;
				}
				nextAt[kind.ordinal()] = System.currentTimeMillis() + REQUEST_EVERY_MS;
				switch (kind) {
					case SKIN -> {
						SkinChange change = pendingSkin;
						pendingSkin = null;
						request = () -> {
							sendSkin(change);
							retryLater(() -> {
								if (pendingSkin == null) {
									pendingSkin = change;
								}
							});
						};
					}
					case CAPE -> {
						String cape = pendingCape;
						pendingCape = null;
						request = () -> {
							sendCape(cape);
							retryLater(() -> {
								if (pendingCape == null) {
									pendingCape = cape;
								}
							});
						};
					}
					default -> {
						pendingProfile = false;
						request = () -> call("GET", "/minecraft/profile", null, null);
					}
				}
			}
			request.run();
			onGameThread.accept(changed);
		}
	}

	/** After a request Mojang could not take (busy, or unreachable), puts it back to try in a minute. */
	private synchronized void retryLater(Runnable requeue) {
		if ("rate_limited".equals(problem) || "unreachable".equals(problem)) {
			requeue.run();
		}
	}

	private void sendSkin(SkinChange change) {
		Profile current = profile;
		if (change.hash() != null && current != null && change.hash().equals(current.skinHash()) && change.slim() == current.slim()) {
			return; // the account already has it
		}
		Profile updated;
		if (change.hash() != null) {
			JsonObject body = new JsonObject();
			body.addProperty("variant", change.slim() ? "slim" : "classic");
			body.addProperty("url", SkinSignal.url(change.hash()));
			updated = call("POST", "/minecraft/profile/skins", "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
		} else {
			String boundary = "OrbitSkin" + UUID.randomUUID().toString().replace("-", "");
			updated = call("POST", "/minecraft/profile/skins", "multipart/form-data; boundary=" + boundary, multipart(boundary, change));
		}
		if (updated != null && updated.skinHash() != null) {
			String hash = updated.skinHash();
			onGameThread.accept(() -> accepted.accepted(change.localSha(), hash));
		}
	}

	private void sendCape(String capeId) {
		if (capeId.isEmpty()) {
			call("DELETE", "/minecraft/profile/capes/active", null, null);
		} else {
			JsonObject body = new JsonObject();
			body.addProperty("capeId", capeId);
			call("PUT", "/minecraft/profile/capes/active", "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private static byte[] multipart(String boundary, SkinChange change) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + (change.slim() ? "slim" : "classic") + "\r\n"
			+ "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n";
		out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
		out.writeBytes(change.png());
		out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	/** One request to Mojang's profile API. Returns the profile it answers with (and keeps it), or null. Worker thread. */
	private Profile call(String method, String path, String contentType, byte[] body) {
		String token = Minecraft.getInstance().getUser().getAccessToken();
		if (token == null || token.isBlank() || token.length() < 20) {
			unavailable = true;
			problem = "offline";
			return null;
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api + path))
			.timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + token)
			.header("Accept", "application/json");
		if (contentType != null) {
			builder.header("Content-Type", contentType);
		}
		builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
		try {
			HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
			byte[] answer = response.body();
			if (answer.length > MAX_RESPONSE_BYTES) {
				problem = "too_large";
				return null;
			}
			int status = response.statusCode();
			if (status == 401 || status == 403) {
				unavailable = true;
				problem = "offline";
				return null;
			}
			if (status == 429) {
				problem = "rate_limited";
				return null;
			}
			if (status == 400) {
				// Mojang refuses skins that break its rules (and files it cannot use).
				problem = "refused";
				return null;
			}
			if (status < 200 || status >= 300) {
				problem = "status " + status;
				return null;
			}
			problem = null;
			unavailable = false;
			if (answer.length == 0) {
				return null;
			}
			Profile parsed = parse(JsonParser.parseString(new String(answer, StandardCharsets.UTF_8)));
			if (parsed != null) {
				profile = parsed;
			}
			return parsed;
		} catch (IOException | RuntimeException e) {
			problem = "unreachable";
			OrbitClient.LOGGER.debug("Mojang profile request failed: {}", e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static Profile parse(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonObject root = element.getAsJsonObject();
		String skinHash = null;
		boolean slim = false;
		for (JsonObject skin : objects(root.get("skins"))) {
			if ("ACTIVE".equals(text(skin, "state"))) {
				skinHash = SkinSignal.hashOf(text(skin, "url"));
				slim = "SLIM".equalsIgnoreCase(text(skin, "variant"));
			}
		}
		List<Cape> capes = new ArrayList<>();
		for (JsonObject cape : objects(root.get("capes"))) {
			String id = text(cape, "id");
			String hash = SkinSignal.hashOf(text(cape, "url"));
			if (id != null && id.length() <= 64 && hash != null) {
				String alias = text(cape, "alias");
				capes.add(new Cape(id, alias == null ? "Cape" : alias.length() > 32 ? alias.substring(0, 32) : alias, hash,
					"ACTIVE".equals(text(cape, "state"))));
			}
		}
		String name = text(root, "name");
		return new Profile(name == null ? "" : name, skinHash, slim, List.copyOf(capes));
	}

	private static List<JsonObject> objects(JsonElement element) {
		List<JsonObject> result = new ArrayList<>();
		if (element != null && element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			for (JsonElement item : array) {
				if (item.isJsonObject() && result.size() < 64) {
					result.add(item.getAsJsonObject());
				}
			}
		}
		return result;
	}

	private static String text(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString().toLowerCase(Locale.ROOT).equals("null") ? null : element.getAsString() : null;
	}
}
