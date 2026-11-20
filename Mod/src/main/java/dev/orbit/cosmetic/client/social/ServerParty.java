package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.common.util.Lists;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.OrbitData;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The Orbit party on servers with their own party system (Hypixel). Its leader there is whoever started the vote
 * that brought the party: they invite the members who came with them (Invite), the members accept (Accept). Leaving
 * the Orbit party leaves the server's (its leader hands it to another member first, or ends it when alone).
 * <p>
 * By default every command is sent only when the player clicks. With the risky setting (Advanced), invites and
 * accepts are sent on their own; around each command Orbit sends by itself, the game takes no actions (moving,
 * attacking, using...) for a moment, so nothing done right then is taken for part of a macro.
 */
public final class ServerParty {
	/** A server's party commands ({@code %s} is a player's name). */
	private record Commands(String invite, String accept, String leave, String warp, String promote, String disband) {
	}

	private static final Commands HYPIXEL = new Commands("p invite %s", "p accept %s", "p leave", "p warp", "p promote %s", "p disband");
	/** Between two commands: Hypixel refuses commands sent faster. */
	private static final long SPACING_MS = 1200;
	/** No actions this long before and after a command sent on its own. */
	private static final long FREEZE_MS = 100;

	private static final Deque<String> QUEUE = new ArrayDeque<>();
	private static long nextAt;
	/** A command about to go (on its own): when it goes, and until when actions are not taken. */
	private static long sendAt;
	private static volatile long frozenUntil;
	private static @Nullable Set<KeyMapping> frozenKeys;
	/** The server's party: who leads it (who started the vote), who came, on which network; and who was invited. */
	private static @Nullable UUID leader;
	private static List<UUID> members = List.of();
	private static @Nullable String network;
	private static final Set<UUID> OFFERED = new HashSet<>();

	private ServerParty() {
	}

	/** The party commands of the server the player is on, or null when it has none Orbit knows. */
	private static @Nullable Commands commands() {
		return commandsFor(currentServer());
	}

