package dev.orbit.cosmetic.client.gui.ui;

import dev.orbit.cosmetic.client.gfx.OrbitGraphics;
import dev.orbit.cosmetic.client.input.OrbitChar;
import dev.orbit.cosmetic.client.input.OrbitClick;
import dev.orbit.cosmetic.client.input.OrbitKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Every screen of Orbit's: it draws with {@link OrbitGraphics} in {@link #draw} and {@link #drawBackground}, and
 * hears keys, characters and clicks as {@link OrbitKey}, {@link OrbitChar} and {@link OrbitClick} (in methods named
 * like Minecraft's: {@code keyPressed(OrbitKey)}...; calling {@code super} lets Minecraft handle them). This class is
 * the one place that knows how each Minecraft version hands a screen its drawing and input. {@link OrbitScreen}
 * adds Orbit's look (the grid, going back to the screen it was opened from); overlays like the emote wheel extend
 * this one directly.
 */
public abstract class OrbitBaseScreen extends Screen {
	protected OrbitBaseScreen(Component title) {
		super(title);
	}

	@Override
	public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		draw(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick);
	}

	/** Draws the screen over its background: by default only its widgets (see {@link #drawWidgets}). */
	protected void draw(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawWidgets(graphics, mouseX, mouseY, partialTick);
	}

	/** Draws the screen's widgets (buttons, text boxes...), as Minecraft does. */
	protected final void drawWidgets(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics.raw(), mouseX, mouseY, partialTick);
	}

	@Override
	public final void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawBackground(OrbitGraphics.of(graphics), mouseX, mouseY, partialTick);
	}

	/** The screen's background: by default Minecraft's own (see {@link #drawMinecraftBackground}). */
	protected void drawBackground(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawMinecraftBackground(graphics, mouseX, mouseY, partialTick);
	}

	/** Minecraft's own menu background (the blurred world, or the panorama). */
	protected final void drawMinecraftBackground(OrbitGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics.raw(), mouseX, mouseY, partialTick);
	}

	// ---------------------------------------------------------------- input

	//? if >=1.21.9 {
	@Override
	public final boolean keyPressed(KeyEvent event) {
		return keyPressed(new OrbitKey(event));
	}

	public boolean keyPressed(OrbitKey key) {
		return super.keyPressed(key.raw());
	}

	@Override
	public final boolean keyReleased(KeyEvent event) {
		return keyReleased(new OrbitKey(event));
	}

	public boolean keyReleased(OrbitKey key) {
		return super.keyReleased(key.raw());
	}

	@Override
	public final boolean charTyped(CharacterEvent event) {
		return charTyped(new OrbitChar(event));
	}

	public boolean charTyped(OrbitChar character) {
		return super.charTyped(character.raw());
	}

	@Override
	public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return mouseClicked(new OrbitClick(event), doubleClick);
	}

	public boolean mouseClicked(OrbitClick click, boolean doubleClick) {
		return super.mouseClicked(click.raw(), doubleClick);
	}

	@Override
	public final boolean mouseReleased(MouseButtonEvent event) {
		return mouseReleased(new OrbitClick(event));
	}

	public boolean mouseReleased(OrbitClick click) {
		return super.mouseReleased(click.raw());
	}

	@Override
	public final boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return mouseDragged(new OrbitClick(event), dragX, dragY);
	}

	public boolean mouseDragged(OrbitClick click, double dragX, double dragY) {
		return super.mouseDragged(click.raw(), dragX, dragY);
	}
	//?} else {
	/*@Override
	public final boolean keyPressed(int key, int scanCode, int modifiers) {
		return keyPressed(new OrbitKey(key, scanCode, modifiers));
	}

	public boolean keyPressed(OrbitKey key) {
		return super.keyPressed(key.key(), key.scanCode(), key.modifiers());
	}

	@Override
	public final boolean keyReleased(int key, int scanCode, int modifiers) {
		return keyReleased(new OrbitKey(key, scanCode, modifiers));
	}

	public boolean keyReleased(OrbitKey key) {
		return super.keyReleased(key.key(), key.scanCode(), key.modifiers());
	}

	@Override
	public final boolean charTyped(char character, int modifiers) {
		return charTyped(new OrbitChar(character, modifiers));
	}

	public boolean charTyped(OrbitChar character) {
		return super.charTyped(character.character(), character.modifiers());
	}

	@Override
	public final boolean mouseClicked(double x, double y, int button) {
		return mouseClicked(new OrbitClick(x, y, button), OrbitClick.isDoubleClick());
	}

	public boolean mouseClicked(OrbitClick click, boolean doubleClick) {
		return super.mouseClicked(click.x(), click.y(), click.button());
	}

	@Override
	public final boolean mouseReleased(double x, double y, int button) {
		return mouseReleased(new OrbitClick(x, y, button));
	}

	public boolean mouseReleased(OrbitClick click) {
		return super.mouseReleased(click.x(), click.y(), click.button());
	}

	@Override
	public final boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
		return mouseDragged(new OrbitClick(x, y, button), dragX, dragY);
	}

	public boolean mouseDragged(OrbitClick click, double dragX, double dragY) {
		return super.mouseDragged(click.x(), click.y(), click.button(), dragX, dragY);
	}
	*///?}
}
