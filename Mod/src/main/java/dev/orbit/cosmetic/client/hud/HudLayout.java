package dev.orbit.cosmetic.client.hud;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.social.FloatingChats;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The HUD components and chat windows, by profile, kept in {@code config/orbit_hud.json}.
 * <p>
 * Each profile has its own components and chat windows; pinned ones are in every profile. The list
 * {@link #components()} is what shows: the pinned components and the active profile's.
 * <p>
 * Linked components form a group that acts as one: each member but one (the group's root) is attached to a side of
 * another member, so when one grows the others move along and they stay edge to edge. The root is the member on the
 * side of the screen the group holds on to, so the group grows away from that side.
 */
public final class HudLayout {
	/** A set of components and chat windows to switch to. */
	public static final class Profile {
		public String name;
		final List<HudComponent> components = new ArrayList<>();
		JsonArray chats = new JsonArray();

		Profile(String name) {
			this.name = name;
		}
	}

	private static final int MAX_COMPONENTS = 64;
	private static final int MAX_PROFILES = 16;
	private static final List<HudComponent> COMPONENTS = new ArrayList<>();
	private static final List<Profile> PROFILES = new ArrayList<>();
	private static int active;
	private static boolean loaded;
	/** Every component hidden at once (from the quick menu), until shown again. */
	private static boolean allHidden;

	private HudLayout() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("orbit_hud.json");
	}

	/** The components shown: the pinned ones and the active profile's. */
	public static List<HudComponent> components() {
		if (!loaded) {
			loaded = true;
			load();
		}
		return COMPONENTS;
	}

	public static HudComponent add(HudComponent.Type type, int screenWidth, int screenHeight) {
		HudComponent component = new HudComponent(UUID.randomUUID(), type);
		// In the middle, a little apart from the last one added.
		int offset = components().size() % 6 * 10;
		component.place((screenWidth - component.scaledWidth()) / 2 + offset, (screenHeight - component.scaledHeight()) / 2 + offset, screenWidth, screenHeight);
		if (COMPONENTS.size() < MAX_COMPONENTS) {
			COMPONENTS.add(component);
		}
		save();
		return component;
	}

	public static boolean allHidden() {
		components();
		return allHidden;
	}

	public static void setAllHidden(boolean hidden) {
		components();
		allHidden = hidden;
		save();
	}

	public static @Nullable HudComponent find(@Nullable UUID id) {
		if (id == null) {
			return null;
		}
		for (HudComponent component : components()) {
			if (component.id.equals(id)) {
				return component;
			}
		}
		return null;
	}

	/** Removes a component and everything linked to it. */
	public static void remove(HudComponent component) {
		COMPONENTS.removeAll(group(component));
		save();
	}

	// ---------------------------------------------------------------- profiles

	public static List<Profile> profiles() {
		components();
		return PROFILES;
	}

	public static Profile activeProfile() {
		components();
		return PROFILES.get(active);
	}

	/** Switches to a profile: its components and chat windows replace the active one's (pinned ones stay). */
	public static void switchTo(Profile profile) {
		components();
		int index = PROFILES.indexOf(profile);
		if (index < 0 || index == active) {
			return;
		}
		stash();
		active = index;
		COMPONENTS.removeIf(component -> !component.pinned);
		COMPONENTS.addAll(profile.components);
		FloatingChats.replaceUnpinned(profile.chats);
		save();
	}

	/** The next profile (the key binding goes round them). */
	public static Profile next() {
		components();
		return PROFILES.get((active + 1) % PROFILES.size());
	}

	/** A new profile: empty, or a copy of the active one. */
	public static @Nullable Profile create(String name, boolean copy) {
		components();
		if (PROFILES.size() >= MAX_PROFILES) {
			return null;
		}
		stash();
		Profile profile = new Profile(uniqueName(name));
		if (copy) {
			Profile current = PROFILES.get(active);
			for (HudComponent component : current.components) {
				HudComponent clone = HudComponent.fromJson(component.toJson());
				if (clone != null) {
					profile.components.add(clone);
				}
			}
			profile.chats = current.chats.deepCopy();
		}
		PROFILES.add(profile);
		switchTo(profile);
		return profile;
	}

	public static void rename(Profile profile, String name) {
		String stripped = name.strip();
		if (!stripped.isEmpty() && !stripped.equals(profile.name)) {
			String old = profile.name;
			profile.name = uniqueName(stripped);
			// Quick menu squares that switch to it keep doing so.
			dev.orbit.cosmetic.client.gui.QuickMenuLayout.profileRenamed(old, profile.name);
			save();
		}
	}

	/** Deletes a profile (there is always one left); deleting the active one switches to another first. */
	public static void delete(Profile profile) {
		components();
		if (PROFILES.size() <= 1 || !PROFILES.contains(profile)) {
			return;
		}
		if (PROFILES.indexOf(profile) == active) {
			switchTo(PROFILES.get(active == 0 ? 1 : 0));
		}
		Profile current = PROFILES.get(active);
		PROFILES.remove(profile);
		active = PROFILES.indexOf(current);
		save();
	}

	private static String uniqueName(String name) {
		String base = name.isBlank() ? "Profile" : name.strip();
		String candidate = base;
		int number = 2;
		while (true) {
			String taken = candidate;
			if (PROFILES.stream().noneMatch(profile -> profile.name.equalsIgnoreCase(taken))) {
				return candidate;
			}
			candidate = base + " " + number++;
		}
	}

	/** Keeps the active profile's own components and chat windows in it (the pinned ones are kept apart). */
	private static void stash() {
		Profile profile = PROFILES.get(active);
		profile.components.clear();
		COMPONENTS.stream().filter(component -> !component.pinned).forEach(profile.components::add);
		profile.chats = FloatingChats.toJson(false);
	}

	// ---------------------------------------------------------------- groups and positions

	/** Everything linked to a component (itself included), root first. */
	public static List<HudComponent> group(HudComponent component) {
		HudComponent root = root(component);
		List<HudComponent> members = new ArrayList<>();
		Deque<HudComponent> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty() && members.size() <= MAX_COMPONENTS) {
			HudComponent next = queue.poll();
			if (members.contains(next)) {
				continue;
			}
			members.add(next);
			queue.addAll(children(next));
		}
		return members;
	}

	public static boolean linked(HudComponent component) {
		return component.parent != null || !children(component).isEmpty();
	}

	static HudComponent root(HudComponent component) {
		HudComponent current = component;
		for (int depth = 0; depth < MAX_COMPONENTS && current.parent != null; depth++) {
			HudComponent parent = find(current.parent);
			if (parent == null) {
				current.parent = null;
				break;
			}
			current = parent;
		}
		return current;
	}

	private static List<HudComponent> children(HudComponent component) {
		List<HudComponent> children = new ArrayList<>();
		for (HudComponent other : components()) {
			if (component.id.equals(other.parent)) {
				children.add(other);
			}
		}
		return children;
	}

	/** Where a component is (left, top) on a screen of this size: as placed, attached in its group, or docked by the hotbar. */
	static int[] position(HudComponent component, int screenWidth, int screenHeight) {
		if (component.dock != HudComponent.DOCK_NONE) {
			return dockPosition(component, component.dock, screenWidth, screenHeight);
		}
		if (component.parent == null && children(component).isEmpty()) {
			int[] at = {component.ownX(screenWidth), component.ownY(screenHeight)};
			at[1] += makeRoom(Map.of(component, at), screenWidth, screenHeight);
			return at;
		}
		Map<HudComponent, int[]> positions = groupPositions(root(component), screenWidth, screenHeight);
		int down = makeRoom(positions, screenWidth, screenHeight);
		int[] position = positions.get(component);
		return position != null ? new int[] {position[0], position[1] + down} : new int[] {component.ownX(screenWidth), component.ownY(screenHeight)};
	}

	/**
	 * How far down a component (or a linked group, all of it) goes to make room for the status effect icons and the
	 * boss bars it is set to avoid: below what it would cover. Not in the editor, where things show where they are put.
	 */
	private static int makeRoom(Map<HudComponent, int[]> positions, int screenWidth, int screenHeight) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || dev.orbit.cosmetic.client.compat.Compat.screen() instanceof HudEditorScreen) {
			return 0;
		}
		int wanted = 0;
		for (HudComponent member : positions.keySet()) {
			wanted |= member.avoid;
		}
		if (wanted == 0) {
			return 0;
		}
		// What is in the way: {left, top, right, bottom}.
		List<int[]> taken = new ArrayList<>();
		if ((wanted & HudComponent.AVOID_EFFECTS) != 0) {
			// Minecraft's effect icons: good ones in a row along the top right, bad ones in a row under it.
			int good = 0;
			int bad = 0;
			for (var effect : minecraft.player.getActiveEffects()) {
				if (effect.showIcon()) {
					if (effect.getEffect().value().isBeneficial()) {
						good++;
					} else {
						bad++;
					}
				}
			}
			if (good > 0) {
				taken.add(new int[] {screenWidth - 25 * good, 1, screenWidth, 25, HudComponent.AVOID_EFFECTS});
			}
			if (bad > 0) {
				taken.add(new int[] {screenWidth - 25 * bad, 27, screenWidth, 51, HudComponent.AVOID_EFFECTS});
			}
		}
		if ((wanted & HudComponent.AVOID_BOSS_BARS) != 0) {
			// Boss bars: 182 wide in the middle of the top, one every 19 pixels from 12 down (a third of the screen at most).
			int bars = ((dev.orbit.cosmetic.client.mixin.BossHealthOverlayAccessor) dev.orbit.cosmetic.client.compat.Compat.bossOverlay()).orbit$events().size();
			if (bars > 0) {
				int bottom = Math.min(12 + (bars - 1) * 19 + 5, screenHeight / 3 + 5);
				taken.add(new int[] {screenWidth / 2 - 91, 0, screenWidth / 2 + 91, bottom, HudComponent.AVOID_BOSS_BARS});
			}
		}
		int down = 0;
		for (Map.Entry<HudComponent, int[]> entry : positions.entrySet()) {
			HudComponent member = entry.getKey();
			int[] at = entry.getValue();
			for (int[] area : taken) {
				boolean across = at[0] < area[2] && at[0] + member.scaledWidth() > area[0];
				boolean over = at[1] < area[3] && at[1] + member.scaledHeight() > area[1];
				if ((member.avoid & area[4]) != 0 && across && over) {
					down = Math.max(down, area[3] + 2 - at[1]);
				}
			}
		}
		return down;
	}

	/** Each member's place: the root where it was put, the others along the sides they are attached to, all kept on screen. */
	private static Map<HudComponent, int[]> groupPositions(HudComponent root, int screenWidth, int screenHeight) {
		Map<HudComponent, int[]> positions = new IdentityHashMap<>();
		positions.put(root, new int[] {root.ownX(screenWidth), root.ownY(screenHeight)});
		Deque<HudComponent> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			HudComponent parent = queue.poll();
			int[] at = positions.get(parent);
			for (HudComponent child : children(parent)) {
				if (positions.containsKey(child)) {
					continue;
				}
				float unit = parent.scale;
				int gap = Math.round(child.gap * unit);
				int along = Math.round(child.offset * unit);
				int[] place = switch (child.side) {
					case HudComponent.SIDE_LEFT -> new int[] {at[0] - child.scaledWidth() - gap, at[1] + along};
					case HudComponent.SIDE_BELOW -> new int[] {at[0] + along, at[1] + parent.scaledHeight() + gap};
					case HudComponent.SIDE_ABOVE -> new int[] {at[0] + along, at[1] - child.scaledHeight() - gap};
					default -> new int[] {at[0] + parent.scaledWidth() + gap, at[1] + along};
				};
				positions.put(child, place);
				queue.add(child);
			}
		}
		// The whole group moves back on screen if it goes past an edge.
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Map.Entry<HudComponent, int[]> entry : positions.entrySet()) {
			int[] at = entry.getValue();
			minX = Math.min(minX, at[0]);
			minY = Math.min(minY, at[1]);
			maxX = Math.max(maxX, at[0] + entry.getKey().scaledWidth());
			maxY = Math.max(maxY, at[1] + entry.getKey().scaledHeight());
		}
		int dx = maxX > screenWidth ? screenWidth - maxX : 0;
		int dy = maxY > screenHeight ? screenHeight - maxY : 0;
		dx = minX + dx < 0 ? -minX : dx;
		dy = minY + dy < 0 ? -minY : dy;
		if (dx != 0 || dy != 0) {
			for (int[] at : positions.values()) {
				at[0] += dx;
				at[1] += dy;
			}
		}
		return positions;
	}

	/**
	 * Where hotbar armour docks: opposite the off hand slot, or beside it (where the slot is when the off hand holds
	 * something, where it would be when it does not).
	 */
	static int[] dockPosition(HudComponent component, int dock, int screenWidth, int screenHeight) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean offhandLeft = minecraft.options.mainHand().get() == HumanoidArm.RIGHT;
		int w = component.scaledWidth();
		int y = screenHeight - component.scaledHeight();
		int hotbarLeft = screenWidth / 2 - 91;
		int hotbarRight = screenWidth / 2 + 91;
		if (dock == HudComponent.DOCK_OPPOSITE) {
			return new int[] {offhandLeft ? hotbarRight + 7 : hotbarLeft - 7 - w, y};
		}
		boolean holding = minecraft.player != null && !minecraft.player.getOffhandItem().isEmpty();
		// The off hand slot is 29 pixels out from the hotbar, 22 wide.
		int out = holding ? 29 + 7 : 7;
		return new int[] {offhandLeft ? hotbarLeft - out - w : hotbarRight + out, y};
	}

	/**
	 * Links two components that touch (and so their groups): the one moved last joins the other's group, then the
	 * group is rooted again. Returns false when they are in the same group already.
	 */
	public static boolean link(HudComponent a, HudComponent b, int screenWidth, int screenHeight) {
		if (root(a) == root(b) || a.dock != HudComponent.DOCK_NONE || b.dock != HudComponent.DOCK_NONE) {
			return false;
		}
		// Pinned or not: the whole group is the same.
		boolean pinned = a.pinned || b.pinned;
		List<HudComponent> members = new ArrayList<>(group(a));
		members.addAll(group(b));
		Map<HudComponent, int[]> positions = snapshot(members, screenWidth, screenHeight);
		// b's group hangs from a, then the whole group is rooted on its side of the screen.
		rebuild(members, positions, root(a), screenWidth, screenHeight, b, a);
		members.forEach(member -> member.pinned = pinned);
		reroot(a, screenWidth, screenHeight);
		save();
		return true;
	}

	/** Unlinks two components linked to each other: the group splits in two, each keeping its place. */
	public static void unlink(HudComponent a, HudComponent b, int screenWidth, int screenHeight) {
		HudComponent child = b.id.equals(a.parent) ? a : a.id.equals(b.parent) ? b : null;
		if (child == null) {
			return;
		}
		List<HudComponent> members = group(a);
		Map<HudComponent, int[]> positions = snapshot(members, screenWidth, screenHeight);
		child.parent = null;
		for (HudComponent member : members) {
			int[] at = positions.get(member);
			if (member.parent == null) {
				member.place(at[0], at[1], screenWidth, screenHeight);
			}
		}
		reroot(a, screenWidth, screenHeight);
		reroot(b, screenWidth, screenHeight);
		save();
	}

	/** Whether two components are linked to each other directly. */
	public static boolean linkedTogether(HudComponent a, HudComponent b) {
		return b.id.equals(a.parent) || a.id.equals(b.parent);
	}

	/**
	 * Roots a group again, on the member on the side of the screen it holds on to (the left-most in the left third,
	 * the right-most in the right third, the middle one in between), keeping everything where it is.
	 */
	public static void reroot(HudComponent member, int screenWidth, int screenHeight) {
		List<HudComponent> members = group(member);
		if (members.size() < 2) {
			return;
		}
		Map<HudComponent, int[]> positions = snapshot(members, screenWidth, screenHeight);
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		for (HudComponent each : members) {
			minX = Math.min(minX, positions.get(each)[0]);
			maxX = Math.max(maxX, positions.get(each)[0] + each.scaledWidth());
		}
		float middle = (minX + maxX) / 2F;
		HudComponent root = members.getFirst();
		float best = Float.MAX_VALUE;
		for (HudComponent each : members) {
			int[] at = positions.get(each);
			float score = middle < screenWidth / 3F ? at[0] : middle > screenWidth * 2 / 3F ? -(at[0] + each.scaledWidth())
				: Math.abs(at[0] + each.scaledWidth() / 2F - middle);
			if (score < best) {
				best = score;
				root = each;
			}
		}
		rebuild(members, positions, root, screenWidth, screenHeight, null, null);
	}

	private static Map<HudComponent, int[]> snapshot(List<HudComponent> members, int screenWidth, int screenHeight) {
		Map<HudComponent, int[]> positions = new IdentityHashMap<>();
		for (HudComponent each : members) {
			positions.put(each, position(each, screenWidth, screenHeight).clone());
		}
		return positions;
	}

	/**
	 * Attaches the members again from {@code root}, following the links they have (and a new one between
	 * {@code extraChild} and {@code extraParent}), each on the side it is on now.
	 */
	private static void rebuild(List<HudComponent> members, Map<HudComponent, int[]> positions, HudComponent root, int screenWidth, int screenHeight,
		@Nullable HudComponent extraChild, @Nullable HudComponent extraParent) {
		// The links as pairs, whichever way they pointed.
		List<HudComponent[]> links = new ArrayList<>();
		for (HudComponent each : members) {
			HudComponent parent = each.parent == null ? null : find(each.parent);
			if (parent != null && members.contains(parent)) {
				links.add(new HudComponent[] {each, parent});
			}
		}
		if (extraChild != null && extraParent != null) {
			links.add(new HudComponent[] {extraChild, extraParent});
		}
		members.forEach(each -> each.parent = null);
		Set<HudComponent> done = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<HudComponent> queue = new ArrayDeque<>();
		queue.add(root);
		done.add(root);
		while (!queue.isEmpty()) {
			HudComponent from = queue.poll();
			for (HudComponent[] link : links) {
				HudComponent to = link[0] == from ? link[1] : link[1] == from ? link[0] : null;
				if (to == null || done.contains(to)) {
					continue;
				}
				attach(to, from, positions);
				done.add(to);
				queue.add(to);
			}
		}
		int[] at = positions.get(root);
		root.dock = HudComponent.DOCK_NONE;
		root.place(at[0], at[1], screenWidth, screenHeight);
	}

	/** Attaches {@code child} to the side of {@code parent} it is on, at its distance and along offset. */
	private static void attach(HudComponent child, HudComponent parent, Map<HudComponent, int[]> positions) {
		int[] c = positions.get(child);
		int[] p = positions.get(parent);
		float unit = Math.max(0.01F, parent.scale);
		child.parent = parent.id;
		child.dock = HudComponent.DOCK_NONE;
		if (c[0] >= p[0] + parent.scaledWidth() - 2) {
			child.side = HudComponent.SIDE_RIGHT;
			child.gap = (c[0] - p[0] - parent.scaledWidth()) / unit;
			child.offset = (c[1] - p[1]) / unit;
		} else if (c[0] + child.scaledWidth() <= p[0] + 2) {
			child.side = HudComponent.SIDE_LEFT;
			child.gap = (p[0] - c[0] - child.scaledWidth()) / unit;
			child.offset = (c[1] - p[1]) / unit;
		} else if (c[1] >= p[1] + parent.scaledHeight() - 2) {
			child.side = HudComponent.SIDE_BELOW;
			child.gap = (c[1] - p[1] - parent.scaledHeight()) / unit;
			child.offset = (c[0] - p[0]) / unit;
		} else {
			child.side = HudComponent.SIDE_ABOVE;
			child.gap = (p[1] - c[1] - child.scaledHeight()) / unit;
			child.offset = (c[0] - p[0]) / unit;
		}
		child.gap = Math.max(0, child.gap);
	}

	// ---------------------------------------------------------------- saving

	public static void save() {
		if (!loaded) {
			// Read first, so saving early never drops what was there.
			components();
		}
		stash();
		JsonObject root = new JsonObject();
		JsonArray profiles = new JsonArray();
		for (Profile profile : PROFILES) {
			JsonObject json = new JsonObject();
			json.addProperty("name", profile.name);
			JsonArray components = new JsonArray();
			profile.components.forEach(component -> components.add(component.toJson()));
			json.add("components", components);
			json.add("chats", profile.chats);
			profiles.add(json);
		}
		root.add("profiles", profiles);
		root.addProperty("active", active);
		JsonArray pinned = new JsonArray();
		COMPONENTS.stream().filter(component -> component.pinned).forEach(component -> pinned.add(component.toJson()));
		root.add("pinned", pinned);
		root.add("pinnedChats", FloatingChats.toJson(true));
		root.addProperty("hidden", allHidden);
		try {
			Path temp = file().resolveSibling("orbit_hud.json.tmp");
			Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save the HUD components: {}", e.toString());
		}
	}

	private static void readComponents(@Nullable JsonElement element, List<HudComponent> into, boolean pinned) {
		if (element instanceof JsonArray array) {
			for (JsonElement each : array) {
				if (each instanceof JsonObject json && into.size() < MAX_COMPONENTS) {
					HudComponent component = HudComponent.fromJson(json);
					if (component != null) {
						component.pinned = pinned;
						into.add(component);
					}
				}
			}
		}
	}

	private static void load() {
		try {
			if (Files.isRegularFile(file())) {
				JsonElement parsed = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
				if (parsed instanceof JsonObject root) {
					allHidden = root.has("hidden") && root.get("hidden").getAsBoolean();
					if (root.get("profiles") instanceof JsonArray profiles) {
						for (JsonElement element : profiles) {
							if (element instanceof JsonObject json && PROFILES.size() < MAX_PROFILES) {
								Profile profile = new Profile(json.has("name") ? json.get("name").getAsString() : "Profile");
								readComponents(json.get("components"), profile.components, false);
								profile.chats = json.get("chats") instanceof JsonArray chats ? chats : new JsonArray();
								PROFILES.add(profile);
							}
						}
					} else {
						// Orbit 1.0.6 and older: one set of components and chat windows, now the first profile.
						Profile profile = new Profile("Default");
						readComponents(root.get("components"), profile.components, false);
						profile.chats = root.get("chats") instanceof JsonArray chats ? chats : new JsonArray();
						PROFILES.add(profile);
					}
					readComponents(root.get("pinned"), COMPONENTS, true);
					if (root.get("pinnedChats") instanceof JsonArray chats) {
						FloatingChats.restore(chats, true);
					}
					active = root.has("active") ? root.get("active").getAsInt() : 0;
				}
			}
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.warn("Could not read the HUD components: {}", e.toString());
		}
		if (PROFILES.isEmpty()) {
			PROFILES.add(new Profile("Default"));
		}
		active = Math.clamp(active, 0, PROFILES.size() - 1);
		Profile profile = PROFILES.get(active);
		COMPONENTS.addAll(profile.components);
		FloatingChats.restore(profile.chats, false);
	}
}
