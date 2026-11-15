package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
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
 * The Orbit party on servers with their own party system (Hypixel): its commands, sent only when the player asks
 * (a click on Invite or Accept, or an Orbit party action of theirs: leave, remove, hand over the lead), one at a
 * time with the server's chat cooldown in mind. Nothing is sent on its own.
 */
public final class ServerParty {
	/** A server's party commands ({@code %s} is a player's name). */
	private record Commands(String invite, String accept, String leave, String kick, String warp, String promote, String disband) {
	}

	private static final Commands HYPIXEL = new Commands("p invite %s", "p accept %s", "p leave", "p kick %s", "p warp", "p promote %s", "p disband");
	/** Between two commands: Hypixel refuses commands sent faster. */
	private static final long SPACING_MS = 1200;

	private static final Deque<String> QUEUE = new ArrayDeque<>();
	private static long nextAt;
	/** The members already offered to invite (not offered again until they leave the server or the party changes). */
	private static final Set<UUID> OFFERED = new HashSet<>();
	private static @Nullable String offeredFor;

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

	private static void send(String command) {
		QUEUE.addLast(command);
	}

	/** Called every tick: sends the next command when it is time, and offers to invite members who came on. */
	static void tick(Social social) {
		Minecraft minecraft = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (!QUEUE.isEmpty() && now >= nextAt) {
			if (minecraft.player == null || commands() == null) {
				QUEUE.clear();
			} else {
				minecraft.player.connection.sendCommand(QUEUE.pollFirst());
				nextAt = now + SPACING_MS;
			}
		}
		Social.Party party = social.party();
		Commands commands = commands();
		String key = party == null || commands == null ? null : party.chat() + "@" + currentServer();
		if (key == null || !key.equals(offeredFor)) {
			OFFERED.clear();
			offeredFor = key;
		}
		if (key == null || !party.leader().equals(social.me())) {
			return;
		}
		// The leader: members on this server not offered yet.
		List<Social.Person> here = party.members().stream().filter(member -> !member.uuid().equals(social.me()) && !OFFERED.contains(member.uuid()))
			.filter(member -> {
				PartyLink.Status status = PartyLink.get().status(member.uuid());
				return status != null && commandsFor(status.server()) == commands;
			}).toList();
		if (here.isEmpty()) {
			return;
		}
		here.forEach(member -> OFFERED.add(member.uuid()));
		Social.Person first = here.getFirst();
		Component detail = here.size() == 1 ? Component.translatable("orbit_cosmetic.server_party.offer.one", first.name())
			: Component.translatable("orbit_cosmetic.server_party.offer.many", here.size());
		SocialPopups.action(first.uuid(), first.name(), Component.translatable("orbit_cosmetic.server_party.offer"), detail,
			Component.translatable("orbit_cosmetic.server_party.invite"), () -> invite(here));
	}

	/** Invites the members on this server to the server's party, and tells their games (they offer to accept). */
	private static void invite(List<Social.Person> members) {
		Commands commands = commands();
		if (commands == null) {
			return;
		}
		members.forEach(member -> send(commands.invite().formatted(member.name())));
		PartyLink.get().signal("server_party_invite");
	}

	/** Another member's game says it invited this player in the server's party: offered to accept, if they lead. */
	static void signal(Social social, UUID from, String name, String signal) {
		Social.Party party = social.party();
		Commands commands = commands();
		if (!signal.equals("server_party_invite") || commands == null || party == null || !party.leader().equals(from) || !name.matches("[A-Za-z0-9_]{1,16}")) {
			return;
		}
		SocialPopups.action(from, name, Component.translatable("orbit_cosmetic.server_party.invited", name), Component.translatable("orbit_cosmetic.server_party.invited.detail"),
			Component.translatable("orbit_cosmetic.server_party.accept"), () -> send(commands.accept().formatted(name)));
	}

	/**
	 * Who leads once the leader leaves: the member there the longest who plays on the leader's server (so the
	 * server's party keeps a leader in it), else the member there the longest. Null when nobody else is in it.
	 */
	static Social.@Nullable Person successor(Social.Party party, UUID me) {
		String server = currentServer();
		Social.Person first = null;
		for (Social.Person member : party.members()) {
			if (member.uuid().equals(me)) {
				continue;
			}
			if (first == null) {
				first = member;
			}
			PartyLink.Status status = PartyLink.get().status(member.uuid());
			if (server != null && PartyLink.playsHere(member.uuid(), status)) {
				return member;
			}
		}
		return first;
	}

	/**
	 * The player left the Orbit party: they leave the server's too. Its leader first hands it to the next leader
	 * (it ends only when nobody is left to lead it).
	 */
	static void left(boolean wasLeader, Social.@Nullable Person next) {
		Commands commands = commands();
		if (commands == null) {
			return;
		}
		if (!wasLeader) {
			send(commands.leave());
		} else if (next == null) {
			send(commands.disband());
		} else {
			send(commands.promote().formatted(next.name()));
			send(commands.leave());
		}
	}

	/** The leader removed a member from the Orbit party: from the server's too. */
	static void removed(String name) {
		Commands commands = commands();
		if (commands != null) {
			send(commands.kick().formatted(name));
		}
	}

	/** The leader handed the Orbit party over: the server's too. */
	static void promoted(String name) {
		Commands commands = commands();
		if (commands != null) {
			send(commands.promote().formatted(name));
		}
	}

	/** The leader brings the party's players to them (the quick menu's Warp party). */
	public static void warp() {
		Commands commands = commands();
		if (commands != null) {
			send(commands.warp());
			OrbitClient.LOGGER.info("Party: warping the server's party");
		}
	}
}
