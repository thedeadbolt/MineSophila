package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.CubeFace;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.contract.VisibilityRule;
import io.github.thedeadbolt.honeyfactory.generation.ObservationCheck;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.image.PngImages;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.Template;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Roadmap M5 exit gate for {@code PANORAMA_310}.
 *
 * <ul>
 *   <li>The mask prototype criteria in panorama mode: the M3 half and irregular occlusion
 *       fixtures against approved golden panorama masks, an open target whose visible and
 *       unoccluded masks are identical, ten identical frozen repeats and a subpixel target
 *       rejected with a reason.</li>
 *   <li>Acceptance and seam criteria at the default (2480 × 1280, 1024² faces) and one
 *       non-default resolution (1240 × 640, 512² faces): landmarks at their directions,
 *       targets across the front/right and left/back seams, the zenith, the bottom edge and
 *       the 155-degree image edge, the blind sector, the ground below −70 degrees, and
 *       partial and full occlusion behind the camera. Every mask must agree with the CPU
 *       ray check, which also proves the faces behind the camera were rendered.</li>
 *   <li>Frozen repeats are identical, and the six faces see one world state.</li>
 *   <li>Switching capture mode keeps the layout and pose; a failure in any face fails the
 *       whole capture and restores the player's rotation.</li>
 *   <li>Small level-1 and level-3 panorama datasets generate and validate.</li>
 *   <li>Benchmarks: capture time per face, reprojection time against the Java-or-Rust
 *       criteria in the roadmap, heap growth, VRAM and raw-face retention cost.</li>
 * </ul>
 *
 * <p>Writes panoramas, overlays and faces to {@code build/m5-review} and datasets to
 * {@code build/m5-datasets}.
 */
