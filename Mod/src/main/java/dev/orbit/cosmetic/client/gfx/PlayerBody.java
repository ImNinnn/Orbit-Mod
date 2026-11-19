package dev.orbit.cosmetic.client.gfx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.mixin.ModelPartAccessor;
import dev.orbit.cosmetic.client.render.VanillaPlayerModels;
import dev.orbit.cosmetic.common.model.Anchor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The body of a player model being drawn: where each body part ({@link Anchor}) is, its pose, whether it shows, and
 * its boxes. Cosmetics hang from it, emotes pose it, body parts hide its parts. Player models are built differently
 * over the Minecraft versions (the root part, the poses, the generic types), so each version provides this class.
 * <p>
 * Part transforms are nine numbers: position x, y, z (model pixels), rotation x, y, z (radians, applied Z then Y then
 * X) and scale x, y, z.
 */
public final class PlayerBody {
	private static final Map<ModelPart, Map<ModelPart, List<ModelPart>>> PATHS = Collections.synchronizedMap(new WeakHashMap<>());

	private final HumanoidModel<?> model;

	private PlayerBody(HumanoidModel<?> model) {
		this.model = model;
	}

	/** Version-specific code only: the body of Minecraft's model. */
	public static PlayerBody of(HumanoidModel<?> model) {
		return new PlayerBody(model);
	}

	/** Minecraft's own model, for version-specific code. */
	public HumanoidModel<?> raw() {
		return model;
	}

	// ---------------------------------------------------------------- where the parts are

	/**
	 * Moves a pose stack (positioned where the model is drawn from) onto a body part as the model is drawn now.
	 * <p>
	 * Vanilla and model replacement mods (Entity Model Features with packs such as Fresh Animations) pose the head,
	 * body, arm and leg parts before render layers run, and EMF applies its own pivots in
	 * {@link ModelPart#translateAndRotate}. Following the chain of parts from the model root therefore makes cosmetics
	 * follow custom animations and proportions without depending on any mod's API. A part that cannot be found in
	 * the hierarchy (renamed or detached) gets its own transform only, which is what vanilla does.
	 */
	public void moveTo(Anchor anchor, PoseStack poseStack) {
		ModelPart root = model.root();
		ModelPart target = part(anchor);
		if (target == root) {
			root.translateAndRotate(poseStack);
			return;
		}
		List<ModelPart> path = path(root, target);
		if (path == null) {
			root.translateAndRotate(poseStack);
			target.translateAndRotate(poseStack);
			return;
		}
		for (ModelPart part : path) {
			part.translateAndRotate(poseStack);
		}
	}

	/** Moves a pose stack by a body part's own transform only (first-person arms, drawn without the rest of the body). */
	public void moveToAlone(Anchor anchor, PoseStack poseStack) {
		part(anchor).translateAndRotate(poseStack);
	}

	/**
	 * Every box of the model as drawn now (the pose stack positioned where the model is drawn from): the visitor gets
	 * each box's transform and its two opposite corners, in model pixels.
	 */
	public void boxes(PoseStack poseStack, BoxVisitor visitor) {
		model.root().visit(poseStack, (pose, path, index, cube) -> visitor.box(pose.pose(), cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ));
	}

	@FunctionalInterface
	public interface BoxVisitor {
		void box(Matrix4f pose, float x0, float y0, float z0, float x1, float y1, float z1);
	}

	// ---------------------------------------------------------------- poses

	/** A body part's transform now (nine numbers, see the class description) into {@code out}. */
	public void transform(Anchor anchor, float[] out) {
		ModelPart part = part(anchor);
		out[0] = part.x;
		out[1] = part.y;
		out[2] = part.z;
		out[3] = part.xRot;
		out[4] = part.yRot;
		out[5] = part.zRot;
		out[6] = part.xScale;
		out[7] = part.yScale;
		out[8] = part.zScale;
	}

	public void setTransform(Anchor anchor, float[] value) {
		ModelPart part = part(anchor);
		part.x = value[0];
		part.y = value[1];
		part.z = value[2];
		part.xRot = value[3];
		part.yRot = value[4];
		part.zRot = value[5];
		part.xScale = value[6];
		part.yScale = value[7];
		part.zScale = value[8];
	}

	/** A body part's transform when the model is standing still (its rest pose) into {@code out}. */
	public void rest(Anchor anchor, float[] out) {
		PartPose rest = part(anchor).getInitialPose();
		out[0] = rest.x();
		out[1] = rest.y();
		out[2] = rest.z();
		out[3] = rest.xRot();
		out[4] = rest.yRot();
		out[5] = rest.zRot();
		out[6] = rest.xScale();
		out[7] = rest.yScale();
		out[8] = rest.zScale();
	}

