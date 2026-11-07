package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.common.protocol.AssetId;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a menu preview player's cosmetics were drawn the last time it was, so a click on the picture can tell which
 * cosmetic is under the mouse. Faces are kept as they were drawn into the preview's picture: x and y in its pixels
 * (y down), z towards the viewer. The player's own body is kept too, so it hides what is behind it.
 * Render thread only, which is also where mouse events arrive.
 */
public final class PickBuffer {
	/** Set on the render state of the one preview whose faces are kept. */
	public static final RenderStateDataKey<PickBuffer> KEY = RenderStateDataKey.create(() -> "orbit_cosmetic:pick");

	/** One face: the cosmetic it belongs to (null for the player's body) and its four corners. */
	private record Face(AssetId asset, float[] corners) {
	}

	private final List<Face> faces = new ArrayList<>();

	/** Forgets the faces of the previous picture; called when the player is drawn again. */
	public void reset() {
		faces.clear();
	}

	/** Adds a face from four corners at {@code positions[offset]}, every {@code stride} floats, drawn with {@code pose}. */
	void add(AssetId asset, Matrix4f pose, float[] positions, int offset, int stride) {
		float[] corners = new float[12];
		Vector3f p = new Vector3f();
		for (int i = 0; i < 4; i++) {
			int o = offset + i * stride;
			pose.transformPosition(positions[o], positions[o + 1], positions[o + 2], p);
			corners[i * 3] = p.x;
			corners[i * 3 + 1] = p.y;
			corners[i * 3 + 2] = p.z;
		}
		faces.add(new Face(asset, corners));
	}

	/** Adds the six faces of a box from its two opposite corners. */
	void addBox(AssetId asset, Matrix4f pose, float x0, float y0, float z0, float x1, float y1, float z1) {
		float[][] quads = {
			{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0},
			{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
			{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
			{x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0},
			{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1},
			{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1},
		};
		for (float[] quad : quads) {
			add(asset, pose, quad, 0, 3);
		}
	}

	/**
	 * The cosmetic in front at a point of the picture (in its pixels), or null where there is none or the player's
	 * body is in front.
	 */
	public AssetId pick(float x, float y) {
		float nearest = Float.NEGATIVE_INFINITY;
		AssetId result = null;
		for (Face face : faces) {
			float[] c = face.corners;
			float z = Math.max(depthIn(c, 0, 3, 6, x, y), depthIn(c, 0, 6, 9, x, y));
			if (z > nearest) {
				nearest = z;
				result = face.asset;
			}
		}
		return result;
	}

	/**
	 * Which cosmetic is in front at every GUI pixel of the picture, to outline cosmetics.
	 *
	 * @param width the picture's size in GUI pixels (likewise height)
	 * @param scale picture pixels per GUI pixel
	 */
	public Coverage coverage(int width, int height, float scale) {
		Object[] owner = new Object[width * height];
		float[] depth = new float[width * height];
		java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
		for (Face face : faces) {
			float[] c = face.corners;
			float minX = Float.MAX_VALUE;
			float minY = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			for (int i = 0; i < 12; i += 3) {
				minX = Math.min(minX, c[i]);
				maxX = Math.max(maxX, c[i]);
				minY = Math.min(minY, c[i + 1]);
				maxY = Math.max(maxY, c[i + 1]);
			}
			int x0 = Math.max(0, (int) Math.floor(minX / scale - 0.5F));
			int y0 = Math.max(0, (int) Math.floor(minY / scale - 0.5F));
			int x1 = Math.min(width - 1, (int) Math.ceil(maxX / scale - 0.5F));
			int y1 = Math.min(height - 1, (int) Math.ceil(maxY / scale - 0.5F));
			Object who = face.asset != null ? face.asset : BODY;
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					float px = (x + 0.5F) * scale;
					float py = (y + 0.5F) * scale;
					float z = Math.max(depthIn(c, 0, 3, 6, px, py), depthIn(c, 0, 6, 9, px, py));
					int index = y * width + x;
					if (z > depth[index]) {
						depth[index] = z;
						owner[index] = who;
					}
				}
			}
		}
		return new Coverage(owner, width, height);
	}

	/** Stands for the player's body in a {@link Coverage}. */
	private static final Object BODY = new Object();

	/** What {@link #coverage} found: who is in front at each GUI pixel. */
	public record Coverage(Object[] owner, int width, int height) {
		/**
		 * The pixels just outside where a cosmetic shows (next to it, on the left, right, top or bottom), as runs
		 * {x, y, length} along rows.
		 */
		public List<int[]> outline(AssetId asset) {
			List<int[]> runs = new ArrayList<>();
			for (int y = 0; y < height; y++) {
				int start = -1;
				for (int x = 0; x <= width; x++) {
					boolean edge = x < width && !is(x, y, asset) && (is(x - 1, y, asset) || is(x + 1, y, asset) || is(x, y - 1, asset) || is(x, y + 1, asset));
					if (edge && start < 0) {
						start = x;
					} else if (!edge && start >= 0) {
						runs.add(new int[] {start, y, x - start});
						start = -1;
					}
				}
			}
			return runs;
		}

		private boolean is(int x, int y, AssetId asset) {
			return x >= 0 && y >= 0 && x < width && y < height && asset.equals(owner[y * width + x]);
		}
	}

	/** The depth of a triangle of the corners at a point inside it, or negative infinity outside it. */
	private static float depthIn(float[] c, int a, int b, int d, float x, float y) {
		float ax = c[a];
		float ay = c[a + 1];
		float bx = c[b];
		float by = c[b + 1];
		float dx = c[d];
		float dy = c[d + 1];
		float area = (bx - ax) * (dy - ay) - (dx - ax) * (by - ay);
		if (Math.abs(area) < 1.0e-6F) {
			return Float.NEGATIVE_INFINITY;
		}
		float u = ((bx - x) * (dy - y) - (dx - x) * (by - y)) / area;
		float v = ((dx - x) * (ay - y) - (ax - x) * (dy - y)) / area;
		float w = 1 - u - v;
		if (u < 0 || v < 0 || w < 0) {
			return Float.NEGATIVE_INFINITY;
		}
		return u * c[a + 2] + v * c[b + 2] + w * c[d + 2];
	}
}