public class M5PanoramaGateTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m5-panorama";
	private static final float YAW = 30.0F;
	private static final float PITCH = -12.0F;
	private static final PanoramicProjection DEFAULT = new PanoramicProjection(2480, 1280, 1024);
	private static final PanoramicProjection SMALL = new PanoramicProjection(1240, 640, 512);
	private static final int REPEATS = 10;
	private static final int BENCHMARK_RUNS = 100;
	private static final double REPROJECTION_BUDGET_MS = 50.0;
	private static final double REPROJECTION_SHARE = 0.25;

	/**
	 * A four-block pillar under the camera. From its top the camera sees down to −70 degrees
	 * past its edge along the axes, where every captured ray clears it.
	 */
	private static final int[][] PILLAR = {{16, 1, 16}, {16, 2, 16}, {16, 3, 16}, {16, 4, 16}};

	/**
	 * Fixture scenes around one camera at the arena centre, yaw 30 and pitch −12. Targets
	 * never touch a full block, whose shared face the renderer would rightly leave out of
	 * both masks while the ray check counts it.
	 */
	enum Fixture {
		FRONT(TargetStatus.FULLY_VISIBLE, new int[] {13, 1, 21}),
		RIGHT(TargetStatus.FULLY_VISIBLE, new int[] {12, 3, 14}),
		SEAM_FRONT_RIGHT(TargetStatus.FULLY_VISIBLE, new int[] {10, 1, 18}),
		SEAM_LEFT_BACK(TargetStatus.FULLY_VISIBLE, new int[] {22, 1, 14}),
		PARTIAL_AT_SEAM(TargetStatus.PARTIALLY_VISIBLE, new int[] {10, 1, 18}, new int[] {11, 1, 17}, new int[] {11, 2, 17}),
		PARTIAL_BEHIND(TargetStatus.PARTIALLY_VISIBLE, new int[] {22, 1, 14}, new int[] {20, 1, 14}, new int[] {20, 2, 14}),
		FULL_BEHIND(TargetStatus.FULLY_OCCLUDED, new int[] {22, 1, 14}, cube(19, 2, 15)),
		IMAGE_EDGE(TargetStatus.FULLY_VISIBLE, new int[] {16, 1, 11}),
		BLIND_SECTOR(TargetStatus.OUT_OF_VIEW, new int[] {19, 1, 11}),
		ZENITH(TargetStatus.FULLY_VISIBLE, new int[] {16, 6, 16}),
		BOTTOM_EDGE(TargetStatus.FULLY_VISIBLE, new int[] {16, 2, 18}, PILLAR),
		BELOW_RANGE(TargetStatus.OUT_OF_VIEW, new int[] {16, 0, 16}, PILLAR);

		final TargetStatus expected;
		final int[] target;
		final int[][] occluders;

		Fixture(TargetStatus expected, int[] target, int[]... occluders) {
			this.expected = expected;
			this.target = target;
			this.occluders = occluders;
		}

		ScenePlan plan() {
			Template template = Template.platform("platform", 1, FixturePlans.SIZE, FixturePlans.SIZE, FixturePlans.HEIGHT, FixturePlans.FLOOR);
			List<Placement> delta = new ArrayList<>();
			for (int[] occluder : this.occluders) {
				delta.add(new Placement(occluder[0], occluder[1], occluder[2], "minecraft:stone"));
			}

			Placement target = new Placement(this.target[0], this.target[1], this.target[2], "minecraft:honey_block");
			delta.add(target);
			return new ScenePlan(template, delta, target, 6000, Weather.CLEAR);
		}

		/** Standing at the arena centre, on the pillar when the fixture has one. */
		CameraPose pose() {
			return new ViewpointPlan(16.5, this.occluders == PILLAR ? 5.0 : 1.0, 16.5, YAW, PITCH).pose();
		}

		/** Fixtures whose target crosses a face seam or the bottom edge and must stay one connected region. */
		boolean seam() {
			return this == SEAM_FRONT_RIGHT || this == SEAM_LEFT_BACK || this == BOTTOM_EDGE;
		}

		/** Fixtures whose mask centroid must point at the target's centre. */
		boolean landmark() {
			return this == FRONT || this == RIGHT || this == SEAM_FRONT_RIGHT || this == SEAM_LEFT_BACK;
		}

		String fileName() {
			return name().toLowerCase(Locale.ROOT).replace('_', '-');
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		List<String> failures = new ArrayList<>();
		Path root = GameTestSupport.repoRoot();
		Path review = root.resolve("packages/honey-factory/build/m5-review");
		Path datasets = root.resolve("packages/honey-factory/build/m5-datasets");
		GameTestSupport.deleteRecursively(review);
		GameTestSupport.deleteRecursively(datasets);
		JsonObject summary = new JsonObject();
		GameTestSupport.pinCaptureSettings(context);
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			for (PanoramicProjection projection : List.of(DEFAULT, SMALL)) {
				JsonObject resolution = new JsonObject();
				for (Fixture fixture : Fixture.values()) {
					reset(context, fixture);
					int repeats = projection == DEFAULT ? REPEATS : 1;
					List<EnvironmentRuntime.Observation> captures = captureFrozen(context, world, projection, repeats);
					JsonObject result = check(fixture, projection, captures, failures);
					String label = projection.width() + "x" + projection.height();
					write(review.resolve(label).resolve(fixture.fileName()), captures.getFirst().capture().images());
					if (projection == DEFAULT && fixture == Fixture.FRONT) {
						writeFaces(review.resolve(label).resolve(fixture.fileName()).resolve("faces"), captures.getFirst().capture().views());
					}

					if (projection == DEFAULT) {
						Long vram = vramUsedBytes();
						if (vram != null) {
							result.addProperty("vram_used_bytes_after", vram);
						}
					}

					resolution.add(fixture.fileName(), result);
				}

				summary.add(projection.width() + "x" + projection.height() + "_face" + projection.faceSize(), resolution);
			}

			summary.add("golden_masks", checkGoldens(context, world, root.resolve("tests/honey-factory/gametest/resources/golden/m5"), review, failures));
			summary.add("subpixel", checkSubpixel(context, failures));
			summary.add("mode_switch", checkModeSwitch(context, world, failures));
			summary.add("atomic_failure", checkAtomicFailure(context, failures));
			summary.add("benchmark", benchmark(context, world, failures));
		}

		summary.add("datasets", datasets(context, datasets, failures, summary.getAsJsonObject("benchmark")));
		try {
			Files.createDirectories(review);
			Files.writeString(review.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M5 panorama gate failures:\n" + String.join("\n", failures));
		}
	}

	private static void reset(ClientGameTestContext context, Fixture fixture) {
		GameTestSupport.await(context, runtime -> runtime.reset(FixturePlans.ARENA, fixture.plan(), fixture.pose()));
	}

	private static List<EnvironmentRuntime.Observation> captureFrozen(ClientGameTestContext context, TestSingleplayerContext world, CaptureProjection projection, int repeats) {
		world.getServer().runOnServer(server -> server.tickRateManager().setFrozen(true));
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

	private static JsonObject check(Fixture fixture, PanoramicProjection projection, List<EnvironmentRuntime.Observation> captures, List<String> failures) {
		String label = fixture + " at " + projection.width() + "x" + projection.height() + ": ";
		EnvironmentRuntime.Observation first = captures.getFirst();
		CapturedImages images = first.capture().images();
		JsonObject result = new JsonObject();
		if (images.width() != projection.width() || images.height() != projection.height() || images.maskDefect() != null) {
			failures.add(label + "invalid output " + images.width() + "x" + images.height() + " " + images.maskDefect());
			return result;
		}

		boolean identical = captures.stream().allMatch(capture -> Arrays.equals(capture.capture().images().rgb(), images.rgb())
			&& Arrays.equals(capture.capture().images().visibleMask(), images.visibleMask())
			&& Arrays.equals(capture.capture().images().unoccludedMask(), images.unoccludedMask()));
		result.addProperty("repeats_identical", identical);
		if (!identical) {
			failures.add(label + captures.size() + " frozen captures differ");
		}

		CameraFrame camera = CameraFrame.of(first.pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
		int[] target = FixturePlans.ARENA.core().min();
		target = new int[] {target[0] + fixture.target[0], target[1] + fixture.target[1], target[2] + fixture.target[2]};
		MaskStatistics visible = MaskStatistics.of(images.visibleMask(), images.width(), images.height());
		MaskStatistics unoccluded = MaskStatistics.of(images.unoccludedMask(), images.width(), images.height());
		boolean inProjection = projection.intersects(camera, BlockBox.cell(target));
		TargetStatus status;
		try {
			status = TargetStatus.derive(true, inProjection, visible.positivePixels(), unoccluded.positivePixels());
		} catch (IllegalArgumentException e) {
			failures.add(label + "impossible evidence: " + e.getMessage());
			return result;
		}

		result.addProperty("target_status", status.jsonName());
		result.addProperty("visible_pixels", visible.positivePixels());
		result.addProperty("unoccluded_pixels", unoccluded.positivePixels());
		result.add("visible_bbox_xywh", JsonWrite.nullable(visible.bboxXywh()));
		if (status != fixture.expected) {
			failures.add(label + "status " + status.jsonName() + ", expected " + fixture.expected.jsonName());
		}

		RayCheck.Result rays = new RayCheck(fixture.plan(), FixturePlans.ARENA, true).check(projection, camera, target, images);
		result.addProperty("ray_unoccluded_mismatches", rays.unoccludedMismatches());
		result.addProperty("ray_visible_mismatches", rays.visibleMismatches());
		result.addProperty("definitely_occluded_positive_pixels", rays.definitelyOccluded());
		if (rays.unoccludedMismatches() != 0 || rays.visibleMismatches() != 0 || rays.definitelyOccluded() != 0) {
			failures.add(label + "masks disagree with the ray check away from edges: " + rays);
		}

		if (fixture.seam()) {
			int regions = regions(images.unoccludedMask(), images.width(), images.height());
			result.addProperty("unoccluded_regions", regions);
			if (regions != 1) {
				failures.add(label + "the target splits into " + regions + " regions across a seam");
			}
		}

		if (fixture.landmark() && unoccluded.centroidXy() != null) {
			double[] seen = projection.direction(camera, unoccluded.centroidXy()[0], unoccluded.centroidXy()[1]);
			double[] centre = {target[0] + 0.5 - camera.eye()[0], target[1] + 0.5 - camera.eye()[1], target[2] + 0.5 - camera.eye()[2]};
			double angle = angleDegrees(seen, centre);
			result.addProperty("centroid_direction_error_degrees", angle);
			if (angle > 3.0) {
				failures.add(label + "the mask centroid points " + angle + " degrees away from the target");
			}
		}

		if (fixture == Fixture.FRONT && !Arrays.equals(images.visibleMask(), images.unoccludedMask())) {
			failures.add(label + "the open target's visible and unoccluded masks differ");
		}

		int[] box = visible.bboxXywh();
		if (fixture == Fixture.IMAGE_EDGE && (box == null || box[0] + box[2] != images.width())) {
			failures.add(label + "the target does not reach the right edge at +155 degrees: " + Arrays.toString(box));
		}

		if (fixture == Fixture.BOTTOM_EDGE && (box == null || box[1] + box[3] != images.height())) {
			failures.add(label + "the target does not reach the bottom edge at −70 degrees: " + Arrays.toString(box));
		}

		if (fixture == Fixture.ZENITH && (box == null || box[1] != 0)) {
			failures.add(label + "the target overhead does not reach the top row: " + Arrays.toString(box));
		}

		CaptureService.Capture capture = first.capture();
		result.addProperty("render_ms", capture.renderNanos() / 1.0e6);
		result.addProperty("readback_ms", capture.readbackNanos() / 1.0e6);
		result.addProperty("assemble_ms", capture.assembleNanos() / 1.0e6);
		return result;
	}

	/**
	 * The M3 half and irregular occlusion fixtures in panorama mode at the default size:
	 * deterministic, consistent with the ray check where the occluder boxes are exact, and
	 * equal to their approved golden visible masks. A missing golden fails and is written
	 * for review; {@code -Dhoneyfactory.regenerateGolden=true} rewrites them all.
	 */
	private static JsonObject checkGoldens(ClientGameTestContext context, TestSingleplayerContext world, Path golden, Path review, List<String> failures) {
		boolean regenerate = Boolean.getBoolean("honeyfactory.regenerateGolden");
		JsonObject results = new JsonObject();
		for (FixturePlans.Fixture fixture : FixturePlans.Fixture.values()) {
			if (!fixture.golden()) {
				continue;
			}

			String label = "golden " + fixture.commandName() + ": ";
			GameTestSupport.await(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.plan(fixture), FixturePlans.viewpoint(fixture).pose()));
			List<EnvironmentRuntime.Observation> captures = captureFrozen(context, world, DEFAULT, REPEATS);
			CapturedImages images = captures.getFirst().capture().images();
			write(review.resolve("golden").resolve(fixture.commandName()), images);
			JsonObject result = new JsonObject();
			boolean identical = captures.stream().allMatch(capture -> Arrays.equals(capture.capture().images().rgb(), images.rgb())
				&& Arrays.equals(capture.capture().images().visibleMask(), images.visibleMask()));
			if (!identical) {
				failures.add(label + REPEATS + " frozen captures differ");
			}

			ScenePlan plan = FixturePlans.plan(fixture);
			int[] target = plan.targetPosition(FixturePlans.ARENA);
			CameraFrame camera = CameraFrame.of(captures.getFirst().pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
			RayCheck.Result rays = new RayCheck(plan, FixturePlans.ARENA, fixture != FixturePlans.Fixture.LEAVES).check(DEFAULT, camera, target, images);
			int visible = MaskStatistics.of(images.visibleMask(), images.width(), images.height()).positivePixels();
			result.addProperty("visible_pixels", visible);
			result.addProperty("unoccluded_pixels", images.unoccludedPixels());
			result.addProperty("ray_unoccluded_mismatches", rays.unoccludedMismatches());
			result.addProperty("ray_visible_mismatches", rays.visibleMismatches());
			result.addProperty("definitely_occluded_positive_pixels", rays.definitelyOccluded());
			if (rays.unoccludedMismatches() != 0 || rays.visibleMismatches() != 0 || rays.definitelyOccluded() != 0) {
				failures.add(label + "masks disagree with the ray check away from edges: " + rays);
			}

			if (visible == 0 || visible == images.unoccludedPixels()) {
				failures.add(label + "expected a partly hidden target, got " + visible + " of " + images.unoccludedPixels() + " pixels");
			}

			Path file = golden.resolve(fixture.commandName() + "-visible.png");
			try {
				if (regenerate || !Files.exists(file)) {
					Files.createDirectories(golden);
					Files.write(file, PngImages.encodeGray(images.width(), images.height(), images.visibleMask()));
					if (!regenerate) {
						failures.add(label + "no golden mask; wrote " + file + " for review");
					}
				} else {
					PngImages.Pixels pixels = PngImages.decode(Files.readAllBytes(file));
					int goldenCount = MaskStatistics.of(pixels.data(), pixels.width(), pixels.height()).positivePixels();
					boolean exact = pixels.width() == images.width() && pixels.height() == images.height() && Arrays.equals(pixels.data(), images.visibleMask());
					result.addProperty("golden_exact", exact);
					result.addProperty("golden_visible_pixels", goldenCount);
					if (!exact || Math.abs(visible - goldenCount) > 1) {
						failures.add(label + "visible mask differs from the approved golden (" + visible + " vs " + goldenCount + " pixels)");
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			results.add(fixture.commandName(), result);
		}

		return results;
	}

	/** A far target in a tiny panorama is in view but covers no pixel centre: subpixel, and level 1 rejects it. */
	private static JsonObject checkSubpixel(ClientGameTestContext context, List<String> failures) {
		PanoramicProjection tiny = new PanoramicProjection(32, 16, 16);
		FixturePlans.Fixture fixture = FixturePlans.Fixture.FAR;
		GameTestSupport.await(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.plan(fixture), FixturePlans.viewpoint(fixture).pose()));
		EnvironmentRuntime.Observation far = GameTestSupport.await(context, runtime -> runtime.observe(tiny));
		CameraFrame camera = CameraFrame.of(far.pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
		int[] target = FixturePlans.plan(fixture).targetPosition(FixturePlans.ARENA);
		int predicted = RayCheck.targetPixels(tiny, camera, target);
		ResolvedConfig.Visibility levelOne = new ResolvedConfig.Visibility(VisibilityRule.FULLY_VISIBLE, null, null);
		ObservationCheck.Outcome outcome = ObservationCheck.check(levelOne, tiny, camera, BlockBox.cell(target), far.capture().images());
		String rejection = outcome instanceof ObservationCheck.Outcome.Rejected rejected ? rejected.reason() + ": " + rejected.detail() : null;
		JsonObject result = new JsonObject();
		result.addProperty("ray_pixels", predicted);
		result.addProperty("unoccluded_pixels", far.capture().images().unoccludedPixels());
		result.addProperty("in_projection", tiny.intersects(camera, BlockBox.cell(target)));
		result.addProperty("rejection", rejection);
		if (predicted != 0 || far.capture().images().unoccludedPixels() != 0 || rejection == null || !rejection.startsWith(ObservationCheck.VISIBILITY + ": subpixel")) {
			failures.add("panorama subpixel: expected no target pixels and a visibility rejection, got " + result);
		}

		return result;
	}

	/** Switching capture mode keeps the layout, the pose and the player's rotation. */
	private static JsonObject checkModeSwitch(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		reset(context, Fixture.FRONT);
		String layout = context.computeOnClient(minecraft -> EnvironmentRuntime.get().layoutSha256());
		float[] rotation = context.computeOnClient(minecraft -> new float[] {minecraft.player.getYRot(), minecraft.player.getXRot()});
		PerspectiveProjection perspective = new PerspectiveProjection(640, 640);
		EnvironmentRuntime.Observation flat = captureFrozen(context, world, perspective, 1).getFirst();
		EnvironmentRuntime.Observation round = captureFrozen(context, world, DEFAULT, 1).getFirst();
		EnvironmentRuntime.Observation again = captureFrozen(context, world, perspective, 1).getFirst();
		String layoutAfter = context.computeOnClient(minecraft -> EnvironmentRuntime.get().layoutSha256());
		float[] rotationAfter = context.computeOnClient(minecraft -> new float[] {minecraft.player.getYRot(), minecraft.player.getXRot()});
		JsonObject result = new JsonObject();
		boolean samePose = flat.pose().equals(round.pose()) && round.pose().equals(again.pose());
		boolean sameImage = Arrays.equals(flat.capture().images().rgb(), again.capture().images().rgb())
			&& Arrays.equals(flat.capture().images().visibleMask(), again.capture().images().visibleMask());
		result.addProperty("same_pose", samePose);
		result.addProperty("same_layout", layout.equals(layoutAfter));
		result.addProperty("rotation_restored", Arrays.equals(rotation, rotationAfter));
		result.addProperty("perspective_unchanged_after_panorama", sameImage);
		CameraFrame camera = CameraFrame.of(flat.pose(), ConfigResolver.EYE_HEIGHT_BLOCKS);
		double[] flatCentroid = MaskStatistics.of(flat.capture().images().visibleMask(), 640, 640).centroidXy();
		double[] roundCentroid = MaskStatistics.of(round.capture().images().visibleMask(), DEFAULT.width(), DEFAULT.height()).centroidXy();
		double angle = flatCentroid == null || roundCentroid == null ? Double.NaN
			: angleDegrees(perspective.direction(camera, flatCentroid[0], flatCentroid[1]), DEFAULT.direction(camera, roundCentroid[0], roundCentroid[1]));
		result.addProperty("centroid_direction_difference_degrees", angle);
		if (!samePose || !layout.equals(layoutAfter) || !Arrays.equals(rotation, rotationAfter) || !sameImage || !(angle < 1.0)) {
			failures.add("mode switch changed the scene or camera: " + result);
		}

		return result;
	}

	/** A failure in the fourth face fails the whole capture and restores the player's rotation. */
	private static JsonObject checkAtomicFailure(ClientGameTestContext context, List<String> failures) {
		reset(context, Fixture.FRONT);
		float[] rotation = context.computeOnClient(minecraft -> new float[] {minecraft.player.getYRot(), minecraft.player.getXRot()});
		context.runOnClient(minecraft -> CaptureService.failNextCapture("face readback", 3));
		EnvironmentException.Reason reason = GameTestSupport.rejection(context, runtime -> runtime.observe(DEFAULT));
		float[] after = context.computeOnClient(minecraft -> new float[] {minecraft.player.getYRot(), minecraft.player.getXRot()});
		reset(context, Fixture.FRONT);
		CapturedImages recovered = GameTestSupport.await(context, runtime -> runtime.observe(DEFAULT)).capture().images();
		JsonObject result = new JsonObject();
		result.addProperty("reason", String.valueOf(reason));
		result.addProperty("rotation_restored", Arrays.equals(rotation, after));
		result.addProperty("recovered_unoccluded_pixels", recovered.unoccludedPixels());
		if (reason != EnvironmentException.Reason.CAPTURE_FAILED || !Arrays.equals(rotation, after) || recovered.unoccludedPixels() == 0) {
			failures.add("a failed face did not fail the capture cleanly: " + result);
		}

		return result;
	}

	/**
	 * Capture and reprojection costs at the default size, and the Java-or-Rust criteria:
	 * reprojection median at most 50 ms and no heap growth across runs. The share of the
	 * per-sample time is checked once the datasets have run.
	 */
	private static JsonObject benchmark(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		reset(context, Fixture.FRONT);
		List<EnvironmentRuntime.Observation> captures = captureFrozen(context, world, DEFAULT, 10);
		List<CapturedImages> faces = captures.getFirst().capture().views();
		JsonObject result = new JsonObject();
		result.addProperty("median_capture_render_ms", median(captures.stream().mapToLong(c -> c.capture().renderNanos()).toArray()));
		result.addProperty("median_capture_readback_ms", median(captures.stream().mapToLong(c -> c.capture().readbackNanos()).toArray()));
		JsonObject reprojection = context.computeOnClient(minecraft -> {
			for (int i = 0; i < 10; i++) {
				DEFAULT.assemble(faces);
			}

			long heapBefore = usedHeapAfterGc();
			long[] nanos = new long[BENCHMARK_RUNS];
			for (int i = 0; i < BENCHMARK_RUNS; i++) {
				long start = System.nanoTime();
				DEFAULT.assemble(faces);
				nanos[i] = System.nanoTime() - start;
			}

			long heapAfter = usedHeapAfterGc();
			JsonObject timing = new JsonObject();
			timing.addProperty("runs", BENCHMARK_RUNS);
			timing.addProperty("median_ms", median(nanos));
			Arrays.sort(nanos);
			timing.addProperty("p95_ms", nanos[(int) (BENCHMARK_RUNS * 0.95)] / 1.0e6);
			timing.addProperty("heap_growth_bytes", heapAfter - heapBefore);
			return timing;
		});
		result.add("reprojection", reprojection);
		double median = reprojection.get("median_ms").getAsDouble();
		if (median > REPROJECTION_BUDGET_MS) {
			failures.add("Java reprojection median " + median + " ms exceeds " + REPROJECTION_BUDGET_MS + " ms: implement it in Rust (roadmap M5)");
		}

		if (reprojection.get("heap_growth_bytes").getAsLong() > 16L << 20) {
			failures.add("reprojection grows the heap by " + reprojection.get("heap_growth_bytes").getAsLong() + " bytes over " + BENCHMARK_RUNS + " runs");
		}

		int size = DEFAULT.faceSize();
		long faceBytes = 6L * size * size * 5;
		long panoramaBytes = (long) DEFAULT.width() * DEFAULT.height() * 5;
		result.addProperty("cpu_face_buffers_bytes", faceBytes);
		result.addProperty("cpu_panorama_buffers_bytes", panoramaBytes);
		result.addProperty("sampling_table_bytes", (long) DEFAULT.width() * DEFAULT.height() * 8);
		long encodeStart = System.nanoTime();
		long faceFileBytes = 0;
		for (CapturedImages face : faces) {
			faceFileBytes += PngImages.encodeRgb(size, size, face.rgb()).length + PngImages.encodeGray(size, size, face.visibleMask()).length;
		}

		result.addProperty("face_png_bytes", faceFileBytes);
		result.addProperty("face_png_encode_ms", (System.nanoTime() - encodeStart) / 1.0e6);
		CapturedImages panorama = captures.getFirst().capture().images();
		encodeStart = System.nanoTime();
		long panoramaFileBytes = PngImages.encodeRgb(panorama.width(), panorama.height(), panorama.rgb()).length
			+ PngImages.encodeGray(panorama.width(), panorama.height(), panorama.visibleMask()).length;
		result.addProperty("panorama_png_bytes", panoramaFileBytes);
		result.addProperty("panorama_png_encode_ms", (System.nanoTime() - encodeStart) / 1.0e6);
		Long vram = vramUsedBytes();
		if (vram != null) {
			result.addProperty("vram_used_bytes_after", vram);
		}

		return result;
	}

	/**
	 * Level-1 panorama dataset at the default size and level-3 at the small size, both
	 * validated. The level-1 run's time per sample sets the reprojection-share criterion.
	 */
	private static JsonObject datasets(ClientGameTestContext context, Path root, List<String> failures, JsonObject benchmark) {
		JsonObject result = new JsonObject();
		for (int level : new int[] {1, 3}) {
			PanoramicProjection projection = level == 1 ? DEFAULT : SMALL;
			String name = "honey-factory-m5-level" + level;
			DatasetGenerator.Result run;
			try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
				GameTestSupport.claim(world, name);
				run = GameTestSupport.generate(context, name, config(level, projection, root.resolve("level" + level)), progress -> false, 20 * 60 * 30);
			}

			JsonObject entry = new JsonObject();
			DatasetManifest manifest = run.manifest();
			entry.addProperty("dataset_id", manifest.datasetId());
			entry.addProperty("lifecycle", manifest.lifecycle().jsonName());
			entry.addProperty("failure", run.failure());
			entry.addProperty("accepted_samples", manifest.counts().acceptedSamples());
			entry.addProperty("attempted_scenes", manifest.counts().attemptedScenes());
			if (run.failure() != null) {
				failures.add(name + ": generation failed: " + run.failure() + " " + run.validationErrors());
				result.add("level" + level, entry);
				continue;
			}

			try {
				DatasetValidator.Report report = DatasetValidator.validate(run.directory());
				if (!report.passed()) {
					failures.add(name + ": validation errors " + report.errors());
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			double seconds = java.time.Duration.between(manifest.createdAtUtc(), manifest.completedAtUtc()).toMillis() / 1000.0;
			double perSampleMs = seconds * 1000.0 / manifest.counts().acceptedSamples();
			entry.addProperty("seconds", seconds);
			entry.addProperty("ms_per_sample", perSampleMs);
			if (level == 1) {
				double share = benchmark.getAsJsonObject("reprojection").get("median_ms").getAsDouble() / perSampleMs;
				entry.addProperty("reprojection_share", share);
				if (share > REPROJECTION_SHARE) {
					failures.add("Java reprojection takes " + share + " of the per-sample time, above " + REPROJECTION_SHARE + ": implement it in Rust (roadmap M5)");
				}
			}

			result.add("level" + level, entry);
		}

		return result;
	}

	private static String config(int level, PanoramicProjection projection, Path root) {
		return """
			{
			  "dataset": {"name": "m5-panorama-l%d", "seed": %d, "difficulty": %d},
			  "viewpoints": {"count": 4},
			  "capture": {"mode": "PANORAMA_310", "width": %d, "height": %d, "cube_face_size": %d},
			  "quotas": {"accepted_scenes": 4},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1073741824}
			}
			""".formatted(level, 3000 + level, level, projection.width(), projection.height(), projection.faceSize(), root.toString().replace("\\", "\\\\"));
	}

	/** 8-connected regions of positive pixels. */
	private static int regions(byte[] mask, int width, int height) {
		int[] label = new int[mask.length];
		int regions = 0;
		int[] stack = new int[mask.length];
		for (int start = 0; start < mask.length; start++) {
			if (mask[start] == 0 || label[start] != 0) {
				continue;
			}

			regions++;
			int top = 0;
			stack[top++] = start;
			label[start] = regions;
			while (top > 0) {
				int p = stack[--top];
				int x = p % width;
				int y = p / width;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if (nx >= 0 && ny >= 0 && nx < width && ny < height && mask[ny * width + nx] != 0 && label[ny * width + nx] == 0) {
							label[ny * width + nx] = regions;
							stack[top++] = ny * width + nx;
						}
					}
				}
			}
		}

		return regions;
	}

	private static double angleDegrees(double[] a, double[] b) {
		double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
		double norms = Math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * (b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
		return Math.toDegrees(Math.acos(Math.clamp(dot / norms, -1.0, 1.0)));
	}

	private static double median(long[] nanos) {
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2] / 1.0e6;
	}

	private static long usedHeapAfterGc() {
		Runtime runtime = Runtime.getRuntime();
		System.gc();
		return runtime.totalMemory() - runtime.freeMemory();
	}

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

	private static void writeFaces(Path directory, List<CapturedImages> faces) {
		for (int i = 0; i < faces.size(); i++) {
			write(directory.resolve(CubeFace.values()[i].name().toLowerCase(Locale.ROOT)), faces.get(i));
		}
	}

	private static int[][] cube(int x, int y, int z) {
		List<int[]> cells = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					cells.add(new int[] {x + dx, y + dy, z + dz});
				}
			}
		}

		return cells.toArray(int[][]::new);
	}
}
