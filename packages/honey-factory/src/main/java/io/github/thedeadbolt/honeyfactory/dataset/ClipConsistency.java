package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.contract.Driver;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The cross-record and file rules of clip datasets (M12-2), as honey-factory-tools applies
 * them; error messages share their key phrases with it, so the shared cases test both.
 *
 * <ul>
 *   <li>clips: unique IDs, an existing scene of the same split, no-honey clips exactly in
 *       target-absent scenes, each scene's clip count and the manifest's counts;
 *   <li>frames: one record per frame, in order; the teacher's realizability on every tick
 *       (a seen belief needs the observed frame to show at least the visible threshold and
 *       names the scene's honey, memory names the last sighting, nothing is seen without
 *       honey); executed commands as the clip records them and within the motor limits;
 *       drift ticks, ticks by mode and ticks by driver as the ticks record them; scripts
 *       drive only scan and pass-by clips and the start of an occlusion clip;
 *   <li>files: videos holding every frame, and each frame's mask pixel count and box as
 *       its record states.
 * </ul>
 */
public final class ClipConsistency {
	private ClipConsistency() {
	}

	/** Clips against their scenes and the manifest, after the scene rules. */
	public static List<String> check(DatasetManifest manifest, List<SceneRecord> scenes, List<ClipRecord> clips) {
		List<String> errors = new ArrayList<>();
		Map<String, SceneRecord> byId = DatasetConsistency.checkScenes(manifest, scenes, errors);
		int framesPerTick = manifest.clipSettings().framesPerTick();
		Set<String> ids = new HashSet<>();
		Map<String, Integer> perScene = new HashMap<>();
		for (ClipRecord clip : clips) {
			String label = "clip " + clip.clipId() + ": ";
			if (!ids.add(clip.clipId())) {
				errors.add(label + "duplicate clip_id");
			}

			SceneRecord scene = byId.get(clip.sceneId());
			if (scene == null) {
				errors.add(label + "references missing scene " + clip.sceneId());
				continue;
			}

			perScene.merge(clip.sceneId(), 1, Integer::sum);
			if (scene.split() != clip.split()) {
				errors.add(label + "split " + clip.split().jsonName() + " differs from its scene's " + scene.split().jsonName());
			}

			if (scene.target().present() == (clip.clipType() == ClipType.NO_HONEY)) {
				errors.add(label + "no-honey clips belong exactly to target-absent scenes");
			}

			if (clip.framesPerTick() != framesPerTick) {
				errors.add(label + "frames_per_tick differs from the dataset's " + framesPerTick);
			}
		}

		for (SceneRecord scene : scenes) {
			int count = perScene.getOrDefault(scene.sceneId(), 0);
			if (scene.acceptedClipCount() == null) {
				errors.add("scene " + scene.sceneId() + ": lacks accepted_clip_count");
			} else if (scene.acceptedClipCount() != count) {
				errors.add("scene " + scene.sceneId() + ": records " + scene.acceptedClipCount() + " clips but has " + count);
			}
		}

		DatasetManifest.Counts counts = manifest.counts();
		if (counts.acceptedScenes() != scenes.size()) {
			errors.add("manifest: counts.accepted_scenes is " + counts.acceptedScenes() + " but there are " + scenes.size() + " scenes");
		}

		if (counts.acceptedClips() != clips.size()) {
			errors.add("manifest: counts.accepted_clips is " + counts.acceptedClips() + " but there are " + clips.size() + " clips");
		}

		if (manifest.lifecycle() == DatasetManifest.Lifecycle.COMPLETE) {
			int present = (int) scenes.stream().filter(scene -> scene.target().present()).count();
			if (manifest.slice() == null && (present != manifest.targetPresentScenes() || scenes.size() - present != manifest.targetAbsentScenes())) {
				errors.add("manifest: " + present + " present and " + (scenes.size() - present) + " absent scenes miss the quotas");
			}

			Map<Split, int[]> totals = new EnumMap<>(Split.class);
			scenes.forEach(scene -> totals.computeIfAbsent(scene.split(), split -> new int[2])[0]++);
			clips.forEach(clip -> totals.computeIfAbsent(clip.split(), split -> new int[2])[1]++);
			for (Split split : Split.values()) {
				DatasetManifest.SplitCount recorded = manifest.splitCounts().get(split);
				int[] found = totals.getOrDefault(split, new int[2]);
				if (recorded.scenes() != found[0] || recorded.clips() != found[1]) {
					errors.add("manifest: splits.counts." + split.jsonName() + " does not match the " + found[0] + " scenes and " + found[1] + " clips");
				}
			}
		}

		return errors;
	}

