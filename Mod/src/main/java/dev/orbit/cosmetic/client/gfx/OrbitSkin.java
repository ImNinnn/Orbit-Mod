package dev.orbit.cosmetic.client.gfx;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
//? if <1.20.2 {
/*import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.core.UUIDUtil;
*///?}
//? if >=1.21.9
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//? if >=1.21.9
import net.minecraft.world.entity.player.PlayerModelType;
//? if >=1.20.2
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * How a player looks: their skin texture, whether it is the slim model, and their cape. Orbit changes skins (its own
 * skins and capes, skins with the outer layer under cosmetics removed) and shows them (faces, previews) only through
 * this, because Minecraft keeps skins differently over the versions (a record of textures since 1.20.2, separate
 * values before). Each Minecraft version provides this one class.
 * <p>
 * It holds no textures of its own: they belong to Minecraft or to {@link OrbitTextures}.
 */
public final class OrbitSkin {
	//? if >=1.20.2 {
	private final PlayerSkin skin;

	private OrbitSkin(PlayerSkin skin) {
		this.skin = skin;
	}

	/** Version-specific code only (mixins, the 3D previews): wraps Minecraft's skin. */
	public static OrbitSkin of(PlayerSkin skin) {
		return new OrbitSkin(skin);
	}

	/** How a player in the world looks right now (Orbit's skins included, see PlayerInfoMixin). */
	public static OrbitSkin of(AbstractClientPlayer player) {
		return new OrbitSkin(player.getSkin());
	}

	/** One of Minecraft's default skins, the one it gives this player while they have none of their own. */
	public static OrbitSkin fallback(UUID player) {
		return new OrbitSkin(DefaultPlayerSkin.get(player));
	}

	//? if >=1.21.9 {
	/**
	 * A player's skin from their profile (with its textures), loaded in the background: the supplier gives the
	 * default skin until it is ready, then theirs.
	 */
	public static Supplier<OrbitSkin> lookup(GameProfile profile) {
		Supplier<PlayerSkin> lookup = Minecraft.getInstance().getSkinManager().createLookup(profile, false);
		return () -> new OrbitSkin(lookup.get());
	}

	/** Minecraft's own skin, for version-specific code. */
	public PlayerSkin raw() {
		return skin;
	}

	/** The skin texture. */
	public Identifier texture() {
		return skin.body().texturePath();
	}

	/** Whether it is drawn on the slim (3-pixel arms) model. */
	public boolean slim() {
		return skin.model() == PlayerModelType.SLIM;
	}

