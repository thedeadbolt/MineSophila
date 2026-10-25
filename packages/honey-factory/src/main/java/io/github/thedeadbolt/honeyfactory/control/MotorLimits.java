package io.github.thedeadbolt.honeyfactory.control;

/**
 * How fast the body may turn and look, in degrees per tick, and how fast those rates may
 * change, in degrees per tick per tick. Walking keeps Minecraft's own acceleration.
 */
public record MotorLimits(double maxTurnRate, double maxTurnAccel, double maxPitchRate, double maxPitchAccel) {
	/** 180 degrees per second of turning and 120 of looking, each reaching full rate in 5 ticks. */
	public static final MotorLimits DEFAULT = new MotorLimits(9.0, 1.8, 6.0, 1.2);

	public MotorLimits {
		if (!(maxTurnRate > 0.0) || !(maxTurnAccel > 0.0) || !(maxPitchRate > 0.0) || !(maxPitchAccel > 0.0)
			|| !Double.isFinite(maxTurnRate + maxTurnAccel + maxPitchRate + maxPitchAccel)) {
			throw new IllegalArgumentException("Motor limits must be positive and finite");
		}
	}

	/**
	 * Checks a command against these limits.
	 *
	 * @throws IllegalArgumentException naming the first field outside its range
	 */
	public void check(MotorCommand command) {
		require("forward", command.forward(), 1.0);
		require("strafe", command.strafe(), 1.0);
		require("turn_rate", command.turnRate(), this.maxTurnRate);
		require("pitch_rate", command.pitchRate(), this.maxPitchRate);
		if (command.delayTicks() != 0) {
			throw new IllegalArgumentException("delay_ticks must be 0, got " + command.delayTicks());
		}
	}

	private static void require(String field, double value, double limit) {
		if (!Double.isFinite(value) || Math.abs(value) > limit) {
			throw new IllegalArgumentException(field + " must lie within [-" + limit + ", " + limit + "], got " + value);
		}
	}
}
