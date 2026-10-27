package io.github.thedeadbolt.honeyfactory.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.clip.ClipDriver;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.ClipSeeds;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.contract.DatasetKind;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.dataset.ClipValidator;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clip generation against a scripted body: records, frames and videos agree, and failed clips are redrawn. */
class ClipGeneratorTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
	private static final StorageBudget.DiskSpace DISK = path -> 1L << 50;
	private static final int TICKS = 4;

	@TempDir
	Path temp;

	private ResolvedConfig config(Path root) {
		return ConfigResolver.resolve(("""
			{
			  "dataset": {"name": "m12-clip-unit", "seed": 7, "difficulty": 1},
			  "capture": {"width": 64, "height": 32, "cube_face_size": 16},
			  "quotas": {"accepted_scenes": 2, "scene_attempts_per_accepted_scene": 3},
			  "target": {"present_fraction": 1.0},
			  "splits": {"train": 0.5, "validation": 0.5, "test": 0.0},
			  "clips": {"clips_per_scene": 2, "frames_per_tick": 3,
			            "ticks": {"approach": %d, "pass_by": %d, "scan": %d, "occlusion": %d, "detour": %d, "no_honey": %d},
			            "mix": {"approach": 0, "pass_by": 1, "scan": 1, "occlusion": 0, "detour": 0}},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1000000}
			}
			""").formatted(TICKS, TICKS, TICKS, TICKS, TICKS, TICKS, root.toString().replace("\\", "\\\\")));
	}

	private static DatasetGenerator.Result run(ClipGenerator generator, ScriptedClipEnvironment environment) throws IOException {
		CompletableFuture<DatasetGenerator.Result> result = generator.start();
		environment.pumpUntil(result);
		return result.join();
	}

	private static List<String> lines(Path file) throws IOException {
		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	@Test
	void writesClipsWithFrameCountsAndValidRecords() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		DatasetGenerator.Result result = run(new ClipGenerator(config(this.temp.resolve("a")), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		DatasetManifest manifest = result.manifest();
		assertEquals(DatasetKind.CLIPS, manifest.kind());
		assertEquals(DatasetManifest.Lifecycle.COMPLETE, manifest.lifecycle());
		assertEquals(2, manifest.counts().acceptedScenes());
		assertEquals(4, manifest.counts().acceptedClips());
		assertTrue(environment.ended);

		List<String> clips = lines(result.directory().resolve(DatasetWriter.CLIPS));
		assertEquals(4, clips.size());
		for (String line : clips) {
			ClipRecord clip = ClipRecord.fromJson(JsonFields.parseObject(line));
			assertTrue(clip.clipType() == ClipType.SCAN || clip.clipType() == ClipType.PASS_BY, clip.clipType().toString());
			assertEquals(1 + TICKS * 3, clip.frameCount());
			List<String> frames = lines(result.directory().resolve(clip.frames().path()));
			assertEquals(13, frames.size());
			for (int f = 0; f < frames.size(); f++) {
				FrameRecord frame = FrameRecord.fromJson(JsonFields.parseObject(frames.get(f)));
				assertEquals(f, frame.frame());
				assertEquals(f % 3 == 0, frame.tickLabels() != null, "tick labels on frame " + f);
			}

			assertEquals(13, videoFrames(result.directory().resolve(clip.rgb().path())));
			assertEquals(13, videoFrames(result.directory().resolve(clip.visibleMask().path())));
			assertEquals(64, clip.rgb().width());
			assertEquals(TICKS, clip.executed().size());
		}

		assertEquals(List.of(), ClipValidator.validate(result.directory()).errors());
		assertNoPartials(result.directory());

		ScriptedClipEnvironment again = new ScriptedClipEnvironment();
		DatasetGenerator.Result repeat = run(new ClipGenerator(config(this.temp.resolve("b")), again, CLOCK, DISK), again);
		assertArrayEquals(Files.readAllBytes(result.directory().resolve(DatasetWriter.SCENES)), Files.readAllBytes(repeat.directory().resolve(DatasetWriter.SCENES)));
		assertEquals(environment.executed, again.executed, "the drivers are deterministic per seed");
	}

	@Test
	void rejectsAndRedrawsAfterOutOfArena() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		environment.leaveArena = (reset, tick) -> reset == 1 && tick == 2;
		ResolvedConfig config = config(this.temp.resolve("c"));
		DatasetGenerator.Result result = run(new ClipGenerator(config, environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		ClipRecord first = ClipRecord.fromJson(JsonFields.parseObject(lines(result.directory().resolve(DatasetWriter.CLIPS)).getFirst()));
		assertEquals(1, first.attempt());
		int scene = Integer.parseInt(first.sceneId().substring(1));
		assertEquals(ClipSeeds.derive(config.seed(), scene, first.clipIndex(), 1), first.seeds());
		assertEquals(4, result.manifest().counts().acceptedClips());
		assertEquals(Map.of("out_of_arena/" + first.clipType().jsonName(), 1), result.manifest().counts().clipRedraws(), "the redraw is counted, not hidden");
		assertNoPartials(result.directory());
	}

	@Test
	void walkingDownhillIsNotAFall() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		environment.climb = (reset, tick) -> -1.0;
		DatasetGenerator.Result result = run(new ClipGenerator(config(this.temp.resolve("downhill")), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		assertEquals(Map.of(), result.manifest().counts().clipRedraws(), "standing one block lower each tick is walking, not falling");
		assertEquals(4, result.manifest().counts().acceptedClips());
	}

	@Test
	void oneDeepDropIsAFall() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		environment.climb = (reset, tick) -> reset == 1 ? -1.0 : 0.0;
		environment.airborne = (reset, tick) -> reset == 1;
		DatasetGenerator.Result result = run(new ClipGenerator(config(this.temp.resolve("drop")), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		ClipRecord first = ClipRecord.fromJson(JsonFields.parseObject(lines(result.directory().resolve(DatasetWriter.CLIPS)).getFirst()));
		assertEquals(Map.of("fall/" + first.clipType().jsonName(), 1), result.manifest().counts().clipRedraws());
	}

	@Test
	void everyClipIsAnnouncedBeforeItStarts() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		DatasetGenerator.Result result = run(new ClipGenerator(allTypes(this.temp.resolve("j"), 1), environment, CLOCK, DISK), environment);
		List<String> announced = environment.announcements;
		assertEquals(environment.resets(), announced.size(), "one announcement per clip attempt");
		for (ClipRecord clip : clips(result)) {
			assertTrue(announced.contains(clip.clipId() + " · " + ClipDriver.describe(clip.clipType())), clip.clipId() + " was announced: " + announced);
		}
	}

	@Test
	void detourAndOcclusionClipsRecordReachingAndEndOnIt() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		DatasetGenerator.Result result = run(new ClipGenerator(allTypes(this.temp.resolve("i"), 3), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		for (ClipRecord clip : clips(result)) {
			boolean seeking = clip.clipType() == ClipType.APPROACH || clip.clipType() == ClipType.DETOUR || clip.clipType() == ClipType.OCCLUSION;
			assertEquals(seeking, clip.summary().reached() != null, clip.clipId() + " " + clip.clipType());
			if (Boolean.TRUE.equals(clip.summary().reached())) {
				assertEquals(ClipRecord.EndReason.REACHED, clip.endReason(), clip.clipId());
				assertNull(clip.summary().missReason());
			} else if (seeking) {
				assertNotNull(clip.summary().missReason(), clip.clipId() + " names why it missed");
			}
		}
	}

	private ResolvedConfig allTypes(Path root, int level) {
		return ConfigResolver.resolve(("""
			{
			  "dataset": {"name": "m12-clip-types", "seed": 11, "difficulty": %d},
			  "capture": {"width": 64, "height": 32, "cube_face_size": 16},
			  "quotas": {"accepted_scenes": 4, "scene_attempts_per_accepted_scene": 3},
			  "target": {"present_fraction": 0.75},
			  "splits": {"train": 0.5, "validation": 0.5, "test": 0.0},
			  "clips": {"clips_per_scene": 3, "frames_per_tick": 1,
			            "ticks": {"approach": 100, "pass_by": 8, "scan": 8, "occlusion": 8, "detour": 30, "no_honey": 8},
			            "approach_linger_ticks": 4, "drift": {"rate": 0.3}, "teacher": {"min_visible_pixels": 1}},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1000000}
			}
			""").formatted(level, root.toString().replace("\\", "\\\\")));
	}

	private static List<ClipRecord> clips(DatasetGenerator.Result result) throws IOException {
		return lines(result.directory().resolve(DatasetWriter.CLIPS)).stream().map(line -> ClipRecord.fromJson(JsonFields.parseObject(line))).toList();
	}

	@Test
	void everyTypeRunsAndLevelOneHasNoOcclusionOrDetour() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		DatasetGenerator.Result result = run(new ClipGenerator(allTypes(this.temp.resolve("e"), 1), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		List<ClipRecord> clips = clips(result);
		assertEquals(12, clips.size());
		for (ClipRecord clip : clips) {
			assertTrue(clip.requestedType() != ClipType.OCCLUSION && clip.requestedType() != ClipType.DETOUR, "level 1 gives their share away: " + clip.clipId());
			assertEquals(clip.requestedType(), clip.clipType());
		}

		assertTrue(clips.stream().anyMatch(clip -> clip.clipType() == ClipType.NO_HONEY), "target-absent scenes give no-honey clips");
		assertEquals(List.of(), ClipValidator.validate(result.directory()).errors());
	}

	@Test
	void approachClipsEndOnceTheHoneyIsReached() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		DatasetGenerator.Result result = run(new ClipGenerator(allTypes(this.temp.resolve("f"), 1), environment, CLOCK, DISK), environment);
		List<ClipRecord> approaches = clips(result).stream().filter(clip -> clip.clipType() == ClipType.APPROACH).toList();
		assertTrue(!approaches.isEmpty(), "the mix draws approach clips");
		for (ClipRecord clip : approaches) {
			if (Boolean.TRUE.equals(clip.summary().reached())) {
				assertEquals(ClipRecord.EndReason.REACHED, clip.endReason());
				assertTrue(clip.tickCount() < 100, clip.clipId() + " ran " + clip.tickCount() + " ticks");
			} else {
				assertEquals(100, clip.tickCount());
			}
		}

		assertTrue(approaches.stream().anyMatch(clip -> Boolean.TRUE.equals(clip.summary().reached())), "the teacher reaches the honey");
	}

	@Test
	void driftTicksAreRecordedOnEveryTick() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		DatasetGenerator.Result result = run(new ClipGenerator(allTypes(this.temp.resolve("g"), 1), environment, CLOCK, DISK), environment);
		int drifting = 0;
		for (ClipRecord clip : clips(result)) {
			int flagged = 0;
			for (String line : lines(result.directory().resolve(clip.frames().path()))) {
				FrameRecord frame = FrameRecord.fromJson(JsonFields.parseObject(line));
				flagged += frame.tickLabels() != null && frame.tickLabels().drifting() ? 1 : 0;
			}

			assertEquals(clip.summary().driftTicks(), flagged, clip.clipId());
			boolean scripted = clip.clipType() == ClipType.SCAN || clip.clipType() == ClipType.PASS_BY;
			assertTrue(!scripted || flagged == 0, "scripted clips do not drift: " + clip.clipId());
			drifting += flagged;
		}

		assertTrue(drifting > 0, "a drift rate of 0.3 drifts some ticks");
	}

	@Test
	void validatesOffTheEnvironmentThreadAndCompletesOnIt() throws IOException {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		ClipGenerator generator = new ClipGenerator(config(this.temp.resolve("h")), environment, CLOCK, DISK);
		CompletableFuture<String> completedOn = new CompletableFuture<>();
		CompletableFuture<DatasetGenerator.Result> result = generator.start().whenComplete((value, error) -> completedOn.complete(Thread.currentThread().getName()));
		environment.pumpUntil(result);
		assertEquals("complete", result.join().manifest().lifecycle().jsonName());
		assertEquals(ClipGenerator.CHECK_THREAD, generator.validatedOn(), "validation runs on the checks thread, not the game's");
		assertEquals(Thread.currentThread().getName(), completedOn.join(), "the result completes on the environment's executor");
	}

	@Test
	void ffmpegMissingFailsBeforeFirstScene() {
		ScriptedClipEnvironment environment = new ScriptedClipEnvironment();
		Path root = this.temp.resolve("d");
		ClipGenerator generator = new ClipGenerator(config(root), environment, CLOCK, DISK, () -> {
			throw new IllegalStateException("ffmpeg is required for clip datasets: not found");
		});
		IllegalStateException error = assertThrows(IllegalStateException.class, generator::start);
		assertTrue(error.getMessage().contains("ffmpeg is required"), error.getMessage());
		assertFalse(Files.exists(root));
		assertEquals(0, environment.resets());
	}

	private static void assertNoPartials(Path dataset) throws IOException {
		try (Stream<Path> files = Files.walk(dataset)) {
			assertEquals(List.of(), files.filter(path -> path.getFileName().toString().endsWith(".partial")).toList());
		}
	}

	private static int videoFrames(Path file) throws IOException {
		Process process = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", file.toString())
			.redirectErrorStream(true).start();
		try (InputStream out = process.getInputStream()) {
			String text = new String(out.readAllBytes(), StandardCharsets.UTF_8).strip();
			assertEquals(0, process.waitFor(), text);
			return Integer.parseInt(text);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}
}
