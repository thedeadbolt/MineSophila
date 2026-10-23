package io.github.thedeadbolt.honeyfactory.capture;

import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import org.jspecify.annotations.Nullable;

/**
 * The pixels of one capture, row-major from the image top: RGB8 colour, the exported
 * visible-target mask and the internal unoccluded-target mask, both 8-bit with values 0
 * or 255.
 */
public record CapturedImages(int width, int height, byte[] rgb, byte[] visibleMask, byte[] unoccludedMask) {
	public CapturedImages {
		long pixels = (long) width * height;
		if (width <= 0 || height <= 0 || rgb.length != pixels * 3 || visibleMask.length != pixels || unoccludedMask.length != pixels) {
			throw new IllegalArgumentException("Image buffers do not match " + width + "x" + height);
		}
	}

	/** Images whose target masks are empty, for a scene without a target. */
	public static CapturedImages withoutTarget(int width, int height, byte[] rgb) {
		return new CapturedImages(width, height, rgb, new byte[width * height], new byte[width * height]);
	}

	/**
	 * Why these masks cannot be labels, or null when they can: every value must be 0 or 255
	 * and no visible pixel may lie outside the unoccluded mask.
	 */
	public @Nullable String maskDefect() {
		for (int p = 0; p < this.visibleMask.length; p++) {
			int visible = this.visibleMask[p] & 0xFF;
			int unoccluded = this.unoccludedMask[p] & 0xFF;
			if (visible != 0 && visible != MaskStatistics.POSITIVE || unoccluded != 0 && unoccluded != MaskStatistics.POSITIVE) {
				return "mask pixel " + p + " is not 0 or 255";
			}

			if (visible != 0 && unoccluded == 0) {
				return "visible mask pixel " + p + " lies outside the unoccluded mask";
			}
		}

		return null;
	}

	public int unoccludedPixels() {
		int count = 0;
		for (byte value : this.unoccludedMask) {
			count += value != 0 ? 1 : 0;
		}

		return count;
	}

	/** Mean Rec. 709 relative luminance of the gamma-encoded RGB values, in [0, 1]. */
	public double meanLuminance() {
		double sum = 0.0;
		for (int p = 0; p < this.rgb.length; p += 3) {
			sum += 0.2126 * (this.rgb[p] & 0xFF) + 0.7152 * (this.rgb[p + 1] & 0xFF) + 0.0722 * (this.rgb[p + 2] & 0xFF);
		}

		return Math.min(1.0, sum / (255.0 * this.width * this.height));
	}
}
