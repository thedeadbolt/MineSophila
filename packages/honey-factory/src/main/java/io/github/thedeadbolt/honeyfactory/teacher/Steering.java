package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * Turns targets into motor commands within the motor limits. Turning asks for the fastest
 * rate from which the body can still slow to a stop on the target, so it arrives without
 * overshooting; walking slows as the facing error grows and stops beyond a right angle.
 */
public final class Steering {
	/** Facing error below which the body walks at full speed, and above which it only turns. */
	static final double FULL_SPEED_ERROR = 30.0;
	static final double STOP_ERROR = 90.0;

	private Steering() {
	}

	/**
	 * A command that turns towards {@code point} (absolute), walks towards it when
	 * {@code walk}, and looks at it when {@code look}, otherwise level.
	 */
	public static MotorCommand toward(CameraPose pose, BodySenses senses, double[] point, double eyeHeight, MotorLimits limits, boolean walk, boolean look) {
		double dx = point[0] - pose.x();
		double dz = point[2] - pose.z();
		double yawError = wrap(Math.toDegrees(Math.atan2(-dx, dz)) - pose.yawDegrees());
		double pitchTarget = look ? -Math.toDegrees(Math.atan2(point[1] - (pose.y() + eyeHeight), Math.hypot(dx, dz))) : 0.0;
		double turn = rate(yawError, limits.maxTurnAccel(), limits.maxTurnRate());
		double pitch = rate(pitchTarget - pose.pitchDegrees(), limits.maxPitchAccel(), limits.maxPitchRate());
		double size = Math.abs(yawError);
		double forward = !walk ? 0.0 : size <= FULL_SPEED_ERROR ? 1.0 : size >= STOP_ERROR ? 0.0 : (STOP_ERROR - size) / (STOP_ERROR - FULL_SPEED_ERROR);
		return new MotorCommand(forward, 0.0, turn, pitch, false, 0);
	}

	/** The rate from which a body slowing by {@code accel} per tick stops after {@code error} degrees, at most {@code max}. */
	static double rate(double error, double accel, double max) {
		double size = Math.abs(error);
		double stoppable = size <= accel ? size : Math.sqrt(2.0 * accel * size);
		return Math.copySign(Math.min(max, stoppable), error);
	}

	/** Degrees in [-180, 180). */
	static double wrap(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}
}
