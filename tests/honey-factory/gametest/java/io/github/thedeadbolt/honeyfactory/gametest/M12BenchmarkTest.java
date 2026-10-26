package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.generation.DatasetRuns;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.generation.GenerationTimings;
import io.github.thedeadbolt.honeyfactory.image.PngImages;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Options;

/**
 * The M12-1 benchmark: fixed-seed reference workloads, timed per phase, whose output is the
 * reference for the quality bar.
 *
 * <ul>
 *   <li>One {@code PANORAMA_310} still dataset per level at the default resolution, 20 scenes
 *       of 8 viewpoints, configured as the phase-1 production datasets are.</li>
 *   <li>A 200-tick control-loop run on a level-1 scene with seed 11, captured in panorama
 *       mode, saving every tick's RGB and mask.</li>
 * </ul>
 *
 * <p>Levels 1 to 4 and the control loop run in a fresh world, where two runs of one build give
 * identical images. Natural level-5 terrain differs between fresh worlds, so the first run
 * saves its level-5 world to {@code build/m12-benchmark/world/} and every later run generates
 * level 5 in a fresh copy of that snapshot, where the terrain already exists. Lighting in a
 * reloaded world is not exactly repeatable, so level-5 RGB is compared with
 * {@link BenchmarkCompare#LEVEL5_RGB_TOLERANCE}; its masks must still be identical.
 *
 * <p>Output goes to {@code build/m12-benchmark/<label>/} ({@code -Dhoneyfactory.benchmarkLabel},
 * default {@code baseline}) with a {@code summary.json} of every phase. When the label is not
 * the reference ({@code -Dhoneyfactory.benchmarkBefore}, default {@code baseline}) and the
 * reference exists, the output is compared with it by {@link BenchmarkCompare} and any quality
 * failure fails the test. Runs only when named ({@code -Dhoneyfactory.gametests=M12Benchmark}).
 */
public class M12BenchmarkTest implements FabricClientGameTest {
	static final long WORLD_SEED = 12_026_001L;
	static final int SCENES = 20;
	static final int CONTROL_TICKS = 200;
	static final long CONTROL_SEED = 11L;
	private static final int TIMEOUT_TICKS = 20 * 60 * 60 * 2;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		String label = property("honeyfactory.benchmarkLabel", "baseline");
		String reference = property("honeyfactory.benchmarkBefore", "baseline");
		Path root = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m12-benchmark");
		Path output = root.resolve(label);
		GameTestSupport.deleteRecursively(output);
		GameTestSupport.pinCaptureSettings(context);
		JsonObject summary = new JsonObject();
		summary.addProperty("label", label);
		summary.addProperty("production_batch_running", ProcessHandle.allProcesses().anyMatch(process -> process.info().commandLine().orElse("").contains("MineSophila-aim")));
		summary.add("render_options", renderOptions(context));
		JsonObject still = new JsonObject();
		String fresh = "honey-factory-m12-benchmark-fresh";
		try (TestSingleplayerContext singleplayer = GameTestSupport.datasetWorld(context, fresh, WORLD_SEED)) {
			GameTestSupport.claim(singleplayer, fresh);
			for (int level = 1; level <= 4; level++) {
				still.add("l" + level, still(context, output.resolve("still"), level));
			}

			summary.add("control", control(context, output.resolve("control")));
		}

		String natural = "honey-factory-m12-benchmark";
		Path snapshot = root.resolve("world");
		boolean fromSnapshot = Files.isDirectory(snapshot);
		summary.addProperty("level5_world_from_snapshot", fromSnapshot);
		TestWorldSave save;
		try (TestSingleplayerContext created = GameTestSupport.datasetWorld(context, natural, WORLD_SEED)) {
			save = created.getWorldSave();
		}

		if (fromSnapshot) {
			GameTestSupport.deleteRecursively(save.getSaveDirectory());
			copyWorld(snapshot, save.getSaveDirectory());
		}

		try (TestSingleplayerContext singleplayer = save.open()) {
			GameTestSupport.claim(singleplayer, natural);
			still.add("l5", still(context, output.resolve("still"), 5));
		}

		summary.add("still", still);
		if (!fromSnapshot) {
			copyWorld(save.getSaveDirectory(), snapshot);
			HoneyFactory.LOGGER.info("M12 benchmark {}: saved the level-5 world snapshot to {}; rerun to record a reference in it", label, snapshot);
		}

		write(output.resolve("summary.json"), summary);
		Path before = root.resolve(reference);
		if (!fromSnapshot || label.equals(reference) || !Files.isRegularFile(before.resolve("summary.json"))) {
			HoneyFactory.LOGGER.info("M12 benchmark {}: recorded as a reference", label);
			return;
		}

