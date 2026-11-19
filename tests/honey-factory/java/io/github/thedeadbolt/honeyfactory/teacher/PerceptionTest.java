package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.control.MotorState;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.junit.jupiter.api.Test;

/** The honey reaches the teacher only through what the frame showed, at the configured threshold. */
class PerceptionTest {
	private static final CaptureProjection PERSPECTIVE = CaptureProjection.of(CaptureMode.PERSPECTIVE_90, 64, 64, null);
	private static final CameraPose POSE = new CameraPose(8.5, 1.0, 2.5, 0.0f, 0.0f);
	private static final int[] HONEY = {8, 1, 12};

	private static TeacherContext context(Grids grid) {
		return new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, 1.62, PERSPECTIVE, 1.5);
	}

	private static BodySenses senses() {
		return BodySenses.of(POSE, new double[3], MotorState.ZERO, true, false, false, MotorCommand.IDLE);
	}

	@Test
	void belowThresholdHidesHoney() {
		Grids grid = new Grids(17, 6, 17);
		TeacherContext context = context(grid);
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		assertNull(Perception.perceive(1, POSE, senses(), HONEY, 49, context, seen, memory).honeySeen());
		assertNull(memory.lastSeen());
		TeacherInput input = Perception.perceive(2, POSE, senses(), HONEY, 50, context, seen, memory);
		assertArrayEquals(HONEY, input.honeySeen());
		assertArrayEquals(HONEY, memory.lastSeen());
	}

	@Test
	void absentHoneyIsNeverSeen() {
		Grids grid = new Grids(17, 6, 17);
		TeacherInput input = Perception.perceive(1, POSE, senses(), null, 5000, context(grid), new SeenMap(grid.core), new HoneyMemory());
		assertNull(input.honeySeen());
	}

	@Test
	void memoryKeepsLastSeenPosition() {
		Grids grid = new Grids(17, 6, 17);
		TeacherContext context = context(grid);
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		assertEquals(Integer.MAX_VALUE, memory.ticksSinceSeen(0));
		Perception.perceive(3, POSE, senses(), HONEY, 400, context, seen, memory);
		for (int tick = 4; tick <= 9; tick++) {
			TeacherInput input = Perception.perceive(tick, POSE, senses(), new int[] {1, 1, 1}, 0, context, seen, memory);
			assertNull(input.honeySeen());
		}

		assertArrayEquals(HONEY, memory.lastSeen());
		assertEquals(3, memory.seenTick());
		assertEquals(6, memory.ticksSinceSeen(9));
	}

	@Test
	void perceivingMarksWhatTheEyeSees() {
		Grids grid = new Grids(17, 6, 17);
		SeenMap seen = new SeenMap(grid.core);
		Perception.perceive(1, POSE, senses(), HONEY, 0, context(grid), seen, new HoneyMemory());
		assertEquals(true, seen.seen(8, 1, 10));
	}
}