	/** The same skin drawn from another texture of the same layout (the model and cape stay). */
	public OrbitSkin withTexture(Identifier texture) {
		return new OrbitSkin(new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), skin.cape(), skin.elytra(), skin.model(), skin.secure()));
	}

	/** Another skin texture and model in place of this one (the cape stays). */
	public OrbitSkin withBody(Identifier texture, boolean slim) {
		return new OrbitSkin(new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), skin.cape(), skin.elytra(),
			slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, false));
	}

	/** The cape texture, or null without one. */
	public @Nullable Identifier cape() {
		return skin.cape() == null ? null : skin.cape().texturePath();
	}

	/** This skin with another cape (null for none); the elytra shows the same picture. */
	public OrbitSkin withCape(@Nullable Identifier cape) {
		ClientAsset.Texture texture = cape == null ? null
			: skin.cape() != null && skin.cape().texturePath().equals(cape) ? skin.cape() : new ClientAsset.ResourceTexture(cape, cape);
		return new OrbitSkin(new PlayerSkin(skin.body(), texture, texture, skin.model(), skin.secure()));
	}
	//?} else {
	/*/^*
	 * A player's skin from their profile (with its textures), loaded in the background: the supplier gives the
	 * default skin until it is ready, then theirs.
	 ^/
	public static Supplier<OrbitSkin> lookup(GameProfile profile) {
		Supplier<PlayerSkin> lookup = Minecraft.getInstance().getSkinManager().lookupInsecure(profile);
		return () -> new OrbitSkin(lookup.get());
	}

	/^* Minecraft's own skin, for version-specific code. ^/
	public PlayerSkin raw() {
		return skin;
	}

	/^* The skin texture. ^/
	public Identifier texture() {
		return skin.texture();
	}

	/^* Whether it is drawn on the slim (3-pixel arms) model. ^/
	public boolean slim() {
		return skin.model() == PlayerSkin.Model.SLIM;
	}

	/^* The same skin drawn from another texture of the same layout (the model and cape stay). ^/
	public OrbitSkin withTexture(Identifier texture) {
		return new OrbitSkin(new PlayerSkin(texture, skin.textureUrl(), skin.capeTexture(), skin.elytraTexture(), skin.model(), skin.secure()));
	}

	/^* Another skin texture and model in place of this one (the cape stays). ^/
	public OrbitSkin withBody(Identifier texture, boolean slim) {
		return new OrbitSkin(new PlayerSkin(texture, null, skin.capeTexture(), skin.elytraTexture(), slim ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, false));
	}

	/^* The cape texture, or null without one. ^/
	public @Nullable Identifier cape() {
		return skin.capeTexture();
	}

	/^* This skin with another cape (null for none); the elytra shows the same picture. ^/
	public OrbitSkin withCape(@Nullable Identifier cape) {
		return new OrbitSkin(new PlayerSkin(skin.texture(), skin.textureUrl(), cape, cape, skin.model(), skin.secure()));
	}
	*///?}

	@Override
	public boolean equals(Object other) {
		return other instanceof OrbitSkin that && skin.equals(that.skin);
	}

	@Override
	public int hashCode() {
		return skin.hashCode();
	}
	//?} else {
	/*// Before 1.20.2 the game keeps a skin's texture, model, cape and elytra apart: this holds them together.
	private final Identifier texture;
	private final boolean slim;
	private final @Nullable Identifier cape;
	private final @Nullable Identifier elytra;

	private OrbitSkin(Identifier texture, boolean slim, @Nullable Identifier cape, @Nullable Identifier elytra) {
		this.texture = texture;
		this.slim = slim;
		this.cape = cape;
		this.elytra = elytra;
	}

	/^* Version-specific code only: a skin from the game's separate values. ^/
	public static OrbitSkin of(Identifier texture, boolean slim, @Nullable Identifier cape, @Nullable Identifier elytra) {
		return new OrbitSkin(texture, slim, cape, elytra);
	}

	/^* How a player in the world looks right now (Orbit's skins included, see PlayerInfoMixin). ^/
	public static OrbitSkin of(AbstractClientPlayer player) {
		return new OrbitSkin(player.getSkinTextureLocation(), "slim".equals(player.getModelName()), player.getCloakTextureLocation(), player.getElytraTextureLocation());
	}

	/^* One of Minecraft's default skins, the one it gives this player while they have none of their own. ^/
	public static OrbitSkin fallback(UUID player) {
		return new OrbitSkin(DefaultPlayerSkin.getDefaultSkin(player), "slim".equals(DefaultPlayerSkin.getSkinModelName(player)), null, null);
	}

	/^*
	 * A player's skin from their profile (with its textures), loaded in the background: the supplier gives the
	 * default skin until it is ready, then theirs.
	 ^/
	public static Supplier<OrbitSkin> lookup(GameProfile profile) {
		OrbitSkin[] current = {fallback(UUIDUtil.getOrCreatePlayerUUID(profile))};
		// The textures arrive one by one on the render thread, where the supplier is read too.
		Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {
			OrbitSkin was = current[0];
			if (type == MinecraftProfileTexture.Type.SKIN) {
				current[0] = new OrbitSkin(location, "slim".equals(texture.getMetadata("model")), was.cape, was.elytra);
			} else if (type == MinecraftProfileTexture.Type.CAPE) {
				current[0] = new OrbitSkin(was.texture, was.slim, location, was.elytra);
			}
		}, false);
		return () -> current[0];
	}

	/^* The skin texture. ^/
	public Identifier texture() {
		return texture;
	}

	/^* Whether it is drawn on the slim (3-pixel arms) model. ^/
	public boolean slim() {
		return slim;
	}

	/^* The elytra texture (version-specific code only), or null for the cape's or the default one. ^/
	public @Nullable Identifier elytra() {
		return elytra;
	}

	/^* The same skin drawn from another texture of the same layout (the model and cape stay). ^/
	public OrbitSkin withTexture(Identifier texture) {
		return new OrbitSkin(texture, slim, cape, elytra);
	}

	/^* Another skin texture and model in place of this one (the cape stays). ^/
	public OrbitSkin withBody(Identifier texture, boolean slim) {
		return new OrbitSkin(texture, slim, cape, elytra);
	}

	/^* The cape texture, or null without one. ^/
	public @Nullable Identifier cape() {
		return cape;
	}

	/^* This skin with another cape (null for none); the elytra shows the same picture. ^/
	public OrbitSkin withCape(@Nullable Identifier cape) {
		return new OrbitSkin(texture, slim, cape, cape);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof OrbitSkin that && texture.equals(that.texture) && slim == that.slim && java.util.Objects.equals(cape, that.cape)
			&& java.util.Objects.equals(elytra, that.elytra);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(texture, slim, cape, elytra);
	}
	*///?}
}
