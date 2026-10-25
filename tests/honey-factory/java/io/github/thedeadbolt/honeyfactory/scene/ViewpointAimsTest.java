package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Where the target lands in the image under each aim, over many level-1 viewpoints. */
class ViewpointAimsTest {
	private static final int SCENES = 100;

	private static ResolvedConfig config(String aim, String mode) {
		return ConfigResolver.resolve("""
			{"dataset": {"name": "aim-test", "seed": 7, "difficulty": 1}, "viewpoints": {"aim": "%s"}, "capture": {"mode": "%s"},
			 "output": {"root": "/tmp/honey"}}
			""".formatted(aim, mode));
	}

	/**
	 * Image-uniform aim spreads the block centre over the image's width, several times wider
	 * than jitter does, and keeps every placed block wholly inside the frame margin. Close
	 * targets are too large to fit near a perspective image's sides, so its outer fifths
	 * hold fewer.
	 */
	@ParameterizedTest
	@CsvSource({"PERSPECTIVE_90, 0.12, 3", "PANORAMA_310, 0.16, 8"})
	void imageUniformAimSpreadsTheTargetAcrossTheImage(String mode, double minimumPerFifth, double spreadFactor) {
		double[][] uniform = centres(config("image_uniform", mode), true);
		double[][] jitter = centres(config("target_jitter", mode), false);
		int[] fifths = fifths(uniform[0]);
		System.out.printf("%s: image_uniform column fifths %s, row fifths %s; column spread %.3f against %.3f with jitter%n", mode, Arrays.toString(fifths),
			Arrays.toString(fifths(uniform[1])), spread(uniform[0]), spread(jitter[0]));
		for (int count : fifths) {
			assertTrue(count >= minimumPerFifth * uniform[0].length, mode + " column fifths " + Arrays.toString(fifths));
		}

		assertTrue(spread(uniform[0]) > spreadFactor * spread(jitter[0]), mode + " spread " + spread(uniform[0]) + " against " + spread(jitter[0]));
		if (mode.equals("PERSPECTIVE_90")) {
			for (int count : fifths(uniform[1])) {
				assertTrue(count >= minimumPerFifth * uniform[1].length, "perspective row fifths " + Arrays.toString(fifths(uniform[1])));
			}
		}
	}

	private static int[] fifths(double[] values) {
		int[] fifths = new int[5];
		for (double value : values) {
			fifths[Math.min(4, (int) (value * 5))]++;
		}

		return fifths;
	}

	/**
	 * Normalized columns and rows of the aim block's centre in every first placeable
	 * viewpoint; checks the block fits when {@code contained}.
	 */
	private static double[][] centres(ResolvedConfig config, boolean contained) {
		SceneGenerator generator = SceneGenerators.forConfig(config);
		CaptureProjection projection = config.projection();
		Map<String, Integer> rejections = new TreeMap<>();
		double[][] centres = new double[2][SCENES * config.viewpoints().count()];
		int placed = 0;
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = ((RecipeGenerator) generator).scene(SceneSeeds.derive(7, index, 0), true);
			int[] aim = scene.aimBlock();
			for (int viewpoint = 0; viewpoint < config.viewpoints().count(); viewpoint++) {
				for (int attempt = 0; attempt < config.viewpoints().attemptsPerViewpoint(); attempt++) {
					ViewpointCandidate candidate = generator.viewpoint(scene, viewpoint, attempt);
					if (candidate instanceof ViewpointCandidate.Rejected rejected) {
						rejections.merge(rejected.reason(), 1, Integer::sum);
						continue;
					}

					ViewpointPlan plan = ((ViewpointCandidate.Placeable) candidate).plan();
					CameraFrame camera = CameraFrame.of(new CameraPose(plan.x(), plan.y(), plan.z(),
						(float) plan.yawDegrees(), (float) plan.pitchDegrees()), config.viewpoints().eyeHeightBlocks());
					double[] centre = projection.project(camera, new double[] {aim[0] + 0.5, aim[1] + 0.5, aim[2] + 0.5});
					if (contained) {
						assertTrue(projection.contains(camera, new double[] {aim[0], aim[1], aim[2]}, new double[] {aim[0] + 1, aim[1] + 1, aim[2] + 1},
							ViewpointAims.FRAME_MARGIN_PX), "the block lies inside the frame");
					}

					centres[0][placed] = centre[0] / projection.width();
					centres[1][placed++] = centre[1] / projection.height();
					break;
				}
			}
		}

		System.out.printf("%s %s: %d placed, rejections %s%n", config.capture().mode(), config.viewpoints().aim().jsonName(), placed, rejections);
		assertEquals(centres[0].length, placed, "every viewpoint found a placeable draw");
		return centres;
	}

	/** The interquartile range. */
	private static double spread(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length * 3 / 4] - sorted[sorted.length / 4];
	}
}
