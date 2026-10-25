package io.github.thedeadbolt.honeyfactory.control;

/** The body's current yaw and pitch rates, in degrees per tick. */
public record MotorState(double yawRate, double pitchRate) {
	public static final MotorState ZERO = new MotorState(0.0, 0.0);

	/** The rates after one tick and the resulting angles, rounded to the floats Minecraft stores. */
	public record Achieved(MotorState state, float yaw, float pitch) {
	}

	/**
	 * One tick of {@code command} from this state. Each rate moves toward the commanded rate
	 * by at most its acceleration limit and stays within its maximum. Yaw wraps to
	 * [-180, 180); pitch clamps to [-90, 90], and reaching a limit stops the pitch rate.
	 */
	public Achieved apply(MotorCommand command, MotorLimits limits, float yaw, float pitch) {
		double yawRate = slew(this.yawRate, command.turnRate(), limits.maxTurnAccel(), limits.maxTurnRate());
		double pitchRate = slew(this.pitchRate, command.pitchRate(), limits.maxPitchAccel(), limits.maxPitchRate());
		double nextPitch = pitch + pitchRate;
		if (nextPitch > 90.0 || nextPitch < -90.0) {
			nextPitch = Math.clamp(nextPitch, -90.0, 90.0);
			pitchRate = 0.0;
		}

		return new Achieved(new MotorState(yawRate, pitchRate), (float) wrapDegrees(yaw + yawRate), (float) nextPitch);
	}

	private static double slew(double rate, double target, double accel, double max) {
		return Math.clamp(rate + Math.clamp(target - rate, -accel, accel), -max, max);
	}

	private static double wrapDegrees(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}
}
