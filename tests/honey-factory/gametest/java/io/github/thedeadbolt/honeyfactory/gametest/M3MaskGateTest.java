package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.contract.VisibilityRule;
import io.github.thedeadbolt.honeyfactory.dataset.PngImages;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.OperationState;
import io.github.thedeadbolt.honeyfactory.generation.ObservationCheck;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans.Fixture;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Roadmap M3 mask prototype gate, every perspective-applicable row: output validity, open,
 * full and known-half occlusion, irregular occluders against approved golden masks, near,
 * far, image-edge and out-of-view targets, ten-capture frozen determinism, cross-run
 * reproducibility (each run builds a fresh world and must match the goldens) and failure
 * handling.
 *
 * <p>An independent CPU ray check confirms that every mask agrees with the analytic target
 * and occluder geometry away from edges, including at a non-square resolution, which
 * verifies the exact 90-degree horizontal projection.
 *
 * <p>Every run writes RGB, masks and overlays to {@code packages/honey-factory/build/m3-review}.
 * A missing golden mask fails the gate and is written there for human approval; run with
 * {@code -Dhoneyfactory.regenerateGolden=true} to rewrite all goldens after review.
 */
public class M3MaskGateTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m3-masks";
	private static final int SIZE = 1280;
	private static final int REPEATS = 10;
	private static final ResolvedConfig.Visibility LEVEL_1 = new ResolvedConfig.Visibility(VisibilityRule.FULLY_VISIBLE, null, null);

	@Override
	public void runTest(ClientGameTestContext context) {
		List<String> failures = new ArrayList<>();
		Path root = GameTestSupport.repoRoot();
		Path golden = root.resolve("tests/honey-factory/gametest/resources/golden/m3");
		Path review = root.resolve("packages/honey-factory/build/m3-review");
		boolean regenerate = Boolean.getBoolean("honeyfactory.regenerateGolden");
		JsonObject summary = new JsonObject();
		GameTestSupport.pinCaptureSettings(context);
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			PerspectiveProjection projection = new PerspectiveProjection(SIZE, SIZE);
			for (Fixture fixture : Fixture.values()) {
				if (fixture == Fixture.EMPTY || fixture == Fixture.PLATFORM) {
					continue;
				}

				reset(context, fixture);
				List<EnvironmentRuntime.Observation> captures = captureFrozen(context, world, projection, REPEATS);
				EnvironmentRuntime.Observation first = captures.getFirst();
				CapturedImages images = first.capture().images();
				write(review.resolve(fixture.commandName()), images);
				JsonObject result = new JsonObject();
				checkDeterminism(fixture, captures, failures, result);
				checkFixture(fixture, projection, first, failures, result);
				if (fixture.golden()) {
					checkGolden(fixture, images, golden, regenerate, failures, result);
				}

				timing(captures, result);
				summary.add(fixture.commandName(), result);
			}

			checkNonSquare(context, failures, summary);
			checkFailureHandling(context, failures, summary);
		}

		try {
			Files.createDirectories(review);
			Files.writeString(review.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M3 mask gate failures:\n" + String.join("\n", failures));
		}
	}

	private static void reset(ClientGameTestContext context, Fixture fixture) {
		GameTestSupport.await(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.plan(fixture), EnvironmentRuntime.pose(FixturePlans.viewpoint(fixture))));
	}

	/** Repeated captures with the server's ticking frozen, so the world does not advance between them. */
	private static List<EnvironmentRuntime.Observation> captureFrozen(ClientGameTestContext context, TestSingleplayerContext world, CaptureProjection projection, int repeats) {
		world.getServer().runOnServer(server -> server.tickRateManager().setFrozen(true));
		// The freeze reaches the client as a packet; wait until it applies there too.
		context.waitFor(minecraft -> minecraft.level != null && minecraft.level.tickRateManager().isFrozen());
		context.waitTicks(1);
		try {
			List<EnvironmentRuntime.Observation> captures = new ArrayList<>();
			for (int i = 0; i < repeats; i++) {
				captures.add(GameTestSupport.await(context, runtime -> runtime.observe(projection)));
			}

			return captures;
		} finally {
			world.getServer().runOnServer(server -> server.tickRateManager().setFrozen(false));
		}
	}

	private static void checkDeterminism(Fixture fixture, List<EnvironmentRuntime.Observation> captures, List<String> failures, JsonObject result) {
		CapturedImages first = captures.getFirst().capture().images();
		boolean masks = true;
		boolean rgb = true;
		boolean ticks = true;
		for (EnvironmentRuntime.Observation capture : captures) {
			CapturedImages images = capture.capture().images();
			masks &= Arrays.equals(images.visibleMask(), first.visibleMask()) && Arrays.equals(images.unoccludedMask(), first.unoccludedMask());
			rgb &= Arrays.equals(images.rgb(), first.rgb());
			ticks &= capture.capture().worldTick() == captures.getFirst().capture().worldTick() && capture.pose().equals(captures.getFirst().pose());
		}

		result.addProperty("repeats", captures.size());
		result.addProperty("masks_identical", masks);
		result.addProperty("rgb_identical", rgb);
		result.addProperty("world_frozen", ticks);
		result.addProperty("rgb_sha256", Sha256.hex(first.rgb()));
		result.addProperty("visible_mask_sha256", Sha256.hex(first.visibleMask()));
		result.addProperty("unoccluded_mask_sha256", Sha256.hex(first.unoccludedMask()));
		if (!masks || !rgb || !ticks) {
			failures.add(fixture + ": " + captures.size() + " frozen captures differ (masks " + masks + ", rgb " + rgb + ", frozen " + ticks + "): ticks "
				+ captures.stream().map(capture -> capture.capture().worldTick()).toList() + ", poses " + captures.stream().map(EnvironmentRuntime.Observation::pose).distinct().toList());
		}
	}

	private static void checkFixture(Fixture fixture, PerspectiveProjection projection, EnvironmentRuntime.Observation observation, List<String> failures, JsonObject result) {
		CapturedImages images = observation.capture().images();
		ScenePlan plan = FixturePlans.plan(fixture);
		int[] target = plan.targetPosition(FixturePlans.ARENA);
		CameraFrame camera = CameraFrame.of(observation.pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
		if (images.width() != SIZE || images.height() != SIZE || images.maskDefect() != null) {
			failures.add(fixture + ": invalid output " + images.width() + "x" + images.height() + " " + images.maskDefect());
			return;
		}

		MaskStatistics visible = MaskStatistics.of(images.visibleMask(), SIZE, SIZE);
		int unoccluded = images.unoccludedPixels();
		boolean inProjection = projection.intersects(camera, new double[] {target[0], target[1], target[2]}, new double[] {target[0] + 1, target[1] + 1, target[2] + 1});
		TargetStatus status = TargetStatus.derive(true, inProjection, visible.positivePixels(), unoccluded);
		result.addProperty("visible_pixels", visible.positivePixels());
		result.addProperty("unoccluded_pixels", unoccluded);
		result.addProperty("target_status", status.jsonName());
		result.add("visibility_ratio", TargetStatus.visibilityRatio(visible.positivePixels(), unoccluded) == null ? null
			: new com.google.gson.JsonPrimitive(TargetStatus.visibilityRatio(visible.positivePixels(), unoccluded)));
		result.add("visible_bbox_xywh", visible.bboxXywh() == null ? null : toJson(visible.bboxXywh()));

		TargetStatus expected = switch (fixture) {
			case OPEN, NEAR, FAR, EDGE -> TargetStatus.FULLY_VISIBLE;
			case FULL -> TargetStatus.FULLY_OCCLUDED;
			case OUT_OF_VIEW -> TargetStatus.OUT_OF_VIEW;
			default -> TargetStatus.PARTIALLY_VISIBLE;
		};
		if (status != expected) {
			failures.add(fixture + ": status " + status.jsonName() + ", expected " + expected.jsonName());
		}

		if (fixture == Fixture.OPEN && !Arrays.equals(images.visibleMask(), images.unoccludedMask())) {
			failures.add("OPEN: visible and unoccluded masks are not pixel-identical");
		}

		if (fixture == Fixture.EDGE && (visible.bboxXywh() == null || visible.bboxXywh()[0] != 0 && visible.bboxXywh()[0] + visible.bboxXywh()[2] != SIZE)) {
			failures.add("EDGE: the target does not reach the image edge: " + Arrays.toString(visible.bboxXywh()));
		}

		RayCheck.Result rays = new RayCheck(plan, FixturePlans.ARENA, fixture != Fixture.LEAVES).check(projection, camera, target, images);
		result.addProperty("ray_unoccluded_mismatches", rays.unoccludedMismatches());
		result.addProperty("ray_visible_mismatches", rays.visibleMismatches());
		result.addProperty("definitely_occluded_positive_pixels", rays.definitelyOccluded());
		if (rays.unoccludedMismatches() != 0 || rays.visibleMismatches() != 0 || rays.definitelyOccluded() != 0) {
			failures.add(fixture + ": masks disagree with the ray check away from edges: " + rays);
		}

		ObservationCheck.Outcome outcome = ObservationCheck.check(LEVEL_1, projection, camera, target, images);
		boolean level1 = status == TargetStatus.FULLY_VISIBLE;
		if ((outcome.rejection() == null) != level1) {
			failures.add(fixture + ": level-1 acceptance " + (outcome.rejection() == null) + " for status " + status.jsonName());
		}
	}

	private static void checkGolden(Fixture fixture, CapturedImages images, Path directory, boolean regenerate, List<String> failures, JsonObject result) {
		Path file = directory.resolve(fixture.commandName() + "-visible.png");
		try {
			if (regenerate || !Files.exists(file)) {
				Files.createDirectories(directory);
				Files.write(file, PngImages.encodeGray(SIZE, SIZE, images.visibleMask()));
				if (!regenerate) {
					failures.add(fixture + ": no golden mask; wrote " + file + " for review");
				}

				return;
			}

			PngImages.Pixels pixels = PngImages.decode(Files.readAllBytes(file));
			int goldenCount = MaskStatistics.of(pixels.data(), pixels.width(), pixels.height()).positivePixels();
			int count = MaskStatistics.of(images.visibleMask(), SIZE, SIZE).positivePixels();
			boolean exact = pixels.width() == SIZE && pixels.height() == SIZE && Arrays.equals(pixels.data(), images.visibleMask());
			result.addProperty("golden_exact", exact);
			result.addProperty("golden_visible_pixels", goldenCount);
			if (!exact || Math.abs(count - goldenCount) > 1) {
				failures.add(fixture + ": visible mask differs from the approved golden (" + count + " vs " + goldenCount + " pixels)");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A non-square output keeps a 90-degree horizontal field of view: masks match the rays. */
	private static void checkNonSquare(ClientGameTestContext context, List<String> failures, JsonObject summary) {
		PerspectiveProjection wide = new PerspectiveProjection(640, 360);
		reset(context, Fixture.PARTIAL);
		EnvironmentRuntime.Observation observation = GameTestSupport.await(context, runtime -> runtime.observe(wide));
		CapturedImages images = observation.capture().images();
		CameraFrame camera = CameraFrame.of(observation.pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
		ScenePlan plan = FixturePlans.plan(Fixture.PARTIAL);
		RayCheck.Result rays = new RayCheck(plan, FixturePlans.ARENA, true).check(wide, camera, plan.targetPosition(FixturePlans.ARENA), images);
		JsonObject result = new JsonObject();
		result.addProperty("width", images.width());
		result.addProperty("height", images.height());
		result.addProperty("unoccluded_pixels", images.unoccludedPixels());
		result.addProperty("ray_unoccluded_mismatches", rays.unoccludedMismatches());
		result.addProperty("ray_visible_mismatches", rays.visibleMismatches());
		summary.add("partial_640x360", result);
		if (images.width() != 640 || images.height() != 360 || images.unoccludedPixels() == 0 || rays.unoccludedMismatches() != 0 || rays.visibleMismatches() != 0) {
			failures.add("non-square 640x360 capture disagrees with the 90-degree projection: " + rays + ", " + images.unoccludedPixels() + " px");
		}
	}

	private static void checkFailureHandling(ClientGameTestContext context, List<String> failures, JsonObject summary) {
		JsonObject result = new JsonObject();
		try {
			new PerspectiveProjection(8, 8);
			failures.add("8x8 output was accepted");
		} catch (IllegalArgumentException expected) {
			result.addProperty("invalid_dimensions", expected.getMessage());
		}

		// A target in view that rasterises to no pixel is subpixel, and level 1 rejects it.
		PerspectiveProjection tiny = new PerspectiveProjection(16, 16);
		reset(context, Fixture.FAR);
		EnvironmentRuntime.Observation far = GameTestSupport.await(context, runtime -> runtime.observe(tiny));
		CameraFrame camera = CameraFrame.of(far.pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
		int[] target = FixturePlans.plan(Fixture.FAR).targetPosition(FixturePlans.ARENA);
		ObservationCheck.Outcome outcome = ObservationCheck.check(LEVEL_1, tiny, camera, target, far.capture().images());
		int predicted = RayCheck.targetPixels(tiny, camera, target);
		result.addProperty("subpixel_unoccluded_pixels", far.capture().images().unoccludedPixels());
		result.addProperty("subpixel_ray_pixels", predicted);
		result.addProperty("subpixel_rejection", outcome.rejection());
		if (predicted != 0 || far.capture().images().unoccludedPixels() != 0 || !ObservationCheck.VISIBILITY.equals(outcome.rejection())) {
			failures.add("subpixel: expected no target pixels and a visibility rejection, got " + far.capture().images().unoccludedPixels() + " px (rays " + predicted + "), " + outcome.rejection());
		}

		context.runOnClient(minecraft -> CaptureService.failNextCapture("readback"));
		EnvironmentException.Reason injected = GameTestSupport.rejection(context, runtime -> runtime.observe(tiny));
		OperationState state = context.computeOnClient(minecraft -> EnvironmentRuntime.get().status().state());
		result.addProperty("readback_failure", String.valueOf(injected));
		if (injected != EnvironmentException.Reason.CAPTURE_FAILED || state != OperationState.FAILED) {
			failures.add("an injected readback failure gave " + injected + " and left " + state);
		}

		reset(context, Fixture.OPEN);
		context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(false));
		context.waitTicks(2);
		try {
			String message = context.computeOnClient(minecraft -> {
				try {
					CaptureService.capture(minecraft, tiny, null);
					return null;
				} catch (EnvironmentException e) {
					return e.getMessage();
				}
			});
			result.addProperty("classic_transparency", message);
			if (!CaptureService.IMPROVED_TRANSPARENCY_REQUIRED.equals(message)) {
				failures.add("capture with classic transparency was not refused: " + message);
			}
		} finally {
			context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(true));
			context.waitTicks(2);
		}

		summary.add("failure_handling", result);
	}

	private static void timing(List<EnvironmentRuntime.Observation> captures, JsonObject result) {
		long[] render = captures.stream().mapToLong(capture -> capture.capture().renderNanos()).sorted().toArray();
		long[] readback = captures.stream().mapToLong(capture -> capture.capture().readbackNanos()).sorted().toArray();
		result.addProperty("median_render_ms", render[render.length / 2] / 1.0e6);
		result.addProperty("median_readback_ms", readback[readback.length / 2] / 1.0e6);
		Long vram = vramUsedBytes();
		if (vram != null) {
			result.addProperty("vram_used_bytes_after", vram);
		}
	}

	/** Device VRAM in use, from amdgpu sysfs where available; coarse and process-wide. */
	private static Long vramUsedBytes() {
		try (var cards = Files.list(Path.of("/sys/class/drm"))) {
			for (Path card : cards.toList()) {
				Path used = card.resolve("device/mem_info_vram_used");
				if (Files.isReadable(used)) {
					return Long.parseLong(Files.readString(used).trim());
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}

		return null;
	}

	private static void write(Path directory, CapturedImages images) {
		try {
			CaptureDebugFiles.write(directory, images);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static JsonArray toJson(int[] values) {
		JsonArray array = new JsonArray();
		for (int value : values) {
			array.add(value);
		}

		return array;
	}
}
