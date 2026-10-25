package io.github.thedeadbolt.honeyfactory.dataset;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.image.PngImages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Checks a dataset directory against the contract: every record on its own, the records
 * together, and every image file the samples reference.
 *
 * <p>For each image: the file exists, its byte count and SHA-256 match the descriptor, it
 * decodes as an 8-bit PNG of the recorded size, masks contain only 0 and 255, and the
 * mask's pixel count, bounding box and centroid equal the sample's target metadata. Image
 * files no sample references are errors too.
 *
 * <p>Samples' images are checked in parallel, on as many threads as a quarter of the heap
 * allows at three RGB images per check, at most {@value #MAX_THREADS}; errors are reported in
 * sample order, as a sequential check would report them.
 */
public final class DatasetValidator {
	/** Validation result; an empty {@code errors} list means the dataset is valid. */
	public record Report(
		@Nullable DatasetManifest manifest,
		List<SceneRecord> scenes,
		List<SampleRecord> samples,
		DatasetManifest.IndexDigest scenesDigest,
		DatasetManifest.IndexDigest samplesDigest,
		List<String> errors
	) {
		public boolean passed() {
			return this.errors.isEmpty();
		}
	}

	private static final List<String> IMAGE_ROOTS = List.of("rgb", "masks");
	/** Image checks are I/O and decoding bound; more threads than this gain little and hold more decoded images. */
	static final int MAX_THREADS = 16;

	private DatasetValidator() {
	}

	/** Validates the dataset as stored, including its manifest. */
	public static Report validate(Path root) throws IOException {
		List<String> errors = new ArrayList<>();
		DatasetManifest manifest = null;
		try {
			JsonObject json = JsonFields.parseObject(Files.readString(root.resolve(DatasetWriter.MANIFEST), StandardCharsets.UTF_8));
			manifest = DatasetManifest.fromJson(json);
		} catch (ContractException | IOException e) {
			errors.add(DatasetWriter.MANIFEST + ": " + e.getMessage());
		}

		return validate(root, manifest, errors);
	}

	/** Validates the stored indexes and images against {@code manifest}, which need not be written yet. */
	public static Report validate(Path root, DatasetManifest manifest) throws IOException {
		return validate(root, manifest, new ArrayList<>());
	}

	private static Report validate(Path root, @Nullable DatasetManifest manifest, List<String> errors) throws IOException {
		byte[] sceneBytes = Files.readAllBytes(root.resolve(DatasetWriter.SCENES));
		byte[] sampleBytes = Files.readAllBytes(root.resolve(DatasetWriter.SAMPLES));
		List<SceneRecord> scenes = parse(sceneBytes, DatasetWriter.SCENES, SceneRecord::fromJson, errors);
		List<SampleRecord> samples = parse(sampleBytes, DatasetWriter.SAMPLES, SampleRecord::fromJson, errors);
		DatasetManifest.IndexDigest scenesDigest = new DatasetManifest.IndexDigest(sceneBytes.length, Sha256.hex(sceneBytes));
		DatasetManifest.IndexDigest samplesDigest = new DatasetManifest.IndexDigest(sampleBytes.length, Sha256.hex(sampleBytes));
		if (manifest != null) {
			errors.addAll(DatasetConsistency.check(manifest, scenes, samples));
			if (manifest.integrity() != null) {
				if (!manifest.integrity().scenes().equals(scenesDigest)) {
					errors.add("manifest: integrity.indexes.scenes does not match " + DatasetWriter.SCENES);
				}

				if (!manifest.integrity().samples().equals(samplesDigest)) {
					errors.add("manifest: integrity.indexes.samples does not match " + DatasetWriter.SAMPLES);
				}
			}
		}

		Set<String> referenced = new HashSet<>();
		for (SampleRecord sample : samples) {
			referenced.add(sample.rgb().path());
			referenced.add(sample.mask().path());
		}

		errors.addAll(sampleFileErrors(root, samples));

		for (String imageRoot : IMAGE_ROOTS) {
			Path directory = root.resolve(imageRoot);
			if (!Files.isDirectory(directory)) {
				continue;
			}

			try (Stream<Path> files = Files.walk(directory)) {
				files.filter(Files::isRegularFile)
					.map(file -> root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/"))
					.filter(path -> !referenced.contains(path))
					.sorted()
					.forEach(path -> errors.add(path + ": no sample references this file"));
			}
		}

		return new Report(manifest, List.copyOf(scenes), List.copyOf(samples), scenesDigest, samplesDigest, List.copyOf(errors));
	}

	private static <T> List<T> parse(byte[] bytes, String fileName, Function<JsonObject, T> parser, List<String> errors) {
		List<JsonObject> lines;
		try {
			lines = Jsonl.read(bytes, fileName);
		} catch (ContractException e) {
			errors.add(e.getMessage());
			return List.of();
		}

		List<T> records = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			try {
				records.add(parser.apply(lines.get(i)));
			} catch (ContractException | IllegalArgumentException e) {
				errors.add(fileName + ":" + (i + 1) + ": " + e.getMessage());
			}
		}

		return records;
	}

	/** Every sample's image errors, in sample order, checked on a pool of its own. */
	private static List<String> sampleFileErrors(Path root, List<SampleRecord> samples) throws IOException {
		long largest = samples.stream().mapToLong(sample -> (long) sample.rgb().width() * sample.rgb().height()).max().orElse(1);
		int threads = threads(largest, Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors());
		ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
			Thread thread = new Thread(runnable, "honey-factory-validate");
			thread.setDaemon(true);
			return thread;
		});
		try {
			List<Future<List<String>>> checks = new ArrayList<>(samples.size());
			for (SampleRecord sample : samples) {
				checks.add(pool.submit(() -> sampleFileErrors(root, sample)));
			}

			List<String> errors = new ArrayList<>();
			for (Future<List<String>> check : checks) {
				errors.addAll(check.get());
			}

			return errors;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("validation was interrupted", e);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof IOException io) {
				throw io;
			}

			throw new IllegalStateException(e.getCause());
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Threads for checking images of up to {@code rgbPixels} pixels: one per spare processor,
	 * within a quarter of {@code maxHeap} at three RGB images each.
	 */
	static int threads(long rgbPixels, long maxHeap, int processors) {
		long byMemory = maxHeap / 4 / Math.max(1, 3 * 3 * rgbPixels);
		return (int) Math.max(1, Math.min(Math.min(MAX_THREADS, processors - 1), byMemory));
	}

	/** One sample's image errors: both files as recorded and the mask's statistics. */
	private static List<String> sampleFileErrors(Path root, SampleRecord sample) throws IOException {
		List<String> errors = new ArrayList<>();
		String label = "sample " + sample.sampleId() + ": ";
		checkImage(root, sample.rgb(), 3, label + "rgb", errors);
		byte[] mask = checkImage(root, sample.mask(), 1, label + "mask", errors);
		if (mask != null) {
			checkMask(sample, mask, label, errors);
		}

		return errors;
	}

	/** Returns the decoded pixels when the file matches its descriptor, otherwise records why. */
	private static byte @Nullable [] checkImage(Path root, SampleRecord.ImageFile file, int channels, String label, List<String> errors) throws IOException {
		Path path = root.resolve(file.path());
		if (!Files.isRegularFile(path)) {
			errors.add(label + ": " + file.path() + " is missing");
			return null;
		}

		byte[] bytes = Files.readAllBytes(path);
		if (bytes.length != file.bytes() || !Sha256.hex(bytes).equals(file.sha256())) {
			errors.add(label + ": " + file.path() + " does not match its recorded byte count and SHA-256");
			return null;
		}

		PngImages.Pixels pixels;
		try {
			pixels = PngImages.decode(bytes);
		} catch (IllegalArgumentException e) {
			errors.add(label + ": " + file.path() + " " + e.getMessage());
			return null;
		}

		if (pixels.width() != file.width() || pixels.height() != file.height() || pixels.channels() != channels) {
			errors.add(label + ": " + file.path() + " is " + pixels.width() + "x" + pixels.height() + " with " + pixels.channels()
				+ " channels, recorded " + file.width() + "x" + file.height() + " with " + channels);
			return null;
		}

		return pixels.data();
	}

	private static void checkMask(SampleRecord sample, byte[] mask, String label, List<String> errors) {
		MaskStatistics statistics;
		try {
			statistics = MaskStatistics.of(mask, sample.mask().width(), sample.mask().height());
		} catch (IllegalArgumentException e) {
			errors.add(label + "mask " + e.getMessage());
			return;
		}

		SampleRecord.Target target = sample.target();
		if (statistics.positivePixels() != target.visiblePixels()
			|| !Arrays.equals(statistics.bboxXywh(), target.visibleBboxXywh())
			|| !Arrays.equals(statistics.centroidXy(), target.visibleCentroidXy())) {
			errors.add(label + "visible_pixels, visible_bbox_xywh or visible_centroid_xy differ from the saved mask");
		}
	}
}
