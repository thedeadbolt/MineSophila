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
	/**
	 * One tick's decision.
	 *
	 * @param coverage the share of reachable cells the teacher has seen so far, in [0, 1]
	 */
	record Decision(MotorCommand command, TeacherMode mode, FrameRecord.Belief belief, double coverage) {
	}

	/** Forgets everything for a new clip. */
	void reset(TeacherContext context);

	Decision decide(TeacherInput input);

	/**
	 * A teacher that stands still and knows nothing: a placeholder until the realizable
	 * teacher exists, for development clips only.
	 */
	static Teacher idle() {
		return new Teacher() {
			@Override
			public void reset(TeacherContext context) {
			}

			@Override
			public Decision decide(TeacherInput input) {
				return new Decision(MotorCommand.IDLE, TeacherMode.SEARCH, new FrameRecord.Belief(FrameRecord.Belief.Source.NONE, null, input.tick() - 1), 0.0);
			}
		};
	}
}
