package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.config.ClientConfig;
import net.minecraft.network.chat.Component;

/**
 * The coin joke, hidden in the settings: every item gets a made-up price (and now and then a made-up discount),
 * and the coin balance can be topped up for free by clicking it. Nothing is ever sold; it all stays on this
 * computer. Prices are derived from the item, so an item always shows the same price.
 */
public final class JokeCoins {
	/** Coins added per click on the counter. */
	public static final int FREE_COINS = 500;

	/** A made-up price; {@code was} is the "original" price of a fake discount, or 0. */
	public record Price(int coins, int was) {
	}

	private JokeCoins() {
	}

	public static boolean enabled() {
		return OrbitClient.get().config().jokeCoins;
	}

	public static long balance() {
		return OrbitClient.get().config().jokeCoinBalance;
	}

	public static void addFreeCoins() {
		ClientConfig config = OrbitClient.get().config();
		config.jokeCoinBalance = Math.min(999_999_999L, config.jokeCoinBalance + FREE_COINS);
		config.save();
		Notifications.success(Component.translatable("orbit_cosmetic.coins.added", FREE_COINS), Component.translatable("orbit_cosmetic.coins.added.detail"));
	}

	/** Gives coins back, after a purchase that could not be completed. */
	public static void refund(int coins) {
		ClientConfig config = OrbitClient.get().config();
		config.jokeCoinBalance = Math.min(999_999_999L, config.jokeCoinBalance + coins);
		config.save();
	}

	/** Takes the coins; false (and a hint) if there are not enough. */
	public static boolean spend(int coins) {
		ClientConfig config = OrbitClient.get().config();
		if (config.jokeCoinBalance < coins) {
			Notifications.warning(Component.translatable("orbit_cosmetic.coins.short"), Component.translatable("orbit_cosmetic.coins.short.detail"));
			return false;
		}
		config.jokeCoinBalance -= coins;
		config.save();
		return true;
	}

	/**
	 * @param key anything that identifies the item (its hash)
	 */
	public static Price price(String key, boolean emote, boolean plus, boolean bundle) {
		long seed = key.hashCode() * 0x9E3779B97F4A7C15L;
		int low;
		int high;
		if (bundle) {
			low = 1000;
			high = 2000;
		} else if (emote) {
			low = plus ? 300 : 100;
			high = plus ? 600 : 300;
		} else {
			low = plus ? 700 : 300;
			high = plus ? 1000 : 400;
		}
		int steps = (high - low) / 50;
		int coins = low + (int) Math.floorMod(seed >>> 7, (long) steps + 1) * 50;
		// About one item in four is "on sale".
		if (Math.floorMod(seed >>> 23, 4L) == 0) {
			int percent = 10 + (int) Math.floorMod(seed >>> 31, 4L) * 10;
			int was = coins;
			coins = Math.max(50, (was * (100 - percent) / 100) / 10 * 10);
			return new Price(coins, was);
		}
		return new Price(coins, 0);
	}

	public static String format(long coins) {
		return String.format(java.util.Locale.ROOT, "%,d", coins);
	}
}
