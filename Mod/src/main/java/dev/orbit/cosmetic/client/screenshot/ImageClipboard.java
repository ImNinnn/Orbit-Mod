package dev.orbit.cosmetic.client.screenshot;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Puts a PNG image on the system clipboard. Minecraft runs without the Java desktop toolkit, so the system's own
 * tools do it: PowerShell on Windows, osascript on macOS, wl-copy (Wayland) or xclip (X11) on Linux.
 */
public final class ImageClipboard {
	private ImageClipboard() {
	}

	/** Copies in the background and says whether it worked. */
	public static void copy(Path image) {
		Util.ioPool().execute(() -> {
			String problem = copyNow(image.toAbsolutePath());
			Minecraft.getInstance().execute(() -> {
				if (problem == null) {
					Notifications.success(Component.translatable("orbit_cosmetic.screenshots.copied"), Component.literal(image.getFileName().toString()));
				} else {
					Notifications.error(Component.translatable("orbit_cosmetic.screenshots.copy_failed"), Component.literal(problem));
				}
			});
		});
	}

	/** Null when it worked, else what went wrong. */
	private static String copyNow(Path image) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String path = image.toString();
		List<List<String>> commands;
		if (os.contains("win")) {
			String quoted = path.replace("'", "''");
			commands = List.of(List.of("powershell", "-NoProfile", "-STA", "-Command",
				"Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; $i = [System.Drawing.Image]::FromFile('" + quoted
					+ "'); [System.Windows.Forms.Clipboard]::SetImage($i); $i.Dispose()"));
		} else if (os.contains("mac")) {
			String quoted = path.replace("\\", "\\\\").replace("\"", "\\\"");
			commands = List.of(List.of("osascript", "-e", "set the clipboard to (read (POSIX file \"" + quoted + "\") as «class PNGf»)"));
		} else if (System.getenv("WAYLAND_DISPLAY") != null) {
			commands = List.of(List.of("wl-copy", "--type", "image/png"), List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path));
		} else {
			commands = List.of(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path), List.of("wl-copy", "--type", "image/png"));
		}
		String problem = "no clipboard tool found (install wl-clipboard or xclip)";
		for (List<String> command : commands) {
			try {
				ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
				if (command.getFirst().equals("wl-copy")) {
					// wl-copy reads the image from its input and keeps serving it after this returns.
					builder.redirectInput(image.toFile());
				}
				Process process = builder.start();
				if (!process.waitFor(15, TimeUnit.SECONDS)) {
					process.destroy();
					problem = "the clipboard tool did not answer";
					continue;
				}
				if (process.exitValue() == 0) {
					return null;
				}
				problem = new String(process.getInputStream().readAllBytes()).trim();
			} catch (IOException e) {
				// This tool is not installed: try the next one.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			}
		}
		OrbitClient.LOGGER.warn("Could not copy {} to the clipboard: {}", image, problem);
		return problem.isEmpty() ? "the clipboard tool failed" : problem;
	}

	/** Whether the file can be copied at all (it exists). */
	public static boolean canCopy(Path image) {
		return Files.isRegularFile(image);
	}
}
