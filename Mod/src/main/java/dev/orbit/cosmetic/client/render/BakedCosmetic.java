package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Quad;
import dev.orbit.cosmetic.common.model.TextureStyles;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A cosmetic ready to draw: textures uploaded to the GPU and geometry flattened into arrays.
 * Created and released on the render thread only.
 */
public final class BakedCosmetic {
	/** Floats per vertex in {@link #meshes}: x, y, z (blocks), u, v, nx, ny, nz. */
	static final int STRIDE = 8;

	private final AssetId id;
	private final CosmeticModel model;
	private final Identifier[] textureIds;
	private final RenderType[] renderTypes;
	private final TextureStyles styles;
	/** The average colour of each style's sample texture (opaque ARGB), for the style buttons. */
	private final int[] styleColors;
	/** [bone][texture] -> packed vertices, or null when the bone has no faces with that texture. */
	final float[][][] meshes;
	/** Bones per anchor, parents before children. */
	final Map<Anchor, int[]> bonesByAnchor = new EnumMap<>(Anchor.class);
	/** Anchors that have at least one face to draw (directly or through descendants). */
	final List<Anchor> drawnAnchors = new ArrayList<>();
	/** Textures drawn with the wearer's skin instead (see TextureData#isSkinSlot), by texture index. */
	private boolean[] skinSlots = new boolean[0];
	/** The parts it can replace as a body part (see BodyParts#valid); computed when first needed. */
	private int bodyParts = -1;
	private boolean released;

	private BakedCosmetic(AssetId id, CosmeticModel model, Identifier[] textureIds, RenderType[] renderTypes, TextureStyles styles, int[] styleColors) {
		this.id = id;
		this.model = model;
		this.textureIds = textureIds;
		this.renderTypes = renderTypes;
		this.styles = styles;
		this.styleColors = styleColors;
		List<Bone> bones = model.bones();
		this.meshes = new float[bones.size()][][];
		int textures = model.textures().size();
		Map<Anchor, List<Integer>> grouped = new EnumMap<>(Anchor.class);
		for (Bone bone : bones) {
			grouped.computeIfAbsent(bone.anchor(), a -> new ArrayList<>()).add(bone.index());
			int[] counts = new int[textures];
			for (Quad quad : bone.quads()) {
				counts[quad.texture()]++;
			}
			float[][] perTexture = new float[textures][];
			int[] fill = new int[textures];
			for (int t = 0; t < textures; t++) {
				if (counts[t] > 0) {
					perTexture[t] = new float[counts[t] * 4 * STRIDE];
				}
			}
			for (Quad quad : bone.quads()) {
				float[] target = perTexture[quad.texture()];
				int offset = fill[quad.texture()];
				for (int v = 0; v < 4; v++) {
					target[offset++] = quad.positions()[v * 3] / 16f;
					target[offset++] = quad.positions()[v * 3 + 1] / 16f;
					target[offset++] = quad.positions()[v * 3 + 2] / 16f;
					target[offset++] = quad.uvs()[v * 2];
					target[offset++] = quad.uvs()[v * 2 + 1];
					target[offset++] = quad.normal()[0];
					target[offset++] = quad.normal()[1];
					target[offset++] = quad.normal()[2];
				}
				fill[quad.texture()] = offset;
			}
			meshes[bone.index()] = perTexture;
		}
		for (Map.Entry<Anchor, List<Integer>> entry : grouped.entrySet()) {
			int[] indices = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
			bonesByAnchor.put(entry.getKey(), indices);
			boolean drawn = false;
			for (int index : indices) {
				if (!bones.get(index).quads().isEmpty()) {
					drawn = true;
					break;
				}
			}
			if (drawn) {
				drawnAnchors.add(entry.getKey());
			}
		}
	}

	/**
	 * Uploads the decoded images and builds the cosmetic. Takes ownership of the images.
	 * Must run on the render thread.
	 */
	static BakedCosmetic create(AssetId id, CosmeticModel model, NativeImage[] images, boolean[] translucent, TextureStyles styles, int[] styleColors) {
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		Identifier[] ids = new Identifier[images.length];
		RenderType[] types = new RenderType[images.length];
		boolean[] skinSlots = skinSlots(model, images);
		for (int i = 0; i < images.length; i++) {
			ids[i] = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "cosmetic/" + id.hex() + "/" + i);
			String label = "Orbit cosmetic " + id.shortHex() + " #" + i;
			textures.register(ids[i], new DynamicTexture(() -> label, images[i]));
			types[i] = translucent[i] ? RenderTypes.entityTranslucent(ids[i]) : RenderTypes.entityCutout(ids[i]);
		}
		BakedCosmetic baked = new BakedCosmetic(id, model, ids, types, styles, styleColors);
		baked.skinSlots = skinSlots;
		return baked;
	}

	/**
	 * Which textures the wearer's skin replaces: named and sized for it (TextureData#isSkinSlot) and empty, so a
	 * file can never carry a skin of its own this way. Checked before the images are uploaded.
	 */
	static boolean[] skinSlots(CosmeticModel model, NativeImage[] images) {
		boolean[] slots = new boolean[images.length];
		for (int i = 0; i < images.length && i < model.textures().size(); i++) {
			NativeImage image = images[i];
			if (image == null || !model.textures().get(i).isSkinSlot() || image.getWidth() != 64 || image.getHeight() != 64) {
				continue;
			}
			boolean empty = true;
			for (int y = 0; y < 64 && empty; y++) {
				for (int x = 0; x < 64 && empty; x++) {
					empty = image.getPixel(x, y) >>> 24 == 0;
				}
			}
			slots[i] = empty;
		}
		return slots;
	}

	/** Whether faces of this texture are drawn with the wearer's skin. */
	boolean isSkinSlot(int texture) {
		return texture >= 0 && texture < skinSlots.length && skinSlots[texture];
	}

	/** The parts this model can replace as a body part (bits of {@link Anchor#ordinal()}). */
	public int bodyParts() {
		if (bodyParts < 0) {
			bodyParts = dev.orbit.cosmetic.common.model.BodyParts.valid(model);
		}
		return bodyParts;
	}

	public AssetId id() {
		return id;
	}

	public CosmeticModel model() {
		return model;
	}

	/** The render type for faces of a texture, drawn in a style (alternate texture). */
	RenderType renderType(int texture, int style) {
		return renderTypes[styles.texture(style, texture)];
	}

	/** The texture drawn for a texture of the model in a style (alternate texture), or null for none. */
	public Identifier texture(int texture, int style) {
		return texture < 0 || texture >= textureIds.length ? null : textureIds[styles.texture(style, texture)];
	}

	/** How many alternate textures the cosmetic has (1 when it has none). */
	public int styleCount() {
		return styles.count();
	}

	/** The colour shown for a style: the average colour of its texture. */
	public int styleColor(int style) {
		return styleColors[Math.clamp(style, 0, styleColors.length - 1)];
	}

	int textureCount() {
		return renderTypes.length;
	}

	public boolean isReleased() {
		return released;
	}

	/** Frees the GPU textures. Must run on the render thread. */
	void release() {
		if (released) {
			return;
		}
		released = true;
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		for (Identifier textureId : textureIds) {
			textures.release(textureId);
		}
	}
}
