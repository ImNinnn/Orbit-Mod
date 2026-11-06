package dev.orbit.cosmetic.common.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.common.util.DataUrls;
import dev.orbit.cosmetic.common.util.PngWriter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generates the starting-point {@code .bbmodel} offered by the Create button.
 *
 * <p>The template contains:
 * <ul>
 *   <li>a locked {@code REFERENCE_PLAYER} group with the vanilla player body, which Orbit never renders;</li>
 *   <li>the six anchor groups at the vanilla pivots, where cosmetic geometry goes;</li>
 *   <li>an example top hat under {@code HEAD} whose cubes have export disabled, so the template itself
 *       renders nothing until the creator adds or enables geometry;</li>
 *   <li>an example {@code IDLE} animation and a blank cosmetic texture.</li>
 * </ul>
 * Format version 4.10 is written because every Blockbench release since 4.10 opens it (newer versions
 * upgrade it on load).
 */
public final class TemplateGenerator {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final int SKIN_HEAD = 0xFFD9A066;
	private static final int SKIN_FACE = 0xFFE8B57E;
	private static final int SKIN_EYES = 0xFF3A6EA5;
	private static final int SKIN_BODY = 0xFF4FA3A5;
	private static final int SKIN_ARMS = 0xFFC98E59;
	private static final int SKIN_LEGS = 0xFF3B4A8C;

	private TemplateGenerator() {
	}

	private record Part(String name, Anchor anchor, float[] from, float[] to, int u, int v, int color) {
	}

	private static final Part[] PARTS = {
		new Part("ref_head", Anchor.HEAD, new float[] {-4, 24, -4}, new float[] {4, 32, 4}, 0, 0, SKIN_HEAD),
		new Part("ref_body", Anchor.BODY, new float[] {-4, 12, -2}, new float[] {4, 24, 2}, 16, 16, SKIN_BODY),
		new Part("ref_right_arm", Anchor.RIGHT_ARM, new float[] {4, 12, -2}, new float[] {8, 24, 2}, 40, 16, SKIN_ARMS),
		new Part("ref_left_arm", Anchor.LEFT_ARM, new float[] {-8, 12, -2}, new float[] {-4, 24, 2}, 32, 48, SKIN_ARMS),
		new Part("ref_right_leg", Anchor.RIGHT_LEG, new float[] {-0.1f, 0, -2}, new float[] {3.9f, 12, 2}, 0, 16, SKIN_LEGS),
		new Part("ref_left_leg", Anchor.LEFT_LEG, new float[] {-3.9f, 0, -2}, new float[] {0.1f, 12, 2}, 16, 48, SKIN_LEGS),
	};

