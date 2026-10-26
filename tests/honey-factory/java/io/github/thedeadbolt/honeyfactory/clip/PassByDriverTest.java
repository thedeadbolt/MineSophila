package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.junit.jupiter.api.Test;

/** A pass-by walks a line past the honey that stays on the floor for the clip's length where one exists. */
class PassByDriverTest {
	@Test
	void lineStaysOnFloor() {
		EdgeGuard guard = new EdgeGuard(EdgeGuardTest.PLATFORM, EdgeGuardTest.CORE);
		// Near the platform's -x edge with the honey ahead (+z): lines to one side run off it.
		CameraPose start = new CameraPose(9.0, 5.0, 10.0, 0.0f, 0.0f);
		int[] honey = {9, 5, 18};
		double needed = 6.0;
		for (long seed = 0; seed < 40; seed++) {
			PassByDriver driver = new PassByDriver(seed, start, honey, MotorLimits.DEFAULT, guard, needed);
			double run = guard.clearRun(start, driver.heading(), needed);
			assertTrue(run >= needed, "seed " + seed + ": heading " + driver.heading() + " runs off the floor after " + run + " blocks");
		}
	}
}
