package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import io.github.thedeadbolt.honeyfactory.world.ArenaExecutor;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * M12-1 task 8: a platform reset writes only the blocks that differ from what the arena
 * holds. Resetting to the same plan twice writes nothing the second time, and switching
 * between level-4 scenes ends with each scene's own layout hash.
 */
public class M12ResetTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m12-reset";

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
			ScenePreview first = ScenePreview.of(level4, 12L, 0);
			ScenePreview second = ScenePreview.of(level4, 12L, 1);
			ArenaExecutor.ResetResult initial = reset(context, first);
			ArenaExecutor.ResetResult again = reset(context, first);
			if (again.blocksWritten() != 0) {
				failures.add("resetting to the same plan again wrote " + again.blocksWritten() + " blocks");
			}

			if (!again.layoutSha256().equals(initial.layoutSha256())) {
				failures.add("the same plan reset twice gave two layout hashes");
			}

			ArenaExecutor.ResetResult switched = reset(context, second);
			if (!switched.layoutSha256().equals(second.scene().plan().layoutSha256(second.arena()))) {
				failures.add("switching scenes left layout " + switched.layoutSha256() + ", not the second scene's");
			}

			if (switched.blocksWritten() == 0 || switched.blocksWritten() >= initial.blocksWritten()) {
				failures.add("switching scenes wrote " + switched.blocksWritten() + " blocks; the first build wrote " + initial.blocksWritten());
			}

			ArenaExecutor.ResetResult back = reset(context, first);
			if (!back.layoutSha256().equals(initial.layoutSha256())) {
				failures.add("switching back left layout " + back.layoutSha256() + ", not the first scene's");
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M12 reset failures:\n" + String.join("\n", failures));
		}
	}

	private static ArenaExecutor.ResetResult reset(ClientGameTestContext context, ScenePreview preview) {
		GameTestSupport.await(context, runtime -> runtime.reset(preview.arena(), preview.scene().plan(), preview.viewpoint().pose(preview.arena())), 20 * 60 * 5);
		return context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastReset());
	}
}
