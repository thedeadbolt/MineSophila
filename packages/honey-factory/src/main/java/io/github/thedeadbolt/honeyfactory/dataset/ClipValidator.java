package io.github.thedeadbolt.honeyfactory.dataset;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.clip.ClipWriter;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.DatasetKind;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Checks a clip dataset directory (M12-2): every scene, clip and frame record on its own,
 * then {@link ClipConsistency}'s rules over records and videos, and every file a clip
 * references: present, of the recorded size and SHA-256.
 * Folders under {@code clips/} no clip references, and unfinished {@code .partial} folders,
 * are errors too.
 */
public final class ClipValidator {
	/** Validation result; an empty {@code errors} list means the dataset is valid. */
	public record Report(
		@Nullable DatasetManifest manifest,
		List<SceneRecord> scenes,
		List<ClipRecord> clips,
		DatasetManifest.IndexDigest scenesDigest,
		DatasetManifest.IndexDigest clipsDigest,
		List<String> errors
	) {
		public boolean passed() {
			return this.errors.isEmpty();
		}

		/** This report with {@code more} errors after its own. */
		public Report withErrors(List<String> more) {
			List<String> all = new ArrayList<>(this.errors);
			all.addAll(more);
			return new Report(this.manifest, this.scenes, this.clips, this.scenesDigest, this.clipsDigest, List.copyOf(all));
		}
	}

	private ClipValidator() {
	}

	/** Validates the dataset as stored, including its manifest and every frame's mask. */
	public static Report validate(Path root) throws IOException {
		return validate(root, true);
	}

	/**
	 * Validates the dataset as stored, including its manifest.
	 *
	 * @param pixels whether to decode the masks and compare them with the labels; without, the
	 *     videos' frame counts are still checked from their containers
	 */
	public static Report validate(Path root, boolean pixels) throws IOException {
		List<String> errors = new ArrayList<>();
		DatasetManifest manifest = null;
		try {
			manifest = DatasetManifest.fromJson(JsonFields.parseObject(Files.readString(root.resolve(DatasetWriter.MANIFEST), StandardCharsets.UTF_8)));
		} catch (ContractException | IOException e) {
			errors.add(DatasetWriter.MANIFEST + ": " + e.getMessage());
		}

		return validate(root, manifest, errors, pixels);
	}

	/** Validates the stored records and files against {@code manifest}, which need not be written yet. */
	public static Report validate(Path root, DatasetManifest manifest) throws IOException {
		return validate(root, manifest, true);
	}

	/** As {@link #validate(Path, DatasetManifest)}; {@code pixels} as in {@link #validate(Path, boolean)}. */
	public static Report validate(Path root, DatasetManifest manifest, boolean pixels) throws IOException {
		return validate(root, manifest, new ArrayList<>(), pixels);
	}

	private static Report validate(Path root, @Nullable DatasetManifest manifest, List<String> errors, boolean pixels) throws IOException {
		byte[] sceneBytes = Files.readAllBytes(root.resolve(DatasetWriter.SCENES));
		byte[] clipBytes = Files.readAllBytes(root.resolve(DatasetWriter.CLIPS));
		List<SceneRecord> scenes = parse(sceneBytes, DatasetWriter.SCENES, SceneRecord::fromJson, errors);
		List<ClipRecord> clips = parse(clipBytes, DatasetWriter.CLIPS, ClipRecord::fromJson, errors);
		DatasetManifest.IndexDigest scenesDigest = new DatasetManifest.IndexDigest(sceneBytes.length, Sha256.hex(sceneBytes));
		DatasetManifest.IndexDigest clipsDigest = new DatasetManifest.IndexDigest(clipBytes.length, Sha256.hex(clipBytes));
		Map<String, SceneRecord> byId = new HashMap<>();
		scenes.forEach(scene -> byId.putIfAbsent(scene.sceneId(), scene));
		if (manifest != null) {
			errors.addAll(manifest.kind() == DatasetKind.CLIPS ? ClipConsistency.check(manifest, scenes, clips) : List.of("manifest: is not a clip dataset"));
			if (manifest.integrity() != null) {
				if (!manifest.integrity().scenes().equals(scenesDigest)) {
					errors.add("manifest: integrity.indexes.scenes does not match " + DatasetWriter.SCENES);
				}

				if (!manifest.integrity().samples().equals(clipsDigest)) {
					errors.add("manifest: integrity.indexes.clips does not match " + DatasetWriter.CLIPS);
				}
			}
		}

		for (ClipRecord clip : clips) {
			errors.addAll(files(root, manifest, byId.get(clip.sceneId()), clip, pixels));
		}

		errors.addAll(unreferenced(root, clips));
		return new Report(manifest, scenes, clips, scenesDigest, clipsDigest, List.copyOf(errors));
	}

