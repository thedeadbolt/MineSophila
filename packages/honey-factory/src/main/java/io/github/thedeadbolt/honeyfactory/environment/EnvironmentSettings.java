package io.github.thedeadbolt.honeyfactory.environment;

import io.github.thedeadbolt.honeyfactory.control.MotorLimits;

/**
 * Settings of an environment run: accepted action ranges, the motor limits of control
 * steps, and whether movement uses Minecraft's own auto-jump. The user's auto-jump option
 * is set only while a step or a control session runs and restored when it ends, however
 * it ends.
 */
public record EnvironmentSettings(ActionLimits limits, MotorLimits motor, boolean autoJump) {
	/** Default limits, with auto-jump assisting movement. */
	public static final EnvironmentSettings DEFAULT = new EnvironmentSettings(ActionLimits.DEFAULT, MotorLimits.DEFAULT, true);

	public EnvironmentSettings withAutoJump(boolean autoJump) {
		return new EnvironmentSettings(this.limits, this.motor, autoJump);
	}

	public EnvironmentSettings withLimits(ActionLimits limits) {
		return new EnvironmentSettings(limits, this.motor, this.autoJump);
	}

	public EnvironmentSettings withMotor(MotorLimits motor) {
		return new EnvironmentSettings(this.limits, motor, this.autoJump);
	}
}