		BenchmarkCompare.Result result = BenchmarkCompare.compare(before, output);
		HoneyFactory.LOGGER.info("M12 benchmark {} against {}: {} frames, {} identical RGB, {} rainy level-5 RGB unchecked, largest difference {} in {} of pixels, {} failures",
			label, reference, result.frames(), result.identicalRgb(), result.uncheckedRgb(), result.maxChannel(), result.maxDifferingFraction(), result.failures().size());
		if (!result.passed()) {
			throw new AssertionError("M12 benchmark " + label + " fails the quality bar against " + reference + ":\n" + String.join("\n", result.failures().subList(0, Math.min(20, result.failures().size()))));
		}
	}

	private static String property(String name, String fallback) {
		String value = System.getProperty(name, "");
		return value.isBlank() ? fallback : value;
	}

	private static JsonObject renderOptions(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> {
			JsonObject options = new JsonObject();
			options.addProperty("framerate_limit", minecraft.options.framerateLimit().get());
			options.addProperty("vsync", minecraft.options.enableVsync().get());
			options.addProperty("inactivity_fps_limit", minecraft.options.inactivityFpsLimit().get().toString());
			options.addProperty("render_distance", minecraft.options.renderDistance().get());
			return options;
		});
	}

	/** Generates level {@code level}'s reference dataset and returns its phases. */
	private static JsonObject still(ClientGameTestContext context, Path directory, int level) {
		String name = "m12-benchmark-l" + level;
		String scene = level == 5 ? "\"scene\": {\"base\": {\"type\": \"natural_patch\", \"scenes_per_patch\": 4}}," : "";
		String config = """
			{
			  "dataset": {"name": "%s", "seed": %d, "difficulty": %d},
			  %s
			  "viewpoints": {"count": 8, "aim": "image_uniform"},
			  "visibility": {"min_visible_pixels": 200},
			  "capture": {"mode": "PANORAMA_310"},
			  "quotas": {"accepted_scenes": %d},
			  "target": {"present_fraction": 0.8},
			  "output": {"root": "%s"}
			}
			""".formatted(name, 12_000 + level, level, scene, SCENES, directory.toString().replace("\\", "\\\\"));
		long started = System.nanoTime();
		Set<String> pacing = new TreeSet<>();
		var result = GameTestSupport.generate(context, name, config, progress -> {
			pacing.add(context.computeOnClient(minecraft -> minecraft.options.framerateLimit().get() + " " + minecraft.options.enableVsync().get() + " "
				+ minecraft.options.inactivityFpsLimit().get().getSerializedName()));
			return false;
		}, TIMEOUT_TICKS);
		double seconds = (System.nanoTime() - started) / 1e9;
		if (!"complete".equals(result.manifest().lifecycle().jsonName())) {
			throw new AssertionError(name + ": " + result.manifest().lifecycle().jsonName() + " (" + result.failure() + ")");
		}

		String uncapped = Options.UNLIMITED_FRAMERATE_CUTOFF + " false minimized";
		boolean uncap = Boolean.parseBoolean(System.getProperty("honeyfactory.uncapFrames", "true"));
		if (uncap && !pacing.equals(Set.of(uncapped))) {
			throw new AssertionError(name + ": generation ran with frame pacing " + pacing + ", expected only " + uncapped);
		}

		JsonObject json = new JsonObject();
		int samples = result.manifest().counts().acceptedSamples();
		json.addProperty("seconds", seconds);
		json.addProperty("accepted_samples", samples);
		json.addProperty("attempted_scenes", result.manifest().counts().attemptedScenes());
		json.addProperty("samples_per_hour", samples / seconds * 3600.0);
		JsonObject phases = new JsonObject();
		GenerationTimings timings = context.computeOnClient(minecraft -> DatasetRuns.lastTimings());
		timings.totals().forEach((phase, total) -> phases.add(phase.name().toLowerCase(Locale.ROOT), total(total)));
		List<long[]> captures = context.computeOnClient(minecraft -> DatasetRuns.lastCaptureSamples());
		String[] parts = {"pose_wall", "render", "readback", "reproject"};
		for (int part = 0; part < parts.length; part++) {
			int index = part;
			phases.add(parts[part], total(GenerationTimings.Total.of(captures.stream().mapToLong(sample -> sample[index]).toArray())));
		}

		phases.add("readiness_wait", total(GenerationTimings.Total.of(captures.stream().mapToLong(sample -> sample[0] - sample[1] - sample[2] - sample[3]).toArray())));
		json.add("phases", phases);
		HoneyFactory.LOGGER.info("M12 benchmark {}: {} samples in {} s", name, samples, seconds);
		return json;
	}

	/**
	 * Drives {@value #CONTROL_TICKS} control steps on a level-1 scene, saving each tick's
	 * frame and mask as PNG with their digests in {@code ticks.jsonl}. The body walks forward
	 * while turning, then back while turning the other way, so it stays near its start.
	 */
	private static JsonObject control(ClientGameTestContext context, Path directory) {
		ScenePreview preview = ScenePreview.of(SceneRecipe.of("level1").build(), CONTROL_SEED, 0);
		PanoramicProjection projection = new PanoramicProjection(2480, 1280, 1024);
		List<String> lines = new ArrayList<>();
		long[] stepNanos = new long[CONTROL_TICKS];
		long[] renderNanos = new long[CONTROL_TICKS];
		long[] readbackNanos = new long[CONTROL_TICKS];
		long[] reprojectNanos = new long[CONTROL_TICKS];
		long resetStart = System.nanoTime();
		int framesPerTick = Integer.getInteger("honeyfactory.benchmarkFramesPerTick", 1);
		EnvironmentRuntime.ControlResult result = GameTestSupport.await(context, runtime -> runtime.controlReset(preview.arena(), preview.scene().plan(),
			preview.viewpoint().pose(preview.arena()), projection, CONTROL_SEED, framesPerTick));
		double resetSeconds = (System.nanoTime() - resetStart) / 1e9;
		lines.add(save(directory, 0, result));
		for (int tick = 1; tick <= CONTROL_TICKS; tick++) {
			MotorCommand command = command(tick);
			long start = System.nanoTime();
			result = GameTestSupport.await(context, runtime -> runtime.controlStep(command, null, ControlStep.Executed.STUDENT));
			stepNanos[tick - 1] = System.nanoTime() - start;
			renderNanos[tick - 1] = result.frames().stream().mapToLong(CaptureService.Capture::renderNanos).sum();
			readbackNanos[tick - 1] = result.frames().stream().mapToLong(CaptureService.Capture::readbackNanos).sum();
			reprojectNanos[tick - 1] = result.frames().stream().mapToLong(CaptureService.Capture::assembleNanos).sum();
			lines.add(save(directory, tick, result));
		}

		context.runOnClient(minecraft -> EnvironmentRuntime.get().controlEnd());
		try {
			Files.write(directory.resolve("ticks.jsonl"), lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		JsonObject json = new JsonObject();
		json.addProperty("ticks", CONTROL_TICKS);
		json.addProperty("frames_per_tick", framesPerTick);
		json.addProperty("reset_seconds", resetSeconds);
		JsonObject phases = new JsonObject();
		phases.add("control_step", total(GenerationTimings.Total.of(stepNanos)));
		phases.add("render", total(GenerationTimings.Total.of(renderNanos)));
		phases.add("readback", total(GenerationTimings.Total.of(readbackNanos)));
		phases.add("reproject", total(GenerationTimings.Total.of(reprojectNanos)));
		json.add("phases", phases);
		return json;
	}

	/** Forward while turning right for 20 ticks, then back while turning left for 20. */
	static MotorCommand command(int tick) {
		boolean out = (tick - 1) % 40 < 20;
		return new MotorCommand(out ? 1.0 : -1.0, 0.0, out ? 6.0 : -6.0, 0.0, false, 0);
	}

	private static String save(Path directory, int tick, EnvironmentRuntime.ControlResult result) {
		CapturedImages images = result.capture().images();
		String frame = "t%04d".formatted(tick);
		byte[] rgb = PngImages.encodeRgb(images.width(), images.height(), images.rgb());
		byte[] mask = PngImages.encodeGray(images.width(), images.height(), images.visibleMask());
		try {
			Files.createDirectories(directory);
			Files.write(directory.resolve(frame + "-rgb.png"), rgb);
			Files.write(directory.resolve(frame + "-mask.png"), mask);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		JsonObject line = new JsonObject();
		line.addProperty("frame", frame);
		line.addProperty("rgb_sha256", Sha256.hex(rgb));
		line.addProperty("mask_sha256", Sha256.hex(mask));
		line.add("truth", result.step().truthJson());
		return line.toString();
	}

	private static JsonObject total(GenerationTimings.Total total) {
		JsonObject json = new JsonObject();
		json.addProperty("count", total.count());
		json.addProperty("mean_seconds", total.meanSeconds());
		json.addProperty("p50_seconds", total.p50Seconds());
		json.addProperty("p95_seconds", total.p95Seconds());
		return json;
	}

	/** Copies a world save, leaving out its session lock. */
	private static void copyWorld(Path from, Path to) {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else if (!file.getFileName().toString().equals("session.lock")) {
					Files.copy(file, target);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void write(Path file, JsonObject json) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
