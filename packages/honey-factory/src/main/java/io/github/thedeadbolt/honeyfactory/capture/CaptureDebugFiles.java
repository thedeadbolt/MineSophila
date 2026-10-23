package io.github.thedeadbolt.honeyfactory.capture;

import io.github.thedeadbolt.honeyfactory.image.PngImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes one capture as inspection files: {@code rgb.png}, {@code visible_mask.png},
 * {@code unoccluded_mask.png} and {@code overlay.png}. These are debugging and review
 * aids, never dataset files.
 *
 * <p>The overlay blends visible target pixels towards red and hidden ones (unoccluded but
 * not visible) towards blue, leaving everything else unchanged.
 */
public final class CaptureDebugFiles {
	private CaptureDebugFiles() {
	}

	public static void write(Path directory, CapturedImages images) throws IOException {
		Files.createDirectories(directory);
		int width = images.width();
		int height = images.height();
		Files.write(directory.resolve("rgb.png"), PngImages.encodeRgb(width, height, images.rgb()));
		Files.write(directory.resolve("visible_mask.png"), PngImages.encodeGray(width, height, images.visibleMask()));
		Files.write(directory.resolve("unoccluded_mask.png"), PngImages.encodeGray(width, height, images.unoccludedMask()));
		Files.write(directory.resolve("overlay.png"), PngImages.encodeRgb(width, height, overlay(images)));
	}

	public static byte[] overlay(CapturedImages images) {
		byte[] rgb = images.rgb();
		byte[] result = rgb.clone();
		for (int p = 0; p < images.visibleMask().length; p++) {
			boolean visible = images.visibleMask()[p] != 0;
			boolean hidden = !visible && images.unoccludedMask()[p] != 0;
			if (visible || hidden) {
				int channel = visible ? 0 : 2;
				for (int c = 0; c < 3; c++) {
					int value = (rgb[p * 3 + c] & 0xFF) / 2 + (c == channel ? 127 : 0);
					result[p * 3 + c] = (byte) value;
				}
			}
		}

		return result;
	}
}
