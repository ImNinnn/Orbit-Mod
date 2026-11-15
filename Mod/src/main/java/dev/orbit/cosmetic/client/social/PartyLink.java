package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
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
	 * A party member's state, as their game last sent it: {@code playing} is false in the game's menus (out of a
	 * world), {@code saturation} is shown with AppleSkin.
	 */
	public record Status(float health, float maxHealth, int hunger, float saturation, boolean playing, String server, String world, long at) {
		/** Dead: in a world with no health left. */
		public boolean dead() {
			return playing && health <= 0;
		}

		/** Where they play: the server's address, or the singleplayer world's name. */
		public String place() {
			return server.isEmpty() ? world : server;
		}
	}

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
		json.addProperty("hunger", player == null ? 20 : player.getFoodData().getFoodLevel());
		json.addProperty("saturation", player == null ? 0 : player.getFoodData().getSaturationLevel());
		json.addProperty("playing", player != null);
		ServerData server = minecraft.getCurrentServer();
		json.addProperty("server", server != null && !minecraft.isLocalServer() ? server.ip : "");
		var integrated = minecraft.getSingleplayerServer();
		json.addProperty("world", integrated != null ? integrated.getWorldData().getLevelName() : "");
		return json.toString();
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
		WebSocket open = socket;
		if (open != null) {
			JsonObject json = new JsonObject();
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
					Minecraft.getInstance().execute(() -> ServerParty.signal(OrbitClient.get().social(), player, name, signal));
				} else if (type.equals("gone")) {
					statuses.remove(player);
				} else if (type.equals("status")) {
					statuses.put(player, new Status(message.get("health").getAsFloat(), message.get("max_health").getAsFloat(), message.get("hunger").getAsInt(),
						message.has("saturation") ? message.get("saturation").getAsFloat() : 0, !message.has("playing") || message.get("playing").getAsBoolean(),
						message.get("server").getAsString(), message.get("world").getAsString(), System.currentTimeMillis()));
				}
			} catch (RuntimeException e) {
				OrbitClient.LOGGER.debug("Party: unreadable live message: {}", e.toString());
			}
		}
		webSocket.request(1);
		return null;
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
