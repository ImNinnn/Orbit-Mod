package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.client.gui.ScreenshotViewScreen;
import dev.orbit.cosmetic.client.gui.StatsScreen;
import dev.orbit.cosmetic.client.gui.TermsScreen;
import dev.orbit.cosmetic.client.gui.preview.PreviewBatch;
import dev.orbit.cosmetic.client.gui.preview.PreviewModel;
import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.Hotspot;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.library.CosmeticSettingsFile;
import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.client.screenshot.ScreenshotLibrary;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.ParticleSettings;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Friends and messages. The Chat tab lists conversations on the left and shows the open one on the right: text,
 * pictures, shared cosmetics (shown on a player, with Add to wardrobe) and server cards (icon, description, Join);
 * the Friends tab has the friend list, friend requests and blocked players side by side.
 *
 * <p>Everything works from the keyboard: Tab and the arrow keys move between buttons, rows and cards, and Enter
 * presses them. Typing goes into the message, which Enter sends; Alt+Up and Alt+Down open the conversation above or
 * below, Page Up and Page Down scroll it, and Ctrl+Tab switches between Chat and Friends.
 */
public final class SocialScreen extends OrbitScreen {
	private static final int HEADER = 24;
	private static final int TABS = 20;
	private static final int LIST = 170;
	private static final int PAD = 6;
	private static final int ROW = 30;
	/** Message bubbles: one's own in Orbit red, the friend's grey. */
	private static final int MINE = 0xFFB81B1B;
	private static final int THEIRS = 0xFF34343C;
	private static final Identifier NO_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_server.png");
	/** Cosmetics shown in the open conversation, kept loaded (see {@link #assetsInUse}). */
	private static final Set<AssetId> SHOWN = new HashSet<>();

	private enum Tab { CHAT, FRIENDS }

	private static Tab tab = Tab.CHAT;
	private static @Nullable UUID open;

	private final ServerLinks links = new ServerLinks();
	private final Map<String, Path> pictures = new HashMap<>();
	private final Map<String, Long> pictureRetry = new HashMap<>();
	private final Map<String, PreviewModel> previews = new HashMap<>();
	private final Set<String> requested = new HashSet<>();
	private final Map<String, Long> retryAt = new HashMap<>();
	/** Hand-drawn things to press (rows, cards' buttons), by a key that stays the same from frame to frame. */
	private final Map<String, Hotspot> spots = new HashMap<>();
	private final Set<String> drawn = new HashSet<>();
	private @Nullable String focusAgain;
	private List<PreviewBatch.Entry> chatPreviews = new ArrayList<>();
	private @Nullable MultiLineEditBox input;
	/** The message being changed in the message box, or null. */
	private Social.@Nullable Entry editing;
	/** The mouse this frame (for what shows while it is on a message). */
	private int mouseX;
	private int mouseY;
	/** Particles of shared emotes and cosmetics, read from their settings once, by message. */
	private final Map<String, List<ParticleSettings>> sharedParticles = new HashMap<>();
	private double chatScroll;
	private int listScroll;
	private @Nullable UUID scrolledTo;
	private String layout = "";

