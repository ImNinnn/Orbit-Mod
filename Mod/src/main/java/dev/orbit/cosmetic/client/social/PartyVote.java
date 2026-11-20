package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.host.Hosting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where the party plays, voted on (parties have no leader). A member in a party who joins a server or opens a world
 * chooses: alone, or with the party. With the party, the others get a popup ("Ninnn would like to join Hypixel -
 * 13s remaining", Join and Opt out) while the one who asked waits; once every member still in the vote has joined,
 * or the time is up, everyone who pressed Join goes there at once (members who did not answer stay where they are).
 * A singleplayer world is opened and put online with e4mc first, and the others join once it is online.
 */
public final class PartyVote {
	/** How long a vote lasts. */
	public static final int SECONDS = 15;
	/** How long the others wait for a world to go online after the vote, before it is called off. */
	private static final long HOSTING_WAIT_MS = 90_000;
	/** How long a vote's popup waits for its result after the time is up (the one who asked may be slow to say). */
	private static final long RESULT_WAIT_MS = 10_000;

	/** A vote: who asked, where (an address; empty while a world is not online yet), who joins and who opted out. */
	static final class Vote {
		final String id;
		final UUID from;
		final String fromName;
		String address;
		final String world;
		final boolean worldKind;
		final long endsAt;
		final Set<UUID> joined = new LinkedHashSet<>();
		final Set<UUID> out = new HashSet<>();
		/** This player pressed Join; the time ran out or everyone answered (waiting to go, or for the world). */
		boolean answered;
		boolean over;
		long overAt;

		Vote(String id, UUID from, String fromName, String address, String world, boolean worldKind, long endsAt) {
			this.id = id;
			this.from = from;
			this.fromName = fromName;
			this.address = address;
			this.world = world;
			this.worldKind = worldKind;
			this.endsAt = endsAt;
		}

		long secondsLeft() {
			return Math.max(0, (endsAt - System.currentTimeMillis() + 999) / 1000);
		}
	}

	/** The vote this player started (at most one), what it does when it is over, and the votes of the others. */
	private static @Nullable Vote mine;
	private static @Nullable Runnable mineGo;
	private static @Nullable Screen mineParent;
	private static final Map<String, Vote> OTHERS = new HashMap<>();
	/** A world opened for a vote, being put online: the vote, and since when. */
	private static @Nullable Vote hosting;
	private static long hostingSince;
	private static boolean hostStarted;
	/** Joins without asking (the choice made, a vote's result, or not in a party). */
	private static boolean bypass;

	private PartyVote() {
	}

	// ---------------------------------------------------------------- joining

	/** Runs a join as it is (no choice asked): alone, or as a vote's result. */
	public static void joinAlone(Runnable join) {
		boolean was = bypass;
		bypass = true;
		try {
			join.run();
		} finally {
			bypass = was;
		}
	}

	/** Whether joining asks first: in a party with someone else, and not already decided. */
	private static boolean asks() {
		Social.Party party = OrbitClient.get().social().party();
		return !bypass && party != null && party.members().size() > 1;
	}

	/**
	 * Joining a server (every way of joining one): in a party, asks whether alone or with the party first. True when
	 * it asked (the join waits for the answer).
	 */
	public static boolean interceptConnect(@Nullable Screen parent, String address, @Nullable ServerData data, Runnable connect) {
		if (!asks()) {
			return false;
		}
		String name = data != null && data.name != null && !data.name.isBlank() ? data.name : address;
		Compat.setScreen(new PartyJoinScreen(parent, Component.literal(name), true, () -> joinAlone(connect),
			() -> start(parent, address, name, false, connect)));
		return true;
	}

	/** Opening a singleplayer world: the same, with the party only when e4mc can put it online. */
	public static boolean interceptWorld(String levelId, Runnable open) {
		if (!asks()) {
			return false;
		}
		Screen parent = Compat.screen();
		Compat.setScreen(new PartyJoinScreen(parent, Component.literal(levelId), Hosting.e4mcInstalled(), () -> joinAlone(open),
			() -> start(parent, "", levelId, true, open)));
		return true;
	}

	// ---------------------------------------------------------------- the one who asks

	/** Starts a vote to go somewhere with the party: the others are asked, this player waits on the vote screen. */
	static void start(@Nullable Screen parent, String address, String world, boolean worldKind, @Nullable Runnable go) {
		Social social = OrbitClient.get().social();
		UUID me = social.me();
		if (me == null || !PartyLink.get().connected()) {
			// The party cannot be asked now: joined alone.
			if (go != null) {
				joinAlone(go);
			}
			return;
		}
		Vote vote = new Vote(UUID.randomUUID().toString(), me, Minecraft.getInstance().getUser().getName(), address, world, worldKind,
			System.currentTimeMillis() + SECONDS * 1000L);
		vote.joined.add(me);
		vote.answered = true;
		mine = vote;
		mineGo = go;
		mineParent = parent;
		JsonObject fields = new JsonObject();
		fields.addProperty("id", vote.id);
		fields.addProperty("address", address);
		fields.addProperty("world", world.length() > 64 ? world.substring(0, 64) : world);
		fields.addProperty("kind", worldKind ? "world" : "server");
		fields.addProperty("seconds", SECONDS);
		PartyLink.get().signal("vote", fields);
		OrbitClient.LOGGER.info("Party: vote {} to join {} with the party", vote.id, world);
		if (go != null) {
			Compat.setScreen(new PartyVoteScreen(parent));
		}
	}

	/** The quick menu's Warp party off servers with a party of their own: a vote to bring the party where this player is. */
	public static boolean warp() {
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = minecraft.getCurrentServer();
		if (server != null && !minecraft.isLocalServer() && minecraft.player != null) {
			start(null, server.ip, server.name == null || server.name.isBlank() ? server.ip : server.name, false, null);
			return true;
		}
		String hosted = Hosting.address();
		var integrated = minecraft.getSingleplayerServer();
		if (hosted != null && integrated != null) {
			start(null, hosted, integrated.getWorldData().getLevelName(), false, null);
			return true;
		}
		return false;
	}

	/** The vote this player is waiting on, for its screen. */
	static @Nullable Vote mine() {
		return mine;
	}

	/** How many the vote counts: everyone in the party, less who opted out. */
	static int total(Vote vote) {
		Social.Party party = OrbitClient.get().social().party();
		int members = party == null ? vote.joined.size() : party.members().size();
		return Math.max(vote.joined.size(), members - vote.out.size());
	}

	/** Called by the vote screen: go now with who joined, or call it off (back where they were). */
	static void goNow() {
		if (mine != null) {
			finish(mine);
		}
	}

	static void cancel() {
		Vote vote = mine;
		if (vote == null) {
			return;
		}
		mine = null;
		mineGo = null;
		JsonObject fields = new JsonObject();
		fields.addProperty("id", vote.id);
		PartyLink.get().signal("vote_cancel", fields);
		Compat.setScreen(mineParent);
	}

	/** The vote is over: everyone who joined goes (the others with the address; a world is opened and put online first). */
	private static void finish(Vote vote) {
		Runnable go = mineGo;
		mine = null;
		mineGo = null;
		vote.over = true;
		boolean together = vote.joined.size() > 1;
		if (vote.worldKind && together) {
			hosting = vote;
			hostingSince = System.currentTimeMillis();
			hostStarted = false;
		} else if (together) {
			sendGo(vote);
		}
		if (go != null) {
			joinAlone(go);
		}
	}

	private static void sendGo(Vote vote) {
		JsonObject fields = new JsonObject();
		fields.addProperty("id", vote.id);
		fields.addProperty("address", vote.address);
		fields.addProperty("world", vote.world.length() > 64 ? vote.world.substring(0, 64) : vote.world);
		JsonArray players = new JsonArray();
		vote.joined.forEach(player -> players.add(player.toString()));
		fields.add("players", players);
		PartyLink.get().signal("vote_go", fields);
		ServerParty.voteWon(vote.from, List.copyOf(vote.joined), vote.address);
	}

	// ---------------------------------------------------------------- every tick

	static void tick(Social social) {
		long now = System.currentTimeMillis();
		Vote vote = mine;
		if (vote != null && (vote.joined.size() >= total(vote) || now >= vote.endsAt)) {
			finish(vote);
		}
		if (hosting != null) {
			tickHosting(now);
		}
		// Votes whose result never came are let go.
		OTHERS.values().removeIf(other -> now > other.endsAt + (other.worldKind ? HOSTING_WAIT_MS : RESULT_WAIT_MS));
	}

	/** The world of a vote is opened: once it has loaded it is put online, and the others are told its address. */
	private static void tickHosting(long now) {
		Vote vote = hosting;
		Minecraft minecraft = Minecraft.getInstance();
		var integrated = minecraft.getSingleplayerServer();
		if (now - hostingSince > HOSTING_WAIT_MS) {
			OrbitClient.LOGGER.warn("Party: the world did not go online in time");
			JsonObject fields = new JsonObject();
			fields.addProperty("id", vote.id);
			PartyLink.get().signal("vote_cancel", fields);
			hosting = null;
			return;
		}
		if (integrated == null || minecraft.player == null) {
			return;
		}
		if (!integrated.isPublished() && !hostStarted) {
			hostStarted = true;
			//? if >=1.20.2 {
			Hosting.start(integrated.getDefaultGameType(), integrated.getWorldData().isAllowCommands());
			//?} else {
			/*Hosting.start(integrated.getDefaultGameType(), integrated.getWorldData().getAllowCommands());
			*///?}
		}
		String address = Hosting.address();
		if (address != null) {
			vote.address = address;
			sendGo(vote);
			hosting = null;
		}
	}

	// ---------------------------------------------------------------- the others

	/** A vote signal from another member's game. */
	static void signal(Social social, UUID from, String name, String signal, JsonObject message) {
		Social.Party party = social.party();
		UUID me = social.me();
		if (party == null || me == null || from.equals(me) || party.members().stream().noneMatch(member -> member.uuid().equals(from))) {
			return;
		}
		String id = message.get("id").getAsString();
		switch (signal) {
			case "vote" -> received(from, name, id, message);
			case "vote_answer" -> {
				Vote vote = mine != null && mine.id.equals(id) ? mine : OTHERS.get(id);
				if (vote != null) {
					if (message.get("answer").getAsString().equals("join")) {
						vote.out.remove(from);
						vote.joined.add(from);
					} else {
						vote.joined.remove(from);
						vote.out.add(from);
					}
				}
			}
			case "vote_go" -> {
				Vote vote = OTHERS.remove(id);
				List<UUID> players = new ArrayList<>();
				message.getAsJsonArray("players").forEach(player -> players.add(UUID.fromString(player.getAsString())));
				String address = message.get("address").getAsString();
				ServerParty.voteWon(from, players, address);
				if (vote != null && vote.answered && players.contains(me) && !address.isEmpty() && !here(address)) {
					OrbitClient.LOGGER.info("Party: going to {} with the party", address);
					String world = message.get("world").getAsString();
					joinAlone(() -> ServerLinks.join(Compat.screen(), address, world));
				}
			}
			case "vote_cancel" -> OTHERS.remove(id);
			default -> {
			}
		}
	}

	private static void received(UUID from, String name, String id, JsonObject message) {
		boolean worldKind = message.get("kind").getAsString().equals("world");
		int seconds = message.get("seconds").getAsInt();
		Vote vote = new Vote(id, from, name, message.get("address").getAsString(), message.get("world").getAsString(), worldKind,
			System.currentTimeMillis() + seconds * 1000L);
		vote.joined.add(from);
		OTHERS.put(id, vote);
		SocialPopups.vote(from, name, () -> Component.translatable("orbit_cosmetic.vote.title", vote.fromName, vote.world), () -> detail(vote),
			() -> Component.translatable(vote.answered ? "orbit_cosmetic.vote.joined" : "orbit_cosmetic.vote.join", vote.joined.size(), total(vote)),
			() -> answer(vote, true), () -> vote.answered ? null : Component.translatable("orbit_cosmetic.vote.opt_out"), () -> answer(vote, false),
			() -> OTHERS.containsKey(vote.id));
	}

	/** "13s remaining", then what it waits for. */
	private static Component detail(Vote vote) {
		long left = vote.secondsLeft();
		if (left > 0) {
			return Component.translatable("orbit_cosmetic.vote.remaining", left);
		}
		return Component.translatable(vote.worldKind ? "orbit_cosmetic.vote.waiting_world" : "orbit_cosmetic.vote.waiting");
	}

	/** This player's answer: Join (stays, with the count), or Opt out (not counted, not going). */
	private static void answer(Vote vote, boolean join) {
		Social social = OrbitClient.get().social();
		UUID me = social.me();
		if (me == null || vote.answered) {
			return;
		}
		if (join) {
			vote.answered = true;
			vote.joined.add(me);
		} else {
			vote.out.add(me);
			OTHERS.remove(vote.id);
		}
		JsonObject fields = new JsonObject();
		fields.addProperty("id", vote.id);
		fields.addProperty("answer", join ? "join" : "out");
		PartyLink.get().signal("vote_answer", fields);
	}

	/** Whether this player plays at that address already (or on the same network). */
	private static boolean here(String address) {
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = minecraft.getCurrentServer();
		if (server != null && minecraft.player != null && PartyLink.network(server.ip).equals(PartyLink.network(address))) {
			return true;
		}
		String hosted = Hosting.address();
		return hosted != null && hosted.equalsIgnoreCase(address);
	}

	/** The player left the party: their vote is called off, the others' forgotten. */
	static void left() {
		if (mine != null) {
			cancel();
		}
		OTHERS.clear();
		hosting = null;
	}

	// ---------------------------------------------------------------- joining a member

	/** Where a member can be joined (their server, or their world once online), when this player is not with them. */
	public static String @Nullable [] place(UUID member) {
		PartyLink.Status status = PartyLink.get().status(member);
		if (status == null || !status.playing() || PartyLink.playsHere(member, status)) {
			return null;
		}
		if (!status.server().isEmpty()) {
			return new String[] {status.server(), status.server()};
		}
		if (!status.extra().host().isEmpty()) {
			return new String[] {status.extra().host(), status.world().isEmpty() ? status.extra().host() : status.world()};
		}
		return null;
	}

	/** Joins a party member where they play (alone: they are there already). */
	public static void joinMember(@Nullable Screen parent, UUID member) {
		String[] place = place(member);
		if (place != null) {
			joinAlone(() -> ServerLinks.join(parent, place[0], place[1]));
		}
	}
}
