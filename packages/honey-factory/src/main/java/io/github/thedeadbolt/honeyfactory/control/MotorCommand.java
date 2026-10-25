package io.github.thedeadbolt.honeyfactory.control;

/**
 * One tick's motor command, in the units of the control protocol.
 *
 * @param forward desired forward (positive) or backward speed in [-1, 1], fed to the player's input
 * @param strafe desired sideways speed in [-1, 1], positive to the right
 * @param turnRate desired yaw rate in degrees per tick, positive turning right
 * @param pitchRate desired pitch rate in degrees per tick, positive looking down
 * @param jump whether to press jump this tick
 * @param delayTicks ticks before the command takes effect; only 0 is supported
 */
public record MotorCommand(double forward, double strafe, double turnRate, double pitchRate, boolean jump, int delayTicks) {
	/** No movement, no turning, no jump. */
	public static final MotorCommand IDLE = new MotorCommand(0.0, 0.0, 0.0, 0.0, false, 0);
}
