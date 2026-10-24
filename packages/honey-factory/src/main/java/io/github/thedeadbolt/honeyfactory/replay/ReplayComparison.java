package io.github.thedeadbolt.honeyfactory.replay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.image.PngImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares a fresh capture with a sample's stored images. The stored files must still match
 * their recorded byte counts and hashes; masks are compared pixel for pixel, RGB by how
 * many pixels differ and by how much.
 */
public final class ReplayComparison {
	/** One viewpoint's comparison; the mask matches when no pixel differs. */
	public record Result(
		String sampleId,
		int maskDifferingPixels,
		int storedVisiblePixels,
		int freshVisiblePixels,
		int rgbDifferingPixels,
		int rgbMaxChannelDelta
	) {
		public boolean maskMatches() {
			return this.maskDifferingPixels == 0;
		}

		public boolean rgbMatches() {
			return this.rgbDifferingPixels == 0;
		}
	}

	private ReplayComparison() {
	}

	/**
	 * @throws ReplayException INVALID_DATASET when a stored file is missing or damaged,
	 *     MISMATCH when the fresh capture has other dimensions
	 */
	public static Result compare(ReplayScene scene, SampleRecord sample, CapturedImages fresh) {
		PngImages.Pixels rgb = stored(scene, sample.rgb(), 3);
		PngImages.Pixels mask = stored(scene, sample.mask(), 1);
		if (fresh.width() != mask.width() || fresh.height() != mask.height()) {
			throw new ReplayException(ReplayException.Reason.MISMATCH, sample.sampleId() + ": fresh capture is " + fresh.width() + "x" + fresh.height()
				+ ", stored images are " + mask.width() + "x" + mask.height());
		}

		int maskDiffering = 0;
		int storedVisible = 0;
		int freshVisible = 0;
		byte[] visible = fresh.visibleMask();
		for (int p = 0; p < visible.length; p++) {
			maskDiffering += visible[p] != mask.data()[p] ? 1 : 0;
			storedVisible += mask.data()[p] != 0 ? 1 : 0;
			freshVisible += visible[p] != 0 ? 1 : 0;
		}

		int rgbDiffering = 0;
		int maxDelta = 0;
		byte[] colour = fresh.rgb();
		for (int p = 0; p < colour.length; p += 3) {
			int delta = 0;
			for (int c = 0; c < 3; c++) {
				delta = Math.max(delta, Math.abs((colour[p + c] & 0xFF) - (rgb.data()[p + c] & 0xFF)));
			}

			rgbDiffering += delta != 0 ? 1 : 0;
			maxDelta = Math.max(maxDelta, delta);
		}

		return new Result(sample.sampleId(), maskDiffering, storedVisible, freshVisible, rgbDiffering, maxDelta);
	}

	private static PngImages.Pixels stored(ReplayScene scene, SampleRecord.ImageFile file, int channels) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(scene.file(file.path()));
		} catch (IOException e) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, file.path() + " cannot be read: " + e.getMessage());
		}

		if (bytes.length != file.bytes() || !Sha256.hex(bytes).equals(file.sha256())) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, file.path() + " does not match its recorded byte count and SHA-256");
		}

		PngImages.Pixels pixels = PngImages.decode(bytes);
		if (pixels.channels() != channels || pixels.width() != file.width() || pixels.height() != file.height()) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, file.path() + " is not the recorded " + file.width() + "x" + file.height() + " image");
		}

		return pixels;
	}

	/** Writes the fresh images as {@code <sample>-rgb.png} and {@code <sample>-mask.png} under {@code directory}. */
	public static List<Path> writeFresh(Path directory, String sampleId, CapturedImages fresh) throws IOException {
		Files.createDirectories(directory);
		Path rgb = directory.resolve(sampleId + "-rgb.png");
		Path mask = directory.resolve(sampleId + "-mask.png");
		Files.write(rgb, PngImages.encodeRgb(fresh.width(), fresh.height(), fresh.rgb()));
		Files.write(mask, PngImages.encodeGray(fresh.width(), fresh.height(), fresh.visibleMask()));
		return List.of(rgb, mask);
	}

	/**
	 * The render settings that differ between the dataset's recorded setup and {@code current},
	 * as {@code name: recorded -> current}; empty on the same setup.
	 */
	public static List<String> renderDifferences(DatasetManifest.RenderEnvironment recorded, DatasetManifest.RenderEnvironment current) {
		List<String> differences = new ArrayList<>();
		differences(recorded.toJson(), current.toJson(), "", differences);
		return differences;
	}

	private static void differences(JsonObject recorded, JsonObject current, String prefix, List<String> out) {
		Set<String> names = new TreeSet<>(recorded.keySet());
		names.addAll(current.keySet());
		for (String name : names) {
			JsonElement before = recorded.get(name);
			JsonElement after = current.get(name);
			if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
				differences(before.getAsJsonObject(), after.getAsJsonObject(), prefix + name + ".", out);
			} else if (before == null || !before.equals(after)) {
				out.add(prefix + name + ": " + before + " -> " + after);
			}
		}
	}
}
