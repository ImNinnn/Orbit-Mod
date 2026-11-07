package dev.orbit.cosmetic.client.net;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.emote.EmoteSignal;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.skin.SkinSignal;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquipCodec;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.protocol.ProtocolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Orbit Cloud: how Orbit players see each other's cosmetics and emotes on every server.
 *
 * <p>Shortly after joining a multiplayer server (or opening a world to LAN), the client signs in with its Minecraft
 * account, uploads the files it uses that Orbit Cloud does not have yet, and publishes what it wears and which emote
 * it plays (presence, refreshed every 30 seconds and whenever it changes). Every 2 seconds it asks what the players
 * in the tab list wear; while none of them uses Orbit, only every 10 seconds and when the tab list changes.
 * Downloaded files are checked against their SHA-256 and cached.
 *
 * <p>Leaving a server keeps the Orbit Cloud session for 10 seconds, so moving between the servers of a network
 * (BungeeCord, Velocity) does not sign in and announce everything again.
 *
 * <p>Moderators can ban players. A feature ban shares featured catalog items only; a complete ban shares nothing.
 * Items moderators blocked are never shared again. The featured catalog and player reports also go through here.
 */
public final class BackendSync {
	private static final int ACTIVATE_AFTER_TICKS = 40;
	private static final int QUERY_EVERY_TICKS = 40;
	/** While no other player here uses Orbit. */
	private static final int QUERY_ALONE_EVERY_TICKS = 200;
	/** After the tab list changed, queries stay frequent this long: new players publish a few seconds after joining. */
	private static final int QUERY_FAST_AFTER_CHANGE_TICKS = 400;
	private static final long DISCONNECT_GRACE_MS = 10_000;
	private static final int PUBLISH_EVERY_TICKS = 600;
	private static final int PUBLISH_DEBOUNCE_TICKS = 20;
	private static final long DOWNLOAD_RETRY_MS = 60_000;
	/** Orbit Cloud refuses larger uploads. */
	private static final int MAX_UPLOAD_BYTES = 1024 * 1024;
	private static final int MAX_QUERY_PLAYERS = 200;
	/** The mod reads settings files up to 256 KiB. */
	private static final int SETTINGS_MAX_BYTES = 256 * 1024;
	private static final long CATALOG_REFRESH_MS = 10 * 60 * 1000;

	/** Where one of the player's own cosmetics (or their emote) stands on Orbit Cloud, for this connection. */
	public enum CloudShare {
		/** Being uploaded or published. */
		SENDING,
		/** Published: every Orbit player on this server can see it. */
		SHARED,
		/** Larger than Orbit Cloud accepts. */
		TOO_BIG,
		/** Orbit Cloud refused it (see {@link #cloudReason}). */
		REFUSED,
		/** Feature ban: only featured catalog items are shared. */
		NOT_FEATURED,
		/** Complete ban: nothing is shared. */
		BANNED,
		/** Moderators blocked this file. */
		BLOCKED
	}

