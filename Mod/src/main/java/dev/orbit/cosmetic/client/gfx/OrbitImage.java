package dev.orbit.cosmetic.client.gfx;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * A picture in memory that Orbit reads or changes pixel by pixel: a screenshot being edited, a cosmetic's texture
 * before it is uploaded, a skin being masked. Pixels are always ARGB here (Minecraft's own image class changed its
 * pixel order over the versions), and the picture is freed with {@link #close} once no texture owns it.
 * <p>
 * Any thread; only uploading it ({@link OrbitTextures}) needs the render thread.
 */
public final class OrbitImage implements AutoCloseable {
	private final NativeImage image;

	private OrbitImage(NativeImage image) {
		this.image = image;
	}

	/** Version-specific code only: wraps Minecraft's image (the new object owns it). */
	public static OrbitImage of(NativeImage image) {
		return new OrbitImage(image);
	}

	/** A picture of this size, every pixel transparent. */
	public static OrbitImage create(int width, int height) {
		return new OrbitImage(new NativeImage(width, height, true));
	}

	/** Reads a PNG. */
	public static OrbitImage read(InputStream in) throws IOException {
		return new OrbitImage(NativeImage.read(in));
	}

	public static OrbitImage read(byte[] png) throws IOException {
		return new OrbitImage(NativeImage.read(png));
	}

	/** Minecraft's own image, for version-specific code. */
	public NativeImage raw() {
		return image;
	}

	public int width() {
		return image.getWidth();
	}

	public int height() {
		return image.getHeight();
	}

	/** The pixel at (x, y), as ARGB. */
	public int pixel(int x, int y) {
		//? if >=1.21.2 {
		return image.getPixel(x, y);
		//?} else {
		/*// Before 1.21.2 pixels are read as ABGR.
		return swapRedBlue(image.getPixelRGBA(x, y));
		*///?}
	}

	public void setPixel(int x, int y, int argb) {
		//? if >=1.21.2 {
		image.setPixel(x, y, argb);
		//?} else {
		/*image.setPixelRGBA(x, y, swapRedBlue(argb));
		*///?}
	}

	//? if <1.21.2 {
	/*/^* ARGB to ABGR and back. ^/
	private static int swapRedBlue(int color) {
		return color & 0xFF00FF00 | color >> 16 & 0xFF | (color & 0xFF) << 16;
	}
	*///?}

	/** A separate picture with the same pixels. */
	public OrbitImage copy() {
		OrbitImage copy = new OrbitImage(new NativeImage(width(), height(), false));
		copy.image.copyFrom(image);
		return copy;
	}

	/** Copies the pixels of a picture of the same size into this one. */
	public void copyFrom(OrbitImage source) {
		image.copyFrom(source.image);
	}

	/** Saves it as a PNG file. */
	public void writePng(Path file) throws IOException {
		image.writeToFile(file);
	}

	@Override
	public void close() {
		image.close();
	}
}
