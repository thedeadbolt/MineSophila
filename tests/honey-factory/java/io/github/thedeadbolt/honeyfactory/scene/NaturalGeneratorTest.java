package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Level 5 without Minecraft: candidate places, the vertical window, target sites and viewpoints on synthetic terrain. */
class NaturalGeneratorTest {
	private static final SceneBase.NaturalTerrain BASE = SceneBase.NaturalTerrain.DEFAULT;
	private static final ResolvedConfig CONFIG = ConfigResolver.resolve("""
		{"dataset": {"name": "level5-test", "seed": 42, "difficulty": 5}, "output": {"root": "/tmp/honey"}}
		""");

	@Test
	void candidatesTakeDistinctCellsAndStaySeparated() {
		Set<List<Integer>> corners = new HashSet<>();
		List<int[]> all = new java.util.ArrayList<>();
		for (int patch = 0; patch < 40; patch++) {
			for (int draw = 0; draw < SourcePatches.DRAWS; draw++) {
				int[] corner = SourcePatches.corner(42, BASE, patch, draw);
				assertEquals(0, Math.floorMod(corner[0], 16), "chunk aligned");
				assertEquals(0, Math.floorMod(corner[1], 16), "chunk aligned");
				assertTrue(corners.add(List.of(corner[0], corner[1])), "distinct cell");
				all.add(corner);
			}
		}

		for (int a = 0; a < 300; a++) {
			for (int b = a + 1; b < 300; b++) {
				int gapX = Math.max(all.get(b)[0] - (all.get(a)[0] + BASE.copyWidth()), all.get(a)[0] - (all.get(b)[0] + BASE.copyWidth()));
				int gapZ = Math.max(all.get(b)[1] - (all.get(a)[1] + BASE.copyLength()), all.get(a)[1] - (all.get(b)[1] + BASE.copyLength()));
				assertTrue(Math.max(gapX, gapZ) >= BASE.regionSeparationBlocks(), "candidates at least the separation apart");
			}
		}

		assertArrayEquals(SourcePatches.corner(42, BASE, 3, 1), SourcePatches.corner(42, BASE, 3, 1));
		assertTrue(!java.util.Arrays.equals(SourcePatches.corner(42, BASE, 0, 0), SourcePatches.corner(43, BASE, 0, 0)), "the dataset seed moves the grid");
		assertThrows(IllegalArgumentException.class, () -> SourcePatches.corner(42, BASE, 0, SourcePatches.DRAWS));
		assertArrayEquals(new int[] {-104704, 103616}, SourcePatches.corner(42, BASE, 0, 0), "pinned; also computed independently from the written algorithm");
	}

	@Test
	void theWindowEndsOnASectionAboveTheHighestBlock() {
		assertEquals(96 - 128, SourcePatches.window(80, 128, -64, 319), "top at 96: 80 + 1 + 8 rounded up to 16");
		assertEquals(112 - 128, SourcePatches.window(88, 128, -64, 319));
		assertEquals(320 - 128, SourcePatches.window(315, 128, -64, 319), "lowered to the build limit");
		assertEquals(-64, SourcePatches.window(-60, 128, -64, 319), "raised to the world bottom");
	}

	@Test
	void targetSitesStandOnSturdyDryGround() {
		PatchTerrain terrain = terrain((x, z) -> x == 0 ? 3 : 10, (x, y, z) -> {
			if (x == 1 && z == 1 && y == 9) {
				return PatchTerrain.OCCUPIED | PatchTerrain.FLUID | PatchTerrain.REPLACEABLE;
			}

			if (x == 2 && z == 2 && y == 9) {
				return PatchTerrain.OCCUPIED | PatchTerrain.COLLIDES | PatchTerrain.STURDY_TOP | PatchTerrain.LEAVES;
			}

			return y < (x == 0 ? 3 : 10) ? PatchTerrain.OCCUPIED | PatchTerrain.OCCLUDES | PatchTerrain.COLLIDES | PatchTerrain.STURDY_TOP : PatchTerrain.REPLACEABLE;
		});
		List<int[]> sites = terrain.targetSites();
		assertEquals(32 * 32 - 2, sites.size(), "water and leaves are no ground");
		assertArrayEquals(new int[] {0, 3, 0}, sites.getFirst());
		assertTrue(sites.stream().noneMatch(site -> site[0] == 1 && site[2] == 1));
	}

