package io.github.thedeadbolt.honeyfactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

/**
 * Minimal 8-bit PNG writer for committed fixtures. Image data uses stored (uncompressed)
 * deflate blocks, so the bytes never depend on a zlib version the way compressed output does.
 */
public final class StoredPng {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final int MAX_STORED_BLOCK = 65_535;

	private StoredPng() {
	}

	public static byte[] gray(int width, int height, byte[] pixels) {
		return encode(width, height, 1, 0, pixels);
	}

	public static byte[] rgb(int width, int height, byte[] pixels) {
		return encode(width, height, 3, 2, pixels);
	}

	private static byte[] encode(int width, int height, int channels, int colourType, byte[] pixels) {
		if (pixels.length != width * height * channels) {
			throw new IllegalArgumentException("Expected " + width * height * channels + " bytes, got " + pixels.length);
		}

		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int y = 0; y < height; y++) {
			raw.write(0);
			raw.write(pixels, y * width * channels, width * channels);
		}

		ByteArrayOutputStream png = new ByteArrayOutputStream();
		png.writeBytes(SIGNATURE);
		chunk(png, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) colourType).put((byte) 0).put((byte) 0).put((byte) 0).array());
		chunk(png, "IDAT", zlibStored(raw.toByteArray()));
		chunk(png, "IEND", new byte[0]);
		return png.toByteArray();
	}

	private static byte[] zlibStored(byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x78);
		out.write(0x01);
		int offset = 0;
		do {
			int length = Math.min(MAX_STORED_BLOCK, data.length - offset);
			boolean last = offset + length == data.length;
			out.write(last ? 1 : 0);
			out.write(length & 0xFF);
			out.write(length >>> 8);
			out.write(~length & 0xFF);
			out.write((~length >>> 8) & 0xFF);
			out.write(data, offset, length);
			offset += length;
		} while (offset < data.length);

		Adler32 adler = new Adler32();
		adler.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) adler.getValue()).array());
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream png, String type, byte[] data) {
		byte[] name = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(data);
		png.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		png.writeBytes(name);
		png.writeBytes(data);
		png.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
