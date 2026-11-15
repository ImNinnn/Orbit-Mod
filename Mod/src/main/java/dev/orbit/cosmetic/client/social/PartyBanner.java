package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * While the player is in a party, a green line at the top of every menu says so: "In a party with Steve", or "You
 * are the party leader in Builders - Alex, Steve" (a group's party lists its other members). It fades in and out a
 * little. Also the stars rising behind a conversation that has a party going.
 */
public final class PartyBanner {
	private static final int GREEN = 0x55FF55;
	/** One fade, in and out (milliseconds), and how faint it gets. */
	private static final double PULSE_MS = 3200;
	private static final float FAINTEST = 0.55F;

	private PartyBanner() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> {
			Component line = line();
			if (line != null) {
				graphics.nextStratum();
				draw(graphics, Minecraft.getInstance().font, current.width, line);
			}
		}));
	}

	/** What the line says, or null out of a party. */
	private static @Nullable Component line() {
		Social social = OrbitClient.get().social();
		Social.Party party = social.party();
		UUID me = social.me();
		if (party == null || me == null) {
			return null;
		}
		boolean leader = party.leader().equals(me);
		if (party.group()) {
			Social.Group group = social.group(party.chat());
			String name = group != null ? group.name() : "?";
			String others = party.members().stream().filter(member -> !member.uuid().equals(me)).map(Social.Person::name).collect(Collectors.joining(", "));
			String key = leader ? "orbit_cosmetic.party.banner.leader_group" : "orbit_cosmetic.party.banner.group";
			return others.isEmpty() ? Component.translatable(key + ".alone", name) : Component.translatable(key, name, others);
		}
		Social.Person friend = social.friend(party.chat());
		String name = friend != null ? friend.name() : party.members().stream().filter(member -> !member.uuid().equals(me)).map(Social.Person::name).findFirst().orElse("?");
		return Component.translatable(leader ? "orbit_cosmetic.party.banner.leader_friend" : "orbit_cosmetic.party.banner.friend", name);
	}

	private static void draw(GuiGraphicsExtractor graphics, Font font, int width, Component line) {
		double phase = System.currentTimeMillis() % (long) PULSE_MS / PULSE_MS;
		float alpha = FAINTEST + (1 - FAINTEST) * (float) (0.5 + 0.5 * Math.cos(phase * Math.PI * 2));
		int shown = Math.min(font.width(line), width - 16);
		int x = (width - shown) / 2;
		int a = Math.round(alpha * 255);
		graphics.fill(x - 4, 1, x + shown + 4, 12, Math.round(alpha * 0x70) << 24);
		graphics.text(font, Theme.clip(font, line, width - 16), x, 3, a << 24 | GREEN, true);
	}

	// ---------------------------------------------------------------- stars

	/**
	 * Stars rising from the bottom of an area to its top, slowly, fading out as they go (worked out from the time:
	 * the same star is in the same place every frame).
	 */
	public static void stars(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
		int w = right - left;
		int h = bottom - top;
		if (w <= 0 || h <= 0) {
			return;
		}
		double seconds = System.currentTimeMillis() / 1000.0;
		int count = Math.clamp(w * h / 1400, 8, 60);
		for (int i = 0; i < count; i++) {
			// Each star's own values, from its index.
			double across = hash(i * 3 + 1);
			double speed = 8 + hash(i * 3 + 2) * 14;
			double start = hash(i * 3 + 3);
			int size = 5 + (int) (hash(i * 7 + 5) * 3) * 2;
			double travel = h + size;
			double risen = (seconds * speed + start * travel) % travel;
			int y = (int) (bottom - risen);
			// A little sway as it rises.
			int x = left + (int) (across * (w - size)) + (int) Math.round(Math.sin(seconds * 0.8 + i) * 2);
			float fade = Mth.clamp((float) (risen / travel), 0, 1);
			float alpha = 0.55F * (1 - fade * fade) * Mth.clamp((float) risen / 12F, 0, 1);
			if (alpha <= 0.02F) {
				continue;
			}
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Theme.icon("star"), x, y, size, size, Math.round(alpha * 255) << 24 | (color & 0xFFFFFF));
		}
	}

	private static double hash(int n) {
		long bits = n * 0x9E3779B97F4A7C15L;
		bits ^= bits >>> 31;
		bits *= 0xBF58476D1CE4E5B9L;
		bits ^= bits >>> 29;
		return (bits >>> 11) / (double) (1L << 53);
	}
}
