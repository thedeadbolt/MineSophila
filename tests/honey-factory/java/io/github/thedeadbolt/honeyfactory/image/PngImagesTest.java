package io.github.thedeadbolt.honeyfactory.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Decoding returns exactly the encoded bytes, in R, G, B order for colour. */
class PngImagesTest {
	@Test
	void rgbAndGreyRoundTrip() {
		Random random = new Random(9);
		byte[] rgb = new byte[37 * 23 * 3];
		random.nextBytes(rgb);
		PngImages.Pixels colour = PngImages.decode(PngImages.encodeRgb(37, 23, rgb));
		assertEquals(3, colour.channels());
		assertArrayEquals(rgb, colour.data());

		byte[] grey = new byte[37 * 23];
		random.nextBytes(grey);
		PngImages.Pixels decoded = PngImages.decode(PngImages.encodeGray(37, 23, grey));
		assertEquals(1, decoded.channels());
		assertArrayEquals(grey, decoded.data());
	}

	@Test
	void pureColoursKeepTheirChannel() {
		byte[] rgb = {(byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255};
		assertArrayEquals(rgb, PngImages.decode(PngImages.encodeRgb(3, 1, rgb)).data());
	}
}
