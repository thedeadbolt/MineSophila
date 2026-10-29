package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The teacher's map of what has been in view: cells in the projection with a clear line from the eye. */
class SeenMapTest {
	private static final double EYE = 1.62;
	private static final CaptureProjection PERSPECTIVE = CaptureProjection.of(CaptureMode.PERSPECTIVE_90, 64, 64, null);
	private static final CaptureProjection PANORAMA = CaptureProjection.of(CaptureMode.PANORAMA_310, 248, 128, 64);
	/** Standing at (8.5, 1, 2.5), facing +z, so a wall across z = 6 stands between the body and the far half. */
	private static final CameraPose POSE = new CameraPose(8.5, 1.0, 2.5, 0.0f, 0.0f);

	private static Grids walled() {
		Grids grid = new Grids(17, 6, 17);
		for (int x = 0; x < 17; x++) {
			grid.wall(x, 6, 4);
		}

		return grid;
	}

	@Test
	void wallHidesCellsBehindIt() {
		Grids grid = walled();
		SeenMap seen = new SeenMap(grid.core);
		seen.observe(POSE, EYE, PERSPECTIVE, grid);
		assertTrue(seen.seen(8, 1, 4), "the floor-level air in front of the wall is seen");
		assertTrue(seen.seen(8, 2, 6), "the wall's face is seen");
		assertFalse(seen.seen(8, 1, 10), "the air behind the wall is not");
		assertFalse(seen.seen(8, 1, 15));
	}

	@Test
	void outsideProjectionNotSeen() {
		Grids grid = new Grids(17, 6, 17);
		SeenMap seen = new SeenMap(grid.core);
		seen.observe(new CameraPose(8.5, 1.0, 8.5, 0.0f, 0.0f), EYE, PERSPECTIVE, grid);
		assertTrue(seen.seen(8, 1, 12), "ahead");
		assertFalse(seen.seen(8, 1, 4), "behind the eye, outside a 90-degree view");
		SeenMap around = new SeenMap(grid.core);
		around.observe(new CameraPose(8.5, 1.0, 8.5, 0.0f, 0.0f), EYE, PANORAMA, grid);
		assertTrue(around.seen(2, 1, 8), "a panorama sees to the side");
	}

	@Test
	void seeingAccumulates() {
		Grids grid = new Grids(17, 6, 17);
		SeenMap seen = new SeenMap(grid.core);
		seen.observe(new CameraPose(8.5, 1.0, 8.5, 0.0f, 0.0f), EYE, PERSPECTIVE, grid);
		seen.observe(new CameraPose(8.5, 1.0, 8.5, 180.0f, 0.0f), EYE, PERSPECTIVE, grid);
		assertTrue(seen.seen(8, 1, 12));
		assertTrue(seen.seen(8, 1, 4));
	}

	@Test
	void frontiersDependOnlyOnWhatWasSeen() {
		// Unseen blocks above or floor below a seen cell cannot change where the teacher searches.
		Grids grid = walled();
		SeenMap seen = new SeenMap(grid.core);
		seen.observe(new CameraPose(8.5, 1.0, 2.5, 0.0f, 30.0f), EYE, PERSPECTIVE, grid);
		SightGrid altered = new SightGrid() {
			@Override
			public boolean solid(int x, int y, int z) {
				boolean unseen = !seen.seen(x, y, z);
				return y == 0 ? !unseen && grid.solid(x, y, z) : unseen && y == 2 || grid.solid(x, y, z);
			}

			@Override
			public boolean blocks(int x, int y, int z, double[] from, double[] to) {
				return solid(x, y, z);
			}
		};
		List<String> expected = seen.frontiers(WalkGraph.of(grid, grid.core, seen)).stream().map(Arrays::toString).toList();
		assertFalse(expected.isEmpty());
		assertEquals(expected, seen.frontiers(WalkGraph.of(altered, grid.core, seen)).stream().map(Arrays::toString).toList());
	}

	@Test
	void frontiersLieOnTheSeenEdge() {
		Grids grid = walled();
		SeenMap seen = new SeenMap(grid.core);
		seen.observe(POSE, EYE, PERSPECTIVE, grid);
		WalkGraph graph = WalkGraph.of(grid, grid.core, seen);
		List<int[]> frontiers = seen.frontiers(graph);
		assertFalse(frontiers.isEmpty());
		for (int[] cell : frontiers) {
			assertTrue(seen.seen(cell[0], cell[1], cell[2]), "a frontier is seen");
			assertTrue(graph.walkable(cell[0], cell[1], cell[2]), "a frontier is somewhere to stand, as far as has been seen");
			boolean unseenNeighbour = false;
			for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int x = cell[0] + step[0];
				int z = cell[2] + step[1];
				unseenNeighbour |= grid.core.contains(x, cell[1], z) && !seen.seen(x, cell[1], z);
			}

			assertTrue(unseenNeighbour, "a frontier borders unseen space");
		}
	}

	@Test
	void coverageCountsSeenReachableCells() {
		Grids grid = new Grids(17, 6, 17);
		SeenMap seen = new SeenMap(grid.core);
		assertEquals(0.0, seen.coverage(SeenMap.reachable(grid, grid.core, new int[] {8, 1, 8})));
		seen.observe(new CameraPose(8.5, 1.0, 8.5, 0.0f, 0.0f), EYE, PANORAMA, grid);
		double coverage = seen.coverage(SeenMap.reachable(grid, grid.core, new int[] {8, 1, 8}));
		assertTrue(coverage > 0.5 && coverage < 1.0, "a panorama from the middle sees most of an open floor, not all: " + coverage);
	}
}
