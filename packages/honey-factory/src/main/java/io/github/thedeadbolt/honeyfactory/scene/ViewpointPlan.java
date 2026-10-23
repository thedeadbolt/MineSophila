package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * A camera viewpoint: arena-relative player feet position and Minecraft yaw/pitch in
 * degrees. The eye is the configured eye height above the feet.
 */
public record ViewpointPlan(double x, double y, double z, double yawDegrees, double pitchDegrees) {
	/** The absolute camera pose; angles take the float precision Minecraft stores. */
	public CameraPose pose() {
		double[] feet = feet();
		return new CameraPose(feet[0], feet[1], feet[2], (float) this.yawDegrees, (float) this.pitchDegrees);
	}

	/** This viewpoint turned by {@code degrees} of yaw. */
	public ViewpointPlan turned(double degrees) {
		return new ViewpointPlan(this.x, this.y, this.z, wrapDegrees(this.yawDegrees + degrees), this.pitchDegrees);
	}

	/** {@code degrees} in {@code [-180, 180)}. */
	public static double wrapDegrees(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}

	/** Absolute feet position in the arena dimension. */
	public double[] feet() {
		return new double[] {ArenaLayout.ORIGIN[0] + this.x, ArenaLayout.ORIGIN[1] + this.y, ArenaLayout.ORIGIN[2] + this.z};
	}

	/** A viewpoint at {@code feet} whose eye, {@code eyeHeight} above it, looks at the centre of a block. */
	public static ViewpointPlan lookingAt(double x, double y, double z, double eyeHeight, int targetX, int targetY, int targetZ) {
		double dx = targetX + 0.5 - x;
		double dy = targetY + 0.5 - (y + eyeHeight);
		double dz = targetZ + 0.5 - z;
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return new ViewpointPlan(x, y, z, yaw, pitch);
	}
}
