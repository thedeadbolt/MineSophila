package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * M12-1: a capture taken as soon as the runtime calls a pose ready equals one taken long
 * after. A panorama looks every way, so after a reset it must not draw any part of the
 * previous scene that the camera had not looked at yet.
 */
public class M12ReadinessTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m12-readiness";
	private static final PanoramicProjection PANORAMA = new PanoramicProjection(1240, 640, 512);
	private static final int SCENES = 6;
	private static final int SETTLED_TICKS = 40;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		SceneSpec level4 = SceneRecipe.of("level4").build();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			for (int index = 0; index < SCENES; index++) {
				ScenePreview preview = ScenePreview.of(level4, 12L, index);
				CameraPose pose = preview.viewpoint().pose(preview.arena());
				GameTestSupport.await(context, runtime -> runtime.reset(preview.arena(), preview.scene().plan(), pose), 20 * 60 * 5);
				CapturedImages ready = capture(context, pose);
				context.waitTicks(SETTLED_TICKS);
				CapturedImages settled = capture(context, pose);
				compare("scene " + index + " right after its reset", ready, settled, failures);
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M12 readiness failures:\n" + String.join("\n", failures));
		}
	}

	private static CapturedImages capture(ClientGameTestContext context, CameraPose pose) {
		List<EnvironmentRuntime.Observation> observations = GameTestSupport.await(context, runtime -> runtime.observeAll(PANORAMA, List.of(pose)));
		return observations.getFirst().capture().images();
	}

	private static void compare(String what, CapturedImages ready, CapturedImages settled, List<String> failures) {
		if (!Arrays.equals(ready.visibleMask(), settled.visibleMask())) {
			failures.add(what + ": the visible mask differs from a capture " + SETTLED_TICKS + " ticks later");
		}

		byte[] a = ready.rgb();
		byte[] b = settled.rgb();
		int differing = 0;
		for (int p = 0; p < a.length; p += 3) {
			if (a[p] != b[p] || a[p + 1] != b[p + 1] || a[p + 2] != b[p + 2]) {
				differing++;
			}
		}

		if (differing > 0) {
			failures.add(what + ": " + differing + " RGB pixels differ from a capture " + SETTLED_TICKS + " ticks later");
		}
	}
}
