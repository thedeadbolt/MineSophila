package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The recipe generator with each level preset: target, layout, lighting and viewpoints. */
class RecipeGeneratorTest {
	private static final int SCENES = 60;

	private static ResolvedConfig config(int level) {
		return ConfigResolver.resolve("""
			{"dataset": {"name": "level%d-test", "seed": 42, "difficulty": %d}, "output": {"root": "/tmp/honey"}}
			""".formatted(level, level));
	}

	private static RecipeGenerator generator(int level) {
		return (RecipeGenerator) SceneGenerators.forConfig(config(level));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4})
	void eachLevelRunsItsPresetOnTheSharedPlatform(int level) {
		RecipeGenerator generator = generator(level);
		assertEquals(Algorithms.SCENE_RECIPE, generator.version());
		assertEquals(ScenePresets.spec("level" + level), generator.spec());
		JsonObject recorded = config(level).toJson().getAsJsonObject("generator");
		assertEquals(Algorithms.SCENE_RECIPE, recorded.getAsJsonObject("algorithms").get("scene_generator").getAsString());
		assertEquals(generator.spec().toJson(), recorded.getAsJsonObject("scene"));
		assertEquals(generator(1).template(), generator.template());
		assertEquals(RecipeGenerator.TEMPLATE_ID, generator.template().id());
		assertEquals(32 * 32, generator.template().blocks().size());
	}

	@Test
	void presentScenesPlaceOneTargetOnTheFloor() {
		RecipeGenerator generator = generator(1);
		for (int index = 0; index < 50; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), true);
			Placement target = scene.plan().target();
			assertNotNull(target);
			assertEquals(List.of(target), scene.plan().delta());
			assertEquals(1, target.y());
			assertTrue(target.x() >= 0 && target.x() < 32 && target.z() >= 0 && target.z() < 32);
			assertEquals("minecraft:honey_block", target.state());
			assertArrayEquals(new int[] {target.x(), 1, target.z()}, scene.aimBlock());
			assertEquals(6000, scene.plan().timeOfDayTicks(), "level 1 keeps fixed noon lighting");
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4})
	void bothClassesShareTheLayoutAroundTheAimCell(int level) {
		RecipeGenerator generator = generator(level);
		for (int index = 0; index < SCENES; index++) {
			SceneSeeds seeds = SceneSeeds.derive(42, index, 0);
			GeneratedScene present = generator.scene(seeds, true);
			GeneratedScene absent = generator.scene(seeds, false);
			assertEquals(present.plan(), generator.scene(seeds, true).plan(), "plans are deterministic");
			assertArrayEquals(present.aimBlock(), absent.aimBlock());
			assertNull(absent.plan().target());
			Placement target = present.plan().target();
			assertNotNull(target);
			assertEquals(target, present.plan().delta().getLast());
			assertEquals(present.plan().delta().subList(0, present.plan().delta().size() - 1), absent.plan().delta());
			assertEquals(present.layoutParameters(), absent.layoutParameters());
			assertEquals(absent.plan().delta().size(), present.layoutParameters().get("blocks").getAsInt());
			assertEquals(generator.viewpoint(present, 2, 5), generator.viewpoint(absent, 2, 5));
		}
	}

	@Test
	void viewpointsFollowTheConfiguredDistanceAndJitter() {
		RecipeGenerator generator = generator(1);
		GeneratedScene scene = generator.scene(SceneSeeds.derive(42, 0, 0), true);
		int[] aim = scene.aimBlock();
		Set<String> rejections = new HashSet<>();
		int accepted = 0;
		for (int viewpoint = 0; viewpoint < 8; viewpoint++) {
			for (int attempt = 0; attempt < 50; attempt++) {
				ViewpointCandidate candidate = generator.viewpoint(scene, viewpoint, attempt);
				assertEquals(candidate, generator.viewpoint(scene, viewpoint, attempt), "draws are deterministic");
				if (candidate instanceof ViewpointCandidate.Rejected rejected) {
					rejections.add(rejected.reason());
					continue;
				}

				accepted++;
				ViewpointPlan plan = ((ViewpointCandidate.Placeable) candidate).plan();
				double distance = Math.hypot(aim[0] + 0.5 - plan.x(), aim[2] + 0.5 - plan.z());
				assertTrue(distance >= 3.0 && distance <= 16.0, "distance " + distance);
				assertEquals(1.0, plan.y());
				assertTrue(plan.x() >= 0.3 && plan.x() <= 31.7 && plan.z() >= 0.3 && plan.z() <= 31.7);
				ViewpointPlan aimed = ViewpointPlan.lookingAt(plan.x(), plan.y(), plan.z(), 1.62, aim[0], aim[1], aim[2]);
				assertTrue(Math.abs(ViewpointPlan.wrapDegrees(plan.yawDegrees() - aimed.yawDegrees())) <= 15.0 + 1e-4);
				assertTrue(Math.abs(plan.pitchDegrees() - aimed.pitchDegrees()) <= 10.0 + 1e-4);
				assertEquals(plan.yawDegrees(), (float) plan.yawDegrees(), "angles are stored at float precision");
			}
		}

		assertTrue(accepted > 0);
		assertTrue(Set.of("camera_off_platform", "camera_overlaps_target").containsAll(rejections));
	}

	@Test
	void clutterKeepsClearOfTheTarget() {
		RecipeGenerator generator = generator(2);
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), false);
			assertTrue(scene.plan().delta().size() >= 6, "level 2 places clutter");
			for (Placement block : scene.plan().delta()) {
				assertTrue(chebyshev(block, scene.aimBlock()) >= ClutterPart.DEFAULT.clearance(), "clutter beside the target: " + block);
				assertTrue(block.y() <= 2, "piles are at most two blocks tall");
				assertTrue(BlockPalettes.DISTRACTORS.contains(block.state()) || BlockPalettes.CONCRETE.contains(block.state()) || BlockPalettes.TEXTURED.contains(block.state()));
			}
		}
	}

	@Test
	void level3BuildsAHidingStructureAgainstTheTarget() {
		RecipeGenerator generator = generator(3);
		Set<String> kinds = new HashSet<>();
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), false);
			JsonObject hiding = scene.layoutParameters().getAsJsonArray("parts").get(0).getAsJsonObject();
			kinds.add(hiding.get("structure").getAsString());
			String material = hiding.get("structure_material").getAsString();
			boolean hidden = false;
			for (Placement block : scene.plan().delta()) {
				int distance = chebyshev(block, scene.aimBlock());
				hidden |= distance <= 1;
				assertTrue(distance >= BackgroundStructuresPart.DEFAULT.clearance() || block.state().equals(material), "only the hiding structure comes close: " + block);
			}

			int[] aim = scene.aimBlock();
			assertTrue(hidden || aim[0] == 0 || aim[2] == 0 || aim[0] == 31 || aim[2] == 31, "scene " + index + " has no hiding structure");
		}

		assertEquals(Set.copyOf(HidingStructurePart.KINDS), kinds);
	}

	@Test
	void level4UsesShapedBlocksAndVegetationInCanonicalForm() {
		RecipeGenerator generator = generator(4);
		Set<String> settings = new HashSet<>();
		Map<String, Integer> kinds = new TreeMap<>();
		int connected = 0;
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), false);
			settings.add(scene.layoutParameters().getAsJsonArray("parts").get(0).getAsJsonObject().get("setting").getAsString());
			ScenePlan plan = scene.plan();
			Set<List<Integer>> cells = new HashSet<>();
			for (Placement block : plan.delta()) {
				assertTrue(cells.add(List.of(block.x(), block.y(), block.z())), "one placement per cell: " + block);
				assertTrue(block.y() >= 1 || BlockPalettes.GROUND.contains(block.state()), "only ground blocks replace the floor: " + block);
				assertEquals(block.state(), canonical(block.state()), "canonical state");
				kinds.merge(kind(block.state()), 1, Integer::sum);
				if (BlockPalettes.PLANTS.contains(block.state())) {
					assertTrue(BlockPalettes.GROUND.contains(stateAt(plan, block.x(), block.y() - 1, block.z())), "a plant needs ground below it: " + block);
				}

				if (block.state().contains("_fence[")) {
					int[][] sides = {{1, 0}, {0, -1}, {0, 1}, {-1, 0}};
					String[] names = {"east", "north", "south", "west"};
					for (int i = 0; i < 4; i++) {
						String neighbour = stateAt(plan, block.x() + sides[i][0], block.y(), block.z() + sides[i][1]);
						boolean expected = neighbour != null && (neighbour.contains("_fence[") || BlockShapes.fullCube(neighbour));
						assertEquals(expected, block.state().contains(names[i] + "=true"), block + " beside " + neighbour);
						connected += expected ? 1 : 0;
					}
				}
			}
		}

		assertEquals(Set.copyOf(ConstructedSettingPart.KINDS), settings, "every setting appears");
		for (String kind : List.of("full", "slab", "stairs", "fence", "leaves", "plant")) {
			assertTrue(kinds.getOrDefault(kind, 0) > 0, "no " + kind + " blocks in " + kinds);
		}

		assertTrue(connected > 0, "some fences connect");
	}

	/** Every kept viewpoint satisfies the preset's sight rule, and almost every viewpoint finds one. */
	@ParameterizedTest
	@ValueSource(ints = {2, 3, 4})
	void keptViewpointsSuitTheSightRule(int level) {
		RecipeGenerator generator = generator(level);
		ResolvedConfig.Viewpoints settings = config(level).viewpoints();
		Map<String, Integer> rejections = new TreeMap<>();
		int exhausted = 0;
		for (int index = 0; index < SCENES; index++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(42, index, 0), true);
			SolidGrid grid = SolidGrid.of(scene.plan());
			for (int viewpoint = 0; viewpoint < settings.count(); viewpoint++) {
				boolean found = false;
				for (int attempt = 0; attempt < settings.attemptsPerViewpoint(); attempt++) {
					ViewpointCandidate candidate = generator.viewpoint(scene, viewpoint, attempt);
					if (candidate instanceof ViewpointCandidate.Rejected rejected) {
						rejections.merge(rejected.reason(), 1, Integer::sum);
						continue;
					}

					ViewpointPlan plan = ((ViewpointCandidate.Placeable) candidate).plan();
					double[] eye = {plan.x(), plan.y() + settings.eyeHeightBlocks(), plan.z()};
					assertNull(generator.spec().sight().check(grid, eye, scene.aimBlock()), "kept a view the sight rule rejects");
					found = true;
				}

				exhausted += found ? 0 : 1;
			}
		}

		System.out.printf("level %d: rejections %s, %d of %d viewpoints exhausted%n", level, rejections, exhausted, SCENES * settings.count());
		assertTrue(exhausted <= SCENES * settings.count() / 10, exhausted + " viewpoints found no placeable draw");
	}

	/** Each part draws from its own stream: changing one part leaves the others' draws alone. */
	@Test
	void editingOnePartLeavesTheOthersAlone() {
		ResolvedConfig config = config(3);
		SceneSpec edited = config.scene().toRecipe()
			.parts(List.of(HidingStructurePart.DEFAULT, new BackgroundStructuresPart(IntRange.of(0), 3), ScatteredBlocksPart.DEFAULT))
			.build();
		RecipeGenerator original = generator(3);
		RecipeGenerator changed = new RecipeGenerator(edited, config.arena(), config.target().allowedBlockStates(), config.viewpoints());
		for (int index = 0; index < 20; index++) {
			SceneSeeds seeds = SceneSeeds.derive(42, index, 0);
			JsonObject before = original.scene(seeds, false).layoutParameters();
			JsonObject after = changed.scene(seeds, false).layoutParameters();
			assertEquals(before.getAsJsonArray("parts").get(0), after.getAsJsonArray("parts").get(0), "the hiding structure is unchanged");
			assertEquals(0, after.getAsJsonArray("parts").get(1).getAsJsonObject().get("structures").getAsInt());
			assertEquals(before.getAsJsonArray("parts").get(2).getAsJsonObject().get("blocks"), after.getAsJsonArray("parts").get(2).getAsJsonObject().get("blocks"),
				"the scattered blocks draw the same count");
		}
	}

	@Test
	void varyingLightingIsDrawnPerScene() {
		ResolvedConfig config = config(2);
		SceneSpec spec = config.scene().toRecipe().lighting(Lighting.of(new IntRange(1000, 11000), io.github.thedeadbolt.honeyfactory.contract.Weather.CLEAR,
			io.github.thedeadbolt.honeyfactory.contract.Weather.RAIN)).build();
		RecipeGenerator generator = new RecipeGenerator(spec, config.arena(), config.target().allowedBlockStates(), config.viewpoints());
		Set<Integer> times = new HashSet<>();
		Set<Object> weathers = new HashSet<>();
		for (int index = 0; index < 40; index++) {
			SceneSeeds seeds = SceneSeeds.derive(42, index, 0);
			ScenePlan plan = generator.scene(seeds, true).plan();
			assertTrue(plan.timeOfDayTicks() >= 1000 && plan.timeOfDayTicks() <= 11000);
			times.add(plan.timeOfDayTicks());
			weathers.add(plan.weather());
			assertEquals(generator(2).scene(seeds, true).plan().delta(), plan.delta(), "lighting never shifts the layout");
		}

		assertTrue(times.size() > 30);
		assertEquals(2, weathers.size());
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

	/** Pins each preset's output so an accidental algorithm change cannot pass silently. */
	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4})
	void outputIsPinned(int level) {
		RecipeGenerator generator = generator(level);
		GeneratedScene scene = generator.scene(SceneSeeds.derive(42, 3, 1), true);
		StringBuilder output = new StringBuilder(scene.plan().layoutSha256(ArenaLayout.of(config(level).arena()))).append('\n');
		output.append(scene.layoutParameters()).append('\n');
		for (int attempt = 0; attempt < 4; attempt++) {
			output.append(generator.viewpoint(scene, 4, attempt)).append('\n');
		}

		assertEquals(PINNED.get(level), output.toString());
	}

	private static final Map<Integer, String> PINNED = Map.of(
		1, """
			070ced1d14aa9bc088c092c43d33dfdba647a98c4f39fb6d8bcdd4dd3f78b928
			{"preset":"level1","parts":[],"blocks":0}
			Placeable[plan=ViewpointPlan[x=17.859858300595068, y=1.0, z=23.200827244823717, yawDegrees=-20.89807891845703, pitchDegrees=5.538839340209961]]
			Placeable[plan=ViewpointPlan[x=22.489703751103704, y=1.0, z=26.047805470419448, yawDegrees=51.82304763793945, pitchDegrees=6.041507720947266]]
			Placeable[plan=ViewpointPlan[x=17.000432405890244, y=1.0, z=31.54896375502363, yawDegrees=-142.2740478515625, pitchDegrees=9.12411880493164]]
			Rejected[reason=camera_off_platform]
			""",
		2, """
			cf69c1ee6cbe7ace572a386d4b3330184d632e1df3fde39ff4531327d8f6e30b
			{"preset":"level2","parts":[{"type":"clutter","items":6,"piles":1,"distractor_items":1}],"blocks":8}
			Placeable[plan=ViewpointPlan[x=17.859858300595068, y=1.0, z=23.200827244823717, yawDegrees=-20.89807891845703, pitchDegrees=5.538839340209961]]
			Placeable[plan=ViewpointPlan[x=22.489703751103704, y=1.0, z=26.047805470419448, yawDegrees=51.82304763793945, pitchDegrees=6.041507720947266]]
			Placeable[plan=ViewpointPlan[x=17.000432405890244, y=1.0, z=31.54896375502363, yawDegrees=-142.2740478515625, pitchDegrees=9.12411880493164]]
			Rejected[reason=camera_off_platform]
			""",
		3, """
			4c4686c2b5c2dd047f51a2b2ded14e7ef3bb04ae4d2a7cc3de87b5405da13179
			{"preset":"level3","parts":[{"type":"hiding_structure","structure":"corner","structure_side":3,"structure_material":"minecraft:andesite","structure_height":1},{"type":"background_structures","structures":6},{"type":"scattered_blocks","blocks":6}],"blocks":29}
			Placeable[plan=ViewpointPlan[x=17.859858300595068, y=1.0, z=23.200827244823717, yawDegrees=-20.89807891845703, pitchDegrees=5.538839340209961]]
			Placeable[plan=ViewpointPlan[x=22.489703751103704, y=1.0, z=26.047805470419448, yawDegrees=51.82304763793945, pitchDegrees=6.041507720947266]]
			Rejected[reason=target_not_partially_hidden]
			Rejected[reason=camera_off_platform]
			""",
		4, """
			4adb8aec3563e89b72d2816e4f1cf45ca315a9edeef5eb7666924f6d3deddeed
			{"preset":"level4","parts":[{"type":"constructed_setting","setting":"terraces","material":"minecraft:deepslate_bricks","terrace_side":0,"steps":3,"step_depth":1},{"type":"near_aim_blocks","material":"minecraft:sandstone","blocks":3},{"type":"scattered_items","material":"minecraft:mossy_stone_bricks","items":4}],"blocks":38}
			Placeable[plan=ViewpointPlan[x=17.859858300595068, y=1.0, z=23.200827244823717, yawDegrees=-20.89807891845703, pitchDegrees=5.538839340209961]]
			Rejected[reason=target_hidden]
			Placeable[plan=ViewpointPlan[x=17.000432405890244, y=1.0, z=31.54896375502363, yawDegrees=-142.2740478515625, pitchDegrees=9.12411880493164]]
			Rejected[reason=camera_off_platform]
			"""
	);

	private static int chebyshev(Placement block, int[] aim) {
		return Math.max(Math.abs(block.x() - aim[0]), Math.abs(block.z() - aim[2]));
	}

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
