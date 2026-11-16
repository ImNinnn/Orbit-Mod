package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import dev.orbit.cosmetic.client.mixin.FoodDataAccessor;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.net.http.WebSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The party's live link to Orbit Cloud: open while the player is in a party. It sends this player's health, hunger,
 * server and world when they change (once a second at most, and every 10 seconds anyway), and keeps the other
 * members' as they come.
 */
public final class PartyLink implements WebSocket.Listener {
	/**
	 * A party member's state, as their game last sent it: health (with absorption), hunger (with saturation and
	 * exhaustion, shown with AppleSkin), what changes how the game draws them ({@link #POISON} and the other
	 * flags), their game mode ("survival", "creative"...), the health of what they ride (0 when nothing with
	 * hearts), and where they play. {@code playing} is false in the game's menus (out of a world).
	 */
	public record Status(float health, float maxHealth, float absorption, int hunger, float saturation, float exhaustion, int flags, String mode,
		float mountHealth, float mountMaxHealth, Extra extra, boolean playing, String server, String world, long at) {
		/** Dead: in a world with no health left. */
		public boolean dead() {
			return playing && health <= 0;
		}

		/** Where they play: the server's address, or the singleplayer world's name. */
		public String place() {
			return server.isEmpty() ? world : server;
		}

		public boolean has(int flag) {
			return (flags & flag) != 0;
		}

		/** Whether their game shows health and hunger (survival and adventure; not creative or spectator). */
		public boolean showsHealth() {
			return mode.equals("survival") || mode.equals("adventure");
		}
	}

	/**
	 * The rest of a member's state: experience, armour points, air (and whether they are under water), whether
	 * they are invisible, the online address of the world they host (e4mc), and their armour and held items (by
	 * slot: head, chest, legs, feet, mainhand, offhand; each its item's ID, count, damage and glint).
	 */
	public record Extra(int xpLevel, float xpProgress, int armor, int air, int maxAir, boolean underwater, boolean invisible, String host, Map<String, JsonObject> items,
		String scoreboard) {
		public static final Extra NONE = new Extra(0, 0, 0, 300, 300, false, false, "", Map.of(), "");
	}

	public static final int POISON = 1;
	public static final int WITHER = 2;
	public static final int REGENERATION = 4;
	public static final int HUNGER = 8;
	public static final int FROZEN = 16;
	public static final int HARDCORE = 32;
	private static final String[] FLAG_NAMES = {"poison", "wither", "regeneration", "hunger", "frozen", "hardcore"};

	/** A status not heard from for this long is from a game that went away without saying (sent every 10 s anyway). */
	private static final long STALE_MS = 30_000;

	private static final long SEND_EVERY_MS = 1000;
	private static final long SEND_ANYWAY_MS = 10_000;
	private static final PartyLink INSTANCE = new PartyLink();

	private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();
	private volatile @Nullable WebSocket socket;
	private boolean connecting;
	private long retryAt;
	private long retryDelay = 5_000;
	private long sentAt;
	private @Nullable String lastSent;
	private final StringBuilder incoming = new StringBuilder();

	private PartyLink() {
	}

	public static PartyLink get() {
		return INSTANCE;
	}

	/** The other members' states (by player). */
	public @Nullable Status status(UUID player) {
		Status status = statuses.get(player);
		return status != null && System.currentTimeMillis() - status.at() > STALE_MS ? null : status;
	}

	public boolean connected() {
		return socket != null;
	}

