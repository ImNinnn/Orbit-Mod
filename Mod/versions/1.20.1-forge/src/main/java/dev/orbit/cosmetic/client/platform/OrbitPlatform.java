package dev.orbit.cosmetic.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.PreviewRenderer;
import dev.orbit.cosmetic.client.gfx.OrbitCanvas;
import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.render.CosmeticLayer;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * What Orbit needs from the mod loader (see the Fabric copy in the shared code for what each method means); this
 * copy is Forge's, for Minecraft 1.20.1. Forge asks for registrations through events on the mod's own bus (key
 * mappings, HUD overlays, renderer layers) and posts the game's events on its main bus. Orbit starts before Forge asks
 * (see {@link OrbitForge}), so what it registers is kept here and handed over when Forge asks.
 */
public final class OrbitPlatform {
	private static final Set<Object> LAYERED_RENDERERS = Collections.newSetFromMap(new WeakHashMap<>());
	private static final List<KeyMapping> KEYS = new ArrayList<>();
	private static boolean renderersWanted;
	private static boolean keysRegistered;

	private OrbitPlatform() {
	}

	/** Listens to Forge's registration events and to the screens (called once, when Forge makes Orbit). */
	static void listen(IEventBus modBus) {
		modBus.addListener(EventPriority.NORMAL, false, RegisterKeyMappingsEvent.class, event -> {
			KEYS.forEach(event::register);
			keysRegistered = true;
		});
		modBus.addListener(EventPriority.NORMAL, false, RegisterGuiOverlaysEvent.class, event -> {
			// Two overlays of Orbit's own, which draw its layers in their order: one right under the chat, one over all.
			// Forge draws a mod's overlays even with the HUD hidden (F1): the one under the chat hides with the HUD,
			// as on Fabric.
			event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "under_chat", (gui, graphics, partialTick, width, height) -> {
				if (!Minecraft.getInstance().options.hideGui) {
					drawHud(UNDER_CHAT, graphics, partialTick);
				}
			});
			event.registerAboveAll("on_top", (gui, graphics, partialTick, width, height) -> drawHud(ON_TOP, graphics, partialTick));
		});
		modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.AddLayers.class, event -> {
			if (!renderersWanted) {
				return;
			}
			// The player renderers by skin model ("default", "slim"); only players ever get cosmetic data.
			for (String skin : event.getSkins()) {
				if (event.getSkin(skin) instanceof PlayerRenderer player && LAYERED_RENDERERS.add(player)) {
					player.addLayer(new CosmeticLayer(player));
				}
			}
		});
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Init.Pre.class, event -> SCREEN_HOOKS.remove(event.getScreen()));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Init.Post.class, OrbitPlatform::screenOpened);
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Render.Pre.class, event ->
			hooks(event.getScreen()).ifPresent(hooks -> hooks.before.forEach(draw -> draw.draw(OrbitGraphics.of(event.getGuiGraphics()), event.getMouseX(), event.getMouseY(), event.getPartialTick()))));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Render.Post.class, event ->
			hooks(event.getScreen()).ifPresent(hooks -> hooks.after.forEach(draw -> draw.draw(OrbitGraphics.of(event.getGuiGraphics()), event.getMouseX(), event.getMouseY(), event.getPartialTick()))));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseButtonPressed.Pre.class, event ->
			hooks(event.getScreen()).ifPresent(hooks -> cancelIf(event, hooks.click.stream().anyMatch(handler -> handler.handle(event.getMouseX(), event.getMouseY())))));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseButtonReleased.Pre.class, event ->
			hooks(event.getScreen()).ifPresent(hooks -> cancelIf(event, hooks.release.stream().anyMatch(handler -> handler.handle(event.getMouseX(), event.getMouseY())))));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseScrolled.Pre.class, event ->
			hooks(event.getScreen()).ifPresent(hooks -> cancelIf(event, hooks.scroll.stream().anyMatch(handler -> handler.handle(event.getMouseX(), event.getMouseY(), event.getScrollDelta())))));
	}

	private static void cancelIf(Event event, boolean taken) {
		if (taken) {
			event.setCanceled(true);
		}
	}

	// ---------------------------------------------------------------- the installation

	/** Whether a mod is installed, by its ID. */
	public static boolean isModLoaded(String id) {
		return modInfo(id).isPresent();
	}

	/** True in a development run (from the source tree), where test switches are allowed. */
	public static boolean isDevelopment() {
		return !FMLLoader.isProduction();
	}

	/** The game's config folder. */
	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	/** Orbit's version, such as 1.3.2 ("dev" if it cannot be told). */
	public static String version() {
		return modInfo(OrbitClient.MOD_ID).map(mod -> mod.getVersion().toString()).orElse("dev");
	}

	/** The mod loader Orbit runs on, as players know it. */
	public static String loaderName() {
		return "Forge";
	}

	/** The Minecraft version being played. */
	public static String minecraftVersion() {
		return FMLLoader.versionInfo().mcVersion();
	}

	/** The game Orbit runs in, such as "Forge 1.20.1": shown on Discord and to friends as "Minecraft Forge 1.20.1". */
	public static String gameName() {
		return loaderName() + " " + minecraftVersion();
	}

	/** A mod's name by its ID, or the ID itself for one that is not installed. */
	public static String modName(String id) {
		return modInfo(id).map(IModInfo::getDisplayName).orElse(id);
	}

	/** A mod as Forge found it (known before any mod is made, unlike the mod list). */
	private static Optional<IModInfo> modInfo(String id) {
		var file = FMLLoader.getLoadingModList().getModFileById(id);
		return file == null ? Optional.empty() : file.getMods().stream().filter(mod -> mod.getModId().equals(id)).findFirst();
	}

	// ---------------------------------------------------------------- game events

	/** After every client tick (20 a second, also on menus). */
	public static void onTick(Runnable action) {
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, event -> {
			if (event.phase == TickEvent.Phase.END) {
				action.run();
			}
		});
	}

	/** When the game is closing. */
	public static void onStopping(Runnable action) {
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, GameShuttingDownEvent.class, event -> action.run());
	}

	/** When the player has joined a world or server. */
	public static void onJoin(Runnable action) {
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientPlayerNetworkEvent.LoggingIn.class, event -> action.run());
	}

	/** When the player has left a world or server (on the main thread). */
	public static void onLeave(Runnable action) {
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientPlayerNetworkEvent.LoggingOut.class, event -> Minecraft.getInstance().execute(action));
	}

	/** When the local player hits an entity. */
	public static void onAttack(Consumer<Entity> action) {
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, AttackEntityEvent.class, event -> {
			if (event.getEntity().level().isClientSide()) {
				action.accept(event.getTarget());
			}
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
		// Once the entities are drawn, into the frame's buffers.
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, event -> {
			if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
				Camera camera = event.getCamera();
				draw.draw(OrbitCanvas.of(Minecraft.getInstance().renderBuffers().bufferSource()), event.getPoseStack(), camera.getPosition(), camera.rotation());
			}
		});
	}

	/** Called by LevelRendererMixin, which is never applied on Forge (Forge has the event). */
	public static void drawWorld(OrbitCanvas canvas, PoseStack poseStack, Vec3 camera, Quaternionf orientation) {
	}

	/** Adds Orbit's cosmetic layer to the player renderers (when Forge makes them) and its preview renderers to the GUI. */
	public static void registerRenderers() {
		renderersWanted = true;
		PreviewRenderer.register();
	}

	// ---------------------------------------------------------------- keys

	/**
	 * A key mapping in Orbit's group of the controls, named {@code key.orbit_cosmetic.<name>}, with a default key
	 * (a GLFW key code; {@link InputConstants#UNKNOWN} for none).
	 */
	public static KeyMapping key(String name, int defaultKey) {
		KeyMapping key = new KeyMapping("key.orbit_cosmetic." + name, defaultKey, "key.category.orbit_cosmetic.main");
		if (keysRegistered) {
			throw new IllegalStateException("Key mapping " + name + " made after Forge registered them");
		}
		KEYS.add(key);
		return key;
	}

	// ---------------------------------------------------------------- the HUD

	/** Draws a part of the HUD. */
	@FunctionalInterface
	public interface HudLayer {
		void draw(OrbitGraphics graphics, float partialTick);
	}

	private record NamedLayer(ResourceLocation id, HudLayer layer) {
	}

	private static final List<NamedLayer> UNDER_CHAT = new ArrayList<>();
	private static final List<NamedLayer> ON_TOP = new ArrayList<>();

	/** A HUD layer right under the chat (so screens, toasts and the chat go over it). */
	public static void hudUnderChat(ResourceLocation id, HudLayer layer) {
		UNDER_CHAT.add(new NamedLayer(id, layer));
	}

	/** A HUD layer right over another of Orbit's. */
	public static void hudAfter(ResourceLocation after, ResourceLocation id, HudLayer layer) {
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

	/** A HUD layer over all the others. */
	public static void hudOnTop(ResourceLocation id, HudLayer layer) {
		ON_TOP.add(new NamedLayer(id, layer));
	}

	/** Called by GuiChatMixin, which is never applied on Forge (its overlays draw the layers under the chat). */
	public static void drawUnderChat(GuiGraphics graphics, float partialTick) {
	}

	private static void drawHud(List<NamedLayer> layers, GuiGraphics graphics, float partialTick) {
		OrbitGraphics orbit = OrbitGraphics.of(graphics);
		for (NamedLayer layer : layers) {
			layer.layer().draw(orbit, partialTick);
		}
	}

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

	private static final List<ScreenOpened> OPENED = new ArrayList<>();
	/** What Orbit added to each open screen, until it is opened again. */
	private static final Map<Screen, ScreenHooks> SCREEN_HOOKS = new WeakHashMap<>();

	private static Optional<ScreenHooks> hooks(Screen screen) {
		return Optional.ofNullable(SCREEN_HOOKS.get(screen));
	}

	private static void screenOpened(ScreenEvent.Init.Post event) {
		ScreenHooks hooks = new ScreenHooks(event);
		SCREEN_HOOKS.put(event.getScreen(), hooks);
		for (ScreenOpened opened : OPENED) {
			opened.opened(event.getScreen(), hooks);
		}
		// Widgets can only be added while Forge's event lasts.
		hooks.event = null;
	}

	/** What Orbit can add to a screen that was just opened (they last until it is opened again). */
	public static final class ScreenHooks {
		private ScreenEvent.Init.@Nullable Post event;
		private final List<GuiEventListener> listeners;
		private final List<ScreenDraw> before = new ArrayList<>();
		private final List<ScreenDraw> after = new ArrayList<>();
		private final List<ScreenMouse> click = new ArrayList<>();
		private final List<ScreenMouse> release = new ArrayList<>();
		private final List<ScreenScroll> scroll = new ArrayList<>();

		private ScreenHooks(ScreenEvent.Init.Post event) {
			this.event = event;
			this.listeners = event.getListenersList();
		}

		/** The screen's widgets, to read or add to (adding only while the screen is being opened). */
		public List<AbstractWidget> widgets() {
			return new AbstractList<>() {
				private List<AbstractWidget> current() {
					List<AbstractWidget> widgets = new ArrayList<>();
					for (GuiEventListener listener : listeners) {
						if (listener instanceof AbstractWidget widget) {
							widgets.add(widget);
						}
					}
					return widgets;
				}

				@Override
				public AbstractWidget get(int index) {
					return current().get(index);
				}

				@Override
				public int size() {
					return current().size();
				}

				@Override
				public void add(int index, AbstractWidget widget) {
					if (event == null) {
						throw new IllegalStateException("Widgets can only be added while the screen is being opened");
					}
					event.addListener(widget);
				}
			};
		}

		/** Before the screen draws anything. */
		public void beforeDraw(ScreenDraw draw) {
			before.add(draw);
		}

		/** After the screen drew everything. */
		public void afterDraw(ScreenDraw draw) {
			after.add(draw);
		}

		public void onClick(ScreenMouse handler) {
			click.add(handler);
		}

		public void onRelease(ScreenMouse handler) {
			release.add(handler);
		}

		public void onScroll(ScreenScroll handler) {
			scroll.add(handler);
		}
	}

	public static void onScreenOpened(ScreenOpened opened) {
		OPENED.add(opened);
	}
}
