package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.BlockLayout;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Level 4: constructed settings of shaped blocks and vegetation around the aim cell. */
class Level4GeneratorTest {
	private static final int SCENES = 60;
	private static final ResolvedConfig CONFIG = ConfigResolver.resolve("""
		{"dataset": {"name": "level4-test", "seed": 42, "difficulty": 4}, "output": {"root": "/tmp/honey"}}
		""");
	private static final Level4Generator GENERATOR = (Level4Generator) SceneGenerators.forConfig(CONFIG);

	@Test
	void recordsItsVersionAndSharesTheLevel1Platform() {
		assertEquals(Algorithms.LEVEL4_GENERATOR, GENERATOR.version());
		assertEquals(Algorithms.LEVEL4_GENERATOR, CONFIG.toJson().getAsJsonObject("generator").getAsJsonObject("algorithms").get("scene_generator").getAsString());
		assertEquals(new Level1Generator(ConfigResolver.resolve("""
			{"dataset": {"name": "level1-test", "seed": 42, "difficulty": 1}, "output": {"root": "/tmp/honey"}}
			""")).template(), GENERATOR.template());
	}

	@Test
	void bothClassesShareTheLayoutAroundTheAimCell() {
		for (int index = 0; index < SCENES; index++) {
			SceneSeeds seeds = SceneSeeds.derive(42, index, 0);
			GeneratedScene present = GENERATOR.scene(seeds, true);
			GeneratedScene absent = GENERATOR.scene(seeds, false);
			assertEquals(present.plan(), GENERATOR.scene(seeds, true).plan(), "plans are deterministic");
			assertArrayEquals(present.aimBlock(), absent.aimBlock());
			assertNull(absent.plan().target());
			Placement target = present.plan().target();
			assertNotNull(target);
			assertEquals(target, present.plan().delta().getLast());
			assertEquals(present.plan().delta().subList(0, present.plan().delta().size() - 1), absent.plan().delta());
			assertEquals(present.layoutParameters(), absent.layoutParameters());
			assertEquals(absent.plan().delta().size(), present.layoutParameters().get("blocks").getAsInt());
		}
	}

	@Test
	void layoutsUseTheLevel4PaletteInCanonicalForm() {
		Set<String> settings = new HashSet<>();
		Map<String, Integer> kinds = new TreeMap<>();
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = GENERATOR.scene(SceneSeeds.derive(42, index, 0), false);
			settings.add(scene.layoutParameters().get("setting").getAsString());
			Set<List<Integer>> cells = new HashSet<>();
			for (Placement block : scene.plan().delta()) {
				assertTrue(cells.add(List.of(block.x(), block.y(), block.z())), "one placement per cell: " + block);
				assertTrue(block.y() >= 1 || BlockPalettes.GROUND.contains(block.state()), "only ground blocks replace the floor: " + block);
				assertEquals(block.state(), canonical(block.state()), "canonical state");
				kinds.merge(kind(block.state()), 1, Integer::sum);
				if (BlockPalettes.PLANTS.contains(block.state())) {
					String below = stateAt(scene.plan(), block.x(), block.y() - 1, block.z());
					assertTrue(BlockPalettes.GROUND.contains(below), "a plant needs ground below it: " + block + " on " + below);
				}
			}
		}

