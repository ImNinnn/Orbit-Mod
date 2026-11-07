package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.TextureAnimation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The Animations tab of a cosmetic's settings: assigns each animation to a player state and sets how it plays.
 * Animations named after a state (IDLE, WALK, SPRINT, ...) start out assigned to it; others start unassigned. The
 * other tabs are Velocity (movement physics) and Particles. Under the animations, each texture can be animated like
 * Blockbench's animated textures: frames stacked in the image, with their size and how long each shows.
 */
public final class AnimationSettingsScreen extends OrbitScreen {
	/** Title line (12) + two widget lines (22 + 20) + spacing. */
	private static final int ROW_HEIGHT = 60;
	/** Where the rows start, under the tabs and the title. */
	private static final int TOP = SettingsTabs.HEIGHT + 40;

	private final LocalCosmetic cosmetic;
	private int page;

	public AnimationSettingsScreen(Screen parent, LocalCosmetic cosmetic) {
		super(Component.translatable("orbit_cosmetic.animations.title", cosmetic.displayName()), parent);
		this.cosmetic = cosmetic;
	}

	private int rowsPerPage() {
		return Math.max(1, (height - TOP - 50) / ROW_HEIGHT);
	}

	/** The model's textures that can be animated (not the wearer's skin). */
	private List<Integer> textures() {
		List<Integer> result = new java.util.ArrayList<>();
		if (cosmetic.model() != null) {
			for (int i = 0; i < cosmetic.model().textures().size(); i++) {
				if (!cosmetic.model().textures().get(i).isSkinSlot()) {
					result.add(i);
				}
			}
		}
		return result;
	}

	private int rowCount() {
		List<AnimationClip> clips = cosmetic.model() == null ? List.of() : cosmetic.model().animations();
		return clips.size() + textures().size();
	}

	private static List<Integer> divisors(int value) {
		List<Integer> result = new java.util.ArrayList<>();
		for (int i = 1; i <= value; i++) {
			if (value % i == 0) {
				result.add(i);
			}
		}
		return result;
	}

	/** The row of a texture: frame width, frame height (the whole image: not animated) and time per frame. */
	private void textureRow(int texture, int left, int y, int columnWidth) {
		var image = cosmetic.model().textures().get(texture);
		TextureAnimation current = cosmetic.textureAnimations().stream().filter(animation -> animation.texture() == texture).findFirst()
			.orElse(new TextureAnimation(texture, image.width(), image.height(), Math.max(1, image.frameTime())));
		addRenderableWidget(FlatCycle.of(left, y, columnWidth, 20, Component.translatable("orbit_cosmetic.textures.frame_width.label"), divisors(image.width()), current.frameWidth(),
			w -> Component.translatable("orbit_cosmetic.textures.frame_width", w), w -> Component.translatable("orbit_cosmetic.textures.frame_width.tooltip"),
			w -> {
				OrbitClient.get().library().setTextureAnimation(cosmetic, texture, w, current.frameHeight(), current.frameTicks());
				rebuildWidgets();
			}));
		addRenderableWidget(FlatCycle.of(left + columnWidth + 4, y, columnWidth, 20, Component.translatable("orbit_cosmetic.textures.frame_height.label"), divisors(image.height()),
			current.frameHeight(),
			h -> h == image.height() ? Component.translatable("orbit_cosmetic.textures.not_animated")
				: Component.translatable("orbit_cosmetic.textures.frame_height", h, image.height() / h),
			h -> Component.translatable("orbit_cosmetic.textures.frame_height.tooltip"),
			h -> {
				OrbitClient.get().library().setTextureAnimation(cosmetic, texture, current.frameWidth(), h, current.frameTicks());
				rebuildWidgets();
			}));
		addRenderableWidget(new ValueSlider(left + (columnWidth + 4) * 2, y, columnWidth, 1, 40, 1, current.frameTicks(),
			value -> Component.translatable("orbit_cosmetic.textures.speed", (int) value, String.format(Locale.ROOT, "%.2f", value / 20)),
			value -> {
				TextureAnimation now = cosmetic.textureAnimations().stream().filter(animation -> animation.texture() == texture).findFirst().orElse(current);
				OrbitClient.get().library().setTextureAnimation(cosmetic, texture, now.frameWidth(), now.frameHeight(), (int) value);
			}));
	}

