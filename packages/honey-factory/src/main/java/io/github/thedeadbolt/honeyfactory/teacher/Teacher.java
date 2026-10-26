package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;

/**
 * The scripted controller whose command every clip records at every tick (M12-2), and whose
 * commands M13 and M14 imitate. It acts only on {@link TeacherInput}, so it never steers by
 * honey it could not have seen.
 */
public interface Teacher {
	/** One tick's decision: the command, the mode it came from, and what the teacher believed of the honey. */
	record Decision(MotorCommand command, TeacherMode mode, FrameRecord.Belief belief) {
	}

	/** Forgets everything for a new clip. */
	void reset(TeacherContext context);

	Decision decide(TeacherInput input);
}