	/** Whether a body part (and its skin layer, which goes with it) is drawn. */
	public boolean isVisible(Anchor anchor) {
		return part(anchor).visible;
	}

	public void setVisible(Anchor anchor, boolean visible) {
		part(anchor).visible = visible;
	}

	// ---------------------------------------------------------------- the skin's outer layer

	/**
	 * One face of the skin's outer layer (hat, jacket, sleeves, trouser legs) of the vanilla model: the body part it
	 * belongs to, the middle of its box, and its four corners as x, y, z (model pixels, in the layer part's frame) and
	 * u, v (0 to 1 on the skin). {@code offset} moves the layer part's frame onto the body part's.
	 */
	public record LayerFace(Anchor anchor, Vector3f boxCenter, float[] corners, Vector3f offset) {
	}

	/** The faces of the vanilla model's outer layer, with wide or slim arms. */
	public static List<LayerFace> skinLayer(boolean slim) {
		PlayerModel model = VanillaPlayerModels.model(slim);
		List<LayerFace> faces = new ArrayList<>();
		addLayer(model.hat, Anchor.HEAD, faces);
		addLayer(model.jacket, Anchor.BODY, faces);
		addLayer(model.leftSleeve, Anchor.LEFT_ARM, faces);
		addLayer(model.rightSleeve, Anchor.RIGHT_ARM, faces);
		addLayer(model.leftPants, Anchor.LEFT_LEG, faces);
		addLayer(model.rightPants, Anchor.RIGHT_LEG, faces);
		return faces;
	}

	private static void addLayer(ModelPart layer, Anchor anchor, List<LayerFace> out) {
		PartPose pose = layer.getInitialPose();
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) layer).orbit$cubes()) {
			Vector3f center = new Vector3f((cube.minX + cube.maxX) / 2, (cube.minY + cube.maxY) / 2, (cube.minZ + cube.maxZ) / 2);
			for (ModelPart.Polygon polygon : cube.polygons) {
				ModelPart.Vertex[] v = polygon.vertices();
				if (v.length < 4) {
					continue;
				}
				float[] corners = new float[20];
				for (int i = 0; i < 4; i++) {
					//? if >=1.21.9 {
					corners[i * 5] = v[i].x();
					corners[i * 5 + 1] = v[i].y();
					corners[i * 5 + 2] = v[i].z();
					//?} else {
					/*corners[i * 5] = v[i].pos().x();
					corners[i * 5 + 1] = v[i].pos().y();
					corners[i * 5 + 2] = v[i].pos().z();
					*///?}
					corners[i * 5 + 3] = v[i].u();
					corners[i * 5 + 4] = v[i].v();
				}
				// The layer part sits where its rest pose puts it on the body part.
				out.add(new LayerFace(anchor, center, corners, new Vector3f(pose.x(), pose.y(), pose.z())));
			}
		}
	}

	// ---------------------------------------------------------------- the parts

	private ModelPart part(Anchor anchor) {
		return switch (anchor) {
			case HEAD -> model.head;
			case BODY -> model.body;
			case LEFT_ARM -> model.leftArm;
			case RIGHT_ARM -> model.rightArm;
			case LEFT_LEG -> model.leftLeg;
			case RIGHT_LEG -> model.rightLeg;
			case ROOT -> model.root();
		};
	}

	private static List<ModelPart> path(ModelPart root, ModelPart target) {
		Map<ModelPart, List<ModelPart>> cache = PATHS.computeIfAbsent(root, r -> Collections.synchronizedMap(new WeakHashMap<>()));
		List<ModelPart> cached = cache.get(target);
		if (cached != null && isStillValid(cached)) {
			return cached;
		}
		List<ModelPart> found = new ArrayList<>();
		found.add(root);
		if (!search(root, target, found, 0)) {
			return null;
		}
		List<ModelPart> path = List.copyOf(found);
		cache.put(target, path);
		return path;
	}

	private static boolean search(ModelPart current, ModelPart target, List<ModelPart> path, int depth) {
		if (current == target) {
			return true;
		}
		if (depth > 32) {
			return false;
		}
		for (ModelPart child : ((ModelPartAccessor) (Object) current).orbit$children().values()) {
			path.add(child);
			if (search(child, target, path, depth + 1)) {
				return true;
			}
			path.remove(path.size() - 1);
		}
		return false;
	}

	/** Model replacement mods can rebuild hierarchies on resource reload; each link is checked again. */
	private static boolean isStillValid(List<ModelPart> path) {
		for (int i = 0; i + 1 < path.size(); i++) {
			if (!((ModelPartAccessor) (Object) path.get(i)).orbit$children().containsValue(path.get(i + 1))) {
				return false;
			}
		}
		return true;
	}
}
