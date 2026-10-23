package io.github.thedeadbolt.honeyfactory.contract;

import org.jspecify.annotations.Nullable;

/**
 * Pixel count, half-open bounding box and centroid of a finalized binary mask.
 *
 * @param bboxXywh smallest {@code [x, y, width, height]} containing every positive pixel, or null when empty
 * @param centroidXy mean of positive pixel centres {@code (x + 0.5, y + 0.5)}, or null when empty
 */
public record MaskStatistics(int positivePixels, int @Nullable [] bboxXywh, double @Nullable [] centroidXy) {
	public static final int POSITIVE = 255;
	public static final String ENCODING = "png-gray8";
	public static final String MEANING = "visible-target-geometry-v1";

	/** Reads a row-major, top-down 8-bit mask whose values are exactly 0 or 255. */
	public static MaskStatistics of(byte[] mask, int width, int height) {
		if (width <= 0 || height <= 0 || mask.length != width * height) {
			throw new IllegalArgumentException("Mask has " + mask.length + " bytes, expected " + width + "x" + height);
		}

		int count = 0;
		int minX = width;
		int minY = height;
		int maxX = -1;
		int maxY = -1;
		long sumX = 0;
		long sumY = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int value = mask[y * width + x] & 0xFF;
				if (value == POSITIVE) {
					count++;
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
					sumX += x;
					sumY += y;
				} else if (value != 0) {
					throw new IllegalArgumentException("Mask pixel (" + x + ", " + y + ") is " + value + ", not 0 or 255");
				}
			}
		}

		if (count == 0) {
			return new MaskStatistics(0, null, null);
		}

		return new MaskStatistics(
			count,
			new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1},
			new double[] {(double) sumX / count + 0.5, (double) sumY / count + 0.5}
		);
	}
}
