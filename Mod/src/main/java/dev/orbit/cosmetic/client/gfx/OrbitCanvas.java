package dev.orbit.cosmetic.client.gfx;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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

	private static RenderType type(Identifier texture, Look look) {
		return switch (look) {
			case CUTOUT -> RenderTypes.entityCutout(texture);
			case TRANSLUCENT -> RenderTypes.entityTranslucent(texture);
			case GLOWING -> RenderTypes.entityTranslucentEmissive(texture);
		};
	}
}
