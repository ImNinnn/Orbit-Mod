package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.mixin.ModelPartAccessor;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Quad;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Flattens the pixels of the skin's outer layer (hat, jacket, sleeves, trouser legs) that worn cosmetics would
 * poke through. Each cosmetic is measured once, on the standing pose: an outer-layer pixel is covered when one of
 * the cosmetic's visible faces lies between the body and that pixel (or right on it), and the pixels next to a
 * covered one count too, so small gaps in a model (the back of a cap) are not crossed by the layer. The player is
 * then drawn with a copy of their skin where those pixels are painted onto the skin under them instead. Animated
 * parts are measured where they rest. Main thread, except the measuring, which runs in the background.
 */
public final class SkinMasks {
	private static final int SIZE = 64;
	private static final int WORDS = SIZE * SIZE / 64;
	/** How far past the outer layer a face still counts as covering it (faces lying on the layer would flicker). */
	private static final float REACH = 0.05F;
	/** How far under the body's surface a face still counts (faces lying on the skin). */
	private static final float UNDER = 0.01F;
	private static final long TEXTURE_UNUSED_MS = 30_000;
	private static final long MASK_UNUSED_MS = 300_000;
	private static final long RETRY_MS = 5_000;
	/** How close another pixel's middle is to count as next to a covered one (pixels; the four neighbours). */
	private static final float NEXT_TO = 1.05F;
	/** For each outer-layer pixel (y * 64 + x), the pixel of the skin under it; -1 elsewhere. */
	private static final int[] UNDER_LAYER = underLayer();

	/** One outer-layer pixel: where it is on its body part (model pixels), the way it faces, and its height over the body. */
	private record Spot(int texel, Anchor anchor, Vector3f position, Vector3f normal, float depth) {
	}

	private static final class Mask {
		final CompletableFuture<long[]> bits;
		long lastUsed = System.currentTimeMillis();

		Mask(CompletableFuture<long[]> bits) {
			this.bits = bits;
		}
	}

	private static final class Masked {
		/** Null when the skin's pixels could not be read; tried again after a while. */
		final Identifier id;
		final long created = System.currentTimeMillis();
		long lastUsed = created;

		Masked(Identifier id) {
			this.id = id;
		}
	}

	private final Map<Boolean, Spot[]> spots = new HashMap<>();
	private final Map<Boolean, Map<AssetId, Mask>> masks = Map.of(false, new HashMap<>(), true, new HashMap<>());
	private final Map<String, Masked> textures = new HashMap<>();
	private int nextTexture;

	/**
	 * The skin to draw a player with: {@code skin} without the outer-layer pixels these cosmetics cover, or
	 * {@code skin} itself when they cover none (or are still being measured). {@code skip} (an emote's own props)
	 * hides nothing.
	 */
	public PlayerSkin apply(PlayerSkin skin, List<RenderPiece> pieces, BakedCosmetic skip) {
		if (skin == null || pieces.isEmpty()) {
			return skin;
		}
		Identifier masked = texture(skin.body().texturePath(), skin.model() == PlayerModelType.SLIM, pieces, skip);
		return masked == null ? skin : new PlayerSkin(new ClientAsset.ResourceTexture(masked, masked), skin.cape(), skin.elytra(), skin.model(), skin.secure());
	}

	/** The masked copy of a skin texture for these cosmetics (first-person arms), or null for the texture itself. */
	public Identifier texture(Identifier skin, boolean slim, List<RenderPiece> pieces, BakedCosmetic skip) {
		long[] mask = null;
		for (RenderPiece piece : pieces) {
			if (piece.baked() == skip || piece.baked().isReleased()) {
				continue;
			}
			long[] bits = mask(piece.baked(), slim);
			if (bits == null) {
				continue;
			}
			if (mask == null) {
				mask = bits.clone();
			} else {
				for (int i = 0; i < WORDS; i++) {
					mask[i] |= bits[i];
				}
			}
		}
		if (mask == null || isEmpty(mask)) {
			return null;
		}
		String key = skin + "#" + Long.toHexString(hash(mask));
		long now = System.currentTimeMillis();
		Masked masked = textures.get(key);
		if (masked == null || masked.id == null && now - masked.created > RETRY_MS) {
			masked = new Masked(create(skin, mask));
			textures.put(key, masked);
		}
		masked.lastUsed = now;
		return masked.id;
	}

