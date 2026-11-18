package dev.orbit.cosmetic.client.social;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Waiting for the party: where to, the time left, how many join, and each member's answer; Go now (with who joined)
 * or Cancel. It goes on its own when everyone answered or the time is up.
 */
public final class PartyVoteScreen extends OrbitScreen {
	PartyVoteScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.vote.wait.title"), parent);
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	private List<Social.Person> members() {
		Social.Party party = OrbitClient.get().social().party();
		return party == null ? List.of() : party.members();
	}

	@Override
	protected int[] panel() {
		int w = Math.min(width - 20, 260);
		int h = Math.min(height - 20, 78 + members().size() * 14 + 30);
		return new int[] {(width - w) / 2, (height - h) / 2, w, h};
	}

	@Override
	protected void init() {
		int[] panel = panel();
		int half = (panel[2] - 24) / 2;
		int y = panel[1] + panel[3] - 28;
		addRenderableWidget(new FlatButton(panel[0] + 8, y, half, 20, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> PartyVote.cancel()));
		addRenderableWidget(new FlatButton(panel[0] + 16 + half, y, half, 20, Component.translatable("orbit_cosmetic.vote.go_now"), "play", FlatButton.Style.PRIMARY,
			b -> PartyVote.goNow()));
	}

	@Override
	public void onClose() {
		PartyVote.cancel();
	}

	@Override
	public void tick() {
		if (PartyVote.mine() == null && dev.orbit.cosmetic.client.compat.Compat.screen() == this) {
			// Over (it went, or was called off elsewhere).
			super.onClose();
		}
	}

	@Override
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int[] panel = panel();
		Theme.panel(graphics, panel[0], panel[1], panel[2], panel[3]);
		graphics.fill(panel[0], panel[1], panel[0] + 2, panel[1] + panel[3], Theme.GREEN);
		graphics.text(font, title, panel[0] + 10, panel[1] + 9, Theme.TEXT, false);
		PartyVote.Vote vote = PartyVote.mine();
		if (vote != null) {
			graphics.text(font, Theme.clip(font, Component.literal(vote.world), panel[2] - 20), panel[0] + 10, panel[1] + 25, Theme.GOLD, false);
			Component left = Component.translatable("orbit_cosmetic.vote.remaining", vote.secondsLeft());
			graphics.text(font, left, panel[0] + panel[2] - 10 - font.width(left), panel[1] + 9, Theme.MUTED, false);
			Component count = Component.translatable("orbit_cosmetic.vote.join", vote.joined.size(), PartyVote.total(vote));
			graphics.text(font, count, panel[0] + 10, panel[1] + 41, Theme.GREEN, false);
			// Time left, as a bar.
			float fraction = vote.secondsLeft() / (float) PartyVote.SECONDS;
			graphics.fill(panel[0] + 10, panel[1] + 53, panel[0] + panel[2] - 10, panel[1] + 55, Theme.PANEL_2);
			graphics.fill(panel[0] + 10, panel[1] + 53, panel[0] + 10 + Math.round((panel[2] - 20) * Math.min(1, fraction)), panel[1] + 55, Theme.GREEN);
			int y = panel[1] + 62;
			for (Social.Person member : members()) {
				SocialPopups.face(graphics, member.uuid(), member.name(), panel[0] + 10, y, 10);
				graphics.text(font, member.name(), panel[0] + 24, y + 1, Theme.TEXT, false);
				boolean joined = vote.joined.contains(member.uuid());
				boolean out = vote.out.contains(member.uuid());
				Component state = Component.translatable(joined ? "orbit_cosmetic.vote.state.join" : out ? "orbit_cosmetic.vote.state.out" : "orbit_cosmetic.vote.state.waiting");
				graphics.text(font, state, panel[0] + panel[2] - 10 - font.width(state), y + 1, joined ? Theme.GREEN : out ? Theme.FAINT : Theme.MUTED, false);
				y += 14;
			}
		}
		drawWidgets(graphics, mouseX, mouseY, partialTick);
	}
}
