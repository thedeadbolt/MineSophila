package io.github.thedeadbolt.honeyfactory.contract;

/**
 * World-space camera unit vectors from Minecraft yaw and pitch, in degrees.
 *
 * <p>Yaw 0 faces south (+Z) and increases toward west (-X); positive pitch looks down.
 * The right vector is {@code forward × +Y} normalized and up is {@code right × forward}.
 */
public record CameraBasis(double[] forward, double[] right, double[] up) {
	public static CameraBasis of(double yawDegrees, double pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double[] forward = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
		double[] right = {-Math.cos(yaw), 0.0, -Math.sin(yaw)};
		double[] up = cross(right, forward);
		return new CameraBasis(forward, right, up);
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}
}
