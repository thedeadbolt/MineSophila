package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * M12-2's first clips: a level-1 clip dataset of scan and pass-by clips in the production
 * panorama (2480 × 1280, 1024² faces), 32 ticks at 3 frames per tick. Checks that it
 * completes, that every video is a full-size panorama holding every frame, and that a scan
 * clip's honey crosses the panorama's edge with each frame's label matching that frame's own
 * mask. Output goes to {@code build/m12-clip/<label>/}. Runs only when named
 * ({@code -Dhoneyfactory.gametests=M12Clip}).
 */
public class M12ClipTest implements FabricClientGameTest {
	private static final String WORLD = "honey-factory-m12-clip";
	private static final int TIMEOUT_TICKS = 20 * 60 * 30;
	private static final int TICKS = 32;
	private static final String CONFIG = """
		{
		  "dataset": {"name": "m12-clip-l1", "seed": 12026, "difficulty": 1},
		  "capture": {"mode": "PANORAMA_310", "width": 2480, "height": 1280, "cube_face_size": 1024},
		  "quotas": {"accepted_scenes": 2, "scene_attempts_per_accepted_scene": 3},
		  "target": {"present_fraction": 1.0},
		  "splits": {"train": 0.5, "validation": 0.5, "test": 0.0},
		  "clips": {"clips_per_scene": 4, "frames_per_tick": 3,
		            "ticks": {"pass_by": %d, "scan": %d},
		            "mix": {"approach": 0, "pass_by": 1, "scan": 3, "occlusion": 0, "detour": 0}},
		  "output": {"root": "%s"}
		}
		""";
	/** Level 3 with every clip type, the default mix and lengths, drift and the realizable teacher. */
	private static final String ALL_TYPES = """
		{
		  "dataset": {"name": "m12-clip-l3", "seed": 12027, "difficulty": 3},
		  "quotas": {"accepted_scenes": 3, "scene_attempts_per_accepted_scene": 3},
		  "target": {"present_fraction": 0.67},
		  "splits": {"train": 0.67, "validation": 0.33, "test": 0.0},
		  "clips": {"clips_per_scene": 4},
		  "output": {"root": "%s"}
		}
		""";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		String label = System.getProperty("honeyfactory.acceptanceLabel", "");
		Path root = AcceptanceRun.directory(GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m12-clip").toString(), "",
			label.isBlank() ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm", Locale.ROOT)) : label);
		String config = CONFIG.formatted(TICKS, TICKS, root.toString().replace("\\", "\\\\"));
		GameTestSupport.pinCaptureSettings(context);
		JsonObject[] metrics = new JsonObject[1];
		AcceptanceRun.inWorld(context, root, WORLD, 12_026L, world -> metrics[0] = AcceptanceRun.generate(context, root, "m12-clip-l1", config, TIMEOUT_TICKS));
		if (!"complete".equals(metrics[0].get("lifecycle").getAsString())) {
			throw new AssertionError("the clip dataset did not complete: " + metrics[0].get("failure") + " " + metrics[0].get("validation_errors"));
		}

		Path dataset = root.resolve(metrics[0].get("directory").getAsString());
		List<String> failures = new ArrayList<>();
		try {
			check(dataset, failures);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		JsonObject[] level3 = new JsonObject[1];
		String allTypes = ALL_TYPES.formatted(root.toString().replace("\\", "\\\\"));
		AcceptanceRun.inWorld(context, root, WORLD + "-l3", 12_027L, world -> level3[0] = AcceptanceRun.generate(context, root, "m12-clip-l3", allTypes, TIMEOUT_TICKS));
		if (!"complete".equals(level3[0].get("lifecycle").getAsString())) {
			failures.add("the level-3 clip dataset did not complete: " + level3[0].get("failure") + " " + level3[0].get("validation_errors"));
		} else {
			try {
				types(root.resolve(level3[0].get("directory").getAsString()), failures);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M12 clip failures:\n" + String.join("\n", failures));
		}
	}

	private static void check(Path dataset, List<String> failures) throws IOException {
		DatasetManifest manifest = DatasetManifest.fromJson(JsonFields.parseObject(Files.readString(dataset.resolve(DatasetWriter.MANIFEST), StandardCharsets.UTF_8)));
		if (manifest.captureMode() != CaptureMode.PANORAMA_310 || manifest.captureWidth() != 2480 || manifest.captureHeight() != 1280) {
			failures.add("the dataset is not a 2480 x 1280 panorama: " + manifest.captureMode() + " " + manifest.captureWidth() + "x" + manifest.captureHeight());
		}

		boolean crossed = false;
		for (String line : Files.readAllLines(dataset.resolve(DatasetWriter.CLIPS), StandardCharsets.UTF_8)) {
			ClipRecord clip = ClipRecord.fromJson(JsonFields.parseObject(line));
			for (ClipRecord.VideoFile video : List.of(clip.rgb(), clip.visibleMask())) {
				String probed = probe(dataset.resolve(video.path()));
				String expected = "2480,1280," + (1 + TICKS * 3);
				if (!probed.equals(expected)) {
					failures.add(video.path() + ": ffprobe reports " + probed + ", expected " + expected);
				}
			}

			if (clip.clipType() != ClipType.SCAN || crossed) {
				continue;
			}

			List<FrameRecord> frames = Files.readAllLines(dataset.resolve(clip.frames().path()), StandardCharsets.UTF_8).stream()
				.map(text -> FrameRecord.fromJson(JsonFields.parseObject(text))).toList();
			for (int f = 1; f < frames.size(); f++) {
				TargetStatus before = frames.get(f - 1).target().targetStatus();
				TargetStatus after = frames.get(f).target().targetStatus();
				if ((before == TargetStatus.OUT_OF_VIEW) != (after == TargetStatus.OUT_OF_VIEW) && frames.get(f).tickLabels() == null) {
					crossed = true;
					for (FrameRecord frame : List.of(frames.get(f - 1), frames.get(f))) {
						int pixels = maskPixels(dataset.resolve(clip.visibleMask().path()), frame.frame());
						if (pixels != frame.target().visiblePixels()) {
							failures.add(clip.clipId() + " frame " + frame.frame() + ": mask has " + pixels + " pixels, its label " + frame.target().visiblePixels());
						}
					}

					HoneyFactory.LOGGER.info("M12 clip: {} crosses the panorama's edge between frames {} and {}", clip.clipId(), f - 1, f);
					break;
				}
			}
		}

		if (!crossed) {
			failures.add("no scan clip's honey crossed the panorama's edge on an in-between frame");
		}
	}

	/** Every clip's videos hold all its frames at full size; the types drawn and how approaches ended are logged. */
	private static void types(Path dataset, List<String> failures) throws IOException {
		java.util.Map<String, Integer> types = new java.util.TreeMap<>();
		for (String line : Files.readAllLines(dataset.resolve(DatasetWriter.CLIPS), StandardCharsets.UTF_8)) {
			ClipRecord clip = ClipRecord.fromJson(JsonFields.parseObject(line));
			String type = clip.requestedType() == clip.clipType() ? clip.clipType().jsonName() : clip.requestedType().jsonName() + "->" + clip.clipType().jsonName();
			types.merge(type, 1, Integer::sum);
			String probed = probe(dataset.resolve(clip.rgb().path()));
			String expected = "2480,1280," + clip.frameCount();
			if (!probed.equals(expected)) {
				failures.add(clip.rgb().path() + ": ffprobe reports " + probed + ", expected " + expected);
			}

			HoneyFactory.LOGGER.info("M12 clip: {} {} {} ticks, ended {}, reached {}, drift {} ticks, modes {}", clip.clipId(), type, clip.tickCount(), clip.endReason().jsonName(),
				clip.summary().reached(), clip.summary().driftTicks(), clip.summary().ticksByMode());
		}

		HoneyFactory.LOGGER.info("M12 clip: level-3 types {}", types);
	}

	/** {@code width,height,frames} of a video's first stream. */
	private static String probe(Path video) throws IOException {
		return run("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=width,height,nb_read_frames", "-of", "csv=p=0", video.toString()).strip();
	}

	/** Visible pixels in frame {@code index} of a mask video. */
	private static int maskPixels(Path video, int index) throws IOException {
		Process process = new ProcessBuilder("ffmpeg", "-v", "error", "-i", video.toString(), "-vf", "select=eq(n\\," + index + ")", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-")
			.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try (InputStream in = process.getInputStream()) {
			byte[] pixels = in.readAllBytes();
			process.waitFor();
			int count = 0;
			for (byte pixel : pixels) {
				count += pixel != 0 ? 1 : 0;
			}

			return count;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}

	private static String run(String... command) throws IOException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		try (InputStream in = process.getInputStream()) {
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor();
			return text;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}
}