	public SocialScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.social.title"), parent);
	}

	/** Opens the conversation with a friend. */
	/** Opens the Friends tab (friend requests). */
	public static SocialScreen friends(@Nullable Screen parent) {
		tab = Tab.FRIENDS;
		return new SocialScreen(parent);
	}

	public static SocialScreen chat(@Nullable Screen parent, UUID friend) {
		tab = Tab.CHAT;
		open = friend;
		return new SocialScreen(parent);
	}

	/** Keeps the files of the cosmetics shown in a conversation loaded. */
	public static void assetsInUse(Set<AssetId> inUse) {
		inUse.addAll(SHOWN);
	}

	private static Social social() {
		return OrbitClient.get().social();
	}

	private int px() {
		return panel()[0];
	}

	private int py() {
		return panel()[1];
	}

	private int pw() {
		return panel()[2];
	}

	private int ph() {
		return panel()[3];
	}

	/** What the widgets depend on: they are made again when it changes. */
	private String layoutKey() {
		return tab + "/" + open + "/" + (open != null && social().friend(open) != null) + "/" + social().isLoaded() + "/" + orbit().config().hasAcceptedTerms();
	}

	@Override
	protected void init() {
		spots.clear();
		layout = layoutKey();
		backButton(px() - 22, py());
		if (!orbit().config().hasAcceptedTerms()) {
			addRenderableWidget(new FlatButton(px() + pw() / 2 - 80, py() + HEADER + 60, 160, 20, Component.translatable("orbit_cosmetic.terms.review"), "forward",
				FlatButton.Style.PRIMARY, b -> Compat.setScreen(new TermsScreen(this))));
			return;
		}
		int right = px() + pw() - PAD;
		addRenderableWidget(FlatButton.icon(right - 18, py() + 3, 18, "person_add", Component.translatable("orbit_cosmetic.social.add_friend"), FlatButton.Style.PRIMARY,
			b -> Compat.setScreen(new AddFriendScreen(this))));
		if (Social.minecraftFriends() != null) {
			addRenderableWidget(FlatButton.icon(right - 40, py() + 3, 18, "social", Component.translatable("orbit_cosmetic.social.import"), FlatButton.Style.SECONDARY,
				b -> social().importMinecraftFriends()));
		}
		int tabY = py() + HEADER + 2;
		Component chatLabel = Component.translatable("orbit_cosmetic.social.chat");
		Component friendsLabel = Component.translatable("orbit_cosmetic.social.friends");
		int chatW = font.width(chatLabel) + 16;
		FlatButton chatTab = addRenderableWidget(new FlatButton(px() + PAD, tabY, chatW, 16, chatLabel, null, FlatButton.Style.GHOST, b -> switchTab(Tab.CHAT)));
		chatTab.setSelected(tab == Tab.CHAT);
		FlatButton friendsTab = addRenderableWidget(new FlatButton(px() + PAD + chatW + 4, tabY, font.width(friendsLabel) + 16, 16, friendsLabel, null, FlatButton.Style.GHOST,
			b -> switchTab(Tab.FRIENDS)));
		friendsTab.setSelected(tab == Tab.FRIENDS);

		input = null;
		// Orbit's messages to every player cannot be answered.
		if (tab == Tab.CHAT && open != null && !Social.isOrbit(open) && social().friend(open) != null) {
			int x = px() + LIST + PAD * 2;
			int w = pw() - LIST - PAD * 3;
			int y = py() + ph() - PAD - 18;
			// Between the picture and send buttons; it grows upwards as the message gets longer.
			input = MultiLineEditBox.builder().setX(x + 22).setY(y).setTextColor(Theme.TEXT).setShowBackground(false).setShowDecorations(false)
				.setPlaceholder(Component.translatable("orbit_cosmetic.social.message_to", social().friend(open).name()).withStyle(ChatFormatting.DARK_GRAY))
				.build(font, w - 44, 18, Component.translatable("orbit_cosmetic.social.message"));
			input.setCharacterLimit(1000);
			addRenderableWidget(input);
			setInitialFocus(input);
			addRenderableWidget(FlatButton.icon(x, y, 18, "image", Component.translatable("orbit_cosmetic.social.send_picture"), FlatButton.Style.SECONDARY,
				b -> Compat.setScreen(new PickScreenshotScreen(this, shot -> ShareScreen.screenshotTo(this, shot, open)))));
			addRenderableWidget(FlatButton.icon(x + w - 18, y, 18, "send", Component.translatable("orbit_cosmetic.social.send"), FlatButton.Style.PRIMARY, b -> sendText()));
			int headerY = py() + HEADER + TABS + 6;
			UUID friend = open;
			String name = social().friend(open).name();
			addRenderableWidget(FlatButton.icon(px() + pw() - PAD - 18, headerY, 18, "block", Component.translatable("orbit_cosmetic.social.block"), FlatButton.Style.DANGER,
				b -> confirm("block", name, () -> {
					social().block(friend);
					open = null;
				})));
			addRenderableWidget(FlatButton.icon(px() + pw() - PAD - 40, headerY, 18, "trash", Component.translatable("orbit_cosmetic.social.remove"), FlatButton.Style.SECONDARY,
				b -> confirm("remove", name, () -> {
					social().remove(friend);
					open = null;
				})));
			addRenderableWidget(FlatButton.icon(px() + pw() - PAD - 62, headerY, 18, "account", Component.translatable("orbit_cosmetic.stats.see"), FlatButton.Style.SECONDARY,
				b -> Compat.setScreen(new StatsScreen(this, friend, name))));
			// End-to-end encryption of this conversation's new messages (off unless turned on).
			boolean encrypted = social().isEncrypted(friend);
			FlatButton lock = addRenderableWidget(FlatButton.icon(px() + pw() - PAD - 84, headerY, 18, encrypted ? "lock" : "lock_open",
				Component.translatable(encrypted ? "orbit_cosmetic.social.encryption.on" : "orbit_cosmetic.social.encryption.off"),
				encrypted ? FlatButton.Style.PRIMARY : FlatButton.Style.SECONDARY, b -> {
					social().setEncrypted(friend, !social().isEncrypted(friend));
					rebuildWidgets();
				}));
			lock.setTooltip(Tooltip.create(Component.translatable(encrypted ? "orbit_cosmetic.social.encryption.on" : "orbit_cosmetic.social.encryption.off")
				.append("\n").append(Component.translatable("orbit_cosmetic.social.encryption.tooltip").withStyle(ChatFormatting.GRAY))));
			if (FloatingChats.available()) {
				FlatButton popOut = addRenderableWidget(FlatButton.icon(px() + pw() - PAD - 106, headerY, 18, "popout", Component.translatable("orbit_cosmetic.social.pop_out"),
					FlatButton.Style.SECONDARY, b -> {
						FloatingChats.open(friend, name);
						onClose();
					}));
				popOut.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.social.pop_out.tooltip")));
			}
		}
		if (open != null) {
			social().view(open);
		}
	}

	private void switchTab(Tab next) {
		tab = next;
		rebuildWidgets();
	}

	private void openChat(UUID friend) {
		tab = Tab.CHAT;
		open = friend;
		chatScroll = 0;
		rebuildWidgets();
	}

	private void confirm(String what, String name, Runnable action) {
		Compat.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				action.run();
			}
			Compat.setScreen(this);
		}, Component.translatable("orbit_cosmetic.social." + what + ".title", name), Component.translatable("orbit_cosmetic.social." + what + ".text", name)));
	}

	private void sendText() {
		if (input == null || open == null || input.getValue().isBlank()) {
			return;
		}
		if (editing != null) {
			String text = input.getValue().strip();
			if (!text.equals(editing.content().get("text").getAsString())) {
				social().edit(open, editing.id(), text);
			}
			editing = null;
			input.setValue("");
			return;
		}
		JsonObject content = new JsonObject();
		content.addProperty("text", input.getValue().strip());
		input.setValue("");
		chatScroll = 0;
		social().send(List.of(open), content, null, () -> chatScroll = 0);
	}

	/** The conversation above or below the open one ({@code step} -1 or 1). */
	private void stepConversation(int step) {
		List<Social.Person> conversations = social().conversations();
		if (conversations.isEmpty()) {
			return;
		}
		int index = -1;
		for (int i = 0; i < conversations.size(); i++) {
			if (conversations.get(i).uuid().equals(open)) {
				index = i;
			}
		}
		int next = index < 0 ? 0 : Math.clamp(index + step, 0, conversations.size() - 1);
		if (next != index) {
			openChat(conversations.get(next).uuid());
		}
	}

	private static boolean isEnter(KeyEvent event) {
		return event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (editing != null && event.key() == InputConstants.KEY_ESCAPE) {
			editing = null;
			if (input != null) {
				input.setValue("");
			}
			return true;
		}
		// Enter sends; Shift+Enter starts a new line.
		if (input != null && isEnter(event) && !event.hasShiftDown() && (input.isFocused() || getFocused() == null)) {
			sendText();
			return true;
		}
		if (event.key() == InputConstants.KEY_TAB && event.hasControlDown() && orbit().config().hasAcceptedTerms()) {
			switchTab(tab == Tab.CHAT ? Tab.FRIENDS : Tab.CHAT);
			return true;
		}
		if (tab == Tab.CHAT && event.hasAltDown() && (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)) {
			stepConversation(event.key() == InputConstants.KEY_UP ? -1 : 1);
			return true;
		}
		if (tab == Tab.CHAT && (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN)) {
			int page = Math.max(40, ph() - HEADER - TABS - 80);
			chatScroll = Math.max(0, chatScroll + (event.key() == InputConstants.KEY_PAGEUP ? page : -page));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// Typing anywhere writes the message (a space still presses what is focused).
		if (input != null && !input.isFocused() && !(getFocused() instanceof EditBox) && event.codepoint() != ' ' && event.isAllowedChatCharacter()) {
			setFocused(input);
			return input.charTyped(event);
		}
		return super.charTyped(event);
	}

	/** The message box's height for its lines (one line: as tall as the buttons; up to five, then it scrolls). */
	private int inputHeight() {
		return input == null ? 18 : Math.clamp(input.getInnerHeight() + 8, 18, 8 + 9 * 5);
	}

	private void layoutInput() {
		if (input != null) {
			int h = inputHeight();
			if (h != input.getHeight()) {
				input.setHeight(h);
				input.setY(py() + ph() - PAD - h);
				// Scrolled while it was smaller: everything shows again (or as much as fits).
				input.refreshScrollAmount();
				if (input.maxScrollAmount() == 0) {
					input.setScrollAmount(0);
				}
			}
		}
	}

	@Override
	public void tick() {
		links.tick();
		if (!layoutKey().equals(layout)) {
			// Made again: the typed message and what the keyboard was on stay.
			String typed = input == null ? "" : input.getValue();
			GuiEventListener focused = getFocused();
			String keep = null;
			for (Map.Entry<String, Hotspot> spot : spots.entrySet()) {
				if (spot.getValue() == focused) {
					keep = spot.getKey();
				}
			}
			rebuildWidgets();
			if (input != null) {
				input.setValue(typed);
			}
			focusAgain = keep;
		}
	}

	@Override
	public void onClose() {
		social().view(null);
		links.close();
		SHOWN.clear();
		super.onClose();
	}

	@Override
	public void removed() {
		social().view(null);
	}

	/**
	 * The hand-drawn thing to press at a place this frame. Made the first time (so the keyboard can reach it), it
	 * keeps its key from frame to frame, so the keyboard stays on it while the screen changes.
	 */
	private Hotspot spot(String key, Component narration, int x, int y, int w, int h, int clipTop, int clipBottom, Consumer<Hotspot> action) {
		Hotspot spot = spots.get(key);
		if (spot == null) {
			spot = addRenderableWidget(new Hotspot(narration, action));
			spots.put(key, spot);
		}
		spot.place(x, y, w, h, clipTop, clipBottom);
		spot.visible = true;
		drawn.add(key);
		if (key.equals(focusAgain)) {
			setFocused(spot);
			focusAgain = null;
		}
		return spot;
	}

	private Hotspot spot(String key, Component narration, int x, int y, int w, int h, Consumer<Hotspot> action) {
		return spot(key, narration, x, y, w, h, Integer.MIN_VALUE, Integer.MAX_VALUE, action);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		drawn.clear();
		Theme.panel(graphics, px(), py(), pw(), ph());
		graphics.fill(px() + 1, py() + 1, px() + pw() - 1, py() + HEADER, Theme.PANEL_2);
		graphics.text(font, title, px() + PAD + 2, py() + 8, Theme.TEXT, false);
		Social social = social();
		int top = py() + HEADER + TABS + 4;
		if (!orbit().config().hasAcceptedTerms()) {
			for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.terms.needed").withStyle(ChatFormatting.GRAY), pw() - 40)) {
				graphics.centeredText(font, line, px() + pw() / 2, top + 6, Theme.TEXT);
				top += 10;
			}
		} else if (!social.isLoaded()) {
			Component message = social.problem() != null ? Component.literal(social.problem()) : Component.translatable("orbit_cosmetic.social.loading");
			graphics.centeredText(font, message.copy().withStyle(ChatFormatting.GRAY), px() + pw() / 2, top + 40, Theme.TEXT);
		} else if (tab == Tab.CHAT) {
			drawConversations(graphics, top);
			drawChat(graphics, top, partialTick);
		} else {
			drawFriends(graphics, top);
		}
		// What was not drawn this frame cannot be pressed.
		for (Map.Entry<String, Hotspot> spot : spots.entrySet()) {
			if (!drawn.contains(spot.getKey())) {
				spot.getValue().visible = false;
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void face(GuiGraphicsExtractor graphics, UUID uuid, String name, int x, int y, int size) {
		SocialPopups.face(graphics, uuid, name, x, y, size);
	}

	private static String when(double at) {
		LocalDate day = Instant.ofEpochSecond((long) at).atZone(ZoneId.systemDefault()).toLocalDate();
		if (day.equals(LocalDate.now())) {
			return Instant.ofEpochSecond((long) at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
		}
		return day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
	}

	/** "Online", or "Last online 3 hours ago". */
	static Component lastOnline(Social.Person person) {
		if (person.online()) {
			return Component.translatable("orbit_cosmetic.social.online").withStyle(ChatFormatting.GREEN);
		}
		if (person.lastSeen() <= 0) {
			return Component.translatable("orbit_cosmetic.social.offline").withStyle(ChatFormatting.GRAY);
		}
		long minutes = Math.max(1, (long) ((System.currentTimeMillis() / 1000.0 - person.lastSeen()) / 60));
		String ago = minutes < 60 ? Component.translatable("orbit_cosmetic.social.minutes", minutes).getString()
			: minutes < 60 * 24 ? Component.translatable("orbit_cosmetic.social.hours", minutes / 60).getString()
			: Component.translatable("orbit_cosmetic.social.days", minutes / 60 / 24).getString();
		return Component.translatable("orbit_cosmetic.social.last_online", ago).withStyle(ChatFormatting.GRAY);
	}

	private void drawConversations(GuiGraphicsExtractor graphics, int top) {
		int x = px() + PAD;
		int bottom = py() + ph() - PAD;
		graphics.fill(px() + LIST + PAD, top, px() + LIST + PAD + 1, bottom, Theme.BORDER);
		List<Social.Person> conversations = social().conversations();
		if (conversations.isEmpty()) {
			for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.social.no_friends").withStyle(ChatFormatting.GRAY), LIST - 8)) {
				graphics.text(font, line, x, top + 8, Theme.TEXT, false);
				top += 10;
			}
			return;
		}
		// Scrolled so a conversation just opened is in sight.
		int fits = Math.max(1, (bottom - top) / ROW);
		for (int i = 0; open != null && !open.equals(scrolledTo) && i < conversations.size(); i++) {
			if (conversations.get(i).uuid().equals(open)) {
				listScroll = Math.clamp(listScroll, Math.max(0, i - fits + 1), i);
				scrolledTo = open;
			}
		}
		listScroll = Math.clamp(listScroll, 0, Math.max(0, conversations.size() - fits));
		int y = top;
		for (int i = listScroll; i < conversations.size() && y + ROW <= bottom; i++) {
			Social.Person person = conversations.get(i);
			UUID uuid = person.uuid();
			Hotspot row = spot("conversation:" + uuid, Component.literal(person.name()), x, y, LIST - 2, ROW - 2, h -> openChat(uuid));
			boolean selected = uuid.equals(open);
			if (selected || row.isHovered()) {
				graphics.fill(x, y, x + LIST - 2, y + ROW - 2, selected ? Theme.PANEL_HOVER : Theme.PANEL_2);
			}
			face(graphics, uuid, person.name(), x + 3, y + 4, 20);
			if (person.online()) {
				graphics.fill(x + 19, y + 20, x + 24, y + 25, 0xFF3CB043);
			}
			graphics.text(font, Theme.clip(font, Component.literal(person.name()), LIST - 70), x + 28, y + 4, Theme.TEXT, false);
			List<Social.Entry> chat = social().chat(uuid);
			Component preview = chat.isEmpty() ? Component.translatable("orbit_cosmetic.social.say_hi")
				: chat.getLast().deleted() ? Component.translatable("orbit_cosmetic.social.deleted")
				: Component.literal(chat.getLast().content() == null ? "…" : Social.preview(chat.getLast().content()));
			int unread = social().unread(uuid);
			int badge = unread > 0 ? font.width(String.valueOf(Math.min(99, unread))) + 10 : 0;
			graphics.text(font, Theme.clip(font, preview.copy().withStyle(ChatFormatting.GRAY), LIST - 34 - badge), x + 28, y + 15, Theme.TEXT, false);
			if (!chat.isEmpty()) {
				String date = when(chat.getLast().at());
				graphics.text(font, date, x + LIST - 6 - font.width(date), y + 4, Theme.FAINT, false);
			}
			if (unread > 0) {
				String count = String.valueOf(Math.min(99, unread));
				int bx = x + LIST - 8 - font.width(count) - 4;
				Theme.round(graphics, bx, y + 13, font.width(count) + 8, 12, Theme.RED);
				graphics.text(font, count, bx + 4, y + 15, Theme.TEXT, false);
			}
			row.drawFocus(graphics);
			y += ROW;
		}
	}

	private void drawChat(GuiGraphicsExtractor graphics, int top, float partialTick) {
		int x = px() + LIST + PAD * 2;
		int w = pw() - LIST - PAD * 3;
		Social.Person friend = open == null ? null : social().friend(open);
		SHOWN.clear();
		if (friend == null) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.social.pick").withStyle(ChatFormatting.GRAY), x + w / 2, top + 40, Theme.TEXT);
			return;
		}
		face(graphics, friend.uuid(), friend.name(), x, top + 2, 16);
		graphics.text(font, friend.name(), x + 22, top + 2, Theme.TEXT, false);
		graphics.text(font, Social.isOrbit(friend.uuid()) ? Component.translatable("orbit_cosmetic.social.orbit_detail").withStyle(ChatFormatting.GRAY) : lastOnline(friend),
			x + 22, top + 12, Theme.TEXT, false);
		layoutInput();
		int inputH = inputHeight();
		int areaTop = top + 26;
		int areaBottom = input == null ? py() + ph() - PAD : py() + ph() - PAD - inputH - 6;
		graphics.fill(x, areaTop - 3, x + w, areaTop - 2, Theme.BORDER);
		if (input != null) {
			Theme.panel(graphics, input.getX(), input.getY(), input.getWidth(), inputH, Theme.PANEL_2);
			if (editing != null) {
				Component label = Component.translatable("orbit_cosmetic.social.editing").withStyle(ChatFormatting.ITALIC);
				graphics.fill(input.getX(), input.getY() - 12, input.getX() + font.width(label) + 8, input.getY(), Theme.PANEL_2);
				graphics.text(font, label, input.getX() + 4, input.getY() - 10, Theme.MUTED, false);
			}
			if (input.isFocused()) {
				graphics.outline(input.getX(), input.getY(), input.getWidth(), inputH, Theme.BORDER_LIGHT);
			}
		}

		UUID me = social().me();
		List<Social.Entry> chat = social().chat(friend.uuid());
		chatPreviews = new ArrayList<>();
		// Laid out from the newest message (at the bottom) up.
		graphics.enableScissor(x, areaTop, x + w, areaBottom);
		int y = areaBottom - 4 + (int) chatScroll;
		for (int i = chat.size() - 1; i >= 0 && y > areaTop - 400; i--) {
			Social.Entry entry = chat.get(i);
			boolean mine = me != null && entry.mine(me);
			y -= entry(graphics, entry, mine, x, 0, w, areaTop, areaBottom, partialTick, false);
			entry(graphics, entry, mine, x, y, w, areaTop, areaBottom, partialTick, true);
			LocalDate day = Instant.ofEpochSecond((long) entry.at()).atZone(ZoneId.systemDefault()).toLocalDate();
			LocalDate before = i > 0 ? Instant.ofEpochSecond((long) chat.get(i - 1).at()).atZone(ZoneId.systemDefault()).toLocalDate() : null;
			if (!day.equals(before)) {
				y -= 16;
				Theme.separator(graphics, font, x + 4, y + 8, w - 8, Component.literal(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))));
			}
		}
		graphics.disableScissor();
		// Every shared cosmetic of the conversation in one picture, clipped to the messages.
		PreviewBatch.grid(graphics, x, areaTop, x + w, areaBottom, chatPreviews);
		if (chat.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.social.empty_chat", friend.name()).withStyle(ChatFormatting.GRAY), x + w / 2, areaBottom - 30,
				Theme.TEXT);
		}
	}

	private int bubbleWidth(int w) {
		return Math.min(320, w * 3 / 4);
	}

	/**
	 * Lays out (and with {@code draw}, draws) one message: its time, its text in a bubble, then its picture, shared
	 * cosmetic or server cards. Returns its height.
	 */
	private int entry(GuiGraphicsExtractor graphics, Social.Entry entry, boolean mine, int x, int y, int w, int areaTop, int areaBottom, float partialTick, boolean draw) {
		int bubble = bubbleWidth(w);
		draw &= y < areaBottom && y + 400 > areaTop;
		if (draw) {
			String time = Instant.ofEpochSecond((long) entry.at()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
			graphics.text(font, time, mine ? x + w - 6 - font.width(time) : x + 6, y + 1, Theme.FAINT, false);
		}
		int cy = y + 12;
		JsonObject content = entry.content();
		if (draw && mine && !entry.deleted() && open != null) {
			messageActions(graphics, entry, x, y, w, entry(graphics, entry, true, x, y, w, areaTop, areaBottom, partialTick, false), areaTop, areaBottom);
		}
		if (content == null) {
			// Deleted by its sender (a grey bubble, whoever sent it), or not readable on this computer.
			Component unreadable = Component.translatable(entry.deleted() ? "orbit_cosmetic.social.deleted" : "orbit_cosmetic.social.unreadable")
				.withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
			int bw = Math.min(bubble, font.width(unreadable) + 16);
			if (draw) {
				int bx = mine ? x + w - 6 - bw : x + 6;
				Theme.bubble(graphics, bx, cy, bw, 20, THEIRS, 2, mine);
				secured(graphics, entry, bx, cy, bw, 20);
				graphics.text(font, unreadable, bx + 8, cy + 6, Theme.TEXT, false);
			}
			return 12 + 20 + 6;
		}
		String text = content.has("text") ? content.get("text").getAsString() : "";
		if (!text.isBlank()) {
			List<FormattedCharSequence> lines = font.split(Component.literal(text), bubble - 16);
			int bw = 16 + lines.stream().mapToInt(font::width).max().orElse(0);
			int bh = lines.size() * 10 + 11;
			if (draw) {
				int bx = mine ? x + w - 6 - bw : x + 6;
				Theme.bubble(graphics, bx, cy, bw, bh, mine ? MINE : THEIRS, 2, mine);
				secured(graphics, entry, bx, cy, bw, bh);
				if (entry.edited()) {
					// Beside the bubble, on the side away from its sender.
					Component edited = Component.translatable("orbit_cosmetic.social.edited").withStyle(ChatFormatting.ITALIC);
					graphics.text(font, edited, mine ? bx - 5 - font.width(edited) : bx + bw + 5, cy + bh - 9, Theme.FAINT, false);
				}
				int ly = cy + 6;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, bx + 8, ly, Theme.TEXT, false);
					ly += 10;
				}
			}
			cy += bh + 3;
			for (String address : ServerLinks.find(text)) {
				if (draw) {
					drawServer(graphics, entry.id() + ":" + address, address, null, null, null, mine, x, cy, w, areaTop, areaBottom);
				}
				cy += 48;
			}
		}
		if (content.get("image") instanceof JsonObject image) {
			int iw = 176;
			int ih = 99;
			if (draw) {
				drawPicture(graphics, entry, image, mine ? x + w - 6 - iw : x + 6, cy, iw, ih, areaTop, areaBottom);
				secured(graphics, entry, mine ? x + w - 6 - iw : x + 6, cy, iw, ih);
			}
			cy += ih + 4;
		}
		if (content.get("cosmetic") instanceof JsonObject cosmetic) {
			if (draw) {
				drawCosmetic(graphics, entry, cosmetic, mine, x, cy, w, areaTop, areaBottom, partialTick);
				int cw = Math.min(bubbleWidth(w), 240);
				secured(graphics, entry, mine ? x + w - 6 - cw : x + 6, cy, cw, 64);
			}
			cy += 68;
		}
		if (content.get("invite") instanceof JsonObject invite) {
			if (draw) {
				drawServer(graphics, entry.id() + ":invite", invite.get("address").getAsString(), text(invite, "world"), text(invite, "motd"), text(invite, "icon"), mine, x, cy, w,
					areaTop, areaBottom);
				int cw = Math.min(bubbleWidth(w), 280);
				secured(graphics, entry, mine ? x + w - 6 - cw : x + 6, cy, cw, 44);
			}
			cy += 48;
		}
		return cy - y + 2;
	}

	/** End-to-end encrypted messages have a dashed outline. */
	private static void secured(GuiGraphicsExtractor graphics, Social.Entry entry, int x, int y, int w, int h) {
		if (entry.encrypted()) {
			Theme.dashed(graphics, x - 2, y - 2, w + 4, h + 4, 0xFFB0B0BA);
		}
	}

	/**
	 * Edit and Delete beside one of the player's own messages: shown while the mouse is on the message (or the
	 * keyboard on one of them).
	 */
	private void messageActions(GuiGraphicsExtractor graphics, Social.Entry entry, int x, int y, int w, int height, int areaTop, int areaBottom) {
		UUID friend = open;
		boolean text = entry.content() != null && entry.content().has("text") && !entry.content().get("text").getAsString().isBlank();
		int bx = x + 6;
		int by = y + 12;
		Hotspot edit = text ? spot("edit:" + entry.id(), Component.translatable("orbit_cosmetic.social.edit"), bx, by, 14, 14, areaTop, areaBottom, h -> startEditing(entry)) : null;
		Hotspot delete = spot("delete:" + entry.id(), Component.translatable("orbit_cosmetic.social.delete"), bx + (text ? 16 : 0), by, 14, 14, areaTop, areaBottom,
			h -> Compat.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					social().delete(friend, entry);
				}
				Compat.setScreen(this);
			}, Component.translatable("orbit_cosmetic.social.delete.title"), Component.translatable("orbit_cosmetic.social.delete.text"))));
		boolean shown = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + height && mouseY >= areaTop && mouseY < areaBottom
			|| delete.isFocused() || edit != null && edit.isFocused();
		for (Hotspot action : edit == null ? List.of(delete) : List.of(edit, delete)) {
			if (shown) {
				boolean hot = action.isHoveredOrFocused();
				graphics.fill(action.getX(), action.getY(), action.getX() + 14, action.getY() + 14, hot ? Theme.PANEL_HOVER : Theme.PANEL_2);
				Theme.icon(graphics, action == delete ? "trash" : "edit", action.getX() + 3, action.getY() + 3, action == delete && hot ? Theme.RED : Theme.MUTED);
				action.setTooltip(Tooltip.create(action.getMessage()));
			}
			action.visible = shown;
		}
	}

	/** Puts one of the player's messages into the message box to change it; Enter saves, Escape cancels. */
	private void startEditing(Social.Entry entry) {
		if (input == null) {
			return;
		}
		editing = entry;
		input.setValue(entry.content().get("text").getAsString());
		setFocused(input);
	}

	private static @Nullable String text(JsonObject json, String key) {
		return json.get(key) != null && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}

	private void drawPicture(GuiGraphicsExtractor graphics, Social.Entry entry, JsonObject image, int ix, int cy, int iw, int ih, int areaTop, int areaBottom) {
		String id = image.get("id").getAsString();
		Path file = pictures.get(id);
		if (file == null && !pictures.containsKey(id) && System.currentTimeMillis() >= pictureRetry.getOrDefault(id, 0L)) {
			pictures.put(id, null);
			social().picture(image, path -> pictures.put(id, path), () -> {
				// Tried again in a while.
				pictures.remove(id);
				pictureRetry.put(id, System.currentTimeMillis() + 10_000);
			});
		}
		Theme.round(graphics, ix, cy, iw, ih, Theme.PANEL_2, 2);
		ScreenshotLibrary.Thumbnail thumbnail = file == null ? null : orbit().screenshots().thumbnail(file, 400);
		if (thumbnail == null) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.screenshots.loading").withStyle(ChatFormatting.GRAY), ix + iw / 2, cy + ih / 2 - 4, Theme.TEXT);
			return;
		}
		float scale = Math.min(iw / (float) thumbnail.width(), ih / (float) thumbnail.height());
		int tw = Math.round(thumbnail.width() * scale);
		int th = Math.round(thumbnail.height() * scale);
		int tx = ix + (iw - tw) / 2;
		int ty = cy + (ih - th) / 2;
		graphics.blit(thumbnail.id(), tx, ty, tx + tw, ty + th, 0, 1, 0, 1);
		Path shown = file;
		Hotspot spot = spot("picture:" + entry.id(), Component.translatable("orbit_cosmetic.social.open_picture"), tx, ty, tw, th, areaTop, areaBottom,
			h -> Compat.setScreen(new ScreenshotViewScreen(this, orbit().screenshots().shot(shown))));
		spot.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.social.open_picture")));
		spot.drawFocus(graphics);
	}

	/** A shared cosmetic, emote or body part: shown on a player (downloaded from Orbit Cloud), with Add to wardrobe. */
	private void drawCosmetic(GuiGraphicsExtractor graphics, Social.Entry entry, JsonObject cosmetic, boolean mine, int x, int cy, int w, int areaTop, int areaBottom,
		float partialTick) {
		int cw = Math.min(bubbleWidth(w), 240);
		int cx = mine ? x + w - 6 - cw : x + 6;
		Theme.round(graphics, cx, cy, cw, 64, Theme.PANEL_2, 2);
		graphics.fill(cx + 4, cy + 4, cx + 60, cy + 60, Theme.BACKGROUND);
		String kind = cosmetic.has("kind") ? cosmetic.get("kind").getAsString() : "cosmetic";
		String name = cosmetic.get("name").getAsString();
		String sha = text(cosmetic, "sha256");
		AssetId asset = sha == null ? null : AssetId.tryParse(sha);
		if (asset != null) {
			BakedCosmetic baked = ensure(asset);
			SHOWN.add(asset);
			PreviewModel model = previews.computeIfAbsent(entry.id(), key -> new PreviewModel());
			// With the particles of its settings, as the sender made them.
			List<ParticleSettings> particles = baked == null ? List.of() : sharedParticles.computeIfAbsent(entry.id(),
				id -> text(cosmetic, "settings") == null ? List.of() : CosmeticSettingsFile.particlesIn(cosmetic.get("settings").getAsString().getBytes(StandardCharsets.UTF_8), baked.model()));
			if (kind.equals("emote")) {
				model.setWorn(List.of());
				if (baked != null) {
					model.loopEmote(asset, particles);
				}
			} else if (baked != null) {
				model.setWorn(List.of(kind.equals("bodypart")
					? new EquippedCosmetic(asset, name, List.of(), List.of(), 0, List.of(), baked.bodyParts(), dev.orbit.cosmetic.common.anim.TextureAnimation.defaults(baked.model().textures()))
					: new EquippedCosmetic(asset, name, defaults(baked), List.of(), 0, particles, 0, dev.orbit.cosmetic.common.anim.TextureAnimation.defaults(baked.model().textures()))));
			}
			float yaw = (float) (orbit().menuTime() * 8 % 360);
			chatPreviews.add(model.entryIn(cx + 6 - x, cy + 6 - areaTop, 52, 52, yaw, 0, partialTick, true));
			if (baked == null) {
				graphics.centeredText(font, Component.literal("…").withStyle(ChatFormatting.GRAY), cx + 32, cy + 50, Theme.TEXT);
			}
		}
		Theme.icon(graphics, kind.equals("emote") ? "emote" : kind.equals("bodypart") ? "player" : "shirt", cx + 66, cy + 10, Theme.GOLD);
		graphics.text(font, Theme.clip(font, Component.literal(name), cw - 84), cx + 78, cy + 10, Theme.TEXT, false);
		graphics.text(font, Component.translatable("orbit_cosmetic.social.kind." + kind).withStyle(ChatFormatting.GRAY), cx + 66, cy + 22, Theme.TEXT, false);
		if (!mine) {
			Hotspot add = spot("add:" + entry.id(), Component.translatable("orbit_cosmetic.social.add"), cx + 66, cy + 38, 64, 18, areaTop, areaBottom,
				h -> SharedCosmetics.addToWardrobe(cosmetic));
			add.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.social.add.tooltip")));
			button(graphics, add, Component.translatable("orbit_cosmetic.social.add"), 0xFFC21E1E, 0xFFE02626);
		}
	}

	/** A raised button drawn by hand, for a hotspot. */
	private void button(GuiGraphicsExtractor graphics, Hotspot spot, Component label, int fill, int hover) {
		int bx = spot.getX();
		int by = spot.getY();
		boolean hot = spot.isHoveredOrFocused();
		Theme.buttonFace(graphics, bx, by, spot.getWidth(), spot.getHeight(), true, hot, hot ? hover : fill, 0xFF000000 | fill | 0x404040);
		graphics.centeredText(font, label, bx + spot.getWidth() / 2, by + (spot.getHeight() - 8) / 2, Theme.TEXT);
	}

	/** A shared file's model, loaded from Orbit Cloud the first time (and again later if it failed). */
	private @Nullable BakedCosmetic ensure(AssetId asset) {
		BakedCosmetic baked = orbit().registry().get(asset);
		String sha = asset.hex();
		if (baked == null && !orbit().registry().isKnown(asset) && System.currentTimeMillis() >= retryAt.getOrDefault(sha, 0L) && requested.add(sha)) {
			orbit().backend().socialDownloadModel(sha, bytes -> {
				requested.remove(sha);
				orbit().remotes().acceptAsset(asset, bytes);
			}, error -> {
				requested.remove(sha);
				retryAt.put(sha, System.currentTimeMillis() + 15_000);
			});
		}
		return baked;
	}

	private static List<AnimationSettings> defaults(BakedCosmetic baked) {
		List<AnimationSettings> settings = new ArrayList<>();
		for (AnimationClip clip : baked.model().animations()) {
			settings.add(AnimationSettings.defaultsFor(clip));
		}
		return settings;
	}

	/**
	 * A server card: its icon, name, players and description (pinged; what the invite carried until then), and
	 * Join.
	 */
	private void drawServer(GuiGraphicsExtractor graphics, String key, String address, @Nullable String name, @Nullable String sharedMotd, @Nullable String sharedIcon,
		boolean mine, int x, int y, int w, int areaTop, int areaBottom) {
		int cw = Math.min(bubbleWidth(w), 280);
		int cx = mine ? x + w - 6 - cw : x + 6;
		Theme.round(graphics, cx, y, cw, 44, Theme.PANEL_2, 2);
		ServerData data = links.status(address, name);
		Identifier icon = links.icon(address, sharedIcon);
		graphics.blit(icon != null ? icon : NO_ICON, cx + 6, y + 6, cx + 38, y + 38, 0, 1, 0, 1);
		int textX = cx + 44;
		int textW = cw - 44 - 54;
		graphics.text(font, Theme.clip(font, Component.literal(name != null ? name : address), textW), textX, y + 6, Theme.TEXT, false);
		boolean answered = data.state() == ServerData.State.SUCCESSFUL;
		Component motd = answered && data.motd != null ? data.motd
			: sharedMotd != null ? Component.literal(sharedMotd)
			: data.state() == ServerData.State.UNREACHABLE ? Component.translatable("orbit_cosmetic.social.server.offline")
			: Component.literal(address);
		int ly = y + 17;
		for (FormattedCharSequence line : font.split(motd.copy().withStyle(style -> style.getColor() == null ? style.withColor(ChatFormatting.GRAY) : style), textW).stream()
			.limit(2).toList()) {
			graphics.text(font, line, textX, ly, Theme.TEXT, false);
			ly += 10;
		}
		if (answered && data.status != null) {
			graphics.text(font, data.status, cx + cw - 6 - font.width(data.status), y + 6, Theme.MUTED, false);
		} else if (data.state() == ServerData.State.UNREACHABLE) {
			Component offline = Component.translatable("orbit_cosmetic.social.server.down").withStyle(ChatFormatting.RED);
			graphics.text(font, offline, cx + cw - 6 - font.width(offline), y + 6, Theme.TEXT, false);
		}
		Hotspot join = spot("join:" + key, Component.translatable("orbit_cosmetic.social.join"), cx + cw - 50, y + 20, 44, 18, areaTop, areaBottom,
			h -> ServerLinks.join(this, address, name != null ? name : address));
		join.setTooltip(Tooltip.create(Component.literal(address)));
		button(graphics, join, Component.translatable("orbit_cosmetic.social.join"), 0xFF3A7D44, 0xFF4CA35A);
	}

	private void drawFriends(GuiGraphicsExtractor graphics, int top) {
		int columns = 3;
		int columnW = (pw() - PAD * 2 - (columns - 1) * PAD) / columns;
		int bottom = py() + ph() - PAD;
		String[] titles = {"friend_list", "requests", "blocked"};
		for (int c = 0; c < columns; c++) {
			int cx = px() + PAD + c * (columnW + PAD);
			Theme.panel(graphics, cx, top, columnW, bottom - top, Theme.PANEL_2);
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.social." + titles[c]), cx + columnW / 2, top + 6, Theme.TEXT);
			int y = top + 20;
			List<Social.Person> people = switch (c) {
				case 0 -> social().friends();
				case 1 -> {
					List<Social.Person> all = new ArrayList<>(social().incoming());
					all.addAll(social().outgoing());
					yield all;
				}
				default -> social().blocked();
			};
			if (people.isEmpty()) {
				graphics.centeredText(font, Component.translatable("orbit_cosmetic.social.none." + titles[c]).withStyle(ChatFormatting.GRAY), cx + columnW / 2, y + 6, Theme.TEXT);
			}
			for (Social.Person person : people) {
				if (y + ROW > bottom) {
					break;
				}
				graphics.fill(cx + 4, y, cx + columnW - 4, y + ROW - 3, Theme.PANEL);
				face(graphics, person.uuid(), person.name(), cx + 8, y + 4, 19);
				int actionCount = c == 2 ? 1 : 3;
				if (c == 0) {
					// A friend's face and name open their statistics.
					String friendName = person.name();
					Hotspot page = spot("friend:" + person.uuid() + ":stats", Component.translatable("orbit_cosmetic.stats.see"), cx + 6, y + 2, columnW - 16 - actionCount * 20,
						ROW - 7, h -> Compat.setScreen(new StatsScreen(this, person.uuid(), friendName)));
					page.setTooltip(Tooltip.create(Component.translatable("orbit_cosmetic.stats.see")));
					if (page.isHovered()) {
						graphics.fill(cx + 4, y, cx + columnW - 4, y + ROW - 3, Theme.PANEL_HOVER);
						face(graphics, person.uuid(), person.name(), cx + 8, y + 4, 19);
					}
					page.drawFocus(graphics);
				}
				graphics.text(font, Theme.clip(font, Component.literal(person.name()), columnW - 44 - actionCount * 20), cx + 32, y + 4, Theme.TEXT, false);
				boolean incoming = c == 1 && social().incoming().contains(person);
				Component detail = c == 0 ? lastOnline(person) : c == 1 ? Component.translatable(incoming ? "orbit_cosmetic.social.incoming" : "orbit_cosmetic.social.outgoing")
					.withStyle(ChatFormatting.GRAY) : Component.translatable("orbit_cosmetic.social.blocked_detail").withStyle(ChatFormatting.GRAY);
				graphics.text(font, Theme.clip(font, detail, columnW - 44 - actionCount * 20), cx + 32, y + 15, Theme.TEXT, false);
				UUID uuid = person.uuid();
				List<String[]> actions = new ArrayList<>();
				if (c == 0) {
					actions.add(new String[] {"message", "message"});
					actions.add(new String[] {"trash", "remove"});
					actions.add(new String[] {"block", "block"});
				} else if (c == 1) {
					if (incoming) {
						actions.add(new String[] {"check_small", "accept"});
					}
					actions.add(new String[] {"close", incoming ? "decline" : "cancel"});
					actions.add(new String[] {"block", "block"});
				} else {
					actions.add(new String[] {"close", "unblock"});
				}
				int bx = cx + columnW - 8 - actions.size() * 20;
				for (String[] action : actions) {
					String name = person.name();
					Runnable run = switch (action[1]) {
						case "message" -> () -> openChat(uuid);
						case "remove" -> () -> confirm("remove", name, () -> social().remove(uuid));
						case "block" -> () -> confirm("block", name, () -> social().block(uuid));
						case "accept" -> () -> social().accept(uuid);
						case "unblock" -> () -> social().unblock(uuid);
						default -> () -> social().remove(uuid);
					};
					Component label = Component.translatable("orbit_cosmetic.social.action." + action[1]);
					Hotspot spot = spot("friend:" + uuid + ":" + action[1], Component.literal(name + ": ").append(label), bx, y + 5, 18, 18, h -> run.run());
					spot.setTooltip(Tooltip.create(label));
					boolean hot = spot.isHoveredOrFocused();
					graphics.fill(bx, y + 5, bx + 18, y + 23, hot ? Theme.PANEL_HOVER : Theme.PANEL_2);
					graphics.outline(bx, y + 5, 18, 18, hot ? Theme.EDGE_HOT : Theme.BORDER);
					Theme.icon(graphics, action[0], bx + 5, y + 10, action[1].equals("block") ? Theme.RED : action[1].equals("accept") ? Theme.GREEN : Theme.TEXT);
					bx += 20;
				}
				y += ROW;
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab == Tab.CHAT && mouseX > px() + LIST) {
			chatScroll = Math.max(0, chatScroll + scrollY * 24);
			return true;
		}
		if (tab == Tab.CHAT) {
			listScroll -= (int) Math.signum(scrollY);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
