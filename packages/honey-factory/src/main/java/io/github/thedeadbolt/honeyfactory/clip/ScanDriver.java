package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.Driver;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;
import java.util.SplittableRandom;

/** Turns in place at one rate, 4 to 9 degrees per tick either way, so the honey enters and leaves the frame. */
final class ScanDriver implements ClipDriver {
	static final double MIN_RATE = 4.0;
	static final double MAX_RATE = 9.0;

	private final MotorCommand command;

	ScanDriver(long seed, MotorLimits limits) {
		SplittableRandom random = new SplittableRandom(seed);
		double rate = Math.min(MIN_RATE + (MAX_RATE - MIN_RATE) * random.nextDouble(), limits.maxTurnRate());
		this.command = new MotorCommand(0.0, 0.0, random.nextBoolean() ? rate : -rate, 0.0, false, 0);
	}

	@Override
	public MotorCommand next(int tick, TeacherInput input, Teacher.Decision teacher) {
		return this.command;
	}

	@Override
	public Driver driver() {
		return Driver.SCRIPT;
	}
}
