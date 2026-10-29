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
 *   <li><b>Re-acquire</b> after losing it: go to where it was last seen, for as long as it
 *       knows a way there that keeps getting shorter, then search the area around that spot
 *       for {@code reacquireTicks}; with no known way, or {@code reacquireTicks} without
 *       getting {@value #PROGRESS_BLOCKS} blocks nearer, it gives up;
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
	/** How much shorter the way back must get to count as progress, in blocks. */
	static final double PROGRESS_BLOCKS = 0.5;

	private final int reacquireTicks;
	private final SearchBehaviour search = new SearchBehaviour();
	private final SearchBehaviour local = new SearchBehaviour();
	private @Nullable TeacherContext context;
	/** The tick re-acquiring reached the last sighting and began searching around it, or -1. */
	private int arrivedTick = -1;
	/** Whether re-acquiring gave up on the last sighting, until the honey is seen again. */
	private boolean gaveUp;
	/** The way back's length when it last got shorter or longer by {@link #PROGRESS_BLOCKS}, in blocks, and that tick. */
	private double way = Double.POSITIVE_INFINITY;
	private int wayTick;

	public RealizableTeacher(int reacquireTicks) {
		this.reacquireTicks = reacquireTicks;
	}

	@Override
	public void reset(TeacherContext context) {
		this.context = context;
		this.search.reset();
		this.local.reset();
		this.arrivedTick = -1;
		this.gaveUp = false;
		this.way = Double.POSITIVE_INFINITY;
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
			this.arrivedTick = -1;
			this.gaveUp = false;
			this.way = Double.POSITIVE_INFINITY;
			this.local.reset();
			return TrackBehaviour.decide(input, context);
		}

		int[] last = input.memory().lastSeen();
		if (last != null && reacquiring(input, last, context)) {
			MotorCommand command;
			if (this.arrivedTick >= 0) {
				int[] origin = context.walkable().min();
				command = this.local.decide(input, context, new int[] {last[0] - origin[0], last[1] - origin[1], last[2] - origin[2]}, REACQUIRE_RADIUS);
			} else {
				command = TrackBehaviour.approach(input, last, context);
			}

			return new Decision(command, TeacherMode.REACQUIRE, new FrameRecord.Belief(FrameRecord.Belief.Source.MEMORY, last, observed));
		}

		return new Decision(this.search.decide(input, context, null, 0.0), TeacherMode.SEARCH, new FrameRecord.Belief(FrameRecord.Belief.Source.NONE, null, observed));
	}

	/**
	 * Whether to keep re-acquiring the sighting at {@code last}: on the way there while the
	 * known way has changed by {@value #PROGRESS_BLOCKS} blocks within {@code reacquireTicks}
	 * (or within {@code reacquireTicks} of losing the honey), then for {@code reacquireTicks}
	 * after arriving. Going back is timed by progress, not from the loss, as a clip's script
	 * may have led the body far off meanwhile; a way that gets longer starts the count afresh,
	 * and a body that gets no nearer gives up.
	 */
	private boolean reacquiring(TeacherInput input, int[] last, TeacherContext context) {
		if (this.gaveUp) {
			return false;
		}

		if (this.arrivedTick < 0 && TrackBehaviour.reached(input.pose(), last, context)) {
			this.arrivedTick = input.tick();
		}

		boolean keep;
		if (this.arrivedTick >= 0) {
			keep = input.tick() - this.arrivedTick <= this.reacquireTicks;
		} else {
			// A way that got longer (led off by a script, or a wall found) starts the count afresh.
			double length = TrackBehaviour.wayLength(input, last, context);
			if (length < this.way - PROGRESS_BLOCKS || length > this.way + PROGRESS_BLOCKS && length < Double.POSITIVE_INFINITY) {
				this.way = length;
				this.wayTick = input.tick();
			}

			keep = input.memory().ticksSinceSeen(input.tick()) <= this.reacquireTicks
				|| length < Double.POSITIVE_INFINITY && input.tick() - this.wayTick <= this.reacquireTicks;
		}

		this.gaveUp = !keep;
		return keep;
	}
}
