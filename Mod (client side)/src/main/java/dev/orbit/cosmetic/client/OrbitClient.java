package dev.orbit.cosmetic.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.compat.PreviewRenderer;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.emote.EmoteController;
import dev.orbit.cosmetic.client.gui.EmoteWheelScreen;
import dev.orbit.cosmetic.client.gui.MenuPlayer;
import dev.orbit.cosmetic.client.gui.OrbitMenuScreen;
import dev.orbit.cosmetic.client.gui.TermsScreen;
import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.FolderWatcher;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.library.OrbitState;
import dev.orbit.cosmetic.client.net.AssetCache;
import dev.orbit.cosmetic.client.net.BackendSync;
import dev.orbit.cosmetic.client.net.RemotePlayers;
import dev.orbit.cosmetic.client.render.CosmeticLayer;
import dev.orbit.cosmetic.client.render.CosmeticRegistry;
import dev.orbit.cosmetic.client.render.CosmeticRenderData;
import dev.orbit.cosmetic.client.render.CosmeticRenderer;
import dev.orbit.cosmetic.client.render.EmoteRenderData;
import dev.orbit.cosmetic.client.render.RenderPiece;
import dev.orbit.cosmetic.client.skin.OrbitSkins;
import dev.orbit.cosmetic.client.skin.SkinLibrary;
import dev.orbit.cosmetic.client.skin.SkinTextures;
import dev.orbit.cosmetic.common.anim.AnimationClock;
import dev.orbit.cosmetic.common.emote.BodyPose;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import dev.orbit.cosmetic.client.render.SkinMasks;
import dev.orbit.cosmetic.client.render.ParticleRenderData;
import dev.orbit.cosmetic.client.render.ParticleRenderer;
import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.client.anim.ParticleSystem;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.model.Bone;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Client entrypoint and the hub connecting the libraries, Orbit Cloud, emotes, animation and rendering. */
public final class OrbitClient implements ClientModInitializer {
	public static final String MOD_ID = "orbit_cosmetic";
	public static final Logger LOGGER = LoggerFactory.getLogger("Orbit");