		assertEquals(Set.of(Level4Generator.KINDS), settings, "every setting appears");
		for (String kind : List.of("full", "slab", "stairs", "fence", "leaves", "plant")) {
			assertTrue(kinds.getOrDefault(kind, 0) > 0, "no " + kind + " blocks in " + kinds);
		}
	}

	@Test
	void fencesConnectToFencesAndFullBlocksOnly() {
		int connected = 0;
		for (int index = 0; index < SCENES; index++) {
			ScenePlan plan = GENERATOR.scene(SceneSeeds.derive(42, index, 0), false).plan();
			for (Placement block : plan.delta()) {
				if (!block.state().contains("_fence[")) {
					continue;
				}

				int[][] sides = {{1, 0}, {0, -1}, {0, 1}, {-1, 0}};
				String[] names = {"east", "north", "south", "west"};
				for (int i = 0; i < 4; i++) {
					String neighbour = stateAt(plan, block.x() + sides[i][0], block.y(), block.z() + sides[i][1]);
					boolean expected = neighbour != null && block.y() >= 1 && (neighbour.contains("_fence[") || BlockShapes.fullCube(neighbour));
					assertEquals(expected, block.state().contains(names[i] + "=true"), block + " beside " + neighbour);
					connected += expected ? 1 : 0;
				}
			}
		}

		assertTrue(connected > 0, "some fences connect");
	}

	/** Every kept viewpoint has a clear estimated sight line and one past a block, and most viewpoints find one. */
	@Test
	void keptViewpointsSeeTheAimCell() {
		ResolvedConfig.Viewpoints settings = CONFIG.viewpoints();
		Map<String, Integer> rejections = new TreeMap<>();
		Map<String, int[]> exhaustedBySetting = new HashMap<>();
		int exhausted = 0;
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = GENERATOR.scene(SceneSeeds.derive(42, index, 0), true);
			SolidGrid grid = SolidGrid.of(scene.plan());
			String setting = scene.layoutParameters().get("setting").getAsString();
			for (int viewpoint = 0; viewpoint < settings.count(); viewpoint++) {
				boolean found = false;
				for (int attempt = 0; attempt < settings.attemptsPerViewpoint(); attempt++) {
					ViewpointCandidate candidate = GENERATOR.viewpoint(scene, viewpoint, attempt);
					if (candidate instanceof ViewpointCandidate.Rejected rejected) {
						rejections.merge(rejected.reason(), 1, Integer::sum);
						continue;
					}

					ViewpointPlan plan = ((ViewpointCandidate.Placeable) candidate).plan();
					SightLines.Estimate estimate = SightLines.estimate(grid, new double[] {plan.x(), plan.y() + settings.eyeHeightBlocks(), plan.z()}, scene.aimBlock());
					assertTrue(estimate.clear() > 0.0 && estimate.open() < 1.0, "kept a view with " + estimate);
					found = true;
				}

				exhaustedBySetting.computeIfAbsent(setting, key -> new int[2])[found ? 0 : 1]++;
				exhausted += found ? 0 : 1;
			}
		}

		Map<String, String> bySetting = new TreeMap<>();
		exhaustedBySetting.forEach((setting, counts) -> bySetting.put(setting, counts[1] + "/" + (counts[0] + counts[1])));
		System.out.printf("level 4: rejections %s, exhausted viewpoints by setting %s%n", rejections, bySetting);
		assertTrue(exhausted <= SCENES * settings.count() / 10, exhausted + " viewpoints found no placeable draw");
	}

	@Test
	void outputIsPinned() {
		GeneratedScene scene = GENERATOR.scene(SceneSeeds.derive(42, 3, 1), true);
		StringBuilder output = new StringBuilder(scene.plan().layoutSha256(ArenaLayout.of(CONFIG.arena()))).append('\n');
		output.append(scene.layoutParameters()).append('\n');
		for (int attempt = 0; attempt < 4; attempt++) {
			output.append(GENERATOR.viewpoint(scene, 4, attempt)).append('\n');
		}

		assertEquals(PINNED, output.toString());
	}

	private static final String PINNED = """
		9da4a1a1e635c1871c4991596060f43ed15762a5f7fa28c64c5cf5b042e86865
		{"setting":"terraces","material":"minecraft:mossy_cobblestone","terrace_side":3,"steps":3,"step_depth":2,"near_aim_blocks":1,"scattered_items":5,"blocks":95}
		Rejected[reason=target_hidden]
		Rejected[reason=camera_off_platform]
		Placeable[plan=ViewpointPlan[x=30.685342088313398, y=1.0, z=18.018342492715984, yawDegrees=106.62036895751953, pitchDegrees=19.624162673950195]]
		Rejected[reason=target_unobstructed]
		""";

	private static String canonical(String state) {
		int open = state.indexOf('[');
		if (open < 0) {
			return state;
		}

		Map<String, String> properties = new HashMap<>();
		for (String entry : state.substring(open + 1, state.length() - 1).split(",")) {
			String[] parts = entry.split("=");
			properties.put(parts[0], parts[1]);
		}

		return BlockLayout.canonicalState(state.substring(0, open), properties);
	}

	private static String kind(String state) {
		String id = BlockShapes.blockId(state);
		if (id.endsWith("_slab")) {
			return "slab";
		}

		if (id.endsWith("_stairs")) {
			return "stairs";
		}

		if (id.endsWith("_fence")) {
			return "fence";
		}

		if (id.endsWith("_leaves")) {
			return "leaves";
		}

		return BlockPalettes.PLANTS.contains(state) ? "plant" : "full";
	}

	/** The state at a cell after the template and delta, or null for air. */
	private static String stateAt(ScenePlan plan, int x, int y, int z) {
		String state = null;
		List<Placement> all = new ArrayList<>(plan.template().blocks());
		all.addAll(plan.delta());
		for (Placement placement : all) {
			if (placement.x() == x && placement.y() == y && placement.z() == z) {
				state = placement.state();
			}
		}

		return state;
	}
}
