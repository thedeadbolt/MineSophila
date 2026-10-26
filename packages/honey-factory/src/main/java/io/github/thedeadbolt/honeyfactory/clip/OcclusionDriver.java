package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.teacher.Steering;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;

/**
 * Walks to a waypoint whose straight way puts a blocker between the eye and the honey for a
 * while (see {@link ClipStarts}), with drift, then follows the teacher, which re-acquires the
 * honey once it shows again.
 */
final class OcclusionDriver implements ClipDriver {
	/** How close to the waypoint counts as there, in blocks. */
	static final double ARRIVED = 0.7;

	private final double[] waypoint;
	private final MotorLimits limits;
	private final double eyeHeight;
	private final Drift drift;
	private boolean arrived;

	OcclusionDriver(double[] waypoint, MotorLimits limits, double eyeHeight, Drift drift) {
		this.waypoint = waypoint.clone();
		this.limits = limits;
		this.eyeHeight = eyeHeight;
		this.drift = drift;
	}

	@Override
	public MotorCommand next(int tick, TeacherInput input, Teacher.Decision teacher) {
		if (!this.arrived && Math.hypot(this.waypoint[0] - input.pose().x(), this.waypoint[2] - input.pose().z()) <= ARRIVED) {
			this.arrived = true;
		}

		MotorCommand meant = this.arrived ? teacher.command()
			: Steering.toward(input.pose(), input.senses(), this.waypoint, this.eyeHeight, this.limits, true, false);
		return this.drift.apply(meant);
	}

	@Override
	public boolean drifting() {
		return this.drift.active();
	}
}
