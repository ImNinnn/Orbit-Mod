package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.gui.ui.FlatButton;
import dev.orbit.cosmetic.client.gui.ui.FlatCycle;
import dev.orbit.cosmetic.client.gui.ui.OrbitScreen;
import dev.orbit.cosmetic.client.gui.ui.Theme;
import dev.orbit.cosmetic.client.compat.Compat;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Shows the group hierarchy of a model, grouped by the body part it follows, and lets the user pick one. Groups that
 * already have settings of the kind being chosen (physics, particles) are ticked.
 */
public final class PartTreeScreen extends OrbitScreen {
	private final CosmeticModel model;
	private final IntPredicate configured;
	private final IntConsumer onChoose;
	private PartList list;
	private FlatButton choose;

	public PartTreeScreen(Screen parent, CosmeticModel model, IntPredicate configured, IntConsumer onChoose) {
		super(Component.translatable("orbit_cosmetic.parts.title"), parent);
		this.model = model;
		this.configured = configured;
		this.onChoose = onChoose;
	}

	/** "HEAD > hat > feather" for display. */
	static String path(CosmeticModel model, int index) {
		if (model == null || index < 0 || index >= model.bones().size()) {
			return "?";
		}
		List<String> names = new ArrayList<>();
		Bone bone = model.bones().get(index);
		while (true) {
			names.addFirst(bone.name());
			if (bone.parent() < 0) {
				break;
			}
			bone = model.bones().get(bone.parent());
		}
		if (bone.anchor() == Anchor.ROOT && Anchor.fromGroupName(bone.name()) == null) {
			names.addFirst(Component.translatable("orbit_cosmetic.parts.root").getString());
		}
		return String.join(" > ", names);
	}

	@Override
	protected void init() {
		int listWidth = Math.min(320, width - 20);
		list = new PartList(minecraft, listWidth, height - 70, 32);
		list.setX((width - listWidth) / 2);
		// Entries take their position when added, so fill the list only after it has been placed.
		list.populate();
		addRenderableWidget(list);
		choose = addRenderableWidget(new FlatButton(width / 2 - 154, height - 28, 150, 20, Component.translatable("orbit_cosmetic.parts.choose"), null, FlatButton.Style.SECONDARY, b -> choose()));
		addRenderableWidget(new FlatButton(width / 2 + 4, height - 28, 150, 20, CommonComponents.GUI_CANCEL, null, FlatButton.Style.SECONDARY, b -> onClose()));
		choose.active = list.getSelected() != null;
	}

	private void choose() {
		PartList.Entry selected = list.getSelected();
		if (selected != null) {
			onChoose.accept(selected.bone.index());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 10, Theme.TEXT);
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.parts.subtitle").withStyle(ChatFormatting.GRAY), width / 2, 20, Theme.TEXT);
	}

	private final class PartList extends ObjectSelectionList<PartList.Entry> {
		PartList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 14);
		}

		void populate() {
			if (model == null) {
				return;
			}
			// Top-level bones grouped by anchor in body-part order; children follow their parents (model order).
			for (Anchor anchor : Anchor.values()) {
				for (Bone bone : model.bones()) {
					if (bone.parent() < 0 && bone.anchor() == anchor) {
						addSubtree(model, bone, 0);
					}
				}
			}
		}

		private void addSubtree(CosmeticModel model, Bone bone, int depth) {
			addEntry(new Entry(bone, depth));
			for (Bone child : model.bones()) {
				if (child.parent() == bone.index()) {
					addSubtree(model, child, depth + 1);
				}
			}
		}

		@Override
		public int getRowWidth() {
			return width - 12;
		}

		@Override
		public void setSelected(@Nullable Entry entry) {
			super.setSelected(entry);
			if (choose != null) {
				choose.active = entry != null;
			}
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final Bone bone;
			final int depth;

			Entry(Bone bone, int depth) {
				this.bone = bone;
				this.depth = depth;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 2 + depth * 12;
				Component label = Component.literal((depth > 0 ? "└ " : "") + bone.name());
				if (depth == 0) {
					boolean anchor = Anchor.fromGroupName(bone.name()) != null;
					label = label.copy().withStyle(anchor ? ChatFormatting.AQUA : ChatFormatting.WHITE);
					if (!anchor) {
						label = label.copy().append(Component.literal("  ")).append(Component.translatable("orbit_cosmetic.parts.root").withStyle(ChatFormatting.DARK_GRAY));
					}
				}
				if (configured.test(bone.index())) {
					label = label.copy().append(Component.literal("  ✔").withStyle(ChatFormatting.GREEN));
				}
				graphics.text(font, label, x, getContentY() + 2, Theme.TEXT);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				PartList.this.setSelected(this);
				if (doubleClick) {
					choose();
				}
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", bone.name());
			}
		}
	}
}
