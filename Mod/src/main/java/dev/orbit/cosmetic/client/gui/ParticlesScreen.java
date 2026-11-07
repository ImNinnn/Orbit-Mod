package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.emote.EmoteMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The particle emitters of a cosmetic (its Particles tab) or of an emote (its settings, with the Standard / Mobile
 * switch at the bottom): every group inside the model's PARTICLES group, each with its texture and whether it is on.
 */
public final class ParticlesScreen extends OrbitScreen {
	private static final int ROW_HEIGHT = 24;

	private final ParticleTarget target;
	private final @Nullable LocalCosmetic cosmetic;
	private final EmoteLibrary.@Nullable LocalEmote emote;
	private int page;

	/** @param cosmetic for a cosmetic: shows its settings tabs; @param emote for an emote: shows its mode */
	ParticlesScreen(Screen parent, ParticleTarget target, @Nullable LocalCosmetic cosmetic, EmoteLibrary.@Nullable LocalEmote emote) {
		super(Component.translatable(emote != null ? "orbit_cosmetic.emote_settings.title" : "orbit_cosmetic.particles.title", target.name()), parent);
		this.target = target;
		this.cosmetic = cosmetic;
		this.emote = emote;
	}

	/** A cosmetic's Particles tab. */
	public static ParticlesScreen forCosmetic(Screen parent, LocalCosmetic cosmetic) {
		return new ParticlesScreen(parent, ParticleTarget.of(cosmetic), cosmetic, null);
	}

	/** An emote's settings: its particles and whether it is Standard or Mobile. */
	public static ParticlesScreen forEmote(Screen parent, EmoteLibrary.LocalEmote emote) {
		return new ParticlesScreen(parent, ParticleTarget.of(emote), null, emote);
	}

	private int top() {
		return (cosmetic != null ? SettingsTabs.HEIGHT : 0) + 46;
	}

	private int rowsPerPage() {
		return Math.max(1, (height - top() - 54) / ROW_HEIGHT);
	}

	@Override
	protected void init() {
		if (cosmetic != null) {
			SettingsTabs.buttons(width, 2, parent, cosmetic).forEach(this::addRenderableWidget);
		}
		List<ParticleSettings> particles = target.particles();
		int perPage = rowsPerPage();
		int pages = Math.max(1, (particles.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= particles.size()) {
				break;
			}
			ParticleSettings settings = particles.get(index);
			int y = top() + row * ROW_HEIGHT;
			FlatButton toggle = addRenderableWidget(FlatCycle.onOff(left + rowWidth - 238, y, 104, 20, Component.translatable("orbit_cosmetic.particles.enabled"),
				settings.enabled(), Component.translatable("orbit_cosmetic.particles.enabled.tooltip"), value -> target.set(currentOf(settings.bone()).withEnabled(value))));
			toggle.active = target.editable() && settings.texture() >= 0;
			addRenderableWidget(new FlatButton(left + rowWidth - 130, y, 60, 20, Component.translatable("orbit_cosmetic.physics.edit"), null, FlatButton.Style.SECONDARY,
				b -> Compat.setScreen(new ParticleEditScreen(this, target, settings.bone())))).active = target.editable();
			addRenderableWidget(new FlatButton(left + rowWidth - 66, y, 66, 20, Component.translatable("orbit_cosmetic.physics.remove"), null, FlatButton.Style.SECONDARY, b -> {
				target.remove(settings.bone());
				rebuildWidgets();
			})).active = target.editable();
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
		// Add emitter... (any group), then the emote's mode, then Done.
		int buttons = emote != null ? 3 : 2;
		int buttonWidth = 120;
		int x = width / 2 - (buttonWidth * buttons + 4 * (buttons - 1)) / 2;
		FlatButton add = addRenderableWidget(new FlatButton(x, footerY, buttonWidth, 20, Component.translatable("orbit_cosmetic.particles.add"), "plus", FlatButton.Style.PRIMARY,
			b -> Compat.setScreen(new PartTreeScreen(this, target.model(), bone -> target.get(bone) != null, bone -> {
				target.add(bone);
				Compat.setScreen(target.get(bone) != null ? new ParticleEditScreen(this, target, bone) : this);
			}))));
		add.active = target.editable() && target.model() != null && particles.size() < ParticleSettings.MAX_EMITTERS;
		x += buttonWidth + 4;
		if (emote != null) {
			EmoteLibrary emotes = OrbitClient.get().emotes();
			FlatButton mode = addRenderableWidget(FlatCycle.of(x, footerY, buttonWidth, 20, Component.translatable("orbit_cosmetic.emote_settings.mode"), List.of(EmoteMode.values()),
				emotes.mode(emote), value -> Component.translatable("orbit_cosmetic.menu.emote.mode." + value.name().toLowerCase(Locale.ROOT)),
				value -> Component.translatable(value == EmoteMode.MOBILE ? "orbit_cosmetic.menu.emote.mobile_hint" : "orbit_cosmetic.menu.emote.standard_hint"),
				value -> emotes.setMode(emote, value)));
			if (emote.animation().isMobile()) {
				// It has animations for walking: always Mobile.
				mode.active = false;
				mode.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("orbit_cosmetic.emote_settings.mode.walking")));
			}
			x += buttonWidth + 4;
		}
		addRenderableWidget(new FlatButton(x, footerY, buttonWidth, 20, CommonComponents.GUI_DONE, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	private ParticleSettings currentOf(int bone) {
		ParticleSettings current = target.get(bone);
		return current != null ? current : ParticleSettings.defaults(bone, -1);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int titleY = (cosmetic != null ? SettingsTabs.HEIGHT : 0) + 8;
		graphics.centeredText(font, title, width / 2, titleY, Theme.TEXT);
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.particles.subtitle", ParticleSettings.MAX_PER_PLAYER).withStyle(ChatFormatting.GRAY), width / 2, titleY + 14, Theme.TEXT);
		List<ParticleSettings> particles = target.particles();
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		if (particles.isEmpty()) {
			int y = height / 2 - 20;
			for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.particles.empty").withStyle(ChatFormatting.GRAY), rowWidth)) {
				graphics.centeredText(font, line, width / 2, y, Theme.TEXT);
				y += 10;
			}
			return;
		}
		int perPage = rowsPerPage();
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= particles.size()) {
				break;
			}
			ParticleSettings settings = particles.get(index);
			int y = top() + row * ROW_HEIGHT;
			graphics.text(font, Component.literal(target.model().bones().get(settings.bone()).name()), left, y + 1, Theme.TEXT);
			graphics.text(font, Component.literal(textureName(target, settings.texture())).withStyle(ChatFormatting.GRAY), left, y + 11, Theme.TEXT);
		}
		if (!target.editable()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.particles.featured").withStyle(ChatFormatting.GRAY), width / 2, height - 44, Theme.TEXT);
		}
	}

	static String textureName(ParticleTarget target, int texture) {
		return texture >= 0 && texture < target.model().textures().size() ? target.model().textures().get(texture).name()
			: Component.translatable("orbit_cosmetic.particles.no_texture").getString();
	}
}
