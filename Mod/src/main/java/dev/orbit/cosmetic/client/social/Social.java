package dev.orbit.cosmetic.client.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Orbit's friends: the friend list, requests and blocked players (kept by Orbit Cloud), and messages, kept on this
 * computer as they arrive: Orbit Cloud deletes them 30 days after they were seen. A conversation's messages are
 * end-to-end encrypted (see {@link SocialCrypto}) when the player turns it on for it; otherwise they are sent as
 * they are, over HTTPS. Game thread only; network calls run in the background.
 */
public final class Social {
	/** Someone on the lists; {@code hosting} is the world a friend hosts (address, world, motd), or null. */
	public record Person(UUID uuid, String name, double lastSeen, boolean online, @Nullable JsonObject hosting) {
	}

	/**
	 * One message of a conversation; {@code content} is what was sent (see {@link #send}), null if unreadable here;
	 * {@code encrypted} when it was end-to-end encrypted.
	 */
	public record Entry(String id, UUID from, double at, @Nullable JsonObject content, boolean encrypted, boolean edited, boolean deleted) {
		public Entry(String id, UUID from, double at, @Nullable JsonObject content, boolean encrypted) {
			this(id, from, at, content, encrypted, false, false);
		}

		public boolean mine(UUID me) {
			return from.equals(me);
		}
	}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final long FAST_POLL_MS = 4000;
	private static final long SLOW_POLL_MS = 30_000;
	/** While Orbit Cloud tells us about news at once (see {@link #tick}), checking now and then is only a fallback. */
	private static final long LIVE_POLL_MS = 60_000;
	private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

	private List<Person> friends = List.of();
	private List<Person> incoming = List.of();
	private List<Person> outgoing = List.of();
	private List<Person> blocked = List.of();
	private final Set<UUID> blockedIds = new HashSet<>();
	private Map<UUID, Integer> unread = Map.of();
	/** Group chats the player is in, and the colours of friends' statistics pages (their chats are tinted with them). */
	private Map<UUID, Group> groups = Map.of();
	private Map<UUID, Integer> colors = Map.of();
	private double memberSince;
	private boolean loaded;
	private @Nullable String problem;
	private final Map<UUID, List<Entry>> chats = new HashMap<>();
	private List<Entry> broadcasts = new ArrayList<>();
	private final Set<String> seenBroadcasts = new HashSet<>();
	/** Conversations whose messages are end-to-end encrypted. */
	private final Set<UUID> encryptedChats = new HashSet<>();
	private final Map<UUID, Supplier<PlayerSkin>> skins = new HashMap<>();
	private final Set<UUID> skinsRequested = new HashSet<>();
	/** When a face's lookup may be tried again, and how many times it was tried. */
	private final Map<UUID, Long> skinRetryAt = new HashMap<>();
	private final Map<UUID, Integer> skinAttempts = new HashMap<>();
	/** The account this was loaded for (another account means other friends and keys). */
	private @Nullable UUID me;
	private SocialCrypto.@Nullable DeviceKey device;
	private boolean deviceRegistered;
	private double since;
	private long lastPoll;
	private long lastState;
	private boolean polling;
	/** The change cursor of Orbit Cloud's wait (-1 before the first), whether a wait is open, and when to retry. */
	private int cursor = -1;
	private boolean waiting;
	private boolean live;
	private long waitAgainAt;
	private long waitBackoff = 2000;
	private long lastStats;
	private boolean profilePulled;
	/** Account totals from Orbit Cloud (-1 before they are known), and what is not sent yet. */
	private long serverPlayed = -1;
	private long serverScreenshots = -1;
	private long pendingPlayed;
	private long pendingScreenshots;
	private boolean activitySeeded;
	private long lastActivity;
	private static final long ACTIVITY_EVERY_MS = 60_000;
	private final CloudWardrobe wardrobe = new CloudWardrobe();
	private static final long STATS_EVERY_MS = 10 * 60_000;
	/** The conversation shown on screen (its new messages are seen, not announced), or null. */
	private @Nullable UUID viewing;
	/** Changes whenever anything shown changed, so screens know to refresh. */
	private int version;

	public int version() {
		return version;
	}

	public boolean isLoaded() {
		return loaded;
	}

	public @Nullable String problem() {
		return problem;
	}

	public List<Person> friends() {
		return friends;
	}

	public List<Person> incoming() {
		return incoming;
	}

	public List<Person> outgoing() {
		return outgoing;
	}

	public List<Person> blocked() {
		return blocked;
	}

	public boolean isBlocked(UUID player) {
		return blockedIds.contains(player);
	}

	public int unread(UUID friend) {
		if (friend.equals(ORBIT)) {
			return (int) broadcasts.stream().filter(entry -> !seenBroadcasts.contains(entry.id())).count();
		}
		Group group = groups.get(friend);
		if (group != null) {
			return group.unread();
		}
		return unread.getOrDefault(friend, 0);
	}

	public int unreadTotal() {
		return unread.values().stream().mapToInt(Integer::intValue).sum() + groups.values().stream().mapToInt(Group::unread).sum() + unread(ORBIT);
	}

	public double memberSince() {
		return memberSince;
	}

	public @Nullable UUID me() {
		return me;
	}

	public @Nullable Person friend(UUID uuid) {
		if (uuid.equals(ORBIT)) {
			return broadcasts.isEmpty() ? null : ORBIT_PERSON;
		}
		Group group = groups.get(uuid);
		if (group != null) {
			return group.asPerson(me);
		}
		return friends.stream().filter(person -> person.uuid().equals(uuid)).findFirst().orElse(null);
	}

	public List<Entry> chat(UUID friend) {
		if (friend.equals(ORBIT)) {
			return broadcasts;
		}
		return chats.computeIfAbsent(friend, this::loadChat);
	}

	/**
	 * The last message with each friend, newest conversations first (friends without messages last); Orbit's
	 * messages to every player are one of them.
	 */
	public List<Person> conversations() {
		List<Person> list = new ArrayList<>(friends);
		groups.values().forEach(group -> list.add(group.asPerson(me)));
		if (!broadcasts.isEmpty()) {
			list.add(ORBIT_PERSON);
		}
		list.sort(Comparator.comparingDouble((Person person) -> {
			List<Entry> chat = chat(person.uuid());
			return chat.isEmpty() ? 0 : chat.getLast().at();
		}).reversed());
		return list;
	}

	/** Shows a conversation on screen: its messages are marked seen. */
	public void view(@Nullable UUID friend) {
		viewing = friend;
		if (ORBIT.equals(friend)) {
			if (broadcasts.stream().anyMatch(entry -> seenBroadcasts.add(entry.id()))) {
				saveMeta();
				version++;
			}
			return;
		}
		if (friend != null) {
			markSeen(friend);
			lastPoll = 0;
		}
	}

	// ---------------------------------------------------------------- group chats

	/** A group chat: its leader, colour, picture (0: the members' heads), members and unread messages. */
	public record Group(UUID id, String name, UUID owner, int color, int picture, List<Person> members, int unread) {
		public static final int MAX_MEMBERS = 10;

