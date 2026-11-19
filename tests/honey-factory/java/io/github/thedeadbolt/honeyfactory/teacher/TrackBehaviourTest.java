package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Tracking visible honey closes the distance, around obstacles, within the motor limits. */
class TrackBehaviourTest {
	private static final CaptureProjection PANORAMA = CaptureProjection.of(CaptureMode.PANORAMA_310, 248, 128, 64);
	private static final double EYE = 1.62;
	private static final double REACH = 1.5;
	private static final int TICKS = 100;

	/** Runs the tracker from {@code start} with the honey visible every tick; the tick it is reached, or -1. */
	static int run(Grids grid, CameraPose start, int[] honey, boolean[] limitsKept) {
		TeacherContext context = new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, EYE, PANORAMA, REACH);
		BodySim body = new BodySim(grid, start);
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		for (int tick = 1; tick <= TICKS; tick++) {
			TeacherInput input = Perception.perceive(tick, body.pose(), body.senses(), honey, 1000, context, seen, memory);
			if (TrackBehaviour.reached(input.pose(), honey, context)) {
				return tick;
			}

			Teacher.Decision decision = TrackBehaviour.decide(input, context);
			assertEquals(TeacherMode.TRACK, decision.mode());
			assertEquals(FrameRecord.Belief.Source.SEEN, decision.belief().source());
			try {
				MotorLimits.DEFAULT.check(decision.command());
			} catch (IllegalArgumentException e) {
				limitsKept[0] = false;
			}

			body.step(decision.command());
		}

		return -1;
	}

	@Test
	void honeyWithNoKnownWayIsWatchedNotWalkedTo() {
		// The honey stands on a second platform across a gap with a 4-block drop: no path,
		// so the tracker faces it and waits instead of walking at it (no brake involved).
		Grids grid = new Grids(33, 10, 33).platform(4, 4, 28, 14, 4).platform(4, 18, 28, 28, 4);
		int[] honey = {16, 5, 24};
		grid.wall(honey[0], honey[2], 5);
		TeacherContext context = new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, EYE, PANORAMA, REACH);
		BodySim body = new BodySim(grid, new CameraPose(16.5, 5.0, 10.5, 0.0f, 0.0f));
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		for (int tick = 1; tick <= 80; tick++) {
			body.step(TrackBehaviour.decide(Perception.perceive(tick, body.pose(), body.senses(), honey, 1000, context, seen, memory), context).command());
			assertTrue(body.pose().y() >= 5.0, "tick " + tick + ": walked into the gap to " + body.pose());
		}
	}

	@Test
	void reachesHoneyOnOpenPlatform() {
		SplittableRandom random = new SplittableRandom(2026);
		int reached = 0;
		boolean[] limits = {true};
		for (int trial = 0; trial < 50; trial++) {
			Grids grid = new Grids(33, 6, 33);
			int[] honey = {random.nextInt(8, 25), 1, random.nextInt(8, 25)};
			grid.wall(honey[0], honey[2], 1);
			double distance = 3 + 13 * random.nextDouble();
			double angle = random.nextDouble() * 2 * Math.PI;
			double x = Math.clamp(honey[0] + 0.5 + Math.cos(angle) * distance, 1.5, 31.5);
			double z = Math.clamp(honey[2] + 0.5 + Math.sin(angle) * distance, 1.5, 31.5);
			CameraPose start = new CameraPose(x, 1.0, z, (float) (random.nextDouble() * 360 - 180), 0.0f);
			if (run(grid, start, honey, limits) > 0) {
				reached++;
			}
		}

		assertTrue(reached >= 48, "reached the honey in " + reached + " of 50 starts");
		assertTrue(limits[0], "every command kept within the motor limits");
	}

	@Test
	void reachesHoneyBehindAWall() {
		Grids grid = new Grids(25, 6, 25);
		for (int x = 6; x <= 18; x++) {
			grid.wall(x, 12, 3);
		}

		int[] honey = {12, 1, 16};
		grid.wall(honey[0], honey[2], 1);
		boolean[] limits = {true};
		int tick = run(grid, new CameraPose(12.5, 1.0, 6.5, 0.0f, 0.0f), honey, limits);
		assertTrue(tick > 0, "the tracker went round the wall");
	}

	@Test
	void reachesHoneyAtAnAlleyMouthFromTheDiagonal() {
		// Pilot m12-2b s000041-c01: the honey fills the mouth of a one-wide alley; its sides
		// are walls, a leaf block and the unseen alley behind it. Only the open diagonal
		// cell, 1.41 from the honey's centre, is within reach.
		Grids grid = new Grids(25, 6, 25);
		for (int x = 12; x <= 16; x++) {
			grid.wall(x, 10, 3).wall(x, 12, 3);
		}

		grid.wall(11, 11, 1).wall(11, 12, 1);
		int[] honey = {12, 1, 11};
		grid.wall(honey[0], honey[2], 1);
		boolean[] limits = {true};
		assertTrue(run(grid, new CameraPose(8.5, 1.0, 14.5, -90.0f, 0.0f), honey, limits) > 0, "the tracker stood within reach");
		assertTrue(limits[0]);
	}

	@Test
	void goesRoundAFenceItCannotClimb() {
		// Pilot m12-2b s000042-c00 and s000006-c00: a fence between the body and the honey,
		// open at one end. A fence is 1.5 high, so the way is round it, not over it.
		Grids grid = new Grids(25, 6, 25);
		for (int x = 6; x <= 18; x++) {
			grid.fence(x, 12);
		}

		int[] honey = {12, 1, 15};
		grid.wall(honey[0], honey[2], 1);
		boolean[] limits = {true};
		assertTrue(run(grid, new CameraPose(12.5, 1.0, 9.5, 0.0f, 0.0f), honey, limits) > 0, "the tracker went round the fence");
	}

	@Test
	void standsStillWithinReach() {
		Grids grid = new Grids(17, 6, 17);
		int[] honey = {8, 1, 10};
		grid.wall(honey[0], honey[2], 1);
		TeacherContext context = new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, EYE, PANORAMA, REACH);
		BodySim body = new BodySim(grid, new CameraPose(8.5, 1.0, 9.2, 0.0f, 0.0f));
		TeacherInput input = Perception.perceive(1, body.pose(), body.senses(), honey, 1000, context, new SeenMap(grid.core), new HoneyMemory());
		assertTrue(TrackBehaviour.reached(input.pose(), honey, context));
		assertEquals(0.0, TrackBehaviour.decide(input, context).command().forward());
		assertDoesNotThrow(() -> MotorLimits.DEFAULT.check(TrackBehaviour.decide(input, context).command()));
	}
}
