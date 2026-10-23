package io.github.thedeadbolt.honeyfactory.environment;

/** Absolute player feet position in the arena dimension and Minecraft yaw/pitch in degrees. */
public record CameraPose(double x, double y, double z, float yawDegrees, float pitchDegrees) {
	public CameraPose {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yawDegrees) || pitchDegrees < -90.0F || pitchDegrees > 90.0F) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "camera pose needs finite coordinates and pitch within [-90, 90]");
		}
	}
}
