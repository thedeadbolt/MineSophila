package io.github.thedeadbolt.honeyfactory.image;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * Lossless PNG encoding of row-major, top-down 8-bit pixels: {@code png-rgb8} colour and
 * {@code png-gray8} masks. Encoding the same pixels always produces the same bytes on one
 * Java runtime, and no ancillary chunks such as timestamps are written.
 */
public final class PngImages {
	/** Decoded pixels; {@code channels} is 1 for grey and 3 for RGB. */
	public record Pixels(int width, int height, int channels, byte[] data) {
	}

	private PngImages() {
	}

	public static byte[] encodeRgb(int width, int height, byte[] rgb) {
		return encode(width, height, 3, rgb, ColorSpace.CS_sRGB);
	}

	public static byte[] encodeGray(int width, int height, byte[] gray) {
		return encode(width, height, 1, gray, ColorSpace.CS_GRAY);
	}

	/**
	 * Decodes an 8-bit grey or RGB PNG without alpha.
	 *
	 * @throws IllegalArgumentException if the bytes are not such a PNG
	 */
	public static Pixels decode(byte[] png) {
		BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(png));
		} catch (IOException e) {
			throw new IllegalArgumentException("not a readable PNG: " + e.getMessage(), e);
		}

		if (image == null) {
			throw new IllegalArgumentException("not a readable PNG");
		}

		Raster raster = image.getRaster();
		int channels = raster.getNumBands();
		boolean eightBit = raster.getTransferType() == DataBuffer.TYPE_BYTE && image.getColorModel().getPixelSize() == 8 * channels;
		if (!eightBit || channels != 1 && channels != 3 || image.getColorModel().hasAlpha()) {
			throw new IllegalArgumentException("must be 8-bit grey or RGB without alpha, got " + channels + " channels of " + image.getColorModel().getPixelSize() + " bits");
		}

		return new Pixels(image.getWidth(), image.getHeight(), channels, bytes(raster, image.getWidth(), image.getHeight(), channels));
	}

	/**
	 * The raster's samples in band order (R, G, B), whatever its byte layout. An interleaved
	 * byte raster, which ImageIO produces for these PNGs, is copied directly; anything else
	 * goes through {@link Raster#getPixels}.
	 */
	private static byte[] bytes(Raster raster, int width, int height, int channels) {
		if (raster.getSampleModel() instanceof ComponentSampleModel model && raster.getDataBuffer() instanceof DataBufferByte buffer && buffer.getNumBanks() == 1
			&& model.getPixelStride() == channels && model.getScanlineStride() == width * channels
			&& raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0 && buffer.getOffset() == 0) {
			byte[] source = buffer.getData();
			int[] offsets = model.getBandOffsets();
			byte[] data = new byte[width * height * channels];
			for (int band = 0; band < channels; band++) {
				int offset = offsets[band];
				for (int i = band, j = offset; i < data.length; i += channels, j += channels) {
					data[i] = source[j];
				}
			}

			return data;
		}

		int[] samples = raster.getPixels(0, 0, width, height, (int[]) null);
		byte[] data = new byte[samples.length];
		for (int i = 0; i < samples.length; i++) {
			data[i] = (byte) samples[i];
		}

		return data;
	}

	private static byte[] encode(int width, int height, int channels, byte[] pixels, int colorSpace) {
		if (pixels.length != width * height * channels) {
			throw new IllegalArgumentException("Expected " + width * height * channels + " bytes, got " + pixels.length);
		}

		int[] offsets = channels == 1 ? new int[] {0} : new int[] {0, 1, 2};
		WritableRaster raster = Raster.createInterleavedRaster(new DataBufferByte(pixels, pixels.length), width, height, width * channels, channels, offsets, null);
		ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(colorSpace), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
		BufferedImage image = new BufferedImage(model, raster, false, null);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			if (!ImageIO.write(image, "png", out)) {
				throw new IllegalStateException("No PNG writer is available");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return out.toByteArray();
	}
}
