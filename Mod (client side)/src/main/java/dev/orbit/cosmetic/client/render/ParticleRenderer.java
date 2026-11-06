package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.anim.ParticleSystem;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws particles as flat squares facing the viewer, each showing its whole texture, turned by its spin. Glowing
 * particles are drawn at full brightness.
 */
public final class ParticleRenderer {
	/** Floats per vertex: x, y, z, u, v. */
	private static final int STRIDE = 5;
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float[][] CORNERS = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};

	private record Quad(float[] vertices, int color, int light) {
	}

	private ParticleRenderer() {
	}

	/**
	 * @param originX subtracted from positions first (in double precision: world coordinates are large), with Y and Z
	 * @param toRender maps positions in the system's space, minus the origin, to the pose stack's space
	 * @param right the viewer's right, in the system's space (unit length)
	 * @param up the viewer's up, in the system's space (unit length)
	 * @param normal the particles' normal in the pose stack's space: where the game's shading lights them fully
	 */
	public static void submit(ParticleSystem system, double originX, double originY, double originZ, Matrix4f toRender, Vector3f right, Vector3f up,
		Vector3f normal, PoseStack poseStack, SubmitNodeCollector collector) {
		Map<RenderType, List<Quad>> quads = new LinkedHashMap<>();
		Vector3f side = new Vector3f();
		Vector3f top = new Vector3f();
		Vector3f corner = new Vector3f();
		for (ParticleSystem.Particle p : system.particles()) {
			int alpha = Math.round(p.alpha() * 255);
			if (alpha <= 0 || p.texture == null || p.source == null || p.source.isReleased()) {
				continue;
			}
			RenderType type = p.glow() ? RenderTypes.entityTranslucentEmissive(p.texture) : RenderTypes.entityTranslucent(p.texture);
			float half = p.size() / 2;
			if (half <= 0) {
				continue;
			}
			float cos = (float) Math.cos(p.spin);
			float sin = (float) Math.sin(p.spin);
			side.set(right).mul(cos * half).add(up.x * sin * half, up.y * sin * half, up.z * sin * half);
			top.set(up).mul(cos * half).sub(right.x * sin * half, right.y * sin * half, right.z * sin * half);
			float[] quad = new float[4 * STRIDE];
			for (int i = 0; i < 4; i++) {
				float[] c = CORNERS[i];
				corner.set((float) (p.x - originX), (float) (p.y - originY), (float) (p.z - originZ)).add(side.x * c[0], side.y * c[0], side.z * c[0]).add(top.x * c[1], top.y * c[1], top.z * c[1]);
				toRender.transformPosition(corner);
				int o = i * STRIDE;
				quad[o] = corner.x;
				quad[o + 1] = corner.y;
				quad[o + 2] = corner.z;
				quad[o + 3] = c[2];
				quad[o + 4] = c[3];
			}
			quads.computeIfAbsent(type, t -> new ArrayList<>()).add(new Quad(quad, alpha << 24 | 0xFFFFFF, p.glow() ? FULL_BRIGHT : p.light));
		}
		for (Map.Entry<RenderType, List<Quad>> entry : quads.entrySet()) {
			List<Quad> list = entry.getValue();
			collector.submitCustomGeometry(poseStack, entry.getKey(), (posed, consumer) -> {
				Matrix4f pose = posed.pose();
				Vector3f v = new Vector3f();
				Vector3f n = posed.normal().transform(new Vector3f(normal)).normalize();
				for (Quad quad : list) {
					for (int i = 0; i < 4; i++) {
						int o = i * STRIDE;
						pose.transformPosition(quad.vertices[o], quad.vertices[o + 1], quad.vertices[o + 2], v);
						consumer.addVertex(v.x, v.y, v.z, quad.color, quad.vertices[o + 3], quad.vertices[o + 4], OverlayTexture.NO_OVERLAY, quad.light, n.x, n.y, n.z);
					}
				}
			});
		}
	}
}
