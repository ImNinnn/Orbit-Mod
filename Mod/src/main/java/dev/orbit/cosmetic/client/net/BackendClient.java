package dev.orbit.cosmetic.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.emote.EmoteSignal;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.protocol.ParticleCodec;
import dev.orbit.cosmetic.common.protocol.ProtocolException;
import dev.orbit.cosmetic.client.library.TileColors;
import dev.orbit.cosmetic.client.skin.SkinSignal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Blocking calls to Orbit Cloud. Call from a background thread only.
 * Responses are untrusted: sizes are capped and every value is checked by the caller.
 */
final class BackendClient {
	private static final Gson GSON = new Gson();
	private static final int MAX_JSON_BYTES = 512 * 1024;
	private static final int MAX_CATALOG_BYTES = 4 * 1024 * 1024;
	/** Orbit Cloud sends the catalog in pages of up to 500 items; 20 pages are far more than it will ever hold. */
	private static final int CATALOG_PAGE = 500;
	private static final int MAX_CATALOG_PAGES = 20;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * A player's presence as stored by the backend.
	 *
	 * @param emote the emote being played, or null
	 * @param emoteAge seconds since the backend first saw that emote start
	 */
	record PresenceEntry(UUID player, String name, int format, String equip, EmoteSignal emote, double emoteAge, SkinSignal skin) {
	}

	/**
	 * A signed-in player session.
	 *
	 * @param fullBan a complete ban (nothing shared) rather than a feature ban (catalog items only)
	 * @param bannedUntil 0 for permanent bans
	 */
	record Session(String token, UUID player, String name, boolean banned, boolean fullBan, double bannedUntil) {
	}

	static final class BackendException extends IOException {
		final int status;
		/** The backend's error code, such as "blocked" or "too_large"; empty if the body had none. */
		final String code;
		/** The backend's human-readable message, or a generic one. */
		final String detail;
		/** For "blocked": when the ban ends (Unix seconds), 0 if permanent or unknown. */
		final double banExpires;
		/** For "blocked": a complete ban rather than a feature ban. */
		final boolean fullBan;
		/** For "cosmetic_blocked": the SHA-256 of the items moderators blocked. */
		final List<String> blockedItems;

		BackendException(int status, String code, String detail) {
			this(status, code, detail, 0, false, List.of());
		}

		BackendException(int status, String code, String detail, double banExpires, boolean fullBan, List<String> blockedItems) {
			super(detail.isEmpty() ? "HTTP " + status : detail);
			this.status = status;
			this.code = code;
			this.detail = detail;
			this.banExpires = banExpires;
			this.fullBan = fullBan;
			this.blockedItems = blockedItems;
		}
	}

	private final String baseUrl;
	private final String userAgent;
	private final String modVersion;
	private volatile String minimumClient;
	/** Bearer token for writes when the backend requires sign-in; null otherwise. */
	private volatile String token;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

