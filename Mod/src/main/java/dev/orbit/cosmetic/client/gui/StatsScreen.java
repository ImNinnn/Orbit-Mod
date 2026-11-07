package dev.orbit.cosmetic.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.social.Profile;
import dev.orbit.cosmetic.client.social.Social;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A player's statistics page: their profile (pronouns, description), friends, time played, screenshots and what is
 * in their wardrobe, on a background in their colour, with the outfit they chose shown on their player at the
 * right. One's own page has Edit (see {@link ProfileEditScreen}); friends see each other's pages (shared through
 * Orbit Cloud, unless turned off in the settings) and can report one.
 */
public final class StatsScreen extends OrbitScreen {
	private static final int WIDTH = 400;
	private static final int HEIGHT = 250;
	private static final int SIDE = 96;
	private static final int TILE_H = 34;
	/** The files of the friend's outfit shown, kept loaded (see {@link #assetsInUse}). */
	private static final Set<AssetId> SHOWN = new HashSet<>();

	private final UUID player;
	private final String name;
	private final boolean own;
	private final PreviewModel preview = new PreviewModel();
	/** A friend's page from Orbit Cloud: null while loading. */
	private @Nullable JsonObject page;
	private @Nullable String problem;
	/** One's own folders, counted when the page opens. */
	private Social.@Nullable StatsCounts counts;

	/** One's own page. */
	public StatsScreen(@Nullable Screen parent) {
		this(parent, Minecraft.getInstance().getUser().getProfileId(), Minecraft.getInstance().getUser().getName());
	}

	public StatsScreen(@Nullable Screen parent, UUID player, String name) {
		super(Component.translatable("orbit_cosmetic.stats.title"), parent);
		this.player = player;
		this.name = name;
		this.own = player.equals(Minecraft.getInstance().getUser().getProfileId());
	}

