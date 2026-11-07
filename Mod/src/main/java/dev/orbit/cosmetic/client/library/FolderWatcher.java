package dev.orbit.cosmetic.client.library;

import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Watches the player's folders and reloads the libraries when files are added, removed or changed, so the Orbit
 * menu always shows what is on disk. Changes are collected for a moment first: saving from Blockbench writes several
 * times in a row.
 */
public final class FolderWatcher {
	private static final long QUIET_MS = 400;
	private static final int DEPTH = 3;

	private final List<Path> roots;
	private final Runnable onChange;
	private WatchService service;

	public FolderWatcher(List<Path> roots, Runnable onChange) {
		this.roots = roots;
		this.onChange = onChange;
	}

	public void start() {
		try {
			service = roots.getFirst().getFileSystem().newWatchService();
			for (Path root : roots) {
				registerTree(root);
			}
		} catch (IOException | RuntimeException e) {
			OrbitClient.LOGGER.warn("Could not watch the Orbit folders; open the Orbit menu again to see new files", e);
			return;
		}
		Thread thread = new Thread(this::run, "Orbit folder watcher");
		thread.setDaemon(true);
		thread.start();
	}

	private void registerTree(Path root) {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> folders = Files.walk(root, DEPTH, new FileVisitOption[0])) {
			for (Path folder : folders.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
				folder.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Could not watch {}", root, e);
		}
	}

	private void run() {
		try {
			while (true) {
				WatchKey key = service.take();
				boolean changed = handle(key);
				// Wait until nothing changed for a moment.
				WatchKey next;
				while ((next = service.poll(QUIET_MS, TimeUnit.MILLISECONDS)) != null) {
					changed |= handle(next);
				}
				if (changed) {
					Minecraft.getInstance().execute(onChange);
				}
			}
		} catch (InterruptedException | ClosedWatchServiceException e) {
			// stopped
		}
	}

	/** Handles one folder's events; new folders are watched too. Returns whether anything relevant changed. */
	private boolean handle(WatchKey key) {
		boolean changed = false;
		Path folder = (Path) key.watchable();
		for (WatchEvent<?> event : key.pollEvents()) {
			if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
				changed = true;
				continue;
			}
			Path path = folder.resolve((Path) event.context());
			String name = path.getFileName().toString();
			if (name.endsWith(".tmp")) {
				continue; // Orbit's own atomic writes; the rename that follows is reported as well
			}
			if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
				registerTree(path);
			}
			changed = true;
		}
		key.reset();
		return changed;
	}
}
