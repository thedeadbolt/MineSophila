package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.scene.SightLines;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** Starts for clip types that need the scene's layout: blocked for detours, crossing a blocker for occlusions. */
class ClipStartsTest {
	private static final double EYE = 1.62;
	private static final BlockBox CORE = new BlockBox(0, 0, 0, 31, 7, 31);

	/** A floor at y = 0, a wall across x = 8..23 at z = 16 three blocks high, and the honey at (16, 1, 22). */
	private static SightGrid walled(boolean wall) {
		Set<Long> blocks = new HashSet<>();
		for (int x = 0; x < 32; x++) {
			for (int z = 0; z < 32; z++) {
				blocks.add(key(x, 0, z));
			}
		}

		if (wall) {
			for (int x = 8; x <= 23; x++) {
				for (int y = 1; y <= 3; y++) {
					blocks.add(key(x, y, 16));
				}
			}
		}

		blocks.add(key(16, 1, 22));
		return new SightGrid() {
			@Override
			public boolean solid(int x, int y, int z) {
				return blocks.contains(key(x, y, z));
			}

			@Override
			public boolean blocks(int x, int y, int z, double[] from, double[] to) {
				return solid(x, y, z);
			}
		};
	}

	private static long key(int x, int y, int z) {
		return ((long) x << 40) | ((long) y << 20) | z;
	}

	/** Random standing poses on the floor, 3 to 16 blocks from the honey. */
	private static IntFunction<CameraPose> draws(long seed) {
		return draw -> {
			SplittableRandom random = new SplittableRandom(seed * 1000 + draw);
			double angle = random.nextDouble() * 2 * Math.PI;
			double distance = 3 + 13 * random.nextDouble();
			double x = Math.clamp(16.5 + Math.cos(angle) * distance, 1.5, 30.5);
			double z = Math.clamp(22.5 + Math.sin(angle) * distance, 1.5, 30.5);
			return new CameraPose(x, 1.0, z, 0.0f, 0.0f);
		};
	}

	@Test
	void detourStartIsBlockedAndLonger() {
		SightGrid grid = walled(true);
		ClipStarts.Start start = ClipStarts.choose(ClipType.DETOUR, draws(1), 0, 60, grid, CORE, new int[] {16, 1, 22}, 9L, EYE);
		assertNotNull(start);
		assertEquals(ClipType.DETOUR, start.type());
		double[] eye = {start.pose().x(), start.pose().y() + EYE, start.pose().z()};
		assertEquals(0.0, SightLines.clearFraction(grid, eye, new int[] {16, 1, 22}), "the honey is hidden from a detour's start");
		assertTrue(start.pathRatio() >= ClipStarts.MIN_DETOUR_RATIO, "the walk is a detour: " + start.pathRatio());
	}

	@Test
	void occlusionStartPassesBehindABlocker() {
		SightGrid grid = walled(true);
		ClipStarts.Start start = ClipStarts.choose(ClipType.OCCLUSION, draws(2), 0, 60, grid, CORE, new int[] {16, 1, 22}, 4L, EYE);
		assertNotNull(start);
		assertEquals(ClipType.OCCLUSION, start.type());
		assertNotNull(start.waypoint());
		assertTrue(start.hiddenTicks() >= ClipStarts.MIN_HIDDEN_TICKS, "the honey hides for " + start.hiddenTicks() + " ticks");
	}

	@Test
	void anEmptyPlatformFallsBackToApproach() {
		SightGrid grid = walled(false);
		for (ClipType requested : new ClipType[] {ClipType.DETOUR, ClipType.OCCLUSION}) {
			ClipStarts.Start start = ClipStarts.choose(requested, draws(3), 0, 30, grid, CORE, new int[] {16, 1, 22}, 1L, EYE);
			assertNotNull(start);
			assertEquals(ClipType.APPROACH, start.type(), requested + " falls back");
		}
	}

	@Test
	void scanAndNoHoneyStartsFaceARandomWay() {
		SightGrid grid = walled(false);
		ClipStarts.Start scan = ClipStarts.choose(ClipType.SCAN, draws(3), 0, 30, grid, CORE, new int[] {16, 1, 22}, 1L, EYE);
		ClipStarts.Start again = ClipStarts.choose(ClipType.SCAN, draws(3), 0, 30, grid, CORE, new int[] {16, 1, 22}, 2L, EYE);
		assertTrue(scan.pose().yawDegrees() != again.pose().yawDegrees(), "the start seed turns the start");
		assertEquals(ClipType.NO_HONEY, ClipStarts.choose(ClipType.NO_HONEY, draws(3), 0, 30, grid, CORE, null, 1L, EYE).type());
	}
}
