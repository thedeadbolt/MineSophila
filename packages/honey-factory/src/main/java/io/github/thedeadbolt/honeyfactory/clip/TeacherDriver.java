package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.Driver;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;

/** Executes the teacher's command, with drift; the teacher recovers from wherever drift leaves the body. */
final class TeacherDriver implements ClipDriver {
	private final Drift drift;

	TeacherDriver(Drift drift) {
		this.drift = drift;
	}

	@Override
	public MotorCommand next(int tick, TeacherInput input, Teacher.Decision teacher) {
		return this.drift.apply(teacher.command());
	}

	@Override
	public boolean drifting() {
		return this.drift.active();
	}

	@Override
	public Driver driver() {
		return drifting() ? Driver.TEACHER_DRIFT : Driver.TEACHER;
	}
}
