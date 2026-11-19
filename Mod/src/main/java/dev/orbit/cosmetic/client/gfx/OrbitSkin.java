package dev.orbit.cosmetic.client.gfx;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
//? if >=1.21.9
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//? if >=1.21.9
import net.minecraft.world.entity.player.PlayerModelType;
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
}