	@Test
	void scenesStandOnTheTerrainAndViewpointsKeepToTheCore() {
		NaturalGenerator generator = (NaturalGenerator) SceneGenerators.forConfig(CONFIG);
		PreparedPatch prepared = new PreparedPatch(patch(), flatWithPillars());
		Map<String, Integer> rejections = new TreeMap<>();
		int kept = 0;
		for (int index = 0; index < 20; index++) {
			SceneSeeds seeds = SceneSeeds.derive(42, index, 0);
			GeneratedScene present = generator.scene(seeds, true, prepared);
			GeneratedScene absent = generator.scene(seeds, false, prepared);
			assertArrayEquals(present.aimBlock(), absent.aimBlock());
			assertNull(absent.plan().target());
			Placement target = present.plan().target();
			assertNotNull(target);
			assertEquals(List.of(target), present.plan().delta());
			assertEquals(10, target.y(), "on the surface");
			assertEquals(prepared.patch(), present.plan().patch());
			assertTrue(present.plan().timeOfDayTicks() >= 1000 && present.plan().timeOfDayTicks() <= 11000);
			for (int attempt = 0; attempt < 20; attempt++) {
				ViewpointCandidate candidate = generator.viewpoint(present, 0, attempt);
				if (candidate instanceof ViewpointCandidate.Rejected rejected) {
					rejections.merge(rejected.reason(), 1, Integer::sum);
					continue;
				}

				ViewpointPlan plan = ((ViewpointCandidate.Placeable) candidate).plan();
				assertEquals(10.0, plan.y(), "feet on the surface");
				assertTrue(plan.x() >= 0.3 && plan.x() <= 31.7 && plan.z() >= 0.3 && plan.z() <= 31.7);
				kept++;
			}
		}

		assertTrue(kept > 50, "kept " + kept);
		assertTrue(rejections.containsKey("camera_outside_core"), rejections.toString());
		assertThrows(UnsupportedOperationException.class, () -> generator.scene(SceneSeeds.derive(42, 0, 0), true));
		ViewpointPlan plan = new ViewpointPlan(1.5, 10, 2.5, 0, 0);
		assertEquals(ArenaLayout.NATURAL_ORIGIN[0] + 1.5, plan.pose(ArenaLayout.natural(BASE)).x(), "poses are relative to the natural core");
	}

	@Test
	void outputIsPinned() {
		NaturalGenerator generator = (NaturalGenerator) SceneGenerators.forConfig(CONFIG);
		GeneratedScene scene = generator.scene(SceneSeeds.derive(42, 3, 1), true, new PreparedPatch(patch(), flatWithPillars()));
		StringBuilder output = new StringBuilder(java.util.Arrays.toString(scene.aimBlock())).append(' ').append(scene.plan().timeOfDayTicks()).append(' ').append(scene.plan().weather()).append('\n');
		output.append(scene.layoutParameters()).append('\n');
		for (int attempt = 0; attempt < 4; attempt++) {
			output.append(generator.viewpoint(scene, 2, attempt)).append('\n');
		}

		assertEquals(PINNED, output.toString());
	}

	private static final String PINNED = """
		[18, 10, 30] 9207 CLEAR
		{"preset":"level5","patch":{"index":0,"draw":2,"patch_id":"9/minecraft:overworld/0,0,0/415,127,415/minecraft-test","rejected_draws":{"patch_void_below":2}},"target_sites":1008,"parts":[],"blocks":0}
		Placeable[plan=ViewpointPlan[x=15.383342020851082, y=10.0, z=30.186442537525302, yawDegrees=-88.92731475830078, pitchDegrees=17.777307510375977]]
		Rejected[reason=camera_outside_core]
		Rejected[reason=camera_outside_core]
		Placeable[plan=ViewpointPlan[x=20.584864734385047, y=10.0, z=24.270556627648872, yawDegrees=5.0322699546813965, pitchDegrees=17.515819549560547]]
		""";

	private static SourcePatch patch() {
		int[] min = {0, 0, 0};
		int[] max = {BASE.copyWidth() - 1, BASE.height() - 1, BASE.copyLength() - 1};
		return new SourcePatch(SourcePatch.id(9, "minecraft:overworld", min, max, "minecraft-test"), 9, "minecraft:overworld", min, max, "minecraft-test", 0, 2,
			"0".repeat(64), Map.of("patch_void_below", 2));
	}

	/** Flat ground with its surface at y = 10 and one-block stone pillars two blocks tall every eighth cell. */
	private static PatchTerrain flatWithPillars() {
		return terrain((x, z) -> 10, (x, y, z) -> {
			boolean pillar = x % 8 == 4 && z % 8 == 4 && y < 12;
			return y < 10 || pillar ? PatchTerrain.OCCUPIED | PatchTerrain.OCCLUDES | PatchTerrain.COLLIDES | PatchTerrain.STURDY_TOP : PatchTerrain.REPLACEABLE;
		});
	}

	private interface Cell {
		int flags(int x, int y, int z);
	}

	private interface Column {
		int surface(int x, int z);
	}

	private static PatchTerrain terrain(Column column, Cell cell) {
		int width = BASE.coreWidth();
		int height = BASE.height();
		int length = BASE.coreLength();
		byte[] flags = new byte[width * height * length];
		int[] surface = new int[width * length];
		for (int z = 0; z < length; z++) {
			for (int x = 0; x < width; x++) {
				surface[z * width + x] = column.surface(x, z);
				for (int y = 0; y < height; y++) {
					flags[(y * length + z) * width + x] = (byte) cell.flags(x, y, z);
				}
			}
		}

		return new PatchTerrain(width, height, length, flags, surface);
	}
}
