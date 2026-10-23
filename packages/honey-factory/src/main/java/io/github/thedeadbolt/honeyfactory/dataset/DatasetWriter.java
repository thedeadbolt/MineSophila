package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Writes one dataset directory in the contract's commit order. Files are written to a
 * temporary sibling, flushed to disk and atomically moved into place, so an index record
 * never points at a partial file and readers never see a partial manifest.
 *
 * <p>Scene and sample records are appended together once a scene is accepted: the scene
 * line first, then its samples, each after their image files are final.
 */
public final class DatasetWriter {
	public static final String MANIFEST = "dataset.json";
	public static final String SCENES = "scenes.jsonl";
	public static final String SAMPLES = "samples.jsonl";

	private final Path root;

	private DatasetWriter(Path root) {
		this.root = root;
	}

	/**
	 * Creates {@code <outputRoot>/<dataset id>/} with the {@code generating} manifest and
	 * empty indexes.
	 *
	 * @throws FileAlreadyExistsException if the dataset directory already exists; identical
	 *     configurations share an ID, so an existing dataset is never extended or replaced
	 */
	public static DatasetWriter create(Path outputRoot, DatasetManifest manifest) throws IOException {
		if (manifest.lifecycle() != DatasetManifest.Lifecycle.GENERATING) {
			throw new IllegalArgumentException("A new dataset starts in the generating state");
		}

		Files.createDirectories(outputRoot);
		Path root = outputRoot.resolve(manifest.datasetId());
		Files.createDirectory(root);
		DatasetWriter writer = new DatasetWriter(root);
		writer.writeManifest(manifest);
		Files.write(root.resolve(SCENES), new byte[0], StandardOpenOption.CREATE_NEW);
		Files.write(root.resolve(SAMPLES), new byte[0], StandardOpenOption.CREATE_NEW);
		return writer;
	}

	public Path root() {
		return this.root;
	}

	/** Writes PNG bytes at a dataset-relative path and describes the final file. */
	public SampleRecord.ImageFile writeImage(String relativePath, byte[] png, int width, int height) throws IOException {
		writeAtomically(resolve(relativePath), png);
		return new SampleRecord.ImageFile(relativePath, SampleRecord.MEDIA_TYPE, width, height, png.length, Sha256.hex(png));
	}

	/** Removes image files of a rejected scene; records never referenced them. */
	public void deleteImages(List<String> relativePaths) throws IOException {
		for (String path : relativePaths) {
			Files.deleteIfExists(resolve(path));
		}
	}

	/** Appends an accepted scene and then its samples, whose files must already be final. */
	public void appendScene(SceneRecord scene, List<SampleRecord> samples) throws IOException {
		for (SampleRecord sample : samples) {
			if (!sample.sceneId().equals(scene.sceneId())) {
				throw new IllegalArgumentException("Sample " + sample.sampleId() + " does not belong to scene " + scene.sceneId());
			}

			for (SampleRecord.ImageFile file : List.of(sample.rgb(), sample.mask())) {
				if (Files.size(resolve(file.path())) != file.bytes()) {
					throw new IOException("Image " + file.path() + " is missing or incomplete");
				}
			}
		}

		append(SCENES, Jsonl.line(scene.toJson()));
		StringBuilder lines = new StringBuilder();
		samples.forEach(sample -> lines.append(Jsonl.line(sample.toJson())));
		append(SAMPLES, lines.toString());
	}

	/** Atomically replaces {@code dataset.json}. */
	public void writeManifest(DatasetManifest manifest) throws IOException {
		writeAtomically(this.root.resolve(MANIFEST), Jsonl.document(manifest.toJson()));
	}

	private Path resolve(String relativePath) {
		if (!Ids.isRelativePath(relativePath)) {
			throw new IllegalArgumentException("Not a dataset-relative path: " + relativePath);
		}

		return this.root.resolve(relativePath);
	}

	private void append(String index, String text) throws IOException {
		try (FileChannel channel = FileChannel.open(this.root.resolve(index), StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
			ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
			while (bytes.hasRemaining()) {
				channel.write(bytes);
			}

			channel.force(true);
		}
	}

	private static void writeAtomically(Path target, byte[] bytes) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling("." + target.getFileName() + ".tmp");
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}

			channel.force(true);
		}

		try {
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.deleteIfExists(temporary);
			throw e;
		}
	}
}
