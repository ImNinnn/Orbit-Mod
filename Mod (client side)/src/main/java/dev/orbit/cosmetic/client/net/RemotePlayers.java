package dev.orbit.cosmetic.client.net;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.render.CosmeticRegistry;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The other Orbit players on the current server, as Orbit Cloud reports them: what they wear and which emote they
 * play. Their cosmetics are evaluated locally; only the files travel, once each, and are cached on disk.
 */
public final class RemotePlayers {
	private final OrbitClient orbit;
	private final AssetCache cache;
	private BackendSync backend;
	private final Map<UUID, Wearer> remote = new HashMap<>();
	private final Set<AssetId> readingCache = new HashSet<>();
	private long ticks;

	public RemotePlayers(OrbitClient orbit, AssetCache cache) {
		this.orbit = orbit;
		this.cache = cache;
	}

	void setBackend(BackendSync backend) {
		this.backend = backend;
	}

	public Map<UUID, Wearer> wearers() {
		return remote;
	}

	/** Every cosmetic and emote file other players use right now. */
	public Set<AssetId> assetsInUse() {
		Set<AssetId> assets = new HashSet<>();
		for (Wearer wearer : remote.values()) {
			for (Wearer.Worn worn : wearer.worn()) {
				assets.add(worn.asset());
			}
			if (wearer.emote() != null) {
				assets.add(wearer.emote().asset);
			}
		}
		return assets;
	}

	public void clear() {
		remote.clear();
	}

	/** Sets what another player wears. */
	void apply(UUID player, List<EquippedCosmetic> cosmetics, Wearer.Emote emote) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && player.equals(minecraft.player.getUUID())) {
			return;
		}
		Wearer wearer = remote.computeIfAbsent(player, Wearer::new);
		wearer.showIcon = true;
		for (EquippedCosmetic cosmetic : cosmetics) {
			ensureAsset(cosmetic.asset());
		}
		wearer.setWorn(cosmetics);
		if (emote != null && orbit.config().showOtherEmotes) {
			ensureAsset(emote.asset);
			wearer.setEmote(emote);
		} else {
			wearer.setEmote(null);
		}
	}

	void remove(UUID player) {
		remote.remove(player);
	}

	// ---------------------------------------------------------------- assets

	/** Makes sure a file is being loaded from the library, the cache or Orbit Cloud. */
	public void ensureAsset(AssetId id) {
		CosmeticRegistry registry = orbit.registry();
		if (registry.isKnown(id) || readingCache.contains(id)) {
			return;
		}
		LocalCosmetic local = orbit.library().find(id);
		if (local != null && local.model() != null) {
			registry.prepare(id, local.model());
			return;
		}
		EmoteLibrary.EmoteFile emote = orbit.emotes().findFile(id);
		if (emote != null && emote.model() != null) {
			registry.prepare(id, emote.model());
			return;
		}
		readingCache.add(id);
		Util.ioPool().execute(() -> {
			byte[] bytes = cache.has(id) ? cache.read(id) : null;
			Minecraft.getInstance().execute(() -> {
				readingCache.remove(id);
				if (bytes != null) {
					registry.prepare(id, bytes, limits());
				} else if (backend != null && orbit.config().downloadFromServers) {
					backend.requestAsset(id);
				}
			});
		});
	}

	/** Caches and loads file bytes already verified against their hash. */
	public void acceptAsset(AssetId id, byte[] bytes) {
		long maxCache = orbit.config().maxCacheMegabytes * 1024L * 1024L;
		Util.ioPool().execute(() -> {
			cache.write(id, bytes);
			cache.trim(maxCache);
		});
		orbit.registry().prepare(id, bytes, limits());
	}

	public ModelLimits limits() {
		return orbit.config().modelLimits();
	}

	public void tick() {
		if (++ticks % 20 != 0) {
			return;
		}
		// Retry files that are not loaded yet (for example after a failed download).
		for (Wearer wearer : remote.values()) {
			for (Wearer.Worn worn : wearer.worn()) {
				ensureAsset(worn.asset());
			}
			if (wearer.emote() != null) {
				ensureAsset(wearer.emote().asset);
			}
		}
	}
}
