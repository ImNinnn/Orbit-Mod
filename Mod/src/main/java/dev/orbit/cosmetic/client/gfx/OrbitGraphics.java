package dev.orbit.cosmetic.client.gfx;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//? if >=1.21.6 {
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.RenderPipelines;
//?} else {
/*import com.mojang.blaze3d.systems.RenderSystem;
import dev.orbit.cosmetic.client.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Everything Orbit draws on screens and the HUD goes through here: rectangles, text, sprites, pictures, items,
 * player heads, clipping, moving and scaling, layers and tooltips. It is what Orbit needs from Minecraft's GUI, not a
 * copy of one Minecraft version's API, so that each Minecraft version only has to provide this one class (newer
 * versions draw with {@code GuiGraphicsExtractor}, older ones with {@code GuiGraphics}).
 * <p>
 * Made once per frame around the graphics object Minecraft hands to a screen, widget or HUD layer
 * ({@link #of}). Code that has to reach Minecraft's own object (version-specific code only) uses {@link #raw()}.
 */
public final class OrbitGraphics {
	private final GuiGraphicsExtractor graphics;

	private OrbitGraphics(GuiGraphicsExtractor graphics) {
		this.graphics = graphics;
	}

	public static OrbitGraphics of(GuiGraphicsExtractor graphics) {
		return new OrbitGraphics(graphics);
	}

	/** Minecraft's own graphics object, for version-specific code (mixins, compat, the 3D previews). */
	public GuiGraphicsExtractor raw() {
		return graphics;
	}

	// ---------------------------------------------------------------- shapes

	/** A filled rectangle from (x0, y0) to (x1, y1), in ARGB. */
	public void fill(int x0, int y0, int x1, int y1, int color) {
		graphics.fill(x0, y0, x1, y1, color);
	}

	/**
	 * A rectangle's 1-pixel border, drawn as four strips (as Minecraft's own outline does since 1.21.11; 1.21.10's
	 * outline is put off to the end of the frame, where it ignores the position, scale and clipping of the moment).
	 */
	public void outline(int x, int y, int width, int height, int color) {
		graphics.fill(x, y, x + width, y + 1, color);
		graphics.fill(x, y + height - 1, x + width, y + height, color);
		graphics.fill(x, y + 1, x + 1, y + height - 1, color);
		graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
	}

