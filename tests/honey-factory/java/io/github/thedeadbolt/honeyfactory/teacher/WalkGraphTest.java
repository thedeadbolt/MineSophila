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
	}

	@Test
	void dropsArePlannedOnlyOntoSeenFloor() {
		Grids grid = new Grids(16, 10, 16).platform(4, 0, 10, 15, 4).platform(11, 0, 15, 15, 2);
		WalkGraph seen = WalkGraph.of(grid, grid.core, SeenMap.everything(grid.core));
		assertTrue(seen.edges(new int[] {10, 5, 5}).stream().anyMatch(edge -> edge.to()[0] == 11 && edge.to()[1] == 3), "a seen 2-block drop is a step");
		WalkGraph blind = WalkGraph.of(grid, grid.core, new SeenMap(grid.core));
		assertFalse(blind.edges(new int[] {10, 5, 5}).stream().anyMatch(edge -> edge.to()[1] < 5), "no drop onto unseen floor");
	}
}
