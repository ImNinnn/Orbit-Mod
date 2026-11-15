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
	/** A party member's state, as their game last sent it. */
	public record Status(float health, float maxHealth, int hunger, String server, String world, long at) {
	}

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
		return statuses.get(player);
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
		ServerData server = minecraft.getCurrentServer();
		json.addProperty("server", server != null && !minecraft.isLocalServer() ? server.ip : "");
		var integrated = minecraft.getSingleplayerServer();
		json.addProperty("world", integrated != null ? integrated.getWorldData().getLevelName() : "");
		return json.toString();
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
				if (type.equals("gone")) {
					statuses.remove(player);
				} else if (type.equals("status")) {
					statuses.put(player, new Status(message.get("health").getAsFloat(), message.get("max_health").getAsFloat(), message.get("hunger").getAsInt(),
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
