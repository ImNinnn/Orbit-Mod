package dev.orbit.cosmetic.common.util;

import java.util.List;

/**
 * Java 21's {@code List.getFirst()} and {@code getLast()}, for Java 17 (the oldest Minecraft versions Orbit runs on):
 * the same behaviour, an exception on an empty list included.
 */
public final class Lists {
	private Lists() {
	}

	public static <T> T first(List<T> list) {
		if (list.isEmpty()) {
			throw new java.util.NoSuchElementException();
		}
		return list.get(0);
	}

	public static <T> T last(List<T> list) {
		if (list.isEmpty()) {
			throw new java.util.NoSuchElementException();
		}
		return list.get(list.size() - 1);
	}
}
