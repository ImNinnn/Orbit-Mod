package dev.orbit.cosmetic.client.gfx;

import com.mojang.blaze3d.vertex.PoseStack;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;
*///?}
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Where Orbit's 3D geometry goes (cosmetics, body parts, particles), in the world, on first-person arms and in
 * menu previews. Each Minecraft version draws it its own way: 1.21.9 and later submit geometry that is drawn later
 * in the frame, older versions write it into a buffer at once, and the render types and vertex calls differ too.
 * Orbit only says what to draw with which texture and how it is lit; this class is the part each version provides.
 */
public final class OrbitCanvas {
	/** How faces show their texture. */
	public enum Look {
		/** Pixels are either drawn or not (cosmetic textures without half-transparent pixels). */
		CUTOUT,
		/** Half-transparent pixels blend with what is behind them. */
		TRANSLUCENT,
		/** Translucent, and at full brightness whatever the light (glowing particles). */
		GLOWING
	}

	/** Takes the vertices of quads, four at a time: position, ARGB colour, texture UV, overlay, light, normal. */
	@FunctionalInterface
	public interface Vertices {
		void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz);
	}

	/**
	 * Writes quads when the game draws them, which may be later in the frame (so it must only use what it captured):
	 * {@code pose} and {@code normal} are where the pose stack was when it was handed in.
	 */
	@FunctionalInterface
	public interface Geometry {
		void emit(Matrix4f pose, Matrix3f normal, Vertices out);
	}

	//? if >=1.21.9 {
	private final SubmitNodeCollector collector;

	private OrbitCanvas(SubmitNodeCollector collector) {
		this.collector = collector;
	}

	public static OrbitCanvas of(SubmitNodeCollector collector) {
		return new OrbitCanvas(collector);
	}

	/** Minecraft's own object, for version-specific code. */
	public SubmitNodeCollector raw() {
		return collector;
	}

	/** Draws quads with a texture, from where the pose stack is now. */
	public void draw(PoseStack poseStack, Identifier texture, Look look, Geometry geometry) {
		collector.submitCustomGeometry(poseStack, type(texture, look), (posed, consumer) -> geometry.emit(posed.pose(), posed.normal(), consumer::addVertex));
	}
	//?} else {
	/*// Before 1.21.9 geometry goes into the frame's buffers at once.
	private final MultiBufferSource buffers;

	private OrbitCanvas(MultiBufferSource buffers) {
		this.buffers = buffers;
	}

	public static OrbitCanvas of(MultiBufferSource buffers) {
		return new OrbitCanvas(buffers);
	}

	/^* Minecraft's own object, for version-specific code. ^/
	public MultiBufferSource raw() {
		return buffers;
	}

	/^* Draws quads with a texture, from where the pose stack is now. ^/
	public void draw(PoseStack poseStack, Identifier texture, Look look, Geometry geometry) {
		VertexConsumer consumer = buffers.getBuffer(type(texture, look));
		//? if >=1.21 {
		geometry.emit(poseStack.last().pose(), poseStack.last().normal(), consumer::addVertex);
		//?} else {
		/^// Before 1.21 a vertex takes its colour as four numbers.
		geometry.emit(poseStack.last().pose(), poseStack.last().normal(), (x, y, z, color, u, v, overlay, light, nx, ny, nz) -> consumer.vertex(x, y, z,
			(color >> 16 & 0xFF) / 255F, (color >> 8 & 0xFF) / 255F, (color & 0xFF) / 255F, (color >>> 24) / 255F, u, v, overlay, light, nx, ny, nz));
		^///?}
	}
	*///?}

	/**
	 * Every look draws both sides of each face, as cosmetic models expect. Since 26.1 that is what "entity cutout"
	 * means (the culling one is "entity cutout cull"); before, the cutout that keeps both sides is "no cull".
	 */
	private static RenderType type(Identifier texture, Look look) {
		//? if >=26.1 {
		return switch (look) {
			case CUTOUT -> RenderTypes.entityCutout(texture);
			case TRANSLUCENT -> RenderTypes.entityTranslucent(texture);
			case GLOWING -> RenderTypes.entityTranslucentEmissive(texture);
		};
		//?} else if >=1.21.11 {
		/*return switch (look) {
			case CUTOUT -> RenderTypes.entityCutoutNoCull(texture);
			case TRANSLUCENT -> RenderTypes.entityTranslucent(texture);
			case GLOWING -> RenderTypes.entityTranslucentEmissive(texture);
		};
		*///?} else {
		/*// Before 1.21.11 the render types make themselves.
		return switch (look) {
			case CUTOUT -> RenderType.entityCutoutNoCull(texture);
			case TRANSLUCENT -> RenderType.entityTranslucent(texture);
			case GLOWING -> RenderType.entityTranslucentEmissive(texture);
		};
		*///?}
	}
}