	private static @Nullable Commands commandsFor(@Nullable String address) {
		if (address == null) {
			return null;
		}
		String host = address.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "").replaceAll("\\.$", "");
		return host.equals("hypixel.net") || host.endsWith(".hypixel.net") ? HYPIXEL : null;
	}

	private static @Nullable String currentServer() {
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = minecraft.getCurrentServer();
		return server != null && !minecraft.isLocalServer() && minecraft.player != null ? server.ip : null;
	}

	/** Whether the player is on a server whose party Orbit can drive. */
	public static boolean available() {
		return commands() != null;
	}

	/** Whether the risky setting is on: party commands sent on their own. */
	private static boolean automatic() {
		return OrbitData.settings().autoServerParty;
	}

	private static void send(String command) {
		QUEUE.addLast(command);
	}

	/** A key the game is not to act on now (around a command sent on its own): the gameplay keys, not the chat. */
	public static boolean blocks(KeyMapping key) {
		if (System.currentTimeMillis() >= frozenUntil) {
			return false;
		}
		if (frozenKeys == null) {
			Options options = Minecraft.getInstance().options;
			Set<KeyMapping> keys = new HashSet<>(List.of(options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift,
				options.keySprint, options.keyAttack, options.keyUse, options.keyPickItem, options.keyDrop, options.keySwapOffhand, options.keyInventory));
			keys.addAll(List.of(options.keyHotbarSlots));
			frozenKeys = keys;
		}
		return frozenKeys.contains(key);
	}

	/** A vote brought players to a place: on a server with a party of its own, the one who asked leads it there. */
	static void voteWon(UUID from, List<UUID> players, String address) {
		if (commandsFor(address) == null || players.size() < 2) {
			return;
		}
		leader = from;
		members = List.copyOf(players);
		network = PartyLink.network(address);
		OFFERED.clear();
	}

	/** Called every tick: sends the next command when it is time, and lets the leader invite who came. */
	static void tick(Social social) {
		Minecraft minecraft = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		Commands commands = commands();
		if (!QUEUE.isEmpty() && now >= nextAt) {
			if (minecraft.player == null || commands == null) {
				QUEUE.clear();
				sendAt = 0;
			} else if (!automatic()) {
				minecraft.player.connection.sendCommand(QUEUE.pollFirst());
				nextAt = now + SPACING_MS;
			} else if (sendAt == 0) {
				// On its own: no actions from now until a moment after it went.
				sendAt = now + FREEZE_MS;
				frozenUntil = sendAt + FREEZE_MS + 50;
			} else if (now >= sendAt) {
				minecraft.player.connection.sendCommand(QUEUE.pollFirst());
				frozenUntil = System.currentTimeMillis() + FREEZE_MS;
				sendAt = 0;
				nextAt = now + SPACING_MS;
			}
		}
		Social.Party party = social.party();
		UUID me = social.me();
		if (party == null || commands == null || leader == null || !leader.equals(me) || network == null
			|| !network.equals(PartyLink.network(currentServer() == null ? "" : currentServer()))) {
			return;
		}
		// The leader: who came with the vote and plays here now, not invited yet.
		List<Social.Person> here = party.members().stream().filter(member -> !member.uuid().equals(me) && members.contains(member.uuid()) && !OFFERED.contains(member.uuid()))
			.filter(member -> PartyLink.playsHere(member.uuid(), PartyLink.get().status(member.uuid()))).toList();
		if (here.isEmpty()) {
			return;
		}
		here.forEach(member -> OFFERED.add(member.uuid()));
		if (automatic()) {
			invite(here);
			return;
		}
		Social.Person first = Lists.first(here);
		Component detail = here.size() == 1 ? Component.translatable("orbit_cosmetic.server_party.offer.one", first.name())
			: Component.translatable("orbit_cosmetic.server_party.offer.many", here.size());
		SocialPopups.action(first.uuid(), first.name(), Component.translatable("orbit_cosmetic.server_party.offer"), detail,
			Component.translatable("orbit_cosmetic.server_party.invite"), () -> invite(here));
	}

	/** Invites the members here to the server's party, and tells their games (they accept). */
	private static void invite(List<Social.Person> invited) {
		Commands commands = commands();
		if (commands == null) {
			return;
		}
		invited.forEach(member -> send(commands.invite().formatted(member.name())));
		PartyLink.get().signal("server_party_invite");
	}

	/** The server party's leader says they invited this player: accepted (on its own, or with a click). */
	static void signal(Social social, UUID from, String name, String signal) {
		Social.Party party = social.party();
		Commands commands = commands();
		if (!signal.equals("server_party_invite") || commands == null || party == null || !from.equals(leader) || !name.matches("[A-Za-z0-9_]{1,16}")) {
			return;
		}
		if (automatic()) {
			send(commands.accept().formatted(name));
			return;
		}
		SocialPopups.action(from, name, Component.translatable("orbit_cosmetic.server_party.invited", name), Component.translatable("orbit_cosmetic.server_party.invited.detail"),
			Component.translatable("orbit_cosmetic.server_party.accept"), () -> send(commands.accept().formatted(name)));
	}

	/**
	 * The player left the Orbit party: they leave the server's too. Its leader first hands it to a member who came
	 * and plays here (or ends it when there is none).
	 */
	static void left(Social.Party party, @Nullable UUID me) {
		Commands commands = commands();
		UUID was = leader;
		leader = null;
		if (commands == null || was == null || network == null || !network.equals(PartyLink.network(currentServer() == null ? "" : currentServer()))) {
			return;
		}
		if (!was.equals(me)) {
			send(commands.leave());
			return;
		}
		Social.Person next = party.members().stream().filter(member -> !member.uuid().equals(me) && members.contains(member.uuid()))
			.filter(member -> PartyLink.playsHere(member.uuid(), PartyLink.get().status(member.uuid()))).findFirst().orElse(null);
		if (next == null) {
			send(commands.disband());
		} else {
			send(commands.promote().formatted(next.name()));
			send(commands.leave());
		}
	}

	/**
	 * Brings the party here (the quick menu's Warp party): the server's own warp for its party's leader, else a vote
	 * to come here.
	 */
	public static void warp() {
		Commands commands = commands();
		UUID me = OrbitClient.get().social().me();
		if (commands != null && me != null && me.equals(leader)) {
			send(commands.warp());
			OrbitClient.LOGGER.info("Party: warping the server's party");
		} else {
			PartyVote.warp();
		}
	}
}
