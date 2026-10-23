package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Levels 2 and 3: layouts around the aim cell and the viewpoints each level keeps. */
class ClutterGeneratorsTest {
	private static final int SCENES = 40;

	private static PlatformGenerator generator(int level) {
		return (PlatformGenerator) SceneGenerators.forConfig(config(level));
	}

	private static ResolvedConfig config(int level) {
		return ConfigResolver.resolve("""
			{"dataset": {"name": "level%d-test", "seed": 42, "difficulty": %d}, "output": {"root": "/tmp/honey"}}
			""".formatted(level, level));
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void recordsItsVersionAndSharesTheLevel1Platform(int level) {
		PlatformGenerator generator = generator(level);
		assertEquals(Algorithms.sceneGenerator(level), generator.version());
		assertEquals(generator.version(), config(level).toJson().getAsJsonObject("generator").getAsJsonObject("algorithms").get("scene_generator").getAsString());
		assertEquals(new Level1Generator(config(1)).template(), generator.template());
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void bothClassesShareTheLayoutAroundTheAimCell(int level) {
		PlatformGenerator generator = generator(level);
		for (int index = 0; index < SCENES; index++) {
			SceneSeeds seeds = SceneSeeds.derive(42, index, 0);
			GeneratedScene present = generator.scene(seeds, true);
			GeneratedScene absent = generator.scene(seeds, false);
			assertEquals(present.plan(), generator.scene(seeds, true).plan(), "plans are deterministic");
			assertArrayEquals(present.aimBlock(), absent.aimBlock());
			assertNull(absent.plan().target());
			Placement target = present.plan().target();
			assertNotNull(target);
			assertEquals(present.plan().delta().subList(0, present.plan().delta().size() - 1), absent.plan().delta());
			assertEquals(target, present.plan().delta().getLast());
			assertEquals(present.layoutParameters(), absent.layoutParameters());
			assertTrue(present.layoutParameters().get("blocks").getAsInt() == absent.plan().delta().size());
			for (Placement block : absent.plan().delta()) {
				assertTrue(block.y() >= 1, "layout blocks stand on the floor: " + block);
				boolean palette = BlockPalettes.DISTRACTORS.contains(block.state()) || BlockPalettes.CONCRETE.contains(block.state())
					|| BlockPalettes.TEXTURED.contains(block.state()) || BlockPalettes.STRUCTURES.contains(block.state());
				assertTrue(palette, "unexpected block " + block.state());
			}
		}
	}

	@Test
	void level2ClutterKeepsClearOfTheTarget() {
		PlatformGenerator generator = generator(2);
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), false);
			int[] aim = scene.aimBlock();
			assertTrue(scene.plan().delta().size() >= 6, "level 2 places clutter");
			for (Placement block : scene.plan().delta()) {
				assertTrue(chebyshev(block, aim) >= Level2Generator.CLEARANCE, "clutter beside the target: " + block);
				assertTrue(block.y() <= 2, "piles are at most two blocks tall");
			}
		}
	}

	@Test
	void level3BuildsAHidingStructureAgainstTheTarget() {
		PlatformGenerator generator = generator(3);
		Set<String> kinds = new HashSet<>();
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), false);
			int[] aim = scene.aimBlock();
			kinds.add(scene.layoutParameters().get("structure").getAsString());
			String material = scene.layoutParameters().get("structure_material").getAsString();
			boolean hiding = false;
			for (Placement block : scene.plan().delta()) {
				int distance = chebyshev(block, aim);
				hiding |= distance <= 1;
				assertTrue(distance >= Level3Generator.CLEARANCE || block.state().equals(material), "only the hiding structure comes close: " + block);
			}

			assertTrue(hiding || touchesArenaEdge(aim), "scene " + index + " has no hiding structure");
		}

		assertEquals(Set.of("wall", "corner", "recess", "pillars"), kinds);
	}

	/** Every kept viewpoint suits its level, and most viewpoints find one within the default attempts. */
	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void keptViewpointsSuitTheLevel(int level) {
		PlatformGenerator generator = generator(level);
		ResolvedConfig.Viewpoints settings = config(level).viewpoints();
		Map<String, Integer> rejections = new TreeMap<>();
		List<Integer> firstPlaceable = new ArrayList<>();
		int exhausted = 0;
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), true);
			SolidGrid grid = SolidGrid.of(scene.plan());
			for (int viewpoint = 0; viewpoint < settings.count(); viewpoint++) {
				Integer first = null;
				for (int attempt = 0; attempt < settings.attemptsPerViewpoint(); attempt++) {
					ViewpointCandidate candidate = generator.viewpoint(scene, viewpoint, attempt);
					if (candidate instanceof ViewpointCandidate.Rejected rejected) {
						rejections.merge(rejected.reason(), 1, Integer::sum);
						continue;
					}

					ViewpointPlan plan = ((ViewpointCandidate.Placeable) candidate).plan();
					double clear = SightLines.clearFraction(grid, new double[] {plan.x(), plan.y() + settings.eyeHeightBlocks(), plan.z()}, scene.aimBlock());
					assertTrue(level == 2 ? clear == 1.0 : clear > 0.0 && clear < 1.0, "level " + level + " kept a view with clear fraction " + clear);
					if (first == null) {
						first = attempt;
					}
				}

				if (first == null) {
					exhausted++;
				} else {
					firstPlaceable.add(first);
				}
			}
		}

		System.out.printf("level %d: rejections %s, %d of %d viewpoints exhausted, mean first placeable %.2f%n", level, rejections, exhausted,
			SCENES * settings.count(), firstPlaceable.stream().mapToInt(Integer::intValue).average().orElse(-1));
		assertTrue(exhausted <= SCENES * settings.count() / 20, exhausted + " viewpoints found no placeable draw");
	}

	private static int chebyshev(Placement block, int[] aim) {
		return Math.max(Math.abs(block.x() - aim[0]), Math.abs(block.z() - aim[2]));
	}

	private static boolean touchesArenaEdge(int[] aim) {
		return aim[0] == 0 || aim[2] == 0 || aim[0] == 31 || aim[2] == 31;
	}

	/** Pins each generator's output so an accidental algorithm change cannot pass silently. */
	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void outputIsPinned(int level) {
		PlatformGenerator generator = generator(level);
		GeneratedScene scene = generator.scene(SceneSeeds.derive(42, 3, 1), true);
		StringBuilder output = new StringBuilder(scene.plan().layoutSha256(ArenaLayout.of(config(level).arena()))).append('\n');
		output.append(scene.layoutParameters()).append('\n');
		for (int attempt = 0; attempt < 4; attempt++) {
			output.append(generator.viewpoint(scene, 4, attempt)).append('\n');
		}

		assertEquals(level == 2 ? PINNED_LEVEL2 : PINNED_LEVEL3, output.toString());
	}

	private static final String PINNED_LEVEL2 = """
		cc6a1caf68cdb186d746d929861163c98e07b5c317394282da180faa0eae642e
		{"items":11,"piles":7,"distractor_items":3,"blocks":32}
		Rejected[reason=target_occluded]
		Placeable[plan=ViewpointPlan[x=21.315094847510327, y=1.0, z=2.9581016943713916, yawDegrees=1.5391595363616943, pitchDegrees=-4.580810070037842]]
		Rejected[reason=camera_off_platform]
		Placeable[plan=ViewpointPlan[x=14.651677058250801, y=1.0, z=4.557568112495245, yawDegrees=-36.407081604003906, pitchDegrees=-4.966007232666016]]
		""";
	private static final String PINNED_LEVEL3 = """
		64b49c945a6b7182195ecd98634c3aa26082702e7a15f9994d685b0193572aeb
		{"structure":"recess","structure_side":3,"structure_material":"minecraft:granite","structure_lintel":true,"background_structures":5,"scattered_blocks":5,"blocks":51}
		Rejected[reason=camera_off_platform]
		Rejected[reason=camera_off_platform]
		Placeable[plan=ViewpointPlan[x=12.855519934202732, y=1.0, z=3.827440134970516, yawDegrees=63.91829299926758, pitchDegrees=7.087850093841553]]
		Rejected[reason=target_not_partially_hidden]
		""";
}