	/** One clip's frame records: order, the teacher's realizability, commands, drift and modes. */
	public static List<String> frames(DatasetManifest manifest, @Nullable SceneRecord scene, ClipRecord clip, List<FrameRecord> frames) {
		String label = "clip " + clip.clipId() + ": ";
		List<String> errors = new ArrayList<>();
		if (frames.size() != clip.frameCount()) {
			errors.add(label + frames.size() + " frame records for " + clip.frameCount() + " frames");
		}

		for (int i = 0; i < frames.size(); i++) {
			FrameRecord frame = frames.get(i);
			if (!frame.clipId().equals(clip.clipId()) || frame.frame() != i || frame.framesPerTick() != clip.framesPerTick()) {
				errors.add(label + "frame record " + i + " is out of place");
				return errors;
			}
		}

		ClipSettings settings = manifest.clipSettings();
		MotorLimits limits = settings.motor();
		int[] honey = scene == null ? null : scene.target().position();
		Map<Integer, FrameRecord> ticks = new HashMap<>();
		for (FrameRecord frame : frames) {
			if (frame.tickLabels() != null) {
				ticks.put(frame.tick(), frame);
			}
		}

		List<MotorCommand> executed = new ArrayList<>();
		int drifting = 0;
		Map<TeacherMode, Integer> modes = new EnumMap<>(TeacherMode.class);
		Map<Driver, Integer> drivers = new EnumMap<>(Driver.class);
		boolean scriptedType = clip.clipType() == ClipType.SCAN || clip.clipType() == ClipType.PASS_BY;
		boolean teacherTookOver = false;
		int[] lastSeen = null;
		for (int tick = 1; tick <= clip.tickCount(); tick++) {
			FrameRecord frame = ticks.get(tick);
			if (frame == null) {
				errors.add(label + "tick " + tick + " has no labelled frame");
				continue;
			}

			FrameRecord.TickLabels labels = frame.tickLabels();
			FrameRecord.Belief belief = labels.belief();
			FrameRecord observed = ticks.get(tick - 1);
			int shown = observed == null ? 0 : observed.target().visiblePixels();
			if (belief.source() == FrameRecord.Belief.Source.SEEN) {
				if (honey == null) {
					errors.add(label + "tick " + tick + ": honey seen in a scene without honey");
				} else if (!Arrays.equals(belief.position(), honey)) {
					errors.add(label + "tick " + tick + ": the seen position " + Arrays.toString(belief.position()) + " is not the scene's honey " + Arrays.toString(honey));
				}

				if (shown < settings.teacher().minVisiblePixels()) {
					errors.add(label + "tick " + tick + ": honey seen while the observed frame showed " + shown + " of the " + settings.teacher().minVisiblePixels() + " pixels needed");
				}

				lastSeen = belief.position();
			} else if (belief.source() == FrameRecord.Belief.Source.MEMORY && !Arrays.equals(belief.position(), lastSeen)) {
				errors.add(label + "tick " + tick + ": the remembered position " + Arrays.toString(belief.position()) + " differs from the last sighting " + Arrays.toString(lastSeen));
			}

			for (MotorCommand command : new MotorCommand[] {labels.executed(), labels.teacher()}) {
				try {
					limits.check(command);
				} catch (IllegalArgumentException e) {
					errors.add(label + "tick " + tick + ": a command is outside the motor limits: " + e.getMessage());
				}
			}

			executed.add(labels.executed());
			drifting += labels.drifting() ? 1 : 0;
			modes.merge(labels.mode(), 1, Integer::sum);
			Driver driver = labels.driver();
			drivers.merge(driver, 1, Integer::sum);
			if (driver == Driver.SCRIPT) {
				if (!scriptedType && clip.clipType() != ClipType.OCCLUSION) {
					errors.add(label + "tick " + tick + ": driven by a script in a " + clip.clipType().jsonName() + " clip");
				} else if (teacherTookOver) {
					errors.add(label + "tick " + tick + ": script ticks after the teacher took over");
				}
			} else if (scriptedType) {
				errors.add(label + "tick " + tick + ": a " + clip.clipType().jsonName() + " clip is driven by its script, not " + driver.jsonName());
			} else {
				teacherTookOver = true;
			}
		}

		if (!executed.equals(clip.executed())) {
			errors.add(label + "executed commands differ from its ticks' executed commands");
		}

		if (clip.summary().driftTicks() != drifting) {
			errors.add(label + "summary.drift_ticks is " + clip.summary().driftTicks() + " but " + drifting + " ticks drifted");
		}

		for (TeacherMode mode : TeacherMode.values()) {
			if (clip.summary().ticksByMode().getOrDefault(mode, 0).intValue() != modes.getOrDefault(mode, 0).intValue()) {
				errors.add(label + "summary.ticks_by_mode differs from the ticks' modes " + modes);
				break;
			}
		}

		for (Driver driver : Driver.values()) {
			if (clip.summary().ticksByDriver().getOrDefault(driver, 0).intValue() != drivers.getOrDefault(driver, 0).intValue()) {
				errors.add(label + "summary.ticks_by_driver differs from the ticks' drivers " + drivers);
				break;
			}
		}

		return errors;
	}

