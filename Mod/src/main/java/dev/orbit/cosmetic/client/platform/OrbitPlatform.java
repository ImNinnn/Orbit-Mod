package dev.orbit.cosmetic.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.PreviewRenderer;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.mixin.OrbitMixinPlugin;
import dev.orbit.cosmetic.client.render.CosmeticLayer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else if >=1.21.9 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
*///?}
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
//? if >=1.21.9
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * What Orbit needs from the mod loader: facts about the installation (other mods, the config folder, versions), the
 * game's events, key mappings, and hooks into the HUD and into every screen. Fabric, Forge and NeoForge each do
 * these their own way (callbacks, event buses, registries), and so do the Minecraft versions, so each loader and
 * version provides this one class; this copy is Fabric's.
 */
public final class OrbitPlatform {
	private static final Set<Object> LAYERED_RENDERERS = Collections.newSetFromMap(new WeakHashMap<>());
	//? if >=1.21.9 {
	private static KeyMapping.@Nullable Category keyCategory;
	//?}

	private OrbitPlatform() {
	}

	// ---------------------------------------------------------------- the installation

	/** Whether a mod is installed, by its ID. */
	public static boolean isModLoaded(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}

	/** True in a development run (from the source tree), where test switches are allowed. */
	public static boolean isDevelopment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	/** The game's config folder. */
	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	/** Orbit's version, such as 1.3.2 ("dev" if it cannot be told). */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/** The mod loader Orbit runs on, as players know it. */
	public static String loaderName() {
		return "Fabric";
	}

	/** The Minecraft version being played (one jar can cover several, such as 1.21.9 and 1.21.10). */
	public static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/** The game Orbit runs in, such as "Fabric 1.21.10": shown on Discord and to friends as "Minecraft Fabric 1.21.10". */
	public static String gameName() {
		return loaderName() + " " + minecraftVersion();
	}

