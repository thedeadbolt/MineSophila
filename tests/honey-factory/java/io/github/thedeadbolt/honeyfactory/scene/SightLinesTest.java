package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SightLinesTest {
	private static final int[] TARGET = {8, 1, 8};
	/** An eye 6 blocks south of the target, slightly above it. */
	private static final double[] EYE = {8.5, 2.62, 14.5};

	@Test
	void anOpenPlatformLeavesEverySightLineClear() {
		assertEquals(1.0, SightLines.clearFraction(grid(), EYE, TARGET));
	}

	@Test
	void aWallBetweenEyeAndTargetBlocksEverything() {
		List<Placement> wall = new ArrayList<>();
		for (int x = 6; x <= 10; x++) {
			for (int y = 1; y <= 3; y++) {
				wall.add(new Placement(x, y, 10, "minecraft:stone"));
			}
		}

		assertEquals(0.0, SightLines.clearFraction(grid(wall.toArray(Placement[]::new)), EYE, TARGET));
	}

	@Test
	void aLowWallHidesOnlyPartOfTheTarget() {
		double clear = SightLines.clearFraction(grid(new Placement(8, 1, 9, "minecraft:stone")), EYE, TARGET);
		assertTrue(clear > 0.0 && clear < 1.0, "clear fraction " + clear);
	}

	@Test
	void blocksBehindTheTargetDoNotCount() {
		assertEquals(1.0, SightLines.clearFraction(grid(new Placement(8, 1, 7, "minecraft:stone"), new Placement(8, 2, 8, "minecraft:stone")), new double[] {8.5, 1.5, 14.5}, TARGET));
	}

	@Test
	void anEyeInsideTheCellSeesNoFace() {
		assertEquals(0.0, SightLines.clearFraction(grid(), new double[] {8.5, 1.5, 8.5}, TARGET));
	}

	@Test
	void theGridReportsOverlapsButNotTouchingFaces() {
		SolidGrid grid = grid(new Placement(3, 1, 3, "minecraft:stone"));
		assertTrue(grid.solid(3, 1, 3));
		assertTrue(grid.solid(0, 0, 0), "the floor is solid");
		assertFalse(grid.solid(-1, 0, 0), "outside the arena is empty");
		assertFalse(grid.intersects(new double[] {2.4, 1.0, 2.4}, new double[] {3.0, 2.8, 3.0}), "touching the block's faces");
		assertTrue(grid.intersects(new double[] {2.5, 1.0, 2.5}, new double[] {3.1, 2.8, 3.1}));
		assertFalse(grid.intersects(new double[] {5.2, 1.0, 5.2}, new double[] {5.8, 2.8, 5.8}), "standing on the floor");
	}

	private static SolidGrid grid(Placement... blocks) {
		Template template = Template.platform("test", 1, 16, 16, 8, "minecraft:light_gray_concrete");
		return SolidGrid.of(new ScenePlan(template, List.of(blocks), null, 6000, Weather.CLEAR));
	}
}