	/** Called every tick: opens the link in a party, closes it out of one, sends this player's state. */
	void tick(Social social) {
		long now = System.currentTimeMillis();
		WebSocket open = socket;
		if (social.party() == null) {
			if (open != null) {
				close();
			}
			return;
		}
		if (open == null) {
			if (!connecting && now >= retryAt) {
				connecting = true;
				OrbitClient.get().backend().openLive(this, opened -> {
					connecting = false;
					socket = opened;
					retryDelay = 5_000;
					lastSent = null;
				}, error -> {
					connecting = false;
					retryAt = System.currentTimeMillis() + retryDelay;
					retryDelay = Math.min(60_000, retryDelay * 2);
					OrbitClient.LOGGER.debug("Party: live link not open: {}", error);
				});
			}
			return;
		}
		if (now - sentAt < SEND_EVERY_MS) {
			return;
		}
		String state = ownState();
		if (state.equals(lastSent) && now - sentAt < SEND_ANYWAY_MS) {
			return;
		}
		sentAt = now;
		lastSent = state;
		open.sendText(state, true);
	}

	/** This player's state, as JSON: health and hunger in a world, the server's address (or the world's name). */
	private static String ownState() {
		Minecraft minecraft = Minecraft.getInstance();
		JsonObject json = new JsonObject();
		LocalPlayer player = minecraft.player;
		json.addProperty("health", player == null ? 0 : player.getHealth());
		json.addProperty("max_health", player == null ? 20 : player.getMaxHealth());
		json.addProperty("absorption", player == null ? 0 : player.getAbsorptionAmount());
		json.addProperty("hunger", player == null ? 20 : player.getFoodData().getFoodLevel());
		json.addProperty("saturation", player == null ? 0 : player.getFoodData().getSaturationLevel());
		json.addProperty("exhaustion", player == null ? 0 : Math.round(exhaustion(minecraft, player) * 10) / 10F);
		com.google.gson.JsonArray effects = new com.google.gson.JsonArray();
		if (player != null) {
			boolean[] on = {player.hasEffect(MobEffects.POISON), player.hasEffect(MobEffects.WITHER), player.hasEffect(MobEffects.REGENERATION),
				player.hasEffect(MobEffects.HUNGER), player.isFullyFrozen(), player.level().getLevelData().isHardcore()};
			for (int i = 0; i < on.length; i++) {
				if (on[i]) {
					effects.add(FLAG_NAMES[i]);
				}
			}
		}
		json.add("effects", effects);
		json.addProperty("mode", minecraft.gameMode == null ? "survival" : minecraft.gameMode.getPlayerMode().getSerializedName());
		if (player != null && player.getVehicle() instanceof LivingEntity mount && mount.showVehicleHealth()) {
			json.addProperty("mount_health", mount.getHealth());
			json.addProperty("mount_max_health", mount.getMaxHealth());
		}
		if (player != null) {
			json.addProperty("xp_level", player.experienceLevel);
			json.addProperty("xp_progress", Math.round(player.experienceProgress * 1000) / 1000F);
			json.addProperty("armor", player.getArmorValue());
			json.addProperty("air", player.getAirSupply());
			json.addProperty("max_air", player.getMaxAirSupply());
			json.addProperty("underwater", player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
			json.addProperty("invisible", player.isInvisible());
			JsonObject items = new JsonObject();
			for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.MAINHAND,
				net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
				var stack = player.getItemBySlot(slot);
				if (!stack.isEmpty()) {
					JsonObject item = new JsonObject();
					item.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
					item.addProperty("count", stack.getCount());
					item.addProperty("damage", stack.getDamageValue());
					item.addProperty("max_damage", stack.getMaxDamage());
					item.addProperty("glint", stack.hasFoil());
					items.add(slot.getName(), item);
				}
			}
			json.add("items", items);
		}
		if (minecraft.level != null) {
			var objective = minecraft.level.getScoreboard().getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
			if (objective != null) {
				String title = net.minecraft.ChatFormatting.stripFormatting(objective.getDisplayName().getString()).strip();
				json.addProperty("scoreboard", title.length() > 48 ? title.substring(0, 48) : title);
			}
		}
		String hosted = dev.orbit.cosmetic.client.host.Hosting.address();
		json.addProperty("host", hosted == null ? "" : hosted);
		json.addProperty("playing", player != null);
		ServerData server = minecraft.getCurrentServer();
		json.addProperty("server", server != null && !minecraft.isLocalServer() ? server.ip : "");
		var integrated = minecraft.getSingleplayerServer();
		json.addProperty("world", integrated != null ? integrated.getWorldData().getLevelName() : "");
		return json.toString();
	}

