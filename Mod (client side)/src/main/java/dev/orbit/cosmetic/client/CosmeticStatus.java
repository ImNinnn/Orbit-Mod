package dev.orbit.cosmetic.client;

import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.net.BackendSync;
import dev.orbit.cosmetic.common.model.Issue;
import net.minecraft.network.chat.Component;

/**
 * What a cosmetic's coloured square (or an emote's coloured icon) in the Orbit menu means, with the text shown when
 * hovering it.
 *
 * @param detail one line explaining the state
 */
public record CosmeticStatus(Kind kind, Component detail) {
	public enum Kind {
		/** Grey: not equipped. */
		NOT_LOADED(0xFF8B8B93),
		/** Green: equipped, only this player sees it here. */
		LOCAL(0xFF55FF55),
		/** Blue: equipped and shared, so every Orbit player here sees it. */
		SHARED(0xFF4DA6FF),
		/** Yellow: equipped, but Orbit Cloud does not share it (ban, blocked, too big). */
		RESTRICTED(0xFFFFCC4D),
		/** Red: the file has errors and cannot be used. */
		ERROR(0xFFFF5555);

		public final int color;

		Kind(int color) {
			this.color = color;
		}

		public Component label() {
			return Component.translatable("orbit_cosmetic.share.kind." + name().toLowerCase(java.util.Locale.ROOT));
		}

		/** The same state worded for an emote ("on a wheel" rather than "loaded"). */
		public Component emoteLabel() {
			return Component.translatable("orbit_cosmetic.share.emote_kind." + name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	public static CosmeticStatus of(LocalCosmetic entry, BackendSync backend) {
		switch (entry.status()) {
			case INVALID -> {
				String reason = CosmeticLibrary.readable(entry.issues().stream().filter(Issue::isError).map(Issue::message).findFirst().orElse(""));
				return new CosmeticStatus(Kind.ERROR, reason.isEmpty() ? text("error") : Component.literal(reason));
			}
			case CHECKING -> {
				return new CosmeticStatus(Kind.NOT_LOADED, text("checking"));
			}
			case READY -> {
				// handled below
			}
		}
		if (!entry.isLoaded()) {
			return new CosmeticStatus(Kind.NOT_LOADED, text("not_loaded"));
		}
		if (entry.duplicateOf() != null) {
			return new CosmeticStatus(Kind.ERROR, Component.translatable("orbit_cosmetic.share.duplicate", entry.duplicateOf()));
		}
		if (!backend.isActive()) {
			return new CosmeticStatus(Kind.LOCAL, text("local"));
		}
		if (backend.isViewOnly()) {
			return restricted("cloud_view_only");
		}
		String sha = entry.asset().hex();
		BackendSync.CloudShare share = backend.cloudShare(sha);
		if (share == null) {
			return new CosmeticStatus(Kind.LOCAL, text("cloud_sending"));
		}
		return switch (share) {
			case SENDING -> new CosmeticStatus(Kind.LOCAL, text("cloud_sending"));
			case SHARED -> new CosmeticStatus(Kind.SHARED, text("cloud_shared"));
			case TOO_BIG -> restricted("cloud_too_big");
			case NOT_FEATURED -> restricted("cloud_banned");
			case BANNED -> restricted("cloud_full_ban");
			case BLOCKED -> restricted("cloud_blocked");
			case REFUSED -> new CosmeticStatus(Kind.RESTRICTED, Component.translatable("orbit_cosmetic.share.cloud_refused", backend.cloudReason(sha)));
		};
	}

	/**
	 * An emote's state, in the same colours: grey when it is on no wheel, green when only this player sees it, blue
	 * when Orbit players here see it played, yellow when Orbit Cloud does not share it.
	 */
	public static CosmeticStatus ofEmote(EmoteLibrary.LocalEmote emote, boolean onWheel, BackendSync backend) {
		if (!onWheel) {
			return new CosmeticStatus(Kind.NOT_LOADED, text("emote.not_on_wheel"));
		}
		if (!backend.isActive()) {
			return new CosmeticStatus(Kind.LOCAL, text("emote.local"));
		}
		if (backend.isViewOnly()) {
			return restricted("cloud_view_only");
		}
		String sha = emote.asset() == null ? "" : emote.asset().hex();
		BackendSync.CloudShare share = backend.cloudShare(sha);
		if (share == BackendSync.CloudShare.BLOCKED) {
			return restricted("cloud_blocked");
		}
		if (share == BackendSync.CloudShare.TOO_BIG) {
			return restricted("cloud_too_big");
		}
		if (backend.isFullBan()) {
			return restricted("cloud_full_ban");
		}
		if (backend.isBanned() && !backend.catalog().contains(sha)) {
			return restricted("cloud_banned");
		}
		return new CosmeticStatus(Kind.SHARED, text("emote.shared"));
	}

	private static CosmeticStatus restricted(String key) {
		return new CosmeticStatus(Kind.RESTRICTED, text(key));
	}

	private static Component text(String key) {
		return Component.translatable("orbit_cosmetic.share." + key);
	}
}
