package io.github.thedeadbolt.honeyfactory.environment;

import io.github.thedeadbolt.honeyfactory.control.MotorLimits;

/**
 * Settings of an environment run: accepted action ranges, the motor limits of control
 * steps, whether movement uses Minecraft's own auto-jump, and whether panoramas are
 * reprojected on the GPU. The user's auto-jump option
 * is set only while a step or a control session runs and restored when it ends, however
 * it ends.
 */
public record EnvironmentSettings(ActionLimits limits, MotorLimits motor, boolean autoJump, boolean gpuReprojection) {
	/** Default limits, with auto-jump assisting movement and panoramas reprojected on the CPU. */
	public static final EnvironmentSettings DEFAULT = new EnvironmentSettings(ActionLimits.DEFAULT, MotorLimits.DEFAULT, true, false);

	public EnvironmentSettings withAutoJump(boolean autoJump) {
		return new EnvironmentSettings(this.limits, this.motor, autoJump, this.gpuReprojection);
	}

	public EnvironmentSettings withLimits(ActionLimits limits) {
		return new EnvironmentSettings(limits, this.motor, this.autoJump, this.gpuReprojection);
	}

	public EnvironmentSettings withMotor(MotorLimits motor) {
		return new EnvironmentSettings(this.limits, motor, this.autoJump, this.gpuReprojection);
	}

	/** Whether panoramas are reprojected on the GPU, falling back to the CPU on any GPU failure (M12-1). */
	public EnvironmentSettings withGpuReprojection(boolean gpuReprojection) {
		return new EnvironmentSettings(this.limits, this.motor, this.autoJump, gpuReprojection);
	}
}
