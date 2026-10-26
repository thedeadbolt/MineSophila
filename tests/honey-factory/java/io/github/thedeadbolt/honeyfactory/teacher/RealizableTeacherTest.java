package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The full teacher: Track while the honey is seen, Re-acquire from memory for a while, then Search. */
class RealizableTeacherTest {
	private static final CaptureProjection PANORAMA = CaptureProjection.of(CaptureMode.PANORAMA_310, 248, 128, 64);
	private static final int REACQUIRE_TICKS = 60;

	private static TeacherContext context(Grids grid) {
		return new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, 1.62, PANORAMA, 1.5);
	}

	@Test
	void neverUsesHiddenHoney() {
		SplittableRandom random = new SplittableRandom(7);
		for (int trial = 0; trial < 20; trial++) {
			Grids grid = new Grids(25, 6, 25);
			TeacherContext context = context(grid);
			RealizableTeacher one = new RealizableTeacher(REACQUIRE_TICKS);
			RealizableTeacher other = new RealizableTeacher(REACQUIRE_TICKS);
			one.reset(context);
			other.reset(context);
			BodySim body = new BodySim(grid, new CameraPose(12.5, 1.0, 12.5, (float) random.nextDouble(-180, 180), 0.0f));
			SeenMap seenOne = new SeenMap(grid.core);
			SeenMap seenOther = new SeenMap(grid.core);
			HoneyMemory memoryOne = new HoneyMemory();
			HoneyMemory memoryOther = new HoneyMemory();
			for (int tick = 1; tick <= 30; tick++) {
				int visible = random.nextInt(0, 50);
				Teacher.Decision a = one.decide(Perception.perceive(tick, body.pose(), body.senses(), new int[] {3, 1, 3}, visible, context, seenOne, memoryOne));
				Teacher.Decision b = other.decide(Perception.perceive(tick, body.pose(), body.senses(), new int[] {20, 1, 20}, visible, context, seenOther, memoryOther));
				assertNotEquals(FrameRecord.Belief.Source.SEEN, a.belief().source(), "below 50 pixels nothing is seen");
				assertEquals(a, b, "where hidden honey lies cannot change a decision");
				body.step(a.command());
			}
		}
	}

	@Test
	void switchesTrackToReacquireToSearch() {
		Grids grid = new Grids(33, 6, 33);
		int[] honey = {16, 1, 26};
		grid.wall(honey[0], honey[2], 1);
		TeacherContext context = context(grid);
		RealizableTeacher teacher = new RealizableTeacher(REACQUIRE_TICKS);
		teacher.reset(context);
		BodySim body = new BodySim(grid, new CameraPose(16.5, 1.0, 6.5, 0.0f, 0.0f));
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		for (int tick = 1; tick <= 90; tick++) {
			int visible = tick <= 10 ? 1000 : 0;
			Teacher.Decision decision = teacher.decide(Perception.perceive(tick, body.pose(), body.senses(), honey, visible, context, seen, memory));
			TeacherMode expected = tick <= 10 ? TeacherMode.TRACK : tick <= 10 + REACQUIRE_TICKS ? TeacherMode.REACQUIRE : TeacherMode.SEARCH;
			assertEquals(expected, decision.mode(), "tick " + tick);
			assertEquals(tick - 1, decision.belief().observedTick());
			switch (decision.mode()) {
				case TRACK -> assertArrayEquals(honey, decision.belief().position());
				case REACQUIRE -> {
					assertEquals(FrameRecord.Belief.Source.MEMORY, decision.belief().source());
					assertArrayEquals(honey, decision.belief().position());
				}
				case SEARCH -> {
					assertEquals(FrameRecord.Belief.Source.NONE, decision.belief().source());
					assertNull(decision.belief().position());
				}
			}

			MotorLimits.DEFAULT.check(decision.command());
			body.step(decision.command());
		}
	}

	@Test
	void reacquireWalksBackTowardsTheLastSighting() {
		Grids grid = new Grids(33, 6, 33);
		int[] honey = {16, 1, 26};
		TeacherContext context = context(grid);
		RealizableTeacher teacher = new RealizableTeacher(REACQUIRE_TICKS);
		teacher.reset(context);
		BodySim body = new BodySim(grid, new CameraPose(16.5, 1.0, 6.5, 180.0f, 0.0f));
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		double start = Math.hypot(body.pose().x() - 16.5, body.pose().z() - 26.5);
		for (int tick = 1; tick <= 40; tick++) {
			int visible = tick == 1 ? 1000 : 0;
			body.step(teacher.decide(Perception.perceive(tick, body.pose(), body.senses(), honey, visible, context, seen, memory)).command());
		}

		double after = Math.hypot(body.pose().x() - 16.5, body.pose().z() - 26.5);
		assertEquals(true, after < start - 4.0, "re-acquiring closed on the last sighting: " + start + " -> " + after);
	}
}
