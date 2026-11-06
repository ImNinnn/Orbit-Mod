package dev.orbit.cosmetic.client;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A newer Orbit the player may install from the game: found before launch by the update check (the
 * {@code update} package, which only the self-updating build has), offered by the menus (an arrow next to Settings,
 * an Update row in the settings, a prompt on the title screen). Without the update package (the build for mod
 * sites, which update mods themselves) nothing is ever offered here.
 *
 * <p>While the game runs, the update check asks again when Orbit Cloud says a release came out ({@link #checkNow});
 * a newer version found then gets a notification.
 */
public final class Updates {
	/** Installs the offered version; {@code result} gets {@code UPDATED} or {@code FAILED <reason>} (any thread). */
	public interface Installer {
		void install(Consumer<String> result);
	}

	private static volatile @Nullable String available;
	private static volatile @Nullable Installer installer;
	private static volatile boolean installed;
	private static volatile @Nullable Supplier<String> checker;
	private static boolean checking;
	private static @Nullable String announced;

	private Updates() {
	}

	/** Called by the update check when a newer version may be installed. */
	public static void offer(String version, Installer how) {
		available = version;
		installer = how;
	}

	/**
	 * Called by the update check: asks Orbit Cloud again (blocking; offers what it finds) and returns the newer
	 * version, or null.
	 */
	public static void checker(Supplier<String> check) {
		checker = check;
	}

	/**
	 * Checks again now, in the background (Orbit Cloud's live wait said a release came out), and tells the player
	 * about a new version. Nothing without the update check (the build for mod sites).
	 */
	public static void checkNow() {
		Supplier<String> check = checker;
		if (check == null || installed || checking) {
			return;
		}
		if (announced == null) {
			// The one found at launch has its prompt on the title screen.
			announced = available();
		}
		checking = true;
		Thread thread = new Thread(() -> {
			String found = null;
			try {
				found = check.get();
			} catch (RuntimeException e) {
				OrbitClient.LOGGER.debug("Update check failed: {}", e.toString());
			}
			String version = found;
			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				checking = false;
				if (version != null && !version.equals(announced) && !installed) {
					announced = version;
					Notifications.info(net.minecraft.network.chat.Component.translatable("orbit_cosmetic.update.found", version),
						net.minecraft.network.chat.Component.translatable("orbit_cosmetic.update.found.text"));
				}
			});
		}, "Orbit update check");
		thread.setDaemon(true);
		thread.start();
	}

	/** The newer version on offer, or null (also once it is installed, waiting for a restart). */
	public static @Nullable String available() {
		return installer == null || installed ? null : available;
	}

	/** Installs the offered version (in the background); false when there is none. */
	public static boolean install(Consumer<String> result) {
		Installer how = installer;
		if (how == null || installed) {
			return false;
		}
		how.install(answer -> {
			if (answer.equals("UPDATED")) {
				installed = true;
			}
			result.accept(answer);
		});
		return true;
	}

	/** This mod's version, such as 0.3.5. */
	public static String modVersion() {
		return FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/** True when version {@code a} (like 0.3.5) is older than {@code b}; false when either is not a version. */
	public static boolean isOlder(String a, @Nullable String b) {
		int[] x = parse(a);
		int[] y = parse(b);
		if (x == null || y == null) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (x[i] != y[i]) {
				return x[i] < y[i];
			}
		}
		return false;
	}

	private static int @Nullable [] parse(@Nullable String version) {
		String core = version == null ? "" : version.split("[+-]", 2)[0];
		String[] parts = core.split("\\.");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
