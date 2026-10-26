package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.contract.Driver;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.teacher.HoneyMemory;
import io.github.thedeadbolt.honeyfactory.teacher.SeenMap;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every driver names who drove each tick: its script, the teacher, or the teacher with drift. */
class ClipDriverTest {
	private static final BlockBox CORE = new BlockBox(0, 64, 0, 31, 79, 31);
	private static final BodySenses STILL = new BodySenses(0.0, 0.0, 0.0, 0.0, 0.0, 0.0f, new double[] {0.0, -1.0, 0.0}, true, false, false, MotorCommand.IDLE);
	private static final Teacher.Decision WALK = new Teacher.Decision(new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0), TeacherMode.SEARCH,
		new FrameRecord.Belief(FrameRecord.Belief.Source.NONE, null, 0));

	@Test
	void scriptsDriveScanAndPassBy() {
		CameraPose start = new CameraPose(10.5, 65.0, 10.5, 0.0f, 0.0f);
		for (ClipDriver driver : new ClipDriver[] {new ScanDriver(1L, MotorLimits.DEFAULT), new PassByDriver(1L, start, new int[] {10, 65, 20}, MotorLimits.DEFAULT, new EdgeGuard(EdgeGuardTest.PLATFORM, EdgeGuardTest.CORE), 6.0)}) {
			driver.next(1, input(1, start), WALK);
			assertEquals(Driver.SCRIPT, driver.driver());
		}
	}

	@Test
	void teacherDriverNamesItsDrift() {
		TeacherDriver driver = new TeacherDriver(new Drift(7L, new ClipSettings.Drift(0.5, 6.0, 6.0, 0.5), MotorLimits.DEFAULT));
		Set<Driver> seen = EnumSet.noneOf(Driver.class);
		CameraPose pose = new CameraPose(10.5, 65.0, 10.5, 0.0f, 0.0f);
		for (int tick = 1; tick <= 200; tick++) {
			driver.next(tick, input(tick, pose), WALK);
			assertEquals(driver.drifting() ? Driver.TEACHER_DRIFT : Driver.TEACHER, driver.driver());
			seen.add(driver.driver());
		}

		assertEquals(EnumSet.of(Driver.TEACHER, Driver.TEACHER_DRIFT), seen);
	}

	@Test
	void occlusionIsScriptedUntilItsWaypoint() {
		OcclusionDriver driver = new OcclusionDriver(new double[] {10.5, 65.0, 14.5}, MotorLimits.DEFAULT, 1.62, new Drift(7L, new ClipSettings.Drift(0.0, 6.0, 6.0, 0.5), MotorLimits.DEFAULT));
		driver.next(1, input(1, new CameraPose(10.5, 65.0, 10.5, 0.0f, 0.0f)), WALK);
		assertEquals(Driver.SCRIPT, driver.driver());
		driver.next(2, input(2, new CameraPose(10.5, 65.0, 14.3, 0.0f, 0.0f)), WALK);
		assertEquals(Driver.TEACHER, driver.driver());
		assertTrue(!driver.drifting());
	}

	private static TeacherInput input(int tick, CameraPose pose) {
		return new TeacherInput(tick, pose, STILL, null, new SeenMap(CORE), new HoneyMemory());
	}
}