		/** Its name, or its members' names (one's own left out), "Alex, Sam, Kim". */
		public String title(@Nullable UUID me) {
			if (!name.isBlank()) {
				return name;
			}
			String names = String.join(", ", members.stream().filter(person -> !person.uuid().equals(me)).map(Person::name).toList());
			return names.isEmpty() ? "?" : names;
		}

		/** The others in the group. */
		public List<Person> others(@Nullable UUID me) {
			return members.stream().filter(person -> !person.uuid().equals(me)).toList();
		}

		/** As a conversation: online while any other member is, else last seen when the last one was. */
		Person asPerson(@Nullable UUID me) {
			List<Person> others = others(me);
			boolean online = others.stream().anyMatch(Person::online);
			double lastSeen = others.stream().mapToDouble(Person::lastSeen).max().orElse(0);
			return new Person(id, title(me), lastSeen, online, null);
		}

		public long onlineCount(@Nullable UUID me) {
			return others(me).stream().filter(Person::online).count();
		}

		Group withUnread(int value) {
			return new Group(id, name, owner, color, picture, members, value);
		}
	}

	public List<Group> groups() {
		return List.copyOf(groups.values());
	}

	public @Nullable Group group(@Nullable UUID id) {
		return id == null ? null : groups.get(id);
	}

	public boolean isGroup(@Nullable UUID id) {
		return id != null && groups.containsKey(id);
	}

	/** The colour a conversation is tinted with: the friend's statistics page, or the group's own; null for none. */
	public @Nullable Integer colorOf(UUID conversation) {
		Group group = groups.get(conversation);
		return group != null ? Integer.valueOf(group.color()) : colors.get(conversation);
	}

