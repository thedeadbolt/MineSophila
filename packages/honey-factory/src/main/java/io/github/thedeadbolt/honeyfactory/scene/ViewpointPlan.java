package io.github.thedeadbolt.honeyfactory.scene;


/**
 * A camera viewpoint: arena-relative player feet position and Minecraft yaw/pitch in
 * degrees. The eye is the configured eye height above the feet.
 */
public record ViewpointPlan(double x, double y, double z, double yawDegrees, double pitchDegrees) {
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
