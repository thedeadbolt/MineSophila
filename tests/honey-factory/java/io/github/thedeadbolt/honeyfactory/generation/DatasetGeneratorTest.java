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
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatasetGeneratorTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
	/** Plenty of free space, whatever the test machine has. */
	private static final StorageBudget.DiskSpace DISK = path -> 1L << 50;

	@TempDir
	Path temp;

	private ResolvedConfig config(Path root, int scenes) {
		return config(root, scenes, 1, 48, 32);
	}

	private ResolvedConfig config(Path root, int scenes, int level, int width, int height) {
		return ConfigResolver.resolve(configJson(root, scenes, level, width, height));
	}

	private String configJson(Path root, int scenes, int level, int width, int height) {
		return ("""
			{
			  "dataset": {"name": "m3-unit", "seed": 7, "difficulty": %d},
			  "viewpoints": {"count": 3},
			  "capture": {"width": %d, "height": %d},
			  "quotas": {"accepted_scenes": %d, "scene_attempts_per_accepted_scene": 3},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1000000}
			}
			""").formatted(level, width, height, scenes, root.toString().replace("\\", "\\\\"));
	}

	private static ResolvedConfig naturalConfig(Path root, int scenes, int padding) {
		return ConfigResolver.resolve(("""
			{
			  "dataset": {"name": "m6-unit", "seed": 11, "difficulty": 5},
			  "scene": {"base": {"type": "natural_patch", "context_padding": %d, "scenes_per_patch": 2}},
			  "viewpoints": {"count": 2},
			  "capture": {"width": 48, "height": 32},
			  "quotas": {"accepted_scenes": %d, "scene_attempts_per_accepted_scene": 3},
			  "splits": {"train": 0.6, "validation": 0.2, "test": 0.2},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1000000}
			}
			""").formatted(padding, scenes, root.toString().replace("\\", "\\\\")));
	}

	private static String naturalJson(Path root, int scenes, int padding) {
		return ("""
			{
			  "dataset": {"name": "m6-unit", "seed": 11, "difficulty": 5},
			  "scene": {"base": {"type": "natural_patch", "context_padding": %d, "scenes_per_patch": 2}},
			  "viewpoints": {"count": 2},
			  "capture": {"width": 48, "height": 32},
			  "quotas": {"accepted_scenes": %d, "scene_attempts_per_accepted_scene": 3},
			  "splits": {"train": 0.6, "validation": 0.2, "test": 0.2},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1000000}
			}
			""").formatted(padding, scenes, root.toString().replace("\\", "\\\\"));
	}

	private static DatasetGenerator.Result run(DatasetGenerator generator, ScriptedEnvironment environment) throws IOException {
		CompletableFuture<DatasetGenerator.Result> result = generator.start();
		environment.pump();
		assertTrue(result.isDone(), "generation waits on no further requests");
		return result.join();
	}

	@Test
	void generatesACompleteValidDatasetThatRegeneratesIdentically() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator.Result result = run(new DatasetGenerator(config(this.temp.resolve("b"), 8), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		DatasetManifest manifest = result.manifest();
		assertEquals(DatasetManifest.Lifecycle.COMPLETE, manifest.lifecycle());
		assertEquals(8, manifest.counts().acceptedScenes());
		assertEquals(24, manifest.counts().acceptedSamples());
		assertEquals(manifest.counts().attemptedScenes(), manifest.counts().acceptedScenes() + manifest.counts().rejectedScenes());

		DatasetValidator.Report report = DatasetValidator.validate(result.directory());
		assertEquals(java.util.List.of(), report.errors());
		assertEquals(6, report.scenes().stream().filter(scene -> scene.target().present()).count());
		for (SampleRecord sample : report.samples()) {
			TargetStatus expected = sample.target().targetPresent() ? TargetStatus.FULLY_VISIBLE : TargetStatus.ABSENT;
			assertEquals(expected, sample.target().targetStatus());
			assertEquals(48, sample.rgb().width());
		}

		int splitScenes = manifest.splitCounts().values().stream().mapToInt(DatasetManifest.SplitCount::scenes).sum();
		assertEquals(8, splitScenes);

		ScriptedEnvironment again = new ScriptedEnvironment();
		DatasetGenerator.Result repeat = run(new DatasetGenerator(config(this.temp.resolve("c"), 8), again, CLOCK, DISK), again);
		for (String index : new String[] {DatasetWriter.SCENES, DatasetWriter.SAMPLES}) {
			assertArrayEquals(Files.readAllBytes(result.directory().resolve(index)), Files.readAllBytes(repeat.directory().resolve(index)), index + " regenerates byte for byte");
		}
	}

	/** {@code json}'s output member with a slice of the scene groups. */
	private static String sliced(String json, int index, int count) {
		return json.replace("\"free_space_reserve_bytes\": 1000000}", "\"free_space_reserve_bytes\": 1000000, \"slice\": {\"index\": %d, \"count\": %d, \"world\": \"world-%d\"}}".formatted(index, count, index));
	}

	/** Index lines without the fields that depend on the order captures ran in. */
	private static List<String> stable(Path index) throws IOException {
		List<String> lines = new ArrayList<>();
		for (String line : Files.readAllLines(index)) {
			com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
			if (json.has("capture")) {
				json.getAsJsonObject("capture").remove("operation_id");
				json.getAsJsonObject("capture").remove("world_tick");
			}

			lines.add(json.toString());
		}

		return lines;
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void slicesTogetherHoldExactlyTheFullRunsScenesAndEachValidates(boolean natural) throws IOException {
		ResolvedConfig full = natural ? naturalConfig(this.temp.resolve("full"), 8, 192) : config(this.temp.resolve("full"), 8);
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator.Result whole = run(new DatasetGenerator(full, environment, CLOCK, DISK), environment);
		assertNull(whole.failure(), () -> whole.failure() + " " + whole.validationErrors());

		List<String> scenes = new ArrayList<>();
		List<String> samples = new ArrayList<>();
		for (int index = 0; index < 2; index++) {
			String base = natural ? naturalJson(this.temp.resolve("slices"), 8, 192) : configJson(this.temp.resolve("slices"), 8, 1, 48, 32);
			ResolvedConfig slice = ConfigResolver.resolve(sliced(base, index, 2));
			assertEquals(full.sha256(), slice.sha256(), "a slice keeps the full run's configuration hash");
			ScriptedEnvironment sliceEnvironment = new ScriptedEnvironment();
			DatasetGenerator.Result part = run(new DatasetGenerator(slice, sliceEnvironment, CLOCK, DISK), sliceEnvironment);
			assertNull(part.failure(), () -> part.failure() + " " + part.validationErrors());
			assertEquals(full.datasetId() + ".slice-" + index + "-of-2", part.directory().getFileName().toString());
			DatasetManifest.Slice recorded = part.manifest().slice();
			assertNotNull(recorded);
			assertEquals("world-" + index, recorded.world());
			assertEquals(recorded.slots().size(), part.manifest().counts().requestedScenes());
			assertEquals(List.of(), DatasetValidator.validate(part.directory()).errors());
			scenes.addAll(Files.readAllLines(part.directory().resolve(DatasetWriter.SCENES)));
			samples.addAll(stable(part.directory().resolve(DatasetWriter.SAMPLES)));
		}

		List<String> wholeScenes = Files.readAllLines(whole.directory().resolve(DatasetWriter.SCENES));
		assertEquals(wholeScenes.size(), scenes.size(), "no scene is missing or doubled");
		assertEquals(new java.util.HashSet<>(wholeScenes), new java.util.HashSet<>(scenes));
		assertEquals(new java.util.HashSet<>(stable(whole.directory().resolve(DatasetWriter.SAMPLES))), new java.util.HashSet<>(samples));
	}

	@Test
	void refusesAnExistingDatasetDirectory() throws IOException {
		ResolvedConfig config = config(this.temp, 2);
		ScriptedEnvironment environment = new ScriptedEnvironment();
		run(new DatasetGenerator(config, environment, CLOCK, DISK), environment);
		assertThrows(FileAlreadyExistsException.class, () -> new DatasetGenerator(config, new ScriptedEnvironment(), CLOCK, DISK).start());
	}

	@Test
	void invisibleTargetsExhaustTheAttemptLimitAndFailTheDataset() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		environment.hideTarget = true;
		DatasetGenerator.Result result = run(new DatasetGenerator(config(this.temp, 4), environment, CLOCK, DISK), environment);
		DatasetManifest manifest = result.manifest();
		assertTrue(result.failure().startsWith(DatasetGenerator.SCENE_ATTEMPT_LIMIT), result.failure());
		assertEquals(DatasetManifest.Lifecycle.FAILED, manifest.lifecycle());
		assertEquals(12, manifest.counts().attemptedScenes());
		assertEquals(manifest.counts().rejectedScenes(), manifest.counts().rejectionReasons().getOrDefault(ObservationCheck.VISIBILITY, 0));
		assertTrue(manifest.counts().acceptedScenes() > 0, "target-absent scenes are still accepted");
		DatasetValidator.Report report = DatasetValidator.validate(result.directory());
		assertEquals(java.util.List.of(), report.errors(), "a failed dataset stays internally consistent, with no orphaned images");
	}

	@Test
	void aCaptureFailureRejectsOnlyThatSceneAttempt() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		environment.failCapture = observation -> observation == 2;
		DatasetGenerator.Result result = run(new DatasetGenerator(config(this.temp, 3), environment, CLOCK, DISK), environment);
		assertNull(result.failure());
		assertEquals(Map.of(DatasetGenerator.CAPTURE_FAILED, 1), result.manifest().counts().rejectionReasons());
		SceneRecord retried = DatasetValidator.validate(result.directory()).scenes().stream().filter(scene -> scene.generation().attemptIndex() == 1).findFirst().orElseThrow();
		assertEquals(0, retried.generation().sceneIndex());
	}

	@Test
	void cancellingMarksTheDatasetFailed() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator generator = new DatasetGenerator(config(this.temp, 3), environment, CLOCK, DISK);
		CompletableFuture<DatasetGenerator.Result> result = generator.start();
		generator.cancel();
		environment.pump();
		assertEquals(DatasetGenerator.CANCELLED, result.join().failure());
		DatasetManifest manifest = result.join().manifest();
		assertEquals(1, manifest.counts().failedScenes());
		assertEquals(DatasetManifest.Lifecycle.FAILED, manifest.lifecycle());
		assertTrue(DatasetValidator.validate(result.join().directory()).passed());
	}

	@Test
	void validationDetectsDamagedMissingAndUnexpectedFiles() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator.Result result = run(new DatasetGenerator(config(this.temp, 2), environment, CLOCK, DISK), environment);
		DatasetValidator.Report report = DatasetValidator.validate(result.directory());
		SampleRecord first = report.samples().get(0);
		SampleRecord second = report.samples().get(1);
		Path root = result.directory();

		byte[] mask = Files.readAllBytes(root.resolve(first.mask().path()));
		mask[mask.length / 2] ^= 0x01;
		Files.write(root.resolve(first.mask().path()), mask);
		Files.delete(root.resolve(second.rgb().path()));
		Files.writeString(root.resolve("rgb").resolve(Split.TRAIN.jsonName()).resolve("stray.png"), "not an image");

		var errors = DatasetValidator.validate(root).errors();
		assertTrue(errors.stream().anyMatch(error -> error.contains(first.sampleId()) && error.contains("SHA-256")), errors::toString);
		assertTrue(errors.stream().anyMatch(error -> error.contains(second.sampleId()) && error.contains("missing")), errors::toString);
		assertTrue(errors.stream().anyMatch(error -> error.contains("stray.png")), errors::toString);
		assertFalse(errors.stream().anyMatch(error -> error.contains("integrity")), "the indexes themselves are unchanged");
	}

	/** Images are checked in parallel, but errors still come in sample order. */
	@Test
	void validationReportsImageErrorsInSampleOrder() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator.Result result = run(new DatasetGenerator(config(this.temp, 6), environment, CLOCK, DISK), environment);
		Path root = result.directory();
		List<SampleRecord> samples = DatasetValidator.validate(root).samples();
		List<String> damaged = new ArrayList<>();
		for (int i = samples.size() - 1; i >= 0; i -= 3) {
			Files.delete(root.resolve(samples.get(i).rgb().path()));
			damaged.add(0, samples.get(i).sampleId());
		}

		List<String> errors = DatasetValidator.validate(root).errors();
		assertEquals(damaged.stream().map(id -> "sample " + id + ": rgb").toList(), errors.stream().map(error -> error.substring(0, error.indexOf(": rgb") + 5)).toList(), errors::toString);
	}

	@Test
	void cancellingMidSceneAbandonsOnlyTheRunningAttempt() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator generator = new DatasetGenerator(config(this.temp, 3), environment, CLOCK, DISK);
		// Observations 4-6 are the second scene's first batch. Its middle view sees no target, so
		// the scene needs a second round; cancelling during the batch abandons the attempt after
		// the batch, with the two accepted images already written.
		environment.hideOn = observation -> observation == 5;
		environment.onObserve = observation -> {
			if (observation == 5) {
				generator.cancel();
			}
		};
		DatasetGenerator.Result result = run(generator, environment);
		assertEquals(DatasetGenerator.CANCELLED, result.failure());
		assertEquals(6, environment.observations(), "the running batch finishes; nothing new starts");
		DatasetManifest manifest = result.manifest();
		assertEquals(DatasetManifest.Lifecycle.FAILED, manifest.lifecycle());
		assertEquals(1, manifest.counts().acceptedScenes());
		assertEquals(1, manifest.counts().failedScenes());
		DatasetValidator.Report report = DatasetValidator.validate(result.directory());
		assertEquals(java.util.List.of(), report.errors(), "the abandoned attempt leaves no images behind");
		assertEquals(3, report.samples().size());
		assertTrue(manifest.integrity().validationPassed());
	}

	@Test
	void cancellingDuringTheFinalCaptureStillCompletes() throws IOException {
		ScriptedEnvironment counting = new ScriptedEnvironment();
		run(new DatasetGenerator(config(this.temp.resolve("a"), 2), counting, CLOCK, DISK), counting);
		int last = counting.observations();

		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator generator = new DatasetGenerator(config(this.temp.resolve("b"), 2), environment, CLOCK, DISK);
		environment.onObserve = observation -> {
			if (observation == last) {
				generator.cancel();
			}
		};
		DatasetGenerator.Result result = run(generator, environment);
		assertNull(result.failure(), "no operation remained to cancel");
		assertEquals(DatasetManifest.Lifecycle.COMPLETE, result.manifest().lifecycle());
	}

	@Test
	void storagePreflightRefusesBeforeCreatingTheDataset() {
		ResolvedConfig config = config(this.temp, 2);
		IOException error = assertThrows(IOException.class, () -> new DatasetGenerator(config, new ScriptedEnvironment(), CLOCK, path -> 1_000_100L).start());
		assertTrue(error.getMessage().contains("free_space_reserve_bytes"), error.getMessage());
		assertFalse(Files.exists(this.temp.resolve(config.datasetId())));
	}

	@Test
	void crossingTheReserveStopsTheRunSafely() throws IOException {
		AtomicInteger checks = new AtomicInteger();
		// The preflight and the first two scene attempts see ample space; the third does not.
		StorageBudget.DiskSpace shrinking = path -> checks.incrementAndGet() <= 3 ? 10_000_000L : 1_000_100L;
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator.Result result = run(new DatasetGenerator(config(this.temp, 4), environment, CLOCK, shrinking), environment);
		assertTrue(result.failure().startsWith(DatasetGenerator.STORAGE_RESERVE), result.failure());
		assertEquals(DatasetManifest.Lifecycle.FAILED, result.manifest().lifecycle());
		assertEquals(2, result.manifest().counts().acceptedScenes());
		assertEquals(0, result.manifest().counts().failedScenes(), "no attempt was running");
		assertTrue(DatasetValidator.validate(result.directory()).passed());
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void clutterLevelsMeetTheirVisibilityRules(int level) throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		ResolvedConfig config = config(this.temp, 4, level, 160, 120);
		DatasetGenerator.Result result = run(new DatasetGenerator(config, environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.manifest().counts());
		DatasetValidator.Report report = DatasetValidator.validate(result.directory());
		assertEquals(java.util.List.of(), report.errors());
		for (SampleRecord sample : report.samples()) {
			if (!sample.target().targetPresent()) {
				continue;
			}

			if (level == 2) {
				assertEquals(TargetStatus.FULLY_VISIBLE, sample.target().targetStatus(), sample.sampleId());
			} else {
				assertEquals(TargetStatus.PARTIALLY_VISIBLE, sample.target().targetStatus(), sample.sampleId());
				double ratio = sample.target().visibilityRatio();
				assertTrue(ratio >= 0.2 && ratio <= 0.8, sample.sampleId() + " ratio " + ratio);
			}
		}

		for (SceneRecord scene : report.scenes()) {
			SceneRecord.Parameters parameters = scene.generation().parameters();
			assertEquals(3, parameters.viewpointDraws().length);
			assertTrue(parameters.layout().get("blocks").getAsInt() > 0);
			if (scene.target().present()) {
				assertArrayEquals(scene.target().position(), parameters.aimBlock());
			}
		}
	}

	/** Switching the capture mode keeps every scene layout and camera pose. */
	@Test
	void panoramaCapturesTheSameScenesFromTheSamePoses() throws IOException {
		ResolvedConfig perspective = config(this.temp.resolve("perspective"), 3);
		ResolvedConfig panorama = ConfigResolver.resolve(configJson(this.temp.resolve("panorama"), 3, 1, 48, 32)
			.replace("\"capture\": {\"width\": 48, \"height\": 32}", "\"capture\": {\"mode\": \"PANORAMA_310\", \"width\": 310, \"height\": 160, \"cube_face_size\": 64}"));
		ScriptedEnvironment first = new ScriptedEnvironment();
		DatasetGenerator.Result flat = run(new DatasetGenerator(perspective, first, CLOCK, DISK), first);
		ScriptedEnvironment second = new ScriptedEnvironment();
		DatasetGenerator.Result round = run(new DatasetGenerator(panorama, second, CLOCK, DISK), second);
		assertNull(round.failure(), () -> round.failure() + " " + round.validationErrors());
		assertEquals(DatasetManifest.Lifecycle.COMPLETE, round.manifest().lifecycle());

		DatasetValidator.Report a = DatasetValidator.validate(flat.directory());
		DatasetValidator.Report b = DatasetValidator.validate(round.directory());
		assertEquals(java.util.List.of(), b.errors());
		assertArrayEquals(Files.readAllBytes(flat.directory().resolve(DatasetWriter.SCENES)), Files.readAllBytes(round.directory().resolve(DatasetWriter.SCENES)),
			"scenes, layouts and accepted draws do not depend on the capture mode");
		for (int i = 0; i < a.samples().size(); i++) {
			SampleRecord one = a.samples().get(i);
			SampleRecord other = b.samples().get(i);
			assertEquals(one.sampleId(), other.sampleId());
			assertArrayEquals(one.camera().playerPosition(), other.camera().playerPosition());
			assertEquals(one.camera().yawDegrees(), other.camera().yawDegrees());
			assertEquals(one.camera().pitchDegrees(), other.camera().pitchDegrees());
			assertEquals(310, other.rgb().width());
			assertEquals(io.github.thedeadbolt.honeyfactory.contract.CaptureMode.PANORAMA_310, other.mode());
		}
	}

	@Test
	void naturalScenesShareEachPreparedPatchAndItsSplit() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		DatasetGenerator.Result result = run(new DatasetGenerator(naturalConfig(this.temp, 9, 192), environment, CLOCK, DISK), environment);
		assertNull(result.failure(), () -> result.failure() + " " + result.validationErrors());
		assertEquals(java.util.List.of(0, 1, 2, 3, 4), environment.preparedPatches, "each patch is prepared once, in order");
		DatasetValidator.Report report = DatasetValidator.validate(result.directory());
		assertEquals(java.util.List.of(), report.errors());
		java.util.Map<String, java.util.Set<io.github.thedeadbolt.honeyfactory.contract.Split>> splitsByPatch = new java.util.HashMap<>();
		for (io.github.thedeadbolt.honeyfactory.contract.SceneRecord scene : report.scenes()) {
			assertNotNull(scene.sourcePatch());
			assertEquals(5, scene.difficulty());
			assertEquals(192, scene.arena().margin());
			assertEquals(io.github.thedeadbolt.honeyfactory.scene.NaturalGenerator.TEMPLATE_ID, scene.template().id());
			assertTrue(scene.environment().timeOfDayTicks() >= 1000 && scene.environment().timeOfDayTicks() <= 11000);
			int[] min = scene.sourcePatch().boundsMin();
			int[] max = scene.sourcePatch().boundsMax();
			assertEquals(32 + 2 * 192, max[0] - min[0] + 1);
			splitsByPatch.computeIfAbsent(scene.sourcePatch().patchId(), key -> new java.util.HashSet<>()).add(scene.split());
		}

		assertEquals(5, splitsByPatch.size());
		splitsByPatch.forEach((patch, splits) -> assertEquals(1, splits.size(), patch + " reaches " + splits));
	}

	@Test
	void aPatchWithoutAnAcceptableCandidateFailsTheRun() throws IOException {
		ScriptedEnvironment environment = new ScriptedEnvironment();
		environment.rejectPatch = patch -> patch == 1;
		DatasetGenerator.Result result = run(new DatasetGenerator(naturalConfig(this.temp, 6, 192), environment, CLOCK, DISK), environment);
		assertNotNull(result.failure());
		assertTrue(result.failure().contains("no acceptable source patch for patch 1"), result.failure());
		DatasetManifest.Counts counts = result.manifest().counts();
		assertEquals(2, counts.acceptedScenes());
		assertEquals(counts.attemptedScenes(), counts.acceptedScenes() + counts.rejectedScenes() + counts.failedScenes());
		assertTrue(result.manifest().integrity().validationPassed(), "what was kept still validates");
	}

	@Test
	void paddingThatLeavesTheVoidVisibleIsRefused() {
		ResolvedConfig config = naturalConfig(this.temp, 2, 64);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> new DatasetGenerator(config, new ScriptedEnvironment(), CLOCK, DISK).start());
		assertTrue(error.getMessage().contains("render distance 8"), error.getMessage());
		assertFalse(Files.exists(this.temp.resolve(config.datasetId())));
	}
}
