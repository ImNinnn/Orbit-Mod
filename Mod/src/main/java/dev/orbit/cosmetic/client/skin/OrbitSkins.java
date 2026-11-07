package dev.orbit.cosmetic.client.skin;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.library.OrbitState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skins and capes. The player picks a skin from {@code Orbit/Skins} and a cape their account owns; they see it at
 * once, Orbit Cloud shares it with other Orbit players, and their Minecraft account is changed too (through
 * {@link MojangAccount}, at most once a minute). Skins are shared as the textures Mojang made of them, so a skin is
 * shared once Mojang accepted it, and switching back to one it already has is instant for everyone. Skins and capes
 * are not part of the featured catalog, so feature bans do not limit them.
 */
public final class OrbitSkins {
	private final OrbitState state;
	private final SkinLibrary library;
	private final SkinTextures textures;
	private final MojangAccount account;
	private final Runnable shareChanged;
	/** What other Orbit players wear, by their UUID on this server. */
	private final Map<UUID, SkinSignal> remote = new HashMap<>();
	private String lastProblem;

	/** @param shareChanged called when what Orbit Cloud should share changed (publishes the presence) */
	public OrbitSkins(OrbitState state, SkinLibrary library, SkinTextures textures, Runnable shareChanged) {
		this.state = state;
		this.library = library;
		this.textures = textures;
		this.shareChanged = shareChanged;
		this.account = new MojangAccount(Minecraft.getInstance()::execute, this::accepted, this::accountChanged);
	}

	public SkinLibrary library() {
		return library;
	}

	public SkinTextures textures() {
		return textures;
	}

	public MojangAccount account() {
		return account;
	}

	// ---------------------------------------------------------------- choosing

	/** The skin worn (from Orbit/Skins), or null for the account's own. */
	public SkinLibrary.LocalSkin skin() {
		return library.find(state.skin());
	}

	/** The cape worn: an ID, "" for none, or null for the account's own. */
	public String cape() {
		return state.cape();
	}

	/** Wears a skin: at once here, through Orbit Cloud as soon as Mojang has it, and on the Minecraft account. */
	public void wearSkin(SkinLibrary.LocalSkin skin) {
		state.setSkin(skin.key());
		state.save();
		account.setSkin(skin, state.skinTextures().get(skin.sha256()));
		shareChanged.run();
	}

	/** Wears a cape the account owns ("" for none). */
	public void wearCape(String capeId) {
		state.setCape(capeId == null ? "" : capeId);
		state.save();
		account.setCape(capeId);
		shareChanged.run();
	}

	/** Mojang's texture of a skin file, once Mojang accepted it; null before. */
	public String mojangTexture(SkinLibrary.LocalSkin skin) {
		return state.skinTextures().get(skin.sha256());
	}

	private void accepted(String localSha, String hash) {
		state.skinTextures().put(localSha, hash);
		state.save();
		shareChanged.run();
	}

	/** The account answered (or failed): tells the player about new problems once. */
	private void accountChanged() {
		String problem = account.problem();
		if (problem != null && !problem.equals(lastProblem) && !"offline".equals(problem)) {
			Notifications.warning(Component.translatable("orbit_cosmetic.skins.problem"), Component.translatable("orbit_cosmetic.skins.problem." + problemKey(problem)));
		}
		lastProblem = problem;
		shareChanged.run();
	}

	/** The translation key part for a problem of the account. */
	public static String problemKey(String problem) {
		return switch (problem) {
			case "offline", "refused", "rate_limited", "unreachable" -> problem;
			default -> "other";
		};
	}

	// ---------------------------------------------------------------- what is shared

	/** What Orbit Cloud shares about this player's skin and cape, or null when they wear the account's own. */
	public SkinSignal signal() {
		SkinLibrary.LocalSkin skin = skin();
		String cape = state.cape();
		if (skin == null && cape == null) {
			return null;
		}
		String texture = skin == null ? null : mojangTexture(skin);
		MojangAccount.Profile profile = account.profile();
		MojangAccount.Cape owned = cape == null || cape.isEmpty() || profile == null ? null : profile.cape(cape);
		return new SkinSignal(texture, skin != null && skin.slim(), owned == null ? null : owned.hash(), "".equals(cape));
	}

	/** What another Orbit player wears (null: as Minecraft shows them). */
	public void setRemote(UUID player, SkinSignal signal) {
		if (signal == null) {
			remote.remove(player);
		} else {
			remote.put(player, signal);
		}
	}

	public void clearRemote() {
		remote.clear();
	}

	// ---------------------------------------------------------------- how players look

	/**
	 * How a player looks with Orbit's skins: {@code original} (as Minecraft has it) with the chosen skin and cape in
	 * place. Main thread.
	 */
	public PlayerSkin apply(UUID player, PlayerSkin original) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean local = minecraft.player != null ? player.equals(minecraft.player.getUUID()) : player.equals(minecraft.getUser().getProfileId());
		if (local) {
			return own(original);
		}
		SkinSignal signal = remote.get(player);
		if (signal == null) {
			return original;
		}
		ClientAsset.Texture body = signal.texture() == null ? null : textures.mojangSkin(signal.texture());
		ClientAsset.Texture cape = signal.noCape() ? null : signal.cape() != null ? textures.mojangCape(signal.cape()) : original.cape();
		if (signal.cape() != null && cape == null && !signal.noCape()) {
			cape = original.cape(); // still loading
		}
		return patch(original, body, signal.slim(), cape);
	}

	/** The local player as they look with their chosen skin and cape. */
	public PlayerSkin own(PlayerSkin original) {
		return with(original, skin(), state.cape());
	}

	/** {@code original} wearing a skin from Orbit/Skins (null: unchanged) and a cape (ID, "" for none, null: unchanged). */
	public PlayerSkin with(PlayerSkin original, SkinLibrary.LocalSkin skin, String capeId) {
		ClientAsset.Texture body = skin == null ? null : textures.local(skin);
		ClientAsset.Texture cape = original.cape();
		if (capeId != null) {
			MojangAccount.Profile profile = account.profile();
			MojangAccount.Cape owned = capeId.isEmpty() || profile == null ? null : profile.cape(capeId);
			ClientAsset.Texture loaded = owned == null ? null : textures.mojangCape(owned.hash());
			// While the cape loads (or the account's capes are not known yet), the current one stays.
			cape = capeId.isEmpty() ? null : loaded != null ? loaded : original.cape();
		}
		return patch(original, body, skin != null && skin.slim(), cape);
	}

	private static PlayerSkin patch(PlayerSkin original, ClientAsset.Texture body, boolean slim, ClientAsset.Texture cape) {
		if (body == null) {
			return new PlayerSkin(original.body(), cape, cape, original.model(), original.secure());
		}
		return new PlayerSkin(body, cape, cape, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, false);
	}
}