	/** @throws IllegalArgumentException if the URL is not an http(s) URL */
	BackendClient(String baseUrl, String modVersion) {
		this.userAgent = "OrbitCosmetic/" + modVersion + " (+https://orbitcosmetics.dev)";
		this.modVersion = modVersion;
		String trimmed = baseUrl == null ? "" : baseUrl.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		URI uri = URI.create(trimmed);
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
			throw new IllegalArgumentException("The backend URL must start with http:// or https://");
		}
		this.baseUrl = trimmed;
	}

	String baseUrl() {
		return baseUrl;
	}

	/** The oldest Orbit Cosmetic version Orbit Cloud accepted at the last {@link #health()} check, or null. */
	String minimumClient() {
		return minimumClient;
	}

	/** @return the backend's authentication scheme (Orbit Cloud: "mojang"), or null if it is not healthy */
	String health() throws IOException, InterruptedException {
		JsonObject body = json(send(request("/v1/health").GET(), MAX_JSON_BYTES));
		if (!"ok".equals(text(body, "status"))) {
			return null;
		}
		minimumClient = text(body, "minimum_client");
		String auth = text(body, "auth");
		return auth == null ? "none" : auth;
	}

	String challenge() throws IOException, InterruptedException {
		String serverId = text(json(send(request("/v1/auth/challenge").POST(HttpRequest.BodyPublishers.noBody()), MAX_JSON_BYTES)), "server_id");
		if (serverId == null || !serverId.matches("[0-9a-f]{40}")) {
			throw new IOException("Invalid challenge from the backend");
		}
		return serverId;
	}

	/** Exchanges a challenge the game has joined (through Mojang) for a session. */
	Session createSession(String username, String serverId) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		body.addProperty("server_id", serverId);
		JsonObject response = json(send(request("/v1/auth/session")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES));
		String sessionToken = text(response, "token");
		String player = text(response, "player_uuid");
		if (sessionToken == null || player == null) {
			throw new IOException("Invalid session from the backend");
		}
		Session session = session(response, sessionToken);
		token = session.token();
		return session;
	}

	/** The current session's player and ban state. */
	Session me() throws IOException, InterruptedException {
		return session(json(send(request("/v1/auth/me").GET(), MAX_JSON_BYTES)), token);
	}

	private static Session session(JsonObject response, String sessionToken) throws IOException {
		String player = text(response, "player_uuid");
		if (player == null) {
			throw new IOException("Invalid session from the backend");
		}
		JsonElement banned = response.get("banned");
		JsonElement until = response.get("banned_until");
		boolean isBanned = banned != null && banned.isJsonPrimitive() && banned.getAsJsonPrimitive().isBoolean() && banned.getAsBoolean();
		double bannedUntil = until != null && until.isJsonPrimitive() && until.getAsJsonPrimitive().isNumber() ? until.getAsDouble() : 0;
		boolean fullBan = isBanned && !"feature".equals(text(response, "ban_kind"));
		try {
			return new Session(sessionToken, UUID.fromString(player), text(response, "player_name"), isBanned, fullBan, bannedUntil);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid session from the backend");
		}
	}

	boolean signedIn() {
		return token != null;
	}

	void signOut() {
		token = null;
	}

	/** @return true if the backend already has an asset with this SHA-256 */
	boolean hasAsset(String sha256) throws IOException, InterruptedException {
		try {
			send(request("/v1/cosmetics/by-sha256/" + sha256).GET(), MAX_JSON_BYTES);
			return true;
		} catch (BackendException e) {
			if (e.status == 404) {
				return false;
			}
			throw e;
		}
	}

	/** Uploads a cosmetic or an emote file. A 409 (already uploaded) counts as success; returns false in that case. */
	boolean upload(String sha256, String displayName, byte[] bytes, boolean emote) throws IOException, InterruptedException {
		String boundary = "orbit-" + UUID.randomUUID();
		byte[] body = multipart(boundary, sha256.substring(0, 16) + ".bbmodel", displayName, emote ? "emote" : "cosmetic", bytes);
		try {
			send(request("/v1/cosmetics/upload")
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)), MAX_JSON_BYTES);
			return true;
		} catch (BackendException e) {
			if (e.status != 409) {
				throw e;
			}
			return false;
		}
	}

	/** The featured catalog: items (every page of them, up to a sane maximum) and bundles. Malformed entries are skipped. */
	/** The servers Orbit Cloud recommends (name and address of each). */
	JsonObject splashes() throws IOException, InterruptedException {
		return json(send(request("/v1/featured/splashes").GET(), MAX_JSON_BYTES));
	}

	JsonObject announcement() throws IOException, InterruptedException {
		return json(send(request("/v1/featured/announcement").GET(), MAX_JSON_BYTES));
	}

	JsonObject recommendedServers() throws IOException, InterruptedException {
		return json(send(request("/v1/servers/recommended").GET(), MAX_JSON_BYTES));
	}

	Catalog featured() throws IOException, InterruptedException {
		JsonObject response = json(send(request("/v1/featured?limit=" + CATALOG_PAGE).GET(), MAX_CATALOG_BYTES));
		List<CatalogItem> items = catalogItems(response.get("items"));
		int total = number(response, "total");
		for (int page = 1; page < MAX_CATALOG_PAGES && page * CATALOG_PAGE < total; page++) {
			JsonObject more = json(send(request("/v1/featured?limit=" + CATALOG_PAGE + "&offset=" + page * CATALOG_PAGE).GET(), MAX_CATALOG_BYTES));
			items.addAll(catalogItems(more.get("items")));
		}
		boolean complete = total <= MAX_CATALOG_PAGES * CATALOG_PAGE;
		List<Catalog.Bundle> bundles = new ArrayList<>();
		JsonElement bundleList = response.get("bundles");
		if (bundleList != null && bundleList.isJsonArray()) {
			for (JsonElement element : bundleList.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject bundle = element.getAsJsonObject();
				String id = text(bundle, "id");
				String name = text(bundle, "name");
				List<CatalogItem> contents = catalogItems(bundle.get("items"));
				if (id != null && name != null && !contents.isEmpty()) {
					bundles.add(new Catalog.Bundle(id, limited(name, 40), contents));
				}
			}
		}
		return new Catalog(List.copyOf(items), bundles, complete);
	}

	private static List<CatalogItem> catalogItems(JsonElement items) {
		List<CatalogItem> result = new ArrayList<>();
		if (items == null || !items.isJsonArray()) {
			return result;
		}
		for (JsonElement element : items.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			String id = text(item, "id");
			String sha = text(item, "sha256");
			String name = text(item, "name");
			if (id == null || name == null || sha == null || !sha.matches("[0-9a-f]{64}")) {
				continue;
			}
			String kindName = text(item, "kind");
			CatalogItem.Kind kind = "emote".equals(kindName) ? CatalogItem.Kind.EMOTE : "bodypart".equals(kindName) ? CatalogItem.Kind.BODY_PART : CatalogItem.Kind.COSMETIC;
			result.add(new CatalogItem(id, kind, limited(name, 64), sha, number(item, "size_bytes"), limited(text(item, "author"), 64),
				limited(text(item, "description"), 300), number(item, "animation_count"), text(item, "settings_url") != null, bool(item, "featured_plus"),
				TileColors.of(item.get("gradient")) != null ? item.get("gradient").deepCopy() : null));
		}
		return result;
	}

	private static boolean bool(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
	}

	/**
	 * Reports a player.
	 *
	 * @return true if this is a new report, false if it updated an earlier one
	 */
	boolean report(UUID player, String name, String reason, String note, List<String> cosmetics) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("player_uuid", player.toString());
		body.addProperty("player_name", name);
		body.addProperty("reason", reason);
		body.addProperty("note", note);
		JsonArray list = new JsonArray();
		cosmetics.forEach(list::add);
		body.add("cosmetics", list);
		JsonObject response = json(send(request("/v1/reports")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES));
		JsonElement isNew = response.get("new");
		return isNew == null || !isNew.isJsonPrimitive() || isNew.getAsBoolean();
	}

	private static int number(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() ? element.getAsInt() : 0;
	}

	private static String limited(String value, int max) {
		if (value == null) {
			return "";
		}
		String clean = value.replaceAll("\\p{Cntrl}", " ").trim();
		return clean.length() > max ? clean.substring(0, max) : clean;
	}

	/** The Orbit settings file shipped with a featured cosmetic, by the item's ID in the catalog. */
	byte[] featuredSettings(String itemId, int maxBytes) throws IOException, InterruptedException {
		if (!itemId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
			throw new IOException("Invalid catalog item");
		}
		return send(request("/v1/featured/" + itemId + "/settings").GET(), maxBytes);
	}

	/** An emote's particles as another player published them; malformed data means none. */
	private static List<ParticleSettings> particles(String base64) {
		if (base64 == null || base64.length() > ParticleCodec.MAX_BYTES * 2) {
			return List.of();
		}
		try {
			return ParticleCodec.decode(Base64.getDecoder().decode(base64));
		} catch (IllegalArgumentException | ProtocolException e) {
			return List.of();
		}
	}

	byte[] download(String sha256, int maxBytes) throws IOException, InterruptedException {
		return send(request("/v1/cosmetics/by-sha256/" + sha256 + "/download").GET(), maxBytes);
	}

	void putPresence(UUID player, String name, int format, String equip, EmoteSignal emote, SkinSignal skin) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("player_name", name);
		body.addProperty("format", format);
		body.addProperty("equip", equip);
		if (emote != null) {
			JsonObject state = new JsonObject();
			state.addProperty("sha256", emote.sha256());
			state.addProperty("mobile", emote.mobile());
			state.addProperty("seq", emote.seq());
			if (!emote.particles().isEmpty()) {
				state.addProperty("particles", Base64.getEncoder().encodeToString(ParticleCodec.encode(emote.particles())));
			}
			body.add("emote", state);
		}
		if (skin != null) {
			JsonObject state = new JsonObject();
			if (skin.texture() != null) {
				state.addProperty("texture", skin.texture());
			}
			state.addProperty("slim", skin.slim());
			if (skin.cape() != null) {
				state.addProperty("cape", skin.cape());
			}
			state.addProperty("no_cape", skin.noCape());
			body.add("skin", state);
		}
		send(request("/v1/presence/" + player)
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES);
	}

	void deletePresence(UUID player) throws IOException, InterruptedException {
		try {
			send(request("/v1/presence/" + player).DELETE(), MAX_JSON_BYTES);
		} catch (BackendException e) {
			if (e.status != 404) {
				throw e;
			}
		}
	}

	/** @param names player names as well, so offline-mode servers (whose UUIDs differ) still match */
	List<PresenceEntry> queryPresence(List<UUID> players, List<String> names) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		JsonArray list = new JsonArray();
		players.forEach(player -> list.add(player.toString()));
		body.add("players", list);
		JsonArray nameList = new JsonArray();
		names.forEach(nameList::add);
		body.add("names", nameList);
		JsonObject response = json(send(request("/v1/presence/query")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES));
		List<PresenceEntry> result = new ArrayList<>();
		JsonElement entries = response.get("players");
		JsonElement now = response.get("now");
		double serverNow = now != null && now.isJsonPrimitive() && now.getAsJsonPrimitive().isNumber() ? now.getAsDouble() : 0;
		if (entries == null || !entries.isJsonArray()) {
			return result;
		}
		for (JsonElement element : entries.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject entry = element.getAsJsonObject();
			try {
				UUID player = UUID.fromString(text(entry, "player_uuid"));
				JsonElement format = entry.get("format");
				String equip = text(entry, "equip");
				String name = text(entry, "player_name");
				if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber() || equip == null) {
					continue;
				}
				EmoteSignal emote = null;
				double age = 0;
				JsonElement emoteElement = entry.get("emote");
				if (emoteElement != null && emoteElement.isJsonObject()) {
					JsonObject state = emoteElement.getAsJsonObject();
					String sha = text(state, "sha256");
					if (sha != null && sha.matches("[0-9a-f]{64}")) {
						emote = new EmoteSignal(sha, bool(state, "mobile"), number(state, "seq"), particles(text(state, "particles")));
						JsonElement started = entry.get("emote_started_at");
						if (started != null && started.isJsonPrimitive() && started.getAsJsonPrimitive().isNumber() && serverNow > 0) {
							age = Math.max(0, serverNow - started.getAsDouble());
						}
					}
				}
				SkinSignal skin = null;
				JsonElement skinElement = entry.get("skin");
				if (skinElement != null && skinElement.isJsonObject()) {
					JsonObject state = skinElement.getAsJsonObject();
					String texture = text(state, "texture");
					String cape = text(state, "cape");
					// Only hashes: the addresses are built here, on textures.minecraft.net.
					skin = new SkinSignal(SkinSignal.isHash(texture) ? texture : null, bool(state, "slim"), SkinSignal.isHash(cape) ? cape : null, bool(state, "no_cape"));
				}
				result.add(new PresenceEntry(player, name == null ? "" : name, format.getAsInt(), equip, emote, age, skin));
			} catch (IllegalArgumentException | NullPointerException ignored) {
				// skip malformed entries
			}
		}
		return result;
	}

	private HttpRequest.Builder request(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(TIMEOUT)
			.header("Accept", "application/json, */*")
			.header("User-Agent", userAgent)
			// Orbit Cloud refuses versions too old for its data.
			.header("X-Orbit-Version", modVersion);
		String current = token;
		if (current != null) {
			builder.header("Authorization", "Bearer " + current);
		}
		if (devPlayer != null) {
			builder.header("X-Orbit-Dev-Player", devPlayer);
		}
		return builder;
	}

	/**
	 * Development runs against a local test backend name their player in a header ({@code -Dorbit.devPlayer}, with
	 * {@code -Dorbit.backendUrl}); a development backend has no Mojang sign-in. Never set in released games.
	 */
	private static final String devPlayer = System.getProperty("orbit.backendUrl") != null
		&& net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment() ? System.getProperty("orbit.devPlayer") : null;

	/** True when this is a development run identified by {@link #devPlayer} (no Mojang sign-in needed). */
	static boolean hasDevPlayer() {
		return devPlayer != null;
	}

	// ---------------------------------------------------------------- friends (see backend routes/social.py)

	/** A JSON call to the friends API: method GET, POST, PUT or DELETE, with an optional JSON body. */
	JsonObject social(String method, String path, JsonElement body) throws IOException, InterruptedException {
		HttpRequest.Builder builder = request("/v1/social" + path);
		HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(GSON.toJson(body));
		if (body != null) {
			builder.header("Content-Type", "application/json");
		}
		builder.method(method, publisher);
		return json(send(builder, 4 * 1024 * 1024));
	}

	/** Waits up to 25 seconds for a change; returns the new change cursor. */
	/** The wait's answer: the new {@code cursor}, and {@code signals} (see BackendSync#signals). */
	JsonObject socialWait(int cursor) throws IOException, InterruptedException {
		JsonObject result = json(send(request("/v1/social/wait?cursor=" + cursor).timeout(Duration.ofSeconds(45)).GET(), MAX_JSON_BYTES));
		JsonElement value = result.get("cursor");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IOException("Unexpected response");
		}
		return result;
	}

	/** Uploads an encrypted attachment; returns its ID. */
	/** Sends raw bytes to a path of the friends API (a group's picture); returns the answer. */
	JsonObject socialPutRaw(String path, byte[] data) throws IOException, InterruptedException {
		HttpRequest.Builder builder = request("/v1/social" + path).header("Content-Type", "application/octet-stream")
			.timeout(java.time.Duration.ofMinutes(1)).PUT(HttpRequest.BodyPublishers.ofByteArray(data));
		return json(send(builder, MAX_JSON_BYTES));
	}

	String socialUpload(byte[] data) throws IOException, InterruptedException {
		HttpRequest.Builder builder = request("/v1/social/attachments").header("Content-Type", "application/octet-stream")
			.timeout(java.time.Duration.ofMinutes(2)).POST(HttpRequest.BodyPublishers.ofByteArray(data));
		String id = text(json(send(builder, MAX_JSON_BYTES)), "id");
		if (id == null || !id.matches("[0-9a-f]{32}")) {
			throw new IOException("Invalid attachment ID");
		}
		return id;
	}

	/** Downloads an encrypted attachment. */
	/** Downloads a file of Orbit Cloud at a path (signed in). */
	byte[] download(String path, int maxBytes, boolean signedIn) throws IOException, InterruptedException {
		return send(request(path).timeout(java.time.Duration.ofMinutes(2)).GET(), maxBytes);
	}

	byte[] socialDownload(String id, int maxBytes) throws IOException, InterruptedException {
		return send(request("/v1/social/attachments/" + id).timeout(java.time.Duration.ofMinutes(2)).GET(), maxBytes);
	}

	private byte[] send(HttpRequest.Builder builder, int maxBytes) throws IOException, InterruptedException {
		HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		byte[] body;
		try (InputStream stream = response.body()) {
			body = stream.readNBytes(maxBytes + 1);
		}
		if (body.length > maxBytes) {
			throw new BackendException(response.statusCode(), "", "Response larger than " + maxBytes + " bytes");
		}
		if (response.statusCode() / 100 != 2) {
			throw error(response.statusCode(), body);
		}
		return body;
	}

	/** Reads the backend's {"error": {"code", "message"}} body, if there is one. */
	private static BackendException error(int status, byte[] body) {
		try {
			JsonObject error = json(body).getAsJsonObject("error");
			if (error != null) {
				String code = text(error, "code");
				String message = text(error, "message");
				double expires = 0;
				boolean fullBan = false;
				List<String> blocked = new ArrayList<>();
				JsonElement details = error.get("details");
				if (details != null && details.isJsonObject()) {
					JsonObject detailObject = details.getAsJsonObject();
					JsonElement value = detailObject.get("expires_at");
					if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
						expires = value.getAsDouble();
					}
					fullBan = "full".equals(text(detailObject, "kind"));
					JsonElement items = detailObject.get("blocked");
					if (items != null && items.isJsonArray()) {
						for (JsonElement item : items.getAsJsonArray()) {
							if (item.isJsonPrimitive() && item.getAsString().matches("[0-9a-f]{64}") && blocked.size() < 64) {
								blocked.add(item.getAsString());
							}
						}
					}
				}
				return new BackendException(status, code == null ? "" : code, message == null ? "" : limited(message, 200), expires, fullBan, List.copyOf(blocked));
			}
		} catch (IOException | ClassCastException ignored) {
			// not a JSON error body
		}
		return new BackendException(status, "", "");
	}

	private static JsonObject json(byte[] body) throws IOException {
		try {
			JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			if (!element.isJsonObject()) {
				throw new IOException("Unexpected response");
			}
			return element.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new IOException("Invalid JSON from the backend", e);
		}
	}

	private static String text(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static byte[] multipart(String boundary, String filename, String displayName, String kind, byte[] file) {
		String name = displayName.replaceAll("[\\r\\n\"]", " ");
		String head = "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"display_name\"\r\n\r\n" + name + "\r\n"
			+ "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"kind\"\r\n\r\n" + kind + "\r\n"
			+ "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
			+ "Content-Type: application/octet-stream\r\n\r\n";
		byte[] start = head.getBytes(StandardCharsets.UTF_8);
		byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] body = new byte[start.length + file.length + end.length];
		System.arraycopy(start, 0, body, 0, start.length);
		System.arraycopy(file, 0, body, start.length, file.length);
		System.arraycopy(end, 0, body, start.length + file.length, end.length);
		return body;
	}
}
