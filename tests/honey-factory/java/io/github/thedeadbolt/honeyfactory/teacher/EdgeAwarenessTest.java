package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The teacher never walks off a ledge it cannot climb back up: raised platforms with a
 * 4-block drop all round (deeper than {@link SeenMap#MAX_DROP}), inside a larger arena whose
 * edge the planner already treats as a wall.
 */
class EdgeAwarenessTest {
	private static final CaptureProjection PANORAMA = CaptureProjection.of(CaptureMode.PANORAMA_310, 248, 128, 64);
	private static final int TOP = 4;
	private static final double STANDING = TOP + 1;

	private static TeacherContext context(Grids grid) {
		return new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, 1.62, PANORAMA, 1.5);
	}

	@Test
	void honeyAcrossAGapDoesNotWalkOff() {
		// Two platforms 3 blocks apart; the honey stands on the far one, in plain view.
		Grids grid = new Grids(33, 10, 33).platform(4, 4, 28, 14, TOP).platform(4, 18, 28, 28, TOP);
		int[] honey = {16, TOP + 1, 24};
		grid.wall(honey[0], honey[2], TOP + 1);
		for (int start = 0; start < 6; start++) {
			assertStaysUp(grid, new CameraPose(8.5 + 3 * start, STANDING, 8.5, 0.0f, 0.0f), honey, 120);
		}
	}

	@Test
	void searchingNeverWalksOff() {
		Grids grid = new Grids(33, 10, 33).platform(8, 8, 22, 22, TOP);
		SplittableRandom random = new SplittableRandom(3);
		for (int trial = 0; trial < 8; trial++) {
			CameraPose start = new CameraPose(random.nextDouble(9.5, 21.5), STANDING, random.nextDouble(9.5, 21.5), (float) random.nextDouble(-180, 180), 0.0f);
			assertStaysUp(grid, start, null, 200);
		}
	}

	@Test
	void honeyBelowTheLedgeIsNotChasedOverIt() {
		// The honey lies on the low floor beside the platform: seen, but out of reach from above.
		Grids grid = new Grids(33, 10, 33).platform(8, 8, 22, 22, TOP);
		int[] honey = {16, 1, 26};
		grid.wall(honey[0], honey[2], 1);
		assertStaysUp(grid, new CameraPose(15.5, STANDING, 15.5, 0.0f, 0.0f), honey, 120);
	}

	private static void assertStaysUp(Grids grid, CameraPose start, int[] honey, int ticks) {
		TeacherContext context = context(grid);
		RealizableTeacher teacher = new RealizableTeacher(60);
		teacher.reset(context);
		BodySim body = new BodySim(grid, start);
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		for (int tick = 1; tick <= ticks; tick++) {
			int visible = honey == null ? 0 : 1000;
			body.step(teacher.decide(Perception.perceive(tick, body.pose(), body.senses(), honey, visible, context, seen, memory)).command());
			assertTrue(body.pose().y() >= STANDING, "tick " + tick + " from " + start + ": the body walked off the platform to " + body.pose());
		}
	}
}