	/**
	 * The player's exhaustion: in singleplayer, the world's own (the game does not tell it to the client); on a
	 * server, what AppleSkin got from it (none without AppleSkin on both).
	 */
	private static float exhaustion(Minecraft minecraft, LocalPlayer player) {
		var integrated = minecraft.getSingleplayerServer();
		if (integrated != null) {
			var own = integrated.getPlayerList().getPlayer(player.getUUID());
			if (own != null) {
				return ((FoodDataAccessor) own.getFoodData()).orbit$exhaustion();
			}
		}
		return ((FoodDataAccessor) player.getFoodData()).orbit$exhaustion();
	}

	/**
	 * Whether a member plays where this player does: in this server's player list, on the same server or network
	 * (alpha.hypixel.net and mc.hypixel.net alike), or in the same singleplayer world.
	 */
	public static boolean playsHere(UUID member, @Nullable Status status) {
		if (status == null || !status.playing()) {
			return false;
		}
		if (tabEntry(member) != null) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = minecraft.getCurrentServer();
		if (server != null && !minecraft.isLocalServer()) {
			return !status.server().isEmpty() && network(server.ip).equals(network(status.server()));
		}
		var integrated = minecraft.getSingleplayerServer();
		return integrated != null && status.server().isEmpty() && integrated.getWorldData().getLevelName().equals(status.world());
	}

	/**
	 * A server address as the network it belongs to: the host without its port, cut to its domain
	 * (mc.hypixel.net and alpha.hypixel.net are both hypixel.net; play.example.co.uk is example.co.uk).
	 */
	public static String network(String address) {
		String host = address.trim().toLowerCase(java.util.Locale.ROOT);
		if (host.startsWith("[")) {
			// An IPv6 address, as it is.
			int end = host.indexOf(']');
			return end > 0 ? host.substring(0, end + 1) : host;
		}
		host = host.replaceAll(":\\d+$", "").replaceAll("\\.+$", "");
		if (host.matches("[0-9.]+")) {
			return host;
		}
		String[] labels = host.split("\\.");
		if (labels.length <= 2) {
			return host;
		}
		int keep = labels[labels.length - 1].length() == 2 && java.util.Set.of("co", "com", "net", "org", "ac", "gov", "edu").contains(labels[labels.length - 2]) ? 3 : 2;
		return String.join(".", java.util.Arrays.copyOfRange(labels, labels.length - keep, labels.length));
	}

	/** The member in this server's player list (by their UUID), or null. */
	public static net.minecraft.client.multiplayer.@Nullable PlayerInfo tabEntry(UUID member) {
		var connection = Minecraft.getInstance().getConnection();
		return connection == null ? null : connection.getPlayerInfo(member);
	}

	/**
	 * A member's name as this server shows it in its player list (its ranks and colours: "[VIP] Name"), or their
	 * name as it is when they are not in it.
	 */
	public static net.minecraft.network.chat.Component displayName(UUID member, String name) {
		var entry = tabEntry(member);
		if (entry == null) {
			return net.minecraft.network.chat.Component.literal(name);
		}
		var shown = entry.getTabListDisplayName();
		return shown != null ? shown : net.minecraft.world.scores.PlayerTeam.formatNameForTeam(entry.getTeam(), net.minecraft.network.chat.Component.literal(name));
	}

	/** Tells the other members' games something (see ServerParty): sent at once if the link is open. */
	void signal(String signal) {
		signal(signal, new JsonObject());
	}