	/** A rectangle fading from one colour at the top to another at the bottom. */
	public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		graphics.fillGradient(x0, y0, x1, y1, top, bottom);
	}

	// ---------------------------------------------------------------- text

	public void text(Font font, String text, int x, int y, int color) {
		graphics.text(font, text, x, y, color);
	}

	public void text(Font font, String text, int x, int y, int color, boolean shadow) {
		graphics.text(font, text, x, y, color, shadow);
	}

	public void text(Font font, Component text, int x, int y, int color) {
		graphics.text(font, text, x, y, color);
	}

	public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
		graphics.text(font, text, x, y, color, shadow);
	}

	public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
		graphics.text(font, text, x, y, color);
	}

	public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		graphics.text(font, text, x, y, color, shadow);
	}

	/** Text centred on x (with a shadow). */
	public void centeredText(Font font, String text, int x, int y, int color) {
		graphics.centeredText(font, text, x, y, color);
	}

	public void centeredText(Font font, Component text, int x, int y, int color) {
		graphics.centeredText(font, text, x, y, color);
	}

	public void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
		graphics.centeredText(font, text, x, y, color);
	}

	// ---------------------------------------------------------------- pictures

	//? if >=1.21.6 {
	/** A GUI sprite (Orbit's icons, Minecraft's HUD sprites) stretched over the rectangle. */
	public void sprite(Identifier sprite, int x, int y, int width, int height) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
	}

	/** A GUI sprite tinted with an ARGB colour (white keeps it as it is; the alpha fades it). */
	public void sprite(Identifier sprite, int x, int y, int width, int height, int color) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, color);
	}

	/**
	 * Part of a GUI sprite: the sprite is taken as {@code spriteWidth} by {@code spriteHeight} pixels, and the part
	 * from (u, v), {@code width} by {@code height}, is drawn at (x, y) (a piece of the hotbar, part of a bar).
	 */
	public void spritePart(Identifier sprite, int spriteWidth, int spriteHeight, int u, int v, int x, int y, int width, int height) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
	}

	/** A whole texture (a screenshot, a server's icon, a group's picture) from (x0, y0) to (x1, y1), with UVs from 0 to 1. */
	public void image(Identifier texture, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) {
		graphics.blit(texture, x0, y0, x1, y1, u0, u1, v0, v1);
	}

	/**
	 * Part of a texture of {@code textureWidth} by {@code textureHeight} pixels: the part from (u, v), {@code width}
	 * by {@code height}, drawn 1:1 at (x, y) (repeated when the part is wider than the texture).
	 */
	public void texture(Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
	}
	//?} else {
	/*// Before 1.21.6 pictures are drawn straight away with the shader's state: blending is switched on for them (the
	// game's sprites leave it as they find it), and a tint is the shader colour.
	public void sprite(Identifier sprite, int x, int y, int width, int height) {
		RenderSystem.enableBlend();
		//? if >=1.20.2 {
		graphics.blitSprite(sprite, x, y, width, height);
		//?} else {
		/^// Before 1.20.2 there is no sprite atlas for the GUI (see compat/LegacySprites).
		dev.orbit.cosmetic.client.compat.LegacySprites.draw(graphics, sprite, x, y, width, height);
		^///?}
		RenderSystem.disableBlend();
	}

	public void sprite(Identifier sprite, int x, int y, int width, int height, int color) {
		graphics.setColor((color >> 16 & 0xFF) / 255F, (color >> 8 & 0xFF) / 255F, (color & 0xFF) / 255F, (color >>> 24) / 255F);
		sprite(sprite, x, y, width, height);
		graphics.setColor(1, 1, 1, 1);
	}

	public void spritePart(Identifier sprite, int spriteWidth, int spriteHeight, int u, int v, int x, int y, int width, int height) {
		RenderSystem.enableBlend();
		//? if >=1.20.2 {
		graphics.blitSprite(sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
		//?} else {
		/^dev.orbit.cosmetic.client.compat.LegacySprites.drawPart(graphics, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
		^///?}
		RenderSystem.disableBlend();
	}

	public void image(Identifier texture, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) {
		RenderSystem.enableBlend();
		((GuiGraphicsExtractorAccessor) graphics).orbit$innerBlit(texture, x0, x1, y0, y1, 0, u0, u1, v0, v1);
		RenderSystem.disableBlend();
	}

	public void texture(Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		RenderSystem.enableBlend();
		graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
		RenderSystem.disableBlend();
	}
	*///?}

	/** A player's face (with their hat layer), {@code size} pixels square. */
	public void face(OrbitSkin skin, int x, int y, int size) {
		//? if >=1.20.2 {
		PlayerFaceExtractor.extractRenderState(graphics, skin.raw(), x, y, size);
		//?} else {
		/*PlayerFaceExtractor.extractRenderState(graphics, skin.texture(), x, y, size);
		*///?}
	}

	/** While {@link #entity} draws: the pose it asked for (body turn, head turn, pitch), or null. */
	private static float @Nullable [] entityPose;

	/**
	 * A creature drawn in a box like the inventory's player, {@code size} pixels per block, but turned as given:
	 * its body {@code bodyRot} degrees (180 faces the viewer), its head {@code headRot} degrees from the body, and
	 * tilted {@code pitch} degrees. Its name is not shown.
	 */
	//? if >=1.21.6 {
	public void entity(LivingEntity entity, int x0, int y0, int x1, int y1, int size, float bodyRot, float headRot, float pitch) {
		entityPose = new float[] {bodyRot, headRot, pitch};
		try {
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x0, y0, x1, y1, size, 0.0625F, (x0 + x1) / 2F, (y0 + y1) / 2F, entity);
		} finally {
			entityPose = null;
		}
	}

	/** Called by InventoryScreenMixin with the pose the inventory drawing is about to use; {@link #entity} replaces it. */
	public static void poseEntity(EntityRenderState state) {
		if (entityPose != null && state instanceof LivingEntityRenderState living) {
			living.bodyRot = entityPose[0];
			living.yRot = entityPose[1];
			living.xRot = entityPose[2];
			living.nameTag = null;
		}
	}
	//?} else {
	/*// Before 1.21.6 the entity itself is drawn: it is turned as asked for the drawing (as the inventory does), then
	// turned back.
	public void entity(LivingEntity entity, int x0, int y0, int x1, int y1, int size, float bodyRot, float headRot, float pitch) {
		float[] saved = {entity.yBodyRot, entity.yBodyRotO, entity.yHeadRot, entity.yHeadRotO, entity.getYRot(), entity.getXRot(), entity.xRotO};
		entity.yBodyRot = entity.yBodyRotO = bodyRot;
		entity.yHeadRot = entity.yHeadRotO = bodyRot + headRot;
		entity.setYRot(bodyRot + headRot);
		entity.setXRot(pitch);
		entity.xRotO = pitch;
		graphics.enableScissor(x0, y0, x1, y1);
		try {
			float scale = entity.getScale();
			//? if >=1.20.2 {
			Vector3f offset = new Vector3f(0.0F, entity.getBbHeight() / 2.0F + 0.0625F * scale, 0.0F);
			InventoryScreen.renderEntityInInventory(graphics, (x0 + x1) / 2F, (y0 + y1) / 2F, size / scale, offset, new Quaternionf().rotateZ((float) Math.PI), null, entity);
			//?} else {
			/^// Before 1.20.2 the entity is placed by its feet.
			int feet = Math.round((y0 + y1) / 2F + (entity.getBbHeight() / 2.0F + 0.0625F * scale) * size / scale);
			InventoryScreen.renderEntityInInventory(graphics, (x0 + x1) / 2, feet, Math.round(size / scale), new Quaternionf().rotateZ((float) Math.PI), null, entity);
			^///?}
		} finally {
			graphics.disableScissor();
			entity.yBodyRot = saved[0];
			entity.yBodyRotO = saved[1];
			entity.yHeadRot = saved[2];
			entity.yHeadRotO = saved[3];
			entity.setYRot(saved[4]);
			entity.setXRot(saved[5]);
			entity.xRotO = saved[6];
		}
	}

	private static int drawingEntity;

	/^* Called by InventoryScreenMixin around every creature drawn in a screen (the inventory's player, {@link #entity}). ^/
	public static void drawingEntity(boolean drawing) {
		drawingEntity = Math.max(0, drawingEntity + (drawing ? 1 : -1));
	}

	/^* Whether a creature is being drawn in a screen, where the own nameplate is never shown. ^/
	public static boolean isDrawingEntity() {
		return drawingEntity > 0;
	}
	*///?}

	/** An item as in an inventory slot (16 by 16 pixels). */
	public void item(ItemStack stack, int x, int y) {
		graphics.item(stack, x, y);
	}

	/** An item's count and durability bar, over where {@link #item} drew it. */
	public void itemDecorations(Font font, ItemStack stack, int x, int y) {
		graphics.itemDecorations(font, stack, x, y);
	}

	// ---------------------------------------------------------------- state

	//? if >=1.21.6 {
	/** Saves the current position and scale, for {@link #pop}. */
	public void push() {
		graphics.pose().pushMatrix();
	}

	public void pop() {
		graphics.pose().popMatrix();
	}

	public void translate(float x, float y) {
		graphics.pose().translate(x, y);
	}

	public void scale(float x, float y) {
		graphics.pose().scale(x, y);
	}
	//?} else {
	/*/^* Saves the current position and scale, for {@link #pop}. ^/
	public void push() {
		graphics.pose().pushPose();
	}

	public void pop() {
		graphics.pose().popPose();
	}

	public void translate(float x, float y) {
		graphics.pose().translate(x, y, 0);
	}

	public void scale(float x, float y) {
		graphics.pose().scale(x, y, 1);
	}
	*///?}

	/** Only what is drawn inside the rectangle shows, until {@link #disableScissor} (they nest). */
	public void enableScissor(int x0, int y0, int x1, int y1) {
		graphics.enableScissor(x0, y0, x1, y1);
	}

	public void disableScissor() {
		graphics.disableScissor();
	}

	/**
	 * What is drawn next goes over everything drawn before it in this frame, text and items included (Minecraft
	 * otherwise draws all text over all rectangles of the same frame).
	 */
	public void layer() {
		//? if >=1.21.6 {
		graphics.nextStratum();
		//?} else {
		/*// Before 1.21.6 everything is drawn in order already, but kept by depth: text and items sit a little nearer
		// than rectangles. Forgetting the depth puts what comes next over them.
		graphics.flush();
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
		*///?}
	}

	/** A tooltip by the mouse, drawn over the screen at the end of the frame. */
	public void tooltip(Font font, Component text, int mouseX, int mouseY) {
		//? if >=1.21.6 {
		graphics.setTooltipForNextFrame(font, text, mouseX, mouseY);
		//?} else {
		/*tooltip(font, List.of(text.getVisualOrderText()), mouseX, mouseY);
		*///?}
	}

	public void tooltip(Font font, List<? extends FormattedCharSequence> lines, int mouseX, int mouseY) {
		//? if >=1.21.6 {
		graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
		//?} else {
		/*// Before 1.21.6: a screen draws it after everything else (where the mouse is); the HUD draws it now.
		if (Minecraft.getInstance().screen != null) {
			Minecraft.getInstance().screen.setTooltipForNextRenderPass(List.copyOf(lines));
		} else {
			graphics.renderTooltip(font, lines, mouseX, mouseY);
		}
		*///?}
	}
}
