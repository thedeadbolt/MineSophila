package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * M12-2: the in-between frames of a control tick are drawn from the camera's interpolated
 * pose, and their masks lie on the honey in their own RGB. The body walks towards a level-1
 * honey block at 3 frames per tick, so each in-between frame must differ from its tick's
 * last frame, and in every frame the mask's centroid must sit on honey-coloured pixels.
 * Runs with the M12 tests ({@code -Dhoneyfactory.gametests=M12Frames}).
 */
public class M12FramesTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m12-frames";
	private static final PanoramicProjection PANORAMA = new PanoramicProjection(2480, 1280, 1024);
	private static final int STEPS = 12;
	/** The share of mask pixels that must show honey colours. */
	private static final double MIN_HONEY_SHARE = 0.75;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		ScenePreview preview = ScenePreview.of(SceneRecipe.of("level1").build(), 12_026L, 0);
		CameraPose start = preview.viewpoint().pose(preview.arena());
		MotorCommand walk = new MotorCommand(1.0, 0.0, 3.0, 0.0, false, 0);
		MotorCommand straight = new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0);
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			GameTestSupport.await(context, runtime -> runtime.controlReset(preview.arena(), preview.scene().plan(), start, PANORAMA, 7L, 3), 20 * 60);
			for (int i = 0; i < STEPS; i++) {
				// Walking while turning, then straight on: in-between frames must move in both.
				MotorCommand command = i < STEPS / 2 ? walk : straight;
				EnvironmentRuntime.ControlResult result = GameTestSupport.await(context, runtime -> runtime.controlStep(command, null, ControlStep.Executed.STUDENT));
				List<CaptureService.Capture> frames = result.frames();
				CapturedImages end = frames.getLast().images();
				for (int k = 0; k < frames.size(); k++) {
					CapturedImages images = frames.get(k).images();
					if (k < frames.size() - 1 && i > 0 && Arrays.equals(images.rgb(), end.rgb())) {
						failures.add("step " + i + " frame " + k + ": the in-between RGB equals the tick's last frame, so the camera did not move");
					}

					String mismatch = maskAgainstHoney(images);
					if (mismatch != null) {
						failures.add("step " + i + " frame " + k + " (" + frames.get(k).pose() + "): " + mismatch);
					}
				}
			}

			context.runOnClient(minecraft -> EnvironmentRuntime.get().controlEnd());
		}

		HoneyFactory.LOGGER.info("M12 frames: {} failures", failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("M12 frame failures:\n" + String.join("\n", failures));
		}
	}

	/**
	 * Why the mask does not lie on the honey in the same frame's RGB, or null when it does:
	 * most visible-mask pixels must show honey colours. Frames with fewer than 50 mask pixels
	 * are skipped.
	 */
	private static String maskAgainstHoney(CapturedImages images) {
		int masked = 0;
		int honey = 0;
		for (int p = 0; p < images.width() * images.height(); p++) {
			if (images.visibleMask()[p] != 0) {
				masked++;
				honey += honeyColoured(images, p) ? 1 : 0;
			}
		}

		if (masked < 50) {
			return null;
		}

		double share = honey / (double) masked;
		HoneyFactory.LOGGER.info("M12 frames: {} mask pixels, {} honey-coloured", masked, String.format("%.3f", share));
		return share >= MIN_HONEY_SHARE ? null : String.format("only %.0f%% of the %d mask pixels show honey", share * 100, masked);
	}

	/** Honey's amber, on any of its faces: red and green well above blue. */
	private static boolean honeyColoured(CapturedImages images, int p) {
		int r = images.rgb()[p * 3] & 0xFF;
		int g = images.rgb()[p * 3 + 1] & 0xFF;
		int b = images.rgb()[p * 3 + 2] & 0xFF;
		return r > 90 && g > 50 && r - b > 60 && g - b > 20;
	}
}
