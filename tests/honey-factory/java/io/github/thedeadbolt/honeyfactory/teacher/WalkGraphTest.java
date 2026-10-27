package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Footing and drops: the teacher steps only where it has seen floor within a safe drop. */
class WalkGraphTest {
	@Test
	void footingNamesSafeBlockedUnknownAndLedge() {
		// A platform of top 4 (feet 5) from x = 4 to 10; a 2-high step at x = 11; the low floor elsewhere.
		Grids grid = new Grids(16, 10, 16).platform(4, 0, 10, 15, 4).platform(3, 0, 3, 15, 2).wall(11, 5, 6);
		WalkGraph seen = WalkGraph.of(grid, grid.core, SeenMap.everything(grid.core));
		assertEquals(WalkGraph.Footing.SAFE, seen.footing(5, 5, 5), "level floor");
		assertEquals(WalkGraph.Footing.SAFE, seen.footing(3, 5, 5), "a 2-block drop onto seen floor");
		assertEquals(WalkGraph.Footing.LEDGE, seen.footing(12, 5, 5), "a 4-block drop");
		assertEquals(WalkGraph.Footing.BLOCKED, seen.footing(11, 5, 5), "a wall too high to step");
		WalkGraph blind = WalkGraph.of(grid, grid.core, new SeenMap(grid.core));
		assertEquals(WalkGraph.Footing.UNKNOWN, blind.footing(5, 5, 5), "floor not seen yet");
		assertEquals(WalkGraph.Footing.LEDGE, seen.footing(-1, 1, 5), "beyond the arena's edge the body would leave it");
		assertEquals(WalkGraph.Footing.LEDGE, blind.footing(5, 1, 16), "the arena's bounds are known without looking");
	}

	@Test
	void firstUnsafeLooksAheadUnderTheBodysWidth() {
		Grids grid = new Grids(16, 10, 16).platform(4, 4, 10, 10, 4);
		WalkGraph seen = WalkGraph.of(grid, grid.core, SeenMap.everything(grid.core));
		int[] origin = {0, 0, 0};
		// Facing +z (yaw 0) from the middle: safe; half a block from the +z edge: a ledge.
		assertEquals(null, seen.firstUnsafe(7.5, 7.5, 5, origin, 0.0, new double[] {0.45, 0.9}, 0.3));
		WalkGraph.Ahead ahead = seen.firstUnsafe(7.5, 10.5, 5, origin, 0.0, new double[] {0.45, 0.9}, 0.3);
		assertEquals(WalkGraph.Footing.LEDGE, ahead.footing());
		assertEquals(11, ahead.z());
		// Walking backwards looks behind: yaw 180 faces -z, so +z is behind.
		assertEquals(WalkGraph.Footing.LEDGE, seen.firstUnsafe(7.5, 10.5, 5, origin, 180.0, new double[] {-0.45, -0.9}, 0.3).footing());
	}

	@Test
	void dropsArePlannedOnlyOntoSeenFloor() {
		Grids grid = new Grids(16, 10, 16).platform(4, 0, 10, 15, 4).platform(11, 0, 15, 15, 2);
		WalkGraph seen = WalkGraph.of(grid, grid.core, SeenMap.everything(grid.core));
		assertTrue(seen.edges(new int[] {10, 5, 5}).stream().anyMatch(edge -> edge.to()[0] == 11 && edge.to()[1] == 3), "a seen 2-block drop is a step");
		WalkGraph blind = WalkGraph.of(grid, grid.core, new SeenMap(grid.core));
		assertFalse(blind.edges(new int[] {10, 5, 5}).stream().anyMatch(edge -> edge.to()[1] < 5), "no drop onto unseen floor");
	}

	@Test
	void aFenceIsNoStepAndAPlantNoObstacle() {
		Grids grid = new Grids(16, 6, 16).fence(6, 5).plant(6, 8);
		WalkGraph seen = WalkGraph.of(grid, grid.core, SeenMap.everything(grid.core));
		assertEquals(WalkGraph.Footing.BLOCKED, seen.footing(6, 1, 5), "a fence stands 1.5 high: no auto-jump clears it");
		assertFalse(seen.edges(new int[] {5, 1, 5}).stream().anyMatch(edge -> edge.to()[0] == 6 && edge.to()[2] == 5), "no step onto a fence");
		assertFalse(seen.walkable(6, 2, 5), "nobody stands on a fence post");
		assertEquals(WalkGraph.Footing.SAFE, seen.footing(6, 1, 8), "a plant is walked through");
		assertTrue(seen.edges(new int[] {5, 1, 8}).stream().anyMatch(edge -> edge.to()[0] == 6 && edge.to()[1] == 1 && edge.to()[2] == 8), "a level step through the plant");
	}
}
