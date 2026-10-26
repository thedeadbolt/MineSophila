package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Paths over what the teacher has seen: around seen walls, up single steps, never through two. */
class PathPlannerTest {
	private static final CaptureProjection PANORAMA = CaptureProjection.of(CaptureMode.PANORAMA_310, 248, 128, 64);

	/** Everything a body at {@code feet} could see, looking both ways. */
	private static SeenMap seenFrom(Grids grid, double x, double z) {
		SeenMap seen = new SeenMap(grid.core);
		seen.observe(new CameraPose(x, 1.0, z, 0.0f, 0.0f), 1.62, PANORAMA, grid);
		seen.observe(new CameraPose(x, 1.0, z, 180.0f, 0.0f), 1.62, PANORAMA, grid);
		return seen;
	}

	private static void assertConnected(List<int[]> path) {
		for (int i = 1; i < path.size(); i++) {
			int[] a = path.get(i - 1);
			int[] b = path.get(i);
			assertTrue(Math.abs(a[0] - b[0]) <= 1 && Math.abs(a[2] - b[2]) <= 1, "steps join neighbours");
		}
	}

	@Test
	void goesAroundAWall() {
		Grids grid = new Grids(21, 6, 21);
		for (int x = 4; x <= 16; x++) {
			grid.wall(x, 10, 3);
		}

		SeenMap seen = seenFrom(grid, 10.5, 4.5);
		WalkGraph graph = WalkGraph.of(grid, grid.core, seen);
		List<int[]> path = PathPlanner.path(graph, new int[] {10, 1, 4}, new int[] {10, 1, 16});
		assertNotNull(path);
		assertArrayEquals(new int[] {10, 1, 4}, path.getFirst());
		assertArrayEquals(new int[] {10, 1, 16}, path.getLast());
		assertConnected(path);
		for (int[] cell : path) {
			assertFalse(cell[2] == 10 && cell[0] >= 4 && cell[0] <= 16, "the path crosses the wall at " + cell[0]);
		}

		// Around the nearer end of the wall: 6 steps across, 12 along, from one side to the other.
		assertTrue(path.size() <= 1.2 * 20, "the detour is near the shortest: " + path.size());
	}

	@Test
	void unseenSpaceIsPlannedAsOpen() {
		Grids grid = new Grids(21, 6, 21);
		for (int x = 0; x < 21; x++) {
			grid.wall(x, 10, 3);
		}

		WalkGraph blind = WalkGraph.of(grid, grid.core, new SeenMap(grid.core));
		assertNotNull(PathPlanner.path(blind, new int[] {10, 1, 4}, new int[] {10, 1, 16}), "an unseen wall cannot be known");
		WalkGraph seen = WalkGraph.of(grid, grid.core, seenFrom(grid, 10.5, 4.5));
		assertNull(PathPlanner.path(seen, new int[] {10, 1, 4}, new int[] {10, 1, 16}), "a seen wall across the arena blocks every path");
	}

	@Test
	void stepUpOfOneAllowedTwoNot() {
		Grids low = new Grids(21, 6, 21);
		Grids high = new Grids(21, 6, 21);
		for (int x = 0; x < 21; x++) {
			for (int z = 10; z < 21; z++) {
				low.wall(x, z, 1);
				high.wall(x, z, 2);
			}
		}

		List<int[]> up = PathPlanner.path(WalkGraph.of(low, low.core, seenFrom(low, 10.5, 8.5)), new int[] {10, 1, 8}, new int[] {10, 2, 14});
		assertNotNull(up, "a one-block step is climbed");
		assertNull(PathPlanner.path(WalkGraph.of(high, high.core, seenFrom(high, 10.5, 8.5)), new int[] {10, 1, 8}, new int[] {10, 3, 14}), "a two-block step is not");
	}
}
