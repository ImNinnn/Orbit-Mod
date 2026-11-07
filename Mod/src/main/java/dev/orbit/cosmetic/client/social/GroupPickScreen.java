package dev.orbit.cosmetic.client.social;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Picks friends for a group chat: a new group (with an optional name; the player leads it), or more members for a
 * group the player leads. A group has at most 10 members.
 */
final class GroupPickScreen extends OrbitScreen {
	private static final int WIDTH = 240;
	private static final int ROW = 16;

	/** The group to add to, or null to make a new one. */
	private final Social.@Nullable Group group;
	private final Set<UUID> picked = new LinkedHashSet<>();
	private final List<Hotspot> rows = new ArrayList<>();
	private List<Social.Person> choices = List.of();
	private @Nullable EditBox name;
	private String typedName = "";
	private int scroll;

	GroupPickScreen(@Nullable Screen parent, Social.@Nullable Group group) {
		super(Component.translatable(group == null ? "orbit_cosmetic.social.group.new" : "orbit_cosmetic.social.group.add"), parent);
		this.group = group;
	}

	@Override
	protected boolean isSmallWindow() {
		return true;
	}

	private int room() {
		return Social.Group.MAX_MEMBERS - (group == null ? 1 : group.members().size());
	}

	private int visibleRows() {
		return Math.max(3, Math.min(10, (height - 140) / ROW));
	}

	private int panelHeight() {
		return 64 + (group == null ? 26 : 0) + visibleRows() * ROW;
	}

	private int top() {
		return Math.max(6, (height - panelHeight()) / 2);
	}

	@Override
	protected void init() {
		Social social = OrbitClient.get().social();
		Set<UUID> members = new java.util.HashSet<>();
		if (group != null) {
			group.members().forEach(member -> members.add(member.uuid()));
		}
		choices = social.friends().stream().filter(friend -> !members.contains(friend.uuid())).toList();
		int x = width / 2 - WIDTH / 2 + 8;
		int y = top() + 24;
		if (group == null) {
			name = new EditBox(font, x + 4, y + 4, WIDTH - 24, 10, Component.translatable("orbit_cosmetic.social.group.name"));
			name.setBordered(false);
			name.setMaxLength(48);
			name.setTextColor(Theme.TEXT);
			name.setHint(Component.translatable("orbit_cosmetic.social.group.name.hint").withStyle(ChatFormatting.DARK_GRAY));
			name.setValue(typedName);
			name.setResponder(text -> typedName = text);
			addRenderableWidget(name);
			y += 26;
		}
		rows.clear();
		scroll = Math.clamp(scroll, 0, Math.max(0, choices.size() - visibleRows()));
		for (int i = 0; i < visibleRows() && scroll + i < choices.size(); i++) {
			Social.Person friend = choices.get(scroll + i);
			Hotspot row = addRenderableWidget(new Hotspot(Component.literal(friend.name()), h -> toggle(friend.uuid())));
			row.place(x, y + i * ROW, WIDTH - 16, ROW - 1);
			rows.add(row);
		}
		int by = top() + panelHeight() - 26;
		int half = (WIDTH - 16 - 4) / 2;
		addRenderableWidget(new FlatButton(x, by, half, 18, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
		FlatButton done = addRenderableWidget(new FlatButton(x + half + 4, by, half, 18,
			Component.translatable(group == null ? "orbit_cosmetic.social.group.create" : "orbit_cosmetic.social.group.add_picked"), "plus", FlatButton.Style.PRIMARY, b -> finish()));
		done.active = !picked.isEmpty();
	}

	private void toggle(UUID friend) {
		if (!picked.remove(friend) && picked.size() < room()) {
			picked.add(friend);
		}
		rebuildWidgets();
	}

	private void finish() {
		Social social = OrbitClient.get().social();
		if (picked.isEmpty()) {
			return;
		}
		if (group == null) {
			social.createGroup(List.copyOf(picked), typedName.strip(), id -> {
				if (parent instanceof SocialScreen screen) {
					Compat.setScreen(screen);
					screen.showChat(id);
				} else {
					Compat.setScreen(SocialScreen.chat(parent, id));
				}
			});
			return;
		}
		picked.forEach(friend -> social.addGroupMember(group.id(), friend));
		onClose();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, choices.size() - visibleRows()));
		rebuildWidgets();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && name != null && name.isFocused()) {
			finish();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = width / 2 - WIDTH / 2;
		int y = top();
		Theme.panel(graphics, x, y, WIDTH, panelHeight());
		graphics.text(font, title, x + 8, y + 8, Theme.TEXT, false);
		Component count = Component.translatable("orbit_cosmetic.social.group.count", picked.size(), room()).withStyle(ChatFormatting.GRAY);
		graphics.text(font, count, x + WIDTH - 8 - font.width(count), y + 8, Theme.TEXT, false);
		if (name != null) {
			graphics.fill(x + 8, y + 24, x + WIDTH - 8, y + 42, Theme.PANEL_2);
			graphics.outline(x + 8, y + 24, WIDTH - 16, 18, name.isFocused() ? Theme.BORDER_LIGHT : Theme.BORDER);
		}
		Social social = OrbitClient.get().social();
		if (choices.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.social.group.no_friends").withStyle(ChatFormatting.GRAY), x + WIDTH / 2,
				y + (name != null ? 56 : 30), Theme.TEXT);
		}
		for (int i = 0; i < rows.size(); i++) {
			Hotspot row = rows.get(i);
			Social.Person friend = choices.get(scroll + i);
			boolean on = picked.contains(friend.uuid());
			if (row.isHoveredOrFocused() || on) {
				graphics.fill(row.getX(), row.getY(), row.getRight(), row.getBottom(), on ? Theme.PANEL_HOVER : Theme.PANEL_2);
			}
			SocialPopups.face(graphics, friend.uuid(), friend.name(), row.getX() + 3, row.getY() + 2, 11);
			graphics.text(font, Theme.clip(font, Component.literal(friend.name()), WIDTH - 60), row.getX() + 19, row.getY() + 4, Theme.TEXT, false);
			int bx = row.getRight() - 14;
			graphics.fill(bx, row.getY() + 2, bx + 11, row.getY() + 13, on ? Theme.TEXT : Theme.BORDER_LIGHT);
			graphics.fill(bx + 1, row.getY() + 3, bx + 10, row.getY() + 12, Theme.PANEL_2);
			if (on) {
				Theme.icon(graphics, "check_small", bx + 1, row.getY() + 3, Theme.GREEN);
			}
			row.drawFocus(graphics);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
