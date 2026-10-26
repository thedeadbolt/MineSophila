package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.NaturalGenerator;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import io.github.thedeadbolt.honeyfactory.world.NaturalPatches;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * M12-1 task 6: GPU reprojection equals the CPU's. For a level-1, a level-4 and a clear
 * level-5 scene, each pose is captured on the CPU and then on the GPU: at the viewpoint,
 * turned so the target lies on the ±155° sides and across cube-face boundaries, and looking
 * down so it lies near the −70° floor. Both masks must be identical in every pixel. RGB must
 * be identical on platforms; on terrain, animated textures such as water change between the
 * two captures, so up to 0.1 % of RGB bytes may differ there. A forced GPU failure must fall
 * back to the CPU and still return the CPU's images.
 */
public class M12GpuReprojectionTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m12-gpu";
	private static final PanoramicProjection PANORAMA = new PanoramicProjection(2480, 1280, 1024);
	/** Yaw and pitch offsets from the viewpoint: as placed, the sides, face boundaries and the floor. */
	private static final float[][] TURNS = {{0, 0}, {150, 0}, {-150, 0}, {45, 0}, {-45, 10}, {90, -30}, {20, 60}};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			for (String level : new String[] {"level1", "level4"}) {
				ScenePreview preview = ScenePreview.of(SceneRecipe.of(level).build(), 12L, 0);
				compare(context, level, preview.arena(), preview.scene(), preview.viewpoint().pose(preview.arena()), failures);
			}

			ResolvedConfig config = ConfigResolver.resolve("""
				{"dataset": {"name": "gpu", "seed": 5, "difficulty": 4}, "output": {"root": "/tmp/honey-factory-unused"}}
				""");
			SceneSpec clear = SceneRecipe.of("level5").lighting(6000, Weather.CLEAR).build();
			NaturalGenerator natural = new NaturalGenerator(clear, List.of("minecraft:honey_block"), config.viewpoints(), config.projection());
			PreparedPatch patch = world.getServer().computeOnServer(server -> NaturalPatches.prepare(server, new NaturalPatches.Request(5, natural.base(), 0, "minecraft:honey_block")));
			GeneratedScene terrain = natural.scene(SceneSeeds.derive(5, 0, 0), true, patch);
			ArenaLayout arena = ArenaLayout.natural(natural.base());
			CameraPose pose = null;
			for (int attempt = 0; pose == null && attempt < 200; attempt++) {
				if (natural.viewpoint(terrain, 0, attempt) instanceof ViewpointCandidate.Placeable placeable) {
					pose = placeable.plan().pose(arena);
				}
			}

			if (pose == null) {
				failures.add("level 5: no placeable viewpoint");
			} else {
				compare(context, "level5", arena, terrain, pose, failures);
			}

			fallback(context, failures);
		} finally {
			gpu(context, false);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M12 GPU reprojection failures:\n" + String.join("\n", failures));
		}
	}

	private static void compare(ClientGameTestContext context, String level, ArenaLayout arena, GeneratedScene scene, CameraPose viewpoint, List<String> failures) {
		GameTestSupport.await(context, runtime -> runtime.reset(arena, scene.plan(), viewpoint), 20 * 60 * 10);
		for (float[] turn : TURNS) {
			CameraPose pose = new CameraPose(viewpoint.x(), viewpoint.y(), viewpoint.z(), viewpoint.yawDegrees() + turn[0], Math.clamp(viewpoint.pitchDegrees() + turn[1], -90.0F, 90.0F));
			String what = level + " turned " + turn[0] + "/" + turn[1];
			gpu(context, false);
			CapturedImages cpu = capture(context, pose);
			gpu(context, true);
			CapturedImages gpu = capture(context, pose);
			if (!Arrays.equals(cpu.visibleMask(), gpu.visibleMask()) || !Arrays.equals(cpu.unoccludedMask(), gpu.unoccludedMask())) {
				failures.add(what + ": GPU masks differ from the CPU's");
			}

			int differing = 0;
			for (int i = 0; i < cpu.rgb().length; i++) {
				differing += cpu.rgb()[i] == gpu.rgb()[i] ? 0 : 1;
			}

			int allowed = arena.natural() ? cpu.rgb().length / 1000 : 0;
			if (differing > allowed) {
				failures.add(what + ": " + differing + " GPU RGB bytes differ from the CPU's, " + allowed + " allowed");
			}
		}
	}

	/** A forced GPU failure falls back to the CPU, which returns the CPU's images, and turns the GPU path off. */
	private static void fallback(ClientGameTestContext context, List<String> failures) {
		CameraPose pose = context.computeOnClient(minecraft -> new CameraPose(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), minecraft.player.getYRot(), minecraft.player.getXRot()));
		gpu(context, false);
		CapturedImages cpu = capture(context, pose);
		gpu(context, true);
		context.runOnClient(minecraft -> CaptureService.failNextGpuReprojection("fallback test"));
		CapturedImages fellBack = capture(context, pose);
		if (!Arrays.equals(cpu.visibleMask(), fellBack.visibleMask()) || !Arrays.equals(cpu.unoccludedMask(), fellBack.unoccludedMask())) {
			failures.add("a failed GPU reprojection did not fall back to the CPU's masks");
		}

		if (context.computeOnClient(minecraft -> CaptureService.gpuReprojectionUsable())) {
			failures.add("a failed GPU reprojection left the GPU path on");
		}

		context.runOnClient(minecraft -> CaptureService.enableGpuReprojectionAgain());
	}

	private static void gpu(ClientGameTestContext context, boolean enabled) {
		context.runOnClient(minecraft -> EnvironmentRuntime.get().configure(EnvironmentRuntime.get().settings().withGpuReprojection(enabled)));
	}

	private static CapturedImages capture(ClientGameTestContext context, CameraPose pose) {
		return GameTestSupport.await(context, runtime -> runtime.observeAll(PANORAMA, List.of(pose))).getFirst().capture().images();
	}
}