	/** A signal with what it carries (a vote's fields). */
	void signal(String signal, JsonObject fields) {
		WebSocket open = socket;
		if (open != null) {
			JsonObject json = fields.deepCopy();
			json.addProperty("type", "signal");
			json.addProperty("signal", signal);
			open.sendText(json.toString(), true);
		}
	}

	private void close() {
		WebSocket open = socket;
		socket = null;
		statuses.clear();
		if (open != null) {
			open.sendClose(WebSocket.NORMAL_CLOSURE, "left the party");
		}
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		incoming.append(data);
		if (last) {
			String text = incoming.toString();
			incoming.setLength(0);
			try {
				JsonObject message = JsonParser.parseString(text).getAsJsonObject();
				UUID player = UUID.fromString(message.get("player").getAsString());
				String type = message.get("type").getAsString();
				if (type.equals("signal")) {
					String signal = message.get("signal").getAsString();
					String name = message.get("name").getAsString();
					if (signal.startsWith("vote")) {
						Minecraft.getInstance().execute(() -> PartyVote.signal(OrbitClient.get().social(), player, name, signal, message));
					} else {
						Minecraft.getInstance().execute(() -> ServerParty.signal(OrbitClient.get().social(), player, name, signal));
					}
				} else if (type.equals("gone")) {
					statuses.remove(player);
				} else if (type.equals("status")) {
					statuses.put(player, readStatus(message));
				}
			} catch (RuntimeException e) {
				OrbitClient.LOGGER.debug("Party: unreadable live message: {}", e.toString());
			}
		}
		webSocket.request(1);
		return null;
	}

	/** A status as another member's game sent it (older games leave out what they did not send yet). */
	private static Status readStatus(JsonObject message) {
		int flags = 0;
		if (message.get("effects") instanceof com.google.gson.JsonArray effects) {
			for (var effect : effects) {
				for (int i = 0; i < FLAG_NAMES.length; i++) {
					if (FLAG_NAMES[i].equals(effect.getAsString())) {
						flags |= 1 << i;
					}
				}
			}
		}
		return new Status(message.get("health").getAsFloat(), message.get("max_health").getAsFloat(), number(message, "absorption"), message.get("hunger").getAsInt(),
			number(message, "saturation"), number(message, "exhaustion"), flags, message.has("mode") ? message.get("mode").getAsString() : "survival",
			number(message, "mount_health"), number(message, "mount_max_health"), readExtra(message), !message.has("playing") || message.get("playing").getAsBoolean(),
			message.get("server").getAsString(), message.get("world").getAsString(), System.currentTimeMillis());
	}

	private static Extra readExtra(JsonObject message) {
		Map<String, JsonObject> items = new java.util.HashMap<>();
		if (message.get("items") instanceof JsonObject sent) {
			for (String slot : sent.keySet()) {
				if (sent.get(slot) instanceof JsonObject item) {
					items.put(slot, item);
				}
			}
		}
		return new Extra((int) number(message, "xp_level"), number(message, "xp_progress"), (int) number(message, "armor"),
			message.has("air") ? message.get("air").getAsInt() : 300, message.has("max_air") ? message.get("max_air").getAsInt() : 300,
			message.has("underwater") && message.get("underwater").getAsBoolean(), message.has("invisible") && message.get("invisible").getAsBoolean(),
			message.has("host") ? message.get("host").getAsString() : "", Map.copyOf(items), message.has("scoreboard") ? message.get("scoreboard").getAsString() : "");
	}

	private static float number(JsonObject message, String key) {
		return message.has(key) && !message.get(key).isJsonNull() ? message.get(key).getAsFloat() : 0;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		lost(webSocket);
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		OrbitClient.LOGGER.debug("Party: live link lost: {}", error.toString());
		lost(webSocket);
	}

	private void lost(WebSocket webSocket) {
		if (socket == webSocket) {
			socket = null;
			retryAt = System.currentTimeMillis() + retryDelay;
		}
	}
}