	/** A clip's videos hold every frame, and each frame's mask matches its record's pixel count and box. */
	public static List<String> videos(Path root, ClipRecord clip, List<FrameRecord> frames, boolean pixels) throws IOException {
		String label = "clip " + clip.clipId() + ": ";
		List<String> errors = new ArrayList<>();
		for (ClipRecord.VideoFile video : List.of(clip.rgb(), clip.visibleMask())) {
			int held = frameCount(root.resolve(video.path()));
			if (held != clip.frameCount()) {
				errors.add(label + video.path() + ": the video holds " + held + " frames, not " + clip.frameCount());
			}
		}

		if (!pixels || !errors.isEmpty()) {
			return errors;
		}

		ClipRecord.VideoFile mask = clip.visibleMask();
		int size = mask.width() * mask.height();
		Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-i", root.resolve(mask.path()).toString(), "-f", "rawvideo", "-pix_fmt", "gray", "-")
			.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try (InputStream in = process.getInputStream()) {
			for (int index = 0; index < frames.size(); index++) {
				byte[] pixelsOfFrame = in.readNBytes(size);
				if (pixelsOfFrame.length < size) {
					break;
				}

				MaskStatistics found = MaskStatistics.of(pixelsOfFrame, mask.width(), mask.height());
				FrameRecord frame = frames.get(index);
				if (found.positivePixels() != frame.target().visiblePixels() || !Arrays.equals(found.bboxXywh(), frame.target().visibleBboxXywh())) {
					errors.add(label + "frame " + index + ": the label (" + frame.target().visiblePixels() + " pixels, box " + Arrays.toString(frame.target().visibleBboxXywh())
						+ ") does not match its mask (" + found.positivePixels() + " pixels, box " + Arrays.toString(found.bboxXywh()) + ")");
				}
			}

			in.transferTo(java.io.OutputStream.nullOutputStream());
		} finally {
			waitFor(process);
		}

		return errors;
	}

	/**
	 * The frames in a video, counted from its container's packets ({@code ffprobe
	 * -count_packets}): one packet per frame in the codecs clips use, and nothing is decoded.
	 */
	static int frameCount(Path video) throws IOException {
		Process process = new ProcessBuilder("ffprobe", "-v", "error", "-count_packets", "-select_streams", "v:0", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", video.toString())
			.redirectErrorStream(true).start();
		try (InputStream in = process.getInputStream()) {
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
			waitFor(process);
			try {
				return Integer.parseInt(text);
			} catch (NumberFormatException e) {
				throw new IOException("ffprobe could not count the frames of " + video + ": " + text, e);
			}
		}
	}

	private static void waitFor(Process process) throws IOException {
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for ffmpeg", e);
		}
	}
}