	private final OrbitClient orbit;
	private final RemotePlayers remotes;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Orbit Cloud");
		thread.setDaemon(true);
		return thread;
	});

	private BackendClient client;
	/** Who we are signed in as; null when not signed in. */
	private volatile UUID signedInAs;
	private boolean connected;
	private boolean active;
	private boolean checking;
	private int ticksSinceJoin;
	private int publishCountdown = -1;
	private int sinceQuery;
	private int sincePublish;
	private boolean queryInFlight;
	/** Players in the tab list when last checked (a hash of their UUIDs), and ticks left of frequent queries. */
	private int tabList;
	private int fastQueries;
	/** Changes on every server joined, so answers about the previous server's players are dropped. */
	private int server;
	/** When a session kept after leaving a server ends; 0 when none is pending. */
	private long disconnectAt;
	/** "Connected to Orbit Cloud" was shown in this game. */
	private boolean connectionAnnounced;
	/** "Not reachable" was shown; it is not shown again until a connection succeeds. */
	private boolean unreachableShown;
	/** The "too old for Orbit Cloud" message was shown (once per game). */
	private boolean outdatedShown;
	private UUID publishedAs;
	/** Players whose presence came from Orbit Cloud on this connection. */
	private final Set<UUID> cloudPlayers = new HashSet<>();
	private final Set<AssetId> downloading = new HashSet<>();
	private final Map<AssetId, Long> retryAfter = new HashMap<>();
	/** Files Orbit Cloud is known to have (cleared when the URL changes). */
	private final Set<String> uploaded = ConcurrentHashMap.newKeySet();
	/** Files moderators blocked. */
	private final Set<String> blocked = ConcurrentHashMap.newKeySet();
	private boolean warned;
	/** Ban state from the last sign-in or refused request. 0 = permanent. */
	private volatile boolean banned;
	private volatile boolean fullBan;
	private volatile double bannedUntil;
	private volatile Catalog catalog = Catalog.EMPTY;
	private volatile long catalogFetchedAt;
	/** Problems already announced on this connection (asset and kind), so they are not repeated every refresh. */
	private final Set<String> announced = ConcurrentHashMap.newKeySet();
	/** By SHA-256. Written by the worker thread, read by the game thread. */
	private final Map<String, CloudShare> cloudShare = new ConcurrentHashMap<>();
	private final Map<String, String> cloudReason = new ConcurrentHashMap<>();

	public BackendSync(OrbitClient orbit, RemotePlayers remotes) {
		this.orbit = orbit;
		this.remotes = remotes;
		remotes.setBackend(this);
	}

	/** True while Orbit Cloud is in use on this server. */
	public boolean isActive() {
		return active;
	}

	// ---------------------------------------------------------------- lifecycle

	public void onJoin() {
		if (active) {
			// Another server of the same network (or a server switch within the grace period): keep the session.
			disconnectAt = 0;
			connected = true;
			server++;
			cloudPlayers.clear();
			remotes.clear();
			orbit.skins().clearRemote();
			downloading.clear();
			queryInFlight = false;
			sinceQuery = QUERY_EVERY_TICKS;
			publishCountdown = 0;
			return;
		}
		endSession();
		connected = true;
		warned = false;
		announced.clear();
	}

	/** Left the server: the session ends after a grace period unless another server is joined first. */
	public void onDisconnect() {
		if (active) {
			connected = false;
			disconnectAt = System.currentTimeMillis() + DISCONNECT_GRACE_MS;
			cloudPlayers.clear();
			remotes.clear();
			orbit.skins().clearRemote();
			return;
		}
		endSession();
	}

	/** The game closes: ends the session now. */
	public void onStop() {
		endSession();
	}

	private void endSession() {
		if (active && publishedAs != null) {
			UUID player = publishedAs;
			BackendClient current = client;
			worker.execute(() -> quietly(() -> current.deletePresence(player)));
		}
		reset();
	}


	/** "Connected to Orbit Cloud", once per game: as soon as Orbit Cloud is first reached (at launch, by Social). */
	public void announceConnected() {
		if (!connectionAnnounced) {
			connectionAnnounced = true;
			Notifications.success(Component.translatable("orbit_cosmetic.notify.connected_cloud"),
				Component.translatable("orbit_cosmetic.notify.connected_cloud.signed_in", Minecraft.getInstance().getUser().getName()));
		}
	}

	/** The worn cosmetics, their settings or the emote changed. */
	public void markDirty() {
		if (active) {
			publishCountdown = PUBLISH_DEBOUNCE_TICKS;
		}
	}

	/** An emote started or stopped: other players should see it right away. */
	public void publishSoon() {
		if (active) {
			publishCountdown = 0;
		}
	}

	private void reset() {
		cloudShare.clear();
		cloudReason.clear();
		cloudPlayers.clear();
		remotes.clear();
		orbit.skins().clearRemote();
		connected = false;
		active = false;
		checking = false;
		ticksSinceJoin = 0;
		publishCountdown = -1;
		sinceQuery = 0;
		sincePublish = 0;
		queryInFlight = false;
		tabList = 0;
		fastQueries = 0;
		disconnectAt = 0;
		publishedAs = null;
		downloading.clear();
		retryAfter.clear();
	}

	// ---------------------------------------------------------------- ticking

	public void tick(Minecraft minecraft) {
		if (disconnectAt > 0 && System.currentTimeMillis() >= disconnectAt) {
			endSession();
		}
		if (!connected || minecraft.player == null || minecraft.getConnection() == null) {
			return;
		}
		if (!active) {
			if (++ticksSinceJoin >= ACTIVATE_AFTER_TICKS && !checking && eligible(minecraft)) {
				activate();
			}
			return;
		}
		if (publishCountdown >= 0 && publishCountdown-- == 0 || ++sincePublish >= PUBLISH_EVERY_TICKS) {
			publishCountdown = -1;
			sincePublish = 0;
			publish(minecraft);
		}
		int players = tabListHash(minecraft.getConnection());
		if (players != tabList) {
			tabList = players;
			fastQueries = QUERY_FAST_AFTER_CHANGE_TICKS;
			sinceQuery = Math.max(sinceQuery, QUERY_EVERY_TICKS / 2);
		}
		if (fastQueries > 0) {
			fastQueries--;
		}
		int every = cloudPlayers.isEmpty() && fastQueries == 0 ? QUERY_ALONE_EVERY_TICKS : QUERY_EVERY_TICKS;
		if (++sinceQuery >= every && !queryInFlight) {
			sinceQuery = 0;
			query(minecraft);
		}
	}

	private static int tabListHash(ClientPacketListener connection) {
		int hash = 0;
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			hash += info.getProfile().id().hashCode();
		}
		return hash * 31 + connection.getOnlinePlayers().size();
	}

	/** Not in plain singleplayer (nobody else can join), and only if enabled. */
	private boolean eligible(Minecraft minecraft) {
		if (!orbit.config().useBackend) {
			return false;
		}
		return minecraft.getSingleplayerServer() == null || minecraft.getSingleplayerServer().isPublished();
	}

	/** The client for the configured URL, created when needed. Game thread only. */
	private BackendClient ensureClient() {
		if (!orbit.config().hasAcceptedTerms()) {
			// Nothing goes to Orbit Cloud before the Terms of Service and Privacy Policy are accepted.
			return null;
		}
		try {
			String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			BackendClient candidate = client != null && client.baseUrl().equals(OrbitCloud.url()) ? client : new BackendClient(OrbitCloud.url(), version);
			if (candidate != client) {
				uploaded.clear();
				blocked.clear();
				signedInAs = null;
			}
			client = candidate;
			return candidate;
		} catch (IllegalArgumentException e) {
			warnOnce("Orbit Cloud disabled: " + e.getMessage());
			return null;
		}
	}

	private void activate() {
		BackendClient candidate = ensureClient();
		if (candidate == null) {
			connected = false;
			return;
		}
		checking = true;
		Minecraft minecraft = Minecraft.getInstance();
		net.minecraft.client.User user = minecraft.getUser();
		worker.execute(() -> {
			String auth;
			try {
				auth = candidate.health();
			} catch (IOException | InterruptedException e) {
				auth = null;
			}
			boolean signedIn = false;
			String minimum = candidate.minimumClient();
			if (auth != null && dev.orbit.cosmetic.client.Updates.isOlder(dev.orbit.cosmetic.client.Updates.modVersion(), minimum)) {
				// Too old for Orbit Cloud's data: it would refuse to sign in, so do not try.
				auth = "outdated:" + minimum;
			}
			if ("mojang".equals(auth)) {
				if (candidate.signedIn() && signedInAs != null) {
					refreshBan(candidate);
					signedIn = candidate.signedIn();
				}
				if (!signedIn) {
					signedIn = signIn(minecraft, user, candidate);
				}
				refreshCatalog(candidate);
			}
			String result = auth;
			boolean ready = signedIn;
			Minecraft.getInstance().execute(() -> {
				checking = false;
				if (!connected) {
					return;
				}
				if (result == null) {
					warnOnce("Orbit Cloud at " + candidate.baseUrl() + " is not reachable; cosmetics of other players are not shown on this server");
					if (!unreachableShown) {
						// Once until a connection succeeds: networks would otherwise show it on every server switch.
						unreachableShown = true;
						Notifications.error(Component.translatable("orbit_cosmetic.notify.cloud_unreachable"), Component.translatable("orbit_cosmetic.notify.cloud_unreachable.detail"));
					}
					connected = false;
					return;
				}
				if (result.startsWith("outdated:")) {
					warnOnce("Orbit Cloud needs Orbit " + result.substring(9) + " or newer; restart Minecraft to update");
					if (!outdatedShown) {
						outdatedShown = true;
						Notifications.error(Component.translatable("orbit_cosmetic.notify.outdated"), Component.translatable("orbit_cosmetic.notify.outdated.detail", result.substring(9)));
					}
					connected = false;
					return;
				}
				if (!"mojang".equals(result)) {
					// Orbit Cloud verifies every player's Minecraft account; anything else is not Orbit Cloud.
					warnOnce("The backend at " + candidate.baseUrl() + " does not verify Minecraft accounts, so it is not used");
					connected = false;
					return;
				}
				active = true;
				unreachableShown = false;
				OrbitClient.LOGGER.info("Sharing cosmetics through Orbit Cloud at {}", candidate.baseUrl());
				Component connectedTitle = Component.translatable("orbit_cosmetic.notify.connected_cloud");
				if (!ready) {
					warnOnce("Could not sign in to Orbit Cloud with this Minecraft account; you can see other players' cosmetics but yours are not shared");
					Notifications.warning(connectedTitle, Component.translatable("orbit_cosmetic.notify.connected_cloud.view_only"));
				} else if (banned) {
					Notifications.warning(connectedTitle, Component.translatable(fullBan ? "orbit_cosmetic.notify.connected_cloud.full_ban" : "orbit_cosmetic.notify.connected_cloud.banned"));
				} else {
					// Usually already said at launch (see announceConnected).
					announceConnected();
				}
				publish(Minecraft.getInstance());
				query(Minecraft.getInstance());
			});
		});
	}

	/**
	 * Signs in with Minecraft's session check. The game tells Mojang it joins the backend's one-time server ID;
	 * the access token only ever goes to Mojang. Runs on the worker thread.
	 */
	private synchronized boolean signIn(Minecraft minecraft, net.minecraft.client.User user, BackendClient backend) {
		if (user == null || user.getAccessToken() == null || user.getAccessToken().isBlank() || user.getProfileId() == null) {
			return false;
		}
		try {
			String serverId = backend.challenge();
			minecraft.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
			BackendClient.Session created = backend.createSession(user.getName(), serverId);
			signedInAs = created.player();
			OrbitClient.LOGGER.info("Signed in to Orbit Cloud as {}", created.name());
			applyBan(created.banned(), created.fullBan(), created.bannedUntil());
			return true;
		} catch (com.mojang.authlib.exceptions.AuthenticationException e) {
			OrbitClient.LOGGER.debug("Mojang refused the session check: {}", e.getMessage());
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Orbit Cloud sign-in failed: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			OrbitClient.LOGGER.debug("Orbit Cloud sign-in failed", e);
		}
		backend.signOut();
		signedInAs = null;
		return false;
	}

	/** Records the ban state Orbit Cloud reported, announcing changes. Runs on the worker thread. */
	private void applyBan(boolean isBanned, boolean isFull, double until) {
		boolean wasBanned = banned;
		boolean wasFull = fullBan;
		banned = isBanned;
		fullBan = isBanned && isFull;
		bannedUntil = until;
		if (isBanned && (!wasBanned || wasFull != fullBan)) {
			String key = fullBan ? "orbit_cosmetic.notify.cloud_banned.full" : "orbit_cosmetic.notify.cloud_banned";
			Component when = until > 0
				? Component.translatable(key + ".until", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond((long) until)))
				: Component.translatable(key + ".permanent");
			Notifications.error(Component.translatable(key), when);
			Minecraft.getInstance().execute(this::markDirty);
		} else if (!isBanned && wasBanned) {
			Notifications.success(Component.translatable("orbit_cosmetic.notify.cloud_unbanned"), null);
			Minecraft.getInstance().execute(this::markDirty);
		}
	}

	/** Asks Orbit Cloud for the current ban state. Worker thread. */
	private void refreshBan(BackendClient backend) {
		if (!backend.signedIn()) {
			return; // not signed in: nothing is published, and the next sign-in reports the ban state
		}
		try {
			BackendClient.Session session = backend.me();
			applyBan(session.banned(), session.fullBan(), session.bannedUntil());
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Could not refresh the ban state: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** True while banned from Orbit Cloud. */
	public boolean isBanned() {
		return banned;
	}

	/** True for a complete ban (nothing is shared); a feature ban still shares featured catalog items. */
	public boolean isFullBan() {
		return banned && fullBan;
	}

	/** True when Orbit Cloud is in use here but the Minecraft account could not be verified: nothing is shared. */
	public boolean isViewOnly() {
		return active && signedInAs == null;
	}

	/** Null when the file has not been published on this connection yet. */
	public CloudShare cloudShare(String sha256) {
		return cloudShare.get(sha256);
	}

	public String cloudReason(String sha256) {
		return cloudReason.getOrDefault(sha256, "");
	}

	/** Shows a problem with a file once per connection. */
	private void announceOnce(String key, Runnable show) {
		if (announced.add(key)) {
			show.run();
		}
	}

	// ---------------------------------------------------------------- publishing

	/**
	 * A file to make sure Orbit Cloud has before publishing.
	 *
	 * @param onCloud a claimed featured item still in the catalog: it lives on Orbit Cloud, so it is never uploaded.
	 *                The player's own files (editable copies of featured items included) are uploaded to Orbit Cloud's
	 *                cache when it does not have them, like any other cosmetic
	 */
	private record Upload(String sha256, String name, byte[] bytes, boolean emote, boolean onCloud) {
	}

	private void publish(Minecraft minecraft) {
		if (minecraft.player == null) {
			return;
		}
		UUID player = signedInAs;
		if (player == null) {
			return; // not signed in: viewing only
		}
		// Presence is stored under the verified account, which offline-mode servers may show with another UUID.
		String name = minecraft.player.getGameProfile().name();
		net.minecraft.client.User user = minecraft.getUser();
		if (banned && bannedUntil > 0 && System.currentTimeMillis() / 1000.0 > bannedUntil) {
			BackendClient backend = client;
			worker.execute(() -> refreshBan(backend));
		}
		// Capture everything on the game thread; the worker only does I/O.
		List<EquippedCosmetic> equipped = new ArrayList<>();
		List<Upload> uploads = new ArrayList<>();
		for (LocalCosmetic entry : orbit.library().loaded()) {
			if (equipped.size() >= EquipCodec.MAX_EQUIPPED) {
				break;
			}
			String sha = entry.asset().hex();
			if (!mayShare(sha)) {
				continue;
			}
			cloudShare.putIfAbsent(sha, CloudShare.SENDING);
			equipped.add(entry.toEquipped());
			uploads.add(new Upload(sha, entry.displayName(), entry.bytes(), false, entry.isFeatured() && catalog.contains(sha)));
		}
		EmoteSignal emote = orbit.emoteController().signal();
		if (emote != null) {
			if (mayShare(emote.sha256())) {
				byte[] bytes = orbit.emoteController().fileBytes();
				cloudShare.putIfAbsent(emote.sha256(), CloudShare.SENDING);
				boolean featured = orbit.emoteController().playing().file().isFeatured();
				uploads.add(new Upload(emote.sha256(), orbit.emoteController().playing().displayName(), bytes, true, featured && catalog.contains(emote.sha256())));
			} else {
				emote = null;
			}
		}
		if (fullBan && banned) {
			return; // a complete ban: nothing is published (and Orbit Cloud removed what was)
		}
		// Skins and capes are Mojang's: shared whatever a feature ban allows.
		SkinSignal skin = orbit.skins().signal();
		BackendClient current = client;
		publishedAs = player;
		EmoteSignal playing = emote;
		worker.execute(() -> {
			Set<String> available = new HashSet<>();
			for (Upload upload : uploads) {
				String sha = upload.sha256();
				if (upload.onCloud()) {
					available.add(sha);
					continue;
				}
				try {
					if (!uploaded.contains(sha) && !current.hasAsset(sha)) {
						if (upload.bytes() == null || upload.bytes().length > MAX_UPLOAD_BYTES) {
							cloudShare.put(sha, CloudShare.TOO_BIG);
							announceOnce(sha + ":too_large", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.too_big", upload.name()),
								Component.translatable("orbit_cosmetic.notify.too_big.detail", MAX_UPLOAD_BYTES / 1024)));
							continue;
						}
						current.upload(sha, upload.name(), upload.bytes(), upload.emote());
					}
					uploaded.add(sha);
					available.add(sha);
				} catch (BackendClient.BackendException e) {
					uploadRefused(sha, upload.name(), e);
				} catch (IOException e) {
					warnOnceAsync("Could not upload \"" + upload.name() + "\" to Orbit Cloud: " + e.getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			List<EquippedCosmetic> shared = equipped.stream().filter(cosmetic -> available.contains(cosmetic.asset().hex())).toList();
			EmoteSignal sharedEmote = playing != null && available.contains(playing.sha256()) ? playing : null;
			putPresence(minecraft, user, current, player, name, shared, sharedEmote, skin, true);
		});
	}

	/** False for files that cannot be shared (blocked, or not in the catalog during a feature ban); records why. */
	private boolean mayShare(String sha) {
		if (blocked.contains(sha)) {
			cloudShare.put(sha, CloudShare.BLOCKED);
			return false;
		}
		if (banned && fullBan) {
			cloudShare.put(sha, CloudShare.BANNED);
			return false;
		}
		if (banned && !catalog.contains(sha)) {
			cloudShare.put(sha, CloudShare.NOT_FEATURED);
			return false;
		}
		return true;
	}

	/** Publishes the presence. Worker thread. */
	private void putPresence(Minecraft minecraft, net.minecraft.client.User user, BackendClient current, UUID player, String name,
		List<EquippedCosmetic> shared, EmoteSignal emote, SkinSignal skin, boolean retry) {
		String equip = Base64.getEncoder().encodeToString(EquipCodec.encode(shared));
		try {
			current.putPresence(player, name, EquipCodec.FORMAT, equip, emote, skin);
			shared.forEach(cosmetic -> cloudShare.put(cosmetic.asset().hex(), CloudShare.SHARED));
			if (emote != null) {
				cloudShare.put(emote.sha256(), CloudShare.SHARED);
			}
		} catch (BackendClient.BackendException e) {
			if (e.status == 401 && retry && signIn(minecraft, user, current)) {
				// Sessions expire; sign in again once and retry.
				putPresence(minecraft, user, current, player, name, shared, emote, skin, false);
			} else if ("blocked".equals(e.code)) {
				applyBan(true, e.fullBan, e.banExpires);
			} else if ("cosmetic_blocked".equals(e.code) && retry) {
				// Moderators blocked some of these files: drop them and publish the rest.
				blocked.addAll(e.blockedItems);
				for (String sha : e.blockedItems) {
					cloudShare.put(sha, CloudShare.BLOCKED);
					announceOnce(sha + ":blocked", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.item_blocked", nameOf(sha)),
						Component.translatable("orbit_cosmetic.notify.item_blocked.detail")));
				}
				List<EquippedCosmetic> rest = shared.stream().filter(cosmetic -> !blocked.contains(cosmetic.asset().hex())).toList();
				putPresence(minecraft, user, current, player, name, rest, emote != null && blocked.contains(emote.sha256()) ? null : emote, skin, false);
			} else {
				warnOnceAsync("Orbit Cloud refused the presence update: " + e.getMessage());
			}
		} catch (IOException e) {
			warnOnceAsync("Orbit Cloud request failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private String nameOf(String sha) {
		for (LocalCosmetic entry : orbit.library().entries()) {
			if (entry.asset() != null && entry.asset().hex().equals(sha)) {
				return entry.displayName();
			}
		}
		return sha.substring(0, 8);
	}

	/** Explains why Orbit Cloud refused an upload. Worker thread. */
	private void uploadRefused(String sha, String displayName, BackendClient.BackendException e) {
		cloudShare.put(sha, CloudShare.REFUSED);
		cloudReason.put(sha, e.detail.isEmpty() ? e.getMessage() : e.detail);
		switch (e.code) {
			case "blocked" -> applyBan(true, e.fullBan, e.banExpires);
			case "cosmetic_blocked" -> {
				blocked.add(sha);
				cloudShare.put(sha, CloudShare.BLOCKED);
				announceOnce(sha + ":blocked", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.item_blocked", displayName),
					Component.translatable("orbit_cosmetic.notify.item_blocked.detail")));
			}
			case "too_large" -> {
				cloudShare.put(sha, CloudShare.TOO_BIG);
				announceOnce(sha + ":too_large", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.too_big", displayName),
					Component.translatable("orbit_cosmetic.notify.too_big.detail", MAX_UPLOAD_BYTES / 1024)));
			}
			case "unsupported_file_type", "empty_file", "invalid_json", "invalid_encoding", "too_deep", "invalid_structure", "too_complex", "no_animations" ->
				announceOnce(sha + ":format", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.wrong_format", displayName), Component.literal(e.detail)));
			case "quota_exceeded", "storage_full" ->
				announceOnce(sha + ":quota", () -> Notifications.warning(Component.translatable("orbit_cosmetic.notify.cloud_rejected", displayName), Component.literal(e.detail)));
			default -> warnOnceAsync("Could not upload \"" + displayName + "\" to Orbit Cloud: " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- the featured catalog

	/** The featured catalog as last fetched (empty until then). */
	public Catalog catalog() {
		return catalog;
	}

	/** Fetches the catalog in the background if it is missing or older than ten minutes. */
	public void refreshCatalog() {
		if (System.currentTimeMillis() - catalogFetchedAt < CATALOG_REFRESH_MS) {
			return;
		}
		BackendClient current = ensureClient();
		if (current != null) {
			catalogFetchedAt = System.currentTimeMillis();
			worker.execute(() -> refreshCatalog(current));
		}
	}

	private void refreshCatalog(BackendClient current) {
		try {
			catalog = current.featured();
			catalogFetchedAt = System.currentTimeMillis();
		} catch (IOException e) {
			catalogFetchedAt = 0; // try again next time
			OrbitClient.LOGGER.debug("Could not load the featured catalog: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The servers Orbit Cloud recommends, as [{"name", "address"}]. Callbacks run on the game thread. */
	private volatile @org.jspecify.annotations.Nullable String announcementText;
	private volatile double announcementUntil;
	private long announcementCheckedAt;

	/** The message under the Wardrobe button ("Summer Cosmetics!", set by the Orbit team), or null. */
	public @org.jspecify.annotations.Nullable String announcement() {
		String text = announcementText;
		return text != null && System.currentTimeMillis() / 1000.0 < announcementUntil ? text : null;
	}

	private @org.jspecify.annotations.Nullable String releaseStamp;
	private @org.jspecify.annotations.Nullable String announcementStamp;
	private @org.jspecify.annotations.Nullable String splashesStamp;
	private void refreshSplashes() {
		BackendClient current = ensureClient();
		if (current == null) {
			return;
		}
		worker.execute(() -> {
			try {
				com.google.gson.JsonObject result = current.splashes();
				List<String> found = new java.util.ArrayList<>();
				if (result.get("splashes") instanceof com.google.gson.JsonArray list) {
					for (com.google.gson.JsonElement element : list) {
						if (element.isJsonPrimitive() && found.size() < 30) {
							String text = element.getAsString();
							found.add(text.length() > 60 ? text.substring(0, 60) : text);
						}
					}
				}
				dev.orbit.cosmetic.client.OrbitSplashes.set(found);
			} catch (IOException | RuntimeException e) {
				OrbitClient.LOGGER.debug("Orbit Cloud: no splash texts ({})", e.toString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * What Orbit Cloud's live wait says changed (game thread): a new release (the update check runs at once), a new
	 * announcement (fetched at once) and this player's ban (applied at once). Orbit Cloud wakes the wait when any of
	 * them changes, so nothing is polled.
	 */
	public void signals(com.google.gson.@org.jspecify.annotations.Nullable JsonObject signals) {
		if (signals == null) {
			return;
		}
		String release = signals.get("release") instanceof com.google.gson.JsonPrimitive value ? value.getAsString() : "";
		if (releaseStamp != null && !release.equals(releaseStamp)) {
			dev.orbit.cosmetic.client.Updates.checkNow();
		}
		releaseStamp = release;
		String announcement = signals.get("announcement") instanceof com.google.gson.JsonPrimitive value ? value.getAsString() : "";
		if (!announcement.equals(announcementStamp)) {
			announcementStamp = announcement;
			refreshAnnouncement(true);
		}
		String splashStamp = signals.get("splashes") instanceof com.google.gson.JsonPrimitive value ? value.getAsString() : "";
		if (splashesStamp != null && !splashStamp.equals(splashesStamp)) {
			refreshSplashes();
		}
		splashesStamp = splashStamp;
		if (signals.has("ban")) {
			com.google.gson.JsonObject ban = signals.get("ban") instanceof com.google.gson.JsonObject object ? object : null;
			boolean isFull = ban != null && !"feature".equals(ban.has("kind") ? ban.get("kind").getAsString() : "");
			double until = ban != null && ban.has("until") && ban.get("until").isJsonPrimitive() ? ban.get("until").getAsDouble() : 0;
			if (ban != null || banned) {
				worker.execute(() -> applyBan(ban != null, isFull, until));
			}
		}
	}

	/** Asks Orbit Cloud for the announcement: once at the start, then whenever the live wait says it changed. */
	public void refreshAnnouncement() {
		refreshAnnouncement(false);
	}

	private void refreshAnnouncement(boolean changed) {
		long now = System.currentTimeMillis();
		if (announcementCheckedAt != 0 && !changed || !orbit.config().hasAcceptedTerms() || !orbit.config().useBackend) {
			return;
		}
		if (announcementCheckedAt == 0) {
			refreshSplashes(); // at the start, with the announcement
		}
		announcementCheckedAt = now;
		BackendClient current = ensureClient();
		if (current == null) {
			return;
		}
		worker.execute(() -> {
			try {
				com.google.gson.JsonObject result = current.announcement();
				if (result.get("announcement") instanceof com.google.gson.JsonObject shown && shown.has("text") && shown.has("until")) {
					announcementUntil = shown.get("until").getAsDouble();
					announcementText = shown.get("text").getAsString();
				} else {
					announcementText = null;
				}
			} catch (IOException | RuntimeException e) {
				OrbitClient.LOGGER.debug("Orbit Cloud: no announcement ({})", e.toString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	public void recommendedServers(Consumer<com.google.gson.JsonArray> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		if (current == null) {
			failed.accept(Component.translatable(orbit.config().hasAcceptedTerms() ? "orbit_cosmetic.social.unreachable" : "orbit_cosmetic.terms.needed").getString());
			return;
		}
		worker.execute(() -> {
			try {
				com.google.gson.JsonObject result = current.recommendedServers();
				com.google.gson.JsonArray servers = result.get("servers") instanceof com.google.gson.JsonArray array ? array : new com.google.gson.JsonArray();
				Minecraft.getInstance().execute(() -> done.accept(servers));
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> failed.accept(Component.translatable("orbit_cosmetic.social.unreachable").getString()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** Loads the catalog now. Callbacks run on the game thread. */
	public void loadCatalog(Consumer<Catalog> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		if (current == null) {
			failed.accept(Component.translatable(orbit.config().hasAcceptedTerms() ? "orbit_cosmetic.social.unreachable" : "orbit_cosmetic.terms.needed").getString());
			return;
		}
		worker.execute(() -> {
			try {
				Catalog loaded = current.featured();
				catalog = loaded;
				catalogFetchedAt = System.currentTimeMillis();
				Minecraft.getInstance().execute(() -> done.accept(loaded));
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> failed.accept(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Downloads a catalog item, checked against its SHA-256, and its settings file if it has one ({@code null}
	 * otherwise). Previews use the same files, so they are cached. Callbacks run on the game thread.
	 */
	public void downloadItem(CatalogItem item, BiConsumer<byte[], byte[]> done, Consumer<String> failed) {
		download(item, true, done, failed);
	}

	/** A catalog item's settings file only, for previews (its particles); {@code null} when it has none. */
	public void downloadSettings(CatalogItem item, Consumer<byte[]> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		if (current == null || !item.hasSettings()) {
			done.accept(null);
			return;
		}
		worker.execute(() -> {
			try {
				byte[] settings = current.featuredSettings(item.id(), SETTINGS_MAX_BYTES);
				Minecraft.getInstance().execute(() -> done.accept(settings));
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> failed.accept(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** A catalog item's model only, for showing it (from the download cache when it is there). */
	public void downloadModel(CatalogItem item, Consumer<byte[]> done, Consumer<String> failed) {
		download(item, false, (model, settings) -> done.accept(model), failed);
	}

	private void download(CatalogItem item, boolean withSettings, BiConsumer<byte[], byte[]> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		if (current == null) {
			failed.accept(Component.translatable(orbit.config().hasAcceptedTerms() ? "orbit_cosmetic.social.unreachable" : "orbit_cosmetic.terms.needed").getString());
			return;
		}
		AssetId id = AssetId.tryParse(item.sha256());
		int maxBytes = remotes.limits().maxFileBytes();
		worker.execute(() -> {
			try {
				byte[] bytes = id != null && orbit.cache().has(id) ? orbit.cache().read(id) : null;
				if (bytes == null) {
					bytes = current.download(item.sha256(), maxBytes);
				}
				if (id == null || !id.matches(bytes)) {
					Minecraft.getInstance().execute(() -> failed.accept("The download does not match the catalog"));
					return;
				}
				byte[] settings = withSettings && item.hasSettings() ? current.featuredSettings(item.id(), SETTINGS_MAX_BYTES) : null;
				byte[] model = bytes;
				Minecraft.getInstance().execute(() -> done.accept(model, settings));
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> failed.accept(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Reports a player to Orbit Cloud, signing in with the Minecraft account first if needed.
	 * {@code done} gets true for a new report and false if it replaced an earlier one. Callbacks run on the game thread.
	 */
	public void report(UUID player, String name, String reason, String note, List<String> cosmetics, Consumer<Boolean> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		Minecraft minecraft = Minecraft.getInstance();
		// Profiles are reported from the statistics page, anywhere; cosmetics from a world.
		if (current == null || minecraft.player == null && !"profile".equals(reason)) {
			failed.accept(Component.translatable(orbit.config().hasAcceptedTerms() ? "orbit_cosmetic.social.unreachable" : "orbit_cosmetic.terms.needed").getString());
			return;
		}
		net.minecraft.client.User user = minecraft.getUser();
		worker.execute(() -> {
			try {
				if (!"mojang".equals(current.health())) {
					minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.report.unreachable").getString()));
					return;
				}
				if (!current.signedIn() && !signIn(minecraft, user, current)) {
					minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.report.sign_in").getString()));
					return;
				}
				boolean created;
				try {
					created = current.report(player, name, reason, note, cosmetics);
				} catch (BackendClient.BackendException e) {
					if (e.status != 401 || !signIn(minecraft, user, current)) {
						throw e;
					}
					created = current.report(player, name, reason, note, cosmetics);
				}
				boolean result = created;
				minecraft.execute(() -> done.accept(result));
			} catch (BackendClient.BackendException e) {
				minecraft.execute(() -> failed.accept(e.detail.isEmpty() ? e.getMessage() : e.detail));
			} catch (IOException e) {
				minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.report.unreachable").getString()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	// ---------------------------------------------------------------- discovering other players

	private void query(Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || minecraft.player == null) {
			return;
		}
		UUID self = minecraft.player.getUUID();
		List<UUID> players = new ArrayList<>();
		List<String> names = new ArrayList<>();
		Map<String, UUID> byName = new HashMap<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			UUID id = info.getProfile().id();
			if (!id.equals(self) && players.size() < MAX_QUERY_PLAYERS) {
				players.add(id);
				String name = info.getProfile().name();
				if (name != null && !name.isEmpty()) {
					names.add(name);
					byName.put(name.toLowerCase(Locale.ROOT), id);
				}
			}
		}
		if (players.isEmpty()) {
			removeCloudPlayers();
			return;
		}
		BackendClient current = client;
		queryInFlight = true;
		int askedOn = server;
		worker.execute(() -> {
			List<BackendClient.PresenceEntry> entries;
			try {
				entries = current.queryPresence(players, names);
			} catch (IOException e) {
				entries = null;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			List<BackendClient.PresenceEntry> result = entries;
			Minecraft.getInstance().execute(() -> {
				if (askedOn != server) {
					return; // asked on the previous server; a new query is on its way
				}
				queryInFlight = false;
				if (active && connected && result != null) {
					apply(result, Set.copyOf(players), byName);
				}
			});
		});
	}

	private void apply(List<BackendClient.PresenceEntry> entries, Set<UUID> asked, Map<String, UUID> byName) {
		Set<UUID> seen = new HashSet<>();
		UUID self = signedInAs;
		double now = orbit.clockTime();
		for (BackendClient.PresenceEntry entry : entries) {
			// Map the entry to the player as this server knows them: same UUID on online-mode servers, the same
			// name on offline-mode servers (where in-game UUIDs differ from the real account's).
			UUID target = asked.contains(entry.player()) ? entry.player() : byName.get(entry.name().toLowerCase(Locale.ROOT));
			if (target == null || entry.player().equals(self) || entry.format() != EquipCodec.FORMAT || entry.equip().length() > 48_000) {
				continue;
			}
			List<EquippedCosmetic> cosmetics;
			try {
				cosmetics = EquipCodec.decode(Base64.getDecoder().decode(entry.equip()));
			} catch (IllegalArgumentException | ProtocolException e) {
				continue; // malformed data from Orbit Cloud is ignored
			}
			Wearer.Emote emote = null;
			EmoteSignal signal = entry.emote();
			AssetId emoteAsset = signal == null ? null : AssetId.tryParse(signal.sha256());
			if (emoteAsset != null) {
				emote = new Wearer.Emote(emoteAsset, signal.mobile(), signal.seq(), signal.particles(), now, entry.emoteAge());
			}
			seen.add(target);
			cloudPlayers.add(target);
			remotes.apply(target, cosmetics, emote);
			orbit.skins().setRemote(target, entry.skin());
		}
		for (UUID player : List.copyOf(cloudPlayers)) {
			if (!seen.contains(player)) {
				cloudPlayers.remove(player);
				remotes.remove(player);
				orbit.skins().setRemote(player, null);
			}
		}
	}

	private void removeCloudPlayers() {
		for (UUID player : cloudPlayers) {
			remotes.remove(player);
			orbit.skins().setRemote(player, null);
		}
		cloudPlayers.clear();
	}

	// ---------------------------------------------------------------- downloads

	/** Downloads a file another player uses. Called by {@link RemotePlayers} when it is not cached. */
	void requestAsset(AssetId id) {
		long now = System.currentTimeMillis();
		if (!active || downloading.contains(id) || retryAfter.getOrDefault(id, 0L) > now) {
			return;
		}
		downloading.add(id);
		BackendClient current = client;
		int maxBytes = remotes.limits().maxFileBytes();
		worker.execute(() -> {
			byte[] bytes;
			try {
				bytes = current.download(id.hex(), maxBytes);
			} catch (IOException e) {
				bytes = null;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			byte[] result = bytes;
			Minecraft.getInstance().execute(() -> {
				downloading.remove(id);
				if (!active) {
					return;
				}
				if (result == null || !id.matches(result)) {
					retryAfter.put(id, System.currentTimeMillis() + DOWNLOAD_RETRY_MS);
					return;
				}
				remotes.acceptAsset(id, result);
			});
		});
	}

	public void shutdown() {
		worker.shutdownNow();
		socialWorker.shutdownNow();
		liveWorker.shutdownNow();
	}

	// ---------------------------------------------------------------- friends

	/** Friends calls have their own thread, so a slow upload never holds up what players wear. */
	private final ExecutorService socialWorker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Orbit friends");
		thread.setDaemon(true);
		return thread;
	});

	/** Waits for news from Orbit Cloud (see {@link #socialWait}): its own thread, as a wait lasts up to 25 seconds. */
	private final ExecutorService liveWorker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Orbit live");
		thread.setDaemon(true);
		return thread;
	});

	private interface SocialCall<T> {
		T run(BackendClient client) throws IOException, InterruptedException;
	}

	/**
	 * Runs a friends call signed in (signing in first if needed, and again once if the session ran out). Callbacks
	 * run on the game thread; {@code failed} gets a message to show.
	 */
	private <T> void social(SocialCall<T> call, Consumer<T> done, Consumer<String> failed) {
		social(socialWorker, call, done, failed);
	}

	private <T> void social(ExecutorService executor, SocialCall<T> call, Consumer<T> done, Consumer<String> failed) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!orbit.config().useBackend) {
			failed.accept(Component.translatable("orbit_cosmetic.social.cloud_off").getString());
			return;
		}
		BackendClient current = ensureClient();
		if (current == null) {
			failed.accept(Component.translatable(orbit.config().hasAcceptedTerms() ? "orbit_cosmetic.social.unreachable" : "orbit_cosmetic.terms.needed").getString());
			return;
		}
		net.minecraft.client.User user = minecraft.getUser();
		boolean dev = BackendClient.hasDevPlayer();
		executor.execute(() -> {
			try {
				if (!dev && !current.signedIn() && !signIn(minecraft, user, current)) {
					minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.social.sign_in").getString()));
					return;
				}
				T result;
				try {
					result = call.run(current);
				} catch (BackendClient.BackendException e) {
					if (e.status != 401 || dev || !signIn(minecraft, user, current)) {
						throw e;
					}
					result = call.run(current);
				}
				T finalResult = result;
				minecraft.execute(() -> done.accept(finalResult));
			} catch (BackendClient.BackendException e) {
				String message = e.detail.isBlank() ? e.getMessage() : e.detail;
				minecraft.execute(() -> failed.accept(message));
			} catch (IOException e) {
				minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.social.unreachable").getString()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** A JSON call to the friends API (path after /v1/social), signed in. */
	public void socialPutRaw(String path, byte[] data, Consumer<com.google.gson.JsonObject> done, Consumer<String> failed) {
		social(client -> client.socialPutRaw(path, data), done, failed);
	}

	public void socialCall(String method, String path, com.google.gson.JsonElement body, Consumer<com.google.gson.JsonObject> done, Consumer<String> failed) {
		social(client -> client.social(method, path, body), done, failed);
	}

	/**
	 * Waits until something of the player's changed on Orbit Cloud (a message, a friend request) or 25 seconds
	 * passed; {@code done} gets the new cursor, which differs from {@code cursor} when something changed.
	 */
	public void socialWait(int cursor, Consumer<com.google.gson.JsonObject> done, Consumer<String> failed) {
		social(liveWorker, client -> client.socialWait(cursor), done, failed);
	}

	/** Uploads an encrypted attachment for a message; {@code done} gets its ID. */
	public void socialUpload(byte[] data, Consumer<String> done, Consumer<String> failed) {
		social(client -> client.socialUpload(data), done, failed);
	}

	/** Downloads an encrypted attachment of a message. */
	/** Downloads a file of the friends API by its path (Orbit's pictures to every player). */
	public void socialDownloadPath(String path, Consumer<byte[]> done, Consumer<String> failed) {
		social(client -> client.download(path, 9 * 1024 * 1024, true), done, failed);
	}

	public void socialDownload(String id, Consumer<byte[]> done, Consumer<String> failed) {
		social(client -> client.socialDownload(id, 9 * 1024 * 1024), done, failed);
	}

	/**
	 * Makes sure Orbit Cloud has a model (to share it with a friend, who downloads it by its SHA-256): uploads it
	 * when it is not there yet. Callbacks on the game thread.
	 */
	public void socialEnsureUploaded(AssetId asset, byte[] bytes, String name, boolean emote, Runnable done, Consumer<String> failed) {
		social(client -> {
			if (!client.hasAsset(asset.hex())) {
				client.upload(asset.hex(), name, bytes, emote);
			}
			return Boolean.TRUE;
		}, ignored -> done.run(), failed);
	}

	/** Downloads a model a friend shared, checked against its SHA-256. */
	public void socialDownloadModel(String sha256, Consumer<byte[]> done, Consumer<String> failed) {
		social(client -> {
			byte[] bytes = client.download(sha256, remotes.limits().maxFileBytes());
			AssetId id = AssetId.tryParse(sha256);
			if (id == null || !id.matches(bytes)) {
				throw new IOException("The download does not match");
			}
			return bytes;
		}, done, failed);
	}

	// ---------------------------------------------------------------- helpers

	private interface Call {
		void run() throws IOException, InterruptedException;
	}

	private void quietly(Call call) {
		try {
			call.run();
		} catch (IOException e) {
			warnOnceAsync("Orbit Cloud request failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void warnOnceAsync(String message) {
		Minecraft.getInstance().execute(() -> warnOnce(message));
	}

	private void warnOnce(String message) {
		if (!warned) {
			warned = true;
			OrbitClient.LOGGER.warn(message);
		} else {
			OrbitClient.LOGGER.debug(message);
		}
	}
}
