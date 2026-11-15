package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.host.Hosting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Following the party's leader. When the leader opens a singleplayer world it is hosted at once (with e4mc), and
 * when they join a server or their world goes online, the other members are invited to follow (a popup with Join).
 * A member who is not with the leader can join them from the social screen.
 */
public final class PartyFollow {
	/** Where the members were last invited to (so each place is told once), and the world last hosted for them. */
	private static @Nullable String announced;
	private static @Nullable String hostedWorld;

	private PartyFollow() {
	}

	/** Where a player can be joined: a server's address, or their hosted world's online address (and its name). */
	public record Place(String address, String name) {
	}

	/** Where this player plays, if it can be joined: the server, or the hosted world once it is online. */
	private static @Nullable Place ownPlace() {
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = minecraft.getCurrentServer();
		if (server != null && !minecraft.isLocalServer() && minecraft.player != null) {
			return new Place(server.ip, server.name == null || server.name.isBlank() ? server.ip : server.name);
		}
		String hosted = Hosting.address();
		var integrated = minecraft.getSingleplayerServer();
		if (hosted != null && integrated != null) {
			return new Place(hosted, integrated.getWorldData().getLevelName());
		}
		return null;
	}

	/** Called every tick: the leader's world is hosted, and members are invited wherever the leader goes. */
	static void tick(Social social) {
		Social.Party party = social.party();
		if (party == null || !party.leader().equals(social.me()) || party.members().size() < 2) {
			announced = null;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		var integrated = minecraft.getSingleplayerServer();
		if (integrated != null && minecraft.player != null && !integrated.isPublished() && Hosting.e4mcInstalled()) {
			String world = integrated.getWorldData().getLevelName();
			if (!world.equals(hostedWorld)) {
				// Hosted once per world opened (if the leader stops hosting it, it stays stopped).
				hostedWorld = world;
				Hosting.start(integrated.getDefaultGameType(), integrated.getWorldData().isAllowCommands());
			}
		} else if (integrated == null) {
			hostedWorld = null;
		}
		Place place = ownPlace();
		if (place == null) {
			announced = null;
		} else if (!place.address().equals(announced)) {
			announced = place.address();
			invite(place);
		}
	}

	private static void invite(Place place) {
		OrbitClient.LOGGER.info("Party: inviting the members to follow to {}", place.address());
		PartyLink.get().signal("follow", place.address(), place.name());
	}

	/** The leader invites the members again (the quick menu's Warp party, off the servers with a party of their own). */
	public static boolean inviteAgain() {
		Place place = ownPlace();
		if (place == null) {
			return false;
		}
		announced = place.address();
		invite(place);
		return true;
	}

	/** The leader's game says where to follow them: offered as an invite, unless this player is there already. */
	static void followSignal(Social social, UUID from, String name, String address, String world) {
		Social.Party party = social.party();
		if (party == null || !party.leader().equals(from) || from.equals(social.me()) || here(address)) {
			return;
		}
		SocialPopups.follow(from, name, address, world);
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

	/** Where the party's leader can be joined, when this player is in a party, not its leader, and not with them. */
	public static @Nullable Place leaderPlace(Social social) {
		Social.Party party = social.party();
		if (party == null || party.leader().equals(social.me())) {
			return null;
		}
		PartyLink.Status status = PartyLink.get().status(party.leader());
		if (status == null || !status.playing() || PartyLink.playsHere(party.leader(), status)) {
			return null;
		}
		if (!status.server().isEmpty()) {
			return new Place(status.server(), status.server());
		}
		if (!status.extra().host().isEmpty()) {
			return new Place(status.extra().host(), status.world().isEmpty() ? status.extra().host() : status.world());
		}
		return null;
	}

	/** Joins the party's leader where they play. */
	public static void joinLeader(@Nullable Screen parent, Social social) {
		Place place = leaderPlace(social);
		if (place != null) {
			ServerLinks.join(parent, place.address(), place.name());
		}
	}
}