	@Override
	protected void init() {
		SettingsTabs.buttons(width, 0, parent, cosmetic).forEach(this::addRenderableWidget);
		List<AnimationClip> clips = cosmetic.model() == null ? List.of() : cosmetic.model().animations();
		List<AnimationSettings> settings = cosmetic.animationSettings();
		List<Integer> textures = textures();
		int perPage = rowsPerPage();
		int pages = Math.max(1, (rowCount() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		int columnWidth = (rowWidth - 8) / 3;

		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= clips.size() + textures.size()) {
				break;
			}
			int y = TOP + row * ROW_HEIGHT + 12;
			if (index >= clips.size()) {
				textureRow(textures.get(index - clips.size()), left, y, columnWidth);
				continue;
			}
			AnimationSettings current = settings.get(index);
			int i = index;

			boolean random = current.trigger() == AnimationTrigger.RANDOM;
			addRenderableWidget(FlatCycle.of(left, y, random ? columnWidth : columnWidth * 2 + 4, 20, Component.translatable("orbit_cosmetic.animations.trigger"),
				List.of(AnimationTrigger.values()), current.trigger(),
				t -> Component.translatable("orbit_cosmetic.trigger." + t.name().toLowerCase(Locale.ROOT)),
				t -> Component.translatable("orbit_cosmetic.trigger." + t.name().toLowerCase(Locale.ROOT) + ".tooltip"), value -> {
					update(i, cosmetic.animationSettings().get(i).withTrigger(value));
					rebuildWidgets();
				}));
			if (random) {
				// Random animations always play once per occurrence; the wait range replaces the mode.
				addRenderableWidget(new ValueSlider(left + columnWidth + 4, y, columnWidth, 0, 120, 0.5, current.minWait(),
					value -> Component.translatable("orbit_cosmetic.animations.min_wait", formatSeconds(value)),
					value -> update(i, cosmetic.animationSettings().get(i).withMinWait((float) value))));
				addRenderableWidget(new ValueSlider(left + (columnWidth + 4) * 2, y, columnWidth, 0, 120, 0.5, current.maxWait(),
					value -> Component.translatable("orbit_cosmetic.animations.max_wait", formatSeconds(value)),
					value -> update(i, cosmetic.animationSettings().get(i).withMaxWait((float) value))));
			} else {
				addRenderableWidget(FlatCycle.of(left + (columnWidth + 4) * 2, y, columnWidth, 20, Component.translatable("orbit_cosmetic.animations.loop"),
					List.of(LoopMode.values()), current.loop(),
					m -> Component.translatable("orbit_cosmetic.loop." + m.name().toLowerCase(Locale.ROOT)),
					m -> Component.translatable("orbit_cosmetic.loop." + m.name().toLowerCase(Locale.ROOT) + ".tooltip"),
					value -> update(i, cosmetic.animationSettings().get(i).withLoop(value))));
			}

			int y2 = y + 22;
			addRenderableWidget(new ValueSlider(left, y2, columnWidth, AnimationSettings.MIN_SPEED, AnimationSettings.MAX_SPEED, 0.05, current.speed(),
				value -> Component.translatable("orbit_cosmetic.animations.speed", String.format(Locale.ROOT, "%.2f", value)),
				value -> update(i, cosmetic.animationSettings().get(i).withSpeed((float) value))));
			addRenderableWidget(new ValueSlider(left + columnWidth + 4, y2, columnWidth, 0, AnimationSettings.MAX_PRIORITY, 1, current.priority(),
				value -> Component.translatable("orbit_cosmetic.animations.priority", (int) value),
				value -> update(i, cosmetic.animationSettings().get(i).withPriority((int) value))));
			addRenderableWidget(FlatCycle.onOff(left + (columnWidth + 4) * 2, y2, columnWidth, 20, Component.translatable("orbit_cosmetic.animations.interrupt"), current.interrupt(),
				Component.translatable("orbit_cosmetic.animations.interrupt.tooltip"),
				value -> update(i, cosmetic.animationSettings().get(i).withInterrupt(value))));
		}

		int footerY = height - 28;
		if (pages > 1) {
			addRenderableWidget(new FlatButton(left, footerY, 20, 20, Component.literal("<"), null, FlatButton.Style.SECONDARY, b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			})).active = page > 0;
			addRenderableWidget(new FlatButton(left + 24, footerY, 20, 20, Component.literal(">"), null, FlatButton.Style.SECONDARY, b -> {
				page = Math.min(pages - 1, page + 1);
				rebuildWidgets();
			})).active = page < pages - 1;
		}
		// Narrower with page buttons, so the page number fits beside them.
		int footerW = pages > 1 ? Math.clamp(width / 2 - 4 - left - 76, 60, 150) : 150;
		FlatButton reset = addRenderableWidget(new FlatButton(width / 2 - footerW - 4, footerY, footerW, 20, Component.translatable("orbit_cosmetic.animations.reset"), null, FlatButton.Style.SECONDARY, b -> {
			OrbitClient.get().library().resetAnimationSettings(cosmetic);
			OrbitClient.get().library().resetTextureAnimations(cosmetic);
			rebuildWidgets();
		}));
		reset.active = rowCount() > 0;
		addRenderableWidget(new FlatButton(width / 2 + 4, footerY, footerW, 20, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	private static String formatSeconds(double seconds) {
		return String.format(Locale.ROOT, seconds % 1 == 0 ? "%.0f" : "%.1f", seconds);
	}

	private void update(int index, AnimationSettings settings) {
		OrbitClient.get().library().setAnimationSettings(cosmetic, index, settings);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, SettingsTabs.HEIGHT + 8, Theme.TEXT);
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.animations.subtitle").withStyle(ChatFormatting.GRAY), width / 2, SettingsTabs.HEIGHT + 20, Theme.TEXT);
		List<AnimationClip> clips = cosmetic.model() == null ? List.of() : cosmetic.model().animations();
		List<Integer> textures = textures();
		if (clips.isEmpty() && textures.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.animations.none").withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 10, Theme.TEXT);
		}
		int perPage = rowsPerPage();
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= clips.size() + textures.size()) {
				break;
			}
			if (index >= clips.size()) {
				var image = cosmetic.model().textures().get(textures.get(index - clips.size()));
				Component name = Component.translatable("orbit_cosmetic.textures.title", image.name()).withStyle(ChatFormatting.BOLD)
					.append(Component.literal("  " + image.width() + "x" + image.height()).withStyle(ChatFormatting.DARK_GRAY));
				graphics.text(font, name, left, TOP + row * ROW_HEIGHT + 1, Theme.TEXT);
				continue;
			}
			AnimationClip clip = clips.get(index);
			int y = TOP + row * ROW_HEIGHT;
			Component name = Component.literal(clip.name()).withStyle(ChatFormatting.BOLD)
				.append(Component.literal(String.format(Locale.ROOT, "  %.2fs", clip.length())).withStyle(ChatFormatting.DARK_GRAY));
			graphics.text(font, name, left, y + 1, Theme.TEXT);
		}
		int pages = Math.max(1, (rowCount() + perPage - 1) / perPage);
		if (pages > 1) {
			int rowLeft = (width - Math.min(420, width - 20)) / 2;
			graphics.text(font, Component.literal((page + 1) + "/" + pages), rowLeft + 50, height - 22, Theme.MUTED);
		}
	}
}