	/** A mod's name by its ID, or the ID itself for one that is not installed. */
	public static String modName(String id) {
		return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getName()).orElse(id);
	}

	// ---------------------------------------------------------------- game events

	/** After every client tick (20 a second, also on menus). */
	public static void onTick(Runnable action) {
		ClientTickEvents.END_CLIENT_TICK.register(client -> action.run());
	}

	/** When the game is closing. */
	public static void onStopping(Runnable action) {
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> action.run());
	}

	/** When the player has joined a world or server. */
	public static void onJoin(Runnable action) {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> action.run());
	}

	/** When the player has left a world or server (on the main thread). */
	public static void onLeave(Runnable action) {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(action));
	}

	/** When the local player hits an entity. */
	public static void onAttack(Consumer<Entity> action) {
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide()) {
				action.accept(entity);
			}
			return InteractionResult.PASS;
		});
	}

	/** Draws into the world each frame, after its own things are gathered (Orbit's particles). */
	@FunctionalInterface
	public interface WorldDraw {
		/**
		 * @param poseStack the world's pose stack (positions are relative to the camera)
		 * @param camera where the camera is (world coordinates)
		 * @param orientation which way the camera looks
		 */
		void draw(OrbitCanvas canvas, PoseStack poseStack, Vec3 camera, Quaternionf orientation);
	}

	public static void onWorldDraw(WorldDraw draw) {
		//? if >=26.1 {
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			CameraRenderState camera = context.levelState().cameraRenderState;
			draw.draw(OrbitCanvas.of(context.submitNodeCollector()), context.poseStack(), camera.pos, camera.orientation);
		});
		//?} else {
		/*if (OrbitMixinPlugin.hasFabricWorldEvents()) {
			FabricWorldEvents.register(draw);
		} else {
			// Fabric API for 1.21.9 has no world render events: Orbit's own hook calls drawWorld.
			worldDraw = draw;
		}
		*///?}
	}

	//? if <26.1 {
	/*private static @Nullable WorldDraw worldDraw;

	/^* Called by LevelRendererMixin where Fabric API cannot tell Orbit when the world is drawn. ^/
	public static void drawWorld(OrbitCanvas canvas, PoseStack poseStack, Vec3 camera, Quaternionf orientation) {
		if (worldDraw != null) {
			worldDraw.draw(canvas, poseStack, camera, orientation);
		}
	}

	*///?}
	//? if >=1.21.9 <26.1 {
	/*/^* Fabric API's world events, in a class of their own so nothing loads them where they do not exist. ^/
	private static final class FabricWorldEvents {
		static void register(WorldDraw draw) {
			// Before entities are drawn: what is submitted then is drawn with them.
			WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
				CameraRenderState camera = context.worldState().cameraRenderState;
				draw.draw(OrbitCanvas.of(context.commandQueue()), context.matrices(), camera.pos, camera.orientation);
			});
		}
	}
	*///?} else if <1.21.9 {
	/*/^* Fabric API's world events before 1.21.9: drawn into the frame's buffers once the entities are (the pose stack only
	 * exists from then on). ^/
	private static final class FabricWorldEvents {
		static void register(WorldDraw draw) {
			WorldRenderEvents.AFTER_ENTITIES.register(context -> {
				if (context.matrixStack() != null && context.consumers() != null) {
					net.minecraft.client.Camera camera = context.camera();
					draw.draw(OrbitCanvas.of(context.consumers()), context.matrixStack(), camera.getPosition(), camera.rotation());
				}
			});
		}
	}
	*///?}
	//? if >=26.1 {
	/** Called by LevelRendererMixin, which is never applied since 26.1 (Fabric API has the event). */
	public static void drawWorld(OrbitCanvas canvas, PoseStack poseStack, Vec3 camera, Quaternionf orientation) {
	}
	//?}

	/** Adds Orbit's cosmetic layer to the player renderers and its preview renderers to the GUI. */
	public static void registerRenderers() {
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			// Player and mannequin renderers; only players ever get cosmetic data. Guard against double registration.
			if (renderer instanceof AvatarRenderer<?> avatar && LAYERED_RENDERERS.add(avatar)) {
				helper.register(new CosmeticLayer(avatar));
			}
		});
		PreviewRenderer.register();
	}

	// ---------------------------------------------------------------- keys

	/**
	 * A key mapping in Orbit's group of the controls, named {@code key.orbit_cosmetic.<name>}, with a default key
	 * (a GLFW key code; {@link InputConstants#UNKNOWN} for none).
	 */
	public static KeyMapping key(String name, int defaultKey) {
		//? if >=1.21.9 {
		if (keyCategory == null) {
			keyCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "main"));
		}
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic." + name, defaultKey, keyCategory));
		//?} else {
		/*// Before 1.21.9 a category is its translation key (the same one 1.21.9 makes from Orbit's category).
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic." + name, defaultKey, "key.category.orbit_cosmetic.main"));
		*///?}
	}

	// ---------------------------------------------------------------- the HUD

	/** Draws a part of the HUD. */
	@FunctionalInterface
	public interface HudLayer {
		void draw(OrbitGraphics graphics, float partialTick);
	}

	//? if >=1.21.6 {
	/** A HUD layer right under the chat (so screens, toasts and the chat go over it). */
	public static void hudUnderChat(Identifier id, HudLayer layer) {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, (graphics, tick) -> layer.draw(OrbitGraphics.of(graphics), tick.getGameTimeDeltaPartialTick(false)));
	}

	/** A HUD layer right over another of Orbit's. */
	public static void hudAfter(Identifier after, Identifier id, HudLayer layer) {
		HudElementRegistry.attachElementAfter(after, id, (graphics, tick) -> layer.draw(OrbitGraphics.of(graphics), tick.getGameTimeDeltaPartialTick(false)));
	}

	/** A HUD layer over all the others. */
	public static void hudOnTop(Identifier id, HudLayer layer) {
		HudElementRegistry.addLast(id, (graphics, tick) -> layer.draw(OrbitGraphics.of(graphics), tick.getGameTimeDeltaPartialTick(false)));
	}
	//?} else {
	/*// Before 1.21.6 Fabric API only calls back after the whole HUD: Orbit keeps its layers in order itself, and draws
	// the ones under the chat from GuiChatMixin.
	private record NamedLayer(Identifier id, HudLayer layer) {
	}

	private static final List<NamedLayer> UNDER_CHAT = new java.util.ArrayList<>();
	private static final List<NamedLayer> ON_TOP = new java.util.ArrayList<>();

	public static void hudUnderChat(Identifier id, HudLayer layer) {
		UNDER_CHAT.add(new NamedLayer(id, layer));
	}

	public static void hudAfter(Identifier after, Identifier id, HudLayer layer) {
		for (List<NamedLayer> layers : List.of(UNDER_CHAT, ON_TOP)) {
			for (int i = 0; i < layers.size(); i++) {
				if (layers.get(i).id().equals(after)) {
					layers.add(i + 1, new NamedLayer(id, layer));
					return;
				}
			}
		}
		hudOnTop(id, layer);
	}

	public static void hudOnTop(Identifier id, HudLayer layer) {
		if (ON_TOP.isEmpty()) {
			//? if >=1.21 {
			HudRenderCallback.EVENT.register((graphics, tick) -> drawHud(ON_TOP, graphics, tick.getGameTimeDeltaPartialTick(false)));
			//?} else {
			/^HudRenderCallback.EVENT.register((graphics, partialTick) -> drawHud(ON_TOP, graphics, partialTick));
			^///?}
		}
		ON_TOP.add(new NamedLayer(id, layer));
	}

	/^* Called by GuiChatMixin just before the chat is drawn. ^/
	public static void drawUnderChat(GuiGraphicsExtractor graphics, float partialTick) {
		drawHud(UNDER_CHAT, graphics, partialTick);
	}

	private static void drawHud(List<NamedLayer> layers, GuiGraphicsExtractor graphics, float partialTick) {
		OrbitGraphics orbit = OrbitGraphics.of(graphics);
		for (NamedLayer layer : layers) {
			layer.layer().draw(orbit, partialTick);
		}
	}
	*///?}

	// ---------------------------------------------------------------- every screen

	/** Called each time a screen is opened or resized, once its own widgets are made. */
	@FunctionalInterface
	public interface ScreenOpened {
		void opened(Screen screen, ScreenHooks hooks);
	}

	/** Draws over or under a screen. */
	@FunctionalInterface
	public interface ScreenDraw {
		void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick);
	}

	/** A mouse button at (x, y) on a screen; true when Orbit took it (the screen then never sees it). */
	@FunctionalInterface
	public interface ScreenMouse {
		boolean handle(double x, double y);
	}

	/** The mouse wheel at (x, y) on a screen; true when Orbit took it. */
	@FunctionalInterface
	public interface ScreenScroll {
		boolean handle(double x, double y, double amount);
	}

	/** What Orbit can add to a screen that was just opened (they last until it is opened again). */
	public static final class ScreenHooks {
		private final Screen screen;

		private ScreenHooks(Screen screen) {
			this.screen = screen;
		}

		/** The screen's widgets, to read or add to. */
		public List<AbstractWidget> widgets() {
			return Screens.getWidgets(screen);
		}

		/** Before the screen draws anything. */
		public void beforeDraw(ScreenDraw draw) {
			ScreenEvents.beforeExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> draw.draw(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick));
		}

		/** After the screen drew everything. */
		public void afterDraw(ScreenDraw draw) {
			ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> draw.draw(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick));
		}

		//? if >=1.21.9 {
		public void onClick(ScreenMouse handler) {
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !handler.handle(event.x(), event.y()));
		}

		public void onRelease(ScreenMouse handler) {
			ScreenMouseEvents.allowMouseRelease(screen).register((current, event) -> !handler.handle(event.x(), event.y()));
		}
		//?} else {
		/*public void onClick(ScreenMouse handler) {
			ScreenMouseEvents.allowMouseClick(screen).register((current, mouseX, mouseY, button) -> !handler.handle(mouseX, mouseY));
		}

		public void onRelease(ScreenMouse handler) {
			ScreenMouseEvents.allowMouseRelease(screen).register((current, mouseX, mouseY, button) -> !handler.handle(mouseX, mouseY));
		}
		*///?}

		public void onScroll(ScreenScroll handler) {
			ScreenMouseEvents.allowMouseScroll(screen).register((current, mouseX, mouseY, scrollX, scrollY) -> !handler.handle(mouseX, mouseY, scrollY));
		}
	}

	public static void onScreenOpened(ScreenOpened opened) {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> opened.opened(screen, new ScreenHooks(screen)));
	}
}