	/**
	 * The Orbit icon and a 1-unit gap, placed before the name inside the nameplate background. Mods that draw
	 * their own icon beside the measured name text (such as Essential) therefore end up to the left of it.
	 */
	private static final Component NAMEPLATE_ICON = Component.literal("")
		.withStyle(style -> style.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "nameplate"))).withColor(ChatFormatting.WHITE));
	private static final long MENU_EPOCH = System.nanoTime();

	private static OrbitClient instance;

	private ClientConfig config;
	private OrbitPaths paths;
	private OrbitState state;
	/** Ticks in a world towards the next minute of time played. */
	private int playTicks;
	private CosmeticLibrary library;
	private EmoteLibrary emotes;
	private CosmeticRegistry registry;
	private final SkinMasks skinMasks = new SkinMasks();
	private final dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary screenshots = new dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary();
	private final dev.orbit.cosmetic.client.social.Social social = new dev.orbit.cosmetic.client.social.Social();
	private RemotePlayers remotes;
	private BackendSync backend;
	private AssetCache cache;
	private EmoteController emoteController;
	private OrbitSkins skins;
	private KeyMapping openMenu;
	private KeyMapping emoteWheel;
	private Wearer localWearer;
	private LocalCosmetic preview;
	private long clientTicks;
	/** Animation time; follows the level's game time smoothly (see AnimationClock). */
	private final AnimationClock clock = new AnimationClock();
	private ClientLevel clockLevel;
	/** Own cosmetics already announced as shared (or worn when the Orbit Cloud session started). */
	private final Set<Object> layeredRenderers = Collections.newSetFromMap(new WeakHashMap<>());
	private Supplier<PlayerSkin> menuSkin;

	public static OrbitClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		Minecraft minecraft = Minecraft.getInstance();
		paths = new OrbitPaths(minecraft.gameDirectory.toPath());
		try {
			paths.create();
		} catch (IOException e) {
			LOGGER.error("Could not create {}", paths.root(), e);
		}
		config = ClientConfig.load(minecraft.gameDirectory.toPath().resolve("config").resolve("orbit_cosmetic.json"));
		state = new OrbitState(paths.stateFile());
		registry = new CosmeticRegistry();
		cache = new AssetCache(paths.cache());
		library = new CosmeticLibrary(paths, state);
		emotes = new EmoteLibrary(paths, state);
		remotes = new RemotePlayers(this, cache);
		backend = new BackendSync(this, remotes);
		emoteController = new EmoteController(this);
		skins = new OrbitSkins(state, new SkinLibrary(paths.skins()), new SkinTextures(paths.cache().resolve("skins")), backend::markDirty);
		registry.setLocalModels(id -> {
			LocalCosmetic local = library.find(id);
			if (local != null && local.model() != null) {
				return local.model();
			}
			EmoteLibrary.EmoteFile emote = emotes.findFile(id);
			return emote != null ? emote.model() : null;
		});
		library.addListener(() -> {
			updateLocalWearer();
			backend.markDirty();
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			backend.onJoin();
			updateLocalWearer();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			emoteController.stop();
			backend.onDisconnect();
			clockLevel = null;
			localWearer = null;
		}));

		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			// Player and mannequin renderers; only players ever get cosmetic data. Guard against double registration.
			if (renderer instanceof AvatarRenderer<?> avatar && layeredRenderers.add(avatar)) {
				helper.register(new CosmeticLayer(avatar));
			}
		});
		PreviewRenderer.register();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic.open_menu", InputConstants.KEY_B, category));
		emoteWheel = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic.emote_wheel", InputConstants.KEY_R, category));
		dev.orbit.cosmetic.client.zoom.Zoom.setKey(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic.zoom", InputConstants.KEY_C, category)));
		OrbitKeys.register(category);

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		// Particles live in the world, so they are drawn with it rather than with their player.
		LevelRenderEvents.COLLECT_SUBMITS.register(this::submitParticles);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			backend.onStop();
			backend.shutdown();
			registry.releaseAll();
		});
		MenuPlayer.register();
		TermsScreen.register();
		dev.orbit.cosmetic.client.gui.UpdatePromptScreen.register();
		dev.orbit.cosmetic.client.gui.servers.ServerTabs.register();
		dev.orbit.cosmetic.client.screenshot.ScreenshotToasts.register();
		dev.orbit.cosmetic.client.social.FloatingChats.register();
		dev.orbit.cosmetic.client.social.SocialPopups.register();

		library.reload();
		emotes.reload();
		skins.library().reload();
		// Files added, removed or changed outside the game show up without a restart.
		new FolderWatcher(List.of(paths.cosmetics(), paths.emotes(), paths.featuredCosmetics(), paths.featuredEmotes(), paths.skins(), paths.bodyParts(),
			paths.featuredBodyParts()), () -> {
			library.reload();
			emotes.reload();
			skins.library().reload();
		}).start();
		LOGGER.info("Orbit ready; folder: {}", paths.root());
	}

	public ClientConfig config() {
		return config;
	}

	public OrbitPaths paths() {
		return paths;
	}

	public dev.orbit.cosmetic.client.social.Social social() {
		return social;
	}

	public dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary screenshots() {
		return screenshots;
	}

	public CosmeticLibrary library() {
		return library;
	}

	public EmoteLibrary emotes() {
		return emotes;
	}

	public CosmeticRegistry registry() {
		return registry;
	}

	public SkinMasks skinMasks() {
		return skinMasks;
	}

	public RemotePlayers remotes() {
		return remotes;
	}

	public AssetCache cache() {
		return cache;
	}

	public BackendSync backend() {
		return backend;
	}

	public EmoteController emoteController() {
		return emoteController;
	}

	/** Skins and capes; null only while the mod starts. */
	public OrbitSkins skins() {
		return skins;
	}

	/** The state file: equipped cosmetics, emote wheels, skin and cape, outfits. */
	public OrbitState state() {
		return state;
	}

	public KeyMapping openMenuKey() {
		return openMenu;
	}

	public KeyMapping emoteWheelKey() {
		return emoteWheel;
	}

	/** The local player as Orbit tracks it, or null outside a world. */
	public Wearer localWearer() {
		return localWearer;
	}

	/** The animation clock's current time (game ticks), for emotes started now. */
	public double clockTime() {
		return clock.time();
	}

	/** Time for menus (ticks since the game started), which keeps running without a world. */
	public double menuTime() {
		return (System.nanoTime() - MENU_EPOCH) / 50_000_000.0;
	}

	/** The local player's skin (with the skin and cape chosen in Orbit), for previews in menus. */
	public PlayerSkin menuSkin() {
		return skins.own(accountSkin());
	}

	/** The local player's skin as their Minecraft account has it. */
	public PlayerSkin accountSkin() {
		if (menuSkin == null) {
			Minecraft minecraft = Minecraft.getInstance();
			menuSkin = minecraft.getSkinManager().createLookup(minecraft.getGameProfile(), false);
		}
		return menuSkin.get();
	}

	/** What the local player wears right now, for previews. */
	public List<EquippedCosmetic> equipped() {
		Map<AssetId, EquippedCosmetic> worn = new LinkedHashMap<>();
		for (LocalCosmetic entry : library.loaded()) {
			worn.putIfAbsent(entry.asset(), entry.toEquipped());
		}
		return List.copyOf(worn.values());
	}

	// ---------------------------------------------------------------- local wearer

	/** Shows a cosmetic on the local player while a menu previews it, without equipping it. */
	public void setPreview(LocalCosmetic entry) {
		preview = entry;
		updateLocalWearer();
	}

	public LocalCosmetic preview() {
		return preview;
	}

	private void updateLocalWearer() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			localWearer = null;
			return;
		}
		if (localWearer == null || !localWearer.id.equals(minecraft.player.getUUID())) {
			localWearer = new Wearer(minecraft.player.getUUID());
		}
		Map<AssetId, EquippedCosmetic> worn = new LinkedHashMap<>();
		for (EquippedCosmetic cosmetic : equipped()) {
			worn.put(cosmetic.asset(), cosmetic);
		}
		if (preview != null && preview.isUsable()) {
			worn.putIfAbsent(preview.asset(), preview.toEquipped());
		}
		localWearer.setWorn(List.copyOf(worn.values()));
	}

	// ---------------------------------------------------------------- ticking

	private void tick(Minecraft minecraft) {
		social.tick();
		backend.refreshAnnouncement();
		dev.orbit.cosmetic.client.host.Hosting.tick();
		if (minecraft.level != null && !minecraft.isPaused() && ++playTicks >= 1200) {
			playTicks = 0;
			state.addPlayed(60);
			social.addPlayed(60);
			state.save();
		}
		OrbitKeys.tick(minecraft);
		DiscordPresence.tick(minecraft);
		while (openMenu.consumeClick()) {
			if (Compat.screen() == null) {
				Compat.setScreen(new OrbitMenuScreen(null));
			}
		}
		while (emoteWheel.consumeClick()) {
			if (Compat.screen() == null && minecraft.player != null) {
				// The wheel stays open while the key is held and plays the chosen emote when it is released.
				Compat.setScreen(new EmoteWheelScreen());
			}
		}
		clientTicks++;
		LocalPlayer player = minecraft.player;
		boolean flying = player != null && player.getAbilities().flying;
		backend.tick(minecraft);
		remotes.tick();
		if (player != null && minecraft.level != null) {
			if (localWearer == null || !localWearer.id.equals(player.getUUID())) {
				updateLocalWearer();
			}
			if (minecraft.level != clockLevel) {
				// Joined a server, changed dimension or switched subserver: a new level and timeline.
				clockLevel = minecraft.level;
				clock.reset();
				emoteController.stop();
			}
			clock.tick(minecraft.level.getGameTime());
			double time = clock.time();
			int generation = clock.generation();
			localWearer.tick(player, time, generation, flying, registry::get);
			emoteController.tick(minecraft);
			double maxDistanceSq = (double) config.renderDistance * config.renderDistance;
			for (Wearer wearer : remotes.wearers().values()) {
				Player other = minecraft.level.getPlayerByUUID(wearer.id);
				if (other != null && other.distanceToSqr(player) <= maxDistanceSq) {
					wearer.tick(other, time, generation, false, registry::get);
				}
			}
		}
		if (clientTicks % 100 == 0) {
			Set<AssetId> inUse = new HashSet<>(remotes.assetsInUse());
			if (localWearer != null) {
				localWearer.worn().forEach(worn -> inUse.add(worn.asset()));
			}
			for (LocalCosmetic entry : library.entries()) {
				if (entry.isLoaded() && entry.asset() != null) {
					inUse.add(entry.asset());
				}
			}
			for (EmoteLibrary.EmoteFile file : emotes.files()) {
				if (file.asset() != null) {
					inUse.add(file.asset());
				}
			}
			MenuPlayer.assetsInUse(inUse);
			dev.orbit.cosmetic.client.social.SocialScreen.assetsInUse(inUse);
			dev.orbit.cosmetic.client.gui.StatsScreen.assetsInUse(inUse);
			registry.collect(inUse);
			skinMasks.collect();
		}
	}

	/** The coloured status of a cosmetic in the Orbit menu: not equipped, local, shared, restricted or broken. */
	public CosmeticStatus cosmeticStatus(LocalCosmetic entry) {
		return CosmeticStatus.of(entry, backend);
	}

	// ---------------------------------------------------------------- rendering hooks

	/** Called from the player renderer after it extracted a player's render state. */
	public void extract(AbstractClientPlayer player, AvatarRenderState state, float partialTicks) {
		state.setData(CosmeticRenderData.KEY, null);
		state.setData(EmoteRenderData.KEY, null);
		state.setData(ParticleRenderData.KEY, null);
		Minecraft minecraft = Minecraft.getInstance();
		boolean local = minecraft.player != null && player.getUUID().equals(minecraft.player.getUUID());
		Wearer wearer = local ? localWearer : remotes.wearers().get(player.getUUID());
		if (state.nameTag != null && config.showNameplateIcons && (local ? backend.isActive() && !backend.isFullBan() : wearer != null && wearer.showIcon)) {
			state.nameTag = Component.empty().append(NAMEPLATE_ICON).append(state.nameTag);
		}
		// Blocked players' cosmetics are hidden too.
		if (wearer == null || !local && (!config.showOtherPlayers || config.isHidden(wearer.id) || social.isBlocked(wearer.id))) {
			return;
		}
		double distanceSq = state.distanceToCameraSq;
		if (!local && distanceSq > (double) config.renderDistance * config.renderDistance) {
			return;
		}
		Wearer.Emote emote = wearer.emote();
		if (wearer.worn().isEmpty() && emote == null) {
			return;
		}
		boolean animate = config.playAnimations && distanceSq <= (double) config.animationDistance * config.animationDistance;
		List<RenderPiece> pieces = wearer.pieces(state, clock.at(partialTicks), partialTicks, animate, config.maxFacesPerPlayer, registry::get);
		if (!config.renderCosmetics) {
			// Cosmetics hidden: only an emote's own props are drawn.
			pieces = pieces.stream().filter(piece -> emote != null && piece.baked() == emote.baked()).toList();
		}
		if (!pieces.isEmpty()) {
			state.setData(CosmeticRenderData.KEY, new CosmeticRenderData(pieces));
			state.setData(ParticleRenderData.KEY, new ParticleRenderData(wearer.particles(), false));
			// The skin's outer layer would poke through cosmetics that sit close to the body.
			state.skin = skinMasks.apply(state.skin, pieces, emote == null ? null : emote.baked());
		}
		BodyPose body = wearer.body();
		if (!body.isEmpty()) {
			BodyPose copy = new BodyPose();
			copy.copyFrom(body);
			state.setData(EmoteRenderData.KEY, new EmoteRenderData(copy));
		}
	}

	/** Whether the local player's own nameplate should be drawn (third person only, as other players see it). */
	public boolean shouldShowOwnNameplate(Entity entity) {
		Minecraft minecraft = Minecraft.getInstance();
		return config.showOwnNameplate
			&& entity == minecraft.player
			&& entity == minecraft.getCameraEntity()
			&& !minecraft.options.getCameraType().isFirstPerson()
			&& !Compat.hudHidden()
			&& !entity.isInvisible()
			&& !entity.isVehicle();
	}

	/** The skin texture for the first-person arms: without the sleeve pixels that arm cosmetics cover. */
	public Identifier firstPersonSkin(Identifier skin) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!config.renderCosmetics || !config.showInFirstPerson || localWearer == null || localWearer.worn().isEmpty() || minecraft.player == null) {
			return skin;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<RenderPiece> pieces = localWearer.pieces(null, clock.at(partial), partial, config.playAnimations, config.maxFacesPerPlayer, registry::get);
		Wearer.Emote emote = localWearer.emote();
		Identifier masked = skinMasks.texture(skin, minecraft.player.getSkin().model() == PlayerModelType.SLIM, pieces, emote == null ? null : emote.baked());
		return masked == null ? skin : masked;
	}

	/** Draws every player's particles in the world (and starts the local player's in first person, where they are not drawn). */
	private void submitParticles(LevelRenderContext context) {
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (Minecraft.getInstance().options.getCameraType().isFirstPerson() && localWearer != null) {
			emitFirstPerson();
		}
		Vector3f right = new Vector3f(1, 0, 0).rotate(camera.orientation);
		Vector3f up = new Vector3f(0, 1, 0).rotate(camera.orientation);
		Matrix4f identity = new Matrix4f();
		List<Wearer> wearers = new ArrayList<>(remotes.wearers().values());
		if (localWearer != null) {
			wearers.add(localWearer);
		}
		for (Wearer wearer : wearers) {
			ParticleSystem system = wearer.particles();
			system.update();
			if (!system.isEmpty()) {
				// Lit from above in the world, like the top of a block.
				ParticleRenderer.submit(system, camera.pos.x, camera.pos.y, camera.pos.z, identity, right, up, new Vector3f(0, 1, 0), context.poseStack(), context.submitNodeCollector());
			}
		}
	}

	/**
	 * In first person the player's body is not drawn, so their emitters are not seen: they spawn where they were last
	 * seen relative to the player (or, before that, where they rest).
	 */
	private void emitFirstPerson() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || !config.renderCosmetics) {
			return;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double x = Mth.lerp(partial, player.xo, player.getX());
		double y = Mth.lerp(partial, player.yo, player.getY());
		double z = Mth.lerp(partial, player.zo, player.getZ());
		float bodyRot = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);
		Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians(180 - bodyRot));
		ParticleSystem system = localWearer.particles();
		for (RenderPiece piece : localWearer.pieces(null, clock.at(partial), partial, config.playAnimations, config.maxFacesPerPlayer, registry::get)) {
			BakedCosmetic baked = piece.baked();
			for (ParticleSettings settings : piece.particles()) {
				if (!settings.enabled() || settings.texture() < 0 || settings.bone() >= baked.model().bones().size()) {
					continue;
				}
				String key = baked.id().hex() + ":" + settings.bone();
				Vector3f offset = system.lastOffset(key);
				Vector3f at = turn.transform(offset != null ? new Vector3f(offset) : restOffset(baked.model().bones(), settings.bone()));
				system.emit(key, settings, x + at.x, y + at.y, z + at.z, bodyRot, baked, baked.texture(settings.texture(), piece.style()), system.lastLight(),
					CosmeticRenderer.emitterRate(baked, piece.pose(), settings.bone()), null);
			}
		}
	}

	/** Where a group rests relative to the player's feet, facing -Z (blocks, Blockbench axes). */
	private static Vector3f restOffset(List<Bone> bones, int index) {
		Bone bone = bones.get(index);
		Anchor anchor = bone.anchor();
		// Model space: the neck at 0, Y down; the anchor's joint, then the pivots down to the group.
		float mx = -anchor.pivotX();
		float my = 24 - anchor.pivotY();
		float mz = anchor.pivotZ();
		for (int b = index; b >= 0; b = bones.get(b).parent()) {
			mx += bones.get(b).pivotX();
			my += bones.get(b).pivotY();
			mz += bones.get(b).pivotZ();
		}
		return new Vector3f(-mx / 16, (24 - my) / 16, mz / 16);
	}

	/** The local player's cosmetics for a first-person arm, with their skin; empty when none are drawn there. */
	private List<RenderPiece> firstPersonPieces(PlayerModel model, ModelPart arm) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!config.renderCosmetics || !config.showInFirstPerson || localWearer == null || localWearer.worn().isEmpty() || minecraft.player == null) {
			return List.of();
		}
		if (arm != model.rightArm && arm != model.leftArm) {
			return List.of();
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Identifier skin = minecraft.player.getSkin().body().texturePath();
		return localWearer.pieces(null, clock.at(partial), partial, config.playAnimations, config.maxFacesPerPlayer, registry::get).stream()
			.map(piece -> piece.withSkin(skin)).toList();
	}

	/** Whether a body part replaces this first-person arm (then only the body part is drawn). */
	public boolean replacesFirstPersonArm(PlayerModel model, ModelPart arm) {
		Anchor anchor = arm == model.rightArm ? Anchor.RIGHT_ARM : Anchor.LEFT_ARM;
		return firstPersonPieces(model, arm).stream().anyMatch(piece -> (piece.hiddenParts() & 1 << anchor.ordinal()) != 0);
	}

	/** Called after the first-person hand was submitted (or instead of it); draws cosmetics attached to that arm. */
	public void renderFirstPersonArm(PlayerModel model, PoseStack poseStack, SubmitNodeCollector collector, int light, ModelPart arm) {
		List<RenderPiece> pieces = firstPersonPieces(model, arm);
		Anchor anchor = arm == model.rightArm ? Anchor.RIGHT_ARM : Anchor.LEFT_ARM;
		if (pieces.isEmpty()) {
			return;
		}
		poseStack.pushPose();
		arm.translateAndRotate(poseStack);
		CosmeticRenderer.submit(pieces, null, poseStack, collector, light, anchor);
		poseStack.popPose();
	}
}