	/** Keeps the files of a friend's outfit loaded while their page is open. */
	public static void assetsInUse(Set<AssetId> inUse) {
		inUse.addAll(SHOWN);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	/** The page's size: at most 400 by 250, smaller on small windows. */
	private int pw() {
		return Math.min(WIDTH, width - 16);
	}

	private int ph() {
		return Math.min(HEIGHT, height - 16);
	}

	/** The outfit column. */
	private int side() {
		return Math.clamp(pw() * 24 / 100, 60, SIDE);
	}

	private int left() {
		return width / 2 - pw() / 2;
	}

	private int top() {
		return Math.max(8, (height - ph()) / 2);
	}

	@Override
	protected void init() {
		int right = left() + pw() - 6;
		backButton(right - 16, top() + 6);
		if (own) {
			counts = Social.counts();
			addRenderableWidget(new FlatButton(right - 70, top() + 6, 50, 16, Component.translatable("orbit_cosmetic.stats.edit"), "edit", FlatButton.Style.SECONDARY,
				b -> Compat.setScreen(new ProfileEditScreen(this))));
		} else {
			FlatButton report = addRenderableWidget(new FlatButton(right - 74, top() + 6, 54, 16, Component.translatable("orbit_cosmetic.stats.report"), null,
				FlatButton.Style.DANGER, b -> confirmReport()));
			report.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.stats.report.tooltip")));
			if (page == null) {
				orbit().social().statsOf(player, loaded -> page = loaded, error -> problem = error);
			}
		}
	}

	private void confirmReport() {
		Compat.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				orbit().backend().report(player, name, "profile", "", List.of(),
					created -> Notifications.success(Component.translatable("orbit_cosmetic.stats.reported", name), Component.empty()),
					error -> Notifications.error(Component.translatable("orbit_cosmetic.report.failed"), Component.literal(error)));
			}
			Compat.setScreen(this);
		}, Component.translatable("orbit_cosmetic.stats.report.title", name), Component.translatable("orbit_cosmetic.stats.report.text")));
	}

	@Override
	public void onClose() {
		SHOWN.clear();
		super.onClose();
	}

	private int color() {
		if (own) {
			return orbit().config().statsColor;
		}
		return page != null && page.get("color") instanceof JsonElement color && color.isJsonPrimitive() ? color.getAsInt() & 0xFFFFFF : 0x5A5A66;
	}

	private @Nullable JsonObject stats() {
		return page != null && page.get("stats") instanceof JsonObject stats ? stats : null;
	}

	private static long number(@Nullable JsonObject json, String key) {
		return json != null && json.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsLong() : 0;
	}

	private static String text(@Nullable JsonObject json, String key) {
		return json != null && json.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsString() : "";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = left();
		int y = top();
		int color = color();
		// The page's colour, fading into the panel.
		Theme.panel(graphics, x, y, pw(), ph());
		graphics.fillGradient(x + 1, y + 1, x + pw() - 1, y + ph() - 1, 0xFF000000 | Theme.shade(0xFF000000 | color, 0.75F), Theme.PANEL);
		graphics.text(font, title, x + 8, y + 10, Theme.TEXT, true);
		Social social = orbit().social();
		JsonObject stats = stats();
		int contentW = pw() - side() - 16;
		PlayerFaceExtractor.extractRenderState(graphics, social.skin(player, name), x + 8, y + 28, 32);
		graphics.text(font, name, x + 48, y + 30, Theme.TEXT, true);
		String pronouns = own ? orbit().config().profilePronouns : text(stats, "pronouns");
		if (!pronouns.isBlank()) {
			graphics.text(font, Component.literal(pronouns).withStyle(ChatFormatting.GRAY), x + 52 + font.width(name), y + 30, Theme.TEXT, false);
		}
		double memberSince = own ? social.memberSince() : page == null ? 0 : number(page, "first_seen");
		Component since = memberSince > 0
			? Component.translatable("orbit_cosmetic.account.member_since", DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date((long) (memberSince * 1000))))
			: Component.translatable(problem != null || own && social.problem() != null ? "orbit_cosmetic.account.offline" : "orbit_cosmetic.account.loading");
		graphics.text(font, since.copy().withStyle(ChatFormatting.GRAY), x + 48, y + 44, Theme.TEXT, false);

		// The description, up to four lines (as many as fit above the tiles).
		String description = own ? orbit().config().profileDescription : text(stats, "description");
		int dy = y + 66;
		int lines = Math.clamp((y + ph() - 2 * (TILE_H + 4) - 22 - 4 - dy) / 10, 0, 4);
		if (!description.isBlank()) {
			for (FormattedCharSequence line : font.split(Component.literal(description), contentW).stream().limit(lines).toList()) {
				graphics.text(font, line, x + 8, dy, Theme.TEXT, false);
				dy += 10;
			}
		} else if (own) {
			graphics.text(font, Component.translatable("orbit_cosmetic.stats.no_description").withStyle(ChatFormatting.DARK_GRAY), x + 8, dy, Theme.TEXT, false);
		}

		int tileW = (contentW - 8) / 3;
		int ty = y + ph() - 2 * (TILE_H + 4) - 22;
		if (!own && page != null && stats == null) {
			graphics.text(font, Component.translatable("orbit_cosmetic.stats.private", name).withStyle(ChatFormatting.GRAY), x + 8, ty + 4, Theme.TEXT, false);
			tile(graphics, x + 8, ty + 18, tileW, "social", Component.translatable("orbit_cosmetic.account.friends"), Long.toString(number(page, "friends")));
		} else if (own || stats != null) {
			long friends = own ? social.friends().size() : number(page, "friends");
			long played = own ? social.playedSeconds() : number(stats, "played_seconds");
			tile(graphics, x + 8, ty, tileW, "social", Component.translatable("orbit_cosmetic.account.friends"), Long.toString(friends));
			tile(graphics, x + 12 + tileW, ty, tileW, "play", Component.translatable("orbit_cosmetic.account.played"), played(played));
			tile(graphics, x + 16 + tileW * 2, ty, tileW, "image", Component.translatable("orbit_cosmetic.account.screenshots"),
				Long.toString(own ? social.screenshotsTaken() : number(stats, "screenshots")));
			ty += TILE_H + 4;
			tile(graphics, x + 8, ty, tileW, "shirt", Component.translatable("orbit_cosmetic.account.cosmetics"), Long.toString(own ? counts.cosmetics() : number(stats, "cosmetics")));
			tile(graphics, x + 12 + tileW, ty, tileW, "emote", Component.translatable("orbit_cosmetic.account.emotes"), Long.toString(own ? counts.emotes() : number(stats, "emotes")));
			tile(graphics, x + 16 + tileW * 2, ty, tileW, "player", Component.translatable("orbit_cosmetic.account.body_parts"),
				Long.toString(own ? counts.bodyParts() : number(stats, "body_parts")));
		} else {
			Component loading = problem != null ? Component.literal(problem) : Component.translatable("orbit_cosmetic.account.loading");
			graphics.text(font, loading.copy().withStyle(ChatFormatting.GRAY), x + 8, ty + 4, Theme.TEXT, false);
		}

		Component footer = own
			? Component.translatable(orbit().config().shareStatistics ? "orbit_cosmetic.stats.shared" : "orbit_cosmetic.stats.not_shared").withStyle(ChatFormatting.GRAY)
			: Component.translatable("orbit_cosmetic.account.version", FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?")).withStyle(ChatFormatting.DARK_GRAY);
		// Up to two lines, from the bottom up (small windows wrap it).
		List<FormattedCharSequence> footerLines = font.split(footer, contentW).stream().limit(2).toList();
		for (int i = 0; i < footerLines.size(); i++) {
			graphics.text(font, footerLines.get(i), x + 8, y + ph() - 14 - (footerLines.size() - 1 - i) * 10, Theme.TEXT, false);
		}

		// The outfit, on the player, at the right.
		int sx = x + pw() - side() - 6;
		int sy = y + 28;
		int sh = ph() - 36;
		graphics.fill(sx, sy, sx + side(), sy + sh, 0x55000000);
		if (own) {
			preview.setWorn(Profile.ownOutfit());
			preview.setSkin(orbit().menuSkin());
		} else {
			SHOWN.clear();
			JsonArray outfit = stats != null && stats.get("outfit") instanceof JsonArray items ? items : null;
			preview.setWorn(Profile.friendOutfit(outfit, SHOWN::add));
			preview.setSkin(social.skin(player, name));
		}
		float yaw = 25 + (float) Math.atan((sx + side() / 2.0 - mouseX) / 80.0) * 20;
		PreviewBatch.panel(graphics, sx, sy, sx + side(), sy + sh, List.of(preview.entryIn(4, 6, side() - 8, sh - 12, yaw, 0, partialTick, true)));
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void tile(GuiGraphicsExtractor graphics, int x, int y, int w, String icon, Component label, String value) {
		graphics.fill(x, y, x + w, y + TILE_H, 0x66000000);
		Theme.icon(graphics, icon, x + 6, y + 6, Theme.MUTED);
		graphics.text(font, Theme.clip(font, label.copy().withStyle(ChatFormatting.GRAY), w - 22), x + 18, y + 7, Theme.TEXT, false);
		graphics.text(font, value, x + 6, y + 21, Theme.TEXT, false);
	}

	/** "3 h 12 min", "45 min". */
	private static String played(long seconds) {
		long minutes = seconds / 60;
		if (minutes < 60) {
			return Component.translatable("orbit_cosmetic.account.minutes", minutes).getString();
		}
		return Component.translatable("orbit_cosmetic.account.hours", minutes / 60, minutes % 60).getString();
	}
}
