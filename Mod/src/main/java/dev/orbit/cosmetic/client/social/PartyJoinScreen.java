package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** In a party, before joining a server or opening a world: alone, or with the party (a vote). */
public final class PartyJoinScreen extends OrbitScreen {
	private final Component place;
	private final boolean partyPossible;
	private final Runnable alone;
	private final Runnable withParty;

	PartyJoinScreen(@Nullable Screen parent, Component place, boolean partyPossible, Runnable alone, Runnable withParty) {
		super(Component.translatable("orbit_cosmetic.vote.choose.title"), parent);
		this.place = place;
		this.partyPossible = partyPossible;
		this.alone = alone;
		this.withParty = withParty;
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	@Override
	protected int[] panel() {
		int w = Math.min(width - 20, 260);
		int h = 104;
		return new int[] {(width - w) / 2, (height - h) / 2, w, h};
	}

	@Override
	protected void init() {
		int[] panel = panel();
		int half = (panel[2] - 24) / 2;
		int y = panel[1] + panel[3] - 50;
		addRenderableWidget(new FlatButton(panel[0] + 8, y, half, 20, Component.translatable("orbit_cosmetic.vote.choose.alone"), "play", FlatButton.Style.SECONDARY,
			b -> alone.run()));
		FlatButton party = addRenderableWidget(new FlatButton(panel[0] + 16 + half, y, half, 20, Component.translatable("orbit_cosmetic.vote.choose.party"), "party",
			FlatButton.Style.PRIMARY, b -> withParty.run()));
		party.active = partyPossible;
		party.setTooltip(Tooltip.create(Component.translatable(partyPossible ? "orbit_cosmetic.vote.choose.party.tooltip" : "orbit_cosmetic.vote.choose.no_e4mc",
			PartyVote.SECONDS)));
		addRenderableWidget(new FlatButton(panel[0] + 8, y + 24, panel[2] - 16, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
	}

	@Override
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int[] panel = panel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.GREEN);
		graphics.text(font, title, panel[0] + 10, panel[1] + 9, Theme.TEXT, false);
		graphics.text(font, Theme.clip(font, place, panel[2] - 20), panel[0] + 10, panel[1] + 25, Theme.GOLD, false);
		drawWidgets(graphics, mouseX, mouseY, partialTick);
	}
}