	public static byte[] generate(String modelName) {
		JsonObject root = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("format_version", "4.10");
		meta.addProperty("model_format", "free");
		meta.addProperty("box_uv", false);
		root.add("meta", meta);
		root.addProperty("name", modelName);
		root.addProperty("model_identifier", "");
		root.add("visible_box", array(1, 1, 0));
		JsonObject resolution = new JsonObject();
		resolution.addProperty("width", 64);
		resolution.addProperty("height", 64);
		root.add("resolution", resolution);

		JsonArray elements = new JsonArray();
		JsonArray outliner = new JsonArray();

		// Reference player: shown in Blockbench, skipped by Orbit because of the group name.
		JsonObject reference = group(BBModelParser.REFERENCE_GROUP, new float[] {0, 0, 0});
		JsonArray referenceChildren = reference.getAsJsonArray("children");
		for (Part part : PARTS) {
			JsonObject partGroup = group(part.name(), new float[] {part.anchor().pivotX(), part.anchor().pivotY(), part.anchor().pivotZ()});
			JsonObject cube = boxCube(part.name(), part.from(), part.to(), part.u(), part.v(), 0);
			cube.addProperty("locked", true);
			cube.addProperty("export", false);
			elements.add(cube);
			partGroup.getAsJsonArray("children").add(cube.get("uuid").getAsString());
			referenceChildren.add(partGroup);
		}
		outliner.add(reference);

		// Anchor groups. Geometry placed inside them follows the matching body part.
		String hatGroupUuid = null;
		for (Anchor anchor : Anchor.values()) {
			if (!anchor.isBodyPart()) {
				continue;
			}
			JsonObject anchorGroup = group(anchor.name(), new float[] {anchor.pivotX(), anchor.pivotY(), anchor.pivotZ()});
			if (anchor == Anchor.HEAD) {
				JsonObject hat = group("example_top_hat", new float[] {0, 32, 0});
				hatGroupUuid = hat.get("uuid").getAsString();
				JsonObject brim = cosmeticCube("example_hat_brim", new float[] {-5, 32, -5}, new float[] {5, 33, 5});
				JsonObject crown = cosmeticCube("example_hat_crown", new float[] {-3.5f, 33, -3.5f}, new float[] {3.5f, 38, 3.5f});
				elements.add(brim);
				elements.add(crown);
				hat.getAsJsonArray("children").add(brim.get("uuid").getAsString());
				hat.getAsJsonArray("children").add(crown.get("uuid").getAsString());
				anchorGroup.getAsJsonArray("children").add(hat);
			}
			outliner.add(anchorGroup);
		}

		root.add("elements", elements);
		root.add("outliner", outliner);

		JsonArray textures = new JsonArray();
		textures.add(texture("reference_skin.png", 0, 64, 64, referenceSkin()));
		textures.add(texture("cosmetic_texture.png", 1, 32, 32, cosmeticTexture()));
		root.add("textures", textures);

		JsonArray animations = new JsonArray();
		animations.add(exampleIdle(hatGroupUuid));
		root.add("animations", animations);

		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The starting point for emotes: the player's body in the emote group tree (ROOT, then the legs and UPPERHALF,
	 * which holds the body, arms and head), each part holding its piece of the player body (Export off, so it is
	 * never drawn in game), and an example {@code PLAY} animation (a wave).
	 */
	public static byte[] generateEmote(String modelName) {
		JsonObject root = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("format_version", "4.10");
		meta.addProperty("model_format", "free");
		meta.addProperty("box_uv", false);
		root.add("meta", meta);
		root.addProperty("name", modelName);
		root.addProperty("model_identifier", "");
		root.add("visible_box", array(1, 1, 0));
		JsonObject resolution = new JsonObject();
		resolution.addProperty("width", 64);
		resolution.addProperty("height", 64);
		root.add("resolution", resolution);

		JsonArray elements = new JsonArray();
		JsonArray outliner = new JsonArray();
		JsonObject rootGroup = group("ROOT", new float[] {0, 0, 0});
		JsonObject upper = group("UPPERHALF", new float[] {0, 12, 0});
		String rightArm = null;
		for (Anchor anchor : new Anchor[] {Anchor.LEFT_LEG, Anchor.RIGHT_LEG, Anchor.BODY, Anchor.LEFT_ARM, Anchor.RIGHT_ARM, Anchor.HEAD}) {
			Part part = part(anchor);
			JsonObject partGroup = group(anchor.name(), new float[] {anchor.pivotX(), anchor.pivotY(), anchor.pivotZ()});
			JsonObject cube = boxCube(part.name(), part.from(), part.to(), part.u(), part.v(), 0);
			cube.addProperty("locked", true);
			cube.addProperty("export", false);
			elements.add(cube);
			partGroup.getAsJsonArray("children").add(cube.get("uuid").getAsString());
			boolean leg = anchor == Anchor.LEFT_LEG || anchor == Anchor.RIGHT_LEG;
			(leg ? rootGroup : upper).getAsJsonArray("children").add(partGroup);
			if (anchor == Anchor.RIGHT_ARM) {
				rightArm = partGroup.get("uuid").getAsString();
			}
		}
		rootGroup.getAsJsonArray("children").add(upper);
		outliner.add(rootGroup);
		root.add("elements", elements);
		root.add("outliner", outliner);

		JsonArray textures = new JsonArray();
		textures.add(texture("reference_skin.png", 0, 64, 64, referenceSkin()));
		root.add("textures", textures);

		JsonArray animations = new JsonArray();
		JsonObject wave = animation("PLAY", "once", 2, "RIGHT_ARM", rightArm);
		JsonArray keyframes = wave.getAsJsonObject("animators").getAsJsonObject(rightArm).getAsJsonArray("keyframes");
		// Raise the arm sideways (Z is stored the same way in every format version) and wave it.
		float[][] frames = {{0, 0}, {0.3f, 150}, {0.6f, 120}, {0.9f, 150}, {1.2f, 120}, {1.5f, 150}, {2, 0}};
		for (float[] frame : frames) {
			keyframes.add(rotationKeyframe(frame[0], frame[1]));
		}
		animations.add(wave);
		root.add("animations", animations);
		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	private static Part part(Anchor anchor) {
		for (Part part : PARTS) {
			if (part.anchor() == anchor) {
				return part;
			}
		}
		throw new IllegalArgumentException(anchor.name());
	}

	/** Human-readable guide written next to generated emote templates. */
	/**
	 * The starting point for body parts: the six parts of the player as real geometry, mapped like a skin onto an
	 * empty 64x64 texture named {@code PLAYER}, which Orbit replaces with the wearer's skin. Unchanged, it looks
	 * exactly like the player; reshape and repaint any part to replace it.
	 */
	public static byte[] generateBodyPart(String modelName) {
		JsonObject root = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("format_version", "4.10");
		meta.addProperty("model_format", "free");
		meta.addProperty("box_uv", false);
		root.add("meta", meta);
		root.addProperty("name", modelName);
		root.addProperty("model_identifier", "");
		root.add("visible_box", array(1, 1, 0));
		JsonObject resolution = new JsonObject();
		resolution.addProperty("width", 64);
		resolution.addProperty("height", 64);
		root.add("resolution", resolution);

		JsonArray elements = new JsonArray();
		JsonArray outliner = new JsonArray();
		for (Anchor anchor : BodyParts.PARTS) {
			Part part = part(anchor);
			JsonObject partGroup = group(anchor.name(), new float[] {anchor.pivotX(), anchor.pivotY(), anchor.pivotZ()});
			JsonObject cube = boxCube(anchor.name().toLowerCase(java.util.Locale.ROOT), part.from(), part.to(), part.u(), part.v(), 0);
			cube.addProperty("export", true);
			elements.add(cube);
			partGroup.getAsJsonArray("children").add(cube.get("uuid").getAsString());
			outliner.add(partGroup);
		}
		root.add("elements", elements);
		root.add("outliner", outliner);

		JsonArray textures = new JsonArray();
		textures.add(texture("PLAYER", 0, 64, 64, new int[64 * 64]));
		root.add("textures", textures);
		root.add("animations", new JsonArray());
		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	public static String bodyPartGuide() {
		return """
			Orbit body part template
			========================

			Open the .bbmodel file in Blockbench (https://www.blockbench.net).

			A body part replaces parts of the player: the head, the body, an arm, a leg, or all of them.

			HEAD, BODY,        One group per part, with its piece of the player inside. Every group that
			RIGHT_ARM,         has something in it replaces that part of the player; delete a group's
			LEFT_ARM,          contents to keep the player's own part there. Names are exact. Keep the
			RIGHT_LEG,         group origins where they are: they are the player's joints, so the part
			LEFT_LEG           moves like the real one.

			Size               Each part may be up to 50% bigger than the player's own part (in every
			                   direction, around its centre). A part that is bigger, or empty, is not used:
			                   the player's own part shows instead.

			PLAYER texture     The template is mapped onto an empty 64x64 texture named PLAYER (SKIN works
			                   too), which Orbit replaces with the skin of whoever wears it: unchanged, the
			                   template looks exactly like the player. Paint other textures of your own for
			                   new looks (a pirate arm, a robot leg), or keep faces on PLAYER to show the
			                   skin. Keep PLAYER empty: anything painted on it makes it a normal texture.

			Settings           In game, the body part's settings choose which of its parts are used, and
			                   any group can make particles. Body parts have no animations: they move with
			                   the player.

			This file is already in its own folder in Orbit/Body Parts and shows up in the Orbit menu
			(Body Parts). The folder name is its name: rename the folder (and the file) to rename it.
			""";
	}

	public static String emoteGuide() {
		return """
			Orbit emote template
			====================

			Open the .bbmodel file in Blockbench (https://www.blockbench.net).

			ROOT               Moves the whole player: animate it to jump, spin or slide.
			  LEFT_LEG,        The legs.
			  RIGHT_LEG
			  UPPERHALF        Everything but the legs: animate it to bend at the hips.
			    BODY, HEAD,    The body, the head and the arms.
			    LEFT_ARM,
			    RIGHT_ARM

			                   Names are exact. Keep the group origins where they are: they sit on the joints
			                   of the player. The player body inside the groups has Export turned off: it is
			                   only a guide and is never drawn. Geometry you add (with Export on) is drawn
			                   while the emote plays, for example a prop held in a hand.

			Animations         PLAY plays once. LOOP (or IDLE) loops until the player crouches (or moves, for
			                   Standard emotes). With both, PLAY plays first, then LOOP loops.
			                   For emotes you can walk with: WALKING, SPRINTING and BACKWARDS loop while the
			                   player moves that way (WALKING also while sprinting when there is no
			                   SPRINTING); an emote with them is always Mobile. No other animations.

			Particles          Any group can make particles, from the emote's Settings in game: they appear
			                   where the group is. Scale the group in an animation to make fewer (size 0 makes
			                   none). Add a texture for them, for example particle.png.

			This file is already in its own folder in Orbit/Emotes and shows up in the Orbit menu (Emotes).
			The folder name is the emote's name: rename the folder (and the file) to rename it, for example
			big_wave/big_wave.bbmodel shows as "big wave".
			""";
	}

	/** Human-readable guide written next to generated templates. */
	public static String guide() {
		return """
			Orbit Cosmetic template
			=======================

			Open the .bbmodel file in Blockbench (https://www.blockbench.net).

			REFERENCE_PLAYER   A locked copy of the player body. It is only a guide and is never rendered
			                   in game. Do not put your own geometry inside it.

			HEAD, BODY,        Anchor groups. Everything inside them follows that body part when the player
			LEFT_ARM,          moves, including custom player animations from resource packs. Names are
			RIGHT_ARM,         exact and case-sensitive. Keep the group origins where they are: they sit on
			LEFT_LEG,          the joints of the reference player.
			RIGHT_LEG

			Anything else      Geometry outside all anchor groups is "player-root" geometry. It follows the
			                   player's position and body rotation only (useful for auras or pets).

			example_top_hat    An example under HEAD. Its cubes have Export turned off, so they are not
			                   rendered. Turn Export on for them (or delete them) when you build your own.

			Textures           Paint your cosmetic on cosmetic_texture.png or add your own textures.
			                   Transparent pixels are supported. Save with textures embedded.
			                   A texture named PLAYER or SKIN, 64x64 and left empty, is drawn with the
			                   wearer's skin (map faces onto it like a skin).

			Animations         Animations named IDLE, WALK, SPRINT, BACKWARDS, JUMP, FALL, SNEAK, CROUCH,
			                   SWIM, FLY, DAMAGE (once when hurt), ATTACK (once when hitting something)
			                   or MINE (while breaking a block) are assigned to that player state
			                   automatically. Any other name can be assigned in game from the cosmetic's
			                   Animations screen.

			Particles          Any group can make particles: add it in game, in the cosmetic's Particles tab.
			                   They appear where the group is, so animating it moves them; scaling it in an
			                   animation makes fewer (size 0 makes none). Add a texture for them, for example
			                   particle.png (or particle_t1.png and particle_t2.png for alternate textures).

			This file is already in its own folder in Orbit/Cosmetics and shows up in the Orbit menu. The
			folder name is the cosmetic's name: rename the folder (and the file) to rename it, for example
			top_hat/top_hat.bbmodel shows as "top hat".
			""";
	}

	private static JsonObject group(String name, float[] origin) {
		JsonObject group = new JsonObject();
		group.addProperty("name", name);
		group.add("origin", array(origin[0], origin[1], origin[2]));
		group.add("rotation", array(0, 0, 0));
		group.addProperty("color", 0);
		group.addProperty("uuid", uuid("group:" + name));
		group.addProperty("export", true);
		group.addProperty("mirror_uv", false);
		group.addProperty("isOpen", true);
		group.addProperty("locked", false);
		group.addProperty("visibility", true);
		group.addProperty("autouv", 0);
		group.add("children", new JsonArray());
		return group;
	}

	private static JsonObject baseCube(String name, float[] from, float[] to) {
		JsonObject cube = new JsonObject();
		cube.addProperty("name", name);
		cube.addProperty("type", "cube");
		cube.addProperty("uuid", uuid("cube:" + name));
		cube.add("from", array(from[0], from[1], from[2]));
		cube.add("to", array(to[0], to[1], to[2]));
		cube.add("origin", array(0, 0, 0));
		cube.add("rotation", array(0, 0, 0));
		cube.addProperty("autouv", 0);
		cube.addProperty("color", 0);
		cube.addProperty("rescale", false);
		cube.addProperty("visibility", true);
		cube.addProperty("inflate", 0);
		return cube;
	}

	private static JsonObject boxCube(String name, float[] from, float[] to, int u, int v, int texture) {
		JsonObject cube = baseCube(name, from, to);
		cube.addProperty("box_uv", true);
		cube.add("uv_offset", array(u, v));
		float w = Math.round(to[0] - from[0]);
		float h = Math.round(to[1] - from[1]);
		float d = Math.round(to[2] - from[2]);
		JsonObject faces = new JsonObject();
		faces.add("north", face(u + d, v + d, u + d + w, v + d + h, texture));
		faces.add("east", face(u, v + d, u + d, v + d + h, texture));
		faces.add("south", face(u + d * 2 + w, v + d, u + d * 2 + w * 2, v + d + h, texture));
		faces.add("west", face(u + d + w, v + d, u + d * 2 + w, v + d + h, texture));
		faces.add("up", face(u + d + w, v + d, u + d, v, texture));
		faces.add("down", face(u + d + w * 2, v, u + d + w, v + d, texture));
		cube.add("faces", faces);
		return cube;
	}

	private static JsonObject cosmeticCube(String name, float[] from, float[] to) {
		JsonObject cube = baseCube(name, from, to);
		cube.addProperty("box_uv", false);
		cube.addProperty("export", false);
		JsonObject faces = new JsonObject();
		for (String side : new String[] {"north", "east", "south", "west", "up", "down"}) {
			faces.add(side, face(0, 0, 16, 16, 1));
		}
		cube.add("faces", faces);
		return cube;
	}

	private static JsonObject face(float u1, float v1, float u2, float v2, int texture) {
		JsonObject face = new JsonObject();
		face.add("uv", array(u1, v1, u2, v2));
		face.addProperty("texture", texture);
		return face;
	}

	private static JsonObject texture(String name, int id, int width, int height, int[] pixels) {
		JsonObject texture = new JsonObject();
		texture.addProperty("path", "");
		texture.addProperty("name", name);
		texture.addProperty("folder", "");
		texture.addProperty("namespace", "");
		texture.addProperty("id", String.valueOf(id));
		texture.addProperty("width", width);
		texture.addProperty("height", height);
		texture.addProperty("uv_width", width);
		texture.addProperty("uv_height", height);
		texture.addProperty("particle", false);
		texture.addProperty("render_mode", "default");
		texture.addProperty("render_sides", "auto");
		texture.addProperty("visible", true);
		texture.addProperty("internal", true);
		texture.addProperty("saved", false);
		texture.addProperty("uuid", uuid("texture:" + name));
		texture.addProperty("source", DataUrls.encodePng(PngWriter.write(width, height, pixels)));
		return texture;
	}

	private static JsonObject exampleIdle(String boneUuid) {
		JsonObject animation = animation("IDLE", "loop", 2, "example_top_hat", boneUuid);
		JsonArray keyframes = animation.getAsJsonObject("animators").getAsJsonObject(boneUuid).getAsJsonArray("keyframes");
		// Z rotation is stored the same way in every format version, so this sway is unambiguous.
		keyframes.add(rotationKeyframe(0, 0));
		keyframes.add(rotationKeyframe(1, 6));
		keyframes.add(rotationKeyframe(2, 0));
		return animation;
	}

	private static JsonObject animation(String name, String loop, float length, String boneName, String boneUuid) {
		JsonObject animation = new JsonObject();
		animation.addProperty("uuid", uuid("animation:" + name));
		animation.addProperty("name", name);
		animation.addProperty("loop", loop);
		animation.addProperty("override", false);
		animation.addProperty("length", length);
		animation.addProperty("snapping", 20);
		animation.addProperty("selected", false);
		animation.addProperty("anim_time_update", "");
		animation.addProperty("blend_weight", "");
		animation.addProperty("start_delay", "");
		animation.addProperty("loop_delay", "");
		JsonObject animators = new JsonObject();
		JsonObject animator = new JsonObject();
		animator.addProperty("name", boneName);
		animator.addProperty("type", "bone");
		animator.add("keyframes", new JsonArray());
		animators.add(boneUuid, animator);
		animation.add("animators", animators);
		return animation;
	}

	private static JsonObject rotationKeyframe(float time, float z) {
		JsonObject keyframe = new JsonObject();
		keyframe.addProperty("channel", "rotation");
		JsonArray points = new JsonArray();
		JsonObject point = new JsonObject();
		point.addProperty("x", "0");
		point.addProperty("y", "0");
		point.addProperty("z", String.valueOf(z));
		points.add(point);
		keyframe.add("data_points", points);
		keyframe.addProperty("uuid", uuid("keyframe:" + time + ":" + z));
		keyframe.addProperty("time", time);
		keyframe.addProperty("color", -1);
		keyframe.addProperty("interpolation", "catmullrom");
		return keyframe;
	}

	private static int[] referenceSkin() {
		int[] pixels = new int[64 * 64];
		for (Part part : PARTS) {
			int w = Math.round(part.to()[0] - part.from()[0]);
			int h = Math.round(part.to()[1] - part.from()[1]);
			int d = Math.round(part.to()[2] - part.from()[2]);
			fill(pixels, part.u() + d, part.v(), w * 2, d, darker(part.color()));
			fill(pixels, part.u(), part.v() + d, d * 2 + w * 2, h, part.color());
			// The front (north face) is lighter so the facing direction is obvious in Blockbench.
			fill(pixels, part.u() + d, part.v() + d, w, h, part.anchor() == Anchor.HEAD ? SKIN_FACE : lighter(part.color()));
		}
		// Eyes on the face.
		fill(pixels, 9, 12, 2, 1, SKIN_EYES);
		fill(pixels, 13, 12, 2, 1, SKIN_EYES);
		return pixels;
	}

	private static int[] cosmeticTexture() {
		int[] pixels = new int[32 * 32];
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) {
				boolean border = x == 0 || y == 0 || x == 15 || y == 15;
				pixels[y * 32 + x] = x < 16 && y < 16 ? (border ? 0xFF2B2B33 : 0xFF45454F) : 0x00000000;
			}
		}
		return pixels;
	}

	private static void fill(int[] pixels, int x, int y, int w, int h, int color) {
		for (int j = y; j < y + h && j < 64; j++) {
			for (int i = x; i < x + w && i < 64; i++) {
				pixels[j * 64 + i] = color;
			}
		}
	}

	private static int darker(int color) {
		return scale(color, 0.8f);
	}

	private static int lighter(int color) {
		return scale(color, 1.15f);
	}

	private static int scale(int color, float factor) {
		int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * factor));
		int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * factor));
		int b = Math.min(255, Math.round((color & 0xFF) * factor));
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static JsonArray array(float... values) {
		JsonArray array = new JsonArray();
		for (float value : values) {
			array.add(value);
		}
		return array;
	}

	private static String uuid(String seed) {
		return UUID.nameUUIDFromBytes(("orbit-template:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
	}
}