	/** Makes a group with these friends (and the player, who leads it). {@code done} gets its ID. */
	public void createGroup(List<UUID> members, String name, Consumer<UUID> done) {
		JsonObject body = new JsonObject();
		JsonArray list = new JsonArray();
		members.forEach(member -> list.add(member.toString()));
		body.add("members", list);
		body.addProperty("name", name);
		OrbitClient.get().backend().socialCall("POST", "/groups", body, created -> {
			refresh();
			done.accept(UUID.fromString(created.get("id").getAsString()));
		}, error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}

	private void groupCall(String method, String path, @Nullable JsonObject body) {
		OrbitClient.get().backend().socialCall(method, path, body, ok -> refresh(),
			error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}

	public void renameGroup(UUID group, String name) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		groupCall("PATCH", "/groups/" + group, body);
	}

	public void setGroupColor(UUID group, int color) {
		JsonObject body = new JsonObject();
		body.addProperty("color", color & 0xFFFFFF);
		groupCall("PATCH", "/groups/" + group, body);
	}

	public void addGroupMember(UUID group, UUID player) {
		JsonObject body = new JsonObject();
		body.addProperty("player", player.toString());
		groupCall("POST", "/groups/" + group + "/members", body);
	}

	/** Removes a member (the leader), or leaves the group (one's own UUID). */
	public void removeGroupMember(UUID group, UUID player) {
		groupCall("DELETE", "/groups/" + group + "/members/" + player, null);
	}

	public void makeGroupLeader(UUID group, UUID player) {
		JsonObject body = new JsonObject();
		body.addProperty("player", player.toString());
		groupCall("POST", "/groups/" + group + "/leader", body);
	}

	/** The group's picture from a screenshot (the middle square, made small), or back to the heads with null. */
	public void setGroupPicture(UUID group, @Nullable Path screenshot) {
		if (screenshot == null) {
			groupCall("DELETE", "/groups/" + group + "/picture", null);
			return;
		}
		Util.ioPool().execute(() -> {
			try {
				java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(screenshot.toFile());
				int side = Math.min(image.getWidth(), image.getHeight());
				java.awt.image.BufferedImage square = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = square.createGraphics();
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(image, 0, 0, 128, 128, (image.getWidth() - side) / 2, (image.getHeight() - side) / 2,
					(image.getWidth() + side) / 2, (image.getHeight() + side) / 2, null);
				g.dispose();
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				javax.imageio.ImageIO.write(square, "png", out);
				Minecraft.getInstance().execute(() -> OrbitClient.get().backend().socialPutRaw("/groups/" + group + "/picture", out.toByteArray(), ok -> refresh(),
					error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error))));
			} catch (IOException | RuntimeException e) {
				Minecraft.getInstance().execute(() -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(String.valueOf(e.getMessage()))));
			}
		});
	}

	private final Map<String, net.minecraft.resources.Identifier> groupPictures = new HashMap<>();
	private final Set<String> loadingPictures = new HashSet<>();

	/** The group's picture as a texture (downloaded the first time), or null while it has none or it loads. */
	public net.minecraft.resources.@Nullable Identifier groupPicture(Group group) {
		if (group.picture() == 0) {
			return null;
		}
		String key = group.id() + "/" + group.picture();
		net.minecraft.resources.Identifier known = groupPictures.get(key);
		if (known != null || !loadingPictures.add(key)) {
			return known;
		}
		OrbitClient.get().backend().socialDownloadPath("/v1/social/groups/" + group.id() + "/picture", png -> Minecraft.getInstance().execute(() -> {
			try {
				com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(png);
				net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "group/" + group.id() + "/" + group.picture());
				Minecraft.getInstance().getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Orbit group " + group.id(), image));
				groupPictures.put(key, id);
			} catch (IOException | RuntimeException e) {
				OrbitClient.LOGGER.debug("Friends: group picture not shown: {}", e.toString());
			}
		}), error -> {
		});
		return null;
	}

	// ---------------------------------------------------------------- Orbit's messages to every player

	/** The conversation with Orbit: messages the Orbit team sends to every player (o!sendall). */
	public static final UUID ORBIT = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
	private static final Person ORBIT_PERSON = new Person(ORBIT, "Orbit", 0, false, null);

	public static boolean isOrbit(@Nullable UUID player) {
		return ORBIT.equals(player);
	}

	/** Orbit's messages as Orbit Cloud has them now (a taken-back one disappears); new ones pop up. */
	private void applyBroadcasts(JsonArray list) {
		List<Entry> entries = new ArrayList<>();
		for (JsonElement element : list) {
			JsonObject broadcast = element.getAsJsonObject();
			String id = broadcast.get("id").getAsString();
			JsonObject content = new JsonObject();
			content.addProperty("text", broadcast.has("text") ? broadcast.get("text").getAsString() : "");
			if (broadcast.has("picture") && broadcast.get("picture").getAsBoolean()) {
				JsonObject picture = new JsonObject();
				picture.addProperty("id", id);
				picture.addProperty("broadcast", true);
				content.add("image", picture);
			}
			entries.add(new Entry(id, ORBIT, broadcast.get("at").getAsDouble(), content, false));
		}
		entries.sort(Comparator.comparingDouble(Entry::at));
		if (entries.equals(broadcasts)) {
			return;
		}
		// One popup: the newest message not seen yet, if it is recent (not the history of a new computer).
		Entry newest = null;
		for (Entry entry : entries) {
			if (!seenBroadcasts.contains(entry.id()) && broadcasts.stream().noneMatch(old -> old.id().equals(entry.id()))) {
				if (ORBIT.equals(viewing)) {
					seenBroadcasts.add(entry.id());
				} else {
					newest = entry;
				}
			}
		}
		if (newest != null && newest.at() > System.currentTimeMillis() / 1000.0 - 3 * 86400) {
			SocialPopups.message(ORBIT, "Orbit", newest.content());
		}
		broadcasts = entries;
		saveMeta();
		version++;
	}

	// ---------------------------------------------------------------- the account

	private Path folder() {
		return OrbitClient.get().paths().root().resolve(".social").resolve(String.valueOf(me));
	}

	/**
	 * Follows the signed-in account; called every tick. From launch on, a wait is always open on Orbit Cloud: it
	 * answers as soon as a message or a friend request arrives, which is then fetched at once. Orbit Cloud is also
	 * checked now and then, more often while the wait does not work.
	 */
	public void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		UUID account = minecraft.getUser().getProfileId();
		if (account == null || !OrbitClient.get().config().useBackend || !OrbitClient.get().config().hasAcceptedTerms()) {
			return;
		}
		if (!account.equals(me)) {
			switchAccount(account);
		}
		long now = System.currentTimeMillis();
		if (!waiting && device != null && now >= waitAgainAt) {
			waitForNews(account);
		}
		if (loaded && now - lastStats > STATS_EVERY_MS) {
			shareStats();
		}
		if (loaded) {
			wardrobe.tick();
			if (now - lastActivity > ACTIVITY_EVERY_MS && (pendingPlayed > 0 || pendingScreenshots > 0 || !activitySeeded)) {
				reportActivity();
			}
		}
		if (now - lastState > SLOW_POLL_MS) {
			lastState = now;
			refresh();
		}
		long interval = live ? LIVE_POLL_MS : viewing != null || dev.orbit.cosmetic.client.compat.Compat.screen() instanceof SocialScreen ? FAST_POLL_MS : SLOW_POLL_MS;
		if (!polling && now - lastPoll > interval && device != null) {
			lastPoll = now;
			poll();
		}
	}

	private void waitForNews(UUID account) {
		waiting = true;
		OrbitClient.get().backend().socialWait(cursor, answer -> {
			waiting = false;
			if (!account.equals(me)) {
				return;
			}
			int next = answer.get("cursor").getAsInt();
			OrbitClient.get().backend().signals(answer.get("signals") instanceof JsonObject signals ? signals : null);
			live = true;
			waitBackoff = 2000;
			if (next != cursor) {
				cursor = next;
				// Fetched on the next tick (after the check under way, if there is one).
				lastPoll = 0;
				lastState = 0;
			}
		}, error -> {
			waiting = false;
			live = false;
			waitAgainAt = System.currentTimeMillis() + waitBackoff;
			waitBackoff = Math.min(60_000, waitBackoff * 2);
		});
	}

	private void switchAccount(UUID account) {
		me = account;
		friends = incoming = outgoing = blocked = List.of();
		blockedIds.clear();
		unread = Map.of();
		chats.clear();
		loaded = false;
		deviceRegistered = false;
		lastState = 0;
		lastPoll = 0;
		cursor = -1;
		live = false;
		waitAgainAt = 0;
		profilePulled = false;
		serverPlayed = -1;
		serverScreenshots = -1;
		activitySeeded = false;
		wardrobe.reset();
		try {
			device = SocialCrypto.deviceKey(folder().resolve("device.json"));
			JsonObject meta = readJson(folder().resolve("meta.json"));
			since = meta != null && meta.has("since") ? meta.get("since").getAsDouble() : 0;
			encryptedChats.clear();
			seenBroadcasts.clear();
			broadcasts = new ArrayList<>();
			if (meta != null && meta.get("broadcasts_seen") instanceof JsonArray seen) {
				seen.forEach(id -> seenBroadcasts.add(id.getAsString()));
			}
			if (meta != null && meta.get("encrypted") instanceof JsonArray encrypted) {
				encrypted.forEach(friend -> encryptedChats.add(UUID.fromString(friend.getAsString())));
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Friends: no key for this computer", e);
			device = null;
		}
		version++;
	}

	/** Reloads friends, requests and blocked players now. */
	public void refresh() {
		lastState = System.currentTimeMillis();
		OrbitClient.get().backend().socialCall("GET", "/state", null, this::applyState, error -> {
			problem = error;
			version++;
		});
		UUID account = me;
		OrbitClient.get().backend().socialCall("GET", "/broadcasts", null, result -> {
			if (java.util.Objects.equals(account, me) && result.get("broadcasts") instanceof JsonArray list) {
				applyBroadcasts(list);
			}
		}, error -> {
		});
		if (device != null && !deviceRegistered) {
			JsonObject body = new JsonObject();
			body.addProperty("id", device.id());
			body.addProperty("public_key", device.publicKey());
			OrbitClient.get().backend().socialCall("PUT", "/devices", body, ok -> deviceRegistered = true, error -> OrbitClient.LOGGER.warn("Friends: {}", error));
		}
	}

	private void applyState(JsonObject state) {
		boolean first = !loaded;
		problem = null;
		loaded = true;
		if (first) {
			// The connection made at launch: Orbit Cloud is there.
			OrbitClient.get().backend().announceConnected();
			pullProfile();
		}
		Set<UUID> known = new HashSet<>();
		incoming.forEach(person -> known.add(person.uuid()));
		Set<UUID> wasOnline = new HashSet<>();
		friends.forEach(person -> {
			if (person.online()) {
				wasOnline.add(person.uuid());
			}
		});
		List<Person> before = friends;
		friends = people(state.getAsJsonArray("friends"));
		if (!first && OrbitClient.get().config().friendOnlineNotifications) {
			for (Person person : friends) {
				boolean knownBefore = before.stream().anyMatch(p -> p.uuid().equals(person.uuid()));
				if (person.online() && knownBefore && !wasOnline.contains(person.uuid())) {
					OrbitClient.LOGGER.info("Friends: {} is online", person.name());
					Notifications.info(Component.translatable("orbit_cosmetic.social.friend_online", person.name()), Component.empty());
				}
			}
		}
		incoming = people(state.getAsJsonArray("incoming"));
		for (Person person : incoming) {
			if (!first && !known.contains(person.uuid())) {
				SocialPopups.request(person.uuid(), person.name());
			}
		}
		outgoing = people(state.getAsJsonArray("outgoing"));
		blocked = people(state.getAsJsonArray("blocked"));
		Map<UUID, Integer> pageColors = new HashMap<>();
		if (state.get("friends") instanceof JsonArray list) {
			for (JsonElement element : list) {
				JsonObject json = element.getAsJsonObject();
				if (json.get("color") instanceof JsonElement color && color.isJsonPrimitive()) {
					pageColors.put(UUID.fromString(json.get("uuid").getAsString()), color.getAsInt() & 0xFFFFFF);
				}
			}
		}
		colors = pageColors;
		Map<UUID, Group> found = new LinkedHashMap<>();
		if (state.get("groups") instanceof JsonArray list) {
			for (JsonElement element : list) {
				JsonObject json = element.getAsJsonObject();
				UUID id = UUID.fromString(json.get("id").getAsString());
				found.put(id, new Group(id, json.get("name").getAsString(), UUID.fromString(json.get("owner").getAsString()), json.get("color").getAsInt() & 0xFFFFFF,
					json.get("picture").getAsInt(), people(json.getAsJsonArray("members")), id.equals(viewing) ? 0 : json.get("unread").getAsInt()));
			}
		}
		groups = found;
		blockedIds.clear();
		blocked.forEach(person -> blockedIds.add(person.uuid()));
		Map<UUID, Integer> counts = new HashMap<>();
		if (state.get("unread") instanceof JsonObject unreadJson) {
			for (Map.Entry<String, JsonElement> entry : unreadJson.entrySet()) {
				counts.put(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
			}
		}
		unread = counts;
		if (state.get("me") instanceof JsonObject meJson && meJson.has("first_seen") && !meJson.get("first_seen").isJsonNull()) {
			memberSince = meJson.get("first_seen").getAsDouble();
		}
		version++;
	}

	private static List<Person> people(JsonArray array) {
		List<Person> list = new ArrayList<>();
		if (array == null) {
			return list;
		}
		for (JsonElement element : array) {
			JsonObject json = element.getAsJsonObject();
			double lastSeen = json.has("last_seen") && !json.get("last_seen").isJsonNull() ? json.get("last_seen").getAsDouble() : 0;
			boolean online = json.has("online") && json.get("online").getAsBoolean();
			JsonObject hosting = json.get("hosting") instanceof JsonObject world && world.has("address") ? world : null;
			list.add(new Person(UUID.fromString(json.get("uuid").getAsString()), json.get("name").getAsString(), lastSeen, online, hosting));
		}
		return list;
	}

	// ---------------------------------------------------------------- statistics

	/**
	 * Shares this player's statistics page with their friends (or takes it off Orbit Cloud when they turned sharing
	 * off). Done when Orbit Cloud is first reached, every 10 minutes, and when the page's colour changes.
	 */
	public void shareStats() {
		if (!loaded) {
			return;
		}
		if (!profilePulled) {
			// Orbit Cloud's profile comes first: this computer's settings may be older.
			return;
		}
		lastStats = System.currentTimeMillis();
		OrbitClient orbit = OrbitClient.get();
		JsonObject body = new JsonObject();
		StatsCounts counts = counts();
		body.addProperty("shared", orbit.config().shareStatistics);
		body.addProperty("outfit_name", orbit.config().profileOutfit);
		body.addProperty("cosmetics", counts.cosmetics());
		body.addProperty("emotes", counts.emotes());
		body.addProperty("body_parts", counts.bodyParts());
		body.addProperty("color", orbit.config().statsColor);
		body.addProperty("description", orbit.config().profileDescription);
		body.addProperty("pronouns", orbit.config().profilePronouns);
		body.add("outfit", Profile.outfitJson());
		// The outfit's files first, so friends can download them.
		Profile.uploadOutfit(() -> orbit.backend().socialCall("PUT", "/stats", body, ok -> {
		}, error -> OrbitClient.LOGGER.debug("Friends: statistics not shared: {}", error)));
	}

	/**
	 * The profile as Orbit Cloud keeps it (the same on every computer of the account): into this computer's settings.
	 * Without one there yet, this computer's is shared.
	 */
	private void pullProfile() {
		UUID account = me;
		if (account == null) {
			return;
		}
		statsOf(account, page -> {
			if (!account.equals(me)) {
				return;
			}
			var config = OrbitClient.get().config();
			if (page.get("stats") instanceof JsonObject stats && stats.has("description")) {
				config.profileDescription = text(stats, "description");
				config.profilePronouns = text(stats, "pronouns");
				config.profileOutfit = text(stats, "outfit_name");
				config.shareStatistics = !stats.has("shared") || stats.get("shared").getAsBoolean();
				if (page.get("color") instanceof JsonElement color && color.isJsonPrimitive()) {
					config.statsColor = color.getAsInt() & 0xFFFFFF;
				}
				config.save();
			}
			if (page.get("stats") instanceof JsonObject stats) {
				serverPlayed = stats.has("played_seconds") ? stats.get("played_seconds").getAsLong() : -1;
				serverScreenshots = stats.has("screenshots") ? stats.get("screenshots").getAsLong() : -1;
			}
			profilePulled = true;
			shareStats();
		}, error -> OrbitClient.LOGGER.debug("Friends: profile not loaded: {}", error));
	}

	private static String text(JsonObject json, String key) {
		return json.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsString() : "";
	}

	/** Time played in a world, counted by the game (sent to Orbit Cloud, which adds it up for the account). */
	public void addPlayed(long seconds) {
		pendingPlayed += seconds;
	}

	/** A screenshot was taken. */
	public void addScreenshot() {
		pendingScreenshots++;
	}

	/** The account's time played: Orbit Cloud's total (with what is not sent yet), or this computer's before that. */
	public long playedSeconds() {
		return serverPlayed >= 0 ? serverPlayed + pendingPlayed : OrbitClient.get().state().playedSeconds();
	}

	/** The account's screenshots taken, like {@link #playedSeconds}; this computer's screenshots before that. */
	public long screenshotsTaken() {
		return serverScreenshots >= 0 ? serverScreenshots + pendingScreenshots : counts().screenshots();
	}

	/**
	 * Sends the time played and screenshots taken since the last report. The first report of a game also offers this
	 * computer's totals, which Orbit Cloud takes only while it has none for the account.
	 */
	private void reportActivity() {
		lastActivity = System.currentTimeMillis();
		long played = Math.min(pendingPlayed, 300);
		int shots = (int) Math.min(pendingScreenshots, 50);
		JsonObject body = new JsonObject();
		body.addProperty("played_seconds", played);
		body.addProperty("screenshots", shots);
		if (!activitySeeded) {
			body.addProperty("seed_played_seconds", OrbitClient.get().state().playedSeconds());
			body.addProperty("seed_screenshots", counts().screenshots());
		}
		UUID account = me;
		OrbitClient.get().backend().socialCall("POST", "/stats/activity", body, totals -> {
			if (!java.util.Objects.equals(account, me)) {
				return;
			}
			activitySeeded = true;
			pendingPlayed -= played;
			pendingScreenshots -= shots;
			serverPlayed = totals.get("played_seconds").getAsLong();
			serverScreenshots = totals.get("screenshots").getAsLong();
		}, error -> OrbitClient.LOGGER.debug("Friends: activity not sent: {}", error));
	}

	/** What is in this player's folders. */
	public record StatsCounts(int screenshots, int cosmetics, int emotes, int bodyParts) {
	}

	public static StatsCounts counts() {
		OrbitClient orbit = OrbitClient.get();
		int bodyParts = (int) orbit.library().entries().stream().filter(entry -> entry.isBodyPart()).count();
		return new StatsCounts(orbit.screenshots().list().size(), orbit.library().entries().size() - bodyParts, orbit.emotes().emotes().size(), bodyParts);
	}

	/** A friend's statistics page (or one's own) from Orbit Cloud. */
	public void statsOf(UUID player, Consumer<JsonObject> done, Consumer<String> failed) {
		OrbitClient.get().backend().socialCall("GET", "/stats/" + player, null, page -> {
			if (player.equals(me)) {
				applyRestrictions(page);
			}
			done.accept(page);
		}, failed);
	}

	private Set<String> restricted = Set.of();

	/**
	 * What the player's ban does not let them put on their statistics page ("description", "pronouns", "outfit"):
	 * Orbit Cloud wiped it and refuses it until the ban ends.
	 */
	public Set<String> restricted() {
		return restricted;
	}

	/** One's own page says what a ban takes away: this computer forgets it too, so it is not sent again later. */
	private void applyRestrictions(JsonObject page) {
		Set<String> now = new HashSet<>();
		if (page.get("restricted") instanceof JsonArray list) {
			list.forEach(element -> now.add(element.getAsString()));
		}
		var config = OrbitClient.get().config();
		boolean changed = false;
		if (now.contains("description") && !config.profileDescription.isEmpty()) {
			config.profileDescription = "";
			changed = true;
		}
		if (now.contains("pronouns") && !config.profilePronouns.isEmpty()) {
			config.profilePronouns = "";
			changed = true;
		}
		if (now.contains("outfit") && !config.profileOutfit.isEmpty()) {
			config.profileOutfit = "";
			changed = true;
		}
		if (changed) {
			config.save();
		}
		restricted = Set.copyOf(now);
	}

	// ---------------------------------------------------------------- friend requests and blocks

	public void request(String player, Runnable done) {
		JsonObject body = new JsonObject();
		body.addProperty("player", player.trim());
		OrbitClient.get().backend().socialCall("POST", "/requests", body, result -> {
			String outcome = result.get("result").getAsString();
			String name = result.getAsJsonObject("player").get("name").getAsString();
			Notifications.success(Component.translatable("orbit_cosmetic.social.request." + outcome, name), Component.empty());
			refresh();
			done.run();
		}, error -> Notifications.error(Component.translatable("orbit_cosmetic.social.request.failed"), Component.literal(error)));
	}

	public void accept(UUID player) {
		call("POST", "/requests/" + player + "/accept", null);
	}

	/** Declines or cancels a request, or removes a friend. */
	public void remove(UUID player) {
		call("DELETE", "/friends/" + player, null);
	}

	public void block(UUID player) {
		call("PUT", "/blocks/" + player, null);
	}

	public void unblock(UUID player) {
		call("DELETE", "/blocks/" + player, null);
	}

	private void call(String method, String path, @Nullable JsonElement body) {
		OrbitClient.get().backend().socialCall(method, path, body, ok -> refresh(),
			error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}

	/**
	 * The Minecraft friend list of this account (Minecraft 26.2 and later), or null where Minecraft has none.
	 * Read from the game's own friend service.
	 */
	public static @Nullable List<UUID> minecraftFriends() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			java.lang.reflect.Field field = Minecraft.class.getDeclaredField("remoteFriendListUpdateHandler");
			field.setAccessible(true);
			Object handler = field.get(minecraft);
			Object data = handler.getClass().getMethod("getLatestFriendData").invoke(handler);
			List<?> list = (List<?>) data.getClass().getMethod("friends").invoke(data);
			List<UUID> result = new ArrayList<>();
			for (Object friend : list) {
				result.add((UUID) friend.getClass().getMethod("profileId").invoke(friend));
			}
			return result;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Sends friend requests to the Orbit players among the Minecraft friend list. */
	public void importMinecraftFriends() {
		List<UUID> list = minecraftFriends();
		if (list == null || list.isEmpty()) {
			Notifications.warning(Component.translatable("orbit_cosmetic.social.import.none"), Component.empty());
			return;
		}
		JsonObject body = new JsonObject();
		JsonArray players = new JsonArray();
		list.forEach(uuid -> players.add(uuid.toString()));
		body.add("players", players);
		OrbitClient.get().backend().socialCall("POST", "/import", body, result -> {
			Notifications.success(Component.translatable("orbit_cosmetic.social.import.done", result.get("requested").getAsInt() + result.get("accepted").getAsInt(),
				result.get("not_orbit").getAsInt()), Component.empty());
			refresh();
		}, error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}

	// ---------------------------------------------------------------- messages

	private void poll() {
		if (device == null || me == null) {
			return;
		}
		polling = true;
		UUID account = me;
		OrbitClient.get().backend().socialCall("GET", "/messages?device=" + device.id() + "&since=" + since, null, result -> {
			polling = false;
			if (!account.equals(me)) {
				return;
			}
			receive(result.getAsJsonArray("messages"));
		}, error -> polling = false);
	}

	private void receive(JsonArray messages) {
		if (messages == null || messages.isEmpty()) {
			return;
		}
		Set<UUID> changed = new HashSet<>();
		// The first sync of this computer brings the history: no notifications for it.
		boolean history = since == 0;
		Map<UUID, UUID> senders = new HashMap<>();
		Map<UUID, JsonObject> news = new LinkedHashMap<>();
		for (JsonElement element : messages) {
			JsonObject message = element.getAsJsonObject();
			UUID from = UUID.fromString(message.get("from").getAsString());
			UUID to = UUID.fromString(message.get("to").getAsString());
			// A message to a group has the group as recipient; to or from a friend, the friend is the conversation.
			UUID friend = from.equals(me) || !to.equals(me) ? to : from;
			double at = message.get("at").getAsDouble();
			// Sent, edited or deleted since the last look: the next look starts after the latest change.
			since = Math.max(since, message.has("changed") && !message.get("changed").isJsonNull() ? message.get("changed").getAsDouble() : at);
			String id = message.get("id").getAsString();
			List<Entry> chat = chat(friend);
			boolean deleted = message.has("deleted") && !message.get("deleted").isJsonNull();
			boolean edited = message.has("edited") && !message.get("edited").isJsonNull();
			boolean plain = message.has("plain") && !message.get("plain").isJsonNull();
			JsonObject content = deleted ? null : sanitize(plain ? parsePlain(message.get("plain").getAsString()) : decrypt(message));
			Entry entry = new Entry(id, from, at, content, !plain && !deleted, edited && !deleted, deleted);
			int known = indexOf(chat, id);
			if (known >= 0) {
				// An edit or a deletion of a message this computer has.
				Entry old = chat.get(known);
				chat.set(known, deleted ? new Entry(id, from, at, null, old.encrypted(), false, true) : new Entry(id, from, at, content, old.encrypted(), edited, false));
				changed.add(friend);
				continue;
			}
			chat.add(entry);
			chat.sort(Comparator.comparingDouble(Entry::at));
			changed.add(friend);
			boolean seen = message.has("seen") && !message.get("seen").isJsonNull();
			if (!history && !seen && !deleted && content != null && !from.equals(me) && !friend.equals(viewing)) {
				news.put(friend, content);
				senders.put(friend, from);
			}
		}
		// One notification per friend: their latest message.
		news.forEach((friend, content) -> {
			Person person = friend(friend);
			Group group = groups.get(friend);
			UUID sender = senders.get(friend);
			String name = person == null ? "?" : person.name();
			if (group != null && sender != null) {
				// "Sam in Builders"
				String who = group.members().stream().filter(member -> member.uuid().equals(sender)).map(Person::name).findFirst().orElse("?");
				name = Component.translatable("orbit_cosmetic.social.group.from", who, group.title(me)).getString();
			}
			SocialPopups.message(friend, name, content);
		});
		for (UUID friend : changed) {
			saveChat(friend);
		}
		saveMeta();
		if (viewing != null && changed.contains(viewing)) {
			markSeen(viewing);
		}
		lastState = 0;
		version++;
	}

	private static final java.util.regex.Pattern HEX32 = java.util.regex.Pattern.compile("[0-9a-f]{32}");
	private static final java.util.regex.Pattern HEX64 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

	/**
	 * A friend's message as the game reads it: only the parts it knows (text, picture, cosmetic, invite), each with
	 * the fields it needs and of the right type, so nothing a friend sends can trip the screens up. Null when
	 * nothing usable is left (shown as unreadable).
	 */
	static @Nullable JsonObject sanitize(@Nullable JsonObject content) {
		if (content == null) {
			return null;
		}
		JsonObject clean = new JsonObject();
		String text = string(content, "text", 4000);
		if (text != null) {
			clean.addProperty("text", text);
		}
		if (content.get("image") instanceof JsonObject image) {
			String id = string(image, "id", 32);
			String key = string(image, "key", 200);
			if (id != null && HEX32.matcher(id).matches() && key != null && isBase64(key)) {
				JsonObject picture = new JsonObject();
				picture.addProperty("id", id);
				picture.addProperty("key", key);
				clean.add("image", picture);
			}
		}
		if (content.get("cosmetic") instanceof JsonObject cosmetic) {
			String name = string(cosmetic, "name", 128);
			String kind = string(cosmetic, "kind", 16);
			String sha = string(cosmetic, "sha256", 64);
			if (name != null && sha != null && HEX64.matcher(sha).matches()) {
				JsonObject item = new JsonObject();
				item.addProperty("name", name);
				item.addProperty("kind", kind != null && List.of("cosmetic", "emote", "bodypart").contains(kind) ? kind : "cosmetic");
				item.addProperty("sha256", sha);
				String settings = string(cosmetic, "settings", 48 * 1024);
				if (settings != null) {
					item.addProperty("settings", settings);
				}
				clean.add("cosmetic", item);
			}
		}
		if (content.get("invite") instanceof JsonObject invite) {
			String address = string(invite, "address", 255);
			if (address != null && !address.isBlank()) {
				JsonObject world = new JsonObject();
				world.addProperty("address", address);
				for (String key : List.of("world", "motd")) {
					String value = string(invite, key, 512);
					if (value != null) {
						world.addProperty(key, value);
					}
				}
				String icon = string(invite, "icon", 64 * 1024);
				if (icon != null && isBase64(icon)) {
					world.addProperty("icon", icon);
				}
				clean.add("invite", world);
			}
		}
		return clean.isEmpty() ? null : clean;
	}

	private static @Nullable String string(JsonObject json, String key, int maxLength) {
		return json.get(key) instanceof JsonElement value && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			&& value.getAsString().length() <= maxLength ? value.getAsString() : null;
	}

	private static boolean isBase64(String text) {
		try {
			Base64.getDecoder().decode(text);
			return !text.isEmpty();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static @Nullable JsonObject parsePlain(String json) {
		try {
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- end-to-end encryption, per conversation

	/** Whether messages to this friend are end-to-end encrypted (off unless the player turned it on). */
	public boolean isEncrypted(UUID friend) {
		// Group chats are never end-to-end encrypted.
		return encryptedChats.contains(friend) && !groups.containsKey(friend);
	}

	public void setEncrypted(UUID friend, boolean encrypted) {
		if (encrypted) {
			encryptedChats.add(friend);
		} else {
			encryptedChats.remove(friend);
		}
		saveMeta();
		version++;
	}

	private @Nullable JsonObject decrypt(JsonObject message) {
		if (device == null || !message.has("key") || message.get("key").isJsonNull()) {
			return null;
		}
		try {
			byte[] key = SocialCrypto.unwrap(message.get("key").getAsString(), device);
			byte[] plain = SocialCrypto.open(Base64.getDecoder().decode(message.get("ciphertext").getAsString()), key);
			return JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (GeneralSecurityException | RuntimeException e) {
			OrbitClient.LOGGER.warn("Friends: could not decrypt message {}", message.get("id"));
			return null;
		}
	}

	/** One line about a message, for notifications and the conversation list. */
	public static String preview(JsonObject content) {
		String text = content.has("text") ? content.get("text").getAsString().strip() : "";
		if (!text.isEmpty()) {
			return text.length() > 80 ? text.substring(0, 80) + "…" : text;
		}
		if (content.has("image")) {
			return Component.translatable("orbit_cosmetic.social.preview.image").getString();
		}
		if (content.has("cosmetic")) {
			return Component.translatable("orbit_cosmetic.social.preview.cosmetic", content.getAsJsonObject("cosmetic").get("name").getAsString()).getString();
		}
		if (content.has("invite")) {
			return Component.translatable("orbit_cosmetic.social.preview.invite").getString();
		}
		return "";
	}

	private void markSeen(UUID friend) {
		Group group = groups.get(friend);
		if (group != null) {
			if (group.unread() > 0) {
				Map<UUID, Group> updated = new LinkedHashMap<>(groups);
				updated.put(friend, group.withUnread(0));
				groups = updated;
				JsonObject body = new JsonObject();
				body.addProperty("until", System.currentTimeMillis() / 1000.0 + 1);
				OrbitClient.get().backend().socialCall("POST", "/groups/" + friend + "/seen", body, ok -> {
				}, error -> {
				});
				version++;
			}
			return;
		}
		if (unread.getOrDefault(friend, 0) == 0) {
			return;
		}
		Map<UUID, Integer> counts = new HashMap<>(unread);
		counts.remove(friend);
		unread = counts;
		JsonObject body = new JsonObject();
		body.addProperty("friend", friend.toString());
		body.addProperty("until", since + 1);
		OrbitClient.get().backend().socialCall("POST", "/messages/seen", body, ok -> {
		}, error -> {
		});
		version++;
	}

	/**
	 * Sends a message to friends: {@code content} holds "text" and maybe "image" (filled in here from
	 * {@code image}), "cosmetic" or "invite". Encrypted for every computer of the sender and of each friend.
	 */
	public void send(List<UUID> to, JsonObject content, @Nullable Path image, Runnable done) {
		if (device == null || me == null) {
			Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.translatable("orbit_cosmetic.social.sign_in"));
			return;
		}
		// A ban keeps friends and messages, but limits sharing: no cosmetics (any ban), no pictures (a complete ban).
		var backend = OrbitClient.get().backend();
		boolean hasPicture = image != null || content.has("image");
		if (content.has("cosmetic") && backend.isBanned() || hasPicture && backend.isFullBan()) {
			Notifications.warning(Component.translatable("orbit_cosmetic.social.banned_sharing"),
				Component.translatable(hasPicture ? "orbit_cosmetic.social.banned_sharing.pictures" : "orbit_cosmetic.social.banned_sharing.cosmetics"));
			return;
		}
		if (image == null) {
			for (UUID friend : to) {
				sendTo(friend, content, null, done);
			}
			return;
		}
		Util.ioPool().execute(() -> {
			try {
				byte[] png = Files.readAllBytes(image);
				if (png.length > MAX_IMAGE_BYTES) {
					throw new IOException("The picture is larger than 8 MiB");
				}
				byte[] key = SocialCrypto.randomKey();
				byte[] sealed = SocialCrypto.seal(png, key);
				Minecraft.getInstance().execute(() -> OrbitClient.get().backend().socialUpload(sealed, attachment -> {
					JsonObject picture = new JsonObject();
					picture.addProperty("id", attachment);
					picture.addProperty("key", Base64.getEncoder().encodeToString(key));
					picture.addProperty("name", image.getFileName().toString());
					content.add("image", picture);
					// The picture is kept here as sent, so it shows without downloading it back.
					try {
						Files.createDirectories(folder().resolve("pictures"));
						Files.copy(image, folder().resolve("pictures").resolve(attachment + ".png"), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException ignored) {
						// it is downloaded when shown
					}
					for (UUID friend : to) {
						sendTo(friend, content, attachment, done);
					}
				}, error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error))));
			} catch (IOException | GeneralSecurityException e) {
				Minecraft.getInstance().execute(() -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(e.getMessage())));
			}
		});
	}

	private void sendTo(UUID friend, JsonObject content, @Nullable String attachment, Runnable done) {
		UUID account = me;
		boolean encrypted = isEncrypted(friend);
		envelope(friend, content, attachment, encrypted, body -> OrbitClient.get().backend().socialCall("POST", "/messages", body,
			sent -> added(friend, account, sent, content, encrypted, done),
			error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error))));
	}

	/**
	 * The request body of a message: as it is (over HTTPS, readable on any computer of the two players), or end-to-end
	 * encrypted with a key for every computer of both players. {@code ready} gets it (on the main thread).
	 */
	private void envelope(UUID friend, JsonObject content, @Nullable String attachment, boolean encrypted, Consumer<JsonObject> ready) {
		UUID account = me;
		if (!encrypted) {
			JsonObject body = new JsonObject();
			body.addProperty("to", friend.toString());
			body.addProperty("plain", true);
			body.addProperty("ciphertext", Base64.getEncoder().encodeToString(GSON.toJson(content).getBytes(StandardCharsets.UTF_8)));
			if (attachment != null) {
				body.addProperty("attachment", attachment);
			}
			ready.accept(body);
			return;
		}
		// Every computer of both players gets the message key.
		OrbitClient.get().backend().socialCall("GET", "/devices/" + friend, null, theirs -> OrbitClient.get().backend().socialCall("GET", "/devices/" + account, null, mine -> {
			try {
				byte[] key = SocialCrypto.randomKey();
				JsonObject keys = new JsonObject();
				for (JsonArray devices : List.of(theirs.getAsJsonArray("devices"), mine.getAsJsonArray("devices"))) {
					for (JsonElement element : devices) {
						JsonObject deviceJson = element.getAsJsonObject();
						PublicKey publicKey = SocialCrypto.publicKey(deviceJson.get("public_key").getAsString());
						keys.addProperty(deviceJson.get("id").getAsString(), SocialCrypto.wrap(key, publicKey));
					}
				}
				if (theirs.getAsJsonArray("devices").isEmpty()) {
					Notifications.warning(Component.translatable("orbit_cosmetic.social.no_devices"), Component.empty());
					return;
				}
				JsonObject body = new JsonObject();
				body.addProperty("to", friend.toString());
				body.addProperty("ciphertext", Base64.getEncoder().encodeToString(SocialCrypto.seal(GSON.toJson(content).getBytes(StandardCharsets.UTF_8), key)));
				body.add("keys", keys);
				if (attachment != null) {
					body.addProperty("attachment", attachment);
				}
				ready.accept(body);
			} catch (GeneralSecurityException | RuntimeException e) {
				Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(e.getMessage()));
			}
		}, error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error))),
			error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}

	/** A message sent: into the conversation. */
	private void added(UUID friend, UUID account, JsonObject sent, JsonObject content, boolean encrypted, Runnable done) {
		List<Entry> chat = chat(friend);
		chat.add(new Entry(sent.get("id").getAsString(), account, sent.get("at").getAsDouble(), content.deepCopy(), encrypted));
		saveChat(friend);
		version++;
		done.run();
	}

	// ---------------------------------------------------------------- editing and deleting

	private static int indexOf(List<Entry> chat, String id) {
		for (int i = 0; i < chat.size(); i++) {
			if (chat.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** Replaces this computer's copy of a message (after Orbit Cloud took the change). */
	private void replace(UUID friend, Entry entry) {
		List<Entry> chat = chat(friend);
		int index = indexOf(chat, entry.id());
		if (index >= 0) {
			chat.set(index, entry);
			saveChat(friend);
			version++;
		}
	}

	/**
	 * Changes the text of one of the player's own messages. Orbit Cloud keeps the new content in place of the old (as
	 * encrypted as the message was), so every computer of both players shows it edited, also one added later.
	 */
	public void edit(UUID friend, String id, String text) {
		List<Entry> chat = chat(friend);
		int index = indexOf(chat, id);
		if (index < 0 || chat.get(index).content() == null || chat.get(index).deleted()) {
			return;
		}
		Entry old = chat.get(index);
		JsonObject content = old.content().deepCopy();
		content.addProperty("text", text);
		envelope(friend, content, null, old.encrypted(), body -> OrbitClient.get().backend().socialCall("PUT", "/messages/" + id, body,
			ok -> replace(friend, new Entry(id, old.from(), old.at(), content, old.encrypted(), true, false)),
			error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error))));
	}

	/**
	 * Deletes one of the player's own messages: Orbit Cloud forgets its content and keeps it as deleted, so every
	 * computer of both players shows it deleted.
	 */
	public void delete(UUID friend, Entry entry) {
		OrbitClient.get().backend().socialCall("DELETE", "/messages/" + entry.id(), null,
			ok -> replace(friend, new Entry(entry.id(), entry.from(), entry.at(), null, entry.encrypted(), false, true)),
			error -> Notifications.error(Component.translatable("orbit_cosmetic.social.failed"), Component.literal(error)));
	}

	/**
	 * A picture of a message, decrypted on this computer (downloaded the first time). {@code done} gets its file;
	 * {@code failed} runs if it cannot be had now.
	 */
	public void picture(JsonObject image, Consumer<Path> done, Runnable failed) {
		String id = image.get("id") instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsString() : "";
		if (!id.matches("[0-9a-f]{32}")) {
			return;
		}
		if (image.has("broadcast")) {
			// A picture of Orbit's message to every player: not encrypted.
			Path file = folder().resolve("pictures").resolve("orbit-" + id + ".png");
			if (Files.isRegularFile(file)) {
				done.accept(file);
				return;
			}
			OrbitClient.get().backend().socialDownloadPath("/v1/social/broadcasts/" + id + "/picture", png -> Util.ioPool().execute(() -> {
				try {
					Files.createDirectories(file.getParent());
					Files.write(file, png);
					Minecraft.getInstance().execute(() -> done.accept(file));
				} catch (IOException e) {
					Minecraft.getInstance().execute(failed);
				}
			}), error -> failed.run());
			return;
		}
		Path file = folder().resolve("pictures").resolve(id + ".png");
		if (Files.isRegularFile(file)) {
			done.accept(file);
			return;
		}
		byte[] key;
		try {
			key = Base64.getDecoder().decode(image.get("key").getAsString());
		} catch (RuntimeException e) {
			failed.run();
			return;
		}
		OrbitClient.get().backend().socialDownload(id, sealed -> Util.ioPool().execute(() -> {
			try {
				byte[] png = SocialCrypto.open(sealed, key);
				Files.createDirectories(file.getParent());
				Files.write(file, png);
				Minecraft.getInstance().execute(() -> done.accept(file));
			} catch (IOException | GeneralSecurityException | RuntimeException e) {
				OrbitClient.LOGGER.warn("Friends: could not open picture {}", id);
				Minecraft.getInstance().execute(failed);
			}
		}), error -> failed.run());
	}

	// ---------------------------------------------------------------- local history

	private List<Entry> loadChat(UUID friend) {
		List<Entry> chat = new ArrayList<>();
		if (me == null) {
			return chat;
		}
		JsonObject json = readJson(folder().resolve("chats").resolve(friend + ".json"));
		if (json != null && json.get("messages") instanceof JsonArray array) {
			for (JsonElement element : array) {
				JsonObject entry = element.getAsJsonObject();
				JsonObject content = entry.get("content") instanceof JsonObject object ? object : null;
				boolean encrypted = !entry.has("encrypted") || entry.get("encrypted").getAsBoolean();
				chat.add(new Entry(entry.get("id").getAsString(), UUID.fromString(entry.get("from").getAsString()), entry.get("at").getAsDouble(), content, encrypted,
					entry.has("edited") && entry.get("edited").getAsBoolean(), entry.has("deleted") && entry.get("deleted").getAsBoolean()));
			}
		}
		return chat;
	}

	private void saveChat(UUID friend) {
		JsonArray array = new JsonArray();
		for (Entry entry : chat(friend)) {
			JsonObject json = new JsonObject();
			json.addProperty("id", entry.id());
			json.addProperty("from", entry.from().toString());
			json.addProperty("at", entry.at());
			json.addProperty("encrypted", entry.encrypted());
			json.addProperty("edited", entry.edited());
			json.addProperty("deleted", entry.deleted());
			if (entry.content() != null) {
				json.add("content", entry.content());
			}
			array.add(json);
		}
		JsonObject root = new JsonObject();
		root.add("messages", array);
		writeJson(folder().resolve("chats").resolve(friend + ".json"), root);
	}

	private void saveMeta() {
		JsonObject meta = new JsonObject();
		meta.addProperty("since", since);
		JsonArray encrypted = new JsonArray();
		encryptedChats.forEach(friend -> encrypted.add(friend.toString()));
		meta.add("encrypted", encrypted);
		JsonArray seen = new JsonArray();
		seenBroadcasts.forEach(seen::add);
		meta.add("broadcasts_seen", seen);
		writeJson(folder().resolve("meta.json"), meta);
	}

	private static @Nullable JsonObject readJson(Path file) {
		try {
			return Files.isRegularFile(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void writeJson(Path file, JsonObject json) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(json));
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Friends: could not save {}", file, e);
		}
	}

	// ---------------------------------------------------------------- faces

	/**
	 * A player's skin, for their face in lists (the default skin until it is loaded). One's own is the skin worn in
	 * Orbit. Mojang's profile service refuses requests that come too fast: a lookup that failed, or whose skin did
	 * not load, is tried again a little later.
	 */
	public PlayerSkin skin(UUID player, String name) {
		Minecraft minecraft = Minecraft.getInstance();
		if (player.equals(minecraft.getUser().getProfileId())) {
			return OrbitClient.get().menuSkin();
		}
		long now = System.currentTimeMillis();
		Supplier<PlayerSkin> known = skins.get(player);
		if (known != null) {
			PlayerSkin skin = known.get();
			if (!skin.equals(DefaultPlayerSkin.get(player)) || now < skinRetryAt.getOrDefault(player, Long.MAX_VALUE)) {
				return skin;
			}
			// Still the default skin a while after the profile arrived: the skin's download may have failed.
			skins.remove(player);
		}
		if (!skinsRequested.contains(player) && now >= skinRetryAt.getOrDefault(player, 0L)) {
			skinsRequested.add(player);
			Util.backgroundExecutor().execute(() -> {
				GameProfile profile = null;
				try {
					var result = minecraft.services().sessionService().fetchProfile(player, false);
					profile = result == null ? null : result.profile();
				} catch (RuntimeException e) {
					OrbitClient.LOGGER.debug("Friends: no profile for {} yet", name);
				}
				GameProfile found = profile;
				minecraft.execute(() -> {
					skinsRequested.remove(player);
					int attempt = skinAttempts.merge(player, 1, Integer::sum);
					// 15 s, 30 s, 1 min… up to 10 minutes between attempts.
					long wait = Math.min(600_000L, 15_000L << Math.min(attempt - 1, 6));
					if (found == null) {
						skinRetryAt.put(player, System.currentTimeMillis() + wait);
						return;
					}
					skins.put(player, minecraft.getSkinManager().createLookup(found, false));
					boolean textured = !found.properties().get("textures").isEmpty();
					// A player without a skin of their own keeps the default one.
					skinRetryAt.put(player, textured ? System.currentTimeMillis() + Math.max(20_000L, wait) : Long.MAX_VALUE);
				});
			});
		}
		return known != null ? known.get() : DefaultPlayerSkin.get(player);
	}

	/** Every conversation partner seen, by name (for screens). */
	public Map<UUID, String> names() {
		Map<UUID, String> names = new LinkedHashMap<>();
		for (List<Person> list : List.of(friends, incoming, outgoing, blocked)) {
			list.forEach(person -> names.put(person.uuid(), person.name()));
		}
		return names;
	}
}
