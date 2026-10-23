package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Level1GeneratorTest {
	private static final ResolvedConfig CONFIG = ConfigResolver.resolve("""
		{"dataset": {"name": "level1-test", "seed": 42, "difficulty": 1}, "output": {"root": "/tmp/honey"}}
		""");
	private static final Level1Generator GENERATOR = new Level1Generator(CONFIG);

	@Test
	void recordsItsVersionInTheResolvedConfiguration() {
		assertEquals(Algorithms.LEVEL1_GENERATOR, CONFIG.toJson().getAsJsonObject("generator").getAsJsonObject("algorithms").get("scene_generator").getAsString());
		assertEquals(Algorithms.LEVEL1_GENERATOR, GENERATOR.version());
		ResolvedConfig level2 = ConfigResolver.resolve("""
			{"dataset": {"name": "level2-test", "seed": 42, "difficulty": 2}, "output": {"root": "/tmp/honey"}}
			""");
		assertTrue(!level2.toJson().getAsJsonObject("generator").getAsJsonObject("algorithms").has("scene_generator"));
		assertThrows(UnsupportedOperationException.class, () -> SceneGenerators.forConfig(level2));
		assertThrows(IllegalArgumentException.class, () -> new Level1Generator(level2));
	}

	@Test
	void presentScenesPlaceOneTargetOnTheFloor() {
		for (int index = 0; index < 50; index++) {
			GeneratedScene scene = GENERATOR.scene(index, 0, SceneSeeds.derive(42, index, 0), true);
			Placement target = scene.plan().target();
			assertNotNull(target);
			assertEquals(List.of(target), scene.plan().delta());
			assertEquals(1, target.y());
			assertTrue(target.x() >= 0 && target.x() < 32 && target.z() >= 0 && target.z() < 32);
			assertEquals("minecraft:honey_block", target.state());
			assertArrayEquals(new int[] {target.x(), 1, target.z()}, scene.aimBlock());
			assertEquals(Level1Generator.TEMPLATE_ID, scene.plan().template().id());
			assertEquals(32 * 32, scene.plan().template().blocks().size());
		}
	}

	@Test
	void absentScenesAimWhereTheTargetWouldBe() {
		SceneSeeds seeds = SceneSeeds.derive(42, 7, 3);
		GeneratedScene present = GENERATOR.scene(7, 3, seeds, true);
		GeneratedScene absent = GENERATOR.scene(7, 3, seeds, false);
		assertNull(absent.plan().target());
		assertTrue(absent.plan().delta().isEmpty());
		assertArrayEquals(present.aimBlock(), absent.aimBlock());
		assertEquals(present.plan().template(), absent.plan().template());
		assertEquals(GENERATOR.viewpoint(present, 2, 5), GENERATOR.viewpoint(absent, 2, 5));
	}

	@Test
	void viewpointsFollowTheConfiguredDistanceAndJitter() {
		GeneratedScene scene = GENERATOR.scene(0, 0, SceneSeeds.derive(42, 0, 0), true);
		int[] aim = scene.aimBlock();
		Set<String> rejections = new HashSet<>();
		int accepted = 0;
		for (int viewpoint = 0; viewpoint < 8; viewpoint++) {
			for (int attempt = 0; attempt < 50; attempt++) {
				ViewpointCandidate candidate = GENERATOR.viewpoint(scene, viewpoint, attempt);
				assertEquals(candidate, GENERATOR.viewpoint(scene, viewpoint, attempt), "draws are deterministic");
				if (candidate.plan() == null) {
					rejections.add(candidate.rejection());
					continue;
				}

				accepted++;
				ViewpointPlan plan = candidate.plan();
				double dx = aim[0] + 0.5 - plan.x();
				double dz = aim[2] + 0.5 - plan.z();
				double distance = Math.hypot(dx, dz);
				assertTrue(distance >= 3.0 && distance <= 16.0, "distance " + distance);
				assertEquals(1.0, plan.y());
				assertTrue(plan.x() >= 0.3 && plan.x() <= 31.7 && plan.z() >= 0.3 && plan.z() <= 31.7);
				ViewpointPlan aimed = ViewpointPlan.lookingAt(plan.x(), plan.y(), plan.z(), 1.62, aim[0], aim[1], aim[2]);
				assertTrue(Math.abs(Level1Generator.wrapDegrees(plan.yawDegrees() - aimed.yawDegrees())) <= 15.0 + 1e-4);
				assertTrue(Math.abs(plan.pitchDegrees() - aimed.pitchDegrees()) <= 10.0 + 1e-4);
				assertEquals(plan.yawDegrees(), (float) plan.yawDegrees(), "angles are stored at float precision");
			}
		}

		assertTrue(accepted > 0);
		assertTrue(Set.of("camera_off_platform", "camera_overlaps_target").containsAll(rejections));
	}

	/** Pins the generator's output so an accidental algorithm change cannot pass silently. */
	@Test
	void outputIsPinned() {
		GeneratedScene scene = GENERATOR.scene(3, 1, SceneSeeds.derive(42, 3, 1), true);
		ArenaLayout arena = ArenaLayout.of(CONFIG.arena());
		assertEquals(PINNED_LAYOUT, scene.plan().layoutSha256(arena));
		StringBuilder draws = new StringBuilder();
		for (int attempt = 0; attempt < 4; attempt++) {
			draws.append(GENERATOR.viewpoint(scene, 4, attempt)).append('\n');
		}

		assertEquals(PINNED_VIEWPOINTS, draws.toString());
	}

	@Test
	void seededStreamsAreIndependentOfEachOther() {
		SeededRandom a = new SeededRandom("key-a");
		SeededRandom b = new SeededRandom("key-b");
		assertNotEquals(a.nextLong53(), b.nextLong53());
		SeededRandom repeat = new SeededRandom("key-a");
		long first = repeat.nextLong53();
		assertEquals(first, new SeededRandom("key-a").nextLong53());
		for (int i = 0; i < 1000; i++) {
			int value = repeat.nextInt(7);
			assertTrue(value >= 0 && value < 7);
			double unit = repeat.nextDouble();
			assertTrue(unit >= 0.0 && unit < 1.0);
		}
	}

	private static final String PINNED_LAYOUT = "4524b21ee056ed19744fa244f43548590b8f79dee2f7cbe4115f6d68cfa16e6d";
	private static final String PINNED_VIEWPOINTS = """
		ViewpointCandidate[plan=ViewpointPlan[x=22.941385641938943, y=1.0, z=26.128690485560043, yawDegrees=98.75511169433594, pitchDegrees=-0.7622568011283875], rejection=null]
		ViewpointCandidate[plan=ViewpointPlan[x=6.869347503393224, y=1.0, z=14.080706581627279, yawDegrees=-34.49997329711914, pitchDegrees=11.442506790161133], rejection=null]
		ViewpointCandidate[plan=null, rejection=camera_off_platform]
		ViewpointCandidate[plan=null, rejection=camera_off_platform]
		""";
}