	/** Releases masked skins and measurements nobody used for a while. */
	public void collect() {
		long now = System.currentTimeMillis();
		Iterator<Masked> it = textures.values().iterator();
		while (it.hasNext()) {
			Masked masked = it.next();
			if (now - masked.lastUsed > TEXTURE_UNUSED_MS) {
				if (masked.id != null) {
					Minecraft.getInstance().getTextureManager().release(masked.id);
				}
				it.remove();
			}
		}
		for (Map<AssetId, Mask> bySlim : masks.values()) {
			bySlim.values().removeIf(mask -> now - mask.lastUsed > MASK_UNUSED_MS);
		}
	}

	/** Which outer-layer pixels a cosmetic covers (bit = y * 64 + x), or null while it is being measured. */
	private long[] mask(BakedCosmetic baked, boolean slim) {
		Map<AssetId, Mask> bySlim = masks.get(slim);
		Mask mask = bySlim.get(baked.id());
		if (mask == null) {
			Spot[] layer = spots(slim);
			CosmeticModel model = baked.model();
			mask = new Mask(CompletableFuture.supplyAsync(() -> measure(model, layer), Util.backgroundExecutor()).exceptionally(error -> {
				OrbitClient.LOGGER.debug("Could not measure cosmetic {}: {}", baked.id().shortHex(), error.getMessage());
				return new long[WORDS];
			}));
			bySlim.put(baked.id(), mask);
		}
		mask.lastUsed = System.currentTimeMillis();
		return mask.bits.getNow(null);
	}

	// ---------------------------------------------------------------- the masked skin

