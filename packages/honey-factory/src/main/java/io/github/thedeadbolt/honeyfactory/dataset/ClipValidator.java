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
 * clips against their scenes and the manifest's counts, and every file a clip references:
 * present, of the recorded size and SHA-256, with one frame record per frame in order.
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
	}

	private ClipValidator() {
	}

	/** Validates the dataset as stored, including its manifest. */
	public static Report validate(Path root) throws IOException {
		List<String> errors = new ArrayList<>();
		DatasetManifest manifest = null;
		try {
			manifest = DatasetManifest.fromJson(JsonFields.parseObject(Files.readString(root.resolve(DatasetWriter.MANIFEST), StandardCharsets.UTF_8)));
		} catch (ContractException | IOException e) {
			errors.add(DatasetWriter.MANIFEST + ": " + e.getMessage());
		}

		return validate(root, manifest, errors);
	}

	/** Validates the stored records and files against {@code manifest}, which need not be written yet. */
	public static Report validate(Path root, DatasetManifest manifest) throws IOException {
		return validate(root, manifest, new ArrayList<>());
	}

	private static Report validate(Path root, @Nullable DatasetManifest manifest, List<String> errors) throws IOException {
		byte[] sceneBytes = Files.readAllBytes(root.resolve(DatasetWriter.SCENES));
		byte[] clipBytes = Files.readAllBytes(root.resolve(DatasetWriter.CLIPS));
		List<SceneRecord> scenes = parse(sceneBytes, DatasetWriter.SCENES, SceneRecord::fromJson, errors);
		List<ClipRecord> clips = parse(clipBytes, DatasetWriter.CLIPS, ClipRecord::fromJson, errors);
		DatasetManifest.IndexDigest scenesDigest = new DatasetManifest.IndexDigest(sceneBytes.length, Sha256.hex(sceneBytes));
		DatasetManifest.IndexDigest clipsDigest = new DatasetManifest.IndexDigest(clipBytes.length, Sha256.hex(clipBytes));
		if (manifest != null) {
			errors.addAll(records(manifest, scenes, clips));
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
			errors.addAll(files(root, clip));
		}

		errors.addAll(unreferenced(root, clips));
		return new Report(manifest, scenes, clips, scenesDigest, clipsDigest, List.copyOf(errors));
	}

	/** Clips against their scenes and the manifest. */
	private static List<String> records(DatasetManifest manifest, List<SceneRecord> scenes, List<ClipRecord> clips) {
		List<String> errors = new ArrayList<>();
		if (manifest.kind() != DatasetKind.CLIPS) {
			errors.add("manifest: is not a clip dataset");
			return errors;
		}

		int framesPerTick = manifest.clipSettings().framesPerTick();
		Map<String, SceneRecord> byId = new HashMap<>();
		for (SceneRecord scene : scenes) {
			if (byId.put(scene.sceneId(), scene) != null) {
				errors.add(DatasetWriter.SCENES + ": duplicate scene_id " + scene.sceneId());
			}

			if (scene.acceptedClipCount() == null) {
				errors.add(DatasetWriter.SCENES + ": scene " + scene.sceneId() + " lacks accepted_clip_count");
			}
		}

		Set<String> ids = new HashSet<>();
		Map<String, Integer> perScene = new HashMap<>();
		for (ClipRecord clip : clips) {
			if (!ids.add(clip.clipId())) {
				errors.add(DatasetWriter.CLIPS + ": duplicate clip_id " + clip.clipId());
			}

			SceneRecord scene = byId.get(clip.sceneId());
			if (scene == null) {
				errors.add(DatasetWriter.CLIPS + ": clip " + clip.clipId() + " references missing scene " + clip.sceneId());
			} else if (scene.split() != clip.split()) {
				errors.add(DatasetWriter.CLIPS + ": clip " + clip.clipId() + " is in " + clip.split().jsonName() + " but its scene is in " + scene.split().jsonName());
			}

			if (clip.framesPerTick() != framesPerTick) {
				errors.add(DatasetWriter.CLIPS + ": clip " + clip.clipId() + " has " + clip.framesPerTick() + " frames per tick; the dataset has " + framesPerTick);
			}

			perScene.merge(clip.sceneId(), 1, Integer::sum);
		}

		for (SceneRecord scene : scenes) {
			int count = perScene.getOrDefault(scene.sceneId(), 0);
			if (scene.acceptedClipCount() != null && scene.acceptedClipCount() != count) {
				errors.add(DatasetWriter.SCENES + ": scene " + scene.sceneId() + " records " + scene.acceptedClipCount() + " clips but has " + count);
			}
		}

		if (manifest.lifecycle() == DatasetManifest.Lifecycle.COMPLETE) {
			if (manifest.counts().acceptedScenes() != scenes.size()) {
				errors.add("manifest: counts.accepted_scenes is " + manifest.counts().acceptedScenes() + " but " + scenes.size() + " scenes are stored");
			}

			if (manifest.counts().acceptedClips() != clips.size()) {
				errors.add("manifest: counts.accepted_clips is " + manifest.counts().acceptedClips() + " but " + clips.size() + " clips are stored");
			}
		}

		return errors;
	}

	/** A clip's files: present, of the recorded size and hash, and one frame record per frame. */
	private static List<String> files(Path root, ClipRecord clip) throws IOException {
		List<String> errors = new ArrayList<>();
		for (String[] file : new String[][] {
			{clip.rgb().path(), Long.toString(clip.rgb().bytes()), clip.rgb().sha256()},
			{clip.visibleMask().path(), Long.toString(clip.visibleMask().bytes()), clip.visibleMask().sha256()},
			{clip.frames().path(), Long.toString(clip.frames().bytes()), clip.frames().sha256()}
		}) {
			Path path = root.resolve(file[0]);
			if (!Files.isRegularFile(path)) {
				errors.add(clip.clipId() + ": " + file[0] + " is missing");
			} else if (Files.size(path) != Long.parseLong(file[1])) {
				errors.add(clip.clipId() + ": " + file[0] + " has " + Files.size(path) + " bytes, not " + file[1]);
			} else if (!ClipWriter.sha256(path).equals(file[2])) {
				errors.add(clip.clipId() + ": " + file[0] + " does not match its SHA-256");
			}
		}

		Path frames = root.resolve(clip.frames().path());
		if (!Files.isRegularFile(frames)) {
			return errors;
		}

		List<FrameRecord> records = parse(Files.readAllBytes(frames), clip.frames().path(), FrameRecord::fromJson, errors);
		if (records.size() != clip.frameCount()) {
			errors.add(clip.clipId() + ": " + records.size() + " frame records for " + clip.frameCount() + " frames");
		}

		for (int i = 0; i < records.size(); i++) {
			FrameRecord frame = records.get(i);
			if (!frame.clipId().equals(clip.clipId()) || frame.frame() != i || frame.framesPerTick() != clip.framesPerTick()) {
				errors.add(clip.clipId() + ": frame record " + i + " is out of place (" + frame + ")");
				break;
			}
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
