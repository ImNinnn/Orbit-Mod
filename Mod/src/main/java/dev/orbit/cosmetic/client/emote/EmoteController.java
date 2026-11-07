package dev.orbit.cosmetic.client.emote;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.library.EmoteLibrary;
import dev.orbit.cosmetic.common.emote.EmoteMode;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/**
 * The local player's emote. Starting one switches to the third-person camera (and back when it ends). Crouching
 * stops any emote; moving or jumping stops Standard emotes, while Mobile emotes keep playing.
 */
public final class EmoteController {
	private final OrbitClient orbit;
	private EmoteLibrary.LocalEmote playing;
	private EmoteMode mode = EmoteMode.STANDARD;
	private int seq;
	/** The camera before the emote switched it, or null if it did not. */
	private CameraType cameraBefore;
	/** Ticks after starting during which held keys do not cancel yet (the key that opened the wheel, for example). */
	private int grace;

	public EmoteController(OrbitClient orbit) {
		this.orbit = orbit;
	}

	public EmoteLibrary.LocalEmote playing() {
		return playing;
	}

	public boolean isPlaying() {
		return playing != null;
	}

	/** Plays an emote on the local player. Returns false when there is no player (menus) or the emote is not ready. */
	public boolean play(EmoteLibrary.LocalEmote emote) {
		Minecraft minecraft = Minecraft.getInstance();
		Wearer wearer = orbit.localWearer();
		if (minecraft.player == null || wearer == null || emote.asset() == null) {
			return false;
		}
		playing = emote;
		mode = orbit.emotes().mode(emote);
		seq = (seq + 1) & 0x7FFFFFFF;
		grace = 5;
		orbit.registry().prepare(emote.asset(), emote.file().model());
		wearer.setEmote(new Wearer.Emote(emote.asset(), mode == EmoteMode.MOBILE, seq, emote.file().particles(), orbit.clockTime(), 0));
		if (minecraft.options.getCameraType().isFirstPerson()) {
			cameraBefore = minecraft.options.getCameraType();
			minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		orbit.backend().publishSoon();
		return true;
	}

	public void stop() {
		if (playing == null) {
			return;
		}
		playing = null;
		Wearer wearer = orbit.localWearer();
		if (wearer != null) {
			wearer.setEmote(null);
		}
		Minecraft minecraft = Minecraft.getInstance();
		// Only undo our own camera change: if the player picked another view meanwhile, keep it.
		if (cameraBefore != null && minecraft.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
			minecraft.options.setCameraType(cameraBefore);
		}
		cameraBefore = null;
		orbit.backend().publishSoon();
	}

	/** Ends the emote when it finished, the player crouches, or (Standard emotes) moves. */
	public void tick(Minecraft minecraft) {
		if (playing == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		Wearer wearer = orbit.localWearer();
		if (player == null || wearer == null || wearer.emote() == null || !player.isAlive()) {
			stop();
			return;
		}
		if (grace > 0) {
			grace--;
			return;
		}
		Options options = minecraft.options;
		boolean crouching = options.keyShift.isDown() || player.isShiftKeyDown();
		boolean moving = options.keyUp.isDown() || options.keyDown.isDown() || options.keyLeft.isDown() || options.keyRight.isDown() || options.keyJump.isDown();
		if (crouching || mode == EmoteMode.STANDARD && moving || player.isPassenger() || player.isFallFlying() || player.isVisuallySwimming()) {
			stop();
		}
	}

	/** What other players should see, or null. */
	public EmoteSignal signal() {
		return playing == null || playing.asset() == null ? null : new EmoteSignal(playing.asset().hex(), mode == EmoteMode.MOBILE, seq, playing.file().particles());
	}

	/** The playing emote's file, for uploading it to Orbit Cloud. */
	public byte[] fileBytes() {
		return playing == null ? null : playing.file().bytes();
	}
}