	private Identifier create(Identifier skin, long[] mask) {
		NativeImage pixels = pixels(skin);
		if (pixels == null) {
			return null;
		}
		int scale = pixels.getWidth() / SIZE;
		if (scale < 1 || pixels.getWidth() % SIZE != 0 || pixels.getHeight() != pixels.getWidth()) {
			pixels.close();
			return null;
		}
		for (int texel = 0; texel < SIZE * SIZE; texel++) {
			int under = UNDER_LAYER[texel];
			if ((mask[texel >> 6] & 1L << (texel & 63)) == 0 || under < 0) {
				continue;
			}
			int x = texel % SIZE * scale;
			int y = texel / SIZE * scale;
			int baseX = under % SIZE * scale;
			int baseY = under / SIZE * scale;
			for (int dy = 0; dy < scale; dy++) {
				for (int dx = 0; dx < scale; dx++) {
					// The layer's pixel is painted onto the skin under it, then removed from the layer.
					int layer = pixels.getPixel(x + dx, y + dy);
					pixels.setPixel(baseX + dx, baseY + dy, over(layer, pixels.getPixel(baseX + dx, baseY + dy)));
					pixels.setPixel(x + dx, y + dy, layer & 0x00FFFFFF);
				}
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "skin_mask/" + nextTexture++);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, pixels));
		return id;
	}

	/** A copy of a skin's pixels: downloaded skins keep theirs; the default skins are read from the resources. */
	private static NativeImage pixels(Identifier skin) {
		Minecraft minecraft = Minecraft.getInstance();
		AbstractTexture texture = minecraft.getTextureManager().getTexture(skin);
		if (texture instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
			NativeImage source = dynamic.getPixels();
			NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), false);
			copy.copyFrom(source);
			return copy;
		}
		Optional<Resource> resource = minecraft.getResourceManager().getResource(skin);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return NativeImage.read(in);
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Could not read skin {}: {}", skin, e.getMessage());
			return null;
		}
	}

	/** {@code top} drawn over {@code bottom} (ARGB). */
	private static int over(int top, int bottom) {
		int alpha = top >>> 24;
		if (alpha == 0) {
			return bottom;
		}
		if (alpha == 255) {
			return top;
		}
		int result = 0xFF000000 & Math.max(alpha, bottom >>> 24) << 24;
		for (int shift = 0; shift < 24; shift += 8) {
			int channel = ((top >> shift & 0xFF) * alpha + (bottom >> shift & 0xFF) * (255 - alpha)) / 255;
			result |= channel << shift;
		}
		return result;
	}

	/** The skin's layout: each outer-layer part is a copy of its body part's layout, moved by a fixed offset. */
	private static int[] underLayer() {
		int[] map = new int[SIZE * SIZE];
		java.util.Arrays.fill(map, -1);
		// x, y, width, height of the layer part, then where its body part is.
		int[][] parts = {
			{32, 0, 32, 16, 0, 0}, // hat over the head
			{16, 32, 24, 16, 16, 16}, // jacket over the body
			{40, 32, 16, 16, 40, 16}, // right sleeve over the right arm
			{48, 48, 16, 16, 32, 48}, // left sleeve over the left arm
			{0, 32, 16, 16, 0, 16}, // right trouser leg over the right leg
			{0, 48, 16, 16, 16, 48} // left trouser leg over the left leg
		};
		for (int[] part : parts) {
			for (int y = 0; y < part[3]; y++) {
				for (int x = 0; x < part[2]; x++) {
					map[(part[1] + y) * SIZE + part[0] + x] = (part[5] + y) * SIZE + part[4] + x;
				}
			}
		}
		return map;
	}

	private static boolean isEmpty(long[] mask) {
		for (long word : mask) {
			if (word != 0) {
				return false;
			}
		}
		return true;
	}

	private static long hash(long[] mask) {
		long hash = 0xCBF29CE484222325L;
		for (long word : mask) {
			hash = (hash ^ word) * 0x100000001B3L;
		}
		return hash;
	}

	// ---------------------------------------------------------------- the outer layer

	/** Every outer-layer pixel of the vanilla player model (wide or slim arms). Main thread, once each. */
	private Spot[] spots(boolean slim) {
		return spots.computeIfAbsent(slim, s -> {
			PlayerModel model = VanillaPlayerModels.model(slim);
			List<Spot> result = new ArrayList<>();
			addSpots(model.hat, Anchor.HEAD, 0.5F, result);
			addSpots(model.jacket, Anchor.BODY, 0.25F, result);
			addSpots(model.leftSleeve, Anchor.LEFT_ARM, 0.25F, result);
			addSpots(model.rightSleeve, Anchor.RIGHT_ARM, 0.25F, result);
			addSpots(model.leftPants, Anchor.LEFT_LEG, 0.25F, result);
			addSpots(model.rightPants, Anchor.RIGHT_LEG, 0.25F, result);
			return result.toArray(Spot[]::new);
		});
	}

	/** The pixels of one layer part, in the frame of the body part it belongs to. */
	private static void addSpots(ModelPart layer, Anchor anchor, float depth, List<Spot> out) {
		PartPose pose = layer.getInitialPose();
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) layer).orbit$cubes()) {
			Vector3f center = new Vector3f((cube.minX + cube.maxX) / 2, (cube.minY + cube.maxY) / 2, (cube.minZ + cube.maxZ) / 2);
			for (ModelPart.Polygon polygon : cube.polygons) {
				ModelPart.Vertex[] v = polygon.vertices();
				if (v.length < 4) {
					continue;
				}
				Vector3f p0 = new Vector3f(v[0].x(), v[0].y(), v[0].z());
				Vector3f edgeA = new Vector3f(v[1].x(), v[1].y(), v[1].z()).sub(p0);
				Vector3f edgeB = new Vector3f(v[3].x(), v[3].y(), v[3].z()).sub(p0);
				// Facing away from the middle of the cube.
				Vector3f normal = new Vector3f(edgeA).cross(edgeB).normalize();
				Vector3f middle = new Vector3f(p0).add(new Vector3f(edgeA).add(edgeB).mul(0.5F));
				if (normal.dot(new Vector3f(middle).sub(center)) < 0) {
					normal.negate();
				}
				float du1 = v[1].u() - v[0].u();
				float dv1 = v[1].v() - v[0].v();
				float du3 = v[3].u() - v[0].u();
				float dv3 = v[3].v() - v[0].v();
				float det = du1 * dv3 - du3 * dv1;
				if (Math.abs(det) < 1.0e-9F) {
					continue;
				}
				float minU = Math.min(Math.min(v[0].u(), v[1].u()), Math.min(v[2].u(), v[3].u())) * SIZE;
				float maxU = Math.max(Math.max(v[0].u(), v[1].u()), Math.max(v[2].u(), v[3].u())) * SIZE;
				float minV = Math.min(Math.min(v[0].v(), v[1].v()), Math.min(v[2].v(), v[3].v())) * SIZE;
				float maxV = Math.max(Math.max(v[0].v(), v[1].v()), Math.max(v[2].v(), v[3].v())) * SIZE;
				for (int ty = Math.round(minV); ty < Math.round(maxV); ty++) {
					for (int tx = Math.round(minU); tx < Math.round(maxU); tx++) {
						if (tx < 0 || ty < 0 || tx >= SIZE || ty >= SIZE) {
							continue;
						}
						// Where this pixel's middle is on the face.
						float u = (tx + 0.5F) / SIZE - v[0].u();
						float w = (ty + 0.5F) / SIZE - v[0].v();
						float a = (u * dv3 - du3 * w) / det;
						float b = (du1 * w - u * dv1) / det;
						Vector3f position = new Vector3f(p0).add(new Vector3f(edgeA).mul(a)).add(new Vector3f(edgeB).mul(b)).add(pose.x(), pose.y(), pose.z());
						out.add(new Spot(ty * SIZE + tx, anchor, position, normal, depth));
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------- measuring a cosmetic

	/** A cosmetic face's triangle on its body part, standing still, with its texture coordinates. */
	private record Triangle(Vector3f a, Vector3f b, Vector3f c, float[] uv, int texture, float[] bounds) {
	}

	/** The pixels of the layer this cosmetic covers. Background thread. */
	private static long[] measure(CosmeticModel model, Spot[] spots) {
		long[] mask = new long[WORDS];
		Map<Anchor, List<Triangle>> triangles = triangles(model);
		if (triangles.isEmpty()) {
			return mask;
		}
		Alpha[] alpha = alpha(model);
		Vector3f from = new Vector3f();
		Vector3f to = new Vector3f();
		float[] hit = new float[2];
		for (Spot spot : spots) {
			List<Triangle> near = triangles.get(spot.anchor());
			if (near == null) {
				continue;
			}
			// From just under the body's surface to just past the layer pixel.
			from.set(spot.normal()).mul(-(spot.depth() + UNDER)).add(spot.position());
			to.set(spot.normal()).mul(REACH).add(spot.position());
			for (Triangle triangle : near) {
				if (outside(triangle.bounds(), from, to) || !intersects(from, to, triangle, hit)) {
					continue;
				}
				float s = hit[0];
				float t = hit[1];
				float u = triangle.uv()[0] * (1 - s - t) + triangle.uv()[2] * s + triangle.uv()[4] * t;
				float v = triangle.uv()[1] * (1 - s - t) + triangle.uv()[3] * s + triangle.uv()[5] * t;
				Alpha texture = triangle.texture() < alpha.length ? alpha[triangle.texture()] : null;
				if (texture == null || texture.visible(u, v)) {
					mask[spot.texel() >> 6] |= 1L << (spot.texel() & 63);
					break;
				}
			}
		}
		return grow(mask, spots);
	}

	/** Adds the pixels next to covered ones (on the same body part, across the edges of its faces too). */
	private static long[] grow(long[] mask, Spot[] spots) {
		long[] grown = mask.clone();
		for (Spot spot : spots) {
			if (!isSet(mask, spot.texel())) {
				continue;
			}
			for (Spot other : spots) {
				if (other.anchor() == spot.anchor() && !isSet(grown, other.texel()) && other.position().distanceSquared(spot.position()) <= NEXT_TO * NEXT_TO) {
					grown[other.texel() >> 6] |= 1L << (other.texel() & 63);
				}
			}
		}
		return grown;
	}

	private static boolean isSet(long[] mask, int texel) {
		return (mask[texel >> 6] & 1L << (texel & 63)) != 0;
	}

	/** The cosmetic's faces per body part, at rest, in that part's model pixels (as {@link CosmeticRenderer} places them). */
	private static Map<Anchor, List<Triangle>> triangles(CosmeticModel model) {
		Map<Anchor, List<Triangle>> result = new EnumMap<>(Anchor.class);
		List<Bone> bones = model.bones();
		Matrix4f[] matrices = new Matrix4f[bones.size()];
		Quaternionf rotation = new Quaternionf();
		for (Bone bone : bones) {
			Matrix4f matrix = bone.parent() >= 0 && matrices[bone.parent()] != null ? new Matrix4f(matrices[bone.parent()]) : new Matrix4f();
			matrix.translate(bone.pivotX(), bone.pivotY(), bone.pivotZ());
			if (bone.rotX() != 0 || bone.rotY() != 0 || bone.rotZ() != 0) {
				matrix.rotate(rotation.rotationZYX(bone.rotZ(), bone.rotY(), bone.rotX()));
			}
			matrices[bone.index()] = matrix;
			if (!bone.anchor().isBodyPart() || bone.quads().isEmpty()) {
				continue;
			}
			List<Triangle> list = result.computeIfAbsent(bone.anchor(), a -> new ArrayList<>());
			for (Quad quad : bone.quads()) {
				Vector3f[] corners = new Vector3f[4];
				for (int i = 0; i < 4; i++) {
					corners[i] = matrix.transformPosition(quad.positions()[i * 3], quad.positions()[i * 3 + 1], quad.positions()[i * 3 + 2], new Vector3f());
				}
				float[] uv = quad.uvs();
				add(list, corners[0], corners[1], corners[2], new float[] {uv[0], uv[1], uv[2], uv[3], uv[4], uv[5]}, quad.texture());
				add(list, corners[0], corners[2], corners[3], new float[] {uv[0], uv[1], uv[4], uv[5], uv[6], uv[7]}, quad.texture());
			}
		}
		return result;
	}

	private static void add(List<Triangle> list, Vector3f a, Vector3f b, Vector3f c, float[] uv, int texture) {
		if (new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).lengthSquared() < 1.0e-10F) {
			return; // the duplicated corner of a triangle face
		}
		float[] bounds = {
			Math.min(a.x, Math.min(b.x, c.x)), Math.min(a.y, Math.min(b.y, c.y)), Math.min(a.z, Math.min(b.z, c.z)),
			Math.max(a.x, Math.max(b.x, c.x)), Math.max(a.y, Math.max(b.y, c.y)), Math.max(a.z, Math.max(b.z, c.z))
		};
		list.add(new Triangle(a, b, c, uv, texture, bounds));
	}

	private static boolean outside(float[] bounds, Vector3f from, Vector3f to) {
		return Math.max(from.x, to.x) < bounds[0] || Math.min(from.x, to.x) > bounds[3]
			|| Math.max(from.y, to.y) < bounds[1] || Math.min(from.y, to.y) > bounds[4]
			|| Math.max(from.z, to.z) < bounds[2] || Math.min(from.z, to.z) > bounds[5];
	}

	/** Whether the segment crosses the triangle (either side); {@code hit} gets the barycentric coordinates. */
	private static boolean intersects(Vector3f from, Vector3f to, Triangle triangle, float[] hit) {
		Vector3f direction = new Vector3f(to).sub(from);
		Vector3f edge1 = new Vector3f(triangle.b()).sub(triangle.a());
		Vector3f edge2 = new Vector3f(triangle.c()).sub(triangle.a());
		Vector3f p = new Vector3f(direction).cross(edge2);
		float det = edge1.dot(p);
		if (Math.abs(det) < 1.0e-9F) {
			return false;
		}
		float inverse = 1 / det;
		Vector3f offset = new Vector3f(from).sub(triangle.a());
		float s = offset.dot(p) * inverse;
		if (s < 0 || s > 1) {
			return false;
		}
		Vector3f q = offset.cross(edge1);
		float t = direction.dot(q) * inverse;
		if (t < 0 || s + t > 1) {
			return false;
		}
		float along = edge2.dot(q) * inverse;
		if (along < 0 || along > 1) {
			return false;
		}
		hit[0] = s;
		hit[1] = t;
		return true;
	}

	/** Which pixels of a cosmetic texture can be seen (alpha above zero). */
	private record Alpha(int width, int height, boolean[] visible) {
		boolean visible(float u, float v) {
			int x = Math.clamp((int) Math.floor(u * width), 0, width - 1);
			int y = Math.clamp((int) Math.floor(v * height), 0, height - 1);
			return visible[y * width + x];
		}
	}

	private static Alpha[] alpha(CosmeticModel model) {
		Alpha[] result = new Alpha[model.textures().size()];
		for (int i = 0; i < result.length; i++) {
			try (NativeImage image = NativeImage.read(model.textures().get(i).png())) {
				boolean[] visible = new boolean[image.getWidth() * image.getHeight()];
				for (int y = 0; y < image.getHeight(); y++) {
					for (int x = 0; x < image.getWidth(); x++) {
						visible[y * image.getWidth() + x] = image.getPixel(x, y) >>> 24 != 0;
					}
				}
				result[i] = new Alpha(image.getWidth(), image.getHeight(), visible);
			} catch (IOException | RuntimeException e) {
				result[i] = null; // counted as covering everywhere
			}
		}
		return result;
	}
}
