package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import org.jspecify.annotations.Nullable;

/**
 * The first teacher (M12-2), acting only on what the fly could know:
 *
 * <ul>
 *   <li><b>Track</b> while the honey is seen: turn to it and approach it, around seen
 *       obstacles;
 *   <li><b>Re-acquire</b> for {@code reacquireTicks} after losing it: go to where it was last
 *       seen, then search the area around that spot;
 *   <li><b>Search</b> after that, or if it was never seen: systematic frontier coverage.
 * </ul>
 *
 * Every command passes the {@link EdgeBrake} before it is given, so the teacher never walks
 * off a ledge it has seen nor onto floor it has not. Its decisions depend only on its
 * inputs, so a teacher replays exactly.
 */
public final class RealizableTeacher implements Teacher {
	/** How far around the last sighting re-acquiring searches, in blocks. */
	static final double REACQUIRE_RADIUS = 6.0;

	private final int reacquireTicks;
	private final SearchBehaviour search = new SearchBehaviour();
	private final SearchBehaviour local = new SearchBehaviour();
	private @Nullable TeacherContext context;
	/** Whether re-acquiring reached the last sighting and now searches around it. */
	private boolean arrived;

	public RealizableTeacher(int reacquireTicks) {
		this.reacquireTicks = reacquireTicks;
	}

	@Override
	public void reset(TeacherContext context) {
		this.context = context;
		this.search.reset();
		this.local.reset();
		this.arrived = false;
	}

	@Override
	public Decision decide(TeacherInput input) {
		TeacherContext context = this.context;
		if (context == null) {
			throw new IllegalStateException("reset the teacher with a context first");
		}

		Decision decision = unbraked(input, context);
		return new Decision(EdgeBrake.apply(decision.command(), input, context), decision.mode(), decision.belief());
	}

	private Decision unbraked(TeacherInput input, TeacherContext context) {
		int observed = input.tick() - 1;
		if (input.honeySeen() != null) {
			this.arrived = false;
			this.local.reset();
			return TrackBehaviour.decide(input, context);
		}

		int[] last = input.memory().lastSeen();
		if (last != null && input.memory().ticksSinceSeen(input.tick()) <= this.reacquireTicks) {
			MotorCommand command;
			if (!this.arrived && TrackBehaviour.reached(input.pose(), last, context)) {
				this.arrived = true;
			}

			if (this.arrived) {
				int[] origin = context.walkable().min();
				command = this.local.decide(input, context, new int[] {last[0] - origin[0], last[1] - origin[1], last[2] - origin[2]}, REACQUIRE_RADIUS);
			} else {
				command = TrackBehaviour.approach(input, last, context);
			}

			return new Decision(command, TeacherMode.REACQUIRE, new FrameRecord.Belief(FrameRecord.Belief.Source.MEMORY, last, observed));
		}

		return new Decision(this.search.decide(input, context, null, 0.0), TeacherMode.SEARCH, new FrameRecord.Belief(FrameRecord.Belief.Source.NONE, null, observed));
	}
}
