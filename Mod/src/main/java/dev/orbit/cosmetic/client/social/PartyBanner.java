package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * While the player is in a party, a green line at the top of every menu says so: "In a party with Steve", or "In a
 * party in Builders - Alex, Steve" (a group's party lists its other members). It fades in and out a
 * little. Also the stars rising behind a conversation that has a party going.
 */
public final class PartyBanner {
	private static final int GREEN = 0x55FF55;
	/** One fade, in and out (milliseconds), and how faint it gets. */
	private static final double PULSE_MS = 3200;
	private static final float FAINTEST = 0.55F;

	private PartyBanner() {
	}

	/** How much the line shows (it goes when the mouse is on it, and comes back once it leaves). */
	private static float shown = 1;
	private static long lastFrame;

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ScreenEvents.afterExtract(screen).register((current, extractor, mouseX, mouseY, partialTick) -> {
			Component line = line();
			if (line != null) {
				OrbitGraphics graphics = OrbitGraphics.of(extractor);
				graphics.layer();
				Social.Party party = OrbitClient.get().social().party();
				// Alone in it: grey ("No one in party").
				int color = party != null && party.members().size() < 2 ? 0xA0A0A0 : GREEN;
				draw(graphics, Minecraft.getInstance().font, current.width, line, mouseX, mouseY, color);
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
		if (party.members().size() < 2) {
			return Component.translatable("orbit_cosmetic.party.banner.alone");
		}
		if (party.group()) {
			Social.Group group = social.group(party.chat());
			String name = group != null ? group.name() : "?";
			String others = party.members().stream().filter(member -> !member.uuid().equals(me)).map(Social.Person::name).collect(Collectors.joining(", "));
			return others.isEmpty() ? Component.translatable("orbit_cosmetic.party.banner.group.alone", name) : Component.translatable("orbit_cosmetic.party.banner.group", name, others);
		}
		Social.Person friend = social.friend(party.chat());
		String name = friend != null ? friend.name() : party.members().stream().filter(member -> !member.uuid().equals(me)).map(Social.Person::name).findFirst().orElse("?");
		return Component.translatable("orbit_cosmetic.party.banner.friend", name);
	}

	/** Drawn as a HUD component is: dark, with a green line on its left; gone while the mouse is on it. */
	private static void draw(OrbitGraphics graphics, Font font, int width, Component line, int mouseX, int mouseY, int color) {
		int textWidth = Math.min(font.width(line), width - 24);
		int w = textWidth + 12;
		int h = 14;
		int x = (width - w) / 2;
		int y = 2;
		long now = System.currentTimeMillis();
		float step = lastFrame == 0 ? 1 : Math.min(1, (now - lastFrame) / 150F);
		lastFrame = now;
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		shown = hovered ? Math.max(0, shown - step) : Math.min(1, shown + step / 2);
		if (shown <= 0.01F) {
			return;
		}
		double phase = now % (long) PULSE_MS / PULSE_MS;
		float pulse = FAINTEST + (1 - FAINTEST) * (float) (0.5 + 0.5 * Math.cos(phase * Math.PI * 2));
		graphics.fill(x, y, x + w, y + h, alpha(0xB01C1C20, shown));
		graphics.outline(x, y, w, h, alpha(0xC02E2E35, shown));
		graphics.fill(x, y, x + 2, y + h, alpha(0xFF000000 | color, shown));
		graphics.text(font, Theme.clip(font, line, textWidth), x + 7, y + 3, alpha(0xFF000000 | color, shown * pulse), false);
	}

	private static int alpha(int color, float factor) {
		return Math.round((color >>> 24) * Mth.clamp(factor, 0, 1)) << 24 | (color & 0xFFFFFF);
	}

	// ---------------------------------------------------------------- stars

	/**
	 * Stars rising from the bottom of an area to its top, slowly, fading out as they go (worked out from the time:
	 * the same star is in the same place every frame).
	 */
	public static void stars(OrbitGraphics graphics, int left, int top, int right, int bottom, int color) {
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
			graphics.sprite(Theme.icon("star"), x, y, size, size, Math.round(alpha * 255) << 24 | (color & 0xFFFFFF));
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
