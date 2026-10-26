package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;

/**
 * Walks a straight line that passes the honey at an angle, not towards it: the line leaves
 * the start 20 to 70 degrees off the honey's bearing, chosen so that its closest approach is
 * 2 to 5 blocks where the start allows. The body steers to hold the line and looks level.
 */
final class PassByDriver implements ClipDriver {
	static final double MIN_OFFSET = 20.0;
	static final double MAX_OFFSET = 70.0;
	static final double MIN_PASS = 2.0;
	static final double MAX_PASS = 5.0;
	/** Yaw error below which the body walks at full speed, and above which it stops to turn. */
	private static final double FULL_SPEED_ERROR = 30.0;
	private static final double STOP_ERROR = 90.0;
	private static final double PITCH_GAIN = 0.8;

	private final double heading;
	private final MotorLimits limits;

	PassByDriver(long seed, CameraPose start, int @Nullable [] honey, MotorLimits limits) {
		this.limits = limits;
		SplittableRandom random = new SplittableRandom(seed);
		double pass = MIN_PASS + (MAX_PASS - MIN_PASS) * random.nextDouble();
		double side = random.nextBoolean() ? 1.0 : -1.0;
		if (honey == null) {
			this.heading = start.yawDegrees();
			return;
		}

		double dx = honey[0] + 0.5 - start.x();
		double dz = honey[2] + 0.5 - start.z();
		double bearing = Math.toDegrees(Math.atan2(-dx, dz));
		double distance = Math.hypot(dx, dz);
		double offset = Math.toDegrees(Math.asin(Math.min(1.0, pass / Math.max(distance, 1e-9))));
		this.heading = ClipDriver.wrap(bearing + side * Math.clamp(offset, MIN_OFFSET, MAX_OFFSET));
	}

	/** The line's world yaw. */
	double heading() {
		return this.heading;
	}

	@Override
	public MotorCommand next(int tick, TeacherInput input, Teacher.Decision teacher) {
		double error = ClipDriver.wrap(this.heading - input.pose().yawDegrees());
		double turn = Math.clamp(error, -this.limits.maxTurnRate(), this.limits.maxTurnRate());
		double size = Math.abs(error);
		double forward = size <= FULL_SPEED_ERROR ? 1.0 : size >= STOP_ERROR ? 0.0 : (STOP_ERROR - size) / (STOP_ERROR - FULL_SPEED_ERROR);
		double pitch = Math.clamp(-PITCH_GAIN * input.pose().pitchDegrees(), -this.limits.maxPitchRate(), this.limits.maxPitchRate());
		return new MotorCommand(forward, 0.0, turn, pitch, false, 0);
	}
}