	/**
	 * A clip's files: present, of the recorded size and hash; its frame records (see
	 * {@link ClipConsistency#frames}); its videos holding every frame, with masks as labelled.
	 * A file that differs from its record is still checked, so every problem shows.
	 */
	private static List<String> files(Path root, @Nullable DatasetManifest manifest, @Nullable SceneRecord scene, ClipRecord clip, boolean pixels) throws IOException {
		List<String> errors = new ArrayList<>();
		boolean videos = true;
		for (String[] file : new String[][] {
			{clip.rgb().path(), Long.toString(clip.rgb().bytes()), clip.rgb().sha256()},
			{clip.visibleMask().path(), Long.toString(clip.visibleMask().bytes()), clip.visibleMask().sha256()},
			{clip.frames().path(), Long.toString(clip.frames().bytes()), clip.frames().sha256()}
		}) {
			Path path = root.resolve(file[0]);
			if (!Files.isRegularFile(path)) {
				errors.add(clip.clipId() + ": " + file[0] + " is missing");
				videos &= file[0].equals(clip.frames().path());
			} else if (Files.size(path) != Long.parseLong(file[1])) {
				errors.add(clip.clipId() + ": " + file[0] + " has " + Files.size(path) + " bytes, not " + file[1]);
			} else if (!ClipWriter.sha256(path).equals(file[2])) {
				errors.add(clip.clipId() + ": " + file[0] + " does not match its SHA-256");
			}
		}

		Path framesFile = root.resolve(clip.frames().path());
		List<FrameRecord> frames = Files.isRegularFile(framesFile) ? parse(Files.readAllBytes(framesFile), clip.frames().path(), FrameRecord::fromJson, errors) : List.of();
		if (manifest != null && manifest.kind() == DatasetKind.CLIPS) {
			errors.addAll(ClipConsistency.frames(manifest, scene, clip, frames));
		}

		if (videos) {
			errors.addAll(ClipConsistency.videos(root, clip, frames, pixels));
		}

		return errors;
	}

	/** Clip folders no clip references, and folders a run left unfinished. */
	private static List<String> unreferenced(Path root, List<ClipRecord> clips) throws IOException {
		Path directory = root.resolve("clips");
		if (!Files.isDirectory(directory)) {
			return List.of();
		}

		Set<String> referenced = new HashSet<>();
		for (ClipRecord clip : clips) {
			referenced.add(clip.frames().path().substring(0, clip.frames().path().lastIndexOf('/')));
		}

		List<String> errors = new ArrayList<>();
		try (Stream<Path> files = Files.walk(directory, 3)) {
			for (Path folder : files.filter(path -> root.relativize(path).getNameCount() == 4).sorted().toList()) {
				String relative = root.relativize(folder).toString().replace(folder.getFileSystem().getSeparator(), "/");
				if (!referenced.contains(relative)) {
					errors.add(relative + ": " + (relative.endsWith(".partial") ? "is an unfinished clip" : "no clip references this folder"));
				}
			}
		}

		return errors;
	}

	private static <T> List<T> parse(byte[] bytes, String name, Function<JsonObject, T> parser, List<String> errors) {
		List<T> records = new ArrayList<>();
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (!text.isEmpty() && !text.endsWith("\n")) {
			errors.add(name + ": the last record lacks its newline");
		}

		String[] lines = text.isEmpty() ? new String[0] : text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			if (i == lines.length - 1 && lines[i].isEmpty()) {
				break;
			}

			try {
				records.add(parser.apply(JsonFields.parseObject(lines[i])));
			} catch (ContractException e) {
				errors.add(name + " line " + (i + 1) + ": " + e.getMessage());
			}
		}

		return records;
	}
}
