package dev.orbit.cosmetic.client.compat;

import net.minecraft.util.Util;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;

/** The few game calls that differ between Minecraft versions; this copy is for Minecraft 26.1. */
public final class Compat {
	private Compat() {
	}

	public static void setScreen(@Nullable Screen screen) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(screen);
	}

	public static @Nullable Screen screen() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.screen;
	}

	public static ToastManager toasts() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getToastManager();
	}

	/** True while the HUD is hidden (F1). */
	public static boolean hudHidden() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.options.hideGui;
	}

	public static void openPath(Path path) {
		Util.getPlatform().openPath(path);
	}

	public static void openUri(URI uri) {
		Util.getPlatform().openUri(uri);
	}

	/** The game's main framebuffer (for screenshots). */
	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().getMainRenderTarget();
	}

	/** True while a player or creature is swinging an arm (attacking, mining, using). */
	public static boolean isSwinging(net.minecraft.world.entity.LivingEntity entity) {
		return entity.swinging;
	}

	/** Opens the singleplayer world to other players (LAN; e4mc, when installed, puts it online). */
	public static boolean publishWorld(net.minecraft.client.server.IntegratedServer server, net.minecraft.world.level.GameType mode, boolean cheats, int port) {
		return server.publishServer(mode, cheats, port);
	}

	/** Closes the world to other players again; false where Minecraft cannot (26.1). */
	public static boolean unpublishWorld(net.minecraft.client.server.IntegratedServer server) {
		return false;
	}

	/**
	 * Whether a key is the letter of a shortcut (Ctrl+Z: {@code 'z'}), as the keyboard's layout has it where
	 * Minecraft knows the layout (26.3).
	 */
	public static boolean isLetter(net.minecraft.client.input.KeyEvent event, char letter) {
		return event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_A + (letter - 'a');
	}

	/** Whether a keyboard key (a GLFW key code) is held. */
	public static boolean keyDown(int key) {
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow(), key);
	}

	/** A keyboard key's name (a GLFW key code), as the controls show it. */
	public static String keyName(int key) {
		return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
	}

	/** Every keyboard key code {@link #keyDown} can be asked about. */
	public static int[] keyCodes() {
		// GLFW key codes, only real ones (asking about a gap between them is an error).
		return java.util.stream.Stream.of(new int[] {32, 39}, java.util.stream.IntStream.rangeClosed(44, 57).toArray(), new int[] {59, 61},
			java.util.stream.IntStream.rangeClosed(65, 93).toArray(), new int[] {96, 161, 162}, java.util.stream.IntStream.rangeClosed(256, 269).toArray(),
			java.util.stream.IntStream.rangeClosed(280, 284).toArray(), java.util.stream.IntStream.rangeClosed(290, 314).toArray(),
			java.util.stream.IntStream.rangeClosed(320, 336).toArray(), java.util.stream.IntStream.rangeClosed(340, 348).toArray())
			.flatMapToInt(java.util.stream.IntStream::of).toArray();
	}

	/** Hides or shows the HUD, like F1. */
	public static void toggleHud() {
		Minecraft.getInstance().options.hideGui = !Minecraft.getInstance().options.hideGui;
	}
}
